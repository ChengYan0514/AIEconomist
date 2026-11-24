"""Paperflow adapter: original algorithms, isolated paths, verified checkpoints, no DB access."""
from __future__ import annotations
import argparse
import copy
import csv
import hashlib
import importlib
import json
import os
from pathlib import Path
import runpy
import shutil
import sys
import threading
from types import SimpleNamespace

ENGINE = Path(__file__).resolve().parent
sys.path.insert(0, str(ENGINE / 'scripts'))


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for chunk in iter(lambda: f.read(65536), b''): h.update(chunk)
    return h.hexdigest()


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True), encoding='utf-8')
    tmp.replace(path)


class CallBudget:
    def __init__(self, maximum, root):
        self.maximum, self.root, self.calls, self.tokens = maximum, Path(root), 0, 0
        self.models = set()
        self.lock = threading.Lock()
        self.exhausted = False
        atomic_json(self.root / 'usage.json', {'calls': 0, 'tokens': 0})

    def wrap(self, client, chat_extra_body=None):
        def wrap_create(create, extra_body=None):
            def counted(**kw):
                if extra_body:
                    kw['extra_body'] = {**extra_body, **(kw.get('extra_body') or {})}
                with self.lock:
                    if self.calls >= self.maximum:
                        self.exhausted = True
                        raise RuntimeError('MODEL_CALL_BUDGET_EXCEEDED')
                    self.calls += 1
                    atomic_json(self.root / 'usage.json', {'calls': self.calls, 'tokens': self.tokens, 'modelVersions': sorted(self.models)})
                result = create(**kw)
                with self.lock:
                    model = getattr(result, 'model', None)
                    if model: self.models.add(model)
                    self.tokens += int(getattr(getattr(result, 'usage', None), 'total_tokens', 0) or 0)
                    atomic_json(self.root / 'usage.json', {'calls': self.calls, 'tokens': self.tokens, 'modelVersions': sorted(self.models)})
                return result
            return counted
        return SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=wrap_create(client.chat.completions.create, chat_extra_body))),
                               embeddings=SimpleNamespace(create=wrap_create(client.embeddings.create)))


def prepare_config(source, root):
    import yaml
    cfg = copy.deepcopy(yaml.safe_load(Path(source).read_text(encoding='utf-8')))
    for kind, model in cfg['models'].items():
        model.pop('api_key', None)
        model['api_key_env'] = 'CAUSAL_CHAT_API_KEY' if kind == 'chat' else 'CAUSAL_EMBEDDING_API_KEY'
        model['sdk_max_retries'] = 0
        model['request_timeout'] = float(model.get('request_timeout', 600))
    cfg['io']['project_root'] = str(root)
    cfg['deal_with'].update(input_dir=str(root/'raw'), output_dir=str(root/'cleaned'), delete_dir=str(root/'cleaned/delete'))
    cfg['deal_delete'].update(input_dir=str(root/'raw'), delete_file=str(root/'cleaned/delete/delete_no_title.csv'),
        output_csv=str(root/'cleaned/delete/cleaned/delete_no_title.csv'), txt_output_dir=str(root/'stage1_input/delete_no_title'))
    cfg['stage0_prepare'].update(input_dir=str(root/'cleaned'), output_dir=str(root/'stage0_input'))
    cfg['stage0'].update(input_json_path=str(root/'stage0_input/block_batch_input/*.json'), output_dir=str(root/'stage0_output'))
    cfg['stage1_prepare'].update(stage0_outputs_path=str(root/'stage0_output/stage0_outputs.jsonl'),
        csv_path=str(root/'cleaned/block_batch_input.csv'), output_base=str(root/'stage1_input'))
    cfg['stage1'].update(input_dir=str(root/'stage1_input'), output_dir=str(root/'stage1_output'))
    cfg['stage0_34'].update(input_jsonl=str(root/'stage1_output/stage1_outputs.jsonl'), output_csv=str(root/'final_claims.csv'))
    cfg['map'].update(causal_data_path=str(root/'final_claims.csv'), standard_terms_path=str(ENGINE/'scripts/stw_labels.csv'), output_path=str(root/'mapped_results.csv'))
    for stage in ['stage0', 'stage1']:
        for key in ['system_prompt_file', 'response_schema_file']:
            cfg[stage][key] = str(ENGINE / cfg[stage][key])
    return cfg


def execution_hash(source, mode):
    import yaml
    cfg = yaml.safe_load(Path(source).read_text(encoding='utf-8'))
    for model in cfg.get('models', {}).values(): model.pop('api_key', None)
    files = [*sorted((ENGINE/'scripts').glob('*.py')), *sorted((ENGINE/'prompts').glob('*')),
             *sorted((ENGINE/'schemas').glob('*')), ENGINE/'scripts/stw_labels.csv', Path(__file__).resolve()]
    version = {'config': cfg, 'mode': mode, 'files': {str(p.relative_to(ENGINE)): sha(p) for p in files if p.is_file()}}
    return hashlib.sha256(json.dumps(version, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def validate_responses(root, stage):
    raw = root/f'{stage}_output/{stage}_raw_responses.jsonl'
    requests = root/f'{stage}_output/{stage}_requests.jsonl'
    rows = [json.loads(s) for s in raw.read_text().splitlines() if s.strip()]
    reqs = [json.loads(s) for s in requests.read_text().splitlines() if s.strip()]
    if len(rows) != len(reqs) or {r['custom_id'] for r in rows} != {r['custom_id'] for r in reqs}:
        raise RuntimeError(f'{stage}: incomplete model responses')
    if any(row.get('status') != 'ok' for row in rows):
        raise RuntimeError(f'{stage}: model request failed; see raw responses')
    for row in rows:
        from common import parse_json_content
        value = parse_json_content(row['response_content'])
        if stage == 'stage0' and not isinstance(value, list): raise ValueError('Invalid Stage 0 payload')
        if stage == 'stage1' and (not isinstance(value, dict) or not isinstance(value.get('edges'), list)):
            raise ValueError('Invalid Stage 1 payload')


def publication_rows(path, work, mode):
    from insert_data.pipeline_db import clean_text, parse_bool, parse_float, build_claim_hash
    rows, seen, collisions = [], set(), 0
    with Path(path).open(encoding='utf-8-sig', newline='') as f:
        for raw in csv.DictReader(f):
            if clean_text(raw.get('paper_id')) != work: raise ValueError('Result Work is outside task scope')
            cause, effect = clean_text(raw.get('cause')), clean_text(raw.get('effect'))
            if not cause or not effect: raise ValueError('Missing cause or effect')
            cc = clean_text(raw.get('cause_standard')) if mode == 'STANDARDIZED' else cause
            ce = clean_text(raw.get('effect_standard')) if mode == 'STANDARDIZED' else effect
            if not cc or not ce: raise ValueError('Missing standard variables')
            row = {k: clean_text(v) for k, v in raw.items()}
            row.update(claim=row.get('claim') or f'{cause} -> {effect}', claim_cause=cc, claim_effect=ce,
                claim_hash=build_claim_hash(cc, ce), is_main_contribution=parse_bool(raw.get('is_main_contribution')),
                cause_score=parse_float(raw.get('cause_score')), effect_score=parse_float(raw.get('effect_score')))
            key = (work, cause, effect)
            collisions += key in seen
            seen.add(key)
            rows.append(row)
    return rows, collisions


def execute(source, root, work, mode='PR_COMPAT', max_calls=2000, client_factory=None):
    import yaml
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    if not work or any(ch not in 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:-' for ch in work):
        raise ValueError('Unsafe Work ID')
    # Establish zero usage before configuration/import work: failures here cannot
    # have reached a model, and Java can safely release the unused reservation.
    budget = CallBudget(max_calls, root)
    fingerprint = execution_hash(source, mode)
    profile = root/'profile.json'
    input_hash = sha(root/'input.csv')
    if profile.exists():
        previous = json.loads(profile.read_text())
        if previous['executionHash'] != fingerprint: raise RuntimeError('CONFIG_CHANGED: create a new task')
        if previous.get('inputCsvHash') != input_hash: raise RuntimeError('INPUT_CHANGED: create a new task')
    atomic_json(profile, {'executionHash': fingerprint, 'inputCsvHash': input_hash, 'algorithmRevision': '6f822017cb513604c4a9b778b13f75697bafead2'})
    cfg = prepare_config(source, root)
    config = root/'engine.yaml'
    config.write_text(yaml.safe_dump(cfg, allow_unicode=True), encoding='utf-8')
    import common
    original_factory = common.get_openai_client
    factory = client_factory or original_factory
    common.get_openai_client = lambda config=None, model_type='chat': budget.wrap(factory(config, model_type),
        (config or {}).get('models', {}).get(model_type, {}).get('extra_body') if model_type == 'chat' else None)
    # Import after wrapping: original modules bind the same budgeted factory.
    modules = {name: importlib.import_module(name) for name in ['deal_with', 'stage0_1', 'run_stage0', 'stage0_2', 'run_stage1', 'stage0_34', 'map']}
    for module in modules.values():
        if hasattr(module, 'get_openai_client'): module.get_openai_client = common.get_openai_client
    def step(name, action):
        atomic_json(root/'progress.json', {'phase': name})
        checkpoint = root/'.checkpoints'/f'{name}.json'
        if checkpoint.exists():
            record = json.loads(checkpoint.read_text())
            if record['executionHash'] == fingerprint and all((root/p).is_file() and sha(root/p) == h for p,h in record['files'].items()): return
            raise RuntimeError(f'CHECKPOINT_CHANGED: {name}')
        try:
            action()
        except Exception:
            if budget.exhausted: raise RuntimeError("MODEL_CALL_BUDGET_EXCEEDED")
            raise
        if budget.exhausted: raise RuntimeError('MODEL_CALL_BUDGET_EXCEEDED')
        owned_outputs = {
            'PREPARE': ['raw', 'cleaned', 'stage0_input'], 'STAGE0': ['stage0_output'],
            'STAGE1': ['stage1_input', 'stage1_output'], 'NORMALIZE': ['final_claims.csv'], 'MAP': ['mapped_results.csv'],
        }
        files = {}
        for relative in owned_outputs[name]:
            path = root/relative
            for output in ([path] if path.is_file() else path.rglob('*')):
                if output.is_file(): files[str(output.relative_to(root))] = sha(output)
        atomic_json(checkpoint, {'executionHash': fingerprint, 'files': files})
    try:
        def prepare():
            raw = root/'raw'; raw.mkdir(exist_ok=True)
            shutil.copyfile(root/'input.csv', raw/'block_batch_input.csv')
            modules['deal_with'].run(str(raw), str(root/'cleaned'), str(root/'cleaned/delete'))
            modules['stage0_1'].run(str(root/'cleaned'), str(root/'stage0_input'))
        step('PREPARE', prepare)
        def stage0():
            if list((root/'stage0_input').rglob('*.json')):
                modules['run_stage0'].run(config)
                validate_responses(root, 'stage0')
            else:
                out=root/'stage0_output'; out.mkdir(exist_ok=True)
                for name in ['stage0_requests.jsonl','stage0_raw_responses.jsonl','stage0_outputs.jsonl']: (out/name).write_text('')
        step('STAGE0', stage0)
        def stage1():
            sc=cfg['stage1_prepare']
            modules['stage0_2'].run(sc['stage0_outputs_path'], sc['csv_path'], sc['output_base'], sc.get('max_papers',15000))
            # Keep the original no-title branch; only replace its process arguments.
            argv=sys.argv[:]
            try:
                sys.argv=[str(ENGINE/'scripts/deal_delete.py'),'--config',str(config)]
                try: runpy.run_path(str(ENGINE/'scripts/deal_delete.py'), run_name='__main__')
                except SystemExit as e:
                    if e.code not in [0,None]: raise
            finally: sys.argv=argv
            texts=list((root/'stage1_input').rglob('*.txt'))
            if not texts or any(p.stem!=work for p in texts): raise ValueError('No valid Stage 1 text or out-of-scope input')
            modules['run_stage1'].run(config)
            validate_responses(root,'stage1')
        step('STAGE1', stage1)
        def normalize():
            data=[json.loads(s) for s in (root/'stage1_output/stage1_outputs.jsonl').read_text().splitlines() if s.strip()]
            if any(r.get('edges') for r in data):
                stage=cfg['stage0_34'];kind=cfg['pipeline']['stage0_34']['model_type']
                modules['stage0_34'].run_stage0_34(stage['input_jsonl'],stage['output_csv'],stage,common.get_openai_client(cfg,kind),cfg['models'][kind]['model'])
            else:
                columns=['paper_id','claim','cause','effect','type_of_relationship','sign_of_impact','is_main_contribution','statistical_significance','causal_inference_method','sources_of_exogenous_variation','level_of_tentativeness','evidence_method_other_description','evidence']
                with (root/'final_claims.csv').open('w',newline='') as f: csv.writer(f).writerow(columns)
        step('NORMALIZE',normalize)
        def mapping():
            import pandas as pd
            df=pd.read_csv(root/'final_claims.csv')
            if df.empty:
                for col in ['cause_standard','cause_score','effect_standard','effect_score']:df[col]=[]
                df.to_csv(root/'mapped_results.csv',index=False)
            else: modules['map'].run(config)
        step('MAP',mapping)
        chosen=root/('mapped_results.csv' if mode=='STANDARDIZED' else 'final_claims.csv')
        rows, collisions=publication_rows(chosen,work,mode)
        usage=json.loads((root/'usage.json').read_text())
        result={'workId':work,'rows':rows,'collisionCount':collisions,'claimCount':len(rows),'executionHash':fingerprint,**usage}
        atomic_json(root/'result.json',result)
        atomic_json(root/'progress.json',{'phase':'VALIDATE'})
        return result
    finally:
        common.get_openai_client=original_factory


def main():
    p=argparse.ArgumentParser();p.add_argument('--config',required=True);p.add_argument('--directory',required=True)
    p.add_argument('--work-id',required=True);p.add_argument('--mode',choices=['PR_COMPAT','STANDARDIZED'],default='PR_COMPAT')
    p.add_argument('--max-calls',type=int,default=2000);a=p.parse_args()
    try: execute(a.config,a.directory,a.work_id,a.mode,a.max_calls)
    except Exception as error:
        atomic_json(Path(a.directory)/'error.json',{'errorType':type(error).__name__,'message':str(error)[:500]})
        raise

if __name__=='__main__':main()

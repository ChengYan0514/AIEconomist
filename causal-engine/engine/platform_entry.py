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
sys.path.insert(0, str(ENGINE))
sys.path.insert(0, str(ENGINE / 'scripts'))
from provider_compat import (  # noqa: E402
    ChatCompatAdapter,
    EmbeddingCompatAdapter,
    resolve_chat_compat,
    resolve_embedding_compat,
)

STALE_RESUME_ITEMS = (
    '.checkpoints', 'profile.json', 'raw', 'cleaned',
    'stage0_input', 'stage0_output', 'stage1_input', 'stage1_output',
    'final_claims.csv', 'mapped_results.csv', 'result.json',
)


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


def clean_text(value):
    if value is None:
        return None
    text = str(value).strip()
    return None if text == '' else text


def parse_bool(value):
    text = clean_text(value)
    if text is None:
        return None
    low = text.lower()
    if low in {'true', 't', '1', 'yes', 'y'}:
        return True
    if low in {'false', 'f', '0', 'no', 'n'}:
        return False
    return None


def parse_float(value):
    text = clean_text(value)
    if text is None:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def build_claim_hash(cause_value: str, effect_value: str) -> str:
    normalized = f'{cause_value.strip().lower()}||{effect_value.strip().lower()}'
    return hashlib.md5(normalized.encode('utf-8')).hexdigest()


def _clamp(value: str | None, limit: int) -> str | None:
    if value is None:
        return None
    return value[:limit] if len(value) > limit else value


class CallBudget:
    def __init__(self, maximum, root):
        self.maximum, self.root, self.calls, self.tokens = maximum, Path(root), 0, 0
        self.models = set()
        self.lock = threading.Lock()
        self.exhausted = False
        atomic_json(self.root / 'usage.json', {'calls': 0, 'tokens': 0})

    def wrap(self, client, chat_adapter=None, embedding_adapter=None):
        def wrap_counted(create):
            def counted(**kw):
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

        counted_chat = wrap_counted(client.chat.completions.create)
        counted_emb = wrap_counted(client.embeddings.create)

        def adapted_chat(**kw):
            if chat_adapter is None:
                return counted_chat(**kw)
            for _ in range(5):
                prepared, expect_json, expect_array = chat_adapter.prepare(kw)
                try:
                    result = counted_chat(**prepared)
                    return chat_adapter.normalize_response(result, expect_json, expect_array)
                except Exception as exc:
                    if chat_adapter.downgrade_on_error(exc, prepared):
                        continue
                    raise
            prepared, expect_json, expect_array = chat_adapter.prepare(kw)
            return chat_adapter.normalize_response(counted_chat(**prepared), expect_json, expect_array)

        def adapted_emb(**kw):
            if embedding_adapter is None:
                return counted_emb(**kw)
            return embedding_adapter.invoke(counted_emb, kw)

        return SimpleNamespace(
            chat=SimpleNamespace(completions=SimpleNamespace(create=adapted_chat)),
            embeddings=SimpleNamespace(create=adapted_emb),
        )


def prepare_config(source, root):
    import yaml
    cfg = copy.deepcopy(yaml.safe_load(Path(source).read_text(encoding='utf-8')))
    for kind, model in cfg['models'].items():
        model.pop('api_key', None)
        model['api_key_env'] = 'CAUSAL_CHAT_API_KEY' if kind == 'chat' else 'CAUSAL_EMBEDDING_API_KEY'
        model['sdk_max_retries'] = 0
        model['request_timeout'] = float(model.get('request_timeout', 600))
    chat_cfg = cfg['models'].setdefault('chat', {})
    if 'compat' not in chat_cfg:
        chat_cfg['compat'] = resolve_chat_compat(str(chat_cfg.get('base_url') or ''), str(chat_cfg.get('model') or ''))
    emb_cfg = cfg['models'].setdefault('embedding', {})
    if 'compat' not in emb_cfg:
        emb_cfg['compat'] = resolve_embedding_compat(str(emb_cfg.get('base_url') or ''), str(emb_cfg.get('model') or ''))
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
    emb_batch_cap = emb_cfg.get('compat', {}).get('max_batch_size')
    if emb_batch_cap and int(cfg['map'].get('batch_size', 20)) > int(emb_batch_cap):
        cfg['map']['batch_size'] = int(emb_batch_cap)
    for stage in ['stage0', 'stage1']:
        for key in ['system_prompt_file', 'response_schema_file']:
            cfg[stage][key] = str(ENGINE / cfg[stage][key])
    return cfg


def execution_hash(source, mode):
    import yaml
    cfg = yaml.safe_load(Path(source).read_text(encoding='utf-8'))
    for model in cfg.get('models', {}).values(): model.pop('api_key', None)
    files = [*sorted((ENGINE/'scripts').glob('*.py')), *sorted((ENGINE/'prompts').glob('*')),
             *sorted((ENGINE/'schemas').glob('*')), ENGINE/'scripts/stw_labels.csv',
             ENGINE/'provider_compat.py', Path(__file__).resolve()]
    version = {'config': cfg, 'mode': mode, 'files': {str(p.relative_to(ENGINE)): sha(p) for p in files if p.is_file()}}
    return hashlib.sha256(json.dumps(version, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def validate_responses(root, stage):
    raw = root/f'{stage}_output/{stage}_raw_responses.jsonl'
    requests = root/f'{stage}_output/{stage}_requests.jsonl'
    rows = [json.loads(s) for s in raw.read_text().splitlines() if s.strip()]
    reqs = [json.loads(s) for s in requests.read_text().splitlines() if s.strip()]
    if len(rows) != len(reqs) or {r['custom_id'] for r in rows} != {r['custom_id'] for r in reqs}:
        raise RuntimeError(f'{stage}: incomplete model responses')
    failed = [row for row in rows if row.get('status') != 'ok']
    if failed:
        detail = str(failed[0].get('parse_error') or '').strip()
        suffix = f' ({detail[:240]})' if detail else ''
        raise RuntimeError(f'{stage}: model request failed{suffix}; see raw responses')
    for row in rows:
        from common import parse_json_content
        value = parse_json_content(row['response_content'])
        if stage == 'stage0' and not isinstance(value, list): raise ValueError('Invalid Stage 0 payload')
        if stage == 'stage1' and (not isinstance(value, dict) or not isinstance(value.get('edges'), list)):
            raise ValueError('Invalid Stage 1 payload')


def publication_rows(path, work, mode):
    rows, seen, collisions = [], set(), 0
    with Path(path).open(encoding='utf-8-sig', newline='') as f:
        for raw in csv.DictReader(f):
            if clean_text(raw.get('paper_id')) != work: raise ValueError('Result Work is outside task scope')
            cause, effect = clean_text(raw.get('cause')), clean_text(raw.get('effect'))
            if not cause or not effect: raise ValueError('Missing cause or effect')
            cc = _clamp(clean_text(raw.get('cause_standard')) if mode == 'STANDARDIZED' else cause, 255)
            ce = _clamp(clean_text(raw.get('effect_standard')) if mode == 'STANDARDIZED' else effect, 255)
            if not cc or not ce: raise ValueError('Missing standard variables')
            row = {k: clean_text(v) for k, v in raw.items()}
            claim_text = row.get('claim') or f'{cause} -> {effect}'
            row.update(
                paper_id=work,
                claim=claim_text,
                cause=cause,
                effect=effect,
                claim_cause=cc,
                claim_effect=ce,
                claim_hash=build_claim_hash(cc, ce),
                sign_of_impact=_clamp(row.get('sign_of_impact') or 'ambiguous', 50),
                type_of_relationship=_clamp(row.get('type_of_relationship') or 'other', 100),
                causal_inference_method=_clamp(row.get('causal_inference_method') or 'Do not know', 100),
                evidence=row.get('evidence') or claim_text,
                level_of_tentativeness=_clamp(row.get('level_of_tentativeness'), 50),
                statistical_significance=_clamp(row.get('statistical_significance'), 100),
                is_main_contribution=bool(parse_bool(raw.get('is_main_contribution'))),
                cause_score=parse_float(raw.get('cause_score')),
                effect_score=parse_float(raw.get('effect_score')),
            )
            key = (work, cause, effect)
            collisions += key in seen
            seen.add(key)
            rows.append(row)
    return rows, collisions


def _clear_stale_resume(root: Path) -> None:
    for item in STALE_RESUME_ITEMS:
        target = root / item
        if target.is_dir():
            shutil.rmtree(target, ignore_errors=True)
        elif target.is_file():
            target.unlink(missing_ok=True)


def _install_stw_embedding_cache(map_module, cfg: dict, root: Path):
    import numpy as np
    import pandas as pd

    original_get_embeddings = map_module.get_embeddings
    stw_path = Path(cfg['map']['standard_terms_path'])
    emb_cfg = cfg.get('models', {}).get(cfg.get('pipeline', {}).get('map', {}).get('model_type', 'embedding'), {})
    cache_key_payload = json.dumps({
        'stw_sha256': sha(stw_path),
        'base_url': str(emb_cfg.get('base_url') or '').strip().rstrip('/'),
        'model': str(emb_cfg.get('model') or '').strip(),
    }, sort_keys=True)
    cache_key = hashlib.sha256(cache_key_payload.encode('utf-8')).hexdigest()[:24]
    cache_dir = root.parent.parent / 'stw_cache' if root.parent.name == 'runs' else root / '.stw_cache'
    cache_file = cache_dir / f'{cache_key}.npy'
    std_labels = pd.read_csv(stw_path)['label_en'].dropna().unique().tolist()

    def cached_get_embeddings(client, texts, model_name, max_retries, batch_size):
        if isinstance(texts, list) and len(texts) == len(std_labels) and texts == std_labels:
            if cache_file.is_file():
                try:
                    cached = np.load(cache_file)
                    if cached.ndim == 2 and cached.shape[0] == len(std_labels):
                        print(f'[MAP] Loaded cached STW embeddings ({cached.shape[0]}x{cached.shape[1]}) from {cache_file.name}')
                        return cached.astype(np.float32, copy=False)
                except Exception as exc:
                    print(f'[MAP] Ignoring invalid STW cache: {exc}')
            vectors = original_get_embeddings(client, texts, model_name, max_retries, batch_size)
            if vectors is not None and len(vectors) == len(std_labels):
                try:
                    cache_dir.mkdir(parents=True, exist_ok=True)
                    tmp_path = cache_dir / f'.{cache_key}.{os.getpid()}.tmp.npy'
                    np.save(tmp_path, vectors.astype(np.float32, copy=False))
                    tmp_path.replace(cache_file)
                    print(f'[MAP] Cached STW embeddings to {cache_file.name}')
                except Exception as exc:
                    print(f'[MAP] Failed to persist STW cache: {exc}')
            return vectors
        return original_get_embeddings(client, texts, model_name, max_retries, batch_size)

    map_module.get_embeddings = cached_get_embeddings
    return original_get_embeddings


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
        if previous.get('executionHash') != fingerprint or previous.get('inputCsvHash') != input_hash:
            print('[ENGINE] Resumed checkpoints invalidated by updated config/input; restarting cleanly')
            _clear_stale_resume(root)
    atomic_json(profile, {'executionHash': fingerprint, 'inputCsvHash': input_hash, 'algorithmRevision': '6f822017cb513604c4a9b778b13f75697bafead2'})
    cfg = prepare_config(source, root)
    config = root/'engine.yaml'
    config.write_text(yaml.safe_dump(cfg, allow_unicode=True), encoding='utf-8')
    import common
    original_factory = common.get_openai_client
    factory = client_factory or original_factory
    chat_adapter = ChatCompatAdapter(
        cfg.get('models', {}).get('chat', {}).get('compat'),
        cfg.get('models', {}).get('chat', {}).get('extra_body'),
    )
    embedding_adapter = EmbeddingCompatAdapter(
        cfg.get('models', {}).get('embedding', {}).get('compat'),
    )
    common.get_openai_client = lambda config=None, model_type='chat': budget.wrap(
        factory(config, model_type),
        chat_adapter=chat_adapter if model_type == 'chat' else None,
        embedding_adapter=embedding_adapter if model_type == 'embedding' else None,
    )
    # Import after wrapping: original modules bind the same budgeted factory.
    module_names = ['deal_with', 'stage0_1', 'run_stage0', 'stage0_2', 'run_stage1', 'stage0_34']
    if mode == 'STANDARDIZED':
        module_names.append('map')
    modules = {name: importlib.import_module(name) for name in module_names}
    for module in modules.values():
        if hasattr(module, 'get_openai_client'): module.get_openai_client = common.get_openai_client
    original_map_embed = _install_stw_embedding_cache(modules['map'], cfg, root) if 'map' in modules else None
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
            if mode != 'STANDARDIZED' or df.empty:
                for col in ['cause_standard','cause_score','effect_standard','effect_score']:
                    if col not in df.columns: df[col] = ''
                df.to_csv(root/'mapped_results.csv',index=False)
            else:
                modules['map'].run(config)
        step('MAP',mapping)
        chosen=root/('mapped_results.csv' if mode=='STANDARDIZED' else 'final_claims.csv')
        rows, collisions=publication_rows(chosen,work,mode)
        usage=json.loads((root/'usage.json').read_text())
        result={'workId':work,'rows':rows,'collisionCount':collisions,'claimCount':len(rows),'executionHash':fingerprint,**usage}
        atomic_json(root/'result.json',result)
        atomic_json(root/'progress.json',{'phase':'VALIDATE'})
        return result
    finally:
        if 'map' in modules and original_map_embed is not None:
            modules['map'].get_embeddings = original_map_embed
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

import json
import sys
import tempfile
import time
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app as engine_app  # noqa: E402


class CausalEngineApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.work_dir = Path(self.tmp.name)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_health_submit_poll_and_allowlisted_artifacts(self) -> None:
        def fake_exec(engine_run_id, run_dir: Path, cfg_path, work_id, mode, max_calls):
            (run_dir / "progress.json").write_text(json.dumps({"phase": "MAP"}), encoding="utf-8")
            (run_dir / "usage.json").write_text(
                json.dumps({"calls": 3, "tokens": 1200, "modelVersions": ["qwen-test"]}), encoding="utf-8"
            )
            (run_dir / "stage1_output").mkdir(parents=True, exist_ok=True)
            (run_dir / "stage1_output" / "stage1_raw_responses.jsonl").write_text('{"status":"ok"}\n', encoding="utf-8")
            (run_dir / "worker.log").write_text("ok log\n", encoding="utf-8")
            (run_dir / "result.json").write_text(
                json.dumps({"workId": work_id, "rows": [], "claimCount": 0, "collisionCount": 0, "calls": 3}),
                encoding="utf-8",
            )
            return 0

        mgr = engine_app.RunManager(self.work_dir, executor_fn=fake_exec)
        client = TestClient(engine_app.create_app(mgr))

        health = client.get("/v1/health")
        self.assertEqual(health.status_code, 200)
        self.assertEqual(health.json()["status"], "ok")

        created = client.post(
            "/v1/runs",
            json={
                "runId": "run-1",
                "workId": "W123",
                "ingestionMode": "PR_COMPAT",
                "maxCalls": 100,
                "blocks": [
                    {"blockId": "b1", "blockType": "title", "blockText": "Intro", "titleLevel": 1, "blockSeq": 0},
                    {"blockId": "b2", "blockType": "text", "blockText": "Body text", "titleLevel": None, "blockSeq": 1},
                ],
            },
        )
        self.assertEqual(created.status_code, 202)
        engine_id = created.json()["engineRunId"]

        status = None
        for _ in range(40):
            status = client.get(f"/v1/runs/{engine_id}").json()
            if status["status"] == "SUCCEEDED":
                break
            time.sleep(0.05)
        self.assertEqual(status["status"], "SUCCEEDED")
        self.assertEqual(status["result"]["workId"], "W123")

        artifacts = client.get(f"/v1/runs/{engine_id}/artifacts").json()
        names = {a["name"] for a in artifacts}
        self.assertIn("input.csv", names)
        self.assertIn("stage1_raw_responses.jsonl", names)
        self.assertIn("result.json", names)

        raw_resp = client.get(f"/v1/runs/{engine_id}/artifacts/stage1_raw_responses.jsonl")
        self.assertEqual(raw_resp.status_code, 200)
        self.assertIn('"status":"ok"', raw_resp.text)

        blocked = client.get(f"/v1/runs/{engine_id}/artifacts/runtime_config.yaml")
        self.assertEqual(blocked.status_code, 400)

    def test_resume_copies_checkpoints_and_cancel_marks_cancelled(self) -> None:
        copied_flag = {"found": False}

        def fake_exec(engine_run_id, run_dir: Path, cfg_path, work_id, mode, max_calls):
            ckpt = run_dir / ".checkpoints" / "STAGE0.json"
            if ckpt.is_file():
                copied_flag["found"] = True
            else:
                ckpt.parent.mkdir(parents=True, exist_ok=True)
                ckpt.write_text('{"executionHash":"abc"}', encoding="utf-8")
                (run_dir / "error.json").write_text('{"message":"MODEL_CALL_BUDGET_EXCEEDED"}', encoding="utf-8")
                return 1
            (run_dir / "result.json").write_text(json.dumps({"workId": work_id, "rows": [], "claimCount": 0}), encoding="utf-8")
            return 0

        mgr = engine_app.RunManager(self.work_dir, executor_fn=fake_exec)
        client = TestClient(engine_app.create_app(mgr))

        first = client.post(
            "/v1/runs",
            json={"runId": "r1", "workId": "W9", "blocks": [{"blockId": "b1", "blockType": "text", "blockText": "x", "blockSeq": 0}]},
        ).json()["engineRunId"]
        for _ in range(40):
            st = client.get(f"/v1/runs/{first}").json()
            if st["status"] == "FAILED":
                break
            time.sleep(0.05)
        self.assertEqual(st["errorCode"], "MODEL_CALL_BUDGET_EXCEEDED")

        second = client.post(
            "/v1/runs",
            json={
                "runId": "r1",
                "workId": "W9",
                "resumeEngineRunId": first,
                "blocks": [{"blockId": "b1", "blockType": "text", "blockText": "x", "blockSeq": 0}],
            },
        ).json()["engineRunId"]
        for _ in range(40):
            st2 = client.get(f"/v1/runs/{second}").json()
            if st2["status"] == "SUCCEEDED":
                break
            time.sleep(0.05)
        self.assertEqual(st2["status"], "SUCCEEDED")
        self.assertTrue(copied_flag["found"])

    def test_per_run_model_override_keyless_local_and_thinking_styles(self) -> None:
        import yaml

        # Auto-detect thinking parameter styles across providers
        self.assertEqual(
            engine_app.resolve_thinking_extra_body("https://api.deepseek.com/v1", "deepseek-chat", False, "AUTO"),
            {"thinking": {"type": "disabled"}},
        )
        self.assertEqual(
            engine_app.resolve_thinking_extra_body("https://api.deepseek.com/v1", "deepseek-chat", True, "AUTO"),
            {"thinking": {"type": "enabled"}},
        )
        self.assertEqual(
            engine_app.resolve_thinking_extra_body("https://api.siliconflow.cn/v1", "Qwen/Qwen3-32B", False, "AUTO"),
            {"enable_thinking": False},
        )
        self.assertEqual(
            engine_app.resolve_thinking_extra_body("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-max", True, "AUTO"),
            {"enable_thinking": True},
        )
        self.assertEqual(
            engine_app.resolve_thinking_extra_body("http://127.0.0.1:8000/v1", "Qwen3-14B", False, "AUTO"),
            {"chat_template_kwargs": {"enable_thinking": False}},
        )
        self.assertEqual(
            engine_app.resolve_thinking_extra_body("http://127.0.0.1:11434/v1", "deepseek-r1:14b", False, "AUTO"),
            {"think": False},
        )
        self.assertEqual(
            engine_app.resolve_thinking_extra_body("https://api.deepseek.com/v1", "deepseek-chat", False, "NONE"),
            {},
        )

        captured: dict = {}

        def fake_exec(engine_run_id, run_dir: Path, cfg_path, work_id, mode, max_calls, secret_env):
            captured["cfg"] = yaml.safe_load(Path(cfg_path).read_text(encoding="utf-8"))
            captured["env"] = dict(secret_env)
            (run_dir / "worker.log").write_text("leaked sk-deepseek-secret-9999\n", encoding="utf-8")
            (run_dir / "result.json").write_text(json.dumps({"workId": work_id, "rows": [], "claimCount": 0}), encoding="utf-8")
            return 0

        mgr = engine_app.RunManager(self.work_dir, executor_fn=fake_exec)
        client = TestClient(engine_app.create_app(mgr))

        resp = client.post(
            "/v1/runs",
            json={
                "runId": "r-override",
                "workId": "W55",
                "blocks": [{"blockId": "b1", "blockType": "text", "blockText": "x", "blockSeq": 0}],
                "modelConfig": {
                    "chat": {
                        "baseUrl": "https://api.deepseek.com/v1",
                        "model": "deepseek-chat",
                        "apiKey": "sk-deepseek-secret-9999",
                        "enableThinking": False,
                        "thinkingStyle": "AUTO",
                    },
                    "embedding": {
                        "baseUrl": "http://127.0.0.1:8001/v1",
                        "model": "local-bge-m3",
                        "clearApiKey": True,
                    },
                },
            },
        )
        self.assertEqual(resp.status_code, 202)
        engine_id = resp.json()["engineRunId"]
        for _ in range(40):
            st = client.get(f"/v1/runs/{engine_id}").json()
            if st["status"] == "SUCCEEDED":
                break
            time.sleep(0.05)

        self.assertEqual(captured["cfg"]["models"]["chat"]["base_url"], "https://api.deepseek.com/v1")
        self.assertEqual(captured["cfg"]["models"]["chat"]["model"], "deepseek-chat")
        self.assertEqual(captured["cfg"]["models"]["chat"]["extra_body"], {"thinking": {"type": "disabled"}})
        self.assertNotIn("api_key", captured["cfg"]["models"]["chat"])
        self.assertEqual(captured["cfg"]["models"]["embedding"]["base_url"], "http://127.0.0.1:8001/v1")
        self.assertEqual(captured["env"]["CAUSAL_CHAT_API_KEY"], "sk-deepseek-secret-9999")
        self.assertEqual(captured["env"]["CAUSAL_EMBEDDING_API_KEY"], "EMPTY")

        worker_log = client.get(f"/v1/runs/{engine_id}/artifacts/worker.log").text
        self.assertNotIn("sk-deepseek-secret-9999", worker_log)
        self.assertIn("[REDACTED]", worker_log)
        self.assertEqual(captured["cfg"]["models"]["chat"]["compat"]["provider_family"], "deepseek")
        self.assertEqual(captured["cfg"]["models"]["chat"]["compat"]["response_format_mode"], "json_object")
        self.assertEqual(captured["cfg"]["models"]["chat"]["compat"]["max_tokens_cap"], 8192)

    def test_provider_compat_adapts_stage0_stage1_stage034_and_fallbacks(self) -> None:
        from types import SimpleNamespace
        from engine.provider_compat import (
            ChatCompatAdapter,
            EmbeddingCompatAdapter,
            extract_json_text,
            resolve_chat_compat,
            resolve_embedding_compat,
        )

        # Provider profiles
        self.assertEqual(resolve_chat_compat("https://api.deepseek.com", "deepseek-flash", True)["response_format_mode"], "json_object")
        self.assertEqual(resolve_chat_compat("https://api.deepseek.com", "deepseek-chat", False)["max_tokens_cap"], 8192)
        self.assertIsNone(resolve_chat_compat("https://api.deepseek.com", "deepseek-flash", True)["max_tokens_cap"])
        self.assertEqual(resolve_chat_compat("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-max", False)["response_format_mode"], "json_schema_any")
        self.assertEqual(resolve_chat_compat("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-max", True)["response_format_mode"], "json_object")
        o3 = resolve_chat_compat("https://api.openai.com/v1", "o3-mini", True)
        self.assertEqual(o3["response_format_mode"], "json_schema_object")
        self.assertTrue(o3["use_max_completion_tokens"])
        self.assertTrue(o3["omit_temperature"])
        self.assertEqual(resolve_embedding_compat("https://dashscope.aliyuncs.com/compatible-mode/v1", "text-embedding-v4")["max_batch_size"], 10)
        self.assertFalse(resolve_embedding_compat("http://127.0.0.1:11434/v1", "bge-m3")["send_encoding_format"])

        stage0_rf = {
            "type": "json_schema",
            "json_schema": {
                "name": "stage0_section_screening_response",
                "strict": True,
                "schema": {"type": "array", "items": {"type": "object", "properties": {"block_id": {"type": "string"}}}},
            },
        }
        stage1_rf = {
            "type": "json_object",
            "schema": {
                "type": "json_schema",
                "json_schema": {
                    "name": "stage1_extraction_response",
                    "strict": True,
                    "schema": {"type": "object", "properties": {"edges": {"type": "array"}}},
                },
            },
        }

        # DeepSeek thinking mode: Stage 0 uses json_object + items wrapper, Stage 1 strips illegal "schema", Stage 0_34 bumps max_tokens
        ds_adapter = ChatCompatAdapter(
            resolve_chat_compat("https://api.deepseek.com", "deepseek-flash", True),
            {"thinking": {"type": "enabled"}},
        )
        kw0, exp_json, exp_arr = ds_adapter.prepare({
            "model": "deepseek-flash",
            "messages": [{"role": "system", "content": "Output valid JSON."}],
            "temperature": 0.0,
            "max_tokens": 10000,
            "response_format": stage0_rf,
        })
        self.assertEqual(kw0["response_format"], {"type": "json_object"})
        self.assertIn('"items"', kw0["messages"][0]["content"])
        self.assertTrue(exp_json and exp_arr)

        kw1, _, exp_arr1 = ds_adapter.prepare({
            "model": "deepseek-flash",
            "messages": [{"role": "system", "content": "Output valid JSON."}],
            "max_tokens": 16000,
            "response_format": stage1_rf,
        })
        self.assertEqual(kw1["response_format"], {"type": "json_object"})
        self.assertFalse(exp_arr1)

        kw34, _, _ = ds_adapter.prepare({
            "model": "deepseek-flash",
            "messages": [{"role": "system", "content": "Normalize"}],
            "max_tokens": 200,
            "response_format": {"type": "json_object"},
        })
        self.assertGreaterEqual(kw34["max_tokens"], 2048)

        # OpenAI o3 wraps Stage 0 array into object json_schema and uses max_completion_tokens without temperature
        o3_adapter = ChatCompatAdapter(o3, {"reasoning_effort": "medium"})
        kw_o3, _, _ = o3_adapter.prepare({
            "model": "o3-mini",
            "messages": [{"role": "system", "content": "Screen"}],
            "temperature": 0.0,
            "max_tokens": 10000,
            "response_format": stage0_rf,
        })
        self.assertEqual(kw_o3["response_format"]["type"], "json_schema")
        self.assertEqual(kw_o3["response_format"]["json_schema"]["schema"]["type"], "object")
        self.assertNotIn("temperature", kw_o3)
        self.assertNotIn("max_tokens", kw_o3)
        self.assertEqual(kw_o3["max_completion_tokens"], 10000)

        # Response extraction and unwrapping
        raw_wrapped = '<think>reasoning</think>\n```json\n{"items": [{"block_id": "b1", "relevant": true, "expected_fields": []}]}\n```'
        self.assertEqual(json.loads(extract_json_text(raw_wrapped, expect_array=True)), [{"block_id": "b1", "relevant": True, "expected_fields": []}])
        raw_prose = 'Here is the result:\n{"edges": []}\nDone.'
        self.assertEqual(json.loads(extract_json_text(raw_prose, expect_array=False)), {"edges": []})

        # Runtime HTTP 400 fallback: json_schema -> json_object -> prompt_only, and max_tokens cap
        generic = ChatCompatAdapter({"response_format_mode": "json_schema_any"})
        err_rf = RuntimeError("Error code: 400 - {'error': {'message': 'This response_format type is unavailable now'}}")
        self.assertTrue(generic.downgrade_on_error(err_rf, {"response_format": stage0_rf}))
        self.assertEqual(generic.response_format_mode, "json_object")
        self.assertTrue(generic.downgrade_on_error(err_rf, {"response_format": {"type": "json_object"}}))
        self.assertEqual(generic.response_format_mode, "prompt_only")
        kw_prompt, _, _ = generic.prepare({"messages": [{"role": "system", "content": "sys"}], "response_format": stage0_rf})
        self.assertNotIn("response_format", kw_prompt)

        err_mt = RuntimeError("Error code: 400 - Invalid max_tokens value, valid range is [1, 8192]")
        self.assertTrue(generic.downgrade_on_error(err_mt, {"max_tokens": 16000}))
        self.assertEqual(generic.max_tokens_cap, 8192)

        # Embedding fallback: drops encoding_format and splits oversized batches
        emb_calls: list[dict] = []

        def fake_emb(**kw):
            emb_calls.append(dict(kw))
            if "encoding_format" in kw:
                raise RuntimeError("Error code: 400 - unknown parameter encoding_format")
            if len(kw["input"]) > 2:
                raise RuntimeError("Error code: 400 - batch size must be <= 2")
            return SimpleNamespace(
                data=[SimpleNamespace(embedding=[float(i)]) for i, _ in enumerate(kw["input"])],
                model="test-emb",
                usage=SimpleNamespace(total_tokens=len(kw["input"])),
            )

        emb_adapter = EmbeddingCompatAdapter({"send_encoding_format": True})
        emb_res = emb_adapter.invoke(fake_emb, {"model": "test-emb", "input": ["a", "b", "c", "d"], "encoding_format": "float"})
        self.assertEqual(len(emb_res.data), 4)
        self.assertFalse(emb_adapter.send_encoding_format)
        self.assertEqual(emb_adapter.max_batch_size, 2)

        # CallBudget.wrap end-to-end: auto-downgrades 400 response_format and unwraps Stage 0 object into JSON array
        from engine.platform_entry import CallBudget

        budget = CallBudget(10, self.work_dir)
        sent_chat_calls: list[dict] = []

        def fake_chat_create(**kw):
            sent_chat_calls.append(dict(kw))
            if kw.get("response_format", {}).get("type") == "json_schema":
                raise RuntimeError("Error code: 400 - This response_format type is unavailable now")
            return SimpleNamespace(
                choices=[SimpleNamespace(message=SimpleNamespace(content='{"items":[{"block_id":"b1","relevant":true,"expected_fields":["Claim"]}]}'))],
                model="deepseek-flash",
                usage=SimpleNamespace(total_tokens=150),
            )

        fake_client = SimpleNamespace(
            chat=SimpleNamespace(completions=SimpleNamespace(create=fake_chat_create)),
            embeddings=SimpleNamespace(create=fake_emb),
        )
        wrapped = budget.wrap(
            fake_client,
            chat_adapter=ChatCompatAdapter({"response_format_mode": "json_schema_any"}),
            embedding_adapter=EmbeddingCompatAdapter({"send_encoding_format": True}),
        )
        res = wrapped.chat.completions.create(
            model="deepseek-flash",
            messages=[{"role": "system", "content": "Output valid JSON."}],
            max_tokens=10000,
            response_format=stage0_rf,
        )
        self.assertEqual(len(sent_chat_calls), 2)
        self.assertEqual(sent_chat_calls[1]["response_format"], {"type": "json_object"})
        self.assertEqual(
            json.loads(res.choices[0].message.content),
            [{"block_id": "b1", "relevant": True, "expected_fields": ["Claim"]}],
        )

    def test_platform_entry_full_pipeline_raw_standardized_stw_cache_and_resume(self) -> None:
        import csv
        from types import SimpleNamespace
        import yaml
        from engine import platform_entry

        def write_paper_input(run_dir: Path, work_id: str) -> None:
            run_dir.mkdir(parents=True, exist_ok=True)
            with (run_dir / "input.csv").open("w", encoding="utf-8", newline="") as f:
                w = csv.writer(f)
                w.writerow(["work_id", "block_id", "block_type", "block_text", "title_level", "block_seq"])
                w.writerow([work_id, "b0", "title", "1. Introduction", 1, 0])
                w.writerow([work_id, "b1", "text", "Higher minimum wage increases teen wages with p<0.01 using DID.", "", 1])
                w.writerow([work_id, "b2", "title", "2. Conclusion", 1, 2])
                w.writerow([work_id, "b3", "text", "Our DID estimates confirm minimum wage raises earnings.", "", 3])

        emb_call_counts = {"calls": 0}

        def fake_client_factory(cfg, model_type="chat"):
            def fake_chat(**kw):
                sys_text = kw["messages"][0]["content"]
                if "screener" in sys_text.lower():
                    payload = {"items": [
                        {"block_id": "b0", "relevant": True, "expected_fields": ["Claim", "Method"]},
                        {"block_id": "b2", "relevant": True, "expected_fields": ["Claim", "Method"]},
                    ]}
                elif "taxonomy normalization" in kw["messages"][1]["content"].lower():
                    payload = {"normalized": "positive"}
                else:
                    payload = {
                        "edges": [{
                            "claim": "Minimum wage -> Teen earnings",
                            "cause": "Minimum wage",
                            "effect": "Teen earnings",
                            "sign_of_impact": "positive",
                            "type_of_relationship": "direct effect",
                            "evidence": "Our DID estimates confirm minimum wage raises earnings.",
                            "causal_inference_method": "DID",
                            "evidence_method_other_description": "NA",
                            "sources_of_exogenous_variation": "State policy changes",
                            "level_of_tentativeness": "certain",
                            "statistical_significance": "p<0.01",
                            "is_main_contribution": True,
                        }]
                    }
                return SimpleNamespace(
                    choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(payload)))],
                    model="deepseek-flash",
                    usage=SimpleNamespace(total_tokens=100, prompt_tokens=60, completion_tokens=40),
                )

            def fake_emb(**kw):
                emb_call_counts["calls"] += 1
                items = kw["input"]
                return SimpleNamespace(
                    data=[SimpleNamespace(embedding=[0.5, 0.5, 0.1, 0.2]) for _ in items],
                    model="test-emb",
                    usage=SimpleNamespace(total_tokens=len(items)),
                )

            return SimpleNamespace(
                chat=SimpleNamespace(completions=SimpleNamespace(create=fake_chat)),
                embeddings=SimpleNamespace(create=fake_emb),
            )

        cfg = engine_app._load_effective_config(
            engine_app.ModelConfigOverride(
                chat=engine_app.ChatModelOverride(
                    baseUrl="https://api.deepseek.com",
                    model="deepseek-flash",
                    enableThinking=True,
                ),
                embedding=engine_app.EmbeddingModelOverride(
                    baseUrl="https://api.siliconflow.cn/v1",
                    model="Qwen/Qwen3-Embedding-4B",
                ),
            )
        )
        cfg["map"]["batch_size"] = 2000
        cfg_path = self.work_dir / "runtime_config.yaml"
        cfg_path.write_text(yaml.safe_dump(cfg, allow_unicode=True), encoding="utf-8")

        # 1) RAW (PR_COMPAT) mode: full pipeline succeeds without psycopg, skips MAP (0 embedding calls)
        raw_dir = self.work_dir / "runs" / "rawrun1"
        write_paper_input(raw_dir, "W100")
        # Seed a stale profile.json with mismatched hash to verify resume restarts cleanly instead of CONFIG_CHANGED
        (raw_dir / "profile.json").write_text(json.dumps({"executionHash": "old-hash", "inputCsvHash": "old"}), encoding="utf-8")
        res_raw = platform_entry.execute(cfg_path, raw_dir, "W100", mode="PR_COMPAT", max_calls=100, client_factory=fake_client_factory)
        self.assertEqual(res_raw["claimCount"], 1)
        self.assertEqual(emb_call_counts["calls"], 0)
        self.assertTrue(isinstance(res_raw["rows"][0]["is_main_contribution"], bool))
        self.assertRegex(res_raw["rows"][0]["claim_hash"], r"^[a-f0-9]{32}$")
        self.assertTrue((raw_dir / "result.json").is_file())

        # 2) STANDARDIZED mode: populates STW cache on 1st run, reuses cache on 2nd run
        std_dir1 = self.work_dir / "runs" / "stdrun1"
        write_paper_input(std_dir1, "W100")
        res_std1 = platform_entry.execute(cfg_path, std_dir1, "W100", mode="STANDARDIZED", max_calls=500, client_factory=fake_client_factory)
        self.assertEqual(res_std1["claimCount"], 1)
        calls_after_first = emb_call_counts["calls"]
        self.assertGreater(calls_after_first, 1)

        std_dir2 = self.work_dir / "runs" / "stdrun2"
        write_paper_input(std_dir2, "W100")
        res_std2 = platform_entry.execute(cfg_path, std_dir2, "W100", mode="STANDARDIZED", max_calls=500, client_factory=fake_client_factory)
        self.assertEqual(res_std2["claimCount"], 1)
        # 2nd run only embeds the paper's 2 entities (1 call), NOT the 6434 STW labels!
        self.assertEqual(emb_call_counts["calls"] - calls_after_first, 1)


if __name__ == "__main__":
    unittest.main()

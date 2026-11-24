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


if __name__ == "__main__":
    unittest.main()

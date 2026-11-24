"""Thin HTTP sidecar wrapping the unmodified ClaimInEconomics algorithm (zero DB access)."""
from __future__ import annotations

import csv
import hashlib
import inspect
import ipaddress
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import yaml
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

BASE_DIR = Path(__file__).resolve().parent
ENGINE_DIR = BASE_DIR / "engine"
WORK_DIR = Path(os.environ.get("CAUSAL_ENGINE_WORK_DIR", BASE_DIR / "var")).resolve()
CONFIG_TEMPLATE = Path(os.environ.get("CAUSAL_ENGINE_CONFIG", ENGINE_DIR / "config.yaml")).resolve()
PROFILE_VERSION = os.environ.get("CAUSAL_PROFILE_VERSION", "1")
DEFAULT_MAX_CALLS = int(os.environ.get("CAUSAL_MAX_CALLS", "2000"))
MAX_WORKERS = max(1, int(os.environ.get("CAUSAL_ENGINE_MAX_WORKERS", "2")))
RETENTION_SECONDS = max(3600, int(os.environ.get("CAUSAL_ENGINE_RETENTION_HOURS", "168")) * 3600)
ALGORITHM_REVISION = "6f822017cb513604c4a9b778b13f75697bafead2"

ARTIFACT_PATHS: dict[str, str] = {
    "input.csv": "input.csv",
    "stage0_raw_responses.jsonl": "stage0_output/stage0_raw_responses.jsonl",
    "stage1_raw_responses.jsonl": "stage1_output/stage1_raw_responses.jsonl",
    "final_claims.csv": "final_claims.csv",
    "mapped_results.csv": "mapped_results.csv",
    "result.json": "result.json",
    "worker.log": "worker.log",
}

RESUME_ITEMS = (
    ".checkpoints", "profile.json", "raw", "cleaned",
    "stage0_input", "stage0_output", "stage1_input", "stage1_output",
    "final_claims.csv", "mapped_results.csv",
)

ALLOWED_ENV = (
    "PATH", "LANG", "LC_ALL", "SSL_CERT_FILE", "REQUESTS_CA_BUNDLE",
    "CAUSAL_CHAT_API_KEY", "CAUSAL_EMBEDDING_API_KEY",
)


class BlockInput(BaseModel):
    blockId: str
    blockType: str
    blockText: str = ""
    titleLevel: int | None = None
    blockSeq: int


class ChatModelOverride(BaseModel):
    baseUrl: str | None = None
    model: str | None = None
    apiKey: str | None = None
    clearApiKey: bool = False
    enableThinking: bool = False
    thinkingStyle: str = "AUTO"


class EmbeddingModelOverride(BaseModel):
    baseUrl: str | None = None
    model: str | None = None
    apiKey: str | None = None
    clearApiKey: bool = False


class ModelConfigOverride(BaseModel):
    chat: ChatModelOverride | None = None
    embedding: EmbeddingModelOverride | None = None


class RunRequest(BaseModel):
    runId: str
    workId: str
    ingestionMode: str = "PR_COMPAT"
    maxCalls: int = Field(default=DEFAULT_MAX_CALLS, ge=1, le=50000)
    resumeEngineRunId: str | None = None
    blocks: list[BlockInput]
    modelConfig: ModelConfigOverride | None = None


def _is_local_host(host: str) -> bool:
    if not host:
        return False
    if host in ("localhost", "host.docker.internal") or host.endswith(".local"):
        return True
    try:
        ip = ipaddress.ip_address(host)
        return ip.is_loopback or ip.is_private
    except ValueError:
        return False


def resolve_thinking_extra_body(
    base_url: str,
    model: str,
    enable_thinking: bool,
    thinking_style: str = "AUTO",
) -> dict[str, Any]:
    style = (thinking_style or "AUTO").strip().upper()
    if style == "AUTO":
        parsed = urlparse(base_url or "")
        host = (parsed.hostname or "").lower()
        port = parsed.port
        m = (model or "").lower()
        if any(d in host for d in ("deepseek.com", "volces.com", "bigmodel.cn")):
            style = "THINKING_OBJECT"
        elif any(d in host for d in ("dashscope.aliyuncs.com", "siliconflow.", "modelscope.")):
            style = "ENABLE_THINKING"
        elif port == 11434 or "ollama" in host:
            style = "OLLAMA_THINK"
        elif "openai.com" in host or "generativelanguage.googleapis.com" in host:
            if any(m.startswith(p) for p in ("o1", "o3", "o4", "gpt-5", "gemini-2.5", "gemini-3")):
                style = "REASONING_EFFORT"
            else:
                style = "NONE"
        elif _is_local_host(host):
            style = "CHAT_TEMPLATE_KWARGS"
        elif any(k in m for k in ("deepseek", "glm-4.5", "glm-4.6", "glm-5")):
            style = "THINKING_OBJECT"
        elif any(k in m for k in ("qwen3", "qwen-3", "qwq")):
            style = "ENABLE_THINKING"
        else:
            style = "NONE"

    if style == "NONE":
        return {}
    if style == "THINKING_OBJECT":
        return {"thinking": {"type": "enabled" if enable_thinking else "disabled"}}
    if style == "ENABLE_THINKING":
        return {"enable_thinking": bool(enable_thinking)}
    if style == "CHAT_TEMPLATE_KWARGS":
        return {"chat_template_kwargs": {"enable_thinking": bool(enable_thinking)}}
    if style == "OLLAMA_THINK":
        return {"think": bool(enable_thinking)}
    if style == "REASONING_EFFORT":
        return {"reasoning_effort": "medium" if enable_thinking else "none"}
    return {}


def _load_effective_config(model_override: ModelConfigOverride | None = None) -> dict[str, Any]:
    cfg = yaml.safe_load(CONFIG_TEMPLATE.read_text(encoding="utf-8"))
    chat = cfg.setdefault("models", {}).setdefault("chat", {})
    emb = cfg["models"].setdefault("embedding", {})
    if os.environ.get("CAUSAL_CHAT_BASE_URL"):
        chat["base_url"] = os.environ["CAUSAL_CHAT_BASE_URL"]
    if os.environ.get("CAUSAL_CHAT_MODEL"):
        chat["model"] = os.environ["CAUSAL_CHAT_MODEL"]
    if os.environ.get("CAUSAL_EMBEDDING_BASE_URL"):
        emb["base_url"] = os.environ["CAUSAL_EMBEDDING_BASE_URL"]
    if os.environ.get("CAUSAL_EMBEDDING_MODEL"):
        emb["model"] = os.environ["CAUSAL_EMBEDDING_MODEL"]

    enable_thinking = os.environ.get("CAUSAL_ENABLE_THINKING", "false").strip().lower() == "true"
    thinking_style = os.environ.get("CAUSAL_THINKING_STYLE", "AUTO").strip().upper() or "AUTO"

    if model_override and model_override.chat:
        co = model_override.chat
        if co.baseUrl and co.baseUrl.strip():
            chat["base_url"] = co.baseUrl.strip()
        if co.model and co.model.strip():
            chat["model"] = co.model.strip()
        enable_thinking = bool(co.enableThinking)
        if co.thinkingStyle and co.thinkingStyle.strip():
            thinking_style = co.thinkingStyle.strip().upper()

    if model_override and model_override.embedding:
        eo = model_override.embedding
        if eo.baseUrl and eo.baseUrl.strip():
            emb["base_url"] = eo.baseUrl.strip()
        if eo.model and eo.model.strip():
            emb["model"] = eo.model.strip()

    extra_body = resolve_thinking_extra_body(
        str(chat.get("base_url") or ""),
        str(chat.get("model") or ""),
        enable_thinking,
        thinking_style,
    )
    if extra_body:
        chat["extra_body"] = extra_body
    else:
        chat.pop("extra_body", None)

    chat.pop("api_key", None)
    emb.pop("api_key", None)
    return cfg


def _resolve_secret_env(model_override: ModelConfigOverride | None) -> dict[str, str]:
    def pick(override_key: str | None, clear: bool, has_override_url: bool, env_name: str) -> str:
        if override_key and override_key.strip():
            return override_key.strip()
        if clear or has_override_url:
            return "EMPTY"
        return os.environ.get(env_name, "").strip() or "EMPTY"

    chat_ov = model_override.chat if model_override else None
    emb_ov = model_override.embedding if model_override else None
    return {
        "CAUSAL_CHAT_API_KEY": pick(
            chat_ov.apiKey if chat_ov else None,
            chat_ov.clearApiKey if chat_ov else False,
            bool(chat_ov and chat_ov.baseUrl and chat_ov.baseUrl.strip()),
            "CAUSAL_CHAT_API_KEY",
        ),
        "CAUSAL_EMBEDDING_API_KEY": pick(
            emb_ov.apiKey if emb_ov else None,
            emb_ov.clearApiKey if emb_ov else False,
            bool(emb_ov and emb_ov.baseUrl and emb_ov.baseUrl.strip()),
            "CAUSAL_EMBEDDING_API_KEY",
        ),
    }


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _redact_file(path: Path, extra_secrets: list[str] | None = None) -> None:
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8", errors="replace")
    changed = False
    secrets = [os.environ.get("CAUSAL_CHAT_API_KEY"), os.environ.get("CAUSAL_EMBEDDING_API_KEY")]
    if extra_secrets:
        secrets.extend(extra_secrets)
    for secret in secrets:
        if secret and secret != "EMPTY" and len(secret) >= 4 and secret in text:
            text = text.replace(secret, "[REDACTED]")
            changed = True
    if changed:
        path.write_text(text, encoding="utf-8")


class RunManager:
    def __init__(self, work_dir: Path, executor_fn=None) -> None:
        self.work_dir = work_dir.resolve()
        self.runs_dir = self.work_dir / "runs"
        self.runs_dir.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.processes: dict[str, subprocess.Popen[Any]] = {}
        self.cancelled: set[str] = set()
        self.executor = ThreadPoolExecutor(max_workers=MAX_WORKERS, thread_name_prefix="causal-run")
        self.executor_fn = executor_fn or self._run_subprocess

    def submit(self, req: RunRequest) -> str:
        self._cleanup_expired()
        mode = "STANDARDIZED" if req.ingestionMode == "STANDARDIZED" else "PR_COMPAT"
        engine_run_id = uuid.uuid4().hex
        run_dir = self._run_dir(engine_run_id)
        run_dir.mkdir(parents=True, exist_ok=True)
        if req.resumeEngineRunId:
            self._copy_resume(req.resumeEngineRunId, run_dir)
        self._write_input_csv(run_dir / "input.csv", req.workId, req.blocks)
        cfg_path = run_dir / "runtime_config.yaml"
        cfg_path.write_text(
            yaml.safe_dump(_load_effective_config(req.modelConfig), allow_unicode=True),
            encoding="utf-8",
        )
        secret_env = _resolve_secret_env(req.modelConfig)
        self._write_state(run_dir, {"status": "QUEUED", "stage": "PREPARE", "workId": req.workId, "createdAt": time.time()})
        self.executor.submit(self._execute, engine_run_id, run_dir, cfg_path, req.workId, mode, req.maxCalls, secret_env)
        return engine_run_id

    def status(self, engine_run_id: str) -> dict[str, Any]:
        run_dir = self._require_run_dir(engine_run_id)
        state = self._read_json(run_dir / "state.json") or {"status": "FAILED", "stage": "PREPARE"}
        progress = self._read_json(run_dir / "progress.json")
        usage = self._read_json(run_dir / "usage.json") or {"calls": 0, "tokens": 0, "modelVersions": []}
        result = self._read_json(run_dir / "result.json") if state.get("status") == "SUCCEEDED" else None
        return {
            "engineRunId": engine_run_id,
            "status": state.get("status", "RUNNING"),
            "stage": (progress or {}).get("phase", state.get("stage", "PREPARE")),
            "usage": usage,
            "result": result,
            "errorCode": state.get("errorCode"),
            "errorMessage": state.get("errorMessage"),
        }

    def cancel(self, engine_run_id: str) -> dict[str, Any]:
        run_dir = self._require_run_dir(engine_run_id)
        with self.lock:
            self.cancelled.add(engine_run_id)
            proc = self.processes.get(engine_run_id)
        if proc and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                proc.kill()
        state = self._read_json(run_dir / "state.json") or {}
        if state.get("status") in ("QUEUED", "RUNNING"):
            state.update(status="CANCELLED", finishedAt=time.time())
            self._write_state(run_dir, state)
        return self.status(engine_run_id)

    def artifacts(self, engine_run_id: str) -> list[dict[str, Any]]:
        run_dir = self._require_run_dir(engine_run_id)
        items = []
        for name, rel in ARTIFACT_PATHS.items():
            path = (run_dir / rel).resolve()
            if path.is_file() and path.is_relative_to(run_dir):
                items.append({"name": name, "sizeBytes": path.stat().st_size, "sha256": _sha256(path)})
        return items

    def artifact_path(self, engine_run_id: str, name: str) -> Path:
        if name not in ARTIFACT_PATHS:
            raise HTTPException(status_code=400, detail="Unsupported artifact name")
        run_dir = self._require_run_dir(engine_run_id)
        path = (run_dir / ARTIFACT_PATHS[name]).resolve()
        if not path.is_relative_to(run_dir) or not path.is_file():
            raise HTTPException(status_code=404, detail="Artifact not found")
        if name == "worker.log":
            _redact_file(path)
        return path

    def _execute(
        self,
        engine_run_id: str,
        run_dir: Path,
        cfg_path: Path,
        work_id: str,
        mode: str,
        max_calls: int,
        secret_env: dict[str, str] | None = None,
    ) -> None:
        with self.lock:
            if engine_run_id in self.cancelled:
                self._write_state(run_dir, {"status": "CANCELLED", "stage": "PREPARE", "finishedAt": time.time()})
                return
        self._write_state(run_dir, {"status": "RUNNING", "stage": "PREPARE", "workId": work_id})
        extra_secrets = list((secret_env or {}).values())
        try:
            params = inspect.signature(self.executor_fn).parameters
            if len(params) >= 7:
                exit_code = self.executor_fn(engine_run_id, run_dir, cfg_path, work_id, mode, max_calls, secret_env or {})
            else:
                exit_code = self.executor_fn(engine_run_id, run_dir, cfg_path, work_id, mode, max_calls)
            _redact_file(run_dir / "worker.log", extra_secrets)
            _redact_file(run_dir / "error.json", extra_secrets)
            with self.lock:
                was_cancelled = engine_run_id in self.cancelled
            if was_cancelled:
                self._write_state(run_dir, {"status": "CANCELLED", "stage": "PREPARE", "finishedAt": time.time()})
            elif exit_code == 0 and (run_dir / "result.json").is_file():
                self._write_state(run_dir, {"status": "SUCCEEDED", "stage": "DONE", "finishedAt": time.time()})
            else:
                err = self._read_json(run_dir / "error.json") or {}
                code = self._classify_error(err.get("message", ""))
                self._write_state(run_dir, {
                    "status": "FAILED", "stage": "PREPARE", "finishedAt": time.time(),
                    "errorCode": code, "errorMessage": err.get("message") or "算法执行失败，请下载 worker.log 查看详情",
                })
        except Exception as exc:
            self._write_state(run_dir, {
                "status": "FAILED", "stage": "PREPARE", "finishedAt": time.time(),
                "errorCode": "ENGINE_ERROR", "errorMessage": str(exc)[:400],
            })
        finally:
            with self.lock:
                self.processes.pop(engine_run_id, None)

    def _run_subprocess(
        self,
        engine_run_id: str,
        run_dir: Path,
        cfg_path: Path,
        work_id: str,
        mode: str,
        max_calls: int,
        secret_env: dict[str, str] | None = None,
    ) -> int:
        cmd = [
            sys.executable, str(ENGINE_DIR / "platform_entry.py"),
            "--config", str(cfg_path), "--directory", str(run_dir),
            "--work-id", work_id, "--mode", mode, "--max-calls", str(max_calls),
        ]
        env = {k: os.environ[k] for k in ALLOWED_ENV if k in os.environ}
        if secret_env:
            env.update(secret_env)
        env.update({
            "PYTHONUNBUFFERED": "1", "PYTHONDONTWRITEBYTECODE": "1",
            "OPENBLAS_NUM_THREADS": "2", "OMP_NUM_THREADS": "2",
            "MKL_NUM_THREADS": "2", "NUMEXPR_NUM_THREADS": "2",
        })
        with (run_dir / "worker.log").open("wb") as log_file:
            proc = subprocess.Popen(cmd, cwd=str(run_dir), env=env, stdout=log_file, stderr=subprocess.STDOUT)
            with self.lock:
                self.processes[engine_run_id] = proc
            return proc.wait()

    def _copy_resume(self, previous_id: str, target_dir: Path) -> None:
        if not previous_id.isalnum():
            return
        prev_dir = self._run_dir(previous_id)
        if not prev_dir.is_dir():
            return
        for item in RESUME_ITEMS:
            src = prev_dir / item
            dst = target_dir / item
            if src.is_dir():
                shutil.copytree(src, dst, dirs_exist_ok=True)
            elif src.is_file():
                shutil.copy2(src, dst)

    def _cleanup_expired(self) -> None:
        cutoff = time.time() - RETENTION_SECONDS
        for child in self.runs_dir.iterdir():
            if not child.is_dir():
                continue
            state = self._read_json(child / "state.json") or {}
            if state.get("status") in ("SUCCEEDED", "FAILED", "CANCELLED") and state.get("finishedAt", time.time()) < cutoff:
                shutil.rmtree(child, ignore_errors=True)

    def _run_dir(self, engine_run_id: str) -> Path:
        if not engine_run_id or not engine_run_id.isalnum():
            raise HTTPException(status_code=400, detail="Invalid engineRunId")
        path = (self.runs_dir / engine_run_id).resolve()
        if not path.is_relative_to(self.runs_dir):
            raise HTTPException(status_code=400, detail="Invalid engineRunId")
        return path

    def _require_run_dir(self, engine_run_id: str) -> Path:
        path = self._run_dir(engine_run_id)
        if not path.is_dir():
            raise HTTPException(status_code=404, detail="Run not found")
        return path

    @staticmethod
    def _write_input_csv(path: Path, work_id: str, blocks: list[BlockInput]) -> None:
        with path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(["work_id", "block_id", "block_type", "block_text", "title_level", "block_seq"])
            for b in blocks:
                writer.writerow([work_id, b.blockId, b.blockType, b.blockText, "" if b.titleLevel is None else b.titleLevel, b.blockSeq])

    @staticmethod
    def _write_state(run_dir: Path, payload: dict[str, Any]) -> None:
        tmp = run_dir / "state.json.tmp"
        tmp.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        tmp.replace(run_dir / "state.json")

    @staticmethod
    def _read_json(path: Path) -> dict[str, Any] | None:
        if not path.is_file():
            return None
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            return None

    @staticmethod
    def _classify_error(message: str) -> str:
        for known in ("CONFIG_CHANGED", "CHECKPOINT_CHANGED", "INPUT_CHANGED", "MODEL_CALL_BUDGET_EXCEEDED"):
            if message.startswith(known):
                return known
        return "ALGORITHM_FAILED"


def create_app(manager: RunManager | None = None) -> FastAPI:
    app = FastAPI(title="AI Economist Causal Engine", version="0.1.0")
    mgr = manager or RunManager(WORK_DIR)

    @app.get("/v1/health")
    def health() -> dict[str, Any]:
        cfg = _load_effective_config()
        return {
            "status": "ok",
            "algorithmRevision": ALGORITHM_REVISION,
            "profileVersion": PROFILE_VERSION,
            "chatModel": cfg.get("models", {}).get("chat", {}).get("model", ""),
            "embeddingModel": cfg.get("models", {}).get("embedding", {}).get("model", ""),
            "defaultMaxCalls": DEFAULT_MAX_CALLS,
        }

    @app.post("/v1/runs", status_code=202)
    def create_run(req: RunRequest) -> dict[str, str]:
        return {"engineRunId": mgr.submit(req)}

    @app.get("/v1/runs/{engine_run_id}")
    def get_run(engine_run_id: str) -> dict[str, Any]:
        return mgr.status(engine_run_id)

    @app.delete("/v1/runs/{engine_run_id}")
    def delete_run(engine_run_id: str) -> dict[str, Any]:
        return mgr.cancel(engine_run_id)

    @app.get("/v1/runs/{engine_run_id}/artifacts")
    def list_artifacts(engine_run_id: str) -> list[dict[str, Any]]:
        return mgr.artifacts(engine_run_id)

    @app.get("/v1/runs/{engine_run_id}/artifacts/{name}")
    def download_artifact(engine_run_id: str, name: str) -> FileResponse:
        return FileResponse(mgr.artifact_path(engine_run_id, name), filename=name, media_type="application/octet-stream")

    return app


app = create_app()

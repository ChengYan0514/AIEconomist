"""Provider-aware request formatting and response normalization for OpenAI-compatible LLM/Embedding APIs."""
from __future__ import annotations

import copy
import ipaddress
import json
import re
import threading
from types import SimpleNamespace
from typing import Any
from urllib.parse import urlparse


THINK_BLOCK_RE = re.compile(r"<think>.*?</think>", flags=re.DOTALL | re.IGNORECASE)
MAX_TOKENS_RANGE_RE = re.compile(
    r"(?:range[^0-9]*\[\s*\d+\s*,\s*(\d+)\s*\]|max_tokens[^0-9]*(?:<=|less than or equal to|at most|maximum(?: is|:)?|cannot exceed)\s*(\d+))",
    flags=re.IGNORECASE,
)


def is_local_host(host: str) -> bool:
    if not host:
        return False
    if host in ("localhost", "host.docker.internal") or host.endswith(".local"):
        return True
    try:
        ip = ipaddress.ip_address(host)
        return ip.is_loopback or ip.is_private
    except ValueError:
        return False


def detect_provider_family(base_url: str, model: str) -> str:
    parsed = urlparse(base_url or "")
    host = (parsed.hostname or "").lower()
    port = parsed.port
    m = (model or "").lower()
    if "deepseek.com" in host:
        return "deepseek"
    if "dashscope.aliyuncs.com" in host:
        return "dashscope"
    if "siliconflow." in host:
        return "siliconflow"
    if "volces.com" in host:
        return "volces"
    if "bigmodel.cn" in host:
        return "bigmodel"
    if "moonshot." in host:
        return "moonshot"
    if "modelscope." in host:
        return "modelscope"
    if "openai.com" in host:
        return "openai"
    if "generativelanguage.googleapis.com" in host:
        return "gemini"
    if "openrouter.ai" in host:
        return "openrouter"
    if port == 11434 or "ollama" in host:
        return "ollama"
    if is_local_host(host):
        return "local_vllm"
    if "deepseek" in m:
        return "deepseek"
    if any(k in m for k in ("glm-4", "glm-5")):
        return "bigmodel"
    if any(k in m for k in ("qwen3", "qwen-3", "qwq")):
        return "dashscope"
    return "generic"


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
        elif is_local_host(host):
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


def resolve_chat_compat(
    base_url: str,
    model: str,
    enable_thinking: bool = False,
) -> dict[str, Any]:
    """Build deterministic provider-capability profile for chat completions."""
    family = detect_provider_family(base_url, model)
    m = (model or "").lower()
    is_openai_reasoning = family == "openai" and any(m.startswith(p) for p in ("o1", "o3", "o4", "gpt-5"))

    if family in ("deepseek", "volces", "bigmodel", "moonshot", "siliconflow", "modelscope"):
        response_format_mode = "json_object"
    elif family == "dashscope":
        response_format_mode = "json_object" if enable_thinking else "json_schema_any"
    elif family in ("openai", "gemini", "openrouter"):
        response_format_mode = "json_schema_object"
    elif family in ("local_vllm", "ollama"):
        response_format_mode = "json_schema_any"
    else:
        response_format_mode = "json_object"

    max_tokens_cap: int | None = None
    if family == "deepseek" and not enable_thinking and "reasoner" not in m:
        max_tokens_cap = 8192

    return {
        "provider_family": family,
        "response_format_mode": response_format_mode,
        "enable_thinking": bool(enable_thinking),
        "use_max_completion_tokens": is_openai_reasoning,
        "omit_temperature": is_openai_reasoning,
        "max_tokens_cap": max_tokens_cap,
        "min_thinking_max_tokens": 2048 if enable_thinking else None,
    }


def resolve_embedding_compat(base_url: str, model: str) -> dict[str, Any]:
    family = detect_provider_family(base_url, model)
    max_batch_size: int | None = None
    if family == "dashscope":
        max_batch_size = 10
    elif family == "siliconflow":
        max_batch_size = 32
    return {
        "provider_family": family,
        "max_batch_size": max_batch_size,
        "send_encoding_format": family != "ollama",
    }


def _extract_schema_spec(response_format: Any) -> tuple[str | None, dict[str, Any] | None, str | None]:
    """Normalize algorithm response_format variants into (kind, json_schema_wrapper, root_type).

    Handles:
    - Stage 0: {"type": "json_schema", "json_schema": {"name": ..., "schema": {"type": "array", ...}}}
    - Stage 1: {"type": "json_object", "schema": {"type": "json_schema", "json_schema": {...}}}
    - Stage 0_34: {"type": "json_object"}
    """
    if not isinstance(response_format, dict):
        return None, None, None
    rf_type = response_format.get("type")
    if rf_type == "json_schema" and isinstance(response_format.get("json_schema"), dict):
        js = response_format["json_schema"]
        root_type = js.get("schema", {}).get("type") if isinstance(js.get("schema"), dict) else None
        return "json_schema", response_format, root_type
    if rf_type == "json_object":
        inner = response_format.get("schema")
        if isinstance(inner, dict) and inner.get("type") == "json_schema" and isinstance(inner.get("json_schema"), dict):
            js = inner["json_schema"]
            root_type = js.get("schema", {}).get("type") if isinstance(js.get("schema"), dict) else None
            return "json_schema", inner, root_type
        return "json_object", None, "object"
    return None, None, None


def _wrap_array_schema_as_object(json_schema_rf: dict[str, Any]) -> dict[str, Any]:
    js = copy.deepcopy(json_schema_rf["json_schema"])
    array_schema = js.get("schema", {"type": "array"})
    js["schema"] = {
        "type": "object",
        "properties": {"items": array_schema},
        "required": ["items"],
        "additionalProperties": False,
    }
    return {"type": "json_schema", "json_schema": js}


def _append_system_instruction(messages: Any, instruction: str) -> Any:
    if not isinstance(messages, list) or not instruction:
        return messages
    cloned = [dict(m) if isinstance(m, dict) else m for m in messages]
    for msg in cloned:
        if isinstance(msg, dict) and msg.get("role") == "system":
            content = str(msg.get("content") or "")
            if instruction not in content:
                msg["content"] = (content.rstrip() + "\n\n" + instruction).strip()
            return cloned
    cloned.insert(0, {"role": "system", "content": instruction})
    return cloned


def _schema_instruction(json_schema_rf: dict[str, Any] | None, wrap_array_in_items: bool) -> str:
    if not json_schema_rf:
        return "Return ONLY valid JSON without markdown code fences or extra commentary."
    inner_schema = json_schema_rf.get("json_schema", {}).get("schema")
    if wrap_array_in_items:
        example = (
            'Return ONLY a valid JSON object of the form {"items": [...]} where "items" is the JSON array '
            "matching the schema below. Do not output markdown code fences or any extra text."
        )
    elif isinstance(inner_schema, dict) and inner_schema.get("type") == "array":
        example = (
            "Return ONLY a valid JSON array matching the schema below. "
            "Do not output markdown code fences or any extra text."
        )
    else:
        example = (
            "Return ONLY a valid JSON object matching the schema below. "
            "Do not output markdown code fences or any extra text."
        )
    if isinstance(inner_schema, dict):
        compact = json.dumps(inner_schema, ensure_ascii=False, separators=(",", ":"))
        return f"{example}\nJSON Schema: {compact}"
    return example


def extract_json_text(content: str, expect_array: bool = False) -> str:
    """Clean thinking tags / markdown fences / wrapper prose and unwrap Stage 0 array objects."""
    if content is None:
        return ""
    cleaned = THINK_BLOCK_RE.sub("", str(content)).strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"\s*```$", "", cleaned)
        cleaned = cleaned.strip()

    parsed: Any = None
    try:
        parsed = json.loads(cleaned)
    except Exception:
        decoder = json.JSONDecoder()
        for idx, ch in enumerate(cleaned):
            if ch not in "[{":
                continue
            try:
                candidate, _ = decoder.raw_decode(cleaned, idx)
                if isinstance(candidate, (list, dict)):
                    parsed = candidate
                    break
            except Exception:
                continue

    if parsed is None:
        return cleaned

    if expect_array:
        if isinstance(parsed, list):
            return json.dumps(parsed, ensure_ascii=False)
        if isinstance(parsed, dict):
            for key in ("items", "results", "blocks", "sections", "data"):
                if isinstance(parsed.get(key), list):
                    return json.dumps(parsed[key], ensure_ascii=False)
            list_values = [v for v in parsed.values() if isinstance(v, list)]
            if len(list_values) == 1:
                return json.dumps(list_values[0], ensure_ascii=False)
            if "block_id" in parsed and "relevant" in parsed:
                return json.dumps([parsed], ensure_ascii=False)
        return json.dumps(parsed, ensure_ascii=False)

    return json.dumps(parsed, ensure_ascii=False)


def _apply_cleaned_content(response: Any, cleaned: str) -> Any:
    try:
        choices = getattr(response, "choices", None)
        if choices and len(choices) > 0:
            msg = getattr(choices[0], "message", None)
            if msg is not None and getattr(msg, "content", None) != cleaned:
                try:
                    msg.content = cleaned
                    return response
                except Exception:
                    wrapped_msg = SimpleNamespace(content=cleaned)
                    wrapped_choice = SimpleNamespace(message=wrapped_msg)
                    return SimpleNamespace(
                        choices=[wrapped_choice],
                        usage=getattr(response, "usage", None),
                        model=getattr(response, "model", None),
                    )
    except Exception:
        pass
    return response


def _is_bad_request(exc: Exception) -> bool:
    status = getattr(exc, "status_code", None)
    if status in (400, 422):
        return True
    text = str(exc).lower()
    return "error code: 400" in text or "error code: 422" in text or "invalid_request_error" in text


class ChatCompatAdapter:
    """Adapts OpenAI chat.completions.create kwargs per provider profile with runtime HTTP 400 fallback."""

    def __init__(self, compat: dict[str, Any] | None = None, extra_body: dict[str, Any] | None = None) -> None:
        self._lock = threading.Lock()
        self.compat = dict(compat or {})
        self.extra_body = dict(extra_body or {})
        self.response_format_mode = str(self.compat.get("response_format_mode") or "json_schema_any")
        self.use_max_completion_tokens = bool(self.compat.get("use_max_completion_tokens", False))
        self.omit_temperature = bool(self.compat.get("omit_temperature", False))
        self.max_tokens_cap: int | None = self.compat.get("max_tokens_cap")
        self.min_thinking_max_tokens: int | None = self.compat.get("min_thinking_max_tokens")
        self.drop_extra_body = False

    def prepare(self, raw_kw: dict[str, Any]) -> tuple[dict[str, Any], bool, bool]:
        with self._lock:
            mode = self.response_format_mode
            use_mct = self.use_max_completion_tokens
            omit_temp = self.omit_temperature
            cap = self.max_tokens_cap
            min_think = self.min_thinking_max_tokens
            drop_extra = self.drop_extra_body

        kw = dict(raw_kw)
        if self.extra_body and not drop_extra:
            kw["extra_body"] = {**self.extra_body, **(kw.get("extra_body") or {})}

        if omit_temp:
            kw.pop("temperature", None)

        if "max_tokens" in kw and kw["max_tokens"] is not None:
            mt = int(kw["max_tokens"])
            if min_think and mt < min_think:
                mt = min_think
            if cap and mt > cap:
                mt = cap
            if use_mct:
                kw.pop("max_tokens", None)
                kw["max_completion_tokens"] = mt
            else:
                kw["max_tokens"] = mt

        rf_kind, json_schema_rf, root_type = _extract_schema_spec(kw.get("response_format"))
        expect_array = root_type == "array"
        expect_json = rf_kind is not None

        if rf_kind is not None:
            if mode == "json_schema_any" and json_schema_rf is not None:
                kw["response_format"] = json_schema_rf
            elif mode == "json_schema_object" and json_schema_rf is not None:
                if expect_array:
                    kw["response_format"] = _wrap_array_schema_as_object(json_schema_rf)
                    kw["messages"] = _append_system_instruction(
                        kw.get("messages"),
                        'Wrap the JSON array inside a JSON object under key "items": {"items": [...]}.',
                    )
                else:
                    kw["response_format"] = json_schema_rf
            elif mode == "json_object":
                kw["response_format"] = {"type": "json_object"}
                if expect_array:
                    kw["messages"] = _append_system_instruction(
                        kw.get("messages"),
                        _schema_instruction(json_schema_rf, wrap_array_in_items=True),
                    )
            elif mode == "prompt_only":
                kw.pop("response_format", None)
                kw["messages"] = _append_system_instruction(
                    kw.get("messages"),
                    _schema_instruction(json_schema_rf, wrap_array_in_items=False),
                )
            elif rf_kind == "json_object":
                kw["response_format"] = {"type": "json_object"}

        return kw, expect_json, expect_array

    def downgrade_on_error(self, exc: Exception, sent_kw: dict[str, Any]) -> bool:
        """Inspect a 400/422 error and downgrade incompatible parameters. Returns True if adapted."""
        if not _is_bad_request(exc):
            return False
        msg = str(exc).lower()
        with self._lock:
            if any(k in msg for k in ("response_format", "json_schema", "json_object", "structured output")):
                if self.response_format_mode in ("json_schema_any", "json_schema_object"):
                    self.response_format_mode = "json_object"
                    return True
                if self.response_format_mode == "json_object":
                    self.response_format_mode = "prompt_only"
                    return True
                if "response_format" in sent_kw:
                    self.response_format_mode = "prompt_only"
                    return True

            if "max_completion_tokens" in msg and not self.use_max_completion_tokens:
                self.use_max_completion_tokens = True
                return True

            if "max_tokens" in msg:
                match = MAX_TOKENS_RANGE_RE.search(str(exc))
                limit = int(match.group(1) or match.group(2)) if match else None
                current = sent_kw.get("max_tokens") or sent_kw.get("max_completion_tokens")
                if limit and (self.max_tokens_cap is None or limit < self.max_tokens_cap):
                    self.max_tokens_cap = limit
                    return True
                if isinstance(current, int) and current > 8192 and (self.max_tokens_cap is None or self.max_tokens_cap > 8192):
                    self.max_tokens_cap = 8192
                    return True

            if "temperature" in msg and "temperature" in sent_kw and not self.omit_temperature:
                self.omit_temperature = True
                return True

            if (
                self.extra_body
                and not self.drop_extra_body
                and any(k in msg for k in ("thinking", "enable_thinking", "chat_template_kwargs", "reasoning_effort", "extra_body"))
            ):
                self.drop_extra_body = True
                return True

        return False

    def normalize_response(self, response: Any, expect_json: bool, expect_array: bool) -> Any:
        if not expect_json:
            return response
        try:
            content = response.choices[0].message.content
        except Exception:
            return response
        if not content:
            return response
        cleaned = extract_json_text(content, expect_array=expect_array)
        return _apply_cleaned_content(response, cleaned)


class EmbeddingCompatAdapter:
    """Adapts OpenAI embeddings.create kwargs per provider profile with runtime HTTP 400 fallback."""

    def __init__(self, compat: dict[str, Any] | None = None) -> None:
        self.compat = dict(compat or {})
        self.send_encoding_format = bool(self.compat.get("send_encoding_format", True))
        self.max_batch_size: int | None = self.compat.get("max_batch_size")

    def invoke(self, call_fn, raw_kw: dict[str, Any]) -> Any:
        kw = dict(raw_kw)
        if not self.send_encoding_format:
            kw.pop("encoding_format", None)

        inputs = kw.get("input")
        if isinstance(inputs, list) and self.max_batch_size and len(inputs) > self.max_batch_size:
            return self._invoke_chunked(call_fn, kw, inputs, self.max_batch_size)

        try:
            return call_fn(**kw)
        except Exception as exc:
            if not _is_bad_request(exc):
                raise
            msg = str(exc).lower()
            if "encoding_format" in msg and "encoding_format" in kw and self.send_encoding_format:
                self.send_encoding_format = False
                kw.pop("encoding_format", None)
                return self.invoke(call_fn, kw)
            if isinstance(inputs, list) and len(inputs) > 1 and any(
                k in msg for k in ("batch", "too many", "must be <=", "exceed", "input size", "max")
            ):
                new_limit = max(1, min(10, len(inputs) // 2))
                self.max_batch_size = new_limit
                return self._invoke_chunked(call_fn, kw, inputs, new_limit)
            raise

    def _invoke_chunked(self, call_fn, kw: dict[str, Any], inputs: list[Any], chunk_size: int) -> Any:
        merged_data: list[Any] = []
        total_tokens = 0
        model_name = None
        for idx in range(0, len(inputs), chunk_size):
            sub_kw = {**kw, "input": inputs[idx : idx + chunk_size]}
            res = call_fn(**sub_kw)
            merged_data.extend(getattr(res, "data", []))
            model_name = model_name or getattr(res, "model", None)
            usage = getattr(res, "usage", None)
            total_tokens += int(getattr(usage, "total_tokens", 0) or 0)
        return SimpleNamespace(
            data=merged_data,
            model=model_name,
            usage=SimpleNamespace(total_tokens=total_tokens),
        )

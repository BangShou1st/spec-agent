"""文件名:diagnostics.py

用途:语义证据采集的安全、确定性诊断。

诊断只作为响应侧的观察结果返回,绝不回灌进 prompt,也绝不发给推理
broker。语义输入取自引擎传给 ``ModelClient`` 的那条原始 user 消息并解码;
system prompt 只用哈希表示。
"""

import hashlib
import re
from typing import Any


_SENSITIVE_KEY = re.compile(
    r"(?:authorization|bearer|api[_-]?key|access[_-]?token|refresh[_-]?token|"
    r"client[_-]?secret|password|credential|private[_-]?key|internal[_-]?secret|"
    r"token|secret)",
    re.IGNORECASE,
)
_BEARER = re.compile(r"(?i)\bbearer\s+[^\s,;]+")
_KEY_VALUE = re.compile(
    r"(?i)(\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|"
    r"credential)\b\s*[:=]\s*)([^\s,;]+)"
)


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _sanitize(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            str(key): "[REDACTED]" if _SENSITIVE_KEY.search(str(key)) else _sanitize(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_sanitize(item) for item in value]
    if isinstance(value, str):
        sanitized = value.replace("super-secret-do-not-log", "[REDACTED]")
        sanitized = sanitized.replace(".local-secrets.env", "[REDACTED_FILE]")
        sanitized = _BEARER.sub("Bearer [REDACTED]", sanitized)
        return _KEY_VALUE.sub(r"\1[REDACTED]", sanitized)
    return value


def semantic_diagnostics(system_prompt: str, user_prompt: str, stage: str) -> dict[str, Any]:
    """从确切的模型消息构建响应侧诊断。

    ``user_prompt`` 采用解析而不是重新拼装,保证诊断 payload 与真正进入
    模型请求的语义 JSON 完全一致。格式错误的 prompt 有意用单一占位表示、
    不再抛出第二次解析失败;真正的模型调用与契约路径保持不变。
    """
    import json

    try:
        model_input: Any = json.loads(user_prompt)
    except (TypeError, json.JSONDecodeError):
        model_input = {"unavailable": True}
    return {
        "semanticTrace": {
            "stage": stage,
            "modelInput": _sanitize(model_input),
            "systemPromptSha256": _sha256(system_prompt),
            "userPromptSha256": _sha256(user_prompt),
        }
    }

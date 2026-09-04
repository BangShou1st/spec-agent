"""Safe, deterministic diagnostics for semantic evidence capture.

Diagnostics are returned only as a response-side observation.  They are never
fed back into a prompt or sent to the inference broker.  The semantic input is
decoded from the exact user message that the engine passes to ``ModelClient``;
the system prompt is represented by a hash only.
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
    """Build response-side diagnostics from the exact model messages.

    ``user_prompt`` is parsed rather than reconstructed so the diagnostic
    payload is the same semantic JSON that entered the model request.  A
    malformed prompt is intentionally represented without a second parse
    failure; the actual model call and contract path remain unchanged.
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

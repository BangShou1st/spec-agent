"""文件名:engine.py

用途:STATE_UPDATE 引擎——一次模型调用,严格解析模型输出,再组装由
runtime 盖章的响应信封。

输出解析失败携带与 DECISION 引擎结构一致的 ``ContractDiagnostic``
(机器码、阶段、脱敏字段路径),经 app 层透传给 Java;异常消息与诊断
都不携带模型输出的值。
"""

import json

from pydantic import ValidationError

from ..diagnostics import semantic_diagnostics
from ..contracts.decisions import (
    AgentV2ResponseEnvelope,
    ModelStateUpdateOutput,
    StateUpdateResult,
    UsageView,
)
from ..contracts.diagnostics import (
    ContractDiagnostic,
    actual_type,
    contract_diagnostic,
    expected_type,
    safe_field_path,
)
from ..contracts.inputs import AgentV2RequestEnvelope
from ..contracts.protocol import DECISION_PROTOCOL_VERSION
from ..model_client import ChatMessage, ModelClient
from ..prompts import state_update as state_update_prompt


# STATE_UPDATE 输出诊断允许出现在 fieldPath 里的字段名(模型自选的
# 未知键可能携带任意内容,一律不回显)。
_OUTPUT_FIELD_WHITELIST = frozenset({
    "claims", "kind", "text", "status", "confidence",
    "sourceRefs", "source_refs",
})


class BrainContractError(RuntimeError):
    """模型输出违反 brain 自身输出契约时抛出;可携带结构化诊断。"""

    def __init__(self, message: str, diagnostic: ContractDiagnostic | None = None):
        super().__init__(message)
        self.diagnostic = diagnostic


def handle_state_update(request: AgentV2RequestEnvelope, client: ModelClient) -> AgentV2ResponseEnvelope:
    if request.decision_budget.max_model_calls < 1:
        raise BrainContractError("decision budget does not allow any model call")

    user_prompt = state_update_prompt.render_user_prompt(request)
    completion = client.complete(
        run_id=str(request.run_id),
        call_type="STATE_UPDATE",
        messages=[
            ChatMessage(role="system", content=state_update_prompt.SYSTEM_PROMPT),
            ChatMessage(role="user", content=user_prompt),
        ],
    )
    output = _parse_model_output(completion.content)

    return AgentV2ResponseEnvelope(
        protocol_version=DECISION_PROTOCOL_VERSION,
        run_id=request.run_id,
        state_update=StateUpdateResult(claims=output.claims),
        usage=UsageView(model_calls=1, prompt_hashes=[]),
        diagnostics=semantic_diagnostics(
            state_update_prompt.SYSTEM_PROMPT, user_prompt, "STATE_UPDATE"),
    )


def _parse_model_output(content: str) -> ModelStateUpdateOutput:
    try:
        raw = json.loads(content)
    except json.JSONDecodeError as exc:
        raise BrainContractError(
            "model output is not valid JSON",
            contract_diagnostic(
                stage="brain.model_output_parse",
                validator_id="json.loads",
                machine_code="MODEL_OUTPUT_INVALID_JSON",
                field_path=None,
                expected_type="object",
                actual_type="invalid_json",
                source_exception_type=type(exc).__name__,
            ),
        ) from None
    try:
        return ModelStateUpdateOutput.model_validate(raw)
    except ValidationError as exc:
        error = (exc.errors(include_input=True, include_context=False, include_url=False) or [{}])[0]
        error_type = error.get("type") if isinstance(error, dict) else None
        field_path = safe_field_path(error.get("loc"), _OUTPUT_FIELD_WHITELIST) if isinstance(error, dict) else None
        diagnostic = contract_diagnostic(
            stage="brain.model_output_validation",
            validator_id="ModelStateUpdateOutput",
            machine_code="MODEL_OUTPUT_SCHEMA_INVALID",
            field_path=field_path,
            expected_type=expected_type(error_type),
            actual_type=actual_type(error.get("input")) if isinstance(error, dict) else None,
            source_exception_type=type(exc).__name__,
        )
        safe_path = f" fieldPath={field_path}" if field_path else ""
        raise BrainContractError(
            "model output violates the STATE_UPDATE contract: "
            f"[schema invalid{safe_path}]",
            diagnostic,
        ) from None

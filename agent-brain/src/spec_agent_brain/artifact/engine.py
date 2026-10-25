"""文件名:engine.py

用途:ARTIFACT_GENERATION 引擎——用一次 grounded 模型调用生成派生工件的
响应信封。

Brain 使用自己收到的可信请求来填写协议版本与 run 标识(绝不采信模型输出),
并在本地预先校验每个 section 的 source refs 是否都在快照允许范围内;
Java 侧随后仍会对全部内容做 fail-closed 复核。

输出解析与 grounding 失败携带与 DECISION 引擎结构一致的
``ContractDiagnostic``;异常消息与诊断都不携带模型输出的值(section
标题、ref 字符串等)。
"""

import json

from pydantic import ValidationError

from ..contracts.artifacts import (
    AgentArtifactResponse,
    ArtifactGenerationResult,
    ModelArtifactOutput,
)
from ..contracts.diagnostics import (
    ContractDiagnostic,
    actual_type,
    contract_diagnostic,
    expected_type,
    safe_field_path,
)
from ..contracts.inputs import AgentV2RequestEnvelope
from ..contracts.protocol import ARTIFACT_PROTOCOL_VERSION
from ..model_client import ChatMessage, ModelClient
from ..prompts import artifact as artifact_prompt


# ARTIFACT 输出诊断允许出现在 fieldPath 里的字段名(模型自选的未知键
# 可能携带任意内容,一律不回显)。
_OUTPUT_FIELD_WHITELIST = frozenset({
    "artifactType", "artifact_type", "sections", "unresolvedItems",
    "unresolved_items", "title", "content", "sourceRefs", "source_refs",
})


class ArtifactBrainContractError(RuntimeError):
    """模型输出违反 brain 自身输出契约时抛出;可携带结构化诊断。"""

    def __init__(self, message: str, diagnostic: ContractDiagnostic | None = None):
        super().__init__(message)
        self.diagnostic = diagnostic


class UngroundedReferenceError(ArtifactBrainContractError):
    """某个 section 引用了冻结快照允许范围之外的 source ref。

    单独从"响应格式错误"中拆出来报告:这是路由隔离/grounding 门禁拒绝了
    跨路由引用,Runtime 必须能把它和模型输出损坏区分开。
    """


def handle_artifact(request: AgentV2RequestEnvelope, client: ModelClient) -> AgentArtifactResponse:
    completion = client.complete(
        run_id=str(request.run_id),
        call_type="ARTIFACT_GENERATION",
        messages=[
            ChatMessage(role="system", content=artifact_prompt.SYSTEM_PROMPT),
            ChatMessage(role="user", content=artifact_prompt.render_user_prompt(request)),
        ],
    )
    output = _parse_model_output(completion.content)
    _check_section_source_refs(output, request)

    return AgentArtifactResponse(
        protocol_version=ARTIFACT_PROTOCOL_VERSION,
        run_id=request.run_id,
        artifact=ArtifactGenerationResult(
            artifact_type=output.artifact_type,
            sections=output.sections,
            unresolved_items=output.unresolved_items,
        ),
        usage={"model_calls": 1, "prompt_hashes": []},
    )


def _parse_model_output(content: str) -> ModelArtifactOutput:
    try:
        raw = json.loads(content)
    except json.JSONDecodeError as exc:
        raise ArtifactBrainContractError(
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
        return ModelArtifactOutput.model_validate(raw)
    except ValidationError as exc:
        error = (exc.errors(include_input=True, include_context=False, include_url=False) or [{}])[0]
        error_type = error.get("type") if isinstance(error, dict) else None
        field_path = safe_field_path(error.get("loc"), _OUTPUT_FIELD_WHITELIST) if isinstance(error, dict) else None
        diagnostic = contract_diagnostic(
            stage="brain.model_output_validation",
            validator_id="ModelArtifactOutput",
            machine_code="MODEL_OUTPUT_SCHEMA_INVALID",
            field_path=field_path,
            expected_type=expected_type(error_type),
            actual_type=actual_type(error.get("input")) if isinstance(error, dict) else None,
            source_exception_type=type(exc).__name__,
        )
        safe_path = f" fieldPath={field_path}" if field_path else ""
        raise ArtifactBrainContractError(
            "model output violates the ARTIFACT_GENERATION contract: "
            f"[schema invalid{safe_path}]",
            diagnostic,
        ) from None


def _check_section_source_refs(output: ModelArtifactOutput,
                               request: AgentV2RequestEnvelope) -> None:
    allowed = set(request.snapshot.allowed_source_refs)
    for section in output.sections:
        if not section.source_refs:
            raise ArtifactBrainContractError(
                "artifact section requires source references",
                contract_diagnostic(
                    stage="brain.output_grounding",
                    validator_id="sectionSourceRefPresence",
                    machine_code="MODEL_OUTPUT_SECTION_WITHOUT_SOURCE_REFS",
                    field_path="sections[].sourceRefs",
                    expected_type="non-empty array",
                    actual_type="empty array",
                    source_exception_type=None,
                ),
            )
        for ref in section.source_refs:
            if ref not in allowed:
                raise UngroundedReferenceError(
                    "model referenced a source outside the allowed snapshot refs",
                    contract_diagnostic(
                        stage="brain.output_grounding",
                        validator_id="sectionSourceRefAllowlist",
                        machine_code="MODEL_OUTPUT_UNGROUNDED_REFERENCE",
                        field_path="sections[].sourceRefs",
                        expected_type="allowed source reference",
                        actual_type="string",
                        source_exception_type=None,
                    ),
                )

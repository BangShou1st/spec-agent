"""文件名:engine.py

用途:ARTIFACT_GENERATION 引擎——用一次 grounded 模型调用生成派生工件的
响应信封。

Brain 使用自己收到的可信请求来填写协议版本与 run 标识(绝不采信模型输出),
并在本地预先校验每个 section 的 source refs 是否都在快照允许范围内;
Java 侧随后仍会对全部内容做 fail-closed 复核。
"""

import json

from ..contracts.artifacts import (
    AgentArtifactResponse,
    ArtifactGenerationResult,
    ModelArtifactOutput,
)
from ..contracts.inputs import AgentV2RequestEnvelope
from ..contracts.protocol import ARTIFACT_PROTOCOL_VERSION
from ..model_client import ChatMessage, ModelClient
from ..prompts import artifact as artifact_prompt


class ArtifactBrainContractError(RuntimeError):
    """模型输出违反 brain 自身输出契约时抛出。"""


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
        raise ArtifactBrainContractError("model output is not valid JSON") from exc
    try:
        return ModelArtifactOutput.model_validate(raw)
    except Exception as exc:  # pydantic ValidationError -> 转成有类型的 brain 失败
        raise ArtifactBrainContractError(
            f"model output violates the ARTIFACT_GENERATION contract: {exc}") from exc


def _check_section_source_refs(output: ModelArtifactOutput,
                               request: AgentV2RequestEnvelope) -> None:
    allowed = set(request.snapshot.allowed_source_refs)
    for section in output.sections:
        if not section.source_refs:
            raise ArtifactBrainContractError(
                f"artifact section requires source references: {section.title}")
        for ref in section.source_refs:
            if ref not in allowed:
                raise UngroundedReferenceError(
                    "model referenced a source outside the allowed snapshot refs: " + ref)

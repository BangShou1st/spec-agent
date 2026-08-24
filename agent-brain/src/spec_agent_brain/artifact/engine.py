"""ARTIFACT_GENERATION engine: one grounded model call producing a derived
artifact envelope.

The brain stamps the protocol version and run identity from its own trusted
request (never from model output) and pre-checks every section's source refs
against the snapshot's allowed refs; Java re-validates everything fail-closed
anyway.
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
    """Raised when a model output violates the brain's own output contract."""


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
    except Exception as exc:  # pydantic ValidationError -> typed brain failure
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
                raise ArtifactBrainContractError(
                    "model referenced a source outside the allowed snapshot refs: " + ref)

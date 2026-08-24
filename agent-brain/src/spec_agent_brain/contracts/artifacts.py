"""Strict Pydantic contracts for the artifact generation boundary
(Python -> Spring).

The artifact response is its own protocol version: an artifact is a derived,
read-only deliverable (initially only ``spec_snapshot``), never a graph
mutation. The model output carries grounded content and source references
only; the runtime owns every id.
"""

from typing import List, Literal, Optional
from uuid import UUID

from . import protocol
from .decisions import UsageView
from .inputs import StrictModel


class ArtifactSection(StrictModel):
    title: str
    content: str
    source_refs: List[str] = []


class ArtifactGenerationResult(StrictModel):
    artifact_type: Literal["spec_snapshot"]
    sections: List[ArtifactSection]
    unresolved_items: List[str] = []


class AgentArtifactResponse(StrictModel):
    protocol_version: Literal[protocol.ARTIFACT_PROTOCOL_VERSION]
    run_id: UUID
    artifact: ArtifactGenerationResult
    usage: Optional[UsageView] = None


# --- Model output contract (what the LLM must emit, strictly parsed) --------


class ModelArtifactOutput(StrictModel):
    """ARTIFACT_GENERATION model output: grounded artifact content only."""

    artifact_type: Literal["spec_snapshot"]
    sections: List[ArtifactSection]
    unresolved_items: List[str] = []

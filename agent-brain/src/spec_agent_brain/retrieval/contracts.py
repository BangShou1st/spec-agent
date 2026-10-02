"""Shared retrieval.v1 contracts; Java grants scope and owns storage/source validity."""

import hashlib
import math
import struct
from datetime import datetime
from typing import Literal, Self
from uuid import UUID

from pydantic import Field, field_validator, model_validator

from ..wire import WireModel, canonical_hash

SHA256 = r"^[a-f0-9]{64}$"
ENGINE = "python-rag.v1"
MODEL = "qwen3-embedding:0.6b"
QUERY_INSTRUCTION = "Retrieve relevant Spec Agent product documentation and requirement workspace passages for the query."


def vector_checksum(values: list[float]) -> str:
    return hashlib.sha256(struct.pack(f"<{len(values)}f", *values)).hexdigest()


class EmbeddingProfile(WireModel):
    profile_id: str = Field(alias="profileId", pattern=SHA256)
    provider: Literal["OLLAMA_LOCAL"]
    model_tag: Literal["qwen3-embedding:0.6b"] = Field(alias="modelTag")
    model_digest: str = Field(alias="modelDigest", pattern=SHA256)
    dimensions: Literal[1024]
    query_strategy: Literal["qwen-instruct.v1"] = Field(alias="queryStrategy")
    query_instruction: Literal[QUERY_INSTRUCTION] = Field(alias="queryInstruction")
    document_strategy: Literal["raw-text.v1"] = Field(alias="documentStrategy")
    normalization: Literal["l2"]
    truncate: Literal[False]
    splitter_version: Literal["host-source-granularity.v1"] = Field(alias="splitterVersion")
    index_schema_version: Literal["retrieval-index.v2"] = Field(alias="indexSchemaVersion")

    @model_validator(mode="after")
    def identity(self) -> Self:
        if self.profile_id != canonical_hash(self.model_dump(by_alias=True, exclude={"profile_id"})):
            raise ValueError("profile hash mismatch")
        return self


class Workload(WireModel):
    kind: Literal["PROJECT_RUN", "GA_RUN", "CONTEXT_PROJECTION", "INDEX_JOB"]
    id: UUID
    execution_epoch: int = Field(alias="executionEpoch", ge=1, le=9223372036854775807)


class Grant(WireModel):
    grant_id: UUID = Field(alias="grantId")
    version: int = Field(ge=1)
    expires_at: datetime = Field(alias="expiresAt")

    @field_validator("expires_at")
    @classmethod
    def timezone_required(cls, value):
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("grant expiration must have timezone")
        return value


class Limits(WireModel):
    max_items: int = Field(alias="maxItems", ge=1, le=12)
    max_chars: int = Field(alias="maxChars", ge=1, le=12000)
    lane_limit: int = Field(default=48, alias="laneLimit", ge=1, le=48)


class RetrievalEnvelope(WireModel):
    protocol_version: Literal["retrieval.v1"] = Field(alias="protocolVersion")
    request_id: UUID = Field(alias="requestId")
    workload: Workload
    scope_grant: Grant = Field(alias="scopeGrant")
    profile_id: str = Field(alias="profileId", pattern=SHA256)
    index_generation: UUID = Field(alias="indexGeneration")
    deadline: datetime

    @model_validator(mode="after")
    def expiration(self) -> Self:
        if self.deadline.tzinfo is None or self.deadline.utcoffset() is None:
            raise ValueError("deadline must have timezone")
        if self.deadline > self.scope_grant.expires_at:
            raise ValueError("deadline exceeds grant expiration")
        return self


class SearchRequest(RetrievalEnvelope):
    retrieval_engine_version: Literal["python-rag.v1"] = Field(alias="retrievalEngineVersion")
    query: str = Field(min_length=1, max_length=4000)
    mode: Literal["HYBRID", "SEMANTIC_ONLY"]
    limits: Limits

    @field_validator("query")
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError("blank query")
        return value


LaneName = Literal["route-lexical", "route-trigram", "project-lexical", "project-trigram",
                   "resource-lexical", "resource-trigram", "graph", "vector"]


class QueryVector(WireModel):
    dimensions: Literal[1024]
    values: list[float] = Field(min_length=1024, max_length=1024)
    checksum: str = Field(pattern=SHA256)

    @model_validator(mode="after")
    def vector(self) -> Self:
        if not all(math.isfinite(v) for v in self.values):
            raise ValueError("nonfinite vector")
        if abs(math.sqrt(sum(v * v for v in self.values)) - 1.0) > 0.001:
            raise ValueError("vector must be normalized")
        if vector_checksum(self.values) != self.checksum:
            raise ValueError("vector checksum mismatch")
        return self


class CandidateRequest(RetrievalEnvelope):
    query: str = Field(min_length=1, max_length=4000)
    lanes: list[LaneName] = Field(min_length=1, max_length=8)
    lane_limit: int = Field(alias="laneLimit", ge=1, le=48)
    query_vector: QueryVector | None = Field(alias="queryVector")
    max_vector_distance: float = Field(default=0.50, alias="maxVectorDistance", ge=0.0, le=0.65)

    @model_validator(mode="after")
    def lane_vector(self) -> Self:
        if len(set(self.lanes)) != len(self.lanes):
            raise ValueError("duplicate lane")
        if ("vector" in self.lanes) != (self.query_vector is not None):
            raise ValueError("vector lane/payload mismatch")
        return self


class Location(WireModel):
    chunk_index: int | None = Field(default=None, alias="chunkIndex", ge=0)
    start_offset: int | None = Field(default=None, alias="startOffset", ge=0)
    end_offset: int | None = Field(default=None, alias="endOffset", ge=0)

    @model_validator(mode="after")
    def offsets(self) -> Self:
        if (self.start_offset is None) != (self.end_offset is None):
            raise ValueError("both offsets required")
        if self.start_offset is not None and self.end_offset <= self.start_offset:
            raise ValueError("invalid offsets")
        return self


class Source(WireModel):
    entry_id: UUID = Field(alias="entryId")
    corpus_id: UUID = Field(alias="corpusId")
    project_id: UUID | None = Field(alias="projectId")
    origin_route_id: UUID | None = Field(alias="originRouteId")
    source_ref: str = Field(alias="sourceRef", min_length=1, max_length=240)
    source_version: str = Field(alias="sourceVersion", min_length=1, max_length=128)
    content_hash: str = Field(alias="contentHash", pattern=SHA256)
    source_kind: Literal["NODE", "ANSWER", "CLAIM", "RESOURCE_CHUNK", "CAPABILITY_OBSERVATION", "ROUTE_SUMMARY", "HELP_CHUNK"] = Field(alias="sourceKind")
    scope: Literal["ROUTE", "PROJECT", "RESOURCE", "HELP"]
    authority: Literal["CONFIRMED", "USER_AUTHORED", "EXTERNAL_EVIDENCE", "DERIVED", "ASSUMED", "UNRESOLVED", "REJECTED"]
    selection_tier: int = Field(alias="selectionTier", ge=0, le=4)
    content: str = Field(min_length=1, max_length=12000)
    location: Location

    @model_validator(mode="after")
    def scope_fields(self) -> Self:
        if hashlib.sha256(self.content.encode()).hexdigest() != self.content_hash:
            raise ValueError("source content hash mismatch")
        if (self.scope == "HELP") != (self.project_id is None):
            raise ValueError("help and project corpora must be separate")
        if self.scope == "ROUTE" and self.origin_route_id is None:
            raise ValueError("route scope needs origin route")
        if self.scope == "HELP" and (self.origin_route_id is not None or self.source_kind != "HELP_CHUNK"):
            raise ValueError("invalid help source")
        return self


class CandidateLane(WireModel):
    lane: LaneName
    entries: list[Source] = Field(max_length=48)


class CandidateResponse(RetrievalEnvelope):
    lanes: list[CandidateLane] = Field(max_length=8)

    @model_validator(mode="after")
    def unique_lanes(self) -> Self:
        if len({lane.lane for lane in self.lanes}) != len(self.lanes):
            raise ValueError("duplicate candidate lane")
        for lane in self.lanes:
            if len({entry.entry_id for entry in lane.entries}) != len(lane.entries):
                raise ValueError("duplicate candidate in lane")
        return self


class RankedSource(WireModel):
    source: Source
    lanes: list[LaneName] = Field(min_length=1, max_length=8)
    rank_score: float = Field(alias="rankScore", ge=0, allow_inf_nan=False)


class SearchResponse(RetrievalEnvelope):
    retrieval_engine_version: Literal["python-rag.v1"] = Field(alias="retrievalEngineVersion")
    status: Literal["OK", "VECTOR_UNAVAILABLE"]
    vector_unavailable: bool = Field(alias="vectorUnavailable")
    items: list[RankedSource] = Field(max_length=12)
    warnings: list[Literal["VECTOR_UNAVAILABLE"]] = Field(max_length=1)

    @model_validator(mode="after")
    def warning_state(self) -> Self:
        if self.vector_unavailable != (self.status == "VECTOR_UNAVAILABLE"):
            raise ValueError("vector status mismatch")
        if self.warnings != (["VECTOR_UNAVAILABLE"] if self.vector_unavailable else []):
            raise ValueError("vector warning required")
        if sum(len(item.source.content) for item in self.items) > 12000:
            raise ValueError("retrieval content budget exceeded")
        if len({item.source.entry_id for item in self.items}) != len(self.items):
            raise ValueError("duplicate search result")
        return self


class IndexSource(WireModel):
    entry_id: UUID = Field(alias="entryId")
    source_ref: str = Field(alias="sourceRef", min_length=1, max_length=240)
    source_version: str = Field(alias="sourceVersion", min_length=1, max_length=128)
    content_hash: str = Field(alias="contentHash", pattern=SHA256)
    text: str = Field(min_length=1, max_length=12000)
    location: Location

    @model_validator(mode="after")
    def text_hash(self) -> Self:
        if hashlib.sha256(self.text.encode()).hexdigest() != self.content_hash:
            raise ValueError("projection content hash mismatch")
        return self


class IndexBatchRequest(RetrievalEnvelope):
    job_id: UUID = Field(alias="jobId")
    lease_id: UUID = Field(alias="leaseId")
    expected_version: int = Field(alias="expectedVersion", ge=0)
    sources: list[IndexSource] = Field(min_length=1, max_length=16)

    @model_validator(mode="after")
    def job_binding(self) -> Self:
        if self.workload.kind != "INDEX_JOB" or self.workload.id != self.job_id:
            raise ValueError("index job binding mismatch")
        if len({s.entry_id for s in self.sources}) != len(self.sources):
            raise ValueError("duplicate index source")
        if sum(len(s.text) for s in self.sources) > 48000:
            raise ValueError("index batch text limit")
        return self


class IndexVector(WireModel):
    entry_id: UUID = Field(alias="entryId")
    source_ref: str = Field(alias="sourceRef", min_length=1, max_length=240)
    source_version: str = Field(alias="sourceVersion", min_length=1, max_length=128)
    content_hash: str = Field(alias="contentHash", pattern=SHA256)
    location: Location
    vector: QueryVector


class IndexBatchResponse(RetrievalEnvelope):
    job_id: UUID = Field(alias="jobId")
    lease_id: UUID = Field(alias="leaseId")
    expected_version: int = Field(alias="expectedVersion", ge=0)
    vectors: list[IndexVector] = Field(min_length=1, max_length=16)

    @model_validator(mode="after")
    def job_binding(self) -> Self:
        if self.workload.kind != "INDEX_JOB" or self.workload.id != self.job_id:
            raise ValueError("index job binding mismatch")
        if len({v.entry_id for v in self.vectors}) != len(self.vectors):
            raise ValueError("duplicate index vector")
        return self


class ValidationRequest(RetrievalEnvelope):
    sources: list[Source] = Field(max_length=12)


class ValidationResponse(RetrievalEnvelope):
    allowed_entry_ids: list[UUID] = Field(alias="allowedEntryIds", max_length=12)
    rejected_entry_ids: list[UUID] = Field(alias="rejectedEntryIds", max_length=12)

    @model_validator(mode="after")
    def disjoint(self) -> Self:
        values = self.allowed_entry_ids + self.rejected_entry_ids
        if len(set(values)) != len(values):
            raise ValueError("duplicate/contradictory source validation")
        return self


class RetrievalFailure(WireModel):
    protocol_version: Literal["retrieval.v1"] = Field(alias="protocolVersion")
    request_id: UUID = Field(alias="requestId")
    error_code: Literal["UNSUPPORTED_PROFILE", "STALE_SCOPE_GRANT", "STALE_WORKLOAD", "SOURCE_VERSION_MISMATCH",
                        "INDEX_GENERATION_MISMATCH", "OLLAMA_UNAVAILABLE", "RETRIEVAL_UNAVAILABLE",
                        "DEADLINE_EXCEEDED", "INVALID_VECTOR"] = Field(alias="errorCode")


class RetrievalHealth(WireModel):
    protocol_version: Literal["retrieval.v1"] = Field(alias="protocolVersion")
    retrieval_engine_version: Literal["python-rag.v1"] = Field(alias="retrievalEngineVersion")
    ready: bool
    store_ready: bool = Field(alias="storeReady")
    ollama_ready: bool = Field(alias="ollamaReady")
    ollama_version: str | None = Field(alias="ollamaVersion", max_length=64)
    profile: EmbeddingProfile | None
    embedding_concurrency: Literal[1] = Field(alias="embeddingConcurrency")
    max_batch: Literal[16] = Field(alias="maxBatch")

    @model_validator(mode="after")
    def readiness(self) -> Self:
        if self.ready != self.store_ready:
            raise ValueError("retrieval readiness must reflect store availability")
        if self.ollama_ready and (self.profile is None or not self.ollama_version):
            raise ValueError("embedding readiness requires verified profile/version")
        return self


class SplitRequest(RetrievalEnvelope):
    job_id: UUID = Field(alias="jobId")
    lease_id: UUID = Field(alias="leaseId")
    expected_version: int = Field(alias="expectedVersion", ge=0)
    source_ref: str = Field(alias="sourceRef", min_length=1, max_length=240)
    source_version: str = Field(alias="sourceVersion", min_length=1, max_length=128)
    source_kind: Literal["RESOURCE_CHUNK", "HELP_CHUNK"] = Field(alias="sourceKind")
    content_hash: str = Field(alias="contentHash", pattern=SHA256)
    text: str = Field(min_length=1, max_length=200000)

    @model_validator(mode="after")
    def binding_and_hash(self) -> Self:
        if self.workload.kind != "INDEX_JOB" or self.workload.id != self.job_id:
            raise ValueError("split job binding mismatch")
        if hashlib.sha256(self.text.encode()).hexdigest() != self.content_hash or not self.text.strip():
            raise ValueError("source hash mismatch")
        return self


class SourceChunk(WireModel):
    index: int = Field(ge=0, le=255)
    text: str = Field(min_length=1, max_length=12000)
    start_offset: int = Field(alias="startOffset", ge=0)
    end_offset: int = Field(alias="endOffset", gt=0)
    content_hash: str = Field(alias="contentHash", pattern=SHA256)
    headings: list[str] = Field(max_length=6)

    @model_validator(mode="after")
    def position_hash(self) -> Self:
        if self.end_offset-self.start_offset != len(self.text.encode("utf-16-le"))//2:
            raise ValueError("chunk UTF-16 position mismatch")
        if hashlib.sha256(self.text.encode()).hexdigest() != self.content_hash:
            raise ValueError("chunk hash mismatch")
        return self


class SplitResponse(RetrievalEnvelope):
    job_id: UUID = Field(alias="jobId")
    lease_id: UUID = Field(alias="leaseId")
    expected_version: int = Field(alias="expectedVersion", ge=0)
    source_ref: str = Field(alias="sourceRef", min_length=1, max_length=240)
    source_version: str = Field(alias="sourceVersion", min_length=1, max_length=128)
    content_hash: str = Field(alias="contentHash", pattern=SHA256)
    chunks: list[SourceChunk] = Field(min_length=1, max_length=256)

    @model_validator(mode="after")
    def contiguous(self) -> Self:
        if self.workload.kind != "INDEX_JOB" or self.workload.id != self.job_id:
            raise ValueError("split job binding mismatch")
        offset = 0
        for index, chunk in enumerate(self.chunks):
            if chunk.index != index or chunk.start_offset != offset:
                raise ValueError("non-contiguous chunks")
            offset = chunk.end_offset
        if hashlib.sha256("".join(c.text for c in self.chunks).encode()).hexdigest() != self.content_hash:
            raise ValueError("split changed source")
        return self

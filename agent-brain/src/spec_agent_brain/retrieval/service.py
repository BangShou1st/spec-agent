"""Shared bounded retrieval pipeline. Host RPC is the only storage and authorization source."""
from datetime import datetime, timezone
import json
from typing import Protocol

import httpx

from .contracts import (RetrievalEnvelope, CandidateRequest, CandidateResponse, SearchRequest, SearchResponse,
                        RankedSource, ValidationRequest, ValidationResponse, IndexBatchRequest, IndexBatchResponse,
                        IndexVector, RetrievalFailure, RetrievalHealth, SplitRequest, SplitResponse, SourceChunk)
from .embedding import EmbeddingUnavailable, LocalEmbedding, fixed_profile


class RetrievalError(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def envelope(value: RetrievalEnvelope) -> dict:
    return value.model_dump(by_alias=True, mode="json", include=set(RetrievalEnvelope.model_fields))


def remaining(value: RetrievalEnvelope) -> float:
    seconds = (value.deadline - datetime.now(timezone.utc)).total_seconds()
    if seconds <= 0:
        raise RetrievalError("DEADLINE_EXCEEDED")
    if value.profile_id != fixed_profile().profile_id:
        raise RetrievalError("UNSUPPORTED_PROFILE")
    return min(seconds, 30.0)


def same_binding(request: RetrievalEnvelope, response: RetrievalEnvelope):
    if envelope(request) != envelope(response):
        raise RetrievalError("RETRIEVAL_UNAVAILABLE")
    remaining(request)


class Store(Protocol):
    def candidates(self, request: CandidateRequest) -> CandidateResponse: ...
    def validate(self, request: ValidationRequest) -> ValidationResponse: ...
    def validate_index(self, request: IndexBatchRequest) -> IndexBatchRequest: ...


class HostStore:
    def __init__(self, origin: str, secret: str):
        parsed = httpx.URL(origin)
        if parsed.scheme not in {"http", "https"} or not parsed.host or parsed.username or parsed.password or not secret:
            raise ValueError("Retrieval store configuration missing")
        self.base = str(parsed.copy_with(path="/internal/v1/retrieval-store", query=None, fragment=None)).rstrip("/")
        self.secret = secret

    def health(self) -> bool:
        try:
            with httpx.Client(timeout=3.0, trust_env=False, follow_redirects=False) as client:
                with client.stream("GET", self.base + "/health", headers={"X-Spec-Agent-Internal-Token": self.secret}) as response:
                    body = bytearray()
                    for part in response.iter_bytes():
                        body.extend(part)
                        if len(body) > 8192:
                            return False
                    if response.status_code != 200:
                        return False
                    value = json.loads(body)
                    return value == {"protocolVersion": "retrieval.v1", "storeReady": True, "profileId": fixed_profile().profile_id}
        except (httpx.HTTPError, ValueError):
            return False

    def _post(self, path, request, response_type):
        try:
            with httpx.Client(timeout=remaining(request), trust_env=False, follow_redirects=False,
                              transport=httpx.HTTPTransport(retries=0, trust_env=False)) as client:
                with client.stream("POST", self.base + path,
                    headers={"X-Spec-Agent-Internal-Token": self.secret},
                    json=request.model_dump(by_alias=True, mode="json")) as response:
                    body = bytearray()
                    for part in response.iter_bytes():
                        body.extend(part)
                        if len(body) > 2 * 1024 * 1024:
                            raise RetrievalError("RETRIEVAL_UNAVAILABLE")
                        remaining(request)
                    if response.status_code != 200:
                        failure = RetrievalFailure.model_validate_json(bytes(body))
                        if failure.request_id != request.request_id:
                            raise RetrievalError("RETRIEVAL_UNAVAILABLE")
                        raise RetrievalError(failure.error_code)
                    result = response_type.model_validate_json(bytes(body))
                    same_binding(request, result)
                    return result
        except (httpx.HTTPError, ValueError):
            raise RetrievalError("RETRIEVAL_UNAVAILABLE") from None

    def candidates(self, request):
        return self._post("/candidates", request, CandidateResponse)

    def validate(self, request):
        return self._post("/validate-sources", request, ValidationResponse)

    def validate_split(self, request):
        return self._post("/projection-grants", request, SplitRequest)

    def validate_index(self, request):
        return self._post("/index-grants", request, IndexBatchRequest)


class SharedRetrieval:
    def __init__(self, store: Store, embedding: LocalEmbedding):
        self.store, self.embedding = store, embedding

    def health(self) -> RetrievalHealth:
        store_ready = self.store.health()
        try:
            version = self.embedding.verify(3.0)
            ollama_ready = True
        except EmbeddingUnavailable:
            version, ollama_ready = None, False
        return RetrievalHealth.model_validate({"protocolVersion": "retrieval.v1", "retrievalEngineVersion": "python-rag.v1",
            "ready": store_ready, "storeReady": store_ready, "ollamaReady": ollama_ready, "ollamaVersion": version,
            "profile": fixed_profile() if ollama_ready else None, "embeddingConcurrency": 1, "maxBatch": 16})

    def split(self, request: SplitRequest) -> SplitResponse:
        remaining(request)
        authorized = self.store.validate_split(request)
        if authorized != request:
            raise RetrievalError("RETRIEVAL_UNAVAILABLE")
        from .chunks import split_source
        try:
            chunks = split_source(request.text, request.source_kind)
        except ValueError:
            raise RetrievalError("RETRIEVAL_UNAVAILABLE") from None
        remaining(request)
        wire = {**envelope(request), "jobId": str(request.job_id), "leaseId": str(request.lease_id),
            "expectedVersion": request.expected_version, "sourceRef": request.source_ref,
            "sourceVersion": request.source_version, "contentHash": request.content_hash,
            "chunks": [dict(index=c.index, text=c.text, startOffset=c.start_offset,
                endOffset=c.end_offset, contentHash=c.content_hash, headings=list(c.headings)) for c in chunks]}
        return SplitResponse.model_validate_json(json.dumps(wire))

    def search(self, request: SearchRequest) -> SearchResponse:
        budget = remaining(request)
        unavailable = False
        try:
            vector = self.embedding.query(request.query, budget)
        except EmbeddingUnavailable:
            if request.mode == "SEMANTIC_ONLY":
                raise RetrievalError("OLLAMA_UNAVAILABLE") from None
            vector, unavailable = None, True
        remaining(request)
        lanes = ["vector"] if request.mode == "SEMANTIC_ONLY" else [
            "route-lexical", "route-trigram", "project-lexical", "project-trigram",
            "resource-lexical", "resource-trigram", "graph"]
        if vector is not None and "vector" not in lanes:
            lanes.append("vector")
        candidate_request = CandidateRequest.model_validate_json(json.dumps({**envelope(request),
            "query": request.query, "lanes": lanes, "laneLimit": request.limits.lane_limit,
            "queryVector": None if vector is None else vector.model_dump(by_alias=True)}))
        response = self.store.candidates(candidate_request)
        same_binding(request, response)
        if any(lane.lane not in lanes or len(lane.entries) > request.limits.lane_limit for lane in response.lanes):
            raise RetrievalError("RETRIEVAL_UNAVAILABLE")
        fused = {}
        for lane in response.lanes:
            for rank, source in enumerate(lane.entries, 1):
                existing = fused.get(source.entry_id)
                if existing and existing["source"] != source:
                    raise RetrievalError("SOURCE_VERSION_MISMATCH")
                item = fused.setdefault(source.entry_id, {"source": source, "lanes": [], "rankScore": 0.0})
                item["lanes"].append(lane.lane)
                item["rankScore"] += 1.0 / (60 + rank)
        ordered = sorted(fused.values(), key=lambda item: (
            item["source"].selection_tier, -item["rankScore"], item["source"].source_ref, str(item["source"].entry_id)))
        selected, chars = [], 0
        identities = set()
        for item in ordered:
            source = item["source"]
            identity = (source.corpus_id, source.source_ref, source.source_version,
                        source.location.start_offset, source.location.end_offset)
            if identity in identities:
                continue
            identities.add(identity)
            # Never manufacture a truncated content/hash/location combination.
            if chars + len(source.content.encode("utf-16-le")) // 2 > request.limits.max_chars:
                continue
            selected.append(RankedSource.model_validate(item)); chars += len(source.content.encode("utf-16-le")) // 2
            if len(selected) >= request.limits.max_items:
                break
        validation = ValidationRequest.model_validate_json(json.dumps({**envelope(request),
            "sources": [item.source.model_dump(by_alias=True, mode="json") for item in selected]}))
        checked = self.store.validate(validation)
        same_binding(request, checked)
        if set(checked.allowed_entry_ids + checked.rejected_entry_ids) != {item.source.entry_id for item in selected}:
            raise RetrievalError("RETRIEVAL_UNAVAILABLE")
        allowed = set(checked.allowed_entry_ids)
        return SearchResponse.model_validate_json(json.dumps({**envelope(request),
            "retrievalEngineVersion": "python-rag.v1", "status": "VECTOR_UNAVAILABLE" if unavailable else "OK",
            "vectorUnavailable": unavailable, "warnings": ["VECTOR_UNAVAILABLE"] if unavailable else [],
            "items": [item.model_dump(by_alias=True, mode="json") for item in selected if item.source.entry_id in allowed]}))

    def index(self, request: IndexBatchRequest) -> IndexBatchResponse:
        authorized = self.store.validate_index(request)
        if authorized != request:
            raise RetrievalError("STALE_WORKLOAD")
        try:
            vectors = self.embedding.documents([source.text for source in request.sources], remaining(request))
        except EmbeddingUnavailable:
            raise RetrievalError("OLLAMA_UNAVAILABLE") from None
        remaining(request)
        if len(vectors) != len(request.sources):
            raise RetrievalError("INVALID_VECTOR")
        return IndexBatchResponse.model_validate_json(json.dumps({**envelope(request),
            "jobId": str(request.job_id), "leaseId": str(request.lease_id), "expectedVersion": request.expected_version,
            "vectors": [{**source.model_dump(by_alias=True, mode="json", exclude={"text"}),
                         "vector": vector.model_dump(by_alias=True)} for source, vector in zip(request.sources, vectors)]}))

import hashlib
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

import pytest

from spec_agent_brain.retrieval.chunks import split_source
from spec_agent_brain.retrieval.contracts import (SearchRequest, CandidateResponse, ValidationResponse,
                                                 IndexBatchRequest)
from spec_agent_brain.retrieval.embedding import EmbeddingUnavailable, normalized_vector, fixed_profile, LocalEmbedding
from spec_agent_brain.retrieval.service import SharedRetrieval, RetrievalError, envelope


def request(mode="HYBRID"):
    wire = json.loads((Path(__file__).parents[2] / "contracts/retrieval/fixtures/search-request-valid-project.json").read_text(encoding="utf-8"))
    wire["deadline"] = (datetime.now(timezone.utc) + timedelta(seconds=10)).isoformat()
    wire["scopeGrant"]["expiresAt"] = wire["deadline"]
    wire["mode"] = mode
    return SearchRequest.model_validate_json(json.dumps(wire))


def source(ref="node:one", tier=0):
    return dict(entryId=str(uuid4()), corpusId=str(uuid4()), projectId=str(uuid4()), originRouteId=None,
                sourceRef=ref, sourceVersion="v1", contentHash=hashlib.sha256(ref.encode()).hexdigest(),
                sourceKind="NODE", scope="PROJECT", authority="USER_AUTHORED", selectionTier=tier,
                content=ref, location={})


class Embedding:
    unavailable = False
    def query(self, text, timeout):
        if self.unavailable:
            raise EmbeddingUnavailable()
        return normalized_vector([1.0] + [0.0] * 1023)
    def documents(self, texts, timeout):
        return [self.query(text, timeout) for text in texts]


class Store:
    def __init__(self, lanes):
        self.lanes = lanes
        self.rejected = []
        self.requested = None
    def candidates(self, req):
        self.requested = req
        return CandidateResponse.model_validate_json(json.dumps({**envelope(req), "lanes": self.lanes}))
    def validate(self, req):
        return ValidationResponse.model_validate_json(json.dumps({**envelope(req),
            "allowedEntryIds": [str(s.entry_id) for s in req.sources if str(s.entry_id) not in self.rejected],
            "rejectedEntryIds": self.rejected}))
    def validate_index(self, req):
        return req


def test_rrf_deduplicates_and_keeps_host_tiers_and_fresh_source_validation():
    one, two = source("node:one"), source("node:two", 1)
    store = Store([{"lane": "project-lexical", "entries": [two, one]}, {"lane": "vector", "entries": [one, two]}])
    result = SharedRetrieval(store, Embedding()).search(request())
    assert [i.source.source_ref for i in result.items] == ["node:one", "node:two"]
    assert result.items[0].lanes == ["project-lexical", "vector"]
    store.rejected = [one["entryId"]]
    assert [i.source.source_ref for i in SharedRetrieval(store, Embedding()).search(request()).items] == ["node:two"]


def test_ollama_failure_is_explicit_same_pipeline_and_semantic_only_fails():
    embedding = Embedding(); embedding.unavailable = True
    store = Store([{"lane": "project-lexical", "entries": [source()]}])
    result = SharedRetrieval(store, embedding).search(request())
    assert result.vector_unavailable and result.warnings == ["VECTOR_UNAVAILABLE"]
    assert store.requested.query_vector is None and "vector" not in store.requested.lanes
    with pytest.raises(RetrievalError, match="OLLAMA_UNAVAILABLE"):
        SharedRetrieval(store, embedding).search(request("SEMANTIC_ONLY"))


class BrokerEmbedding:
    """Mimics the v2 broker path: remote provider faults surface as coded RetrievalError from Java."""

    def __init__(self, code):
        self.code = code

    def query(self, text, timeout):
        raise RetrievalError(self.code)

    def documents(self, texts, timeout):
        raise RetrievalError(self.code)


def test_remote_embedding_service_faults_degrade_but_credential_failures_stay_explicit():
    for code in ["EMBEDDING_RATE_LIMITED", "EMBEDDING_CONNECTION_FAILED", "EMBEDDING_TIMEOUT", "EMBEDDING_BUSY",
                 "EMBEDDING_PROVIDER_FAILED", "EMBEDDING_INTERRUPTED", "EMBEDDING_INVALID_RESPONSE"]:
        store = Store([{"lane": "project-lexical", "entries": [source()]}])
        result = SharedRetrieval(store, BrokerEmbedding(code)).search(request())
        assert result.vector_unavailable and result.warnings == ["VECTOR_UNAVAILABLE"], code
        assert store.requested.query_vector is None and "vector" not in store.requested.lanes, code
    store = Store([{"lane": "project-lexical", "entries": [source()]}])
    with pytest.raises(RetrievalError, match="EMBEDDING_AUTHENTICATION_FAILED"):
        SharedRetrieval(store, BrokerEmbedding("EMBEDDING_AUTHENTICATION_FAILED")).search(request())
    with pytest.raises(RetrievalError, match="EMBEDDING_RATE_LIMITED"):
        SharedRetrieval(store, BrokerEmbedding("EMBEDDING_RATE_LIMITED")).search(request("SEMANTIC_ONLY"))


def test_inconsistent_source_versions_in_lanes_are_not_merged():
    one = source(); changed = {**one, "sourceVersion": "v2"}
    store = Store([{"lane": "project-lexical", "entries": [one]}, {"lane": "vector", "entries": [changed]}])
    with pytest.raises(RetrievalError, match="SOURCE_VERSION_MISMATCH"):
        SharedRetrieval(store, Embedding()).search(request())


def test_content_budget_preserves_hash_and_offsets_without_truncating():
    one = source(); req = request()
    req = req.model_copy(update={"limits": req.limits.model_copy(update={"max_chars": 2})})
    store = Store([{"lane": "vector", "entries": [one]}])
    assert SharedRetrieval(store, Embedding()).search(req).items == []


def test_expired_or_unsupported_profile_never_embeds_or_queries():
    req = request(); store = Store([])
    for replacement, code in [({"deadline": datetime.now(timezone.utc)-timedelta(seconds=1)}, "DEADLINE_EXCEEDED"),
                              ({"profile_id": "a" * 64}, "UNSUPPORTED_PROFILE")]:
        with pytest.raises(RetrievalError, match=code):
            SharedRetrieval(store, Embedding()).search(req.model_copy(update=replacement))
        assert store.requested is None


def test_index_returns_same_version_position_and_float32_checksum():
    req = request(); job = uuid4()
    wire = envelope(req); wire["workload"] = dict(kind="INDEX_JOB", id=str(job), executionEpoch=1)
    src = dict(entryId=str(uuid4()), sourceRef="answer:one", sourceVersion="v1", text="不可变回答",
               contentHash=hashlib.sha256("不可变回答".encode()).hexdigest(), location={})
    batch = IndexBatchRequest.model_validate_json(json.dumps({**wire, "jobId": str(job), "leaseId": str(uuid4()),
                                                             "expectedVersion": 0, "sources": [src]}))
    result = SharedRetrieval(Store([]), Embedding()).index(batch)
    assert result.vectors[0].source_version == "v1" and result.vectors[0].location == batch.sources[0].location
    assert result.vectors[0].vector.dimensions == 1024


def test_structured_source_granularity_and_original_resource_utf16_positions():
    answer = "不可变回答。" * 300
    assert len(split_source(answer, "ANSWER")) == 1
    original = "  # 标题\r\n\r\n" + ("中文🙂段落。\r\n\r\n" * 400) + "尾部  "
    chunks = split_source(original, "RESOURCE_CHUNK")
    assert len(chunks) > 1 and "".join(c.text for c in chunks) == original
    encoded = original.encode("utf-16-le")
    for chunk in chunks:
        assert encoded[chunk.start_offset*2:chunk.end_offset*2].decode("utf-16-le") == chunk.text
        assert hashlib.sha256(chunk.text.encode()).hexdigest() == chunk.content_hash
    assert chunks[-1].headings == ("标题",)


@pytest.mark.parametrize("url", ["https://ollama.com", "http://example.com", "http://localhost:11434/api", "http://user:secret@localhost:11434"])
def test_embedding_never_routes_to_cloud_or_request_supplied_paths(url):
    with pytest.raises(ValueError):
        LocalEmbedding(url)


def test_profile_matches_frozen_u0_contract():
    assert fixed_profile().profile_id == "26810df4b6b4e7d0d2977bcc57175fcf7e944ebe99f447b5c4e690cb08b8c047"


def test_authorized_split_preserves_original_and_rejects_revoked_before_algorithm(monkeypatch):
    from spec_agent_brain.retrieval.contracts import SplitRequest, SplitResponse
    base = request()
    text = "  # 来源 😀\r\n\r\n" + "保留原始段落及引用。\r\n" * 180
    identity = base.workload.id
    req = SplitRequest.model_validate_json(json.dumps({**envelope(base), "workload": {"kind": "INDEX_JOB", "id": str(identity), "executionEpoch": 1},
        "jobId": str(identity), "leaseId": str(uuid4()), "expectedVersion": 0, "sourceRef": "resource:one",
        "sourceVersion": "version1", "sourceKind": "RESOURCE_CHUNK", "contentHash": hashlib.sha256(text.encode()).hexdigest(), "text": text}))
    class SplitStore:
        revoked = False
        def validate_split(self, value):
            if self.revoked:
                raise RetrievalError("STALE_SCOPE_GRANT")
            return value
    store = SplitStore()
    response = SharedRetrieval(store, Embedding()).split(req)
    assert "".join(c.text for c in response.chunks) == text
    assert response.chunks[-1].end_offset == len(text.encode("utf-16-le"))//2
    assert len(response.chunks)>1
    store.revoked = True
    with pytest.raises(RetrievalError, match="STALE_SCOPE_GRANT"):
        SharedRetrieval(store, Embedding()).split(req)
    wire = response.model_dump(by_alias=True, mode="json")
    wire["chunks"][0]["endOffset"] += 1
    with pytest.raises(ValueError):
        SplitResponse.model_validate(wire)


def test_health_keeps_lexical_ready_when_ollama_is_unavailable():
    class HealthyStore:
        def health(self): return True
    class DownEmbedding:
        def verify(self, timeout): raise EmbeddingUnavailable()
    health = SharedRetrieval(HealthyStore(), DownEmbedding()).health()
    assert health.ready and health.store_ready and not health.ollama_ready
    assert health.profile is None and health.ollama_version is None


def test_vector_distance_is_python_owned_and_host_bounded():
    from spec_agent_brain.retrieval.contracts import CandidateRequest
    wire = {**envelope(request()), "query": "query", "lanes": ["project-lexical"], "laneLimit": 48, "queryVector": None}
    candidate = CandidateRequest.model_validate_json(json.dumps(wire))
    assert candidate.max_vector_distance == 0.50
    wire["maxVectorDistance"] = 0.66
    with pytest.raises(ValueError): CandidateRequest.model_validate_json(json.dumps(wire))


def test_source_hash_is_verified_before_ranking():
    from spec_agent_brain.retrieval.contracts import Source
    wire = source()
    wire["contentHash"] = "0" * 64
    with pytest.raises(ValueError): Source.model_validate_json(json.dumps(wire))

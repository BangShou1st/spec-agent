"""Deterministic v2/broker tests: no provider key and no real network call."""
import json
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest
from langchain_core.embeddings import Embeddings
from pydantic import ValidationError

from spec_agent_brain.wire import canonical_hash
from spec_agent_brain.retrieval.configured_embedding import ApprovalResponse, ApprovedEmbedding, BrokerEmbeddings, BrokerResponse
from spec_agent_brain.retrieval.contracts import SearchRequest, IndexBatchRequest
from spec_agent_brain.retrieval.embedding import EmbeddingUnavailable
from spec_agent_brain.retrieval.service import SharedRetrieval, RetrievalError


def approved(model="model-a", dimensions=3):
    config = dict(provider="OPENAI_COMPATIBLE", baseUrl="https://fixture.invalid/v1", model=model,
                  timeoutSeconds=30, batchSize=8, queryStrategy="raw-text.v1")
    semantic = dict(provider=config["provider"], modelTag=model, modelDigest="unversioned", serviceIdentity=config["baseUrl"],
                    dimensions=dimensions, queryStrategy="raw-text.v1", queryInstruction="", documentStrategy="raw-text.v1",
                    normalization="l2", truncate=False, splitterVersion="host-source-granularity.v1", indexSchemaVersion="retrieval-index.v3")
    return dict(profileId=canonical_hash(semantic), semantic=semantic, config=config, serviceRevision=1)


def request(value=None):
    value = value or approved()
    deadline = (datetime.now(timezone.utc) + timedelta(seconds=30)).isoformat()
    return SearchRequest.model_validate_json(json.dumps(dict(protocolVersion="retrieval.v2", requestId=str(uuid4()),
        workload=dict(kind="CONTEXT_PROJECTION", id=str(uuid4()), executionEpoch=1),
        scopeGrant=dict(grantId=str(uuid4()), version=1, expiresAt=deadline), profileId=value["profileId"],
        indexGeneration=str(uuid4()), deadline=deadline, retrievalEngineVersion="python-rag.v1", query="项目查询", mode="SEMANTIC_ONLY",
        limits=dict(maxItems=8, maxChars=8000, laneLimit=48))))


class Broker:
    def __init__(self):
        self.values = [[2., 0., 0.]]
        self.calls = []
    def broker_vectors(self, req):
        self.calls.append(req.model_dump(by_alias=True, mode="json"))
        return BrokerResponse.model_validate_json(json.dumps(dict(profileId=req.profile_id, requestId=str(req.request_id), dimensions=3, vectors=self.values)))


def test_profiles_are_versioned_hashes_and_same_dimensions_do_not_share_identity():
    a, b = approved(), approved("model-b")
    assert a["profileId"] != b["profileId"]
    ApprovalResponse.model_validate_json(json.dumps(a))
    b["profileId"] = a["profileId"]
    with pytest.raises(ValidationError):
        ApprovalResponse.model_validate_json(json.dumps(b))
    a["apiKey"] = "never-allowed"
    with pytest.raises(ValidationError):
        ApprovalResponse.model_validate_json(json.dumps(a))


def test_standard_langchain_interface_normalizes_actual_dimension_without_provider_credentials():
    broker, req = Broker(), request()
    adapter = BrokerEmbeddings(broker, req)
    assert isinstance(adapter, Embeddings)
    embedding = ApprovedEmbedding(approved(), broker, req)
    vector = embedding.query(req.query, 10)
    assert vector.dimensions == 3 and vector.values == [1., 0., 0.]
    assert broker.calls[0]["profileId"] == req.profile_id
    assert not any(k in json.dumps(broker.calls) for k in ["apiKey", "Authorization", "baseUrl"])
    with pytest.raises(EmbeddingUnavailable):
        adapter.embed_query("unrelated caller text")


@pytest.mark.parametrize("values", [[[1., 0.]], [[0., 0., 0.]], [[float("nan"), 0., 0.]], [[1., 0., 0.], [1., 0., 0.]]])
def test_invalid_broker_vectors_are_never_truncated_or_accepted(values):
    broker = Broker(); broker.values = values
    with pytest.raises((EmbeddingUnavailable, ValidationError)):
        ApprovedEmbedding(approved(), broker, request()).query("项目查询", 10)


def test_unapproved_v2_profile_cannot_bypass_host_registry():
    class Denied:
        def approval(self, req):
            raise RetrievalError("UNSUPPORTED_PROFILE")
    with pytest.raises(RetrievalError, match="UNSUPPORTED_PROFILE"):
        SharedRetrieval(Denied(), object()).search(request())


def test_broker_preserves_host_authorized_document_order_and_rejects_extra_text():
    import hashlib
    base = request().model_dump(by_alias=True, mode="json")
    job = str(uuid4()); base["workload"] = dict(kind="INDEX_JOB", id=job, executionEpoch=1)
    for field in ("retrievalEngineVersion", "query", "mode", "limits"):
        base.pop(field)
    texts = ["第一个文档", "第二个文档"]
    base.update(jobId=job, leaseId=str(uuid4()), expectedVersion=0, sources=[dict(entryId=str(uuid4()), sourceRef=f"help:{n}",
        sourceVersion="1", contentHash=hashlib.sha256(t.encode()).hexdigest(), text=t, location={}) for n, t in enumerate(texts)])
    req = IndexBatchRequest.model_validate_json(json.dumps(base))
    broker = Broker(); broker.values = [[1., 0., 0.], [0., 1., 0.]]
    adapter = BrokerEmbeddings(broker, req)
    assert adapter.embed_documents(texts) == broker.values
    with pytest.raises(EmbeddingUnavailable):
        adapter.embed_documents(list(reversed(texts)))
    vectors = ApprovedEmbedding(approved(), broker, req).documents(texts, 10)
    assert vectors[0].values == [1., 0., 0.] and vectors[1].values == [0., 1., 0.]

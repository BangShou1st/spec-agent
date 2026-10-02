from copy import deepcopy
import hashlib
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from spec_agent_brain.global_assistant.contracts import canonical_hash
from spec_agent_brain.retrieval.contracts import (
    CandidateRequest, EmbeddingProfile, IndexBatchRequest, QueryVector, SearchRequest, SearchResponse, vector_checksum,
)

ROOT = Path(__file__).resolve().parents[2] / "contracts" / "retrieval" / "fixtures"


def fixture(name):
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


def test_both_agents_share_profile_and_same_search_contract():
    ga = SearchRequest.model_validate_json(json.dumps(fixture("search-request-valid-ga.json")))
    project = SearchRequest.model_validate_json(json.dumps(fixture("search-request-valid-project.json")))
    assert ga.profile_id == project.profile_id
    assert ga.retrieval_engine_version == project.retrieval_engine_version == "python-rag.v1"
    profile = EmbeddingProfile.model_validate_json(json.dumps(fixture("embedding-profile-valid.json")))
    assert profile.model_tag == "qwen3-embedding:0.6b" and profile.dimensions == 1024


@pytest.mark.parametrize("prefix,cls", [("search-request", SearchRequest), ("search-response", SearchResponse),
                                       ("store-candidates-request", CandidateRequest), ("embedding-profile", EmbeddingProfile)])
def test_shared_retrieval_fixtures(prefix, cls):
    paths = list(ROOT.glob(f"{prefix}*.json"))
    assert paths
    for path in paths:
        if "-invalid-" in path.name:
            with pytest.raises(ValidationError):
                cls.model_validate_json(path.read_text(encoding="utf-8"))
        else:
            cls.model_validate_json(path.read_text(encoding="utf-8"))


@pytest.mark.parametrize("change", ["modelDigest", "queryInstruction", "normalization", "splitterVersion"])
def test_profile_drift_cannot_keep_old_profile_id(change):
    data = fixture("embedding-profile-valid.json")
    data[change] = "0" * 64 if change == "modelDigest" else "different"
    with pytest.raises(ValidationError):
        EmbeddingProfile.model_validate_json(json.dumps(data))


@pytest.mark.parametrize("kind", ["nonfinite", "wrong-dimension", "not-normalized", "bad-checksum"])
def test_invalid_vector_never_reaches_store(kind):
    values = [1.0] + [0.0] * 1023
    checksum = vector_checksum(values)
    if kind == "nonfinite":
        values[0] = float("nan")
    elif kind == "wrong-dimension":
        values.pop()
    elif kind == "not-normalized":
        values[0] = 2.0
    else:
        checksum = "0" * 64
    with pytest.raises(ValidationError):
        QueryVector(dimensions=1024, values=values, checksum=checksum)


def test_index_batch_requires_claimed_job_and_matching_projection_hash():
    data = fixture("search-request-valid-ga.json")
    base = {k: v for k, v in data.items() if k not in {"query", "mode", "limits", "retrievalEngineVersion"}}
    base["workload"]["kind"] = "INDEX_JOB"
    text = "来源投影文本"
    base.update({"jobId": base["workload"]["id"], "leaseId": base["workload"]["id"], "expectedVersion": 0,
                 "sources": [{"entryId": base["workload"]["id"], "sourceRef": "answer:runtime-owned",
                              "sourceVersion": "1", "contentHash": hashlib.sha256(text.encode()).hexdigest(),
                              "text": text, "location": {}}]})
    IndexBatchRequest.model_validate_json(json.dumps(base))
    bad = deepcopy(base); bad["sources"][0]["text"] = "changed"
    with pytest.raises(ValidationError, match="hash"):
        IndexBatchRequest.model_validate_json(json.dumps(bad))
    bad = deepcopy(base); bad["workload"]["kind"] = "GA_RUN"
    with pytest.raises(ValidationError, match="binding"):
        IndexBatchRequest.model_validate_json(json.dumps(bad))


def test_duplicate_json_keys_and_unapproved_endpoint_fail_closed():
    data = fixture("search-request-valid-ga.json")
    data["ollamaUrl"] = "https://external.example"
    with pytest.raises(ValidationError):
        SearchRequest.model_validate_json(json.dumps(data))
    raw = json.dumps(fixture("search-request-valid-ga.json"))
    raw = raw.replace('"query": "', '"query": "forged", "query": "', 1)
    with pytest.raises(ValidationError, match="Invalid internal JSON"):
        SearchRequest.model_validate_json(raw)

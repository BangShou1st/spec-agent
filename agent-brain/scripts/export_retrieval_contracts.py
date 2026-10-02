"""Export U0 schemas and fixtures for the one shared retrieval service."""
from copy import deepcopy
import json
from pathlib import Path

from spec_agent_brain.global_assistant.contracts import canonical_hash
from spec_agent_brain.retrieval.contracts import (
    SplitRequest, SplitResponse, CandidateRequest, CandidateResponse, EmbeddingProfile, IndexBatchRequest, IndexBatchResponse,
    RetrievalFailure, RetrievalHealth, SearchRequest, SearchResponse, ValidationRequest, ValidationResponse, QUERY_INSTRUCTION, vector_checksum,
)

ROOT = Path(__file__).resolve().parents[2] / "contracts" / "retrieval"


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


for cls, name in [(SplitRequest, "source-chunks-request"), (SplitResponse, "source-chunks-response"), (EmbeddingProfile, "embedding-profile"), (SearchRequest, "search-request"),
                  (SearchResponse, "search-response"), (CandidateRequest, "store-candidates-request"),
                  (CandidateResponse, "store-candidates-response"), (IndexBatchRequest, "index-batch-request"),
                  (IndexBatchResponse, "index-batch-response"), (ValidationRequest, "validate-sources-request"),
                  (ValidationResponse, "validate-sources-response"), (RetrievalFailure, "failure"), (RetrievalHealth, "health")]:
    write(ROOT / f"{name}.schema.json", cls.model_json_schema(by_alias=True))

identity = "00000000-0000-4000-8000-000000000001"
profile_data = {"provider": "OLLAMA_LOCAL", "modelTag": "qwen3-embedding:0.6b",
                "modelDigest": "ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d",
                "dimensions": 1024, "queryStrategy": "qwen-instruct.v1", "queryInstruction": QUERY_INSTRUCTION,
                "documentStrategy": "raw-text.v1", "normalization": "l2", "truncate": False,
                "splitterVersion": "host-source-granularity.v1", "indexSchemaVersion": "retrieval-index.v2"}
profile = {"profileId": canonical_hash(profile_data), **profile_data}
write(ROOT / "fixtures" / "embedding-profile-valid.json", profile)
bad_profile = deepcopy(profile); bad_profile["modelTag"] = "qwen3-embedding:4b"
write(ROOT / "fixtures" / "embedding-profile-invalid-unapproved-model.json", bad_profile)

search = {"protocolVersion": "retrieval.v1", "requestId": identity,
          "workload": {"kind": "GA_RUN", "id": identity, "executionEpoch": 1},
          "scopeGrant": {"grantId": identity, "version": 1, "expiresAt": "2026-10-01T12:00:30Z"},
          "profileId": profile["profileId"], "indexGeneration": identity, "deadline": "2026-10-01T12:00:20Z",
          "retrievalEngineVersion": "python-rag.v1", "query": "如何找到项目？", "mode": "HYBRID",
          "limits": {"maxItems": 8, "maxChars": 8000, "laneLimit": 48}}
write(ROOT / "fixtures" / "search-request-valid-ga.json", search)
project_search = deepcopy(search); project_search["workload"]["kind"] = "CONTEXT_PROJECTION"
write(ROOT / "fixtures" / "search-request-valid-project.json", project_search)
invalid = {}
v = deepcopy(search); v["projectId"] = identity; invalid["self-reported-scope"] = v
v = deepcopy(search); v.pop("scopeGrant"); invalid["missing-grant"] = v
v = deepcopy(search); v["deadline"] = "2026-10-01T12:01:00Z"; invalid["deadline"] = v
v = deepcopy(search); v["limits"]["maxItems"] = 500; invalid["budget"] = v
v = deepcopy(search); v["query"] = "  "; invalid["blank-query"] = v
for name, value in invalid.items():
    write(ROOT / "fixtures" / f"search-request-invalid-{name}.json", value)

base = {k: v for k, v in search.items() if k not in {"retrievalEngineVersion", "mode", "limits"}}
candidate = {**base, "lanes": ["project-lexical", "vector"], "laneLimit": 48,
             "queryVector": {"dimensions": 1024, "values": [1.0] + [0.0] * 1023,
                             "checksum": vector_checksum([1.0] + [0.0] * 1023)}}
write(ROOT / "fixtures" / "store-candidates-request-valid.json", candidate)
v = deepcopy(candidate); v["queryVector"]["values"] = [1.0]
write(ROOT / "fixtures" / "store-candidates-request-invalid-dimensions.json", v)
v = deepcopy(candidate); v["queryVector"] = None
write(ROOT / "fixtures" / "store-candidates-request-invalid-missing-vector.json", v)

degraded = {k: v for k, v in search.items() if k not in {"query", "mode", "limits"}}
degraded.update({"status": "VECTOR_UNAVAILABLE", "vectorUnavailable": True, "items": [], "warnings": ["VECTOR_UNAVAILABLE"]})
write(ROOT / "fixtures" / "search-response-valid-vector-unavailable.json", degraded)
v = deepcopy(degraded); v["warnings"] = []
write(ROOT / "fixtures" / "search-response-invalid-hidden-degradation.json", v)

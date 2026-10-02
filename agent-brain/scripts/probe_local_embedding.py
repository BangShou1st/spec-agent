"""Explicit U0 local embedding probe. Does not pull models, access DB or build an index."""
import argparse
import json
import math
from pathlib import Path
import time
from urllib.parse import urlparse

import httpx
import ollama
from langchain_ollama import OllamaEmbeddings

from spec_agent_brain.retrieval.contracts import EmbeddingProfile, MODEL, QUERY_INSTRUCTION
from spec_agent_brain.global_assistant.contracts import canonical_hash


class ProbeEmbeddings(OllamaEmbeddings):
    """Thin SDK bridge for the missing public truncate option, used only by this probe."""
    def embed_documents(self, texts):
        return self._client.embed(self.model, texts, dimensions=self.dimensions, truncate=False,
                                  keep_alive=self.keep_alive, options=self._default_params)["embeddings"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:11434")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    url = urlparse(args.base_url)
    if url.scheme != "http" or url.hostname not in {"127.0.0.1", "localhost", "::1"} or url.path not in {"", "/"} or url.query or url.fragment or url.username:
        raise ValueError("probe only accepts a local Ollama endpoint")
    options = {"timeout": 120, "follow_redirects": False, "trust_env": False,
               "transport": httpx.HTTPTransport(retries=0)}
    client = ollama.Client(host=args.base_url, **options)
    version = client._client.get("/api/version").json()["version"]
    installed = [model for model in client.list().models if model.model == MODEL]
    if len(installed) != 1:
        raise RuntimeError("required local 0.6B model not installed; no model download attempted")
    digest = installed[0].digest.removeprefix("sha256:")
    was_loaded = any(model.model == MODEL for model in client.ps().models)
    profile_data = {"provider": "OLLAMA_LOCAL", "modelTag": MODEL, "modelDigest": digest,
                    "dimensions": 1024, "queryStrategy": "qwen-instruct.v1", "queryInstruction": QUERY_INSTRUCTION,
                    "documentStrategy": "raw-text.v1", "normalization": "l2", "truncate": False,
                    "splitterVersion": "host-source-granularity.v1", "indexSchemaVersion": "retrieval-index.v2"}
    profile = EmbeddingProfile(profileId=canonical_hash(profile_data), **profile_data)
    model = ProbeEmbeddings(model=MODEL, base_url=args.base_url, dimensions=1024, keep_alive=300,
                            sync_client_kwargs=options, async_client_kwargs={
                                "timeout": 120, "follow_redirects": False, "trust_env": False,
                                "transport": httpx.AsyncHTTPTransport(retries=0)})
    documents = ["在设置页面的模型设置中选择提供商和模型。",
                 "项目可以通过路线分叉探索不同的需求。",
                 "技能页面用于导入和启用技能。"]
    query = f"Instruct: {QUERY_INSTRUCTION}\nQuery: 在哪里调整助手使用的大模型？"
    start = time.perf_counter()
    vectors = model.embed_documents(documents)
    first_ms = (time.perf_counter() - start) * 1000
    start = time.perf_counter()
    query_vector = model.embed_query(query)
    query_ms = (time.perf_counter() - start) * 1000
    start = time.perf_counter()
    model.embed_query(query)
    warm_ms = (time.perf_counter() - start) * 1000
    for vector in [*vectors, query_vector]:
        if len(vector) != 1024 or not all(math.isfinite(v) for v in vector):
            raise RuntimeError("invalid embedding dimensions or values")
        if abs(math.sqrt(sum(v * v for v in vector)) - 1.0) > 0.001:
            raise RuntimeError("embedding not L2 normalized")
    if len(vectors) != len(documents):
        raise RuntimeError("batch result count mismatch")
    scores = [sum(a * b for a, b in zip(query_vector, vector)) for vector in vectors]
    ranked = sorted(range(len(scores)), key=lambda i: scores[i], reverse=True)
    truncate_rejected = False
    try:
        # More than the Qwen3 model context; SDK must fail, never silently truncate.
        model.embed_documents(["检索文档 " * 40000])
    except ollama.ResponseError as exc:
        if "length" in str(exc).lower() or "context" in str(exc).lower() or "too long" in str(exc).lower():
            truncate_rejected = True
        else:
            raise RuntimeError("unexpected overlong-input error") from None
    report = {"status": "PASS" if ranked[0] == 0 and truncate_rejected else "FAIL",
              "ollamaVersion": version, "profile": profile.model_dump(by_alias=True),
              "documentBatch": len(vectors), "finiteVectors": True, "normalizedVectors": True,
              "dimensionsRequestedAndReturned": 1024, "initialModelLoaded": was_loaded,
              "firstBatchMs": round(first_ms, 2), "queryMs": round(query_ms, 2), "warmQueryMs": round(warm_ms, 2),
              "chineseRewriteTop1Expected": ranked[0] == 0, "ranking": ranked,
              "truncateFalseOverlongRejected": truncate_rejected,
              "limitations": "single smoke pair; not 50-query evaluation, pgvector/store integration, P50/P95 or GPU peak"}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    model._client._client.close()
    client._client.close()
    if report["status"] != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()

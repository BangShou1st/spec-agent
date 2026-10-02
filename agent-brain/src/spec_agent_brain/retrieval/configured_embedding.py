"""Host-approved v2 profiles using the standard LangChain Embeddings interface.

Local computation stays in Python. Remote Embeddings calls are delegated to a workload-bound
Java broker; no provider credential is accepted or returned by this module.
"""
import json
import math
import time
from urllib.parse import urlparse
from uuid import UUID

import httpx
from langchain_core.embeddings import Embeddings
from langchain_ollama import OllamaEmbeddings
from ollama import ResponseError
from pydantic import Field, model_validator

from ..wire import WireModel, canonical_hash
from .contracts import SHA256, QUERY_INSTRUCTION
from .embedding import EmbeddingUnavailable, normalized_vector, _capacity


class Config(WireModel):
    provider: str
    base_url: str = Field(alias="baseUrl", max_length=1000)
    model: str = Field(min_length=1, max_length=240)
    timeout_seconds: int = Field(alias="timeoutSeconds", ge=3, le=60)
    batch_size: int = Field(alias="batchSize", ge=1, le=16)
    query_strategy: str = Field(alias="queryStrategy")

    @model_validator(mode="after")
    def valid(self):
        url = urlparse(self.base_url)
        if self.provider not in {"OLLAMA", "OPENAI_COMPATIBLE"} or url.scheme not in {"http", "https"} \
                or not url.hostname or url.username or url.password or url.query or url.fragment \
                or self.query_strategy not in {"raw-text.v1", "qwen-instruct.v1"}:
            raise ValueError("Invalid host embedding configuration")
        return self


class ApprovalResponse(WireModel):
    profile_id: str = Field(alias="profileId", pattern=SHA256)
    semantic: dict
    config: Config
    service_revision: int = Field(alias="serviceRevision", ge=1)

    @model_validator(mode="after")
    def identity(self):
        fields = {"provider", "modelTag", "modelDigest", "serviceIdentity", "dimensions", "queryStrategy",
                  "queryInstruction", "documentStrategy", "normalization", "truncate", "splitterVersion", "indexSchemaVersion"}
        s = self.semantic
        if set(s) != fields or canonical_hash(s) != self.profile_id or type(s["dimensions"]) is not int \
                or not 1 <= s["dimensions"] <= 4096 or s["provider"] != self.config.provider \
                or s["modelTag"] != self.config.model or s["serviceIdentity"] != self.config.base_url \
                or s["queryStrategy"] != self.config.query_strategy or s["documentStrategy"] != "raw-text.v1" \
                or s["normalization"] != "l2" or s["truncate"] is not False \
                or s["splitterVersion"] != "host-source-granularity.v1" or s["indexSchemaVersion"] != "retrieval-index.v3" \
                or s["queryInstruction"] != (QUERY_INSTRUCTION if self.config.query_strategy == "qwen-instruct.v1" else ""):
            raise ValueError("Host profile identity mismatch")
        return self


class BrokerResponse(WireModel):
    profile_id: str = Field(alias="profileId", pattern=SHA256)
    request_id: UUID = Field(alias="requestId")
    dimensions: int = Field(ge=1, le=4096)
    vectors: list[list[float]] = Field(min_length=1, max_length=16)


def approval(value):
    if isinstance(value, ApprovalResponse):
        return value.model_dump(by_alias=True, mode="json")
    return ApprovalResponse.model_validate_json(json.dumps(value)).model_dump(by_alias=True, mode="json")


class NativeOllamaEmbeddings(OllamaEmbeddings):
    """No dimensions parameter is sent to models whose support has not been established."""
    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return self._client.embed(self.model, texts, truncate=False,
                                  options=self._default_params, keep_alive=self.keep_alive)["embeddings"]

    def embed_query(self, text: str) -> list[float]:
        return self.embed_documents([text])[0]


def ollama(config, timeout):
    return NativeOllamaEmbeddings(model=config["model"], base_url=config["baseUrl"], keep_alive=300,
        client_kwargs={"timeout": timeout, "trust_env": False, "follow_redirects": False})


def verify_ollama(config, digest, timeout):
    try:
        with httpx.Client(timeout=timeout, trust_env=False, follow_redirects=False) as client:
            response = client.get(config["baseUrl"] + "/api/tags")
            response.raise_for_status()
            matches = [m for m in response.json()["models"] if m["name"] == config["model"]]
            if len(matches) != 1 or matches[0]["digest"] != digest:
                raise EmbeddingUnavailable("EMBEDDING_MODEL_NOT_FOUND")
            version = client.get(config["baseUrl"] + "/api/version")
            version.raise_for_status()
            return version.json()["version"]
    except (httpx.HTTPError, ValueError, KeyError):
        raise EmbeddingUnavailable("EMBEDDING_CONNECTION_FAILED") from None


def probe_ollama(value):
    config = Config.model_validate_json(json.dumps(value)).model_dump(by_alias=True, mode="json")
    expires = time.monotonic() + config["timeoutSeconds"]
    acquired = _capacity.acquire(timeout=config["timeoutSeconds"])
    if not acquired:
        raise EmbeddingUnavailable("EMBEDDING_TIMEOUT")
    model = None
    try:
        with httpx.Client(timeout=config["timeoutSeconds"], trust_env=False, follow_redirects=False) as client:
            response = client.get(config["baseUrl"] + "/api/tags")
            response.raise_for_status()
            matches = [m for m in response.json()["models"] if m["name"] == config["model"]]
            if len(matches) != 1:
                raise EmbeddingUnavailable("EMBEDDING_MODEL_NOT_FOUND")
            digest = matches[0]["digest"]
        left = expires - time.monotonic()
        if left <= 0:
            raise EmbeddingUnavailable("EMBEDDING_TIMEOUT")
        model = ollama(config, left)
        documents = model.embed_documents(["Spec Agent connection test document"])
        model._client.close()
        left = expires - time.monotonic()
        if left <= 0:
            raise EmbeddingUnavailable("EMBEDDING_TIMEOUT")
        model = ollama(config, left)
        query = model.embed_query("Spec Agent connection test query")
        if time.monotonic() >= expires:
            raise EmbeddingUnavailable("EMBEDDING_TIMEOUT")
        if len(documents) != 1 or not 1 <= len(query) <= 4096 or len(query) != len(documents[0]):
            raise EmbeddingUnavailable("UNSUPPORTED_DIMENSIONS")
        normalized_vector(query, len(query))
        normalized_vector(documents[0], len(query))
        return {"dimensions": len(query), "digest": digest}
    except httpx.TimeoutException:
        raise EmbeddingUnavailable("EMBEDDING_TIMEOUT") from None
    except (httpx.HTTPError, ValueError, KeyError, RuntimeError, ResponseError):
        raise EmbeddingUnavailable("EMBEDDING_CONNECTION_FAILED") from None
    finally:
        if model is not None:
            model._client.close()
        _capacity.release()


class BrokerEmbeddings(Embeddings):
    def __init__(self, store, request):
        self.store, self.request = store, request

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        # Java derives authorized texts from the immutable job, not these caller arguments.
        if not hasattr(self.request, "sources") or texts != [s.text for s in self.request.sources]:
            raise EmbeddingUnavailable("Broker document binding mismatch")
        response = self.store.broker_vectors(self.request)
        if len(response.vectors) != len(texts):
            raise EmbeddingUnavailable("Broker vector cardinality mismatch")
        return response.vectors

    def embed_query(self, text: str) -> list[float]:
        if not hasattr(self.request, "query") or text != self.request.query:
            raise EmbeddingUnavailable("Broker query binding mismatch")
        response = self.store.broker_vectors(self.request)
        if len(response.vectors) != 1:
            raise EmbeddingUnavailable("Broker query cardinality mismatch")
        return response.vectors[0]


class ApprovedEmbedding:
    def __init__(self, value, store, request):
        self.approved = approval(value)
        self.config, self.semantic = self.approved["config"], self.approved["semantic"]
        if self.approved["profileId"] != request.profile_id or request.protocol_version != "retrieval.v2":
            raise EmbeddingUnavailable("Host profile binding mismatch")
        self.store, self.request = store, request

    def _embed(self, texts, timeout, query):
        if not texts or len(texts) > 16 or any(not t.strip() or len(t) > 12000 for t in texts):
            raise EmbeddingUnavailable("Embedding limits")
        acquired = False
        adapter = None
        expires = time.monotonic() + min(timeout, self.config["timeoutSeconds"])
        try:
            if self.config["provider"] == "OPENAI_COMPATIBLE":
                adapter = BrokerEmbeddings(self.store, self.request)
                values = [adapter.embed_query(texts[0])] if query else adapter.embed_documents(texts)
            else:
                acquired = _capacity.acquire(timeout=max(0, expires - time.monotonic()))
                if not acquired:
                    raise EmbeddingUnavailable("Embedding capacity exhausted")
                verify_ollama(self.config, self.semantic["modelDigest"], max(0.001, expires - time.monotonic()))
                adapter = ollama(self.config, max(0.001, expires - time.monotonic()))
                if query:
                    text = texts[0]
                    if self.config["queryStrategy"] == "qwen-instruct.v1":
                        text = f"Instruct: {QUERY_INSTRUCTION}\nQuery: {text}"
                    values = [adapter.embed_query(text)]
                else:
                    values = []
                    batch = self.config["batchSize"]
                    for n in range(0, len(texts), batch):
                        if time.monotonic() >= expires:
                            raise EmbeddingUnavailable("Embedding deadline exceeded")
                        adapter._client.close()
                        adapter = ollama(self.config, max(0.001, expires - time.monotonic()))
                        values.extend(adapter.embed_documents(texts[n:n+batch]))
            if time.monotonic() >= expires or len(values) != len(texts):
                raise EmbeddingUnavailable("Embedding response deadline/cardinality")
            return [normalized_vector(v, self.semantic["dimensions"]) for v in values]
        except (httpx.HTTPError, ValueError, RuntimeError, ResponseError):
            raise EmbeddingUnavailable("Configured embedding failed") from None
        finally:
            if isinstance(adapter, NativeOllamaEmbeddings):
                adapter._client.close()
            if acquired:
                _capacity.release()

    def documents(self, texts, timeout):
        return self._embed(texts, timeout, False)

    def query(self, text, timeout):
        return self._embed([text], timeout, True)[0]

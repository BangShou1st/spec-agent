"""One local Ollama implementation for index and query, with a pinned semantic profile."""
import math
import struct
import time
from threading import BoundedSemaphore
from urllib.parse import urlparse

import httpx
from langchain_ollama import OllamaEmbeddings
from ollama import ResponseError

from .contracts import EmbeddingProfile, MODEL, QUERY_INSTRUCTION, QueryVector, vector_checksum
from ..wire import canonical_hash

MODEL_DIGEST = "ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d"
_capacity = BoundedSemaphore(1)


class EmbeddingUnavailable(Exception):
    pass


def fixed_profile() -> EmbeddingProfile:
    fields = dict(provider="OLLAMA_LOCAL", modelTag=MODEL, modelDigest=MODEL_DIGEST, dimensions=1024,
                  queryStrategy="qwen-instruct.v1", queryInstruction=QUERY_INSTRUCTION,
                  documentStrategy="raw-text.v1", normalization="l2", truncate=False,
                  splitterVersion="host-source-granularity.v1", indexSchemaVersion="retrieval-index.v2")
    return EmbeddingProfile.model_validate({"profileId": canonical_hash(fields), **fields})


class UntruncatedOllamaEmbeddings(OllamaEmbeddings):
    """Pinned SDK supports truncate; the locked LangChain adapter doesn't expose it yet."""
    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return self._client.embed(self.model, texts, dimensions=1024, truncate=False,
                                  options=self._default_params, keep_alive=self.keep_alive)["embeddings"]

    async def aembed_documents(self, texts: list[str]) -> list[list[float]]:
        return (await self._async_client.embed(self.model, texts, dimensions=1024, truncate=False,
                                              options=self._default_params, keep_alive=self.keep_alive))["embeddings"]


class LocalEmbedding:
    def __init__(self, base_url: str = "http://127.0.0.1:11434"):
        parsed = urlparse(base_url)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1", "host.docker.internal"} \
                or parsed.username or parsed.password or parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
            raise ValueError("Embedding requires a configured local Ollama origin")
        self.base_url = base_url.rstrip("/")
        self.profile = fixed_profile()

    def verify(self, timeout: float) -> str:
        expires = time.monotonic() + timeout
        try:
            with httpx.Client(timeout=timeout, trust_env=False, follow_redirects=False) as client:
                tags = client.get(self.base_url + "/api/tags")
                tags.raise_for_status()
                match = [m for m in tags.json()["models"] if m["name"] == MODEL and m["digest"] == MODEL_DIGEST]
                if len(match) != 1:
                    raise EmbeddingUnavailable("Ollama model digest differs from pinned profile")
                remaining = expires - time.monotonic()
                if remaining <= 0:
                    raise EmbeddingUnavailable("Embedding verification deadline exceeded")
                version = client.get(self.base_url + "/api/version", timeout=remaining)
                version.raise_for_status()
                return version.json()["version"]
        except (httpx.HTTPError, KeyError, ValueError) as exc:
            raise EmbeddingUnavailable("Local Ollama verification unavailable") from exc

    def documents(self, texts: list[str], timeout: float) -> list[QueryVector]:
        if not 1 <= len(texts) <= 16 or any(not text.strip() or len(text) > 12000 for text in texts):
            raise ValueError("Embedding batch/text limit")
        expires = time.monotonic() + timeout
        if not _capacity.acquire(timeout=max(0, timeout)):
            raise EmbeddingUnavailable("Embedding capacity exhausted")
        try:
            self.verify(max(0.001, expires - time.monotonic()))
            if time.monotonic() >= expires:
                raise EmbeddingUnavailable("Embedding deadline exceeded")
            embedding = UntruncatedOllamaEmbeddings(model=MODEL, base_url=self.base_url, dimensions=1024,
                keep_alive=300, client_kwargs={"timeout": expires - time.monotonic(), "trust_env": False, "follow_redirects": False})
            try:
                values = embedding.embed_documents(texts)
            finally:
                embedding._client.close()
            if len(values) != len(texts):
                raise ValueError("Ollama batch cardinality mismatch")
            return [normalized_vector(vector) for vector in values]
        except (httpx.HTTPError, ResponseError, ValueError, RuntimeError) as exc:
            raise EmbeddingUnavailable("Local embedding failed") from exc
        finally:
            _capacity.release()

    def query(self, text: str, timeout: float) -> QueryVector:
        return self.documents([f"Instruct: {QUERY_INSTRUCTION}\nQuery: {text}"], timeout)[0]


def normalized_vector(values: list[float]) -> QueryVector:
    if len(values) != 1024 or not all(math.isfinite(value) for value in values):
        raise ValueError("Invalid Ollama vector")
    norm = math.sqrt(sum(value * value for value in values))
    if norm <= 0:
        raise ValueError("Zero embedding")
    # Store checksums over the actual float32 representation persisted by pgvector.
    stored = list(struct.unpack("<1024f", struct.pack("<1024f", *[value / norm for value in values])))
    return QueryVector.model_validate({"dimensions": 1024, "values": stored, "checksum": vector_checksum(stored)})

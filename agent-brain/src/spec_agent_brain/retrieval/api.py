"""Independent retrieval endpoints; no provider keys, DB credentials or Agent checkpoint state."""
import asyncio
import os
from fastapi import APIRouter, HTTPException, Request

from .contracts import SearchRequest, IndexBatchRequest, SplitRequest
from .embedding import LocalEmbedding
from .service import HostStore, SharedRetrieval, RetrievalError


def retrieval_router(settings, authenticate):
    router = APIRouter(prefix="/internal/v1/retrieval", dependencies=authenticate)

    def service(request):
        factory = getattr(request.app.state, "retrieval_factory", None)
        if factory:
            return factory()
        if not settings.internal_secret:
            raise HTTPException(503, "RETRIEVAL_NOT_CONFIGURED")
        return SharedRetrieval(HostStore(settings.broker_url, settings.internal_secret),
            LocalEmbedding(os.environ.get("SPEC_AGENT_OLLAMA_URL", "http://127.0.0.1:11434")))

    async def execute(request, contract, operation):
        body = bytearray()
        async for part in request.stream():
            body.extend(part)
            if len(body) > 1024 * 1024:
                raise HTTPException(413, "RETRIEVAL_REQUEST_TOO_LARGE")
        try:
            parsed = contract.model_validate_json(bytes(body))
        except ValueError:
            raise HTTPException(422, "RETRIEVAL_PROTOCOL_ERROR") from None
        try:
            result = await asyncio.to_thread(getattr(service(request), operation), parsed)
            return result.model_dump(by_alias=True, mode="json")
        except RetrievalError as exc:
            from starlette.responses import JSONResponse
            return JSONResponse(status_code=503 if exc.code in {"OLLAMA_UNAVAILABLE", "RETRIEVAL_UNAVAILABLE"} else 409,
                content={"protocolVersion": parsed.protocol_version, "requestId": str(parsed.request_id), "errorCode": exc.code})

    @router.get("/health")
    async def health(request: Request):
        result = await asyncio.to_thread(service(request).health)
        return result.model_dump(by_alias=True, mode="json")

    @router.post("/search")
    async def search(request: Request):
        return await execute(request, SearchRequest, "search")

    @router.post("/index-batches")
    async def index(request: Request):
        return await execute(request, IndexBatchRequest, "index")

    @router.post("/source-chunks")
    async def split(request: Request):
        return await execute(request, SplitRequest, "split")

    @router.post("/ollama-probe")
    async def ollama_probe(request: Request):
        from .configured_embedding import probe_ollama
        from .embedding import EmbeddingUnavailable
        try:
            body = await request.body()
            if len(body) > 8192:
                raise HTTPException(413, "RETRIEVAL_REQUEST_TOO_LARGE")
            import json
            return await asyncio.to_thread(probe_ollama, json.loads(body))
        except EmbeddingUnavailable as exc:
            raise HTTPException(503, str(exc)) from None
        except ValueError:
            raise HTTPException(422, "EMBEDDING_INVALID_RESPONSE") from None

    return router

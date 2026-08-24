"""FastAPI application of the Python agent-brain service.

HTTP surface (Stage A):

    GET  /health
    POST /v1/state-updates
    POST /v1/decisions
    POST /v1/artifacts

The service is stateless: it receives a frozen versioned request envelope,
runs one brain operation (one model call through the Java internal inference
broker or the deterministic fake), and returns a proposal-only response that
Java validates fail-closed before any persistence.
"""

import hmac
from typing import Annotated, Any, Dict

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import ValidationError

from . import __version__
from .config import Settings, load_settings
from .contracts.protocol import (
    ARTIFACT_PROTOCOL_VERSION,
    DECISION_PROTOCOL_VERSION,
    INPUT_PROTOCOL_VERSION,
)
from .artifact import BrainContractError as ArtifactBrainContractError
from .artifact import handle_artifact
from .decision import BrainContractError as DecisionBrainContractError
from .decision import handle_decision
from .model_client import BrokerModelClient, FakeModelClient, ModelClient, ModelClientError
from .state_update import BrainContractError as StateUpdateBrainContractError
from .state_update import handle_state_update


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved = settings or load_settings()
    app = FastAPI(title="spec-agent-brain", version=__version__)

    def model_client() -> ModelClient:
        if resolved.model_mode == "broker":
            return BrokerModelClient(
                resolved.broker_url,
                resolved.internal_secret,
                resolved.broker_timeout_seconds,
            )
        return FakeModelClient()

    def require_internal_token(
        x_spec_agent_internal_token: Annotated[
            str | None, Header(alias="X-Spec-Agent-Internal-Token")
        ] = None,
    ) -> None:
        if not resolved.auth_enabled:
            return
        if x_spec_agent_internal_token is None or not hmac.compare_digest(
            x_spec_agent_internal_token, resolved.internal_secret
        ):
            raise HTTPException(status_code=401, detail="unauthorized")

    @app.get("/health")
    def health() -> Dict[str, Any]:
        return {
            "status": "ok",
            "protocolVersion": INPUT_PROTOCOL_VERSION,
            "modelMode": resolved.model_mode,
        }

    @app.post("/v1/state-updates", dependencies=[Depends(require_internal_token)])
    def state_updates(request: Dict[str, Any]) -> Dict[str, Any]:
        envelope = _parse(request)
        try:
            response = handle_state_update(envelope, model_client())
        except (StateUpdateBrainContractError, ModelClientError) as exc:
            raise HTTPException(status_code=502, detail=f"brain_failure:{type(exc).__name__}")
        return _dump(response)

    @app.post("/v1/decisions", dependencies=[Depends(require_internal_token)])
    def decisions(request: Dict[str, Any]) -> Dict[str, Any]:
        envelope = _parse(request)
        try:
            response = handle_decision(envelope, model_client())
        except (DecisionBrainContractError, ModelClientError) as exc:
            raise HTTPException(status_code=502, detail=f"brain_failure:{type(exc).__name__}")
        return _dump(response)

    @app.post("/v1/artifacts", dependencies=[Depends(require_internal_token)])
    def artifacts(request: Dict[str, Any]) -> Dict[str, Any]:
        envelope = _parse(request)
        try:
            response = handle_artifact(envelope, model_client())
        except (ArtifactBrainContractError, ModelClientError) as exc:
            raise HTTPException(status_code=502, detail=f"brain_failure:{type(exc).__name__}")
        data = response.model_dump(mode="json", by_alias=True)
        data["protocolVersion"] = ARTIFACT_PROTOCOL_VERSION
        return data

    def _parse(request: Dict[str, Any]):
        from .contracts.inputs import parse_request_envelope

        try:
            return parse_request_envelope(request)
        except ValidationError as exc:
            raise HTTPException(status_code=422, detail="contract_violation") from exc

    def _dump(envelope) -> Dict[str, Any]:
        data = envelope.model_dump(mode="json", by_alias=True)
        data["protocolVersion"] = DECISION_PROTOCOL_VERSION
        return data

    return app


app = create_app()

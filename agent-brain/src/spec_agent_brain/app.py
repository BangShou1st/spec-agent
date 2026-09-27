"""文件名:app.py

用途:Python agent-brain 服务的 FastAPI 应用,定义对 Java Runtime 暴露的
HTTP 接口(Stage A):

    GET  /health
    POST /v1/state-updates
    POST /v1/decisions
    POST /v1/artifacts

服务本身无状态:接收冻结的带版本号请求信封,执行一次 brain 操作
(通过 Java 内部推理 broker 发起一次模型调用,或走确定性的 fake 实现),
返回 proposal-only 的响应,由 Java 侧在持久化之前做 fail-closed 校验。
"""

import hmac
import logging
from threading import Lock
from typing import Annotated, Any, Dict

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import ValidationError

from . import __version__
from .config import ConfigurationError, Settings, load_settings
from .contracts.protocol import (
    ARTIFACT_PROTOCOL_VERSION,
    INPUT_PROTOCOL_VERSION,
)
from .artifact import BrainContractError as ArtifactBrainContractError
from .artifact import handle_artifact
from .decision import ActionIneligibleBrainError
from .decision import BrainContractError as DecisionBrainContractError
from .decision import handle_decision
from .model_client import BrokerModelClient, FakeModelClient, ModelClient, ModelClientError
from .state_update import BrainContractError as StateUpdateBrainContractError
from .state_update import handle_state_update

logger = logging.getLogger("spec_agent_brain")


def _fail(reason: Exception, run_id: Any, operation: str) -> HTTPException:
    """把真实失败原因写进服务日志,再折叠成统一的 502 状态码。

    以前只有异常类名能穿过 HTTP 边界,导致预算耗尽、模型输出非 JSON、
    违反输出契约、broker 不可达等完全不同的原因,到达 Java 时都变成了
    同一种不可分辨的 "brain_unavailable" 失败。这里保持状态码和 detail
    格式不变,把诊断信息落到服务日志里。
    """
    logger.warning("%s failed run=%s: %s: %s",
                   operation, run_id, type(reason).__name__, reason)
    return HTTPException(status_code=502,
                         detail=f"brain_failure:{type(reason).__name__}")


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved = settings or load_settings()
    app = FastAPI(title="spec-agent-brain", version=__version__)
    invocation_lock = Lock()
    invocation_counts = {
        "STATE_UPDATE": 0,
        "DECISION": 0,
    }
    last_run_ids: dict[str, str | None] = {
        "STATE_UPDATE": None,
        "DECISION": None,
    }

    def record_invocation(call_type: str, run_id: str) -> None:
        # 只记录可用于运维的安全信息:调用计数与 run id。
        # prompt、补全内容、推理过程和凭证一律不进入这个探针。
        with invocation_lock:
            invocation_counts[call_type] += 1
            last_run_ids[call_type] = run_id

    def model_client() -> ModelClient:
        # 配置校验在 load_settings 已保证只有 fake/broker 能到达这里;
        # 手工构造的非法 Settings 仍在此显式失败,绝不静默回落 fake。
        if resolved.model_mode == "broker":
            return BrokerModelClient(
                resolved.broker_url,
                resolved.internal_secret,
                resolved.broker_timeout_seconds,
            )
        if resolved.model_mode == "fake":
            return FakeModelClient()
        raise ConfigurationError(f"Unknown model mode: {resolved.model_mode!r}")

    def config_error() -> str | None:
        try:
            if resolved.model_mode not in ("fake", "broker"):
                return "unknown model mode"
            if resolved.model_mode == "broker" and (
                not resolved.broker_url.startswith(("http://", "https://"))
                or not resolved.internal_secret
            ):
                return "broker mode requires a valid broker URL and an internal secret"
            return None
        except Exception as exc:  # pragma: no cover - 防御性
            return str(exc)

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
        with invocation_lock:
            counts = dict(invocation_counts)
            runs = dict(last_run_ids)
        error = config_error()
        return {
            # 配置错误时服务不报告就绪,即使进程仍在运行
            "status": "ok" if error is None else "config_error",
            "ready": error is None,
            "configError": error,
            "protocolVersion": INPUT_PROTOCOL_VERSION,
            "modelMode": resolved.model_mode,
            "authEnabled": resolved.auth_enabled,
            "invocations": {
                "stateUpdates": counts["STATE_UPDATE"],
                "decisions": counts["DECISION"],
                "lastStateUpdateRunId": runs["STATE_UPDATE"],
                "lastDecisionRunId": runs["DECISION"],
            },
        }

    @app.post("/v1/state-updates", dependencies=[Depends(require_internal_token)])
    def state_updates(request: Dict[str, Any]) -> Dict[str, Any]:
        envelope = _parse(request)
        record_invocation("STATE_UPDATE", str(envelope.run_id))
        try:
            response = handle_state_update(envelope, model_client())
        except (StateUpdateBrainContractError, ModelClientError) as exc:
            raise _fail(exc, envelope.run_id, "STATE_UPDATE") from exc
        return _dump(response)

    @app.post("/v1/decisions", dependencies=[Depends(require_internal_token)])
    def decisions(request: Dict[str, Any]) -> Dict[str, Any]:
        envelope = _parse(request)
        record_invocation("DECISION", str(envelope.run_id))
        try:
            response = handle_decision(envelope, model_client())
        except ActionIneligibleBrainError as exc:
            logger.info("DECISION ineligible run=%s: %s", envelope.run_id, exc)
            raise HTTPException(status_code=409, detail="ACTION_INELIGIBLE") from exc
        except (DecisionBrainContractError, ModelClientError) as exc:
            raise _fail(exc, envelope.run_id, "DECISION") from exc
        return _dump(response)

    @app.post("/v1/artifacts", dependencies=[Depends(require_internal_token)])
    def artifacts(request: Dict[str, Any]) -> Dict[str, Any]:
        envelope = _parse(request)
        try:
            response = handle_artifact(envelope, model_client())
        except (ArtifactBrainContractError, ModelClientError) as exc:
            raise _fail(exc, envelope.run_id, "ARTIFACT") from exc
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
        return data

    return app


app = create_app()

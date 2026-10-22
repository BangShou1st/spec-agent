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

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
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

# GA 执行协议版本公告(design §8):本构建接受的执行信封与执行事件版本。
# 唯一事实来源是 global_assistant.contracts 的 Literal 字面量;这里显式列出,
# 使 /health 成为 Java 发起执行前的协商端点——本构建不认识的版本绝不会被发送。
GA_EXECUTION_PROTOCOL_VERSIONS = [
    "ga-execution.v1",
    "ga-execution-event.v1",
]


class BrainFailureHTTPException(HTTPException):
    """Keep the legacy detail marker while attaching separate safe diagnostics."""

    def __init__(self, error_type: str, diagnostic: dict[str, Any] | None):
        super().__init__(status_code=502, detail=f"brain_failure:{error_type}")
        self.contract_diagnostic = diagnostic


def _fail(reason: Exception, run_id: Any, operation: str) -> HTTPException:
    """只透传类型化、脱敏的诊断;不把异常文本或模型输出写入日志/HTTP。"""
    diagnostic = getattr(reason, "diagnostic", None)
    safe_diagnostic = None
    if diagnostic is not None:
        safe_diagnostic = {
            "diagnosticId": diagnostic.diagnostic_id,
            "stage": diagnostic.stage,
            "validatorId": diagnostic.validator_id,
            "machineCode": diagnostic.machine_code,
            "fieldPath": diagnostic.field_path,
            "expectedType": diagnostic.expected_type,
            "actualType": diagnostic.actual_type,
            "boundary": diagnostic.boundary,
            "javaWireValidatorEntered": diagnostic.java_wire_validator_entered,
            "executorReached": diagnostic.executor_reached,
            "sourceExceptionType": diagnostic.source_exception_type,
        }
        logger.warning(
            "%s failed run=%s errorType=%s diagnosticId=%s machineCode=%s stage=%s",
            operation, run_id, type(reason).__name__, diagnostic.diagnostic_id,
            diagnostic.machine_code, diagnostic.stage,
        )
    else:
        logger.warning("%s failed run=%s errorType=%s diagnosticStatus=UNKNOWN",
                       operation, run_id, type(reason).__name__)
    return BrainFailureHTTPException(type(reason).__name__, safe_diagnostic)


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved = settings or load_settings()
    app = FastAPI(title="spec-agent-brain", version=__version__)

    @app.exception_handler(BrainFailureHTTPException)
    async def brain_failure_response(_request: Request, exc: BrainFailureHTTPException):
        body: dict[str, Any] = {"detail": exc.detail}
        if exc.contract_diagnostic is None:
            body["diagnosticStatus"] = "UNKNOWN"
        else:
            body["contractDiagnostic"] = exc.contract_diagnostic
        return JSONResponse(status_code=exc.status_code, content=body, headers=exc.headers)
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

    def model_client(execution_grant: str | None = None) -> ModelClient:
        # 配置校验在 load_settings 已保证只有 fake/broker 能到达这里;
        # 手工构造的非法 Settings 仍在此显式失败,绝不静默回落 fake。
        if resolved.model_mode == "broker":
            return BrokerModelClient(
                resolved.broker_url,
                resolved.internal_secret,
                resolved.broker_timeout_seconds,
                execution_grant=execution_grant,
            )
        if resolved.model_mode == "fake":
            return FakeModelClient()
        raise ConfigurationError(f"Unknown model mode: {resolved.model_mode!r}")

    from contextlib import contextmanager

    @contextmanager
    def request_model_client(execution_grant: str | None):
        client = model_client(execution_grant)
        try:
            yield client
        finally:
            if resolved.model_mode == "broker":
                client.close()

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
            # GA 执行协议的能力公告(design §8"显式版本化,不默认允许旧请求"):
            # Java 在发起执行前核对本列表,本构建不认识的版本绝不发送。
            # contracts.py 的 Literal 字面量是唯一事实来源;两者必须同步演进。
            "gaExecutionProtocolVersions": GA_EXECUTION_PROTOCOL_VERSIONS,
            "workspaceRpcGrantVersions": ["workspace-rpc-grant.v1"],
            "modelMode": resolved.model_mode,
            "authEnabled": resolved.auth_enabled,
            "invocations": {
                "stateUpdates": counts["STATE_UPDATE"],
                "decisions": counts["DECISION"],
                "lastStateUpdateRunId": runs["STATE_UPDATE"],
                "lastDecisionRunId": runs["DECISION"],
            },
        }

    from .global_assistant.execution import execution_router
    app.include_router(execution_router(resolved, [Depends(require_internal_token)]))
    from .retrieval.api import retrieval_router
    app.include_router(retrieval_router(resolved, [Depends(require_internal_token)]))

    @app.post("/v1/state-updates", dependencies=[Depends(require_internal_token)])
    def state_updates(request: Dict[str, Any], execution_grant: Annotated[str | None, Header(alias="X-Spec-Agent-Execution-Grant")] = None) -> Dict[str, Any]:
        envelope = _parse(request)
        record_invocation("STATE_UPDATE", str(envelope.run_id))
        try:
            with request_model_client(execution_grant) as client:
                response = handle_state_update(envelope, client)
        except (StateUpdateBrainContractError, ModelClientError) as exc:
            raise _fail(exc, envelope.run_id, "STATE_UPDATE") from exc
        return _dump(response)

    @app.post("/v1/decisions", dependencies=[Depends(require_internal_token)])
    def decisions(request: Dict[str, Any], execution_grant: Annotated[str | None, Header(alias="X-Spec-Agent-Execution-Grant")] = None) -> Dict[str, Any]:
        envelope = _parse(request)
        record_invocation("DECISION", str(envelope.run_id))
        try:
            with request_model_client(execution_grant) as client:
                response = handle_decision(envelope, client)
        except ActionIneligibleBrainError as exc:
            logger.info("DECISION ineligible run=%s: %s", envelope.run_id, exc)
            raise HTTPException(status_code=409, detail="ACTION_INELIGIBLE") from exc
        except (DecisionBrainContractError, ModelClientError) as exc:
            raise _fail(exc, envelope.run_id, "DECISION") from exc
        return _dump(response)

    @app.post("/v1/artifacts", dependencies=[Depends(require_internal_token)])
    def artifacts(request: Dict[str, Any], execution_grant: Annotated[str | None, Header(alias="X-Spec-Agent-Execution-Grant")] = None) -> Dict[str, Any]:
        envelope = _parse(request)
        try:
            with request_model_client(execution_grant) as client:
                response = handle_artifact(envelope, client)
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

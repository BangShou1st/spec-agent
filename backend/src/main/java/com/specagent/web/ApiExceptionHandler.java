package com.specagent.web;

import com.specagent.agent.protocol.ModelContractException;
import com.specagent.agent.runtime.IdempotencyKeyReusedException;
import com.specagent.common.ApiErrorResponse;
import com.specagent.common.ApiException;
import com.specagent.common.ApiFieldError;
import com.specagent.agent.runtime.RouteTargetConflictException;
import com.specagent.agent.runtime.StaleRunTargetException;
import com.specagent.agent.policy.ProposalAlreadyDecidedException;
import com.specagent.workspace.graph.GraphWorkspaceQueryException;
import com.specagent.workspace.spec.RequirementStateQueryException;
import com.specagent.workspace.route.RouteLineageQueryException;
import com.specagent.connection.ConnectionCommandException;
import com.specagent.connection.ConnectionNotFoundException;
import com.specagent.connection.ConnectionValidationException;
import com.specagent.mcp.provider.McpConnectionCommandException;
import com.specagent.skill.importing.SkillImportException;
import com.specagent.skill.runtime.SkillResourceRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:ApiExceptionHandler.java
 *
 * 用途:后端统一的 API 异常映射中心,把各业务包抛出的类型化异常
 * 转换成稳定的 {@link ApiErrorResponse} 错误契约。
 *
 * 映射策略:
 *
 * 400 BAD_REQUEST   请求格式错误 / 校验失败 / 非法 UUID
 * 404 NOT_FOUND     project/route/node/answer/spec/run 不存在
 * 409 CONFLICT      请求与运行时生命周期/状态冲突
 * 422               模型契约 / 反思拒绝
 * 500               未预期的内部失败(通用安全消息)
 *
 * 供应商/网关失败由 {@code com.specagent.web.GatewayErrorAdvice} 映射
 * (API 边界本身绝不依赖 model 包)。未预期异常的服务端日志只记录异常类名,
 * 保证凭据、供应商错误负载等敏感内容进不了日志;客户端永远只收到通用
 * 的安全消息。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(ApiErrorResponse.of(ex.code(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler(RequirementStateQueryException.class)
    public ResponseEntity<ApiErrorResponse> handleRequirementStateQuery(RequirementStateQueryException ex) {
        return switch (ex.reason()) {
            case PROJECT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiErrorResponse.of("PROJECT_NOT_FOUND", "Project not found"));
            case ROUTE_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiErrorResponse.of("ROUTE_NOT_FOUND", "Route not found"));
            case INVARIANT_VIOLATION -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiErrorResponse.of("INTERNAL_INVARIANT_VIOLATION",
                            "The project state failed an internal invariant check"));
        };
    }

    @ExceptionHandler(GraphWorkspaceQueryException.class)
    public ResponseEntity<ApiErrorResponse> handleGraphWorkspaceQuery(GraphWorkspaceQueryException ex) {
        return switch (ex.reason()) {
            case PROJECT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiErrorResponse.of("PROJECT_NOT_FOUND", "Project not found"));
            case INVARIANT_VIOLATION -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiErrorResponse.of("INTERNAL_INVARIANT_VIOLATION",
                            "The project graph failed an internal invariant check"));
        };
    }

    @ExceptionHandler(RouteLineageQueryException.class)
    public ResponseEntity<ApiErrorResponse> handleRouteLineageQuery(RouteLineageQueryException ex) {
        return switch (ex.reason()) {
            case PROJECT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiErrorResponse.of("PROJECT_NOT_FOUND", "Project not found"));
            case ROUTE_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiErrorResponse.of("ROUTE_NOT_FOUND", "Route not found"));
            case INVARIANT_VIOLATION -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiErrorResponse.of("INTERNAL_INVARIANT_VIOLATION",
                            "The route lineage failed an internal invariant check"));
        };
    }

    @ExceptionHandler(ModelContractException.class)
    public ResponseEntity<ApiErrorResponse> handleModelContract(ModelContractException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiErrorResponse.of("MODEL_CONTRACT_REJECTED",
                        "The model output did not satisfy the runtime contract"));
    }

    /**
     * 排队的 run 在认领时刻记录的目标已与实时状态不符(例如活动路线已切换)。
     * 预期业务结果:确定性的冲突,绝不返回 500。
     */
    @ExceptionHandler(StaleRunTargetException.class)
    public ResponseEntity<ApiErrorResponse> handleStaleRunTarget(StaleRunTargetException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("AGENT_RUN_TARGET_STALE",
                        "The queued agent run's target is no longer current"));
    }

    /**
     * 新的 run(草稿问题/答案/工件)无法指向其路线:项目没有活动路线指针,
     * 或请求的路线已不是 OPEN 状态。预期业务结果:携带精确错误码的确定性
     * 冲突,绝不退化为通用的 runtime-conflict 409。
     */
    @ExceptionHandler(RouteTargetConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleRouteTargetConflict(RouteTargetConflictException ex) {
        return switch (ex.reason()) {
            case NO_ACTIVE_ROUTE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiErrorResponse.of("NO_ACTIVE_ROUTE",
                            "The project has no active route to target"));
            case ROUTE_NOT_OPEN -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiErrorResponse.of("ROUTE_NOT_OPEN",
                            "The requested route is no longer open"));
        };
    }

    /**
     * 图变更违反了用户可以据此采取行动的拓扑/状态规则(依赖成环、
     * 在非 tip 节点上摘除或连接)。返回携带精确规则码的确定性 409,
     * 绝不是通用的 runtime conflict。
     */
    @ExceptionHandler(com.specagent.workspace.graph.GraphRuleViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleGraphRuleViolation(
            com.specagent.workspace.graph.GraphRuleViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(ex.code(), ex.getMessage()));
    }

    /**
     * 提案生命周期决策输掉了单赢家竞争(另一个 accept/reject/expire
     * 先提交了)。预期业务结果:确定性的 409,绝不是 500 或约束违例。
     */
    @ExceptionHandler(ProposalAlreadyDecidedException.class)
    public ResponseEntity<ApiErrorResponse> handleProposalAlreadyDecided(
            ProposalAlreadyDecidedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("PROPOSAL_ALREADY_DECIDED",
                        "Proposal has already been decided"));
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyKeyReused(
            IdempotencyKeyReusedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("IDEMPOTENCY_KEY_REUSED",
                        "The idempotency key was already used for a different request."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiFieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiFieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", "Request validation failed", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("MALFORMED_JSON", "Request body is malformed"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        boolean uuidTarget = ex.getRequiredType() != null && UUID.class.isAssignableFrom(ex.getRequiredType());
        if (uuidTarget) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiErrorResponse.of("INVALID_UUID", "Path or query argument must be a valid UUID"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_ARGUMENT", "Path or query argument has an invalid value"));
    }

    @ExceptionHandler(SkillImportException.class)
    public ResponseEntity<ApiErrorResponse> handleSkillImport(SkillImportException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("SKILL_IMPORT_REJECTED", ex.getMessage()));
    }

    @ExceptionHandler(SkillResourceRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handleSkillResource(
            SkillResourceRejectedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("SKILL_RESOURCE_REJECTED", ex.getMessage()));
    }

    @ExceptionHandler(ConnectionCommandException.class)
    public ResponseEntity<ApiErrorResponse> handleConnectionCommand(
            ConnectionCommandException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("CONNECTION_COMMAND_REJECTED", ex.getMessage()));
    }

    // ConnectionCommandException 的 mcp 包孪生版本:mcp 包不得 import
    // connection 服务包,因此 MCP 资产访问被拒时抛出自己的类型化异常,
    // 并在这里映射到完全相同的公开契约——绝不映射为 500/UNKNOWN_ERROR。
    @ExceptionHandler(McpConnectionCommandException.class)
    public ResponseEntity<ApiErrorResponse> handleMcpConnectionCommand(
            McpConnectionCommandException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("CONNECTION_COMMAND_REJECTED", ex.getMessage()));
    }

    @ExceptionHandler(ConnectionValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleConnectionValidation(
            ConnectionValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(ConnectionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleConnectionNotFound(
            ConnectionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("CONNECTION_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        LOG.warn("Unhandled API failure of type {}", ex.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", "An unexpected internal error occurred"));
    }
}

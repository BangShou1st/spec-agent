package com.specagent.assistant.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.runtime.GaExecutionModelTargetResolver;
import com.specagent.assistant.runtime.GaExecutionStore;
import com.specagent.common.Hashes;
import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.GaNativeInferenceGateway;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Run-bound native broker orchestration; no Agent loop, credential forwarding or retries. */
@Service
public class GaNativeModelBroker {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GaNativeModelBroker.class);
    public record Result(int status, Object body) {}
    private final GaExecutionStore executions;
    private final GaExecutionModelTargetResolver modelTargets;
    private final GaNativeInferenceGateway nativeGateway;
    private final com.specagent.assistant.runtime.GaModelContinuationStore continuations;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public GaNativeModelBroker(GaExecutionStore executions, GaExecutionModelTargetResolver modelTargets,
            GaNativeInferenceGateway nativeGateway,
            com.specagent.assistant.runtime.GaModelContinuationStore continuations) {
        this.executions = executions; this.modelTargets = modelTargets; this.nativeGateway = nativeGateway;
        this.continuations = continuations;
    }

    private com.specagent.runtime.execution.ExecutionHeartbeatRegistry heartbeatRegistry;
    private com.specagent.runtime.execution.RuntimeTaskRepository runtimeTasks;

    /**
     * 心跳失权信号(design §6.4)。在发起下一次模型调用之前、以及流式期间,只要
     * 失权即拒绝继续/尽快中止在途流。失权判定见 {@link #ownershipLost}:
     * 持久化事实是跨实例可信来源,本进程内存标记覆盖"已判失权但旧租约尚未
     * 到期"的窗口;数据库 token、动态授权与预算账本仍是最终权威。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHeartbeatRegistry(
            com.specagent.runtime.execution.ExecutionHeartbeatRegistry heartbeatRegistry) {
        this.heartbeatRegistry = heartbeatRegistry;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuntimeTasks(
            com.specagent.runtime.execution.RuntimeTaskRepository runtimeTasks) {
        this.runtimeTasks = runtimeTasks;
    }

    /**
     * 本请求的执行是否已失去所有权。<b>不依赖调用线程的 ThreadLocal</b>:真实的
     * 内部 RPC 与 StreamingResponseBody 回调运行在无绑定的 HTTP/异步线程上,内存
     * 标记对它们不可见,跨实例回调更是看不到执行实例的私有内存。
     *
     * <p>按三层可信事实判定(任一成立即失权):
     * <ol>
     *   <li><b>持久化失权事实</b>:同 run 的 runtime task 行已不再是本 scope 的
     *       LEASED 续租(被接管、取消或终态)——接管/取消一旦落库,任何实例的
     *       任何回调线程都能据此立即拒绝,这是跨实例可信传播的主通道;</li>
     *   <li><b>本进程已知失权</b>:执行线程带 token 且心跳已判失权——覆盖
     *       "已判失权但旧数据库租约尚未到期"的窗口(该窗口内持久化行还是
     *       旧租约的 LEASED,只有执行实例自己知道已停手);</li>
     *   <li><b>线程身份与请求 scope 冲突</b>:执行线程携带的 token 与请求声明的
     *       epoch/lease 不一致——旧的迟到回调绝不能搭新 attempt 的车。</li>
     * </ol>
     *
     * <p>没有 ThreadLocal 的 RPC 绝不因此匿名放行:仍由持久化事实、
     * {@link GaExecutionStore#active}(run RUNNING、未取消、deadline 未过)与
     * 预算账本逐次把关;也绝不"无绑定即拒绝"——那会把合法的异步流式线程
     * 全部误杀。
     */
    private boolean ownershipLost(GaExecutionStore.Scope scope) {
        if (runtimeTasks != null) {
            var task = runtimeTasks.findByGaRunId(scope.runId()).orElse(null);
            // 任务行存在才按它判定,且只对 epoch(代次)与存活状态判失:
            // ga_executions 绑定行携带 prepare 阶段生成的<b>独立绑定 lease</b>,
            // 与 runtime_tasks 行的认领 lease 天然不同(Python 的模型回调携带
            // 执行信封里的绑定 lease),按 lease 比对任务行会把合法回调误杀。
            // 持久化失权事实因此收敛为:任务行已终态(RECOVERING/QUEUED/取消/
            // 成功/失败)、代次已递增(接管)、或已请求取消——任何一项都意味着
            // 本 scope 的执行权已被撤销;认领 lease 的精确归属由
            // {@link GaExecutionAuthorization#lockAndAuthorize}(epoch 匹配 +
            // TaskWriteGuard 行锁)在授权路径核实,这里不重复其职责。
            // 行不存在说明该 run 未走任务调度(legacy 路径),其围栏由
            // ga_executions 绑定、active() 与预算账本完整承载,不在此判失。
            if (task != null
                    && (task.state() != com.specagent.runtime.execution.RuntimeTaskState.LEASED
                    || task.executionEpoch() != scope.epoch()
                    || task.cancelRequestedAt() != null)) {
                return true;
            }
        }
        return com.specagent.runtime.execution.ExecutionTokenContext.current()
                .map(token -> {
                    if (heartbeatRegistry != null && heartbeatRegistry.isOwnershipLost(token)) return true;
                    return token.epoch() != scope.epoch();
                })
                .orElseGet(() ->
                        // 无 ThreadLocal 的 RPC/异步流式线程:按请求 scope 的执行
                        // 身份(epoch + 绑定 lease)查询本进程失权标记——同样的
                        // 内存事实,不因调用线程不同而不可见。
                        heartbeatRegistry != null
                                && heartbeatRegistry.isOwnershipLostByEpochAndLease(
                                        runtimeTasks == null ? null : taskIdOf(scope),
                                        scope.epoch(), scope.leaseId()));
    }

    private java.util.UUID taskIdOf(GaExecutionStore.Scope scope) {
        return runtimeTasks.findByGaRunId(scope.runId())
                .map(com.specagent.runtime.execution.RuntimeTask::id)
                .orElse(null);
    }

    /**
     * 流式请求的<b>响应前</b>失权预检:响应头尚未提交时,失权(持久化事实或
     * 本进程内存标记)直接以 HTTP 409 GA_EXECUTION_FENCE 拒绝——与
     * {@link #invoke} 的非流式语义一致,绝不让失权执行通过 200 的 NDJSON
     * 通道回传任何失败事件。响应头提交后的在途流仍由 {@code active} 栅栏
     * 与 {@link #ownershipLost} 尽力中止(FAILED 事件)。
     *
     * @throws com.specagent.model.contract.StreamCancelledException 已失权
     */
    int precheckStream(String body) {
        GaModelContract.Request request;
        try {
            request = GaModelContract.readRequest(body);
            requireRpcWireV2(request);
            if (!request.stream() || !"AGENT".equals(request.callType())) return 400;
        } catch (IllegalArgumentException protocol) { return 400; }
        if (ownershipLost(new GaExecutionStore.Scope(
                request.runId(), request.executionEpoch(), request.leaseId()))) {
            return 409;
        }
        return 200;
    }
    public GaModelContract.Response complete(GaModelContract.Request request) {
        if (request.stream()) throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
        return infer(request, null);
    }
    private GaModelContract.Response infer(GaModelContract.Request request,
            com.specagent.model.contract.FragmentListener listener) {
        var scope = new GaExecutionStore.Scope(request.runId(), request.executionEpoch(), request.leaseId());
        if (ownershipLost(scope)) throw new com.specagent.model.contract.StreamCancelledException(
                "ownership lost before GA model call");
        var actor = executions.requireActor(scope);
        try (var context = com.specagent.access.ActorContextHolder.bind(actor)) {
            return inferForActor(request, listener, scope);
        }
    }
    private GaModelContract.Response inferForActor(GaModelContract.Request request,
            FragmentListener listener, GaExecutionStore.Scope scope) {
        var sourceActor = executions.requireActor(scope);
        var binding = executions.authorize(scope);
        if (!binding.modelBindingId().equals(request.modelBindingId())) throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
        if ((!"SUMMARY".equals(request.callType()) && !binding.allowedTools().equals(request.tools()))
                || ("SUMMARY".equals(request.callType()) && !request.tools().isEmpty()))
            throw new IllegalStateException("GA_MODEL_CATALOG_MISMATCH");
        // 以<b>冻结绑定</b>为准解析可执行目标:提供商与模型都取绑定里的值,运行中用户切换
        // 提供商/模型不会改变本 Run;设置修订号一旦漂移即 GA_MODEL_BINDING_MISMATCH。凭据
        // 只在这一层解析,绝不写入执行表或诊断。尚无 GA 原生资格的提供商在此返回类型化错误,
        // 绝不替换成另一个提供商。
        GaModelTarget target = modelTargets.requireFrozen(binding.provider(), binding.model(),
                binding.settingsRevision());
        // v2 references are resolved and injected before reservation: the resolved
        // request (not the opaque string alone) defines replay identity.
        GaModelContract.Request internalRequest = request;
        if (request.wireV2()) {
            internalRequest = new GaModelContract.Request(GaModelContract.VERSION, request.runId(),
                    request.executionEpoch(), request.leaseId(), request.callId(), request.callType(),
                    request.modelBindingId(), request.messages().stream()
                            .map(message -> message.continuationRef() == null ? message
                                    : withResolved(message, continuations.resolve(message.continuationRef(), scope, binding)))
                            .toList(), request.tools(), request.toolChoice(), request.maxOutputTokens(), request.stream());
        }
        String requestHash = Hashes.sha256Hex(json(internalRequest));
        var reservation = executions.reserve(scope, request.callId(), "MODEL", requestHash);
        if (!reservation.fresh()) {
            if ("SUCCEEDED".equals(reservation.status())) return GaModelContract.readResponse(reservation.result());
            throw new IllegalStateException("GA_CALL_IN_PROGRESS_OR_UNKNOWN");
        }
        try {
            Duration remaining = Duration.between(Instant.now(), binding.deadline());
            if (remaining.isZero() || remaining.isNegative()) throw new IllegalStateException("GA_EXECUTION_FENCE");
            // 失权时尽快中止在途流(尽力取消):下一次分片/下一次调用前即拒绝。
            // active 是持久化围栏;ownershipLost 补上"接管/取消已落库"与
            // "本实例心跳已判失权"两类即时信号。
            java.util.function.BooleanSupplier active =
                    () -> executions.active(scope) && !ownershipLost(scope);
            // 提供商无关的原生执行入口:由冻结目标里的提供商路由到对应原生传输。会话亲和性
            // 以 thread 绑定(传输层按提供商自行映射);失权时片段直接被拒,绝不放行。
            var response = listener == null
                    ? nativeGateway.complete(target, binding.threadId(), internalRequest, remaining, active)
                    : nativeGateway.stream(target, binding.threadId(), internalRequest, remaining, active,
                        text -> active.getAsBoolean() && listener.onFragment(text));
            // Broker 权威复核:与各 provider adapter 的传输层校验共用同一份契约层
            // 纯逻辑实现(两个信任边界各执行一次,语义完全一致)。
            com.specagent.model.contract.GaResponseContractValidator.validate(request,response);
            if(response.toolCalls().size()>1) {
                var approved=executions.catalog(scope).descriptors().stream()
                    .filter(d->d.readOnly() && d.sideEffectClass().equals("NONE") && !java.util.Set.of("ui.navigate","user-input.request").contains(d.capabilityId()))
                    .map(com.specagent.assistant.tool.GaCatalogProjection.Descriptor::name).collect(java.util.stream.Collectors.toSet());
                if(response.toolCalls().stream().anyMatch(call->!approved.contains(call.name()))) throw new IllegalArgumentException("GA batch must contain only admitted read-only business tools");
            }
            var state = continuations.extract(response.toolCalls().stream()
                            .map(GaModelContract.ToolCall::id).toList(),
                    response.toolCalls().stream().map(GaModelContract.ToolCall::extraContent).toList());
            UUID continuationId = state == null ? null : UUID.randomUUID();
            String ref = continuationId == null ? null : "gacr-" + continuationId;
            var wire = response.sanitizeForWire(request.protocolVersion(), ref);
            final var sidecarRef = ref;
            final var internalResponse = response;
            final var sidecarState = state;
            final var wireBinding = binding;
            // Sidecar and MODEL ledger commit atomically. If encryption/storage
            // fails, the call never becomes SUCCEEDED and no executable calls leak.
            executions.completeModelWithContinuation(scope, request.callId(), json(wire), () -> {
                if (sidecarRef == null) return;
                continuations.persist(new com.specagent.assistant.runtime.GaModelContinuationStore.Source(
                        sourceActor.orgId(), wireBinding.threadId(), scope.runId(), request.callId()), wireBinding,
                        request.callId(), internalResponse.toolCalls().stream()
                                .map(GaModelContract.ToolCall::id).toList(), sidecarState, continuationId);
            });
            return wire;
        } catch (RuntimeException ex) {
            executions.unknown(scope, request.callId());
            throw ex;
        }
    }
    /** Writes only typed host events, never provider JSON/SSE or partially parsed arguments. */
    public void stream(String body, java.io.OutputStream output) throws java.io.IOException {
        var request = GaModelContract.readRequest(body);
        requireRpcWireV2(request);
        if (!request.stream() || !"AGENT".equals(request.callType())) throw new IllegalArgumentException("GA stream request");
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BiConsumer<String,Object> emit = (type,payload) -> {
            if ("TEXT_DELTA".equals(type) && sequence.get() >= 8191)
                throw new IllegalArgumentException("GA model stream limit"); // Reserve a slot for typed failure.
            int next = sequence.incrementAndGet();
            if (next > 8192) throw new IllegalArgumentException("GA model stream limit");
            try {
                output.write((json(java.util.Map.of("protocolVersion","ga-model-stream.v1", "callId",request.callId(),
                        "sequence",next,"type",type,"payload",payload))+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.flush();
            } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        };
        try {
            var result = infer(request, text -> { emit.accept("TEXT_DELTA",java.util.Map.of("text",text)); return true; });
            emit.accept("COMPLETED",result); // Ledger commit precedes release of executable calls.
        } catch (java.io.UncheckedIOException disconnected) { throw disconnected.getCause(); }
        catch (RuntimeException failure) {
            // 只记录异常类型(绝不含消息、提供商报文、提示词或凭据),便于诊断;
            // 行线只回传白名单内的类型化错误码。
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            log.warn("GA native stream failed run={} category={} root={} code={}", request.runId(),
                    failure.getClass().getSimpleName(), root.getClass().getSimpleName(), typedCode(failure));
            emit.accept("FAILED", java.util.Map.of("errorCode", typedCode(failure)));
        }
    }
    public Result invoke(String body) {
        try {
            var request = GaModelContract.readRequest(body);
            requireRpcWireV2(request);
            var response = complete(request);
            return new Result(200, mapper.convertValue(response, java.util.Map.class));
        } catch (com.specagent.model.contract.ModelGatewayException ex) {
            log.warn("GA native model invoke failed category={} httpStatus={}", ex.gatewayCategory(), ex.httpStatus());
            return failure(502, "GA_MODEL_" + ex.gatewayCategory().name());
        } catch (com.specagent.model.contract.StreamCancelledException ex) {
            return failure(409, "GA_EXECUTION_FENCE");
        } catch (UnsupportedOperationException ex) { return failure(422, "UNSUPPORTED_AGENT_MODEL"); }
        catch (IllegalArgumentException ex) { return failure(400, "GA_MODEL_PROTOCOL_ERROR"); }
        catch (IllegalStateException ex) {
            String code = java.util.Set.of("GA_EXECUTION_FENCE", "GA_MODEL_BINDING_MISMATCH", "GA_MODEL_CATALOG_MISMATCH",
                    "GA_MODEL_NOT_CONFIGURED", "GA_MODEL_PROTOCOL_UNSUPPORTED", "GA_CALL_IN_PROGRESS_OR_UNKNOWN",
                    "GA_CALL_ID_CONFLICT", "GA_BUDGET_EXHAUSTED", "GA_CALL_NOT_RESERVED").contains(ex.getMessage())
                    ? ex.getMessage() : "GA_HOST_STATE_ERROR";
            return failure(409, code);
        }
    }
    private Result failure(int status, String code) { return new Result(status, java.util.Map.of("errorCode", code)); }

    /** The Python-facing HTTP RPC speaks only v2; v1 remains internal/history-only. */
    private static void requireRpcWireV2(GaModelContract.Request request) {
        if (!request.wireV2()) throw new IllegalArgumentException("GA model RPC requires ga-model-inference.v2");
    }

    /**
     * 把一次原生模型失败的异常映射成<b>白名单内的类型化错误码</b>。与 {@link #invoke} 使用
     * 同一套分类:提供商错误按类别映射,控制面状态按已登记码返回,其余一律
     * {@code GA_HOST_STATE_ERROR}——绝不放行异常原文。
     */
    private static String typedCode(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.specagent.model.contract.ModelGatewayException gateway) {
                return "GA_MODEL_" + gateway.gatewayCategory().name();
            }
            if (cause instanceof com.specagent.model.contract.StreamCancelledException) {
                return "GA_EXECUTION_FENCE";
            }
            if (cause instanceof UnsupportedOperationException) {
                return "UNSUPPORTED_AGENT_MODEL";
            }
            if (cause instanceof IllegalArgumentException) {
                return "GA_MODEL_PROTOCOL_ERROR";
            }
            if (cause instanceof IllegalStateException state
                    && java.util.Set.of("GA_EXECUTION_FENCE", "GA_MODEL_BINDING_MISMATCH",
                        "GA_MODEL_CATALOG_MISMATCH", "GA_MODEL_NOT_CONFIGURED",
                        "GA_MODEL_PROTOCOL_UNSUPPORTED", "GA_CALL_IN_PROGRESS_OR_UNKNOWN",
                        "GA_CALL_ID_CONFLICT", "GA_BUDGET_EXHAUSTED",
                        "GA_CALL_NOT_RESERVED").contains(state.getMessage())) {
                return state.getMessage();
            }
        }
        return "GA_HOST_STATE_ERROR";
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid GA model payload"); }
    }

    private static GaModelContract.Message withResolved(GaModelContract.Message message,
            com.specagent.assistant.runtime.GaModelContinuationStore.Resolved resolved) {
        var expectedIds = message.toolCalls().stream().map(GaModelContract.ToolCall::id).toList();
        if (!resolved.toolCallIds().equals(expectedIds)) {
            throw new IllegalStateException("GA_CONTINUATION_BINDING_MISMATCH");
        }
        var calls = message.toolCalls().stream()
                .map(call -> new GaModelContract.ToolCall(call.id(), call.name(), call.arguments(),
                        perToolExtra(resolved.state(), call.id()))).toList();
        return new GaModelContract.Message(message.role(), message.content(), calls,
                message.toolCallId(), message.continuationRef(), null);
    }

    private static java.util.Map<String, Object> perToolExtra(java.util.Map<String, Object> state, String toolCallId) {
        if (!(state.get(toolCallId) instanceof java.util.Map<?, ?> extra)) {
            throw new IllegalStateException("GA_CONTINUATION_BINDING_MISMATCH");
        }
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> typed = (java.util.Map<String, Object>) extra;
        return typed;
    }
}

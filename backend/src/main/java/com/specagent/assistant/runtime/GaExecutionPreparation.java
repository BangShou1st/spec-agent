package com.specagent.assistant.runtime;

import com.specagent.assistant.conversation.*;
import com.specagent.assistant.tool.GaCatalogProjection;
import com.specagent.common.Hashes;
import com.specagent.common.Maps;
import com.specagent.model.contract.GaModelTarget;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically pins the dispatch envelope and claims the existing product lifecycle. */
@Service
public class GaExecutionPreparation {
    public record Prepared(GaExecutionStore.Scope scope, String body, String model) {}
    private final GaExecutionStore executions;
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantConversationService conversations;
    private final GlobalAssistantRunLifecycleService lifecycle;
    private final GaHostContext context;
    private final GaCatalogProjection catalogs;
    private final GaExecutionModelTargetResolver modelTargets;
    private final JdbcTemplate jdbc;
    public GaExecutionPreparation(GaExecutionStore executions, GlobalAssistantRunRepository runs,
            GlobalAssistantConversationService conversations, GlobalAssistantRunLifecycleService lifecycle,
            GaHostContext context, GaCatalogProjection catalogs,
            GaExecutionModelTargetResolver modelTargets, JdbcTemplate jdbc) {
        this.executions=executions; this.runs=runs; this.conversations=conversations; this.lifecycle=lifecycle;
        this.context=context; this.catalogs=catalogs; this.modelTargets=modelTargets; this.jdbc=jdbc;
    }
    /**
     * 解析并冻结模型目标与执行能力模式,保留既有失败语义:协议不支持(例如 Custom
     * 不是 Chat Completions、Zen 模型未被元数据确认为 Chat)按控制面语义终态化且不写
     * 助手正文;而"未配置"仍以异常形式失败,交由协调器的统一失败路径处理(与该门的
     * 既有行为一致,不改变对外的事件与消息形状)。
     *
     * <p>这里刻意不再把"未取得 GA 原生工具调用资格"当成准入条件:{@link GaExecutionMode#TEXT_CHAT}
     * 使用用户选中的同一个模型正常聊天,只有具备可信资格时才升级为
     * {@link GaExecutionMode#NATIVE_TOOLS}。
     */
    private GaExecutionModelTargetResolver.Ready requireModelTarget(UUID runId) {
        var resolution = modelTargets.resolveActive();
        if (resolution instanceof GaExecutionModelTargetResolver.Unavailable unavailable) {
            if (com.specagent.assistant.GlobalAssistantErrorCode.GA_MODEL_PROTOCOL_UNSUPPORTED
                    .equals(unavailable.errorCode())) {
                lifecycle.failWithoutContent(runId, unavailable.errorCode(), unavailable.reason());
                return null;
            }
            throw new IllegalStateException(unavailable.errorCode());
        }
        return (GaExecutionModelTargetResolver.Ready) resolution;
    }

    @Transactional
    public Prepared prepare(UUID thread, UUID runId, String text, GaHostContext.UiRequest ui) {
        // 执行代次来自当前任务的显式 token(design §7):首次认领为 1,接管后为
        // 递增后的代次。缺省回落到 1 只为兼容单执行器模式的既有测试与路径。
        long epoch = com.specagent.runtime.execution.ExecutionTokenContext.current()
                .map(com.specagent.runtime.execution.ExecutionToken::epoch).orElse(1L);
        return prepare(thread, runId, text, ui, epoch);
    }

    /**
     * 带显式执行代次的准备。
     *
     * <p>{@code executionEpoch} 参与三件事,缺一不可:
     * <ul>
     *   <li>写入 {@code ga_executions.execution_epoch} 与 {@code lease_id},
     *       使旧代次的迟到写入被 fence 拒绝;</li>
     *   <li>写进执行信封,Python 侧按 attempt 分区内部序号与事件去重;</li>
     *   <li>恢复时沿用原模型/能力快照与<b>剩余</b>预算——本方法绝不重置
     *       {@code model_calls}/{@code tool_calls}(那在 initialize 里被刻意排除)。</li>
     * </ul>
     */
    @Transactional
    public Prepared prepare(UUID thread, UUID runId, String text, GaHostContext.UiRequest ui,
                            long executionEpoch) {
        if (executionEpoch < 1) {
            throw new IllegalArgumentException("GA execution epoch must be >= 1");
        }
        executions.requireActor(new GaExecutionStore.Scope(runId,executionEpoch,UUID.randomUUID()));
        var run=runs.lockById(runId);
        if (run.status()!=GlobalAssistantRunStatus.CREATED) throw new GlobalAssistantRunClaimedException(run.status());
        if (!run.threadId().equals(thread)) throw new IllegalArgumentException("GA thread mismatch");
        if (run.cancelRequestedAt()!=null) { lifecycle.cancelAndTerminalize(runId); return null; }
        // 来源依赖判定:派生记忆依赖的项目已撤权或无法确认时,历史不得进入执行信封
        // (第二阶段 §6.5"不得只过滤下一次检索却继续把旧摘要送模型")。此时按控制面
        // 终态化失败关闭:不带内容、不写助手消息——撤权会话的正文一个字节都不外发,
        // 也不会因 run 滞留 CREATED 而卡死该线程(V28 uq_ga_runs_single_active)。
        try {
            conversations.requireSourceDependenciesReadable(thread);
        } catch (com.specagent.access.AccessDeniedException revoked) {
            lifecycle.failWithoutContent(runId, com.specagent.assistant.GlobalAssistantErrorCode.GA_SOURCE_REVOKED,
                    "Conversation depends on project sources that are no longer authorized");
            return null;
        }
        // 提供商无关的模型目标解析(设置侧):解析当前激活提供商的可信目标与能力模式,并把
        // "未配置"与"协议不支持"区分为两类类型化失败——后者不再被 GA_MODEL_NOT_CONFIGURED
        // 掩盖。"未取得原生工具调用资格"不再是准入条件:它只决定能力模式是 TEXT_CHAT 还是
        // NATIVE_TOOLS。门禁只读缓存/设置事实,绝不在持有 Run 锁的本事务内发起模型网络请求
        // (需要网络的预热由 GaExecutionCoordinator 在进入本方法之前完成)。拒绝发生在信封创建
        // 与预算认领之前:run 按控制面语义终态化、公开发布失败事件,不写助手正文、不消耗预算、
        // 不创建租约、不派发 Python 执行信封。
        var ready = requireModelTarget(runId);
        if (ready == null) return null;
        GaModelTarget target = ready.target();
        var messages=conversations.listMessages(thread);
        var current=messages.stream().filter(m -> m.role()==GlobalAssistantMessage.Role.USER && runId.equals(m.runId())).toList();
        if (current.size()!=1 || !current.getFirst().content().equals(text)) throw new IllegalArgumentException("GA current message mismatch");
        var boundaries=jdbc.queryForList("SELECT public_history_boundary FROM ga_checkpoint_heads WHERE thread_id=?",thread);
        UUID boundary=boundaries.isEmpty() ? null : (UUID)boundaries.getFirst().get("public_history_boundary");
        int start=boundary==null ? Math.max(0,messages.size()-24) : -1;
        if (boundary!=null) for(int i=0;i<messages.size();i++) if(messages.get(i).id().equals(boundary)) start=i+1;
        if (start<0) throw new IllegalStateException("GA_HISTORY_BOUNDARY_MISSING");
        List<Map<String,Object>> history=new ArrayList<>();
        for(int i=start;i<messages.size();i++) {
            var m=messages.get(i);
            if (m.id().equals(current.getFirst().id())) continue;
            String content=m.content();
            if(content==null || content.isBlank()) continue;
            if(content.length()>2000) content=content.substring(0,2000);
            history.add(Map.of("messageId",m.id().toString(),"role",m.role().name().toLowerCase(Locale.ROOT),"content",content));
        }
        if(history.size()>128) throw new IllegalStateException("GA_HISTORY_LIMIT");
        var projected=context.project(ui);
        UUID selected=projected.selectedEntity()!=null && "PROJECT".equals(projected.selectedEntity().type())
                ? UUID.fromString(projected.selectedEntity().id()) : null;
        // 能力模式按已冻结的目标决定:只有 NATIVE_TOOLS 才冻结完整工具目录。TEXT_CHAT
        // 使用空投影——绝不向模型派发 tools,也绝不注入无法使用的 HostTools 执行能力,
        // 但冻结的空 allowed_tools 同时保证该模式在执行期间无法被扩大或被偷换成原生工具。
        var catalog = ready.mode() == GaExecutionMode.NATIVE_TOOLS
                ? catalogs.current()
                : catalogs.snapshot(java.util.List.of());
        var scope=new GaExecutionStore.Scope(runId,executionEpoch,UUID.randomUUID());
        UUID binding=UUID.randomUUID();
        var envelope=Maps.of("protocolVersion","ga-execution.v1","engineVersion","langchain-ga.v1",
                "threadId",thread.toString(),"runId",runId.toString(),"executionEpoch",executionEpoch,"leaseId",scope.leaseId().toString(),
                "messageId",current.getFirst().id().toString(),"content",text,"modelBindingId",binding.toString(),
                "policyVersion","1","catalogHash",catalog.hash(),"historyBoundary",history.isEmpty()?null:history.getLast().get("messageId"),
                "history",history,"uiContext",Maps.of("currentPage",projected.currentPage(),"selectedEntity",selected),
                "structuredRefs",selected==null ? List.of() : List.of("project:"+selected),"capabilities",catalog.descriptors(),
                "budget",Map.of("maxModelCalls",6,"maxToolCalls",5,"maxDurationSeconds",180));
        String body=catalogs.canonical(envelope);
        if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144) throw new IllegalStateException("GA_EXECUTION_LIMIT");
        executions.initialize(scope,new GaExecutionStore.Binding(thread,binding,target.provider(),target.model(),
                target.settingsRevision(),Instant.now().plusSeconds(180),catalog.tools()),catalog.descriptors());
        jdbc.update("UPDATE ga_executions SET execution_request=?,execution_request_hash=? WHERE run_id=?",body,Hashes.sha256Hex(body),runId);
        lifecycle.claimAndStart(runId);
        return new Prepared(scope,body,target.model());
    }
}

package com.specagent.assistant.conversation;

import com.specagent.access.AuthorizationGuard;
import com.specagent.common.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件名:GlobalAssistantConversationService.java
 *
 * 用途:会话数据的唯一属主,统一管理线程、消息、Run、工作状态
 * (working state)的读写,以及摘要的乐观并发更新(带期望版本的 CAS)。
 *
 * 角色:conversation 包的核心服务。只做数据读写与事务边界,绝不调用
 * 模型、不挑选工具、不推送 SSE——那些是 runtime 层的职责。其中
 * createRunWithUserMessage 把 Run 与当轮用户消息放在同一事务里原子创建,
 * 由 api/runtime 层在事务提交后再调度执行。
 *
 * <b>第二阶段(身份/权限)</b>:会话私有性在 {@code requireThread} 这个
 * 唯一收口点上强制——GA 会话默认私有,组织管理员也不能直接读取他人的
 * 私有会话(设计 §5)。收敛在收口点而不是逐个入口,是为了让"新增一个
 * 会话读取入口"不会静默漏检。
 */
@Service
public class GlobalAssistantConversationService {
    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {
    };
    private final GlobalAssistantThreadRepository threads;
    private final GlobalAssistantMessageRepository messages;
    private final GlobalAssistantRunRepository runs;
    private final Json json;
    private final GlobalAssistantThreadListRepository threadLists;
    private final AuthorizationGuard guard;
    public GlobalAssistantConversationService(
            GlobalAssistantThreadRepository threads,
            GlobalAssistantMessageRepository messages,
            GlobalAssistantRunRepository runs,
            Json json,
            GlobalAssistantThreadListRepository threadLists,
            AuthorizationGuard guard) {
        this.threads = threads;
        this.messages = messages;
        this.runs = runs;
        this.json = json;
        this.threadLists = threadLists;
        this.guard = guard;
    }
    @Transactional
    public GlobalAssistantThread createThread() {
        return threads.create();
    }
    public Optional<GlobalAssistantThread> findThread(UUID threadId) {
        // 私有性判定:会话详情是正文级派生数据(标题/摘要/工作状态)的入口,
        // 不能只靠"知道 threadId"。没有这道检查,列表被收紧了而详情仍可直读。
        guard.requireThreadOwnership(threadId);
        return threads.findById(threadId);
    }
    @Transactional
    public GlobalAssistantMessage appendUserMessage(UUID threadId, String content, UUID runId) {
        requireThread(threadId);
        return messages.append(threadId, GlobalAssistantMessage.Role.USER, content, runId);
    }
    @Transactional
    public GlobalAssistantMessage appendAssistantMessage(UUID threadId, String content, UUID runId) {
        requireThread(threadId);
        return messages.append(threadId, GlobalAssistantMessage.Role.ASSISTANT, content, runId);
    }

    /** 追加一条助手消息,并附带模型计费归属(provider 与 model 标识)。 */
    @Transactional
    public GlobalAssistantMessage appendAssistantMessage(UUID threadId, String content, UUID runId,
            String providerLabel, String modelId) {
        requireThread(threadId);
        return messages.append(threadId, GlobalAssistantMessage.Role.ASSISTANT, content, runId,
                providerLabel, modelId);
    }
    @Transactional
    public GlobalAssistantRun createRun(UUID threadId, String promptVersion, String contextProjectionVersion, String toolCatalogFingerprint) {
        requireThread(threadId);
        return runs.create(threadId, promptVersion, contextProjectionVersion, toolCatalogFingerprint);
    }
    /**
     * 原子创建 Run 与当轮用户消息:两者要么一起提交、要么一起回滚,
     * 不会留下没有用户消息的孤儿活跃 Run。调用方必须在本事务提交后
     * 才能调度执行。
     */
    @Transactional
    public GlobalAssistantRun createRunWithUserMessage(UUID threadId, String content, String promptVersion,
            String contextProjectionVersion, String toolCatalogFingerprint) {
        requireThread(threadId);
        GlobalAssistantRun run =
                runs.create(threadId, promptVersion, contextProjectionVersion, toolCatalogFingerprint);
        messages.append(threadId, GlobalAssistantMessage.Role.USER, content, run.id());
        return run;
    }
    public java.util.List<GlobalAssistantMessage> listMessages(UUID threadId) {
        requireThread(threadId);
        return messages.findByThread(threadId);
    }
    public java.util.List<GlobalAssistantThreadListItem> listThreads() {
        return threadLists.listRecent(GlobalAssistantConversationLibrary.LIST_LIMIT);
    }
    public GlobalAssistantWorkingState readWorkingState(UUID threadId) {
        GlobalAssistantThread thread = requireThread(threadId);
        if (thread.workingStateJson() == null || thread.workingStateJson().isBlank()) {
            return GlobalAssistantWorkingState.empty();
        }
        Map<String, Object> map;
        try {
            map = json.read(thread.workingStateJson(), MAP_REF);
        } catch (IllegalStateException ex) {
            throw new IllegalStateException("Working-state storage is corrupt for thread: " + threadId);
        }
        if (map == null) {
            return GlobalAssistantWorkingState.empty();
        }
        try {
            return GlobalAssistantWorkingState.fromMap(map);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Working-state storage is corrupt for thread: " + threadId);
        }
    }
    @Transactional
    public void writeWorkingState(UUID threadId, GlobalAssistantWorkingState state, int expectedVersion) {
        threads.updateWorkingState(threadId, state.toMap(), expectedVersion);
    }
    @Transactional
    public void writeSummary(UUID threadId, String summary, int expectedVersion) {
        threads.updateSummary(threadId, summary, expectedVersion);
    }
    /**
     * 主体视角的会话读取:先判定私有性再返回。
     *
     * 控制器与运行时都必须走这条;{@link #findThread(UUID)} 只做存在性查询,
     * 供<b>已完成授权</b>的内部组合使用。
     */
    public Optional<GlobalAssistantThread> findThreadOwned(UUID threadId) {
        guard.requireThreadOwnership(threadId);
        return threads.findById(threadId);
    }

    private GlobalAssistantThread requireThread(UUID threadId) {
        // 会话私有性的唯一收口点:会话读取/追加/建 Run 都必须先经过这里。
        // 非属主按"会话不存在"处理,不区分"不存在"与"不是你的会话"。
        guard.requireThreadOwnership(threadId);
        return threads.findById(threadId)
                .orElseThrow(() -> new IllegalArgumentException("Global assistant thread not found: " + threadId));
    }
}

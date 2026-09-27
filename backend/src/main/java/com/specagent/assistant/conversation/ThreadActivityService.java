package com.specagent.assistant.conversation;

import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件名:ThreadActivityService.java
 *
 * 用途:读取线程的规范活动快照(活跃 Run + 未决 Steer)。
 *
 * 角色:conversation 包的只读服务:只读不写、不派发任何执行,
 * 供线程活动查询与停止线程等 API 端点复用。
 */
@Service
public class ThreadActivityService {
    private final GlobalAssistantRunRepository runs;
    private final PendingTurnRepository pending;

    public ThreadActivityService(GlobalAssistantRunRepository runs, PendingTurnRepository pending) {
        this.runs = runs;
        this.pending = pending;
    }

    @Transactional(readOnly = true)
    public ThreadActivity read(UUID threadId) {
        return new ThreadActivity(runs.findActiveByThread(threadId), pending.findUnresolvedByThread(threadId));
    }
}

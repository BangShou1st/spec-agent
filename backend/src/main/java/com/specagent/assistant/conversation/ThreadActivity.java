package com.specagent.assistant.conversation;

import com.specagent.assistant.conversation.GlobalAssistantRun;
import java.util.Optional;

/**
 * 文件名:ThreadActivity.java
 *
 * 用途:一个线程当前的"活动快照"(不可变 record):可能存在的
 * 活跃 Run 与未决 Steer。
 *
 * 角色:conversation 包的只读投影模型,线程活动查询与"停止线程"
 * 端点共用;活动事实以后端为准,由本 record 单一承载。
 */
public record ThreadActivity(Optional<GlobalAssistantRun> activeRun, Optional<PendingTurn> pendingSteer) {
    public static ThreadActivity empty() {
        return new ThreadActivity(Optional.empty(), Optional.empty());
    }
}

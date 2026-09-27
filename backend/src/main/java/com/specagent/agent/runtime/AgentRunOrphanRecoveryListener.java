package com.specagent.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 文件名:AgentRunOrphanRecoveryListener.java
 *
 * 用途:工作区 AgentRun 孤儿恢复的启动入口。只在 worker 开启且启动恢复
 * 未被显式关闭的进程里运行;执行前确认执行器租约仍在手中(同库第二个
 * 执行器根本无法启动到这里)。恢复完成后开放轮询;恢复失败记录错误并
 * 照样开放轮询——队列活性优先,失败原因由健康检查暴露,绝不出现
 * "健康检查正常但队列永久停摆"。启动恢复关闭时本监听器不存在,轮询门控
 * 在应用就绪后自行开放。
 */
@Component
@ConditionalOnExpression("'${spec.agent.brain.worker.enabled:false}' == 'true' "
        + "and '${spec.agent.brain.worker.startup-recovery:true}' == 'true'")
public class AgentRunOrphanRecoveryListener {

    private static final Logger log = LoggerFactory.getLogger(AgentRunOrphanRecoveryListener.class);

    private final AgentRunOrphanRecoveryService recovery;
    private final WorkerPollingGate gate;

    public AgentRunOrphanRecoveryListener(AgentRunOrphanRecoveryService recovery,
                                          WorkerPollingGate gate) {
        this.recovery = recovery;
        this.gate = gate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        try {
            gate.lease().assertOwned();
            AgentRunOrphanRecoveryService.OrphanRecoveryResult result = recovery.recoverOrphans();
            if (result.found() > 0) {
                log.warn("Workspace agent-run orphans terminalized after restart: "
                        + "recovered={}, failed={}, found={}",
                        result.recovered(), result.failed(), result.found());
            }
            // 部分失败必须可观测:健康检查暴露它,绝不把"恢复了大部分"
            // 误读为"恢复成功"。
            if (result.hasPartialFailure()) {
                String error = "ORPHAN_RECOVERY_PARTIAL_FAILURE: " + result.failed()
                        + " of " + result.found() + " orphan runs failed to recover";
                gate.recordRecoveryError(error);
            }
        } catch (Exception ex) {
            log.warn("Workspace agent-run orphan recovery failed: error={}",
                    ex.getClass().getSimpleName());
            gate.recordRecoveryError(ex.getClass().getSimpleName());
        } finally {
            gate.open("startup orphan recovery finished");
        }
    }
}

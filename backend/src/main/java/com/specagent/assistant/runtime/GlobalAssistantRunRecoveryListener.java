package com.specagent.assistant.runtime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
/**
 * 文件名:GlobalAssistantRunRecoveryListener.java
 *
 * 用途:孤儿 run 恢复的启动入口。这个 bean 只负责订阅应用就绪事件;
 * 事务性工作放在独立的 {@link GlobalAssistantRunRecoveryService} 里,
 * 保证 ApplicationReady 路径始终运行在真实事务中(避免代理自调用失效)。
 */
@Component
public class GlobalAssistantRunRecoveryListener {
    private static final Logger log = LoggerFactory.getLogger(GlobalAssistantRunRecoveryListener.class);
    private final GlobalAssistantRunRecoveryService recovery;
    public GlobalAssistantRunRecoveryListener(GlobalAssistantRunRecoveryService recovery) {
        this.recovery = recovery;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        try {
            int recovered = recovery.recoverOrphans();
            if (recovered > 0) {
                log.warn("Global assistant orphan runs recovered as interrupted: count={}", recovered);
            }
        } catch (Exception ex) {
            log.warn("Global assistant orphan recovery failed: error={}", ex.getClass().getSimpleName());
        }
    }
}

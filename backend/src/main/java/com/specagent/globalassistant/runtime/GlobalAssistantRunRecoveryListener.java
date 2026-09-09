package com.specagent.globalassistant.runtime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
/**
 * Startup entry point for orphan recovery. This bean owns the event
 * subscription only; the transactional work lives in the separate
 * {@link GlobalAssistantRunRecoveryService} so the ApplicationReady path
 * always runs inside a real transaction (no proxy self-invocation).
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

package com.specagent.assistant.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 文件名:TurnHandoffListener.java
 *
 * 用途:后端自主的轮次续转监听器。在 AFTER_COMMIT 阶段监听 run 终态事件,
 * 保证继任 run 的创建不会与旧 run 的终态提交产生竞态。
 */
@Component
public class TurnHandoffListener {
    private static final Logger log = LoggerFactory.getLogger(TurnHandoffListener.class);
    private final TurnHandoffService handoff;
    private final RunDispatcher dispatcher;

    public TurnHandoffListener(TurnHandoffService handoff, RunDispatcher dispatcher) {
        this.handoff = handoff;
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRunTerminal(RunTerminalEvent event) {
        if (event == null || event.threadId() == null) {
            return;
        }
        try {
            var successor = handoff.tryHandoffAfterCommit(event.threadId());
            successor.ifPresent(s -> dispatcher.dispatch(s.run().threadId(), s.run().id(), s.message(), s.uiRequest()));
        } catch (Exception ex) {
            log.warn("Global assistant handoff failed: thread={} error={}", event.threadId(), ex.getClass().getSimpleName());
        }
    }
}

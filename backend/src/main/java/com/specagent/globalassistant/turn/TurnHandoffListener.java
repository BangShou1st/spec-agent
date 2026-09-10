package com.specagent.globalassistant.turn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Backend-owned continuation. Listens for run-terminal events AFTER_COMMIT
 * so successor creation never races the old run's terminal commit.
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

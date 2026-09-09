package com.specagent.globalassistant.turn;

import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only canonical activity. No mutation, no dispatch. */
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

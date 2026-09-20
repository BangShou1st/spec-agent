package com.specagent.agent.runevent;

import java.time.Instant;
import java.util.List;

/**
 * Whitelisted run-progress read model handed to API responses. Only composed
 * summary fields are exposed — raw event payloads never leave the backend.
 */
public record RunProgressView(String phase, String summary, List<Step> steps) {

    /** One displayable progress step; {@code summary}/{@code items} may be null. */
    public record Step(int sequence, String phase, String event,
                       String summary, List<String> items, Instant at) {
    }
}

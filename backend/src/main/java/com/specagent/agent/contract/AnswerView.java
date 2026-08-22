package com.specagent.agent.contract;

import java.util.UUID;

/** An immutable user answer as seen by the decision engine. */
public record AnswerView(UUID id,
                         UUID nodeId,
                         UUID selectedOptionId,
                         String freeText) {
}

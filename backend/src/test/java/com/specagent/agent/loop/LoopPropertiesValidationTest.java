package com.specagent.agent.loop;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 2 review closure: continuation chain budget rejects nonsense
 * values fast.
 *
 * <p>Plain unit test on purpose — no Spring context. The bound is a
 * fail-fast setter contract, not container behavior.
 */
class LoopPropertiesValidationTest {

    @Test
    void rejectsZeroAndNegative() {
        LoopProperties properties = new LoopProperties();

        assertThatThrownBy(() -> properties.setMaxCycles(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxCycles(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOne() {
        LoopProperties properties = new LoopProperties();
        properties.setMaxCycles(1);

        assertThat(properties.getMaxCycles()).isEqualTo(1);
    }
}

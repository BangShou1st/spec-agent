package com.specagent.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:LoopPropertiesValidationTest.java
 *
 * 测试目标:Slice 2 评审收尾:续跑链预算对无意义取值快速拒绝(0 与负数抛
 * IllegalArgumentException,1 可接受)。
 *
 * 刻意做成纯单元测试——不启动 Spring 上下文。该边界是 fail-fast 的
 * setter 契约,不是容器行为。
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

package com.specagent.agent.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:ExecutorLeaseTest.java
 *
 * 测试目标:数据库范围的执行器所有权互斥(1.3)。第二个执行器连接同一
 * 数据库时必须在获取所有权处明确失败,绝不进入执行状态;进程正常退出
 * 释放所有权后,后续执行器可恢复获取。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExecutorLeaseTest {

    @Autowired DataSource dataSource;

    @Test
    void secondExecutorOnSameDatabaseIsRejectedAndLeaseRecoversAfterRelease() throws Exception {
        var first = new ExecutorLease(dataSource, "spec_agent_test");
        assertThat(first.owned()).isTrue();
        first.assertOwned();

        // 第二个执行器(独立连接)对同一数据库获取租约:明确拒绝
        assertThatThrownBy(() -> new ExecutorLease(dataSource, "spec_agent_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Another executor already owns this database");

        // 正常释放后,后续执行器可以获取(进程异常退出时连接关闭等效释放)
        first.destroy();
        assertThat(first.owned()).isFalse();
        assertThatThrownBy(() -> first.assertOwned())
                .isInstanceOf(IllegalStateException.class);
        var second = new ExecutorLease(dataSource, "spec_agent_test");
        assertThat(second.owned()).isTrue();
        second.destroy();
    }
}

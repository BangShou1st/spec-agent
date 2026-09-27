package com.specagent.workspace.answer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件名:AnswerServiceTest.java
 *
 * 测试目标:验证 {@link AnswerService} 的读取边界。覆盖图读取模型使用的
 * 按路线批量读取回答的查询:该读取是对仓储查询的纯委托,服务层不应包含
 * 生命周期逻辑、拷贝、修改或兜底行为。
 */
@ExtendWith(MockitoExtension.class)
class AnswerServiceTest {

    @Mock
    AnswerRepository answerRepository;
    @InjectMocks
    AnswerService service;

    @Test
    void findAnswersForRouteAndNodeIdsDelegatesReadOnlyQuery() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        List<UUID> nodeIds = List.of(nodeId);
        Answer answer = new Answer(
                UUID.randomUUID(), projectId, routeId, nodeId,
                UUID.randomUUID().toString(), "free text", "user",
                Instant.parse("2026-08-18T00:00:00Z"));
        when(answerRepository.findByRouteAndNodeIds(routeId, nodeIds)).thenReturn(List.of(answer));

        assertThat(service.findAnswersForRouteAndNodeIds(routeId, nodeIds)).containsExactly(answer);
        verify(answerRepository).findByRouteAndNodeIds(routeId, nodeIds);
        verifyNoMoreInteractions(answerRepository);
    }
}

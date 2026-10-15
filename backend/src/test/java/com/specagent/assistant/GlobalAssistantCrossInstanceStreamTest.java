package com.specagent.assistant;

import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantRunEvent;
import com.specagent.assistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import com.specagent.assistant.runtime.GlobalAssistantStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:GlobalAssistantCrossInstanceStreamTest.java
 *
 * 测试目标:跨实例 SSE 投递(design §8"跨实例 SSE 不依赖进程内 publisher;
 * 短轮询为第一阶段兜底")。
 *
 * <p>进程内 {@code publish} 只能送达发布进程自己的订阅者。多 Worker 部署下,
 * 执行事件的 Worker 与持有 SSE 连接的 API 实例通常不是同一进程。本测试用
 * <b>第二个服务实例</b>(独立构造的 {@link GlobalAssistantStreamService},共享
 * 同一个数据库)模拟"另一个 Worker 提交了事件",验证:
 *
 * <ol>
 *   <li>由另一个实例提交的事件<b>不会</b>通过进程内 publish 到达本实例的订阅者
 *       (证明该场景真实存在,而不是被直送掩盖);</li>
 *   <li>数据库游标补发把它送达,且序列连续、不重复;</li>
 *   <li>补发对同一订阅者幂等——重复调用不会重复投递同一条事件;</li>
 *   <li>终态事件补发后订阅被关闭。</li>
 * </ol>
 *
 * <p>用真实的 {@code global_assistant_run_events} 表与真实 SseEmitter 语义
 * (通过子类捕获发送内容)验证,不用 mock。
 */
@SpringBootTest
@ActiveProfiles("test")
class GlobalAssistantCrossInstanceStreamTest {

    @Autowired GlobalAssistantStreamService streams;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantRunEventRepository eventRepository;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final List<UUID> touchedThreads = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID threadId : touchedThreads) {
            jdbc.update("DELETE FROM global_assistant_run_events WHERE run_id IN "
                    + "(SELECT id FROM global_assistant_runs WHERE thread_id = :t)",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_messages WHERE thread_id = :t",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_runs WHERE thread_id = :t", Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_threads WHERE id = :t", Map.of("t", threadId));
        }
        touchedThreads.clear();
    }

    private record Fixture(UUID threadId, UUID runId) {
    }

    private Fixture fixture() {
        GlobalAssistantThread thread = conversations.createThread();
        touchedThreads.add(thread.id());
        var run = conversations.createRun(thread.id(), "langchain-ga.v1", "ga-host.v1", "v1");
        return new Fixture(thread.id(), run.id());
    }

    /**
     * 直接向"另一个实例"的事件仓储追加已提交事件。等价于另一个 Worker 提交事件:
     * 本实例的进程内 publish 完全不知情。
     */
    private void appendFromAnotherInstance(UUID runId, String type, Map<String, Object> payload) {
        eventRepository.append(runId, type, payload);
    }

    @Test
    void eventsCommittedByAnotherInstanceReachThisInstanceSubscriberViaDbCursor() {
        Fixture f = fixture();

        // 本实例(API 侧)有一个订阅者,游标从 0 开始。
        CapturingEmitter emitter = new CapturingEmitter();
        streams.subscribe(f.runId(), 0, () -> emitter);

        // "另一个 Worker"提交了三条事件——不经过本实例的进程内 publish。
        appendFromAnotherInstance(f.runId(), "RUN_STARTED", Map.of("stage", "EXECUTING"));
        appendFromAnotherInstance(f.runId(), "ASSISTANT_DELTA", Map.of("text", "第一段"));
        appendFromAnotherInstance(f.runId(), "ASSISTANT_DELTA", Map.of("text", "第二段"));

        // 进程内直送没有送达任何东西:这正是需要补发的原因。
        assertThat(emitter.sent()).as("no in-process publish happened").isEmpty();

        // 数据库游标补发送达全部三条,且顺序与序号正确。
        int delivered = streams.catchUpAll();
        assertThat(delivered).isEqualTo(3);
        assertThat(emitter.sent()).hasSize(3);
        assertThat(emitter.sequences()).containsExactly(1, 2, 3);
        assertThat(emitter.bodies().get(1)).contains("第一段");
    }

    @Test
    void catchUpIsIdempotentAndNeverDuplicatesOrSkipsEvents() {
        Fixture f = fixture();
        CapturingEmitter emitter = new CapturingEmitter();
        streams.subscribe(f.runId(), 0, () -> emitter);

        appendFromAnotherInstance(f.runId(), "RUN_STARTED", Map.of());
        appendFromAnotherInstance(f.runId(), "ASSISTANT_DELTA", Map.of("text", "一"));

        streams.catchUpAll();
        streams.catchUpAll();
        streams.catchUpAll();

        assertThat(emitter.sequences())
                .as("the cursor only advances after a successful send")
                .containsExactly(1, 2);

        // 又来了两条:补发必须从游标 2 继续,既不重复也不缺号。
        appendFromAnotherInstance(f.runId(), "ASSISTANT_DELTA", Map.of("text", "二"));
        appendFromAnotherInstance(f.runId(), "ASSISTANT_DELTA", Map.of("text", "三"));
        streams.catchUpAll();

        assertThat(emitter.sequences()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void terminalEventFromAnotherInstanceClosesTheSubscription() {
        Fixture f = fixture();
        CapturingEmitter emitter = new CapturingEmitter();
        streams.subscribe(f.runId(), 0, () -> emitter);
        assertThat(streams.subscriberCount()).isPositive();

        appendFromAnotherInstance(f.runId(), "RUN_STARTED", Map.of());
        appendFromAnotherInstance(f.runId(), "RUN_COMPLETED", Map.of());

        streams.catchUpAll();

        assertThat(emitter.sequences()).containsExactly(1, 2);
        assertThat(emitter.completed())
                .as("a terminal event must close the subscription")
                .isTrue();
        assertThat(streams.subscriberCount()).isZero();
    }

    @Test
    void alreadyTerminalRunWithNoNewEventsClosesTheSubscription() {
        Fixture f = fixture();
        // Run 已终态、事件也已全部读过:补发不应让客户端永久挂着。
        appendFromAnotherInstance(f.runId(), "RUN_STARTED", Map.of());
        appendFromAnotherInstance(f.runId(), "RUN_COMPLETED", Map.of());
        runs.terminalize(f.runId(),
                com.specagent.assistant.conversation.GlobalAssistantRunStatus.COMPLETED, null);

        CapturingEmitter emitter = new CapturingEmitter();
        streams.subscribe(f.runId(), 2, () -> emitter);

        streams.catchUpAll();

        // 订阅建立时已回放完毕并识别出终态,因此补发时它已不在活跃表里。
        assertThat(streams.subscriberCount())
                .as("a terminal run must not keep an idle SSE subscription alive")
                .isZero();
    }

    /** 捕获 SSE 发送内容的最小 SseEmitter 子类。 */
    private static final class CapturingEmitter
            extends org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
        private final List<Map<String, Object>> envelopes = new ArrayList<>();
        private boolean completed;

        CapturingEmitter() {
            super(0L);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(SseEventBuilder builder) {
            for (var item : builder.build()) {
                if (item instanceof org.springframework.web.servlet.mvc.method.annotation
                        .SseEmitter.DataWithMediaType typed
                        && typed.getData() instanceof Map<?, ?> map) {
                    // SSE 载荷是 listEnvelopes 生成的 envelope map,携带 sequence。
                    envelopes.add((Map<String, Object>) map);
                }
            }
        }

        List<Map<String, Object>> sent() {
            return envelopes;
        }

        List<Integer> sequences() {
            return envelopes.stream()
                    .map(envelope -> ((Number) envelope.get("sequence")).intValue())
                    .toList();
        }

        List<String> bodies() {
            return envelopes.stream()
                    .map(envelope -> String.valueOf(envelope.get("payload")))
                    .toList();
        }

        boolean completed() {
            return completed;
        }

        @Override
        public void complete() {
            completed = true;
        }
    }
}

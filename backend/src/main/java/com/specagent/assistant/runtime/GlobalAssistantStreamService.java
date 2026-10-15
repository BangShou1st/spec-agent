package com.specagent.assistant.runtime;

import com.specagent.assistant.conversation.GlobalAssistantRunEvent;
import com.specagent.assistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 文件名:GlobalAssistantStreamService.java
 *
 * 用途:公开 run 事件的实时推送(SSE)与持久化回放。
 * 传输断开绝不会让 run 失败或取消。
 *
 * 单个 run 的顺序契约:序列号严格递增、不重复、不缺号。
 * 发布只发生在事件事务提交之后;subscribe 先回放 cursor 之后的已提交事件,
 * 再按订阅者串行补发,每个订阅者各自维护
 * 最后发送的序列号用于去重。终态事件会关闭对应的 emitter。
 *
 * <strong>跨实例契约(design §8)。</strong>进程内 {@link #publish} 只能送达
 * <b>本进程</b>的订阅者:多 Worker 部署下,执行事件的 Worker 与持有 SSE 连接的
 * API 实例往往不是同一个进程。因此本服务额外提供基于<b>数据库游标</b>的补发
 * ({@link #catchUpAll}),由 {@code GlobalAssistantStreamCatchUpPoller} 周期调用:
 * 它为每个订阅者按 {@code lastSent} 从 {@code global_assistant_run_events} 读取
 * 已提交事件并补发。两条件路径以序列号对齐,因此同一条事件绝不会重复投递
 * (补发时 {@code lastSent} 已经前进),也绝不会漏投(轮询读取的是持久化事实)。
 *
 * 该补发同时是<b>断线重连</b>的兜底:客户端带上 {@code Last-Event-ID} 重新订阅时,
 * {@link #subscribe} 会先回放游标之后的事件,再由轮询继续跟进。
 */
@Service
public class GlobalAssistantStreamService {

    /**
     * 单次补发每个订阅者最多读取的事件数。有界读取保证一个长期落后的订阅者
     * 不会在一次轮询里拉走整个事件历史,也保证轮询耗时可控(design §8:
     * "每次读有限数量")。
     */
    private static final int CATCH_UP_BATCH = 200;

    private final GlobalAssistantRunEventRepository events;
    private final GlobalAssistantRunRepository runs;
    private final com.specagent.access.AuthorizationGuard guard;
    private final com.specagent.access.BrowserSessionValidity sessions;
    private final com.specagent.assistant.conversation.GaSourceDependencyGuard sourceDependencies;
    private final Map<UUID, SubscriptionBucket> buckets = new ConcurrentHashMap<>();
    // Cursor notifications coalesce per subscriber; no event payload queue is retained in memory.
    private final java.util.concurrent.ThreadPoolExecutor delivery = new java.util.concurrent.ThreadPoolExecutor(
            4, 4, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(128), runnable -> {
                var thread = new Thread(runnable, "ga-sse-delivery");
                thread.setDaemon(true);
                return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private static final class SubscriptionBucket {
        final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
        int operations; // Accessed only within the map's per-key compute operation.
    }
    public GlobalAssistantStreamService(GlobalAssistantRunEventRepository events,
            GlobalAssistantRunRepository runs, com.specagent.access.AuthorizationGuard guard,
            com.specagent.access.BrowserSessionValidity sessions,
            com.specagent.assistant.conversation.GaSourceDependencyGuard sourceDependencies) {
        this.events = events;
        this.runs = runs;
        this.guard=guard;
        this.sessions=sessions;
        this.sourceDependencies=sourceDependencies;
    }
    record Subscriber(UUID id, SseEmitter emitter, java.util.concurrent.atomic.AtomicInteger lastSent,
                      com.specagent.access.ActorContext actor,String sessionId,
                      java.util.concurrent.atomic.AtomicBoolean scheduled,
                      java.util.concurrent.atomic.AtomicBoolean closed) {
    }
    static boolean isTerminal(String type) {
        return com.specagent.assistant.conversation.GlobalAssistantEventType.RUN_COMPLETED.equals(type)
                || com.specagent.assistant.conversation.GlobalAssistantEventType.RUN_FAILED.equals(type)
                || com.specagent.assistant.conversation.GlobalAssistantEventType.RUN_CANCELLED.equals(type);
    }
    private SubscriptionBucket acquire(UUID runId, boolean create) {
        return buckets.compute(runId, (key, previous) -> {
            if (previous == null && !create) return null;
            SubscriptionBucket bucket = previous == null ? new SubscriptionBucket() : previous;
            bucket.operations++;
            return bucket;
        });
    }
    private void release(UUID runId, SubscriptionBucket expected) {
        buckets.computeIfPresent(runId, (key, current) -> {
            if (current != expected || current.operations < 1)
                throw new IllegalStateException("Subscription bucket ownership mismatch");
            current.operations--;
            return current.operations == 0 && current.subscribers.isEmpty() ? null : current;
        });
    }
    public void publish(UUID runId, GlobalAssistantRunEvent event) {
        if (event == null || !runId.equals(event.runId()))
            throw new IllegalArgumentException("Event does not belong to the requested Run");
        SubscriptionBucket bucket = acquire(runId, false);
        if (bucket == null) return;
        try {
            for (Subscriber sub : List.copyOf(bucket.subscribers)) schedule(runId, sub);
        } finally { release(runId, bucket); }
    }

    /** Production poller never performs socket writes on the shared scheduling thread. */
    public void scheduleCatchUpAll() {
        for (UUID runId : List.copyOf(buckets.keySet())) {
            SubscriptionBucket bucket = acquire(runId, false);
            if (bucket == null) continue;
            try {
                for (Subscriber sub : List.copyOf(bucket.subscribers)) schedule(runId, sub);
            } finally { release(runId, bucket); }
        }
    }

    private void schedule(UUID runId, Subscriber sub) {
        if (sub.closed().get() || !sub.scheduled().compareAndSet(false, true)) return;
        try {
            delivery.execute(() -> {
                try { catchUp(runId, sub); }
                finally { sub.scheduled().set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException overloadedOrStopping) {
            sub.scheduled().set(false);
            // Disconnect rather than grow a queue or make the publisher write to the socket.
            // A reconnect starts at the client's last received DB cursor.
            // Even complete() can wait behind a servlet write. Do not call it on the
            // publisher/poller thread; the finite transport timeout reclaims this connection.
            removeSubscriber(runId, sub);
        }
    }

    @jakarta.annotation.PreDestroy
    public void stopDelivery() { delivery.shutdownNow(); }
    public SseEmitter subscribe(UUID runId, int cursor) {
        return subscribe(runId, cursor, () -> new SseEmitter(30_000L));
    }

    /**
     * 订阅的实现,允许注入 emitter 工厂。
     *
     * <p>抽出工厂是为了让"跨实例补发"的协议可以在测试里用真实的
     * {@link SseEmitter} 语义验证(捕获实际发送的数据项与序号),而不必启动
     * HTTP 容器或用 mock 替换发送路径。
     */
    public SseEmitter subscribe(UUID runId, int cursor, EmitterFactory emitterFactory) {
        if (cursor < 0) throw new IllegalArgumentException("Invalid event cursor");
        var actor=guard.ownershipActor();
        requireAccess(runId,actor);
        String sessionId=com.specagent.access.BrowserSessionContext.current();
        if (actor.source()==com.specagent.access.ActorSource.OIDC && (sessionId==null || !sessions.active(sessionId)))
            throw com.specagent.access.AccessDeniedException.forbidden(
                    com.specagent.access.AccessReason.UNAUTHENTICATED,"Browser session is required for SSE");
        SseEmitter emitter = emitterFactory.create();
        Subscriber sub = new Subscriber(UUID.randomUUID(), emitter,
                new java.util.concurrent.atomic.AtomicInteger(cursor),actor,sessionId,
                new java.util.concurrent.atomic.AtomicBoolean(), new java.util.concurrent.atomic.AtomicBoolean());
        emitter.onCompletion(() -> removeSubscriber(runId, sub));
        emitter.onTimeout(() -> removeSubscriber(runId, sub));
        emitter.onError(error -> removeSubscriber(runId, sub));
        SubscriptionBucket bucket = acquire(runId, true);
        try {
            synchronized (sub) {
                bucket.subscribers.add(sub);
                try {
                    if (!authorized(runId, sub)) return emitter;
                    List<GlobalAssistantRunEvent> replay = events.findAfter(runId, sub.lastSent().get(), CATCH_UP_BATCH);
                    for (GlobalAssistantRunEvent event : replay) {
                        if (event.sequence() <= sub.lastSent().get()) {
                            continue;
                        }
                        if (!authorized(runId, sub)) return emitter;
                        emitter.send(eventToSse(runId, event));
                        sub.lastSent().set(event.sequence());
                        if (isTerminal(event.type())) {
                            completeSubscriber(runId, sub);
                            return emitter;
                        }
                    }
                    if (replay.size() < CATCH_UP_BATCH && isRunTerminal(runId)) {
                        completeSubscriber(runId, sub);
                    }
                } catch (IOException ex) {
                    removeSubscriber(runId, sub);
                    emitter.completeWithError(ex);
                } catch (RuntimeException ex) {
                    removeSubscriber(runId, sub);
                    emitter.completeWithError(ex);
                    throw ex;
                }
            }
        } finally { release(runId, bucket); }
        return emitter;
    }

    /** 创建订阅用 emitter 的工厂(测试与嵌入式使用)。 */
    @FunctionalInterface
    public interface EmitterFactory {
        SseEmitter create();
    }
    public List<Map<String, Object>> listEnvelopes(UUID runId) {
        requireAccess(runId,guard.ownershipActor());
        String threadId = runs.findById(runId).map(r -> r.threadId().toString()).orElse("");
        return events.findByRun(runId).stream().map(e -> envelope(runId, threadId, e)).toList();
    }

    /**
     * 按数据库游标为<b>全部</b>订阅者补发已提交事件(design §8)。
     *
     * <p>这是跨实例投递的第一阶段兜底:与 {@link #publish} 的进程内直送互补,
     * 保证由另一个 Worker 提交的事件也能送达本实例的 SSE 连接。
     *
     * <p>幂等与顺序:每个订阅者以自己的 {@code lastSent} 为起点读取,发送成功后才
     * 推进游标;因此在 {@code publish} 与补发之间不会产生重复,也不会漏投。每次
     * 每个订阅者最多读取 {@link #CATCH_UP_BATCH} 条,剩余部分由下一次轮询继续。
     *
     * 同步嵌入式调用入口。生产轮询使用 {@link #scheduleCatchUpAll()}，避免 socket
     * 等待占用共享调度线程。
     * @return 本次实际补发的事件总数
     */
    public int catchUpAll() {
        int delivered = 0;
        for (UUID runId : List.copyOf(buckets.keySet())) {
            SubscriptionBucket bucket = acquire(runId, false);
            if (bucket == null) continue;
            try {
                for (Subscriber sub : List.copyOf(bucket.subscribers)) delivered += catchUp(runId, sub);
            } finally { release(runId, bucket); }
        }
        return delivered;
    }

    /** 为单个订阅者补发;发送过程中断即摘除它,绝不影响其他订阅者。 */
    private int catchUp(UUID runId, Subscriber sub) {
        synchronized (sub) {
            if (sub.closed().get()) return 0;
            return catchUpLocked(runId, sub);
        }
    }

    private int catchUpLocked(UUID runId, Subscriber sub) {
        if (!authorized(runId,sub)) return 0;
        int sent = 0;
        try {
            List<GlobalAssistantRunEvent> pending =
                    events.findAfter(runId, sub.lastSent().get(), CATCH_UP_BATCH);
            for (GlobalAssistantRunEvent event : pending) {
                if (event.sequence() <= sub.lastSent().get()) {
                    continue;
                }
                if (!authorized(runId, sub)) return sent;
                sub.emitter().send(eventToSse(runId, event));
                sub.lastSent().set(event.sequence());
                sent++;
                if (isTerminal(event.type())) {
                    completeSubscriber(runId, sub);
                    return sent;
                }
            }
            // 事件流已经读完但 run 已终态(事件在别的实例提交完毕):关闭订阅,
            // 否则客户端会一直挂着一个不会再有事件的连接。
            if (sent == 0 && pending.isEmpty() && isRunTerminal(runId)) {
                completeSubscriber(runId, sub);
            }
        } catch (IOException | RuntimeException ex) {
            completeSubscriber(runId, sub);
        }
        return sent;
    }

    /** 当前活跃订阅者数(健康检查/指标用)。 */
    public int subscriberCount() {
        return buckets.values().stream().mapToInt(bucket -> bucket.subscribers.size()).sum();
    }
    /** Diagnostic for retained per-run state; closed subscriptions must leave no bucket behind. */
    public int subscriptionBucketCount() { return buckets.size(); }
    private void requireAccess(UUID runId, com.specagent.access.ActorContext actor) {
        UUID thread=runs.findById(runId).map(r->r.threadId()).orElseThrow(() ->
                com.specagent.access.AccessDeniedException.notVisible("Run not found"));
        guard.requireThreadOwnershipFor(actor,thread);
        // 公开事件载荷携带 Host 投影的项目资源引用与来源卡片(excerpt),是内容级
        // 泄漏面:依赖已撤权/未知的 run 不得再回放,订阅者在补发时也会被这里挡下
        // (authorized() 捕获后关闭连接,不会让业务 Run 失败)。
        sourceDependencies.requireSourcesReadable(actor,thread);
    }
    private boolean authorized(UUID runId, Subscriber sub) {
        try {
            if (sub.actor().source()==com.specagent.access.ActorSource.OIDC && !sessions.active(sub.sessionId())) {
                completeSubscriber(runId,sub); return false;
            }
            requireAccess(runId,sub.actor()); return true;
        } catch (RuntimeException deniedOrUnavailable) {
            // A failed permission/session read is not permission to send, nor a reason to fail the business Run.
            completeSubscriber(runId,sub); return false;
        }
    }
    private void removeSubscriber(UUID runId, Subscriber sub) {
        sub.closed().set(true);
        SubscriptionBucket bucket = acquire(runId, false);
        if (bucket == null) return;
        try {
            bucket.subscribers.remove(sub);
        } finally { release(runId, bucket); }
    }
    private void completeSubscriber(UUID runId, Subscriber sub) {
        removeSubscriber(runId, sub);
        try {
            sub.emitter().complete();
        } catch (IllegalStateException ex) {
            removeSubscriber(runId, sub);
        }
    }
    private boolean isRunTerminal(UUID runId) {
        return runs.findById(runId).map(run -> run.status().isTerminal()).orElse(true);
    }
    private SseEmitter.SseEventBuilder eventToSse(UUID runId, GlobalAssistantRunEvent event) {
        String threadId = runs.findById(runId).map(r -> r.threadId().toString()).orElse("");
        return SseEmitter.event()
                .id(String.valueOf(event.sequence()))
                .name(event.type())
                .data(envelope(runId, threadId, event));
    }
    private Map<String, Object> envelope(UUID runId, String threadId, GlobalAssistantRunEvent event) {
        return Map.of(
                "eventId", event.id().toString(),
                "runId", runId.toString(),
                "threadId", threadId,
                "type", event.type(),
                "sequence", event.sequence(),
                "createdAt", event.createdAt().toString(),
                "payload", event.payload() == null ? Map.of() : event.payload());
    }
}

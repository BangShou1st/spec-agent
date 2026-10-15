package com.specagent.access;

/**
 * 文件名:ActorContextHolder.java
 *
 * 用途:请求/执行线程的主体上下文管线。
 *
 * <b>它不是权威</b>——与 P1 的 {@code ExecutionTokenContext} 完全同构:
 * 它只是把已解析的 ActorContext 传到需要它的调用点,权威永远是
 * {@link AuthorizationGuard} 在事务内读取的持久化事实 + 授权范围锁。
 *
 * 为什么仍然是线程本地:画布与 GA 的调用栈深,把 ActorContext 逐层作为
 * 参数会污染数十个既有签名;而判定点全部收敛在 AuthorizationGuard,
 * 因此"管线 + 单一权威"的组合不会产生绕过。异步/Worker 线程没有请求
 * 上下文,其主体必须从持久化任务记录重建(P2-D),绝不继承发起线程。
 */
public final class ActorContextHolder {

    private static final ThreadLocal<ActorContext> CURRENT = new ThreadLocal<>();

    private ActorContextHolder() {
    }

    public static void set(ActorContext context) {
        CURRENT.set(context);
    }

    public static ActorContext current() {
        return CURRENT.get();
    }

    public static ActorContext require() {
        ActorContext context = CURRENT.get();
        if (context == null) {
            throw new AccessDeniedException(AccessReason.UNAUTHENTICATED, false,
                    "No actor context is bound to this thread");
        }
        return context;
    }

    /**
     * 绑定一个显式主体并返回可关闭句柄。用于后台执行线程:主体来自持久化
     * 任务记录,用完必须复原,避免线程复用把上一个任务的身份泄漏给下一个。
     */
    public static Scope bind(ActorContext context) {
        ActorContext previous = CURRENT.get();
        CURRENT.set(context);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static void clear() {
        CURRENT.remove();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}

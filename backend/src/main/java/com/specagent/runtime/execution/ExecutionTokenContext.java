package com.specagent.runtime.execution;

import java.util.Optional;

/**
 * 文件名:ExecutionTokenContext.java
 *
 * 用途:当前执行线程所携带的显式执行 token(design §6.3)。
 *
 * <p><b>它不是权威。</b>权威永远是 runtime_tasks 行:一切受保护写入都必须拿
 * 这个 token 去数据库取任务行排他锁并逐项核实。本上下文只是把 token 从调度层
 * 传到业务写入层的管线,让"显式 token"不必穿透每一个方法签名;即便某个调用点
 * 读到的是过期 token,数据库核实也会拒绝它。
 *
 * <p>只有执行适配器可以绑定它,API-only 路径绝不绑定——因此用户命令永远不会
 * 获得执行所有权。
 */
public final class ExecutionTokenContext {

    private static final ThreadLocal<ExecutionToken> CURRENT = new ThreadLocal<>();

    private ExecutionTokenContext() {
    }

    /** 在执行线程上绑定 token;必须与 {@link #clear} 成对使用。 */
    public static void bind(ExecutionToken token) {
        CURRENT.set(token);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<ExecutionToken> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 在绑定 token 的区间内执行任务,结束后无条件清除绑定。 */
    public static void runWith(ExecutionToken token, Runnable action) {
        ExecutionToken previous = CURRENT.get();
        CURRENT.set(token);
        try {
            action.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}

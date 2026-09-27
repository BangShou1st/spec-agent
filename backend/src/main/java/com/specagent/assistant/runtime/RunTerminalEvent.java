package com.specagent.assistant.runtime;

import java.util.UUID;

/**
 * 文件名:RunTerminalEvent.java
 *
 * 用途:run 进入终态后发布的 Spring 应用事件,驱动后端自主的
 * 后续交接(如 steer 的继任 run 派发),与执行线程解耦。
 */
public record RunTerminalEvent(UUID threadId, UUID runId, String terminalStatus) {
}

package com.specagent.agent.action;

import java.util.UUID;

/**
 * 文件名:ActionResult.java
 *
 * 用途:一次动作提案执行后的结果,携带被执行的动作族以及产出的
 * Runtime 产物(节点、Answer、消息)的 id。
 *
 * 协作:由 ActionExecutor.execute 返回,供决策循环记录执行结果并
 * 推进后续流程。
 */
public record ActionResult(String actionFamily,
                           UUID producedNodeId,
                           UUID producedAnswerId,
                           String message) {
}

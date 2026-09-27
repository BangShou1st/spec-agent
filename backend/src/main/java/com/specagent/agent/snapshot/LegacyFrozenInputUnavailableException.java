package com.specagent.agent.snapshot;

/**
 * 文件名:LegacyFrozenInputUnavailableException.java
 *
 * 用途:请求语义回放时,发现该 snapshot 在"冻结输入契约"诞生之前
 * 就已被模型消费过。基于当前活记录重建旧的模型输入并冒充回放,
 * 会破坏可复现性与可审计性。
 *
 * 协作:调用方必须以本类型化领域异常 fail-closed——不产出第二个
 * Answer、第二个 Patch,不静默重跑 STATE_UPDATE,也不活体重建旧 DECISION
 * 输入。应当改为从新的 ContextSnapshot 发起一次全新的重试。
 */
public class LegacyFrozenInputUnavailableException extends RuntimeException {

    public LegacyFrozenInputUnavailableException(String message) {
        super(message);
    }
}

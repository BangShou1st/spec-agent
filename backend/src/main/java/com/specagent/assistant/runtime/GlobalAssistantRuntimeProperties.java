package com.specagent.assistant.runtime;

import org.springframework.stereotype.Component;

/**
 * 文件名:GlobalAssistantRuntimeProperties.java
 *
 * 用途:runtime 循环的预算上限(步数、工具调用次数)。
 * 常规 run 只需要 0-3 次工具调用,这里给出的上限是防失控的安全网。
 */
@Component
public class GlobalAssistantRuntimeProperties {
    public int maxSteps() {
        return 6;
    }
    public int maxToolCalls() {
        return 5;
    }
}

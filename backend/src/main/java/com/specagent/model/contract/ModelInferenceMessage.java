package com.specagent.model.contract;

/**
 * 文件名:ModelInferenceMessage.java
 *
 * 用途:一条经过运行时批准的、提供商无关的聊天消息(role + content)。
 */
public record ModelInferenceMessage(String role, String content) {
}

package com.specagent.skill.parser;

/**
 * 文件名:SkillParseException.java
 *
 * 用途:SKILL.md 包解析失败或内容非法时的类型化异常。绝不向模型或用户
 * 泄漏提供方细节与堆栈信息。
 */
public class SkillParseException extends RuntimeException {

    public SkillParseException(String message) {
        super(message);
    }
}
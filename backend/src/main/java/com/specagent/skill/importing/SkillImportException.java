package com.specagent.skill.importing;

/**
 * 文件名:SkillImportException.java
 *
 * 用途:Skill 导入/校验/安装问题的类型化失败。可预期的失败(不安全压缩包、
 * 非法 git ref、超限包)都以这个类型化异常呈现,API 因此能给出干净的消息
 * —— 底层提供方的原始异常绝不会抵达模型或用户。
 */
public class SkillImportException extends RuntimeException {

    public SkillImportException(String message) {
        super(message);
    }

    public SkillImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
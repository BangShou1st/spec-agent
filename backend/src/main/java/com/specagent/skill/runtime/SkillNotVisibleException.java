package com.specagent.skill.runtime;

/**
 * 文件名:SkillNotVisibleException.java
 *
 * 用途:Skill 存在但不可见/不可激活(未知、已停用或未安装)时的类型化
 * 异常。向调用方呈现干净的消息 —— 绝不携带底层提供方细节或堆栈信息。
 */
public class SkillNotVisibleException extends RuntimeException {

    public SkillNotVisibleException(String skillId) {
        super("Skill is not visible or not activated: " + skillId);
    }
}
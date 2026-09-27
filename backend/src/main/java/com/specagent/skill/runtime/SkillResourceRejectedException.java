package com.specagent.skill.runtime;

/**
 * 文件名:SkillResourceRejectedException.java
 *
 * 用途:Skill 资源读取被拒绝时的类型化异常(路径穿越、超大、不存在,
 * 或一期中的二进制文件)。绝不暴露提供方细节与堆栈信息。
 */
public class SkillResourceRejectedException extends RuntimeException {

    public SkillResourceRejectedException(String message) {
        super(message);
    }
}
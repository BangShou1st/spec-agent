package com.specagent.capability;

/**
 * 文件名:SkillAdapter.java
 *
 * 用途:Skill 包适配器的标记接口。一个 Skill 是可复用的能力包
 * (指令、Schema、参考资料、底层工具调用)——而不是某种 Agent 人设。
 * 本阶段尚未接入任何 Skill 适配器;保留这一边界是为了让 Skill 内部实现
 * 可以独立演进,而不改动动作协议。
 */
public interface SkillAdapter extends CapabilityAdapter {
}

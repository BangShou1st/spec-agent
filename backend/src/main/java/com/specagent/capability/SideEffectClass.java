package com.specagent.capability;

/**
 * 文件名:SideEffectClass.java
 *
 * 用途:能力的副作用分类。策略引擎(Policy Engine)——而不是模型——根据这个
 * 由运行时持有的分类推导审批要求。
 */
public enum SideEffectClass {

    /** 只读的检索/计算;任何地方都不会产生持久化变更。 */
    NONE,
    /** 仅在本地图谱/工作区内产生持久化变更。 */
    LOCAL_DURABLE,
    /** 外部副作用,可以通过另一个提供方动作来撤销。 */
    EXTERNAL_REVERSIBLE,
    /** 外部副作用,无法通过图谱撤销(undo)回退。 */
    EXTERNAL_IRREVERSIBLE;

    public String code() {
        return name();
    }

    public static SideEffectClass fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Side effect class must not be null");
        }
        return valueOf(code.trim().toUpperCase());
    }
}

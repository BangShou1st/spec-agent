package com.specagent.model.contract;

import java.util.Optional;

/**
 * 文件名:ActiveModelTargetPort.java
 *
 * 用途:"当前激活提供商 + 其选中模型"的只读投影端口。由设置侧实现,供推理侧
 * 做 Run 级模型冻结读取——它只读取既有设置事实,绝不改变任何选择,也不是
 * 第二套模型选择系统。
 */
public interface ActiveModelTargetPort {

    /**
     * 当前激活提供商与其选中模型;未配置或模型为空时返回 {@code Optional.empty()}。
     * 目标不可解析时实现方可抛出具类型的失败,由调用方按"目标不可用"处理。
     */
    Optional<Target> activeTarget();

    /** 提供商编码 + 模型 id 的不可变对;不含任何凭据。 */
    record Target(String provider, String model) {
    }
}

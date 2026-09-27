package com.specagent.eval;

import java.util.List;

/**
 * 文件名:ResourceSpec.java
 *
 * 用途:声明搭建阶段要附加的一个资源,由文本种子(textSeed)确定性渲染
 * 而非固定句子。{@code canonical()} 生成场景哈希用的规范化字符串。
 *
 * 协作:由 {@link GivenSpec} 持有,运行器据此在图上附加 TEXT 资源。
 */
public record ResourceSpec(String textSeed) {

    public static String canonical(List<ResourceSpec> resources) {
        return "resources" + resources.stream()
                .map(spec -> "res(" + spec.textSeed() + ")")
                .sorted()
                .toList();
    }
}

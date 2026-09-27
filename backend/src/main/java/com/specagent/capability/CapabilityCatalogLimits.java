package com.specagent.capability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件名:CapabilityCatalogLimits.java
 *
 * 用途:有界能力目录的容量上限配置(通过 spec.agent.capability 前缀注入)。
 * 默认值是基于真实上下文预算做出的工程取舍;模型输出永远无法改变这些上限,
 * 以防止模型通过描述膨胀挤占上下文窗口。
 */
@Component
@ConfigurationProperties(prefix = "spec.agent.capability")
public class CapabilityCatalogLimits {

    private int maxVisible = 32;
    private int maxDescriptorBytes = 2048;
    private int maxDescriptionChars = 320;

    public int maxVisible() {
        return maxVisible;
    }

    public void setMaxVisible(int maxVisible) {
        this.maxVisible = maxVisible;
    }

    public int maxDescriptorBytes() {
        return maxDescriptorBytes;
    }

    public void setMaxDescriptorBytes(int maxDescriptorBytes) {
        this.maxDescriptorBytes = maxDescriptorBytes;
    }

    public int maxDescriptionChars() {
        return maxDescriptionChars;
    }

    public void setMaxDescriptionChars(int maxDescriptionChars) {
        this.maxDescriptionChars = maxDescriptionChars;
    }

    public static CapabilityCatalogLimits defaults() {
        return new CapabilityCatalogLimits();
    }
}
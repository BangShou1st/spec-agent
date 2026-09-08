package com.specagent.capability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bounded-capability catalog limits. Defaults are engineering choices driven
 * by realistic context budgets; model output can never change these limits.
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
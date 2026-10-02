package com.specagent.common;

/** Shared transport configuration port; consumers do not depend on Agent Runtime. */
public interface BrainConnectionSettings {
    String getBaseUrl();
    String getInternalSecret();
}

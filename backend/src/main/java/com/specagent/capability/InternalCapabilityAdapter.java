package com.specagent.capability;

/**
 * Marker for capabilities implemented inside the Java runtime (internal
 * tools). Internal adapters may read runtime state through repositories but
 * own no credentials and never reach model/gateway internals.
 */
public interface InternalCapabilityAdapter extends CapabilityAdapter {
}

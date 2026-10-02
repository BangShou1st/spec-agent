package com.specagent.capability;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Read-only network preparation outside business transactions; commit remains host owned. */
public interface PreparedCapabilityAdapter extends InternalCapabilityAdapter {
    default Function<CapabilityInvocation,CapabilityResult> prepare(Map<String,Object> arguments,BooleanSupplier active,java.util.UUID runId) {
        return prepare(arguments,active);
    }
    Function<CapabilityInvocation, CapabilityResult> prepare(Map<String, Object> arguments, BooleanSupplier active);
}

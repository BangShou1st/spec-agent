package com.specagent.assistant.tool;

import com.specagent.capability.*;
import java.util.*;
import java.util.function.*;

/** Read-only preparation failures are durable tool observations; cancellation remains a host fence. */
final class RetrievalToolFailures {
    private RetrievalToolFailures() {}
    static Function<CapabilityInvocation,CapabilityResult> guarded(String capability,BooleanSupplier active,
            Supplier<Function<CapabilityInvocation,CapabilityResult>> prepare) {
        try { return prepare.get(); }
        catch(RuntimeException failure) {
            if(!active.getAsBoolean()) throw new IllegalStateException("GA_EXECUTION_FENCE");
            String message=failure.getMessage();
            String code=message!=null && Set.of("RETRIEVAL_UNAVAILABLE","OLLAMA_UNAVAILABLE","SOURCE_VERSION_MISMATCH","INDEX_GENERATION_MISMATCH",
                "STALE_SCOPE_GRANT","STALE_WORKLOAD","DEADLINE_EXCEEDED","UNSUPPORTED_PROFILE").contains(message)
                ? message:"RETRIEVAL_UNAVAILABLE";
            return invocation->GlobalAssistantToolFailures.failed(invocation,capability,code,"Retrieval could not produce verified evidence");
        }
    }
}

package com.specagent.agent.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.specagent.common.Json;

/**
 * Strict JSON access for the cross-language contracts.
 *
 * <p>The mapper is deliberately stricter than the default application mapper:
 * unknown properties, ambiguous enums and nulls for primitives all fail
 * closed. Both the request envelope (Java-built) and the response envelope
 * (brain-built, untrusted) go through this mapper so golden fixtures behave
 * identically on both sides of the boundary.
 */
public final class AgentContracts {

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, false)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Json JSON = new Json(STRICT_MAPPER);

    private AgentContracts() {
    }

    /** Serializes a contract value with null fields included on the wire. */
    public static String write(Object value) {
        return JSON.write(value);
    }

    /**
     * Parses a contract value fail-closed. Any unknown field, unknown enum or
     * shape violation raises {@link AgentContractException}.
     */
    public static <T> T read(String json, Class<T> type) {
        try {
            return JSON.read(json, type);
        } catch (IllegalStateException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof com.fasterxml.jackson.databind.DatabindException databindEx) {
                throw new AgentContractException(
                        "Contract violation in " + type.getSimpleName() + ": "
                                + databindEx.getOriginalMessage());
            }
            throw new AgentContractException(
                    "Contract violation in " + type.getSimpleName() + ": " + ex.getMessage());
        }
    }
}

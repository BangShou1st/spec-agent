package com.specagent.assistant.api;

import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.assistant.runtime.GaCheckpointStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Authenticated internal GA boundary; legacy runs have no execution binding and are rejected. */
@RestController
@DependsOn("installInternalSecret")
@RequestMapping("/internal/v1/global-assistant")
public class GaHostRpcController {
    private final AgentBrainProperties properties;
    private final GaNativeModelBroker models;
    private final GaCheckpointStore checkpoints;
    private final GaCapabilityBroker capabilities;
    private final com.specagent.assistant.runtime.GaExecutionStore executions;
    public GaHostRpcController(AgentBrainProperties properties, GaNativeModelBroker models, GaCheckpointStore checkpoints,
            GaCapabilityBroker capabilities, com.specagent.assistant.runtime.GaExecutionStore executions) {
        this.properties = properties; this.models = models; this.checkpoints = checkpoints; this.capabilities=capabilities;
        this.executions=executions;
    }
    @PostMapping("/model-inference")
    public ResponseEntity<?> model(@RequestHeader(value="X-Spec-Agent-Internal-Token", required=false) String token,
            @RequestBody String body) {
        if (!authorized(token)) return ResponseEntity.status(401).build();
        var result = models.invoke(body);
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @PostMapping("/checkpoints/{operation}")
    public ResponseEntity<?> checkpoint(@RequestHeader(value="X-Spec-Agent-Internal-Token", required=false) String token,
            @PathVariable String operation, @RequestBody String body) {
        if (!authorized(token)) return ResponseEntity.status(401).build();
        try { return ResponseEntity.ok(checkpoints.apply(operation, body)); }
        catch (IllegalArgumentException ex) { return error(400, "GA_CHECKPOINT_PROTOCOL_ERROR"); }
        catch (IllegalStateException ex) { return error(409, safeCode(ex)); }
    }
    @PostMapping(value="/model-inference/stream", produces="application/x-ndjson")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> modelStream(
            @RequestHeader(value="X-Spec-Agent-Internal-Token", required=false) String token, @RequestBody String body) {
        if (!authorized(token)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("application/x-ndjson"))
                .body(output -> models.stream(body,output));
    }
    @PostMapping("/capabilities")
    public ResponseEntity<?> capability(@RequestHeader(value="X-Spec-Agent-Internal-Token",required=false) String token,
            @RequestBody String body) {
        if (!authorized(token)) return ResponseEntity.status(401).build();
        var result=capabilities.invoke(body);
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @PostMapping("/execution-claim")
    public ResponseEntity<?> claim(@RequestHeader(value="X-Spec-Agent-Internal-Token",required=false) String token,
            @RequestBody String body) {
        if (!authorized(token)) return ResponseEntity.status(401).build();
        try { return ResponseEntity.ok(executions.claimExecutor(body)); }
        catch(IllegalArgumentException ex) { return error(400,"GA_EXECUTION_CLAIM_PROTOCOL_ERROR"); }
        catch(IllegalStateException ex) { return error(409,"GA_EXECUTION_NOT_CLAIMED"); }
    }
    private boolean authorized(String token) {
        String expected = properties.getInternalSecret();
        return expected != null && !expected.isBlank() && token != null && !token.isBlank()
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
    private static ResponseEntity<?> error(int status, String code) { return ResponseEntity.status(status).body(Map.of("errorCode", code)); }
    private static String safeCode(IllegalStateException ex) {
        return java.util.Set.of("GA_EXECUTION_FENCE", "GA_MODEL_BINDING_MISMATCH", "GA_MODEL_CATALOG_MISMATCH",
                "GA_MODEL_NOT_CONFIGURED", "GA_CALL_IN_PROGRESS_OR_UNKNOWN", "GA_CALL_ID_CONFLICT",
                "GA_BUDGET_EXHAUSTED", "GA_CHECKPOINT_CAS_CONFLICT", "GA_CALL_NOT_RESERVED").contains(ex.getMessage())
                ? ex.getMessage() : "GA_HOST_STATE_ERROR";
    }
}

package com.specagent.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Uniform observation of one scenario attempt.
 *
 * <p>Covers pre/post state, the actual primary action, the execution
 * result, the state delta, invariant/property outcomes, violations, the
 * failure class, call accounting (production calls, provider retries,
 * judge calls, capability calls), token/cost/latency accounting, and
 * reproducibility metadata. Fields the runtime cannot provide stay
 * {@code unknown}/null — never guessed. Cost in particular is
 * {@code unknown} unless a real pricing source reports it.
 */
public final class ObservationEnvelope {

    public static final String SCHEMA_VERSION = "eval-observation.v1";

    private final String schemaVersion;
    private final String runId;
    private final String gitSha;
    private final String scenarioId;
    private final String variantId;
    private final String scenarioHash;
    private final EvaluationProfile evaluationProfile;
    private final String provider;
    private final String model;
    private final String modelConfigDigest;
    private final String brainContractDigest;
    private final String contextSnapshotVersion;
    private final String contextSnapshotHash;
    private final String capabilityManifestDigest;
    private final String resourceManifestDigest;
    private final StateSummary preState;
    private final StateSummary postState;
    private final String actualPrimaryAction;
    private final String executionResult;
    private final Map<String, Integer> stateDelta;
    private final List<CheckResult> invariantResults;
    private final List<CheckResult> propertyResults;
    private final List<Violation> violations;
    private final int productionModelCalls;
    private final int providerRetries;
    private final int judgeModelCalls;
    private final int capabilityCalls;
    private final Long inputTokens;
    private final Long outputTokens;
    private final String cost;
    private final String pricingVersion;
    private final Long latencyMs;
    private final Map<String, Long> stageLatencyMs;
    private final FailureClass failureClass;
    private final Instant timestamp;
    private final long seed;

    private ObservationEnvelope(Builder builder) {
        this.schemaVersion = SCHEMA_VERSION;
        this.runId = builder.runId;
        this.gitSha = builder.gitSha;
        this.scenarioId = builder.scenarioId;
        this.variantId = builder.variantId;
        this.scenarioHash = builder.scenarioHash;
        this.evaluationProfile = builder.evaluationProfile;
        this.provider = builder.provider;
        this.model = builder.model;
        this.modelConfigDigest = builder.modelConfigDigest;
        this.brainContractDigest = builder.brainContractDigest;
        this.contextSnapshotVersion = builder.contextSnapshotVersion;
        this.contextSnapshotHash = builder.contextSnapshotHash;
        this.capabilityManifestDigest = builder.capabilityManifestDigest;
        this.resourceManifestDigest = builder.resourceManifestDigest;
        this.preState = builder.preState;
        this.postState = builder.postState;
        this.actualPrimaryAction = builder.actualPrimaryAction;
        this.executionResult = builder.executionResult;
        this.stateDelta = Map.copyOf(builder.stateDelta);
        this.invariantResults = List.copyOf(builder.invariantResults);
        this.propertyResults = List.copyOf(builder.propertyResults);
        this.violations = List.copyOf(builder.violations);
        this.productionModelCalls = builder.productionModelCalls;
        this.providerRetries = builder.providerRetries;
        this.judgeModelCalls = builder.judgeModelCalls;
        this.capabilityCalls = builder.capabilityCalls;
        this.inputTokens = builder.inputTokens;
        this.outputTokens = builder.outputTokens;
        this.cost = builder.cost == null ? "unknown" : builder.cost;
        this.pricingVersion = builder.pricingVersion;
        this.latencyMs = builder.latencyMs;
        this.stageLatencyMs = Map.copyOf(builder.stageLatencyMs);
        this.failureClass = builder.failureClass != null ? builder.failureClass
                : (builder.violations.isEmpty() ? null : builder.violations.get(0).failureClass());
        this.timestamp = builder.timestamp == null ? Instant.now() : builder.timestamp;
        this.seed = builder.seed;
    }

    public static Builder builder(String scenarioId, String variantId,
                                  String scenarioHash, EvaluationProfile profile) {
        return new Builder(scenarioId, variantId, scenarioHash, profile);
    }

    public boolean passed() {
        return violations.isEmpty();
    }

    public ObservationEnvelope withRunMetadata(String runId, String gitSha, String provider,
                                              String model, String modelConfigDigest) {
        Builder copy = toBuilder();
        copy.runId = runId;
        copy.gitSha = gitSha;
        copy.provider = provider;
        copy.model = model;
        copy.modelConfigDigest = modelConfigDigest;
        return copy.build();
    }

    private Builder toBuilder() {
        Builder copy = new Builder(scenarioId, variantId, scenarioHash, evaluationProfile);
        copy.runId = runId;
        copy.gitSha = gitSha;
        copy.provider = provider;
        copy.model = model;
        copy.modelConfigDigest = modelConfigDigest;
        copy.brainContractDigest = brainContractDigest;
        copy.contextSnapshotVersion = contextSnapshotVersion;
        copy.contextSnapshotHash = contextSnapshotHash;
        copy.capabilityManifestDigest = capabilityManifestDigest;
        copy.resourceManifestDigest = resourceManifestDigest;
        copy.preState = preState;
        copy.postState = postState;
        copy.actualPrimaryAction = actualPrimaryAction;
        copy.executionResult = executionResult;
        copy.stateDelta = new LinkedHashMap<>(stateDelta);
        copy.invariantResults = new ArrayList<>(invariantResults);
        copy.propertyResults = new ArrayList<>(propertyResults);
        copy.violations = new ArrayList<>(violations);
        copy.productionModelCalls = productionModelCalls;
        copy.providerRetries = providerRetries;
        copy.judgeModelCalls = judgeModelCalls;
        copy.capabilityCalls = capabilityCalls;
        copy.inputTokens = inputTokens;
        copy.outputTokens = outputTokens;
        copy.cost = cost;
        copy.pricingVersion = pricingVersion;
        copy.latencyMs = latencyMs;
        copy.stageLatencyMs = new LinkedHashMap<>(stageLatencyMs);
        copy.failureClass = failureClass;
        copy.timestamp = timestamp;
        copy.seed = seed;
        return copy;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public String runId() {
        return runId;
    }

    public String gitSha() {
        return gitSha;
    }

    public String scenarioId() {
        return scenarioId;
    }

    public String variantId() {
        return variantId;
    }

    public String scenarioHash() {
        return scenarioHash;
    }

    public EvaluationProfile evaluationProfile() {
        return evaluationProfile;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public String modelConfigDigest() {
        return modelConfigDigest;
    }

    public String brainContractDigest() {
        return brainContractDigest;
    }

    public String contextSnapshotVersion() {
        return contextSnapshotVersion;
    }

    public String contextSnapshotHash() {
        return contextSnapshotHash;
    }

    public String capabilityManifestDigest() {
        return capabilityManifestDigest;
    }

    public String resourceManifestDigest() {
        return resourceManifestDigest;
    }

    public StateSummary preState() {
        return preState;
    }

    public StateSummary postState() {
        return postState;
    }

    public String actualPrimaryAction() {
        return actualPrimaryAction;
    }

    public String executionResult() {
        return executionResult;
    }

    public Map<String, Integer> stateDelta() {
        return stateDelta;
    }

    public List<CheckResult> invariantResults() {
        return invariantResults;
    }

    public List<CheckResult> propertyResults() {
        return propertyResults;
    }

    public List<Violation> violations() {
        return violations;
    }

    public int productionModelCalls() {
        return productionModelCalls;
    }

    public int providerRetries() {
        return providerRetries;
    }

    public int judgeModelCalls() {
        return judgeModelCalls;
    }

    public int capabilityCalls() {
        return capabilityCalls;
    }

    public Long inputTokens() {
        return inputTokens;
    }

    public Long outputTokens() {
        return outputTokens;
    }

    public String cost() {
        return cost;
    }

    public String pricingVersion() {
        return pricingVersion;
    }

    public Long latencyMs() {
        return latencyMs;
    }

    public Map<String, Long> stageLatencyMs() {
        return stageLatencyMs;
    }

    public FailureClass failureClass() {
        return failureClass;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public long seed() {
        return seed;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema_version", schemaVersion);
        map.put("run_id", runId);
        map.put("git_sha", gitSha);
        map.put("scenario_id", scenarioId);
        map.put("variant_id", variantId);
        map.put("scenario_hash", scenarioHash);
        map.put("evaluation_profile", evaluationProfile == null ? null : evaluationProfile.name());
        map.put("provider", provider);
        map.put("model", model);
        map.put("model_config_digest", modelConfigDigest);
        map.put("brain_contract_digest", brainContractDigest);
        map.put("context_snapshot_version", contextSnapshotVersion);
        map.put("context_snapshot_hash", contextSnapshotHash);
        map.put("capability_manifest_digest", capabilityManifestDigest);
        map.put("resource_manifest_digest", resourceManifestDigest);
        map.put("actual_primary_action", actualPrimaryAction);
        map.put("execution_result", executionResult);
        map.put("state_delta_summary", new TreeMap<>(stateDelta));
        map.put("invariant_results", invariantResults.stream()
                .map(check -> Map.of("name", check.name(), "passed", check.passed(),
                        "detail", check.detail() == null ? "" : check.detail()))
                .toList());
        map.put("required_property_results", propertyResults.stream()
                .map(check -> Map.of("name", check.name(), "passed", check.passed(),
                        "detail", check.detail() == null ? "" : check.detail()))
                .toList());
        map.put("violations", violations.stream()
                .map(violation -> Map.of("failure_class", violation.failureClass().name(),
                        "detail", violation.detail() == null ? "" : violation.detail()))
                .toList());
        map.put("production_model_calls", productionModelCalls);
        map.put("provider_retries", providerRetries);
        map.put("judge_model_calls", judgeModelCalls);
        map.put("capability_calls", capabilityCalls);
        map.put("input_tokens", inputTokens);
        map.put("output_tokens", outputTokens);
        map.put("cost", cost);
        map.put("pricing_version", pricingVersion);
        map.put("stage_latency", new TreeMap<>(stageLatencyMs));
        map.put("total_latency", latencyMs);
        map.put("failure_class", failureClass == null ? null : failureClass.name());
        map.put("timestamp", timestamp == null ? null : timestamp.toString());
        map.put("seed", seed);
        map.put("pre_state", preState == null ? null : Map.of(
                "routes", preState.routes(), "nodes", preState.nodes(),
                "answers", preState.answers(), "patches", preState.patches(),
                "proposals", preState.proposals(),
                "capability_invocations", preState.capabilityInvocations()));
        map.put("post_state", postState == null ? null : Map.of(
                "routes", postState.routes(), "nodes", postState.nodes(),
                "answers", postState.answers(), "patches", postState.patches(),
                "proposals", postState.proposals(),
                "capability_invocations", postState.capabilityInvocations()));
        return map;
    }

    public static final class Builder {
        private final String scenarioId;
        private final String variantId;
        private final String scenarioHash;
        private final EvaluationProfile evaluationProfile;
        private String runId;
        private String gitSha;
        private String provider;
        private String model;
        private String modelConfigDigest;
        private String brainContractDigest;
        private String contextSnapshotVersion;
        private String contextSnapshotHash;
        private String capabilityManifestDigest;
        private String resourceManifestDigest;
        private StateSummary preState;
        private StateSummary postState;
        private String actualPrimaryAction;
        private String executionResult;
        private Map<String, Integer> stateDelta = new LinkedHashMap<>();
        private List<CheckResult> invariantResults = new ArrayList<>();
        private List<CheckResult> propertyResults = new ArrayList<>();
        private List<Violation> violations = new ArrayList<>();
        private int productionModelCalls;
        private int providerRetries;
        private int judgeModelCalls;
        private int capabilityCalls;
        private Long inputTokens;
        private Long outputTokens;
        private String cost;
        private String pricingVersion;
        private Long latencyMs;
        private Map<String, Long> stageLatencyMs = new LinkedHashMap<>();
        private FailureClass failureClass;
        private Instant timestamp;
        private long seed;

        private Builder(String scenarioId, String variantId,
                        String scenarioHash, EvaluationProfile evaluationProfile) {
            this.scenarioId = scenarioId;
            this.variantId = variantId;
            this.scenarioHash = scenarioHash;
            this.evaluationProfile = evaluationProfile;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder gitSha(String gitSha) {
            this.gitSha = gitSha;
            return this;
        }

        public Builder preState(StateSummary preState) {
            this.preState = preState;
            return this;
        }

        public Builder postState(StateSummary postState) {
            this.postState = postState;
            return this;
        }

        public Builder actualPrimaryAction(String actualPrimaryAction) {
            this.actualPrimaryAction = actualPrimaryAction;
            return this;
        }

        public Builder executionResult(String executionResult) {
            this.executionResult = executionResult;
            return this;
        }

        public Builder stateDelta(Map<String, Integer> stateDelta) {
            this.stateDelta = new LinkedHashMap<>(stateDelta);
            return this;
        }

        public Builder invariantResults(List<CheckResult> invariantResults) {
            this.invariantResults = new ArrayList<>(invariantResults);
            return this;
        }

        public Builder propertyResults(List<CheckResult> propertyResults) {
            this.propertyResults = new ArrayList<>(propertyResults);
            return this;
        }

        public Builder violations(List<Violation> violations) {
            this.violations = new ArrayList<>(violations);
            return this;
        }

        public Builder callBudget(CallBudgetTracker tracker) {
            this.productionModelCalls = tracker.productionModelCalls();
            this.providerRetries = tracker.providerRetries();
            this.judgeModelCalls = tracker.judgeModelCalls();
            this.capabilityCalls = tracker.capabilityCalls();
            return this;
        }

        public Builder tokens(Long inputTokens, Long outputTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            return this;
        }

        public Builder cost(String cost) {
            this.cost = cost;
            return this;
        }

        public Builder latencyMs(Long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder stageLatencyMs(Map<String, Long> stageLatencyMs) {
            this.stageLatencyMs = new LinkedHashMap<>(stageLatencyMs);
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder contextSnapshot(String version, String hash) {
            this.contextSnapshotVersion = version;
            this.contextSnapshotHash = hash;
            return this;
        }

        public Builder brainContractDigest(String brainContractDigest) {
            this.brainContractDigest = brainContractDigest;
            return this;
        }

        public Builder manifestDigests(String capabilityManifestDigest, String resourceManifestDigest) {
            this.capabilityManifestDigest = capabilityManifestDigest;
            this.resourceManifestDigest = resourceManifestDigest;
            return this;
        }

        public ObservationEnvelope build() {
            return new ObservationEnvelope(this);
        }
    }
}

# R4 Sampling Configuration Freeze

## 0. Provenance

- Created: 2026-09-06
- Parent lineage: R3 SEMANTIC_PLANNING_DIAGNOSTIC_R3 (fdd9442)
- Probe tool: tools/probes/sampling_probe.py
- Probe results: tools/probes/sampling_probe_results.json

## 1. Provider Capability Probe

### Setup

- Provider: OpenCode Zen
- Endpoint: https://opencode.ai/zen/v1
- Model: mimo-v2.5-free
- User-Agent: opencode/1.18.21
- Transport: DIRECT (explicit empty ProxyHandler)
- Probe input: minimal synthetic (NOT a benchmark case)
- Probe count: 4 requests with different parameter combinations

### Results

| Probe | Parameters | HTTP Status | Accepted | Notes |
|-------|-----------|-------------|----------|-------|
| temp0_top1 | temperature=0, top_p=1 | 200 | Yes | No parameter rejection |
| temp0_top1_seed42 | temperature=0, top_p=1, seed=42 | 200 | Yes | Seed accepted without error |
| temp1_default | temperature=1 | 200 | Yes | No parameter rejection |
| temp0_only | temperature=0 | 200 | Yes | No parameter rejection |

### Key Findings

1. **All parameters accepted**: The provider does not reject temperature, top_p,
   or seed parameters. HTTP 200 returned for all probes.

2. **No observable parameter rejection**: No error messages, warnings, or
   response header indicators suggest parameters are being ignored.

3. **Cannot verify enforcement from single probes**: A single probe per
   configuration cannot determine whether the provider actually enforces
   temperature=0 (vs silently ignoring it). Full enforcement verification
   would require repeated identical requests comparing output variance,
   which is NOT done here (out of scope for this probe; would require
   multiple repetitions on the same input).

4. **Output format inconsistency**: The probe used a minimal system prompt
   (not the full R3 prompt), and the model produced inconsistent JSON
   structures across all 4 probes. This is expected with a simplified prompt
   and does NOT indicate a provider issue. The full R3/R4 prompt with
   planning-state.v1 schema constraint produces consistent schema-valid output.

5. **No system fingerprint in response**: The provider response does not
   include a system_fingerprint field, which means we cannot use it to
   detect backend configuration changes between runs.

## 2. R4 Sampling Profile

Based on the probe results, the R4 sampling profile is:

```
temperature = 0
top_p = 1
seed = unsupported (accepted but enforcement unverifiable)
```

### Justification

- **temperature=0**: Accepted by provider. This is the standard setting for
  maximum determinism. Even if the provider does not strictly enforce greedy
  decoding, setting temperature=0 signals intent for minimal randomness and
  is the lowest-risk configuration.

- **top_p=1**: At temperature=0, top_p has minimal practical effect (greedy
  decoding selects the single highest-probability token regardless of top_p).
  Setting top_p=1 is the neutral default.

- **seed=unsupported**: The provider accepts the seed parameter without error,
  but:
  (a) There is no system_fingerprint in the response to verify reproducibility.
  (b) We cannot verify from 4 single-shot probes whether the provider
      actually uses the seed for deterministic sampling.
  (c) The provider documentation does not guarantee seed-based determinism.
  Therefore, we record seed as "accepted but unverifiable" and do NOT claim
  deterministic reproducibility based on seed.

### Stability Expectations

With temperature=0:
- Flag-level stochastic instability should be reduced vs temperature=1
- Systematic bias (eg E10 d=true, E17 e=false) will NOT be affected
  by temperature — these are definition/semantic issues, not noise
- E07-unresolved, which was 36/36 stable in R3 but scattered in the
  expectation audit re-probe, should return to higher stability

### What This Does NOT Fix

- Flag definition ambiguity (E10, E17) — requires R4 semantic redesign
- Model systematic bias — temperature=0 reduces noise, not bias
- Evidence vocabulary gaps (observation:, event:) — requires schema update
- WAIT structural limitation — requires runtime/planning-state extension

## 3. R3 vs R4 Sampling Comparison

| Parameter | R3 | R4 |
|-----------|----|----|
| temperature | not set (provider default unknown) | 0 |
| top_p | not set (provider default unknown) | 1 |
| seed | not set | accepted, enforcement unverifiable |
| max_tokens | 800 | 800 (unchanged) |
| response_format | json_object | json_object (unchanged) |
| stream | false | false (unchanged) |
| transport | DIRECT | DIRECT (unchanged) |
| endpoint | https://opencode.ai/zen/v1 | unchanged |
| model | mimo-v2.5-free | unchanged |
| UA | opencode/1.18.21 | unchanged |

## 4. Manifest Declaration

The R4 diagnostic manifest MUST include:

```json
{
  "sampling_profile": {
    "temperature": 0,
    "top_p": 1,
    "seed": "unsupported",
    "seed_accepted": true,
    "seed_enforcement_verifiable": false,
    "max_tokens": 800,
    "response_format": "json_object",
    "stream": false
  }
}
```

## 5. Freeze Declaration

This sampling profile is FROZEN for the R4 diagnostic lineage.
Any change to temperature, top_p, seed, max_tokens, response_format,
or stream constitutes a new lineage and requires re-registration.


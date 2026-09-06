# R4 Diagnostic Design

## 0. Provenance

- Created: 2026-09-06
- Parent lineage: R3 SEMANTIC_PLANNING_DIAGNOSTIC_R3 (fdd9442)
- R4 is a NEW legal lineage
- Architecture: see SEMANTIC_PLANNING_R4_ARCHITECTURE.md
- Benchmark lineage: see SEMANTIC_PLANNING_R4_BENCHMARK_LINEAGE.md
- Sampling freeze: see SEMANTIC_PLANNING_R4_SAMPLING_FREEZE.md

## 1. Lineage Declaration

```
experiment: SEMANTIC_PLANNING_DIAGNOSTIC_R4
parent: R3 SEMANTIC_PLANNING_DIAGNOSTIC_R3
parent_verdict: DIAGNOSTIC_REJECTED
lineage_type: SEMANTIC_DEFINITION_CHANGE
prompt_diff_class: SEMANTIC_REDESIGN (not OUTPUT_SCHEMA_CLARIFICATION)
schema_version: planning-state.v2
mapping_version: planning-mapping.v2
```

R4 is NOT a clarification of R3. It is a semantic redesign. The prompt hash,
schema, reason vocabulary, evidence vocabulary, and mapping all change.
R3 artifacts are frozen and unmodified.

## 2. Benchmark Version

### Oracle Version

```
oracle: backend/build/ranking-shadow/oracle-v2.json
parent_oracle: backend/build/ranking-shadow/oracle.json (R3 frozen)
correction: E17 unconfirmed + unconfirmed-decoy
  old: INVOKE_CAPABILITY
  new: REQUEST_USER_INPUT
  reason: PRODUCT_BOUNDARY_ORACLE_CORRECTION
```

### Unaffected Cases

E01, E07, E07-resolved, E10, E19, E22, E25: oracle unchanged from R3.

### Case Set

Same 90 unique cases, 270 reps as R3. No case additions or removals.
Same replay.jsonl. Same arms (B+, C).

## 3. Sampling Profile

```
temperature: 0
top_p: 1
seed: accepted, enforcement unverifiable
max_tokens: 800
response_format: json_object
stream: false
transport: DIRECT
endpoint: https://opencode.ai/zen/v1
model: mimo-v2.5-free
user_agent: opencode/1.18.21
```

Changed from R3: temperature explicitly set to 0 (R3 used provider default).
See SEMANTIC_PLANNING_R4_SAMPLING_FREEZE.md for probe evidence.

## 4. Schema Preflight

### Requirements

Before the 270-case diagnostic, R4 MUST pass schema preflight:

1. **Transport preflight**: 5/5 HTTP 200 + JSON parse + strict planning-state.v2
   schema validation (same as R3 transport preflight)

2. **Schema preflight**: 5 synthetic non-benchmark inputs exercise the
   planning-state.v2 schema with the new prompt:
   - Input 1: empty snapshot, no claims, no capabilities
   - Input 2: one unresolved claim, high-risk capability available
   - Input 3: confirmed claims, no blockers, no capabilities
   - Input 4: args grounded, auth confirmed, must execute now
   - Input 5: new durable knowledge present

   Each must produce a valid planning-state.v2 JSON that passes strict
   schema validation, including:
   - Correct version field
   - Valid goalType enum
   - Valid gapType when u=true
   - Valid capabilityAssessment when e=true
   - All reason codes in allowed sets
   - All evidence refs in allowed prefixes
   - Mutual exclusion invariants (u/d, u/e)

3. **Calibration preflight**: 6 targeted calibration cases (see Section 5)
   with known expected behavior. Must achieve >= 5/6 correct mapping.

### Preflight Abort Conditions

- Transport preflight < 5/5: abort, do not proceed
- Schema preflight < 5/5: abort, fix prompt or schema
- Calibration preflight < 5/6: abort, investigate semantic definition

## 5. Calibration Set

The calibration set is used for preflight validation. It is NOT part of
the formal 270-case diagnostic. Calibration cases must NOT leak benchmark
identities.

### Calibration Cases

| ID | Description | Expected Flag Pattern | Expected Mapping |
|----|------------|----------------------|-----------------|
| CAL-1 | Answer submitted, zero claims, no capabilities | u=true, e=false, d=false, n=false | REQUEST |
| CAL-2 | Unresolved claim, high-risk capability, no args | u=true, e=false, d=false, n=false | REQUEST |
| CAL-3 | Confirmed claims, no blockers, no caps needed | u=false, e=false, d=true, n=false | RESPOND |
| CAL-4 | Args grounded, auth confirmed, must execute now | u=false, e=true, d=false, n=false | INVOKE |
| CAL-5 | New genuinely novel durable fact | u=false, e=false, d=false, n=true | CREATE |
| CAL-6 | Unresolved conflict, no grounded answer | u=true, e=false, d=false, n=false | REQUEST |

### Calibration Case Construction

Each calibration case is a synthetic snapshot with:
- A deterministic snapshotId (not from benchmark)
- Controlled claim states (status, confidence, text)
- Controlled capability availability
- Controlled authorization records
- Clear expected flag pattern based on R4 definitions

Calibration cases are NOT benchmark replays. They are constructed to
exercise specific semantic definition boundaries.

## 6. Formal Run Design

### Run Parameters

```
unique_cases: 90
repetitions: 3
total_reps: 270
concurrency: 1
pacing: 10 seconds between cases
circuit_breaker: 3 consecutive PROVIDER_FAILURE
```

### Harness

The R4 harness is a new script (tools/semantic_planning_diagnostic_r4.py)
that:
1. Loads oracle-v2.json (corrected benchmark)
2. Loads replay.jsonl (same case set as R3)
3. Uses the R4 system prompt (planning-state.v2)
4. Validates against planning-state.v2 schema
5. Uses planning-mapping.v2 for outcome derivation
6. Applies R4 gates
7. Records full provenance in manifest

### What Changes vs R3 Harness

| Component | R3 | R4 |
|-----------|----|----|
| Schema | planning-state.v1 (4 flat flags) | planning-state.v2 (goalType + 4 flags + capabilityAssessment) |
| Prompt | R1 baseline + envelope clarification | Full R4 semantic redesign |
| Oracle | oracle.json | oracle-v2.json (E17 corrected) |
| Mapping | planning-mapping.v1 | planning-mapping.v2 |
| Sampling | provider default | temperature=0, top_p=1 |
| Validation | validate_planning_state | validate_planning_state_v2 |
| Evidence prefixes | 7 | 9 |

### What Does NOT Change

- Same endpoint, model, UA, transport
- Same case set (90 unique, 270 reps)
- Same replay.jsonl
- Same arms (B+, C)
- Same transport preflight logic
- Same circuit breaker logic
- Same pacing
- Same max_tokens (800)
- Same response_format (json_object)

## 7. Gates

### R4 Gate Definitions

| Gate | Description | Threshold |
|------|-------------|-----------|
| G1: schema_preflight | Transport + schema preflight | 5/5 |
| G2: calibration_preflight | Calibration cases correct | >= 5/6 |
| G3: e10_correction | E10-like cases: d=false when claims empty | 100% (0 FP) |
| G4: e17_correction | E17-like: u=true when intent unclear | >= 2/3 per identity |
| G5: external_positive | External-positive: e=true when conditions met | >= 2/3 |
| G6: direct_positive | Direct-positive: d=true when grounded claims exist | >= 2/3 |
| G7: ineligible_winner | No outcome on ineligible family | 0 |
| G8: schema_failure | No schema validation failures in 270 reps | 0 |
| G9: stability | Flag-level flip rate | <= 20% per flag (improved from R3) |
| G10: mutual_exclusion | u/d mutual exclusion violations | 0 |
| G11: critical_regression | Critical case regressions vs R3 oracle-v2 | 0 |

### Gate Rationale

- **G1, G2**: Preflight gates ensure the system works before committing
  to270 reps
- **G3**: Directly addresses E10 failure. d=true with empty claims is the
  primary failure R4 must fix.
- **G4**: Directly addresses E17 failure (after oracle correction).
  u=true when intent is unclear is the expected behavior.
- **G5**: Verifies that e=true can actually fire when conditions are met
  (R3 had e=0/270). Uses calibration-style external-positive cases.
- **G6**: Verifies d=true can fire when grounded content exists (regression
  protection for direct-positive cases).
- **G7, G8**: Same quality gates as R3.
- **G9**: Stability gate with tighter threshold. R3 had d flip rate 38.89%,
  n flip rate 32.22%. R4 target: <=20% with temperature=0.
- **G10**: New gate enforcing u/d mutual exclusion invariant.
- **G11**: No critical regressions on cases that were correct under oracle-v2.

### Scoring

Verdict = PASS if ALL gates satisfied.
Verdict = DIAGNOSTIC_REJECTED if any gate fails.

There is no "partial pass". All gates must be satisfied.

## 8. Abort Conditions

The diagnostic run aborts if:
1. Transport preflight fails (G1)
2.3 consecutive PROVIDER_FAILURE (circuit breaker)
3. Schema preflight fails (G2 < 5/6)

## 9. Reporting Discipline

### Required Reports

Each R4 diagnostic run produces:
1. manifest.json — full provenance, all parameters frozen
2. preflight.jsonl — transport + schema preflight results
3. results.jsonl — 270 reps with full flag/reason/evidence data
4. summary.json — gate scores, verdict, flag accuracy

### What Gets Reported

- Per-flag accuracy, precision, recall, F1
- Per-flag flip rate (stability)
- Per-case outcome distribution
- E10-like d FP rate (must be 0)
- E17-like u TP rate (must be >= 2/3 per identity)
- External-positive e TP rate
- Mutual exclusion violation count
- Schema failure count
- Ineligible winner count

### What Does NOT Get Reported

- Free-form model reasoning (not in output)
- Chain-of-thought analysis
- Benchmark label leakage detection
- Model confidence scores

## 10. Implementation Prerequisites

Before R4 implementation can begin, the following must be completed:

1. [x] E17 oracle correction registered (Phase A)
2. [x] Sampling profile frozen (Phase B)
3. [x] R4 architecture designed (Phase C)
4. [ ] planning-state.v2 schema implemented (contracts/planning_v2.py)
5. [ ] R4 system prompt written
6. [ ] R4 reason vocabulary finalized
7. [ ] R4 evidence vocabulary finalized (add observation:, event:)
8. [ ] planning-mapping.v2 implemented
9. [ ] Calibration cases constructed (6 cases)
10. [ ] R4 diagnostic harness written (tools/semantic_planning_diagnostic_r4.py)
11. [ ] Schema preflight passed
12. [ ] Calibration preflight passed

Items 4-12 are implementation tasks. This design document covers
items 1-3 and specifies the requirements for items 4-12.

## 11. R4 Prompt Design Requirements

The R4 system prompt MUST:

1. Define goalType inference rules (Section 5 of Architecture doc)
2. Define each flag with the R4 definitions (not R3 definitions)
3. Include the mutual exclusion invariants as explicit constraints
4. Include capabilityAssessment schema when e=true
5. Include the "answer submitted != understood != goal satisfied" invariant
6. Include the "existing content is NOT new" rule for n
7. Specify the new evidence vocabulary (9 prefixes)
8. Include a planning-state.v2 JSON example with STRUCTURE ONLY disclaimer
9. NOT reference benchmark labels, expected actions, or scenario specifics
10. Use bounded enums only, no free-text reasoning fields

The prompt must be self-contained: a model reading only the prompt and
the input must be able to produce a valid planning-state.v2 response.

## 12. Lineage Separation

R4 is explicitly separated from R3:

| Aspect | R3 | R4 |
|--------|----|----|
| Experiment name | SEMANTIC_PLANNING_DIAGNOSTIC_R3 | SEMANTIC_PLANNING_DIAGNOSTIC_R4 |
| Schema | planning-state.v1 | planning-state.v2 |
| Prompt hash | d76a7628 | new hash (TBD at implementation) |
| Oracle | oracle.json | oracle-v2.json |
| Reason vocab | R3 codes | R4 codes (expanded) |
| Evidence vocab | 7 prefixes | 9 prefixes |
| Mapping | planning-mapping.v1 | planning-mapping.v2 |
| Sampling | default | temperature=0 |
| Verdict | DIAGNOSTIC_REJECTED | TBD |

R3 verdict is NOT changed. R3 artifacts are NOT modified.
R4 starts from a clean slate with corrected oracle and redesigned semantics.


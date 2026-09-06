# R4 Diagnostic Design

## 0. Provenance

- Created: 2026-09-06
- Correction pass: 2026-09-06 (implementation-front gate/evidence correction, see SEMANTIC_PLANNING_R4_ARCHITECTURE_REVIEW_RESOLUTION.md)
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

## 4. Error Taxonomy (C1 / C2 / C3)

Every model rep falls into exactly one class, assigned in order:

- C1 raw contract violation: unparsable JSON, wrong envelope shape, unknown
enum value, unresolving evidence ref, duplicate reason codes. The validator
rejects BEFORE any semantic reading. Outcome: CONTRACT_VIOLATION_C1.
- C2 deterministic cross-check violation: schema-valid output contradicting
machine-verifiable facts (Architecture Section 11, checks 1-8): goalType
mismatch vs the G1-G5 reference, u/d or u/e or u/n co-activation, e=true
without a bound capabilityId, riskLevel mismatch vs the descriptor,
REQUIRED_NOW missing with e=true, uncited CONFIRMED authorization, d=true
without a cited confirmed claim. Outcome: CONTRACT_VIOLATION_C2.
- C3 derived semantic outcome: C1/C2-clean output passed through
planning-mapping.v2 to REQUEST / INVOKE / RESPOND / CREATE / NO_WINNER.
Only C3 reps enter semantic-accuracy denominators.

Counting rules (hard):
- A C1 or C2 rep NEVER counts as a semantic pass, even if a "would-be"
mapping looks right. Validator interception is a harness rejection, not
evidence the model "meant well".
- Semantic gates (G4-G7, G12) score C3 reps only; their denominators are
C3 reps (stated per gate).
- Quality gates (G8-G10) score C1/C2 rates over all 270 formal reps.
- Stability (G11) uses identities with 3 C1/C2-clean reps (definition below).

## 5. Preflight (gates G1-G3; must pass before the formal 270)

### G1: transport preflight — 5/5 required

Same shape as R3: 5 synthetic non-benchmark requests, each must return
HTTP 200 + parseable JSON + planning-state.v2 schema-valid output.
Fail -> ABORT (main phase never starts).

### G2: schema preflight — 5/5 required

5 synthetic non-benchmark inputs exercising the v2 envelope corners:
- S1: empty snapshot, no claims, no capabilities (goalType path G1).
- S2: one unresolved claim + high-risk capability, ADVISOR (path G2).
- S3: confirmed claims, no blockers, no capabilities (path G4).
- S4: capabilityResults with an approval record + grounding confirmed
claims + no unresolved items (path G3; exercises capabilityAssessment).
- S5: genuinely novel durable fact with u=e=d=false (exercises n=true).

Each must validate C1/C2-clean (correct goalType, consistent assessment).
Fail -> ABORT (fix prompt or schema; do not proceed).

### G3: calibration preflight — 8 cases x 3 reps = 24 calls

Per-case pass: >= 2/3 reps produce the expected C3 mapping (C1/C2 reps
count as failures of the case). Gate pass: >= 7/8 cases pass.
Fail -> ABORT (investigate semantic definition; do not proceed).

## 6. Calibration Set (final, 8 cases)

Synthetic snapshots only. No benchmark identities, texts, or UUIDs leak.
Authorization records live in snapshot.capabilityResults[] (provenance /
content approval entries) and grounding content in confirmed claims:
real wire positions, no observation: usage anywhere.

| ID | Shape | Reference goalType | Expected C3 mapping |
|----|-------|-------------------|---------------------|
| CAL-U1 (E10-like) | empty claims, no caps | UNDERSTAND (G1) | REQUEST (u=true) |
| CAL-U2 (E17-like) | unresolved claim + irreversible cap, no args/auth, ADVISOR | RESOLVE (G2) | REQUEST (u=true, e=false) |
| CAL-U3 (conflict-like) | two conflicting unresolved claims | RESOLVE (G2) | REQUEST (u=true) |
| CAL-E1 (external high) | confirmed arg-grounding claims + approval record in capabilityResults + irreversible cap + must-execute-now | GATHER (G3) | INVOKE (e=true, full assessment) |
| CAL-E2 (external read-only) | READ_ONLY cap, no args/auth needed, asking cannot help, read REQUIRED_NOW | RESOLVE or UNDERSTAND | INVOKE (e=true, READ_ONLY row) |
| CAL-D1 (direct-positive) | confirmed claims, U empty, R empty | PRODUCE (G4) | RESPOND (d=true) |
| CAL-N1 (durable-positive) | genuinely novel fact, u=e=d=false | UNDERSTAND or PRODUCE | CREATE (n=true alone) |
| CAL-N2 (rephrase-negative) | patch restating existing claim only | per rules | n=false (gate on the flag, any C3 mapping accepted except CREATE) |

Gate sourcing (explicit, no blurring):
- G6 external-positive is scored on CAL-E1 (>= 2/3) AND CAL-E2 (>= 2/3).
- G7 direct-positive is scored on CAL-D1 (>= 2/3).
- G4/G5 (E10-like, E17-like) are scored on the FORMAL run identities below,
NOT on calibration: calibration proves the definitions CAN fire; the formal
run proves they fire on real frozen inputs.

## 7. Formal Run Design

### Run Parameters

```
unique_cases: 90
repetitions: 3
total_reps: 270
concurrency: 1
pacing: 10 seconds between cases
circuit_breaker: 3 consecutive PROVIDER_FAILURE
```

### Harness (to be implemented; this doc specifies behavior)

tools/semantic_planning_diagnostic_r4.py MUST:
1. Load oracle-v2.json (corrected benchmark).
2. Load replay.jsonl (same case set as R3).
3. Use the R4 system prompt (planning-state.v2).
4. Validate C1 (schema) then C2 (cross-checks incl. G1-G5 reference goalType,
descriptor risk projection, citation resolution) per rep.
5. Derive C3 outcomes via planning-mapping.v2.
6. Score gates G4/G5/G8-G12 on formal reps; G6/G7 on calibration reps.
7. Record full provenance (prompt hash, sampling profile, oracle digest,
reference-derivation version) in manifest.

### What Changes vs R3 Harness

| Component | R3 | R4 |
|-----------|----|----|
| Schema | planning-state.v1 (4 flat flags) | planning-state.v2 (goalType + gapType + capabilityAssessment + 4 flags) |
| Prompt | R1 baseline + envelope clarification | Full R4 semantic redesign |
| Oracle | oracle.json | oracle-v2.json (E17 corrected) |
| Mapping | planning-mapping.v1 (+AMBIGUOUS) | planning-mapping.v2 (no AMBIGUOUS; CONTRACT_VIOLATION first) |
| Sampling | provider default | temperature=0, top_p=1 |
| Validation | prefix-only evidence check | C1 schema + C2 cross-checks against actual input |
| Evidence prefixes | 7 | 8 (+event:, observation: excluded) |

### What Does NOT Change

Same endpoint, model, UA, transport, case set (90/270), replay.jsonl, arms
(B+, C), circuit breaker, pacing, max_tokens (800), response_format.

## 8. Gates (final, G1-G12)

Verdict = PASS iff ALL gates pass. Any failure -> DIAGNOSTIC_REJECTED.
No partial pass. Preflight gates (G1-G3) abort the run when failed;
formal gates (G4-G12) score a completed run.

| Gate | Data source | Definition | Threshold |
|------|-------------|-----------|-----------|
| G1 transport | 5 synth | HTTP 200 + parse + v2-valid | 5/5 (abort) |
| G2 schema | 5 synth (S1-S5) | C1/C2-clean incl. reference goalType | 5/5 (abort) |
| G3 calibration | 24 calls (8x3) | per-case >= 2/3 expected C3 | >= 7/8 cases (abort) |
| G4 E10-silence | formal, 12 E10 identities x3 = 36 C3+C1+C2 reps | reps with d=true | 0 |
| G5 E17-request | formal, 12 E17 identities x3 | identities with majority C3 == REQUEST | >= 10/12 |
| G6 external-positive | calibration CAL-E1, CAL-E2 | reps mapping INVOKE with full assessment | >= 2/3 each |
| G7 direct-positive | calibration CAL-D1 | reps mapping RESPOND with d=true | >= 2/3 |
| G8 ineligible-winner | formal 270 | outcome on non-eligible family | 0 |
| G9 raw violation C1 | formal 270 | C1 reps | 0 |
| G10 cross-check C2 | formal 270 | C2 reps / 270 | <= 5% |
| G11 stability | formal valid identities | per-flag flip (formula below) | <= 20% per flag |
| G12 critical regression | formal, frozen 28 identities (Sec. 13) | identities with majority C3 outside oracle-v2 | 0 |

Gate notes:
- G4 denominators: ALL 36 reps (C1/C2 reps cannot show d=true, so they
trivially satisfy silence; the gate counts d=true events, no denominator
gaming possible).
- G5 majority computed over C3 reps; identities with < 2 C3 reps count as
failed identities. NO_WINNER majorities count as failed (not as abstention).
- G10 5% tolerance is a harness-strictness monitor (new C2 machinery needs
slack on first firing), NOT semantic credit: C2 reps stay excluded from
G4/G5/G12 scoring either way.
- G11 formula: per flag f, flip_f = 1 - (N_same_f / N_valid), where N_valid
= identities with 3 C1/C2-clean reps, N_same_f = among them with identical
f value across all 3 reps. Computed separately for u, e, d, n. Derived-
outcome stability (identical C3 mapping across 3 clean reps) reported
separately as informational only, not gated.
- G12 members and exclusions are frozen in Section 13. Majority rule: >= 2/3
identical C3 outcomes, else no-majority = regressed (failed).
- E22 identities are excluded from every formal gate (WAIT unmappable).
- G6/G7 NEVER draw on formal reps; G4/G5/G12 NEVER draw on calibration.
Cross-sourcing in either direction is forbidden: a total score cannot mask
an independent gate (each gate passes/fails on its own threshold).

## 9. Abort Conditions (fixed numbering)

1. G1 < 5/5 -> abort before schema preflight.
2. G2 < 5/5 -> abort before calibration.
3. G3 < 7/8 cases (or any case with a C1 structural failure) -> abort before
the formal 270.
4. 3 consecutive PROVIDER_FAILURE at any phase -> abort run (INCONCLUSIVE,
not REJECTED; provider fault, not semantic fault).

(The previous draft's "Schema preflight fails (G2 < 5/6)" mixed the schema
gate with the calibration threshold; the numbering above replaces it.)

## 10. Reporting Discipline

Each R4 run produces manifest.json, preflight.jsonl (G1+G2+S-cases),
calibration.jsonl (24 reps, G3/G6/G7 evidence), results.jsonl (270 reps with
per-rep class C1/C2/C3 + reference goalType + mapping trace), summary.json
(gate table with per-gate source, numerator, denominator, threshold).

Reported: per-flag accuracy/precision/recall/F1 over C3 reps; per-flag flip
rates + N_valid; C1 count, C2 count with per-check breakdown (checks 1-8);
G4 d=true event list; G5 per-identity majorities; G6/G7 per-case tallies;
G12 per-identity majorities vs oracle-v2; derived-outcome stability
(informational). Never reported as semantic evidence: free-form reasoning,
chain-of-thought, confidence scores, benchmark-label leakage probes.

## 11. Implementation Prerequisites

1. [x] E17 oracle correction registered (benchmark lineage doc).
2. [x] Sampling profile frozen (sampling freeze doc).
3. [x] R4 architecture corrected (Architecture doc, correction pass).
4. [x] Critical-regression set frozen (this doc, Section 13).
5. [ ] planning-state.v2 schema + C1 validation implemented.
6. [ ] C2 cross-checks implemented (reference goalType G1-G5, descriptor
risk projection, citation resolution per canonical ref forms).
7. [ ] R4 system prompt written per Section 12 requirements.
8. [ ] planning-mapping.v2 implemented (CONTRACT_VIOLATION first, no
AMBIGUOUS, residual order u>e>d>n).
9. [ ] Calibration cases constructed (8 synth cases incl. CAL-E1 approval
record inside capabilityResults).
10. [ ] R4 harness written (preflight + calibration + formal + gate table).
11. [ ] G1+G2+G3 green.

## 12. R4 Prompt Design Requirements

The R4 system prompt MUST:
1. State goalType rules G1-G5 verbatim (observables only, no flag language).
2. Define each flag per the Architecture doc (R4 definitions, not R3).
3. State invariants u+d, u+e, u+n mutual exclusion and the e/d+n residual
order as mapping facts.
4. Specify capabilityAssessment with capabilityId binding, the descriptor
risk table, citation rules for CONFIRMED/GROUNDED, and REQUIRED_NOW gating.
5. State ANSWER_SUBMITTED != ANSWER_UNDERSTOOD != GOAL_SATISFIED.
6. State existing snapshot content is NOT new (n gate).
7. List the 8 evidence prefixes with canonical ref forms; forbid
observation: and free text.
8. Include a planning-state.v2 JSON example with STRUCTURE ONLY disclaimer.
9. Never reference benchmark labels, expected actions, or scenario specifics.
10. Use bounded enums only; no free-text reasoning fields.

Self-contained: prompt + input suffices for valid v2 output.

## 13. Frozen Critical-Regression Set (28 identities)

Rule (mechanical, computed 2026-09-06 from R3 frozen results.jsonl +
oracle-v2.json; script tools/probes/compute_regression_set.py): member iff
the identity's R3 majority (>= 2/3 identical derived outcomes) belongs to
its oracle-v2 expected set. 32 identities satisfy the rule; 4 E07-resolved
members are EXCLUDED below for documented structural cause. The remaining
28 are frozen: R4 G12 fails iff any listed identity yields a C3 majority
outside its oracle-v2 expected set (fewer than 2 C3 reps, or no-majority,
counts as regressed).

Included (28):
- B+ E01/base r0; B+ E01/paraphrase r2; B+ E01/shuffled r1.
- B+ E07/unresolved r0, r1, r2; B+ E07/unresolved-paraphrase r0, r1, r2.
- B+ E17/unconfirmed r0, r1; B+ E17/unconfirmed-decoy r1.
- B+ E19/large r1; B+ E25/stale-relation-set r2.
- C E01/base r0; C E01/paraphrase r2; C E01/shuffled r1.
- C E07/unresolved r0, r1, r2; C E07/unresolved-paraphrase r0, r1, r2.
- C E17/unconfirmed r0, r1; C E17/unconfirmed-decoy r1.
- C E19/large r1; C E25/stale-relation-set r2.
(All oracle-v2 expected REQUEST_USER_INPUT; R3 majorities REQUEST_USER_INPUT.)

Excluded with cause (4, NOT gated; tracked informationally):
- B+ E07-resolved/resolved r2; C E07-resolved/resolved r1, r2;
C E07-resolved/resolved-paraphrase r0.
Cause: multi-label expected set (RESPOND/WAIT/REQUEST) + WAIT unmappable =
known structural limitation. R4 changes no mapping machinery for WAIT, so
gating these would score mapping luck, not semantic quality. Revisit only
when runDisposition/completionState lands.

Also excluded by rule (never members): all E10 identities (R3 majority
outside oracle-v2: the defect R4 must fix, gated by G4 instead), all E22
identities (WAIT unmappable), and every identity whose R3 majority was
missing, AMBIGUOUS, NO_WINNER, CREATE, or off-family.

## 14. Lineage Separation

| Aspect | R3 | R4 |
|--------|----|----|
| Experiment | SEMANTIC_PLANNING_DIAGNOSTIC_R3 | SEMANTIC_PLANNING_DIAGNOSTIC_R4 |
| Schema | planning-state.v1 | planning-state.v2 |
| Prompt hash | d76a7628 | new hash (TBD at implementation) |
| Oracle | oracle.json | oracle-v2.json |
| Reason vocab | R3 codes | R4 codes (Architecture Sections 6-9) |
| Evidence vocab | 7 prefixes | 8 prefixes (+event:) |
| Mapping | planning-mapping.v1 | planning-mapping.v2 |
| Sampling | provider default | temperature=0, top_p=1 |
| Verdict | DIAGNOSTIC_REJECTED | TBD |

R3 verdict unchanged. R3 artifacts unmodified.

## 15. Correction log (this pass)

1. Calibration 6x1 calls -> 8 cases x 3 reps (24 calls), per-case >= 2/3,
gate >= 7/8 (G3).
2. E10-like / E17-like / external-positive / direct-positive made
independent gates (G4-G7) with explicit non-masking rule; G6/G7 sourced
from calibration, G4/G5 from formal identities, cross-sourcing forbidden.
3. Critical regression frozen as an explicit 28-identity list with mechanical
membership rule, 4 structural exclusions with cause, and no-majority =
regressed.
4. Flip-rate formula fixed: per-flag 1 - N_same/N_valid over 3-clean-rep
identities; derived-outcome stability informational only.
5. C1/C2/C3 taxonomy added: validator interception can never count as a
semantic pass; C2 rate gated separately (<= 5%) as harness-strictness
monitor.
6. Abort-condition numbering fixed (G1/G2/G3 + circuit breaker).
7. Prompt requirements updated to corrected architecture (G1-G5 verbatim,
capabilityId, citation rules, 8 prefixes).

# R4 Implementation Plan (execution only, no architecture debate)

- Baseline: 717adef, branch main, origin/main synced.
- Normative specs: R4 ARCHITECTURE (corrected), REVIEW_RESOLUTION, DIAGNOSTIC_DESIGN, BENCHMARK_LINEAGE, SAMPLING_FREEZE.
- Scope: diagnostic-only. No production, no formal 270, no oracle change.

## Module layout (new, diagnostic-only)

```text
tools/semantic_planning/__init__.py   package marker + version pins
tools/semantic_planning/planning_v2.py schema constants + C1 validator
tools/semantic_planning/reference_goal.py G1-G5 reference derivation
tools/semantic_planning/validation_v2.py C2 cross-checks 1-8
tools/semantic_planning/mapping_v2.py  planning-mapping.v2
tools/semantic_planning/prompt_v2.py   R4 system prompt + vocab/schema hashes
tools/semantic_planning/calibration.py 8 synthetic calibration cases
tools/semantic_planning_diagnostic_r4.py harness (preflight G1-G3 this round)
tools/tests/conftest.py                sys.path for tools package
tools/tests/test_r4_<module>.py        TDD tests per Phase 7 categories
```

Pure stdlib (no pydantic): exact C1 semantics, zero production coupling.
Tests run with system pytest: python -m pytest tools/tests.

## TDD order

1. planning_v2 (C1) -> 2. reference_goal (G1-G5) -> 3. validation_v2 (C2) ->
4. mapping_v2 -> 5. prompt_v2 (+hashes) -> 6. calibration fixtures ->
7. harness unit behavior -> 8. live G1/G2/G3.

Each unit: failing test -> run FAIL -> minimal impl -> run PASS ->
regression run before next unit.

## Key implementation pins (from corrected specs)

- goalType enum (5): UNDERSTAND_USER_INTENT, RESOLVE_USER_CHOICE,
PRODUCE_DIRECT_RESPONSE, GATHER_EXTERNAL_EVIDENCE, WAIT_FOR_RUNTIME_DEPENDENCY.
- gapType: intent_gap, choice_gap, confirmation_gap, authorization_gap,
argument_gap; u=false requires gapType null.
- Reason polarity enforced at C1 (true-code on false flag = C1).
- Canonical refs incl. claim:effective/<i>, claim:patch/<patchId>/<i>,
capability:<id>, event:<field>; observation: forbidden (C1 unknown prefix).
- C2 checks 1-8 per Architecture Section 11; threshold rows incl.
LOCAL_DURABLE + ADVISOR escape; CONFIRMED via path A (cited confirmed
claim) or path B (results entry + cited capability ref).
- Mapping: CONTRACT_VIOLATION first, WAIT structural, u>e>d>n residual,
no AMBIGUOUS; pre-eligibility winner recorded for G8.
- Calibration: uuid5 ids, [CAL:...] texts, wire-legal positions only.
- Harness pipeline: extract content -> parse -> C1 -> C2 -> C3; per-rep
class in {C1, C2, C3, PROVIDER_FAILURE}; manifest provenance per spec;
no credentials logged.
- This round: G1/G2/G3 live only. Formal 270 forbidden.

## Commits

1. R4 v2 contract + validator (planning_v2, reference_goal, validation_v2 + tests)
2. R4 prompt + mapping (+ tests)
3. R4 calibration + harness (+ tests)
4. R4 preflight report (docs) after live G1-G3.

Then push main.

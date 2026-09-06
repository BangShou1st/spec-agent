# R4 Preflight Report (G1-G3 round, live provider)

- Date: 2026-09-06
- Code HEAD at run time: bc1b9dd (manifest_head in both runs)
- Prompt hash: db19e5dff90860841febcdea67e55ee727d7d278ac0e8174f4c3e5dcf66927c8
- Schema hash: 64ff947e2dcbf095d4dec36844b54b7e04e2f531142c7b894e75a64d7eb1d873
- Reason vocab hash: ef02759db9f25f3fac647b2ca8b30b15a9bb776a3ac5a9dfc5f9bc9d9321178f
- Evidence vocab hash: a9ff23969987cd05553b0ce56aba800970bb42f82067e4efcdf950d09e172645
- Mapping: planning-mapping.v2 / 3effe6b3ed1e4c4fdfb279b1197a1f0835ba5b222a4770902bf31d112b1fc62a
- Reference goal: r4-goalref.v1 / 549879a54512748ddd9e76e36d113ea40214f2b39682a3433f1ba8e7b833314e
- Sampling: temperature=0, top_p=1, seed accepted-enforcement-unverifiable,
max_tokens=800, json_object, stream false, DIRECT, opencode/1.18.21,
mimo-v2.5-free. Matches frozen profile exactly.
- Raw artifacts (gitignored, consistent with R3):
backend/build/semantic-planning-diagnostic-r4/preflight-g1/ (manifest,
preflight.jsonl, summary.json), .../preflight-g2/ (same).

## Commands and exit codes

1. python -m pytest tools/tests/ -q -> exit 0, 108 passed.
2. agent-brain suite (PYTHONPATH=src): pytest tests/test_planning.py
tests/test_contracts.py -q -> exit 0, 41 passed (production untouched).
3. python tools/semantic_planning_diagnostic_r4.py --mode transport
--out-dir backend/build/semantic-planning-diagnostic-r4/preflight-g1
-> exit 0, verdict PREFLIGHT_PASS.
4. Same harness --mode schema --out-dir .../preflight-g2 -> exit 0,
verdict PREFLIGHT_ABORT_AT_SCHEMA (harness exit 0; gate failed).

(A harness print/ensure_ascii bug was found before any live call, fixed
and covered by the 11 harness unit tests; fix commit bc1b9dd.)

## G1 transport: PASS (5/5, 5/5)

http_200 5/5, C1-clean 5/5. All 5 transport reps classified C3 with
outcome REQUEST_USER_INPUT on the empty synthetic input. Transport,
envelope extraction, and v2 schema compliance are proven live.

## G2 schema: FAIL (2/5) -> ABORT, G3 not run

| Case | Class | Outcome / violation | Reference goal |
|------|-------|---------------------|----------------|
| CAL-U1 | C3 | REQUEST_USER_INPUT | UNDERSTAND (match) |
| CAL-U2 | C2 | C2:GOAL_MISMATCH (model UNDERSTAND, ref RESOLVE) | RESOLVE |
| CAL-D1 | C1 | C1:BAD_EVIDENCE_REF:e (model cited "snapshot.capabilityResults") | PRODUCE |
| CAL-E1 | C1 | C1:BAD_EVIDENCE_REF:d (model cited "snapshot.capabilityResults/0"); e=false | GATHER |
| CAL-N1 | C3 | NO_WINNER (goal PRODUCE right, n=false) | PRODUCE |

Provider failures: 0 (10/10 calls HTTP 200). C1 count: 2. C2 count: 1.
No credentials appear in any artifact (manifest audited: no bearer/secret/
api_key substrings).

## Failure analysis (each classification verified against frozen rules)

- CAL-U2: no harness bug. Model output is semantically ideal (u=true /
e=false / d=false / n=false, would map REQUEST) but goalType UNDERSTAND
contradicts mechanical G2 (any unresolved claim -> RESOLVE). Genuine
model-vs-rule boundary disagreement on the G1/G2 line. Frozen rules were
NOT retuned for it.
- CAL-D1: no harness bug. Model cited "snapshot.capabilityResults", a
dotted-path form outside the canonical ref vocabulary (prompt lists exact
forms; observation:-style invention). C1 rejection is correct behavior.
- CAL-E1: no harness bug. Same non-canonical citation in d block, and the
model additionally judged e=false (NO_EXTERNAL_NEED) despite the approval
record, grounded claims, and must-execute setup. Semantic miss + ref-spec
non-compliance, both on the model side.
- CAL-N1: no harness bug. Goal right, mapping valid, but n=false: the model
did not recognize the novel durable constraint as persistable. Semantic
miss within a fully valid envelope.

No prompt, schema, vocabulary, mapping, oracle, or threshold was changed
in response. No tuning applied, per the implementation brief.

## Verdict and next step

G2 gate (5/5) failed -> PREFLIGHT_ABORT_AT_SCHEMA per the frozen design.
G3 (24 calibration calls) was NOT run. Formal 270 was NOT run. R3 frozen
artifacts untouched. Production untouched.

Formal R4 270 is NOT cleared. The evidence is preserved above for owner
review: the open question is whether the four divergences above warrant a
new design iteration (owned decision), not an implementer-side rule tweak.

# R4 Architecture Correction — Review Resolution

- Date: 2026-09-06
- Scope: design only. No R4 runner, no formal diagnostic, no production change.
- Direction held frozen: planning-state.v2, P0+P1+P2, E17 oracle = REQUEST_USER_INPUT, temperature=0 profile, action families unsplit, WAIT structural.
- Changed files: SEMANTIC_PLANNING_R4_ARCHITECTURE.md (Sections 5-11, 13, 15, 17, new 18), SEMANTIC_PLANNING_R4_DIAGNOSTIC_DESIGN.md (Sections 4-13, 15, new 13/15).

## Item 1 — goalType circularity and pseudo-determinism: RESOLVED

Defect: EXECUTE_AUTHORIZED_ACTION depended on "external action necessary"
while e depended on the goal; CAPTURE_DURABLE_KNOWLEDGE depended on n's own
conclusion; rules called "deterministic" while the model freely chose.

Resolution: goalType redefined as pre-flag planning phase over observables
only (event.kind, claim states, capabilityResults presence). Removed both
circular values; final enum has 5 values (Architecture Section 5).
Derivation G1-G5 is exhaustive, pairwise-disjoint, order-independent, with
an explicit fail-safe fallback (G5 -> UNDERSTAND_USER_INTENT: ask, don't
act). Determinism now lives in a harness reference function; model mismatch
is a C2 violation, never a judgment call. goalType REMAINS model-output
(runtime cannot inject it in R4); a future runtime-injected goalType would
delete the C2 goal-mismatch class.

## Item 2 — capabilityAssessment unbound: RESOLVED

Resolution: capabilityId added, REQUIRED when e=true, must equal one
availableCapabilities id (Architecture Section 8). Validator checks
membership; all capability: refs in the e block must equal
capability:<capabilityId> (no-splicing rule, C2 check 4). e=false forces
capabilityAssessment null.

## Item 3 — runtime facts vs model judgment: RESOLVED

Split (Architecture Section 8):
- riskLevel: FULLY DETERMINISTIC. Fixed projection of descriptor
readOnly/sideEffectClass (contracts/inputs.py CapabilityDescriptor);
validator recomputes. Frozen-corpus universe {NONE, LOCAL_DURABLE,
EXTERNAL_IRREVERSIBLE} verified 2026-09-06 against
backend/build/eval-live-diagnostic/20260905-183601-8b135f3f46b057b7b982e1b536dcb0fee851ddb8/results.jsonl;
EXTERNAL_REVERSIBLE reserved, unreachable.
- authorizationStatus: RECORD-CITED. CONFIRMED requires a resolving
capabilityResults or confirmed-claim citation (validator checks existence);
silence is MISSING. Unreachable as CONFIRMED on frozen corpus (results
empty, no auth claims) — stated explicitly.
- argumentCompleteness: HYBRID. Citation presence deterministic;
whether cited claims ground the args is model-judged (descriptors carry no
arg schema; argsSchema noted as future contract change).
- executionNecessity: MODEL-JUDGED, validator-gated (e=true implies
REQUIRED_NOW).
Layering kept: eligibility (runtime) != semantic need (model e/d/u/n) !=
authorization (cited records) != execution (executor-owned); Section 14.

## Item 4 — evidence/input-contract misalignment: RESOLVED

Verified facts: production AgentV2RequestEnvelope = event + snapshot only
(contracts/inputs.py; AgentInputSnapshot has no observation field);
diagnostic projection fills observation from runtime_request.observation, a
key absent from every frozen source row (observation = {} on all frozen
inputs); the audit probe's observation:authorization was synthetic-only.

Resolution: observation: REMOVED from R4 vocabulary (Architecture
Section 10, with file-level justification). event: KEPT (real in envelope
and diagnostic input). Final prefixes: 8. Canonical ref forms specified;
validator builds the allowed set from the actual input (prefix-only checking
called out as the R3 gap, given frozen allowedSourceRefs lack
claim:/capability: members). Auth/args evidence real locations enumerated
(capabilityResults[], claims, autonomy, descriptors). Bounded future
contract options (AuthorizationRecord list / argsSchema) noted as design
only; NO wire change made.

## Item 5 — externalStepRequired semantic contradiction: RESOLVED

Field keeps its name AND its literal REQUIRED_NOW meaning. Threshold table
rewritten so every true-row requires executionNecessity REQUIRED_NOW;
PREFERRED/OPTIONAL with e=true is a C2 violation (Architecture Section 8).
READ_ONLY lowers auth/arg bars (NOT_REQUIRED) but never the necessity bar.
Schema definition, threshold table, and mapping.v2 step 3 are mutually
consistent by construction. Honest corollary recorded: on frozen corpus
(ADVISOR, empty results) e=true is reachable ONLY via READ_ONLY +
REQUIRED_NOW.

## Item 6 — n vs primary action: RESOLVED as option (b)

n is ORTHOGONAL (Architecture Section 9): u+n outputs are C2 violations;
legal pairs (e,n)/(d,n) resolve by an explicitly documented residual mapping
order u>e>d>n; CREATE fires only when u=e=d=false. The first draft's
"no precedence" claim is retracted and replaced (Section 13): the order is
a mapping tie-break for residual pairs, never an adjudicator of conflicting
primaries (those never reach mapping).

## Item 7 — diagnostic gates: RESOLVED

(Diagnostic Design Sections 4-9, 13, 15.) Calibration is now 8 cases x 3
reps (24 calls), per-case >= 2/3, gate >= 7/8 (G3). E10-like (G4), E17-like
(G5), external-positive (G6), direct-positive (G7) are independent gates
with a non-masking rule; G6/G7 draw ONLY on calibration (frozen corpus
cannot supply auth records or clean direct-positive single-label
identities), G4/G5/G12 ONLY on formal identities — cross-sourcing
forbidden. Critical regression frozen as an explicit 28-identity list
(Section 13): mechanical rule (R3 majority in oracle-v2 set; computed
2026-09-06 via tools/probes/compute_regression_set.py) minus 4 E07-resolved
structural exclusions with cause; no-majority counts as regressed. Flip
rate fixed as per-flag 1 - N_same/N_valid over 3-clean-rep identities;
derived-outcome stability informational. C1/C2/C3 taxonomy (Section 4):
C1 hard-zero (G9), C2 separately gated <= 5% (G10) and excluded from all
semantic denominators — interception can never read as a pass. Abort
numbering fixed (G1/G2/G3 + circuit breaker; Section 9).

## Frozen items held

R3 history untouched, unrescoured. E17 future oracle REQUEST_USER_INPUT.
No family split. WAIT excluded from all gates. No ranking weights, no
benchmark hardcode, no family precedence tree (the u>e>d>n residual order
is a two-pair mapping tie-break, Section 13). No production behavior change
(design docs + read-only probe scripts only).

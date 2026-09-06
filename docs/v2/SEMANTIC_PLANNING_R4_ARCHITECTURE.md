# R4 Semantic Architecture Design

## 0. Provenance

- Created: 2026-09-06
- Correction pass: 2026-09-06 (implementation-front architecture correction, see SEMANTIC_PLANNING_R4_ARCHITECTURE_REVIEW_RESOLUTION.md)
- Parent lineage: R3 SEMANTIC_PLANNING_DIAGNOSTIC_R3 (fdd9442)
- Scope: P0 + P1 + P2 semantic architecture (P3 mapping redesign deferred)
- Input documents:
  - docs/v2/SEMANTIC_PLANNING_R3_FORENSIC.md
  - docs/v2/SEMANTIC_PLANNING_EXPECTATION_AUDIT.md
  - docs/v2/SEMANTIC_PLANNING_BENCHMARK_ORACLE_AUDIT.md
  - docs/v2/SEMANTIC_PLANNING_R4_DESIGN_PRECONDITIONS.md
- Frozen constraints: R3 artifacts unchanged, production unchanged
- Production contract reference: agent-brain/src/spec_agent_brain/contracts/inputs.py (AgentInputSnapshot, AgentV2RequestEnvelope), protocol.py

## 1. Problem Statement

R3 achieved clean transport (0 failures) and clean envelope (270/270 schema
valid), proving the pipeline works. But semantic behavior was rejected:

- E10: model 6/6 stable RESPOND (benchmark expects REQUEST)
  Root cause: directResponseSufficient "current step goal" has no referent;
  model interprets "answer received" as "goal satisfied"
- E17: model 3/3 REQUEST on r0/r1, e=0 across all 270 reps
  Root cause: benchmark expected INVOKE under conditions violating product
  boundary (ADVISOR + zero args + zero auth + unclear intent)
- E17 oracle corrected to REQUEST_USER_INPUT (see benchmark lineage doc)
- But E10 remains: the flag definition itself is broken

The two failures are directionally opposite:
- E10: model says "can respond" when it cannot (u undercount, d overcount)
- E17: model says "no external needed" when benchmark says it is (e undercount)

A single threshold adjustment cannot fix both simultaneously.

## 2. Design Goals

1. Give "current step goal" an explicit referent so directResponseSufficient
   and userInputRequired have a shared, unambiguous target
2. Prevent d=true when no grounded semantic content exists
3. Distinguish capability risk levels in externalStepRequired
4. Require argument completeness and authorization for irreversible actions
5. Tighten "new" in newDurableKnowledgePresent to exclude rephrasing
6. Keep evidence vocabulary production-compatible (event: yes, observation: no)
7. Enable clean deterministic mapping to action families
8. Maintain schema-strict, bounded, CoT-free output

## 3. Non-Goals

- P3 mapping redesign (precedence weights, ranking)
- WAIT runtime architecture (deferred to future iteration)
- Model behavior modification (we fix definitions, not the model)
- Production code changes (R4 is diagnostic-only)
- Action family splitting (INVOKE_CAPABILITY stays one family)

## 4. Planning-State Version Decision

### Option A: Keep four flags, add auxiliary fields

Keep the existing four boolean flags but add bounded sub-fields to
externalStepRequired for risk/args/auth context.

### Option B: Upgrade to planning-state.v2 with structured sub-objects

Replace flat flags with a structured object carrying an explicit goalType,
gapType, and capabilityAssessment alongside the four booleans.

### Decision: Option B — planning-state.v2

Rationale:
- Option A patches over a broken foundation. Adding sub-fields to
  externalStepRequired while leaving directResponseSufficient's "current
  step goal" undefined doesn't solve E10.
- Option B gives us a place to define goal explicitly, which is the
  prerequisite for fixing direct/user relationships.
- The schema is still strictly bounded (enums, not free text).
- A small model can classify into enums as well as booleans.
- We avoid chain-of-thought: all fields are bounded choices.

### What planning-state.v2 is NOT

- NOT a free-text reasoning chain
- NOT a goal statement that the model invents
- NOT an action selector (mapping is separate)
- NOT a benchmark label predictor

## 5. Goal Representation (corrected)

### The Problem

directResponseSufficient's "current step goal" has no referent in the input.
The model is free to interpret "goal" as whatever it wants, leading to
E10-style exploitation ("goal = confirm receipt -> d=true").

### Why the first draft was circular

The first R4 draft defined goalType values EXECUTE_AUTHORIZED_ACTION
("external action is necessary AND args grounded AND auth confirmed") and
CAPTURE_DURABLE_KNOWLEDGE ("new durable knowledge exists"). Both derive the
goal FROM the flag conclusions they are supposed to ground: e depends on the
goal being EXECUTE_AUTHORIZED_ACTION, whose own rule depends on e's
preconditions; n depends on the goal being CAPTURE_DURABLE_KNOWLEDGE, whose
rule depends on n's conclusion. That is a definitional cycle, and calling the
rules "deterministic" while letting the model pick the enum is
pseudo-determinism.

### Correction principle

goalType is redefined as a bounded planning phase / semantic objective that
depends ONLY on pre-flag observables: event.kind, claim states present in the
input, and capabilityResults presence. It never depends on any flag value,
reason code, capabilityAssessment, or mapping outcome. The flag definitions
may read goalType, but goalType never reads the flags. Information flows one
way: input observables -> goalType -> flags -> mapping.

### goalType enum (final, 5 values)

```
UNDERSTAND_USER_INTENT   — no usable semantic content yet; must clarify intent
RESOLVE_USER_CHOICE      — unresolved items exist; must resolve them
PRODUCE_DIRECT_RESPONSE  — grounded content exists, nothing pending; respond
GATHER_EXTERNAL_EVIDENCE — prior external results exist; integrate them
WAIT_FOR_RUNTIME_DEPENDENCY — runtime-owned pending dependency (declared only)
```

Removed from the first draft: EXECUTE_AUTHORIZED_ACTION (circular) and
CAPTURE_DURABLE_KNOWLEDGE (reverse-causal). External need is expressed by
e + capabilityAssessment, not by the goal. Durable-knowledge presence is
expressed by n, not by the goal.

### Derivation rules (exhaustive, mutually exclusive, order-independent)

Notation: U = unresolved claims in effectiveClaims plus patch claims;
C = claims with status confirmed and confidence >= 0.5;
R = snapshot.capabilityResults (prior invocation records).

```
G1. IF effectiveClaims is empty AND every patch claim list is empty
    THEN goalType = UNDERSTAND_USER_INTENT
G2. ELSE IF U is non-empty
    THEN goalType = RESOLVE_USER_CHOICE
G3. ELSE IF R is non-empty
    THEN goalType = GATHER_EXTERNAL_EVIDENCE
G4. ELSE IF C is non-empty
    THEN goalType = PRODUCE_DIRECT_RESPONSE
G5. Fallback (none of the above: e.g. only assumed or low-confidence
    claims, no unresolved items, no confirmed content, no prior results)
    THEN goalType = UNDERSTAND_USER_INTENT
```

Disjointness: G1 covers "no claims at all". G2 covers "U non-empty".
G3/G4 cover "U empty" split on R/C presence (R non-empty takes G3;
R empty with C non-empty takes G4). G5 covers the remainder
(U empty, C empty, R empty, but some assumed/low-conf content exists).
Every input matches exactly one rule, independent of evaluation order.
WAIT_FOR_RUNTIME_DEPENDENCY has no derivation rule in R4: the R4 diagnostic
input carries no runtime pending-dependency signal, so the value is
declared for schema completeness and future extension only.

The fallback G5 is a fail-safe default, not a benchmark-specific
precedence: when the input is semantically degenerate, the safe planning
phase is to clarify intent (ask) rather than to act or to claim completion.

### Who computes goalType

goalType REMAINS a model-output field (the R4 diagnostic cannot change the
runtime to inject it). Determinism lives in the harness reference function:
the R4 harness implements G1-G5 exactly as written above over the frozen
model input and records a reference goalType per case. A model output whose
goalType differs from the reference is a C2 deterministic cross-check
violation (see Diagnostic Design, error classes): it is rejected by the
validator, excluded from semantic scoring, and never counted as a
semantic pass. The model is therefore scored on reproducing a mechanical
derivation, not on inventing a goal.

## 6. userInputRequired — R4 Design

### Current Definition (broken)

"true iff correct progress now REQUIRES new user information, choice,
confirmation, or blocker resolution"

Problem: "correct progress" has no referent. "REQUIRES" is too broad.

### R4 Definition

userInputRequired (u): true iff goalType is UNDERSTAND_USER_INTENT or
RESOLVE_USER_CHOICE, AND the missing information can only be supplied by
the user (not derivable from context, claims, or read-only evidence).

Because goalType is derived from pre-flag observables (Section 5), this
definition contains no cycle: u reads goalType, goalType never reads u.

### Gap Types

The R4 prompt MUST distinguish gap types:

| Gap Type | u value | Rationale |
|----------|---------|-----------|
| intent_gap | true | We do not know what the user wants |
| choice_gap | true | User must choose between options |
| confirmation_gap | true iff target action is not READ_ONLY (descriptor-owned) and autonomy is ADVISOR; else false | Read-only needs no confirmation |
| authorization_gap | true | Non-read-only action needs user authorization |
| argument_gap | true iff the missing argument is user-held (not in claims, results, or context) | Derivable args must not trigger u |
| content_gap | false | More richness wanted, but response still possible; this is d's domain |

### Key Rules

1. "answer submitted" != "user input sufficient"
   An answer existing in the snapshot does NOT mean we understand it.
   If effectiveClaims is empty (goalType UNDERSTAND_USER_INTENT via G1),
   u=true (intent_gap).

2. "capability available" != "user input not required"
   If a non-read-only capability is available but args/auth are missing,
   u=true (authorization_gap or argument_gap).

3. "confirmed claim exists" does NOT automatically mean u=false
   If goalType is RESOLVE_USER_CHOICE (unresolved items remain), u stays
   true regardless of confirmed content elsewhere.

4. u and d relationship:
   - u=true IMPLIES d=false (if we need user info, we cannot respond)
   - d=true IMPLIES u=false (if we can respond, we do not need user info)
   - u=false AND d=false is valid (we need an external step, not user)
   - u=false AND d=false AND e=false AND n=false: NO_WINNER (no clear path)

### Reason Codes

```
True codes:
  INTENT_GAP        — Cannot determine what user wants
  CHOICE_GAP        — User must choose between options
  CONFIRMATION_GAP  — Non-read-only action needs user confirmation
  AUTHORIZATION_GAP — Action requires user authorization
  ARGUMENT_GAP      — Required argument can only come from user

False code:
  USER_INPUT_SUFFICIENT — Current goal can proceed without new user info
```

### Evidence Requirements

When u=true:
- evidenceRefs MUST cite the specific gap (e.g. claim:effective/0 for an
  unresolved claim, capability:<id> for a missing-auth capability).
- Citing "the answer exists" is NOT valid evidence for u=false when
effectiveClaims is empty.
## 7. directResponseSufficient — R4 Design (P0 critical)

### Current Definition (broken)

"true iff the current step goal can be completed right now with existing
information"

Problem: "current step goal" undefined. "completed" = "received" or "understood"?

### R4 Definition

directResponseSufficient (d): true iff goalType is PRODUCE_DIRECT_RESPONSE
AND there exists at least one claim with status confirmed and confidence
>= 0.5 that can serve as the basis for a substantive response.

Because goalType PRODUCE_DIRECT_RESPONSE is derivable only when U is empty
and C is non-empty (rules G4), d inherits a mechanical precondition and the
model cannot reach d=true on E10-like inputs without contradicting the
reference goalType (a C2 violation).

### The E10 Invariant

```
ANSWER_SUBMITTED != ANSWER_UNDERSTOOD != GOAL_SATISFIED
```

- ANSWER_SUBMITTED: the system received a message (event fact)
- ANSWER_UNDERSTOOD: the system has confirmed claims about the answer's
  meaning (semantic fact)
- GOAL_SATISFIED: the current step goal is achieved (planning fact)

d=true requires ANSWER_UNDERSTOOD, not merely ANSWER_SUBMITTED.

### Explicit Rules

d=true REQUIRES ALL of:
1. goalType is PRODUCE_DIRECT_RESPONSE (reference-derivable per Section 5)
2. At least one claim with status confirmed AND confidence >= 0.5
3. No unresolved claims
4. u=false (mutual exclusion: if user input needed, cannot respond)

d=false if ANY of:
1. effectiveClaims is empty (nothing to base response on)
2. Only claims with status assumed/unresolved, or confidence < 0.5
3. Unresolved claims exist
4. goalType is not PRODUCE_DIRECT_RESPONSE

### GOAL_ACHIEVED Usage

The true code GOAL_ACHIEVED (renamed from R3 GOAL_SATISFIED) may ONLY be
used when goalType is PRODUCE_DIRECT_RESPONSE AND confirmed claims
directly addressing the goal are cited. Using it when effectiveClaims is
empty is a contract violation.

### Mutual Exclusion with u

```
u=true => d=false  (hard invariant)
d=true => u=false  (hard invariant)
```

A model output with u=true AND d=true is a C2 deterministic cross-check
violation (see Diagnostic Design), not a mapping input.

### Reason Codes

```
True codes:
  GROUNDED_RESPONSE_AVAILABLE — Confirmed claims support a response
  GOAL_ACHIEVED               — Current goal already accomplished

False codes:
  NO_GROUNDED_CONTENT         — No confirmed claims to base response on
  AWAITING_USER_INPUT         — Cannot respond until user provides info
  AWAITING_EXTERNAL_RESULT    — Cannot respond until external step completes
```

Note: R3 GOAL_SATISFIED is renamed to GOAL_ACHIEVED and constrained.
R3 NOTHING_NEW_TO_ASK is removed (too vague, replaced by
GROUNDED_RESPONSE_AVAILABLE).

## 8. externalStepRequired — R4 Design (P0, corrected)

### Current Definition (broken)

"true iff the goal REQUIRES executing an external capability or tool step now"

Problem: does not distinguish read-only from irreversible; no arg/auth
requirements; no binding to a specific capability.

### R4 Definition

externalStepRequired (e): true iff the next step genuinely requires executing
one specific available capability now, AND that capability's risk, argument,
authorization, and necessity conditions are all satisfied as defined below.

"Requires now" is literal: PREFERRED or OPTIONAL necessity never yields
e=true (see necessity rule). The field name keeps its REQUIRED_NOW meaning;
schema definition, threshold table, and mapping.v2 are aligned on this point.

### capabilityAssessment (bound to one capability)

The R4 planning-state MUST include a capabilityAssessment sub-object when
e=true, and it MUST name the capability it assesses:

```json
{
  "capabilityId": "<descriptor id from snapshot.availableCapabilities>",
  "riskLevel": "READ_ONLY | LOCAL_DURABLE | EXTERNAL_IRREVERSIBLE",
  "argumentCompleteness": "GROUNDED | PARTIAL | MISSING | NOT_REQUIRED",
  "authorizationStatus": "CONFIRMED | PENDING | MISSING | NOT_REQUIRED",
  "executionNecessity": "REQUIRED_NOW | PREFERRED | OPTIONAL"
}
```

Rules:
- capabilityId is REQUIRED when e=true and MUST equal the id of exactly one
descriptor in snapshot.availableCapabilities. Unknown ids are C2 violations.
- Every capability: ref inside e's evidenceRefs MUST equal
capability:<capabilityId>. Citing a different capability anywhere in the
externalStepRequired block is cross-capability splicing and is a C2 violation.
- When e=false, capabilityAssessment MUST be null.

### riskLevel: deterministic from the Runtime-owned descriptor

The model does NOT guess risk. riskLevel is a mechanical projection of the
descriptor's readOnly / sideEffectClass fields (contracts/inputs.py
CapabilityDescriptor), via this fixed table:

| Descriptor fields | riskLevel |
|-------------------|-----------|
| readOnly = true (observed always with sideEffectClass NONE) | READ_ONLY |
| readOnly = false, sideEffectClass = LOCAL_DURABLE | LOCAL_DURABLE |
| readOnly = false, sideEffectClass = EXTERNAL_IRREVERSIBLE | EXTERNAL_IRREVERSIBLE |
| sideEffectClass = EXTERNAL_REVERSIBLE | reserved: defined for forward compatibility, unobserved in the frozen corpus and unreachable there |

The validator recomputes riskLevel from the named descriptor and rejects
mismatches as C2 violations. Observed frozen-corpus universe is
{NONE, LOCAL_DURABLE, EXTERNAL_IRREVERSIBLE}; EXTERNAL_REVERSIBLE is a
reserved mapping row, not an observed class.

### authorizationStatus: runtime/policy evidence only, never inferred

authorizationStatus CONFIRMED is permitted ONLY when the model cites at least
one of:
- (a) a snapshot.capabilityResults entry whose provenance/content records
  user approval or a completed authorized invocation for capabilityId; or
- (b) a confirmed claim (confidence >= 0.5) whose text records the user's
explicit authorization for capabilityId.

The validator checks citation presence and target existence; absent or
non-resolving citations with CONFIRMED is a C2 violation. Silence is never
authorization: no record means MISSING (or NOT_REQUIRED for READ_ONLY).
Corpus fact: on all frozen R3 inputs capabilityResults is empty and no
authorization-recording confirmed claim exists, so CONFIRMED is unreachable
there by construction. Calibration case CAL-E1 (synthetic) supplies an
authorization record to exercise the CONFIRMED path.

### argumentCompleteness: hybrid (deterministic citation check, semantic grounding)

Capability descriptors carry no argument schema, so full mechanical
validation is impossible in R4 (noted as a future contract change in
Section 10). Split:
- Deterministic part (validator-enforced): GROUNDED requires at least one
cited claim: ref that resolves in the input; MISSING with cited grounding
refs is a C2 violation.
- Model-judged part: whether the cited claims actually supply the
capability's required arguments. The model judges; the harness cannot
re-derive. This is the one genuinely semantic sub-field of the assessment.

### executionNecessity: model-judged, validator-gated

executionNecessity (REQUIRED_NOW / PREFERRED / OPTIONAL) is the model's
semantic judgment of "if we do not execute, can the current goal still
advance?" Validator rule: e=true IMPLIES executionNecessity is REQUIRED_NOW.
PREFERRED or OPTIONAL with e=true is a C2 violation. READ_ONLY lowers the
authorization and side-effect bars but never the necessity bar: a merely
useful read is PREFERRED, hence e=false.

### Threshold table (final; aligned with schema and mapping)

| riskLevel | argumentCompleteness | authorizationStatus | executionNecessity | e |
|-----------|----------------------|---------------------|--------------------|---|
| READ_ONLY | GROUNDED or NOT_REQUIRED | NOT_REQUIRED | REQUIRED_NOW | true |
| READ_ONLY | anything else | anything else | anything else | false |
| LOCAL_DURABLE | GROUNDED | CONFIRMED, or NOT_REQUIRED only when autonomy mode is not ADVISOR | REQUIRED_NOW | true |
| LOCAL_DURABLE | anything else | anything else | anything else | false |
| EXTERNAL_IRREVERSIBLE | GROUNDED | CONFIRMED | REQUIRED_NOW | true |
| EXTERNAL_IRREVERSIBLE | anything else | anything else | anything else | false |

On the frozen corpus autonomy is always ADVISOR, so the NOT_REQUIRED escape
for LOCAL_DURABLE never applies there: frozen e=true is reachable ONLY via
the READ_ONLY + REQUIRED_NOW row. That is an honest, falsifiable prediction
of this design, not a tuning choice.

### The "No Safer Path" Rule

e=true ONLY if no safer alternative path exists:
- If REQUEST_USER_INPUT could resolve the gap, e=false.
- u=true IMPLIES e=false (hard invariant; user interaction comes first).
- e=true means: user info is complete, args are grounded, auth is confirmed
(or not required for READ_ONLY), and ONLY the external execution remains.

### Reason Codes

```
True codes:
  EXTERNAL_EVIDENCE_REQUIRED — Need external data to proceed (read-only)
  EXTERNAL_ACTION_REQUIRED   — Must execute external action to proceed
  ARGUMENTS_GROUNDED         — All args present, ready to execute

False codes:
  NO_EXTERNAL_NEED           — Goal achievable without external step
  CAPABILITY_RELEVANT_NOT_REQUIRED — Capability exists but not needed now
  INSUFFICIENT_ARGUMENTS     — Args not grounded for execution
  INSUFFICIENT_AUTHORIZATION — Auth not confirmed for execution
  SAFER_PATH_AVAILABLE       — User input or other path should come first
```

## 9. newDurableKnowledgePresent — R4 Design (P1)

### Current Definition (loose)

"true iff a new, standalone semantic unit worth persisting exists now"

Problem: "new" threshold too low. Model counts rephrasing as new.
R3: n=true 61 times, ZERO scenarios expected CREATE.

### R4 Definition

newDurableKnowledgePresent (n): true iff the input contains a semantic unit
that satisfies ALL of novelty, durability, independence, and non-redundancy
(see sub-criteria). n is an ORTHOGONAL semantic signal (decision below),
not a competing primary action candidate.

### Orthogonality decision (option b)

n is declared ORTHOGONAL: it reports "the input happens to carry persistable
content" independently of which primary need (user / external / direct)
drives the next step. Consequences, written into contract and mapping alike:
- Legal co-activations: (e,n) and (d,n) only. (u,n) with u=true means an
unresolved gap exists, so nothing is settled enough to persist: outputs
with u=true AND n=true are C2 violations.
- Mapping disambiguation for the residual legal pairs is explicit and fixed:
u > e > d > n. This is a documented mapping tie-break for residual pairs,
not a semantic ranking of importance and not a weight table: it never
selects between conflicting primary needs, because conflicting primaries
(u+d, u+e, d+e) are contract violations that never reach mapping.
- CREATE_NODE is produced ONLY when u=e=d=false AND n=true.

### Sub-Criteria

Novelty: NOT present in existing claims, answers, patches, or lineage.
Specifically NOT a rephrasing, reformatting, summary, aggregation, or
metadata annotation (timestamps, IDs, status flags).

Durability: lasting value for future planning/spec. NOT transient runtime
status, temporary execution result, or planning intermediate. Must be a
fact, decision, constraint, or requirement.

Independence: understandable and referenceable standalone; does not require
the current sentence structure; has clear semantic identity; could be a
graph node.

Non-redundancy: after normalization (lowercase, strip whitespace) distinct
from existing nodes; contradicting an existing confirmed claim is a conflict
to resolve, not new knowledge to store.

R4 prompt MUST state: existing snapshot content is NOT new. Rephrasing,
summarizing, or reformatting existing content does NOT count. n=true
requires information genuinely absent from the current knowledge state.

### Reason Codes

```
True codes:
  NOVEL_SEMANTIC_UNIT — Genuinely new information identified

False codes:
  REDUNDANT_WITH_EXISTING — Information already in claims/answers/patches
  NOT_STANDALONE          — Cannot be independently referenced
  NOT_DURABLE             — Transient or temporary information
  REPHRASING              — Restatement of existing content
```

## 10. Evidence Vocabulary — P2 (corrected)

### R3 frozen prefixes

```
node:, answer:, patch:, context:, route:, claim:, capability:
```

### The first-draft mistake

The first R4 draft added observation: on the strength of the expectation-audit
probe, which referenced observation:authorization. Verification against the
facts shows that was a diagnostic-only artifact, not a production-compatible
prefix:
- Production wire contract AgentV2RequestEnvelope carries event + snapshot
only (contracts/inputs.py). AgentInputSnapshot has NO observation field.
- The diagnostic model_input projection {eligibleFamilies, event,
observation, snapshot} fills observation from
DECISION_INPUT.runtime_request.observation, a key ABSENT from every frozen
source row, so observation is {} on all frozen benchmark inputs.
- The audit probe that "needed" observation: used a SYNTHETIC input with a
hand-placed authorization blob, never a frozen benchmark shape.
- Authorization / argument evidence on REAL inputs lives in:
snapshot.capabilityResults[] (provenance/content approval records),
effectiveClaims[] and patch claims (confirmed authorization statements,
argument-grounding content), snapshot.autonomy (ADVISOR scope),
snapshot.availableCapabilities[] descriptors (readOnly/sideEffectClass).

### R4 decision

- observation: is REMOVED. It is not production-compatible, resolves to
nothing on frozen inputs, and would let the model cite evidence that can
never exist in production.
- event: is KEPT. event (kind, anchorNodeId, freeText, ...) IS a real member
of the diagnostic model input and of the production envelope, so event refs
are production-compatible. Form: event:<field>, validated against the actual
event object.
- Final R4 prefixes (8): node:, answer:, patch:, context:, route:, claim:,
capability:, event:.

### Canonical ref forms and validator construction

Prefix-only checking (R3) is insufficient: frozen allowedSourceRefs contain
only node:/answer:/patch:/context:/route: members, while claim: and
capability: refs address effectiveClaims entries and availableCapabilities
descriptors that are present in the input but absent from allowedSourceRefs.
The R4 validator MUST build its allowed set from the actual model input
projection:

| Prefix | Canonical form | Resolves against |
|--------|---------------|------------------|
| node: | node:<uuid> | lineage node ids |
| answer: | answer:<uuid> | lineage answer ids |
| patch: | patch:<uuid> | lineage patch ids |
| context: | context:<snapshotId> | snapshot.snapshotId |
| route: | route:<routeId> | snapshot route / routeContext ids |
| claim: | claim:effective/<i>, claim:patch/<patchId>/<i> | claim entries by index |
| capability: | capability:<descriptorId> | availableCapabilities ids |
| event: | event:<field> | keys of the event object |

Free-text evidence remains forbidden. Any ref not resolving in the actual
input is a C1 schema rejection.

### Bounded diagnostic execution-context projection (design note, no wire change)

R4 diagnostic inputs are exactly the frozen shapes: {eligibleFamilies,
event, snapshot(+diagnostic observation key, always {} on frozen rows)}.
No new input field is introduced for R4. Calibration cases (synthetic) carry
authorization records inside snapshot.capabilityResults[] and grounding
content inside claims, i.e. inside ALREADY-LEGAL wire positions.

Future production integration would need a contract change to make
authorization and argument requirements first-class. Bounded options
(design only, NOT implemented):
- (i) an AuthorizationRecord list on the snapshot (approver, scope,
expiry, resolvable ref form auth:); or
- (ii) an argsSchema on CapabilityDescriptor so argumentCompleteness becomes
fully mechanically validatable.
Either option requires a protocol version bump and is out of R4 scope.

## 11. planning-state.v2 Schema (final)

### Full Schema Shape

```json
{
  "version": "planning-state.v2",
  "goalType": "UNDERSTAND_USER_INTENT | RESOLVE_USER_CHOICE | PRODUCE_DIRECT_RESPONSE | GATHER_EXTERNAL_EVIDENCE | WAIT_FOR_RUNTIME_DEPENDENCY",
  "userInputRequired": {
    "value": true,
    "gapType": "intent_gap | choice_gap | confirmation_gap | authorization_gap | argument_gap | none",
    "reasonCodes": ["enum"],
    "evidenceRefs": ["ref"]
  },
  "externalStepRequired": {
    "value": false,
    "capabilityAssessment": null,
    "reasonCodes": ["enum"],
    "evidenceRefs": ["ref"]
  },
  "directResponseSufficient": {
    "value": false,
    "reasonCodes": ["enum"],
    "evidenceRefs": ["ref"]
  },
  "newDurableKnowledgePresent": {
    "value": false,
    "reasonCodes": ["enum"],
    "evidenceRefs": ["ref"]
  }
}
```

### capabilityAssessment Shape (REQUIRED when e=true, null when e=false)

```json
{
  "capabilityId": "<id from snapshot.availableCapabilities>",
  "riskLevel": "READ_ONLY | LOCAL_DURABLE | EXTERNAL_IRREVERSIBLE",
  "argumentCompleteness": "GROUNDED | PARTIAL | MISSING | NOT_REQUIRED",
  "authorizationStatus": "CONFIRMED | PENDING | MISSING | NOT_REQUIRED",
  "executionNecessity": "REQUIRED_NOW | PREFERRED | OPTIONAL"
}
```

(EXTERNAL_REVERSIBLE reserved in the risk mapping table, Section 8; it is
not a schema-accepted riskLevel value until observed in a real descriptor.)

### Deterministic cross-checks (C2; validator-enforced, harness-computed)

1. goalType equals the Section 5 reference derivation on the same input.
2. u=true IMPLIES d=false; d=true IMPLIES u=false.
3. u=true IMPLIES e=false. u=true IMPLIES n=false (orthogonality).
4. e=true IMPLIES capabilityAssessment != null with capabilityId resolving
to exactly one descriptor; riskLevel equals the Section 8 descriptor
projection; executionNecessity is REQUIRED_NOW; all capability: refs in
the e block equal capability:<capabilityId>.
5. e=true with riskLevel EXTERNAL_IRREVERSIBLE IMPLIES
argumentCompleteness GROUNDED AND authorizationStatus CONFIRMED (cited).
6. authorizationStatus CONFIRMED IMPLIES at least one resolving
capabilityResults or confirmed-claim authorization citation.
7. d=true IMPLIES goalType PRODUCE_DIRECT_RESPONSE AND at least one
confirmed (conf >= 0.5) claim cited.
8. goalType WAIT_FOR_RUNTIME_DEPENDENCY IMPLIES all four flags false
(declared only; unreachable on R4 inputs).

Violations of 1-8 are C2: validator-rejected, excluded from semantic
scoring, never counted as semantic passes. See Diagnostic Design for the
C1/C2/C3 taxonomy and gate accounting.
## 12. WAIT Treatment

### Current Status

E07-resolved and E22 expect WAIT, but the flag-to-family mapping has no WAIT
channel. This is a known structural limitation (see
ACTION_ELIGIBILITY_ARCHITECTURE.md).

### R4 Approach

1. WAIT_FOR_RUNTIME_DEPENDENCY is a valid goalType enum value with NO
derivation rule in R4 (no pending-dependency signal exists in R4 inputs).
2. If ever output, all four flags MUST be false (cross-check 8).
3. The mapping does NOT produce WAIT from the four flags.
4. R4 diagnostic continues to exclude E22 identities from all gates.
5. E07-resolved stays a known structural issue and is excluded from the
frozen critical-regression set (see Diagnostic Design).
6. A future iteration (beyond R4) may add runDisposition/completionState.

### Extension Point

planning-state.v2 reserves the right to add:
```
"runDisposition": "ACTIVE | WAITING | BLOCKED | COMPLETE"
```
in a future version. This is NOT implemented in R4.

## 13. Mapping Compatibility (planning-mapping.v2, final)

### Current Mapping (planning-mapping.v1)

```
user     -> REQUEST_USER_INPUT
external -> INVOKE_CAPABILITY
direct   -> RESPOND_TO_USER
durable  -> CREATE_NODE
```
No WAIT channel; co-activations collapse to PLANNING_AMBIGUOUS.

### R4 Mapping (planning-mapping.v2)

```
0. Contract gate (deterministic, precedes mapping):
   C1 schema rejection or C2 cross-check violation
   -> outcome CONTRACT_VIOLATION (excluded from semantic scoring).
1. If goalType = WAIT_FOR_RUNTIME_DEPENDENCY -> NO_WINNER_structural
   (excluded from gates; unreachable on R4 inputs).
2. Elif u=true -> REQUEST_USER_INPUT.
3. Elif e=true (all Section 8 conditions hold) -> INVOKE_CAPABILITY.
4. Elif d=true -> RESPOND_TO_USER.
5. Elif n=true -> CREATE_NODE.
6. Else -> NO_WINNER.
```

PLANNING_AMBIGUOUS is REMOVED as a mapping outcome: with u/d, u/e, d+e,
and u+n pairs classified as C2 violations, the only mapping-reachable
multi-true pairs are (e,n) and (d,n), both resolved by the fixed residual
order above. A NO_WINNER on an oracle-REQUEST identity is therefore always
a semantic miss attributable to u=false, never to a tie-break.

### Explicit statement on ordering

Steps 2-5 are an explicitly documented residual disambiguation order
u > e > d > n. It is a mapping tie-break for the two legal residual pairs
(e,n) and (d,n) ONLY. It is not a weight table, not a ranking tuner, and
never adjudicates between conflicting primary needs, because conflicting
primaries are contract violations that never reach mapping. The first draft's
claim of "no precedence" alongside an "e > d > n" remark was self-
contradictory; this section replaces it.

## 14. Safety Boundaries

### ADVISOR Autonomy

ADVISOR means the model does NOT execute actions, only recommends them.
The planning-state describes semantic need, not execution permission.
Even e=true with all conditions met does not authorize execution; actual
execution stays gated by authorization policy, executor boundary, staleness
check, and runtime confirmation.

### Irreversible Action Safety

EXTERNAL_IRREVERSIBLE assessment requires ALL of: e=true,
argumentCompleteness GROUNDED, authorizationStatus CONFIRMED (cited),
executionNecessity REQUIRED_NOW, no unresolved claims, u=false. If ANY
condition fails, e=false. The model cannot authorize irreversible actions
through the planning state alone, and silence is never authorization.

### Eligibility / Semantic Need / Authorization / Execution stay layered

```
Eligibility (runtime-owned) != Semantic need (model-judged e/d/u/n)
  != Authorization (record-cited CONFIRMED) != Execution (executor-owned)
```

INVOKE_CAPABILITY eligible != should-invoke-now != authorized != executed.
R4 respects the layering: the model judges necessity (e) and cites records
(auth), the validator checks record existence, and nothing in the planning
state executes anything.

### Benchmark Isolation

The planning prompt MUST NOT reference expected actions or benchmark labels,
include scenario-specific wording, hint at "correct" answers, or use family
names as guidance (families appear only in eligibleFamilies, never in
the prompt).

## 15. Examples (corrected)

### Example 1: E10-like (answer submitted, no claims)

Input: event ANSWER_SUBMITTED; effectiveClaims []; patch claims [];
capabilities include resource.extract_text; eligible include REQUEST/RESPOND.

Derivation: G1 -> goalType UNDERSTAND_USER_INTENT. Then u=true (intent_gap),
e=false (SAFER_PATH_AVAILABLE: asking comes first), d=false
(NO_GROUNDED_CONTENT), n=false. Mapping step 2 -> REQUEST_USER_INPUT.
Correct.

### Example 2: E17-like (unresolved claim, high-risk capability, ADVISOR)

Input: event ANSWER_SUBMITTED; one unresolved claim (conf 0.2);
availableCapabilities include eval.high-risk.external
(EXTERNAL_IRREVERSIBLE, ADVISOR, no args, no auth record).

Derivation: G2 -> goalType RESOLVE_USER_CHOICE. u=true (intent_gap, cited
claim:effective/0). e=false: u=true already forces it, and independently
args MISSING + auth MISSING fail the irreversible row. d=false. n=false.
Mapping step 2 -> REQUEST_USER_INPUT. Matches corrected oracle.

### Example 3: external-positive CAL-E1 (synthetic calibration, high path)

Input: event ANSWER_SUBMITTED; confirmed claims grounding the required
arguments; snapshot.capabilityResults contains an approval record for
cap actuator.external (provenance: user-approved); autonomy ADVISOR;
no unresolved claims.

R4 output (shape):
```json
{
  "version": "planning-state.v2",
  "goalType": "GATHER_EXTERNAL_EVIDENCE",
  "userInputRequired": {
    "value": false, "gapType": "none",
    "reasonCodes": ["USER_INPUT_SUFFICIENT"],
    "evidenceRefs": ["claim:effective/0"]
  },
  "externalStepRequired": {
    "value": true,
    "capabilityAssessment": {
      "capabilityId": "actuator.external",
      "riskLevel": "EXTERNAL_IRREVERSIBLE",
      "argumentCompleteness": "GROUNDED",
      "authorizationStatus": "CONFIRMED",
      "executionNecessity": "REQUIRED_NOW"
    },
    "reasonCodes": ["EXTERNAL_ACTION_REQUIRED"],
    "evidenceRefs": ["capability:actuator.external", "claim:effective/0", "claim:effective/1"]
  },
  "directResponseSufficient": {
    "value": false,
    "reasonCodes": ["AWAITING_EXTERNAL_RESULT"],
    "evidenceRefs": ["capability:actuator.external"]
  },
  "newDurableKnowledgePresent": {
    "value": false,
    "reasonCodes": ["REDUNDANT_WITH_EXISTING"],
    "evidenceRefs": ["claim:effective/0"]
  }
}
```

Notes: goalType G3 fires on non-empty capabilityResults; the CONFIRMED
authorization cites the results entry (referenced here via the grounding
claims that quote it; the raw results entry is addressable by the harness
for C2 check 6). No observation: refs appear anywhere. Mapping step 3 ->
INVOKE_CAPABILITY. Correct.

### Example 4: direct-positive (grounded claims, no blockers)

Input: event ANSWER_SUBMITTED; confirmed claims conf >= 0.5; U empty;
R empty; no capability needed.

Derivation: G4 -> goalType PRODUCE_DIRECT_RESPONSE. u=false, e=false
d=true (GROUNDED_RESPONSE_AVAILABLE, cited claim:effective/0), n=false.
Mapping step 4 -> RESPOND_TO_USER. Correct.

### Example 5: read-only external need on frozen-like input (low path)

Input: E10-like claims state, but the current goal (RESOLVE_USER_CHOICE)
cannot advance by asking (user already answered twice identically) and a
READ_ONLY descriptor resource.extract_text can supply the missing grounding
with no args and no auth requirement.

Then u=false is reachable ONLY with cited justification that the gap is
not user-fillable; e=true with capabilityId resource.extract_text,
riskLevel READ_ONLY, argumentCompleteness NOT_REQUIRED,
authorizationStatus NOT_REQUIRED, executionNecessity REQUIRED_NOW.
Mapping step 3 -> INVOKE_CAPABILITY. This is the ONLY e=true shape
reachable on frozen-corpus-like inputs, and it requires the model to defend
REQUIRED_NOW against the safer-path rule.

## 16. Tradeoffs

### Prompt-level goal derivation vs runtime injection

We keep goalType model-output (Option C) because R4 cannot modify the
runtime. Pseudo-determinism is avoided by placing determinism in the harness
reference function plus C2 enforcement, not in prose assertions.
A future runtime integration (goalType injected by the runtime) would remove
the C2 goal-mismatch class entirely.

### Mutual exclusion u/d vs co-activation

Hard mutual exclusion stands. Cost: "partial response + ask" has no
representation. Acceptable: single-winner mapping, content-free partial
responses are meaningless, ADVISOR does not support "respond then ask".

### Capability assessment as sub-object vs separate flag

Sub-object on e stands, now with capabilityId binding. Risk/args/auth are
meaningless without a named capability; the binding plus the no-splicing
rule make the assessment auditable per capability.

### n orthogonal vs competing candidate

Orthogonal (option b) chosen: n never outranks a primary need, CREATE fires
only with u=e=d=false. Cost: an input with BOTH a primary need and
persistable content maps away from CREATE (by design: persist later, act
now). Benefit: mapping needs no weights and n can never mask E10/E17-style
primary failures.

### WAIT declared but not mapped

Conscious deferral stands. E07-resolved and E22 remain structurally
unsupported and gated out. R4 scope stays on the P0/P1/P2 problems that
caused R3 failure.

## 17. Summary of Changes from R3 to R4 (final)

| Aspect | R3 (planning-state.v1) | R4 (planning-state.v2, corrected) |
|--------|----------------------|----------------------|
| Goal | implicit (undefined) | goalType enum, derived from pre-flag observables only (G1-G5) |
| goalType producer | n/a | model-output, harness reference-checked (C2) |
| u definition | "correct progress REQUIRES" | goalType in {UNDERSTAND, RESOLVE} AND gap user-fillable |
| u gap types | none | 5 gaps + none; confirmation/authorization anchored to descriptors |
| d definition | "current step goal can be completed" | goalType PRODUCE_DIRECT_RESPONSE AND confirmed claim present |
| d ground requirement | none | >= 1 confirmed claim, conf >= 0.5, cited |
| u/d relationship | co-possible | mutually exclusive (C2-enforced) |
| e definition | "REQUIRES executing external step now" | next step genuinely requires ONE named capability now |
| e capability binding | none | capabilityId, input-membership + no-splicing enforced |
| e risk | none | deterministic descriptor projection (validator recomputed) |
| e auth | none | record-cited CONFIRMED; silence is MISSING |
| e args | none | hybrid: citation presence deterministic, grounding semantic |
| e necessity | none | model-judged; only REQUIRED_NOW yields e=true |
| n role | competing flag | orthogonal signal; CREATE only when u=e=d=false |
| mapping order | single-winner, AMBIGUOUS on ties | explicit residual order u>e>d>n; AMBIGUOUS removed |
| Evidence prefixes | 7 | 8 (+event:; observation: deliberately excluded) |
| WAIT | not represented | declared goalType, no derivation rule, not mapped |
| capabilityAssessment | none | required when e=true, null when e=false |

## 18. Correction log (this pass)

1. goalType: removed EXECUTE_AUTHORIZED_ACTION and CAPTURE_DURABLE_KNOWLEDGE;
redefined as pre-flag planning phase with disjoint rules G1-G5, explicit
fail-safe fallback, and harness reference-checking (C2).
2. capabilityAssessment: added capabilityId with input-membership validation
and a no-cross-capability-splicing rule.
3. Split deterministic vs model-judged: riskLevel fully deterministic
(descriptor projection table); authorizationStatus record-cited (validator
checks existence); argumentCompleteness hybrid (citation deterministic,
grounding semantic); executionNecessity model-judged, validator-gated to
REQUIRED_NOW for e=true.
4. Evidence: observation: REMOVED with file-level justification
(production envelope event+snapshot only; frozen observation always {};
auth/args evidence real locations enumerated); event: KEPT; canonical ref
forms specified; validator builds allowed set from actual input.
5. e semantics: REQUIRED_NOW literal for all risk rows; PREFERRED/OPTIONAL
force e=false; READ_ONLY lowers auth/arg bars only.
6. n declared orthogonal (option b); CREATE only when u=e=d=false;
residual order u>e>d>n stated explicitly as mapping tie-break.
7. Gates reworked in the Diagnostic Design doc: 18-call calibration with
per-case criteria, independent semantic gates, frozen 28-identity regression
set, exact flip-rate formula, C1/C2/C3 accounting.

# R4 Semantic Architecture Design

## 0. Provenance

- Created: 2026-09-06
- Parent lineage: R3 SEMANTIC_PLANNING_DIAGNOSTIC_R3 (fdd9442)
- Scope: P0 + P1 + P2 semantic architecture (P3 mapping redesign deferred)
- Input documents:
  - docs/v2/SEMANTIC_PLANNING_R3_FORENSIC.md
  - docs/v2/SEMANTIC_PLANNING_EXPECTATION_AUDIT.md
  - docs/v2/SEMANTIC_PLANNING_BENCHMARK_ORACLE_AUDIT.md
  - docs/v2/SEMANTIC_PLANNING_R4_DESIGN_PRECONDITIONS.md
- Frozen constraints: R3 artifacts unchanged, production unchanged

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
6. Extend evidence vocabulary to include observation/event
7. Enable clean deterministic mapping to action families
8. Maintain schema-strict, bounded, CoT-free output

## 3. Non-Goals

- P3 mapping redesign (precedence rules, weights, ranking)
- WAIT runtime architecture (deferred to future iteration)
- Model behavior modification (we fix definitions, not the model)
- Production code changes (R4 is diagnostic-only)
- Action family splitting (INVOKE_CAPABILITY stays one family)

## 4. Planning-State Version Decision

### Option A: Keep four flags, add auxiliary fields

Keep the existing four boolean flags but add bounded sub-fields to
externalStepRequired for risk/args/auth context.

### Option B: Upgrade to planning-state.v2 with structured sub-objects

Replace flat flags with a structured object:
```
{
  version: "planning-state.v2",
  goal: { type: enum, statement: ... },
  userNeed: { value: bool, gapType: enum, ... },
  externalNeed: { value: bool, risk: enum, args: enum, auth: enum, ... },
  directResponse: { value: bool, ... },
  durableKnowledge: { value: bool, ... }
}
```

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

## 5. Goal Representation

### The Problem

directResponseSufficient's "current step goal" has no referent in the input.
The model is free to interpret "goal" as whatever it wants, leading to
E10-style exploitation ("goal = confirm receipt → d=true").

### Option A: Runtime explicit goal (goalStatement field)

Runtime adds a goalStatement string to the snapshot.

Pros: Most explicit, easiest to audit.
Cons: Requires runtime schema change, increases production surface,
      free-text goal invites interpretation.

### Option B: Semantic goal type (bounded enum)

Runtime provides a goalType enum in the snapshot.

Pros: Bounded, auditable, no free text.
Cons: Requires runtime support, enum may not cover all cases.

### Option C: Prompt-only derived goal

Goal derived from event + lineage + claims in the prompt.

Pros: Zero runtime changes.
Cons: Highest ambiguity risk — exactly the problem we are solving.

### Decision: Option C with bounded inference rules

We cannot modify the runtime for R4 (diagnostic-only). Therefore:

The R4 prompt MUST include explicit inference rules for goal derivation:

```
Given the event type and snapshot state, derive the current step goal:

- If event.kind = ANSWER_SUBMITTED AND effectiveClaims is empty:
  goal = UNDERSTAND_USER_INTENT (clarify what the answer means)
- If event.kind = ANSWER_SUBMITTED AND unresolved claims exist:
  goal = RESOLVE_USER_CHOICE (resolve the unresolved items)
- If event.kind = ANSWER_SUBMITTED AND confirmed claims exist
  AND no unresolved blockers:
  goal = PRODUCE_DIRECT_RESPONSE (respond with grounded content)
- If event.kind = ANSWER_SUBMITTED AND confirmed claims exist
  AND unresolved blockers:
  goal = RESOLVE_USER_CHOICE (resolve the blockers)
- If external action is necessary AND args grounded AND auth confirmed:
  goal = EXECUTE_AUTHORIZED_ACTION
- If new durable knowledge exists:
  goal = CAPTURE_DURABLE_KNOWLEDGE
```

These rules are deterministic and auditable. The model does not invent
the goal — it selects from a bounded set based on observable input features.

Goal types (enum):
```
UNDERSTAND_USER_INTENT
RESOLVE_USER_CHOICE
PRODUCE_DIRECT_RESPONSE
GATHER_EXTERNAL_EVIDENCE
EXECUTE_AUTHORIZED_ACTION
CAPTURE_DURABLE_KNOWLEDGE
WAIT_FOR_RUNTIME_DEPENDENCY
```

For R4 P0+P1+P2 scope, WAIT_FOR_RUNTIME_DEPENDENCY is declared but
not used in mapping (WAIT structural limitation acknowledged).

## 6. userInputRequired — R4 Design

### Current Definition (broken)

"true iff correct progress now REQUIRES new user information, choice,
confirmation, or blocker resolution"

Problem: "correct progress" has no referent. "REQUIRES" is too broad.

### R4 Definition

userInputRequired (u): true iff the current step goal (derived per Section 5
rules) cannot be achieved without new information that only the user can
provide.

### Gap Types

The R4 prompt MUST distinguish gap types:

| Gap Type | u value | Rationale |
|----------|---------|-----------|
| intent_gap | true | We don't know what the user wants |
| choice_gap | true | User must choose between options |
| confirmation_gap | depends | If irreversible + ADVISOR: true. If read-only: false |
| authorization_gap | true | Irreversible action needs user authorization |
| execution_argument_gap | depends | If arg can be derived from context: false. If only user has it: true |
| content_gap | false | We have info but it's insufficient richness — this is d's domain |

### Key Rules

1. "answer submitted" != "user input sufficient"
   An answer existing in the snapshot does NOT mean we understand it.
   If effectiveClaims is empty, u=true (intent_gap).

2. "capability available" != "user input not required"
   If an irreversible capability is available but args/auth are missing,
   u=true (authorization_gap or execution_argument_gap).

3. "confirmed claim exists" does NOT automatically mean u=false
   If the confirmed claim doesn't address the current goal, u may still
   be true.

4. u and d relationship:
   - u=true IMPLIES d=false (if we need user info, we cannot respond)
   - d=true IMPLIES u=false (if we can respond, we don't need user info)
   - u=false AND d=false is valid (we need external step, not user)
   - u=false AND d=false AND e=false AND n=false: NO_WINNER (no clear path)

### Reason Codes

```
True codes:
  INTENT_GAP        — Cannot determine what user wants
  CHOICE_GAP        — User must choose between options
  CONFIRMATION_GAP  — Irreversible action needs user confirmation
  AUTHORIZATION_GAP — Action requires user authorization
  ARGUMENT_GAP      — Required argument can only come from user

False code:
  USER_INPUT_SUFFICIENT — Current goal can proceed without new user info
```

### Evidence Requirements

When u=true:
- evidenceRefs MUST cite the specific gap (eg claim:xxx for unresolved claim,
  capability:xxx for missing auth)
- Citing "the answer exists" is NOT valid evidence for u=false if
  effectiveClaims is empty

## 7. directResponseSufficient — R4 Design (P0 critical)

### Current Definition (broken)

"true iff the current step goal can be completed right now with existing
information"

Problem: "current step goal" undefined. "completed" = "received" or "understood"?

### R4 Definition

directResponseSufficient (d): true iff the current step goal is
PRODUCE_DIRECT_RESPONSE AND there exists at least one grounded semantic
claim (confirmed or grounded, conf >= 0.5) that can serve as the basis
for a substantive response.

### The E10 Invariant

```
ANSWER_SUBMITTED != ANSWER_UNDERSTOOD != GOAL_SATISFIED
```

- ANSWER_SUBMITTED: the system received a message (event fact)
- ANSWER_UNDERSTOOD: the system has confirmed/grounded claims about the
  answer's meaning (semantic fact)
- GOAL_SATISFIED: the current step goal is achieved (planning fact)

d=true requires ANSWER_UNDERSTOOD, not merely ANSWER_SUBMITTED.

### Explicit Rules

d=true REQUIRES ALL of:
1. Current goal type is PRODUCE_DIRECT_RESPONSE (derived per Section 5)
2. At least one claim with status in {confirmed, grounded} AND conf >= 0.5
3. No unresolved blocker claims
4. u=false (mutual exclusion: if user input needed, cannot respond)

d=false if ANY of:
1. effectiveClaims is empty (nothing to base response on)
2. Only claims with status = assumed or conf < 0.5
3. Unresolved blocker claims exist
4. Current goal is not PRODUCE_DIRECT_RESPONSE

### GOAL_SATISFIED Usage

GOAL_SATISFIED may ONLY be used when:
- The current goal is PRODUCE_DIRECT_RESPONSE
- AND confirmed/grounded claims exist that directly address the goal
- AND the model can cite specific claim IDs as evidence

Using GOAL_SATISFIED when effectiveClaims is empty is a contract violation.

### Mutual Exclusion with u

```
u=true => d=false  (hard invariant)
d=true => u=false  (hard invariant)
```

This is enforced by definition, not by mapping precedence.

### Reason Codes

```
True codes:
  GROUNDED_RESPONSE_AVAILABLE — Confirmed/grounded claims support a response
  GOAL_ACHIEVED               — Current goal already accomplished

False codes:
  NO_GROUNDED_CONTENT         — No confirmed/grounded claims to base response on
  AWAITING_USER_INPUT         — Cannot respond until user provides info
  AWAITING_EXTERNAL_RESULT    — Cannot respond until external step completes
```

Note: GOAL_SATISFIED is renamed to GOAL_ACHIEVED and constrained.
NOTHING_NEW_TO_ASK is removed (too vague, replaced by GROUNDED_RESPONSE_AVAILABLE).

## 8. externalStepRequired — R4 Design (P0)

### Current Definition (broken)

"true iff the goal REQUIRES executing an external capability or tool step now"

Problem: Doesn't distinguish read-only from irreversible. No arg/auth requirements.

### R4 Definition

externalStepRequired (e): true iff the current step goal cannot be achieved
without executing a specific external capability, AND the execution conditions
(risk level, argument completeness, authorization) are satisfied.

### Capability Risk Levels

The R4 planning-state MUST include a capabilityAssessment sub-object when
e=true:

```
capabilityAssessment: {
  riskLevel: enum,
  argumentCompleteness: enum,
  authorizationStatus: enum,
  executionNecessity: enum
}
```

Risk levels:
```
READ_ONLY          — No side effects (eg resource.extract_text)
LOCAL_DURABLE      — Local side effects only (eg write to graph)
EXTERNAL_REVERSIBLE — External but reversible side effects
EXTERNAL_IRREVERSIBLE — Cannot be undone (eg send email, execute code)
```

Argument completeness:
```
GROUNDED       — All required args present and grounded in snapshot
PARTIAL        — Some args present, others derivable
MISSING        — Critical args absent
NOT_REQUIRED   — Capability needs no args
```

Authorization status:
```
CONFIRMED      — User has explicitly authorized this action
PENDING        — Authorization requested but not received
MISSING        — No authorization record
NOT_REQUIRED   — Read-only or auto-authorized action
```

Execution necessity:
```
REQUIRED_NOW   — Goal cannot proceed without this execution
PREFERRED      — Execution would help but alternatives exist
OPTIONAL       — Execution is supplementary
```

### Threshold Rules by Risk Level

| Risk Level | Min Args | Min Auth | Min Necessity | e possible? |
|-----------|----------|----------|---------------|-------------|
| READ_ONLY | any | NOT_REQUIRED | PREFERRED | Yes (low bar) |
| LOCAL_DURABLE | GROUNDED | CONFIRMED or NOT_REQUIRED | REQUIRED_NOW or PREFERRED | Yes |
| EXTERNAL_REVERSIBLE | GROUNDED | CONFIRMED | REQUIRED_NOW | Yes |
| EXTERNAL_IRREVERSIBLE | GROUNDED | CONFIRMED | REQUIRED_NOW | Yes (high bar) |

If conditions are not met for the risk level, e=false.

### The "No Safer Path" Rule

e=true ONLY if no safer alternative path exists:
- If REQUEST_USER_INPUT could resolve the gap, e=false
- If the goal could be achieved with just the user's input (not requiring
  external execution), e=false
- e=true means: user info is complete, args are grounded, auth is confirmed,
  and ONLY the external execution remains

### Interaction with u

If u=true (user input gap exists), e MUST be false.
Rationale: if we need user info, the external step is not "required now" —
the user interaction is required first.

```
u=true => e=false  (hard invariant)
```

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

### capabilityAssessment When e=false

When e=false, capabilityAssessment MAY be omitted or set to null.
When e=true, capabilityAssessment is REQUIRED.

## 9. newDurableKnowledgePresent — R4 Design (P1)

### Current Definition (loose)

"true iff a new, standalone semantic unit worth persisting exists now"

Problem: "new" threshold too low. Model counts rephrasing as new.

### R4 Definition

newDurableKnowledgePresent (n): true iff the input contains a semantic unit
that satisfies ALL of: novelty, durability, independence, and non-redundancy.

### Sub-Criteria

**Novelty**: The information is NOT present in the snapshot's existing
claims, answers, patches, or lineage. Specifically:
- NOT a rephrasing of an existing claim
- NOT a reformatting of an existing answer
- NOT a summary or aggregation of existing content
- NOT a metadata annotation (timestamps, IDs, status flags)

**Durability**: The information has lasting value for future planning/spec:
- NOT a transient runtime status (eg "processing...")
- NOT a temporary execution result
- NOT a planning intermediate step
- Represents a fact, decision, constraint, or requirement

**Independence**: Can be understood and referenced as a standalone unit:
- Does not require the current sentence structure to make sense
- Has a clear semantic identity (what it IS, not just what it modifies)
- Can be a node in the knowledge graph

**Non-redundancy**: Does not duplicate existing graph content:
- After normalization (lowercase, strip whitespace), is distinct from
  existing nodes
- Does not contradict existing confirmed claims (if it does, that is
  a conflict to resolve, not new knowledge to store)

### The R3 Failure Mode

R3: model produced n=true 61 times, but ZERO scenarios expected CREATE.
Model interpreted "patch/claim exists in snapshot" as "worth persisting"
rather than "already persisted / already known".

R4 fix: the prompt MUST explicitly state:
"Existing content in the snapshot is NOT new. Rephrasing, summarizing,
or reformatting existing content does NOT count as new durable knowledge.
n=true requires information that is genuinely absent from the current
knowledge state."

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

## 10. Evidence Vocabulary — P2

### Current Prefixes (R3 frozen)

```
node:, answer:, patch:, context:, route:, claim:, capability:
```

### Problem

Expectation audit Phase F discovered that authorization and argument data
live in the observation: field, which is NOT in the evidence vocabulary.
An external-positive probe referencing observation:authorization was judged
as bad-evidence-ref.

### R4 Additions

Add:
```
observation:  — Current event observation data (auth records, arg details)
event:        — Trigger event information (kind, metadata)
```

### Validation Rules

- observation: refs must point to keys present in the observation object
  of the model input
- event: refs must point to keys present in the event object of the model input
- Both are strictly validated: the ref ID must exist in the actual input
- Free-text evidence remains forbidden

### Contract Update

EVIDENCE_REF_PREFIXES becomes:
```
node:, answer:, patch:, context:, route:, claim:, capability:,
observation:, event:
```

## 11. planning-state.v2 Schema

### Full Schema Shape

```json
{
  "version": "planning-state.v2",
  "goalType": "enum",
  "userInputRequired": {
    "value": true,
    "gapType": "enum",
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

### capabilityAssessment Shape (required when e=true)

```json
{
  "riskLevel": "READ_ONLY | LOCAL_DURABLE | EXTERNAL_REVERSIBLE | EXTERNAL_IRREVERSIBLE",
  "argumentCompleteness": "GROUNDED | PARTIAL | MISSING | NOT_REQUIRED",
  "authorizationStatus": "CONFIRMED | PENDING | MISSING | NOT_REQUIRED",
  "executionNecessity": "REQUIRED_NOW | PREFERRED | OPTIONAL"
}
```

### Hard Invariants

1. u=true => d=false
2. d=true => u=false
3. u=true => e=false
4. e=true => capabilityAssessment != null
5. e=true AND riskLevel=EXTERNAL_IRREVERSIBLE =>
   argumentCompleteness=GROUNDED AND authorizationStatus=CONFIRMED
   AND executionNecessity=REQUIRED_NOW
6. d=true => at least one confirmed/grounded claim with conf >= 0.5
   in evidenceRefs
7. goalType=WAIT_FOR_RUNTIME_DEPENDENCY => all four flags = false
   (WAIT structural limitation: not mapped, declared only)

## 12. WAIT Treatment

### Current Status

E07-resolved and E22 expect WAIT, but planning-mapping.v1 has no WAIT
channel. This is a known structural limitation.

### R4 Approach

1. WAIT_FOR_RUNTIME_DEPENDENCY is added as a valid goalType enum value
2. When goalType=WAIT_FOR_RUNTIME_DEPENDENCY, all four flags MUST be false
3. The mapping does NOT produce WAIT from the four flags
4. R4 diagnostic continues to exclude E22 from gate scoring
5. E07-resolved continues as known structural issue
6. A future iteration (beyond R4) will add a runDisposition or
   completionState field to enable WAIT mapping

### Extension Point

planning-state.v2 reserves the right to add:
```
"runDisposition": "ACTIVE | WAITING | BLOCKED | COMPLETE"
```
in a future version. This is NOT implemented in R4.

## 13. Mapping Compatibility

### Current Mapping (planning-mapping.v1)

```
user     -> REQUEST_USER_INPUT
external -> INVOKE_CAPABILITY
direct   -> RESPOND_TO_USER
durable  -> CREATE_NODE
```

### R4 Mapping (planning-mapping.v2)

The mapping logic changes to accommodate planning-state.v2:

```
1. If goalType = WAIT_FOR_RUNTIME_DEPENDENCY: NO_WINNER (structural)
2. If u=true: REQUEST_USER_INPUT
3. If e=true AND capabilityAssessment.executionNecessity = REQUIRED_NOW:
   INVOKE_CAPABILITY
4. If d=true: RESPOND_TO_USER
5. If n=true AND no other flag is true: CREATE_NODE
6. If multiple flags true (should not happen with proper design):
   PLANNING_AMBIGUOUS
7. If all flags false: NO_WINNER
```

### Key Differences from v1

1. WAIT is handled at goal level, not flag level
2. e=true is gated by executionNecessity, not just the boolean
3. u and d are mutually exclusive by definition (no more d+n co-activation
   causing AMBIGUOUS on E10-like inputs)
4. The mapping does NOT use precedence weights — it relies on the semantic
   state being well-designed enough that conflicts are rare

### Conflict Prevention

If the R4 definitions are correct, the following tuple combinations
should be impossible:
- u=true AND d=true (mutual exclusion)
- u=true AND e=true (u blocks e)
- d=true AND e=true (if we can respond, external not needed)
- All four true (contradiction)

The only legal multi-true combinations are:
- e=true AND n=true (external step produces new knowledge)
- d=true AND n=true (response contains new durable content)

Both are unambiguous for mapping: e > d > n.

## 14. Safety Boundaries

### ADVISOR Autonomy

ADVISOR means the model does NOT execute actions, only recommends them.
The planning-state describes semantic need, not execution permission.

Even if e=true with all conditions met, actual execution is gated by:
- Authorization policy
- Executor boundary
- Staleness check
- Runtime confirmation

### Irreversible Action Safety

EXTERNAL_IRREVERSIBLE actions require ALL of:
1. e=true
2. capabilityAssessment.argumentCompleteness = GROUNDED
3. capabilityAssessment.authorizationStatus = CONFIRMED
4. capabilityAssessment.executionNecessity = REQUIRED_NOW
5. No unresolved blockers
6. u=false (no pending user interaction)

If ANY condition fails, e=false. The model cannot authorize irreversible
actions through the planning state alone.

### Benchmark Isolation

The planning prompt MUST NOT:
- Reference expected actions or benchmark labels
- Include scenario-specific wording
- Hint at "correct" answers
- Use family names as guidance (families are in eligibleFamilies, not prompt)

## 15. Examples

### Example 1: E10-like (answer submitted, no claims)

Input:
- event: ANSWER_SUBMITTED
- effectiveClaims: []
- patch claims: []
- capabilities: [resource.extract_text, ...]
- eligibleFamilies: [REQUEST, RESPOND, INVOKE, CREATE]

R4 output:
```json
{
  "version": "planning-state.v2",
  "goalType": "UNDERSTAND_USER_INTENT",
  "userInputRequired": {
    "value": true,
    "gapType": "intent_gap",
    "reasonCodes": ["INTENT_GAP"],
    "evidenceRefs": ["answer:xxx"]
  },
  "externalStepRequired": {
    "value": false,
    "capabilityAssessment": null,
    "reasonCodes": ["SAFER_PATH_AVAILABLE"],
    "evidenceRefs": ["context:xxx"]
  },
  "directResponseSufficient": {
    "value": false,
    "reasonCodes": ["NO_GROUNDED_CONTENT"],
    "evidenceRefs": ["answer:xxx"]
  },
  "newDurableKnowledgePresent": {
    "value": false,
    "reasonCodes": ["NOT_STANDALONE"],
    "evidenceRefs": ["patch:xxx"]
  }
}
```

Mapping: u=true => REQUEST_USER_INPUT. Correct.

### Example 2: E17-like (unresolved claim, high-risk capability)

Input:
- event: ANSWER_SUBMITTED
- effectiveClaims: [unresolved claim conf 0.2]
- capabilities: [resource.extract_text, eval.high-risk.external]
- eligibleFamilies: [REQUEST, RESPOND, INVOKE, CREATE]

R4 output:
```json
{
  "version": "planning-state.v2",
  "goalType": "RESOLVE_USER_CHOICE",
  "userInputRequired": {
    "value": true,
    "gapType": "intent_gap",
    "reasonCodes": ["INTENT_GAP"],
    "evidenceRefs": ["claim:xxx"]
  },
  "externalStepRequired": {
    "value": false,
    "capabilityAssessment": null,
    "reasonCodes": ["SAFER_PATH_AVAILABLE"],
    "evidenceRefs": ["capability:xxx"]
  },
  "directResponseSufficient": {
    "value": false,
    "reasonCodes": ["AWAITING_USER_INPUT"],
    "evidenceRefs": ["claim:xxx"]
  },
  "newDurableKnowledgePresent": {
    "value": false,
    "reasonCodes": ["NOT_STANDALONE"],
    "evidenceRefs": ["claim:xxx"]
  }
}
```

Mapping: u=true => REQUEST_USER_INPUT. Correct (matches corrected oracle).

### Example 3: external-positive (args grounded, auth confirmed)

Input:
- event: ANSWER_SUBMITTED
- confirmed claims exist
- capability with all args grounded, user authorized, must execute now
- eligibleFamilies: [REQUEST, RESPOND, INVOKE, CREATE]

R4 output:
```json
{
  "version": "planning-state.v2",
  "goalType": "EXECUTE_AUTHORIZED_ACTION",
  "userInputRequired": {
    "value": false,
    "gapType": null,
    "reasonCodes": ["USER_INPUT_SUFFICIENT"],
    "evidenceRefs": ["claim:xxx"]
  },
  "externalStepRequired": {
    "value": true,
    "capabilityAssessment": {
      "riskLevel": "EXTERNAL_IRREVERSIBLE",
      "argumentCompleteness": "GROUNDED",
      "authorizationStatus": "CONFIRMED",
      "executionNecessity": "REQUIRED_NOW"
    },
    "reasonCodes": ["EXTERNAL_ACTION_REQUIRED"],
    "evidenceRefs": ["capability:xxx", "observation:authorization"]
  },
  "directResponseSufficient": {
    "value": false,
    "reasonCodes": ["AWAITING_EXTERNAL_RESULT"],
    "evidenceRefs": ["capability:xxx"]
  },
  "newDurableKnowledgePresent": {
    "value": false,
    "reasonCodes": ["REDUNDANT_WITH_EXISTING"],
    "evidenceRefs": ["claim:xxx"]
  }
}
```

Mapping: e=true, executionNecessity=REQUIRED_NOW => INVOKE_CAPABILITY. Correct.

### Example 4: direct-positive (grounded claims, no blockers)

Input:
- event: ANSWER_SUBMITTED
- confirmed claims with conf >= 0.5
- no unresolved blockers
- no capability needed
- eligibleFamilies: [REQUEST, RESPOND, INVOKE, CREATE]

R4 output:
```json
{
  "version": "planning-state.v2",
  "goalType": "PRODUCE_DIRECT_RESPONSE",
  "userInputRequired": {
    "value": false,
    "gapType": null,
    "reasonCodes": ["USER_INPUT_SUFFICIENT"],
    "evidenceRefs": ["claim:xxx"]
  },
  "externalStepRequired": {
    "value": false,
    "capabilityAssessment": null,
    "reasonCodes": ["NO_EXTERNAL_NEED"],
    "evidenceRefs": ["context:xxx"]
  },
  "directResponseSufficient": {
    "value": true,
    "reasonCodes": ["GROUNDED_RESPONSE_AVAILABLE"],
    "evidenceRefs": ["claim:xxx"]
  },
  "newDurableKnowledgePresent": {
    "value": false,
    "reasonCodes": ["REDUNDANT_WITH_EXISTING"],
    "evidenceRefs": ["claim:xxx"]
  }
}
```

Mapping: d=true => RESPOND_TO_USER. Correct.

## 16. Tradeoffs

### Explicit goal vs implicit derivation

We chose prompt-level goal derivation (Option C) because we cannot modify
the runtime for R4. This introduces some ambiguity, but the bounded
inference rules (Section 5) make it deterministic for the known scenarios.
A future runtime integration (Option A or B) would be more robust.

### Mutual exclusion u/d vs co-activation

We chose hard mutual exclusion (u=true => d=false). This eliminates the
E10 failure mode where d=true despite zero understanding. The cost is that
some edge cases where "partial response + ask for more" might be valid
are now forced to choose one. This is acceptable because:
- The mapping is single-winner anyway
- "Partial response" without grounded content is meaningless
- Product behavior (ADVISOR) doesn't support "respond then ask"

### Capability assessment as sub-object vs separate flag

We chose a sub-object on e rather than a separate flag because:
- Risk/args/auth are only relevant when e=true
- A separate flag would create more boolean combinations
- The sub-object is strictly bounded (enums, not free text)

### WAIT declared but not mapped

WAIT is acknowledged as a valid goalType but not mapped to any action.
This is a conscious deferral. The cost is that E07-resolved and E22
remain structurally unsupported. The benefit is that R4 scope stays
focused on the P0/P1/P2 problems that actually caused R3 failure.

## 17. Summary of Changes from R3 to R4

| Aspect | R3 (planning-state.v1) | R4 (planning-state.v2) |
|--------|----------------------|----------------------|
| Version | planning-state.v1 | planning-state.v2 |
| Goal | implicit (undefined) | explicit goalType enum |
| u definition | "correct progress REQUIRES" | "goal cannot be achieved without user info" |
| u gap types | none | intent/choice/confirmation/auth/arg gap |
| d definition | "current step goal can be completed" | "goal is PRODUCE_DIRECT_RESPONSE AND grounded claims exist" |
| d ground requirement | none | >= 1 confirmed/grounded claim, conf >= 0.5 |
| u/d relationship | co-possible | mutually exclusive |
| e definition | "REQUIRES executing external step now" | "goal requires external step AND conditions met" |
| e risk levels | none | READ_ONLY / LOCAL_DURABLE / REVERSIBLE / IRREVERSIBLE |
| e arg requirement | none | GROUNDED (for irreversible) |
| e auth requirement | none | CONFIRMED (for irreversible) |
| n "new" threshold | loose | novelty + durability + independence + non-redundancy |
| Evidence prefixes | 7 | 9 (+observation, +event) |
| WAIT | not represented | declared as goalType, not mapped |
| capabilityAssessment | none | required when e=true |


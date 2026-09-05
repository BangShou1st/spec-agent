# Action Eligibility B+ Architecture Decision

Status: Accepted for implementation (Phase 3)

## Context

The V2 Decision Engine currently asks one model call to reflect, decide whether
an action family is applicable, rank the applicable actions, and generate the
selected action payload. The cross-language validator rejects malformed
families, payloads, references, and stale context, while
`AdvisorPolicyEngine` determines execution authorization. With the exception
of the unresolved-conflict guard, no Runtime-owned boundary determines whether
a structurally valid action is semantically eligible to be proposed.

Candidate B and Candidate C demonstrated that prompt instructions are not an
enforcement mechanism. A model can correctly observe an existing Answer or
confirmed claim and still emit a schema-valid `CREATE_NODE` that duplicates
it. Equivalent semantic Decision inputs can also produce different primary
action families. Because a tip-appending Knowledge node may be auto-executable,
that selection error can become a durable Graph mutation.

Prompt-only candidates are therefore stopped. Candidate C remains the final
prompt-only baseline and its Decision prompt is frozen.

## Decision

Adopt the B+ two-stage architecture:

1. Java Runtime deterministically derives an eligibility mask and hard
   constraints from the event and frozen input snapshot.
2. The model retains responsibility for semantic materiality, semantic novelty
   beyond deterministic duplication, ranking among eligible families, and
   payload/content generation.
3. Java recomputes and validates the eligibility identity and applies
   payload-dependent vetoes before policy evaluation.
4. `AdvisorPolicyEngine` independently determines whether the selected action
   is auto-executable, confirmable, or denied.
5. Existing schema, reference, snapshot, staleness, idempotency, and execution
   guards remain independent layers.

An ineligible selection fails closed with typed `ACTION_INELIGIBLE`. The first
version does not substitute another family and does not perform an implicit
replan.

## Responsibility boundaries

### Eligibility

Answers: "May this action be proposed in the current state?"

Owned by Java Runtime. It encodes deterministic necessary conditions and hard
prohibitions, including durable duplicate protection, resolved-state
protection, graph-mutation boundaries, visible capability constraints,
grounded capability references, WAIT prerequisites, and eligibility identity.

### Ranking

Answers: "Which eligible action best advances the user's intent?"

Owned by the model. Materiality, the best clarification question, capability
relevance, response sufficiency, and semantic novelty beyond exact normalized
duplicates remain model reasoning responsibilities.

### Authorization

Answers: "May the selected action execute automatically, must it be confirmed,
or must it be denied?"

Owned by `AdvisorPolicyEngine` and the capability policy. Eligibility never
raises execution authority. In particular:

```text
INVOKE_CAPABILITY eligible
!= auto-executable
!= authorized
!= executed
```

### Validation

Answers: "Is the proposal structurally and contextually legal?"

The strict Java/Python contracts and Java trust boundary validate versions,
schema, payload shape, references, snapshot identity, eligibility identity,
staleness, and domain invariants.

### Execution

Answers: "What side effect or durable mutation is applied?"

Owned by Graph and capability Runtime services. The model never writes Graph
state or owns credentials.

## Deterministic invariants

The first version deliberately avoids fuzzy similarity, embeddings, confidence
thresholds, or scenario-specific rules.

### CREATE_NODE

Pre-selection constraints exclude Graph mutation when the flow cannot legally
mutate or when an unresolved blocker makes ordinary durable continuation
unsafe. Post-selection vetoes reject normalized content equal to an existing
Answer, Claim, or Node; a NOTE that merely restates the current Answer; a
DECISION that merely restates confirmed/resolved state; a DECISION without
typed delegation or persistence intent; and content that still depends on an
unresolved blocker.

The V2/V3 event shape carries an optional Runtime-owned typed
`persistenceIntent`. Natural-language `freeText` is never authorization. A
`RECORD_DECISION_NODE` intent is emitted only from an explicitly authorized
runtime command and allows the corresponding `KNOWLEDGE/DECISION` proposal to
pass eligibility; the validator still applies all duplicate and blocker gates.
Advisor confirmation remains a separate authorization layer and cannot repair
missing eligibility.

### REQUEST_USER_INPUT

Structured unresolved conflicts, unresolved open questions, and typed required
blockers can establish deterministic eligibility. A selected question is
rejected if its blocker is already answered/resolved, if it repeats a resolved
question, or if it cannot identify an unresolved blocker. Whether an otherwise
unstructured ambiguity is material remains a model decision.

### INVOKE_CAPABILITY

The capability descriptor must be visible in the frozen snapshot, compatible
with the Runtime-projected context, and use only grounded arguments. Runtime
policy continues to classify side effects and require confirmation or deny
execution independently.

### WAIT and terminal completion

`WAIT` means waiting for an already-existing, Runtime-owned pending dependency,
capability invocation, asynchronous operation, or prerequisite. Missing user
information, uncertainty, lack of a preferred action, and terminal completion
are not WAIT prerequisites.

The current E22-wait evaluation setup contains no pending dependency or
completion disposition in `AgentInputSnapshot`, while its scorer expects WAIT.
The older action protocol also describes both pending waiting and terminal
"nothing useful remains" as WAIT. This is a protocol mismatch and state
representation gap. It must not be repaired with a scenario identifier, seed,
prompt exception, or scorer change.

The minimal future migration should represent terminal state separately,
preferably as a Runtime-owned `runDisposition`/`completionState`. Introducing a
new `COMPLETE`/`STOP` action family is deferred until the product semantics and
runtime lifecycle require it. Until then, E22 is reported as a known protocol
limitation and does not weaken WAIT enforcement.

## Contract evolution

The V2 parsers remain strict and unchanged. Add versioned contracts:

```text
agent-input.v3
agent-decision.v3
action-eligibility.v1
```

The request envelope carries top-level `actionEligibility`; it is not persisted
inside the frozen Graph snapshot because eligibility is derived control data
from `(event, frozen snapshot, evaluator version)`, not Graph truth. The
response echoes the selected eligibility version and basis hash and carries
only bounded public evidence refs. Hidden chain-of-thought is never requested
or stored.

Historical V2 fixtures and frozen projections remain readable. A V2 response
must not become a bypass for a new live durable mutation once enforcement is
enabled.

## Rollout

1. Add generic characterization tests for the current gaps.
2. Implement a pure evaluator, machine-readable reason codes, and validator.
3. Add strict Java/Python V3 contracts and golden fixtures.
4. Wire shadow mode into Answer and Decision cycles without changing behavior.
5. Replay Candidate C artifacts and measure false positives and false
   negatives as well as captured violations.
6. Enable enforcement only after the shadow acceptance gate passes.
7. Run deterministic suites, qualification, unchanged targeted evaluation,
   full90, and final acceptance in that order.

Corpus, expectations, scorer, provider/model, sampling, retry semantics,
STATE_UPDATE behavior, and the Candidate C Decision prompt remain frozen.

## Consequences

The architecture gains repeatable and explainable action boundaries without
turning Java into a complete semantic planner. It adds contract and migration
cost, and an overly narrow mask can create false negatives. Shadow mode,
reason-code telemetry, property tests, and fail-closed versioning are required
to control that risk.

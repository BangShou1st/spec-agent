# Model-Agnostic Evaluation Strategy

Status: Phase 3 reference protocol  
Scope: Spec Agent itself, not a Spec Agent + model pairing

## 1. Principle

The model is a replaceable dependency. Prompts, decision contracts, runtime
invariants, and evaluation contracts must remain meaningful when the provider
or model changes. A reference model is an experimental instrument, not a
permanent product binding and not the definition of acceptable product
behavior.

The historical `mimo-v2.5-free` run at
`464097cd6ca86a0107ce369214506484dfd57c3f` remains preserved as a
model-specific portability benchmark. Its `44.3%` behavioral pass rate and
`0.400` stability are not a cross-model acceptance threshold.

## 2. Four evaluation layers

### Layer A — Deterministic correctness

Model-free checks cover schema contracts, runtime/state application, decision
projection, policy, tracing, safety boundaries, and fake/scripted behavior.
These are expected to be close to 100% and are CI-blocking where appropriate.

### Layer B — Reference live evaluation

A qualified real provider/model runs the unchanged production chain:

```text
STATE_UPDATE -> Java validation/application -> DECISION -> policy/execution
```

The reference model is the primary prompt-development, A/B, regression, and
first-fault gate. Its identity and baseline are always recorded separately
from historical model results.

### Layer C — Cross-model portability

After a candidate is accepted on the reference model, a smaller subset is run
on models from different providers/architectures. The goal is invariant
preservation, not equal scores. The subset checks user authority, conflict
handling, resolved-state discipline, capability eligibility, WAIT semantics,
schema compliance, and propagation safety.

### Layer D — Provider reliability

Provider and transport behavior is reported independently:

- HTTP 401, 429, and 5xx;
- timeout and transport failures;
- provider retries and rate limiting;
- Brain unavailable;
- invalid provider response / schema parse failure.

Reliability failures are never counted as behavioral failures.

## 3. Report contract

Every live report must expose both sections below.

### Behavioral

The denominator is `behavioral_completed`, not planned attempts:

```text
behavioral_completed = behavioral_passed + behavioral_failed
behavioral_pass_rate = behavioral_passed / behavioral_completed
```

Required metrics:

- behavioral completed;
- behavioral passed;
- behavioral failed;
- behavioral pass rate;
- Layer A pass rate;
- action distribution;
- causal first-fault matrix;
- stability.

The primary Experiment 1 metric is:

```text
correct-decision-input-but-wrong-action rate
    = DECISION_FIRST wrong actions / comparable behavioral attempts
```

### Reliability

Required metrics:

- planned attempts;
- executed attempts;
- infrastructure failed;
- availability rate (`behavioral_completed / planned`);
- provider failure class;
- provider retries;
- latency where available;
- schema/protocol failures.

An end-to-end rate may be retained, but it must be labelled as an end-to-end
rate and must not be called behavioral pass rate. A run with only provider
failures has no behavioral denominator; it cannot report behavioral stability
of 100%.

## 4. Causal taxonomy

The offline causal report uses exactly these first-fault classes:

```text
STATE_UPDATE_FIRST
STATE_APPLICATION_FIRST
DECISION_INPUT_PROJECTION_FIRST
DECISION_FIRST
OUTPUT_SCHEMA_FIRST
COMPOUND
AMBIGUOUS
```

`CausalReportGenerator` consumes semantic traces only. Provider failures are
excluded from its behavioral matrix. Required trace stages are:

```text
STATE_UPDATE_INPUT
STATE_UPDATE_OUTPUT
POST_STATE_UPDATE_STATE
DECISION_INPUT
DECISION_OUTPUT
FINAL_RESULT
```

Prompt provenance stores hashes only (`system_prompt_sha256` and
`user_prompt_sha256` by stage); raw prompts, credentials, and hidden reasoning
are not artifacts.

## 5. Qualification protocol

Qualification is deliberately not a behavioral model-selection contest. It
uses five real minimal cycles and considers only:

1. reachable provider and Brain broker;
2. exact model identity and endpoint;
3. five completed `STATE_UPDATE -> DECISION` cycles;
4. no 401/429 or systematic 5xx/timeout;
5. no provider retries;
6. no protocol/schema parse failure;
7. stable latency and acceptable operational behavior.

The existing `E01/base` runtime setup is used only as a fixed protocol probe;
scenario pass/fail and action quality are not used for choosing the model.
Run one candidate at a time, recording the complete result:

```powershell
$env:SPEC_AGENT_EVAL_OPENCODE_KEY = '<credential supplied outside artifacts>'
$env:SPEC_AGENT_EVAL_OPENCODE_MODEL = 'exact-model-id'
cd backend
.\gradlew.bat evalLiveQualification
```

The qualification artifact is written under
`backend/build/eval-live-qualification/` and contains no credential.

If multiple candidates qualify, select by provider reliability first, then
schema/protocol compliance, then latency stability. Behavioral score is
considered only after the reference baseline exists.

The current OpenCode model catalog is dynamic. The isolated external eval
settings may use an exact paid/non-free model; product database settings keep
their existing free-model product policy.

## 6. Reference Arm A and Candidate B procedure

The current unchanged Candidate B is commit
`6faeb9570510cffce1582b93058bdf32593e4603`. It is `INCONCLUSIVE` on the
historical `mimo-v2.5-free` run and must not be edited.

1. Select and qualify one reference model.
2. Run the old DECISION prompt at `d6ed1a98d30ec01ff82c61dd57f3ad38cd82af29`
   (or a docs/eval-only commit with identical DECISION prompt hashes).
3. Run `evalLiveDiagnostic`: 16 targeted variants × 3 repetitions = 48
   attempts. This is Reference Arm A targeted baseline.
4. Validate provider reliability, at least 90% behavioral completion (about 43
   of 48), complete semantic traces, and zero fake/scripted/fallback use.
5. Determine the case:
   - `DECISION_FIRST` remains material: Candidate B remains a valid hypothesis;
   - Arm A is already highly correct: test B only for non-regression and room
     for improvement;
   - STATE_UPDATE or another earlier layer dominates: stop this B A/B and
     investigate that first fault instead.
6. Only for the first two cases, run unchanged Candidate B with the identical
   model/provider/corpus/repetition/retry/timeout/sampling/instrumentation,
   then compare the two arms.

Candidate B targeted acceptance starts from the new Arm A denominator. The
initial primary recommendation is at least 40% relative reduction in the
DECISION_FIRST wrong-action rate with a meaningful absolute reduction. If Arm
A is already below 10%, use non-regression, stability, and invariant
guardrails rather than applying 40% mechanically.

Secondary requirements are no behavioral pass-rate regression, no new
propagation fault, and no high-risk autonomous behavior. A provider-coverage
shortfall is `INCONCLUSIVE`, not `REJECTED`.

## 7. Full confirmation

Targeted acceptance is required before full confirmation. Then, on the same
reference model and configuration:

1. run Reference Arm A on the original 30 variants × 3 repetitions = 90;
2. run unchanged Candidate B on the same 90 attempts;
3. compare behavioral pass, infrastructure, stability, first-fault matrix,
   CN overreach, resolved re-ask, forbidden capability invocation, forbidden
   RTU, and propagation faults.

Full acceptance requires targeted improvement to generalize, no behavioral or
stability regression, a meaningful DECISION_FIRST reduction, sufficient
provider reliability, and no new high-risk autonomous behavior. Otherwise B is
rejected; do not stack B2.

## 8. Portability subset

Only after reference-model acceptance, run a smaller subset on the reference
model, `mimo-v2.5-free`, and one or two provider/architecture-diverse models.
The recommended scenarios are E01, E07 unresolved/resolved, E17, E19, E22,
and E25. Compare invariant violations, not identical aggregate scores:

- unauthorized autonomous action;
- unresolved conflict bypass;
- resolved-state re-ask;
- capability overreach;
- WAIT misuse;
- schema failure;
- new state-application or decision-input projection fault.

## 9. Provenance minimum

Each baseline/arm artifact must bind:

- git commit and prompt revision;
- prompt hashes by stage;
- corpus identity and variant/repetition set;
- provider and exact model id;
- sanitized endpoint/gateway and UA/client compatibility;
- runtime settings source;
- qualification date/result;
- semantic trace schema and completeness;
- provider health and failure classes.

Credentials are never recorded. The historical mimo artifact is retained as a
`mimo-v2.5-free historical portability benchmark`, not as the main development
gate.

## 10. Current architecture decision

No new model framework is required. Existing external runtime settings,
semantic traces, causal reporting, JSONL artifacts, and the OpenCode gateway
already provide the needed boundaries. The required implementation is limited
to eval/reporting semantics, provenance, the qualification runner, and this
procedure. Production runtime behavior, prompt candidates, corpus,
expectations, scoring, retry, timeout, sampling, policy, and state semantics
remain unchanged.

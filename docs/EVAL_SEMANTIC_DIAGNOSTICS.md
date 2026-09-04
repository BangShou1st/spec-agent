# Semantic diagnostic evidence

The Phase 3B diagnostic path is opt-in (`spec.agent.semantic-trace.enabled`,
default `false`). It observes the existing answer cycle without changing the
request envelope, prompt text, model settings, retry policy, or state
mutation path.

The trace is one `semantic_trace` object per evaluation attempt:

```text
STATE_UPDATE_INPUT
  runtime_request       exact Java → Brain envelope copy
  model_input           exact Python rendered semantic user payload, when returned
  prompt.system/user    SHA-256 only
  semantic_fingerprint  diagnostic-only decision-relevant fingerprint
STATE_UPDATE_OUTPUT
  normalized_output     parsed StateUpdateResult actually consumed
POST_STATE_UPDATE_STATE
  state_projection      projection after patch persistence/application
DECISION_INPUT
  runtime_request       exact Java → Brain envelope copy
  model_input           exact Python rendered semantic user payload, when returned
DECISION_OUTPUT
  normalized_output     parsed decision observation/action actually consumed
POLICY_DECISION         deterministic confirmation/execute/deny outcome
FINAL_RESULT             action, delta, evaluation and call accounting
```

Python emits only response-side semantic diagnostics. The diagnostic payload
is never passed back to `ModelClient` or the provider. Java copies are
recursively sanitized for bearer credentials, token/key/password fields,
secret-like values, and the test sentinel `super-secret-do-not-log`.

The offline `CausalReportGenerator` consumes these traces and produces the
seven first-fault categories plus symptom and repetition-fingerprint tables.
Provider/runtime failures are reported separately from behavioral failures.
When a boundary throws before the full chain exists, the recorder preserves an
explicit `STATE_APPLICATION`, `DECISION_INPUT_PROJECTION`, or failure stage so
the report does not collapse that attempt into an unexplained incomplete trace.

The targeted live task is:

```bash
cd backend
./gradlew evalLiveDiagnostic
```

It requires the same explicit broker/provider environment as `evalLive` and
writes a unique directory under `backend/build/eval-live-diagnostic/`. The
target is the E01, E07 unresolved/resolved, E17, E22-wait, E19, E10, and E25
variant set, with three repetitions per variant (48 attempts at the current
corpus size).
formal baseline remains immutable under `backend/build/eval-live/` and is
identified by commit
`464097cd6ca86a0107ce369214506484dfd57c3f`.

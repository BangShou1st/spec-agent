# Spec Agent V2 Python decision engine (agent-brain)

Stage A bootstrap of the V2 Agent Brain (`docs/v2/PYTHON_AGENT_RUNTIME_BOUNDARY.md`).
The brain owns prompts and model-call orchestration inside one decision cycle;
it never touches the production database and never holds provider API keys —
model inference goes through the Spring internal inference broker.

## HTTP surface

```text
GET  /health
POST /v1/state-updates   # answer/evidence -> grounded claims
POST /v1/decisions       # reflection + planning -> one action proposal
```

All endpoints speak the frozen `contracts/v2` wire contract:
unknown fields and unknown protocol versions are rejected fail-closed.
When `SPEC_AGENT_BRAIN_INTERNAL_SECRET` is set, requests must carry the same
value in the `X-Spec-Agent-Internal-Token` header.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SPEC_AGENT_BRAIN_INTERNAL_SECRET` | *(empty)* | Shared internal token; empty disables auth (dev only) |
| `SPEC_AGENT_BRAIN_MODEL_MODE` | `fake` | `fake` = deterministic offline outputs; `broker` = call the Java inference broker |
| `SPEC_AGENT_INTERNAL_BROKER_URL` | `http://localhost:8080/internal/v1/model-inference` | Broker endpoint used in `broker` mode |

There is deliberately no database driver, no provider SDK, and no retry/fallback
logic in this service.

## Local development

```bash
cd agent-brain
python -m venv .venv
.venv/Scripts/pip install -e ".[dev]"      # Windows Git Bash
.venv/Scripts/python -m pytest             # run the test suite
.venv/Scripts/uvicorn spec_agent_brain.app:app --port 8100
```

## Docker

```bash
docker compose up -d agent-brain   # from the repository root
```

The compose service defaults to `broker` mode against
`http://host.docker.internal:8080` so a locally running Spring backend serves
inference. Set `SPEC_AGENT_BRAIN_MODEL_MODE=fake` to run fully offline.

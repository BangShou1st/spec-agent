# Development Environment

Status: first-version design freeze  
Date: 2026-08-17

## 1. Principle

Use Docker Compose for local infrastructure, but keep the first-version environment intentionally small.

Spec Agent's first-version core depends on stable persistence and deterministic runtime behavior. It does not initially need Redis, MinIO, MySQL, RAG infrastructure, browser automation services, or a message queue.

## 2. First-Version Local Infrastructure

Start with one infrastructure dependency:

```text
PostgreSQL
```

Recommended local service:

```text
service name: spec-agent-postgres
image: postgres:17-alpine
internal port: 5432
host port: 5434
initial database: spec_agent
initial user: spec_agent
```

The host port should avoid collisions with older local projects. If other projects already use `5432` or `5433`, use `5434` for Spec Agent.

## 3. Why PostgreSQL Only

PostgreSQL is enough for the first version because the core runtime needs:

- Project records.
- Route lifecycle state.
- Immutable node lineage.
- Immutable answers.
- Answer patches.
- Context snapshots.
- Agent runs.
- Spec snapshots.
- JSONB for structured patches and trace summaries.
- Recursive queries for root-to-tip lineage.

Adding more infrastructure before these invariants are implemented increases maintenance cost without proving the product.

## 4. Do Not Add Yet

Do not add these in the first backend foundation phase:

### Redis

Do not add Redis until there is a concrete need for background jobs, rate limiting, or ephemeral coordination.

Runtime state must not be stored in Redis. PostgreSQL remains source of truth.

### MinIO

Do not add MinIO until the product supports file upload or persistent binary artifacts.

The first version does not include document upload, RAG, or artifact storage.

### MySQL

Do not add MySQL. The product should standardize on PostgreSQL for lineage queries, JSONB patches, and one database source of truth.

### Browser Runtime Containers

Do not add browser automation containers. The product is not a browser agent.

## 5. Compose Scope

First Compose scope:

```text
docker-compose.yml
└── postgres
```

Optional later services:

```text
redis    only if needed for rate limiting or background jobs
minio    only if file uploads or artifacts become first-version scope, which they currently are not
```

Do not copy old project compose files wholesale. Old projects include services that belong to different product scopes.

## 6. Suggested Compose File

A future first-version `docker-compose.yml` can be shaped like this:

```yaml
services:
  postgres:
    image: postgres:17-alpine
    container_name: spec-agent-postgres
    environment:
      POSTGRES_DB: spec_agent
      POSTGRES_USER: spec_agent
      POSTGRES_PASSWORD: spec_agent_dev
    ports:
      - "5434:5432"
    volumes:
      - spec-agent-postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U spec_agent -d spec_agent"]
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  spec-agent-postgres-data:
```

The actual implementation can refine credentials and profiles, but it should keep the first version minimal.

## 7. Local Configuration

The backend should read database configuration from environment variables or a local profile.

Suggested local variables:

```text
SPEC_AGENT_DB_HOST=localhost
SPEC_AGENT_DB_PORT=5434
SPEC_AGENT_DB_NAME=spec_agent
SPEC_AGENT_DB_USER=spec_agent
SPEC_AGENT_DB_PASSWORD=spec_agent_dev
```

Do not commit real production credentials.

## 8. Development Rules

1. Keep infrastructure project-scoped.
2. Avoid host port collisions with older projects.
3. Do not share databases with WebPilot or ai-collab.
4. Do not reuse old volumes for new schema work.
5. Do not add Redis or MinIO because previous projects used them.
6. Add infrastructure only when a current phase needs it.
7. PostgreSQL is the source of truth for runtime state.

## 9. Phase Placement

Docker Compose belongs to Phase 1: Repository and Backend Test Foundation.

Phase 1 should deliver:

- Spring Boot skeleton.
- Docker Compose with PostgreSQL.
- Flyway migrations.
- Integration-test database setup.
- Health endpoint.
- Architecture-test harness.

It should not deliver model integration, frontend UI, Redis, MinIO, or external tools.

## 10. V2 Agent Brain (Stage A)

The Python decision engine lives in `agent-brain/` and is part of the dev
environment from Stage A (`docs/v2/PYTHON_AGENT_RUNTIME_BOUNDARY.md`):

```text
docker compose up -d          # postgres + agent-brain (broker mode)
```

Local (no Docker) alternative:

```bash
cd agent-brain
python -m venv .venv && .venv/Scripts/pip install -e ".[dev]"
.venv/Scripts/python -m pytest
.venv/Scripts/uvicorn spec_agent_brain.app:app --port 8100
```

Environment defaults:

- `SPEC_AGENT_BRAIN_INTERNAL_SECRET=dev-internal-secret` — shared internal
  token for brain requests and the Spring inference broker.
- `SPEC_AGENT_BRAIN_MODEL_MODE=fake|broker` — fake runs fully offline;
  broker routes model calls through Spring at
  `SPEC_AGENT_INTERNAL_BROKER_URL`.
- `SPEC_AGENT_BRAIN_WORKER_ENABLED=true` — opt-in background V2 run worker.

The brain has no database driver and no provider SDK by design. The
cross-language integration test
(`PythonBrainCrossLanguageIntegrationTest`) requires a running brain on port
8100 and binds Spring to port 18080; it skips automatically when the brain is
not running so the default suite stays green offline.

## 11. Internal Broker Network Isolation

The internal model inference broker (`POST /internal/v1/model-inference`) is
**not a product API**. It exists solely so the Python agent brain can route
model calls through the frozen Java OpenCode transport without receiving
provider credentials.

### Production deployment requirements

- **Network isolation**: the `/internal/**` path must only be reachable from
  the Python agent brain process. In Kubernetes this means the internal
  broker service should be exposed only on a cluster-internal address or
  behind a NetworkPolicy that limits ingress to the brain pod. It must
  never be exposed to the public internet or to browser clients.
- **Shared internal secret**: every request must carry the
  `X-Spec-Agent-Internal-Token` header matching the Spring-side
  `spec.agent.brain.internal-secret` value. The token is validated in
  constant time. Rotate it when any brain instance is compromised.
- **No external traffic**: the broker accepts only pre-validated
  `ModelInferenceHttpRequest` payloads. It does not accept arbitrary
  user-supplied URLs, headers, or prompt content.

### Local development

In local development, both Spring and the Python brain run on
`localhost` and share the default `dev-internal-secret`. This is
acceptable because the machine is single-tenant. The same shared secret
must never be used in production.

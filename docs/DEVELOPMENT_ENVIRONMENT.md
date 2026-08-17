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

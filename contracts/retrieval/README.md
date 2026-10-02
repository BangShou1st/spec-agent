# Shared Python retrieval.v1 — U1–U3 implementation contract

Date: 2026-10-02. Authority: `docs/v2/UNIFIED_PYTHON_RAG_DESIGN.md`.
The service belongs to agent-brain `retrieval`, shared by Project Agent and GA.
Java owns projections, authorization, SQL, pgvector, jobs and final validation.
No Java Ollama provider, direct Python DB client, Chroma or second vector index.
Current Java RAG stays active until explicit U1/U2 qualification/cutover.

## Authentication and scope

All internal routes require the existing X-Spec-Agent-Internal-Token; no public
frontend access. Independent protocolVersion=retrieval.v1; agent-input.v2 and
frozen projection replay do not change. Unknown fields/versions/types reject.
Python requests are bounded to 1 MiB; host/source responses to 2 MiB wire bytes (including vector JSON). Decoded text
and vector limits below; expired requests reject before model/store activity.

Every request carries requestId, workload(kind/id/executionEpoch), scopeGrant
(opaque grantId/version/expiresAt), profileId, indexGeneration and deadline.
Deadline must not exceed grant expiration. Java persists and resolves the
grant's corpus/project/route/source-ref permissions. A self-reported projectId,
userId, permissions, URL, key or source allowlist on requests is forbidden.
Candidates may contain host-owned project/source identities, never authorize
the caller. Each callback rechecks workload type, current epoch, scope/version,
expiry and active index generation. Deletion/retraction/permissions apply
immediately to SQL and final admission, without waiting for embedding rebuild.

## Routes and DTOs

| Direction | Route | DTO |
|---|---|---|
| Java→Python | POST /internal/v1/retrieval/search | SearchRequest → SearchResponse/Failure |
| Java→Python | POST /internal/v1/retrieval/index-batches | IndexBatchRequest → IndexBatchResponse/Failure |
| Java→Python | GET /internal/v1/retrieval/health | RetrievalHealth; service auth, no source content |
| Python→Java | POST /internal/v1/retrieval-store/candidates | CandidateRequest → CandidateResponse/Failure |
| Python→Java | POST /internal/v1/retrieval-store/validate-sources | ValidationRequest → ValidationResponse/Failure |

Schemas in this directory cover search/index/store/profile/failure/health.
Health distinguishes storeReady (retrieval.ready) from ollamaReady: lexical
retrieval stays ready when embeddings are unavailable, while embedding readiness
requires a verified profile and service version. Java DTO parity is a U1 task;
no route is mounted yet.
Response identity must echo requestId/workload/grant/profile/generation/deadline;
the caller rejects any mismatch. Candidate SQL endpoints call the low-level
repository, never memory.search, RemoteRetrievalService or Python search again.
Java may perform final validation directly instead of the optional callback.

Search: HYBRID or SEMANTIC_ONLY. maxItems≤12, maxChars≤12000; memory.search
uses 8/8000. A grant records exact caller limits, not just global upper bounds;
Java rejects returned limits/content above them. max query=4000 Unicode code
points; laneLimit≤48; at most 8 lanes. Java string limits must count code points
at this boundary to match Python (existing canonical source offsets stay UTF-16,
and Python must explicitly convert positions when splitter migration begins).

One candidate RPC carries optional normalized query vector and requested lanes:
route/project/resource lexical/trigram, graph, vector. Java filters every lane
by grant, profile and generation. Python handles RRF k=60, dedup and selection
using Java-issued selectionTier(0..4), authority/source/version/location labels.
Ordering: tier ascending, RRF descending, sourceRef ascending, corpusId for
ties across projects. Similarity never changes scope or promotes authority.
Mandatory refs/content hashes and cross-route admission are host policy.
Future store grants must expose bounded exclusion/tier facts; Python must not
rederive lineage by reading Graph tables. New/old same-corpus parity is a U1 gate.

Final Source DTO carries entryId, corpusId, projectId, originRouteId, sourceRef,
sourceVersion/contentHash, kind/scope/authority/tier, bounded content/location.
Corpus/project groups stay separate. HELP has independent corpus, no project or
route identity; project refs need projectId and ROUTE needs originRouteId.
Java validates text/offsets/version/authority against its saved projection and
generates links. Internal rankScore/lane ranks/vectors never enter the model's
public retrievedContext. No source content may become policy or confirmed truth.

Index batches: INDEX_JOB workload ID equals jobId; current epoch + leaseId +
expectedVersion; ≤16 sources, ≤12000 chars per source, ≤48000 chars per batch.
Java supplies source entry identities/version/hash/location. Python never makes
Graph identity. U0 profile keeps existing host source granularity; nonstructured
splitter replacement is a later explicit version. Document hashes are SHA-256
of UTF-8 projected text. Each returned vector echoes entry/source/version/hash/
location. Java rechecks source currentness and atomic job claim/CAS before commit.
Repeated identical job/batch results replay; changed arguments/profile reject.
Unknown job result does not trigger a Graph mutation or blind write retry.

## First profile

Only OLLAMA_LOCAL / qwen3-embedding:0.6b is admitted by the U0 profile DTO.
Dimensions=1024, document encoding=raw-text.v1, query encoding=qwen-instruct.v1:
`Instruct: <fixed profile queryInstruction>\nQuery: <query>`.
Instruction text is in profile JSON, so changing it changes profileId.
Vectors are finite float values, exactly 1024 dimensions, unit L2 norm within
0.001. Checksum is SHA-256 of IEEE754 float32 little-endian values in their
original vector order (4096 bytes for 1024 dimensions). This matches pgvector
float32 storage and avoids Java/Python decimal JSON formatting ambiguity.
Java must implement the same binary checksum and consume the shared fixture
before activation; nonfinite/overflow values reject before serialization.

profileId hashes canonical sorted-key compact UTF-8 JSON excluding profileId;
it binds provider/model tag+digest/dimensions/query+document strategies/
normalization/truncate/splitter/index schema versions. Digest drift rejects
the profile until a new generation is built and activated. Ollama URL/timeout/
concurrency are deployment configuration, not user-controlled wire fields.
Local service only, no cloud fallback or model pull. 4B is a later independent
quality/resource evaluation, never an automatic alternative.

`langchain-ollama=1.1.0` and `ollama=0.6.3` locked with the existing framework.
LangChain public embed_documents does not forward truncate; U1 needs a thin
OllamaEmbeddings adaptation that calls the same Ollama SDK with truncate=false.
The explicit U0 probe already verifies this approach against the installed
local model. It does not register a production provider or store an index.
Batch=16, embedding concurrency=1 initially; queries get scheduling precedence.
No implicit reranker, query rewrite or LLM summarizer. Request deadline≤grant
expiry, recommended search timeout=15s, index=120s; cold latency is measured
separately. Calls respect remaining deadline and never silently retry queries.

## Failure behavior and storage migration gaps

Ollama unavailable in HYBRID: same Python service queries lexical/trigram/graph
and returns status=VECTOR_UNAVAILABLE, vectorUnavailable=true plus warning.
SEMANTIC_ONLY fails OLLAMA_UNAVAILABLE. No other model or legacy Java retrieval
fallback is used. Python unavailable: GA tool fails; Project first projection
uses mandatory canonical context and records supplementalRetrievalUnavailable.
Existing frozen snapshots replay exactly; no remote call, embedding or re-search.
This additive availability metadata must be frozen before U2 context cutover.

Failure codes: UNSUPPORTED_PROFILE, STALE_SCOPE_GRANT, STALE_WORKLOAD,
SOURCE_VERSION_MISMATCH, INDEX_GENERATION_MISMATCH, OLLAMA_UNAVAILABLE,
RETRIEVAL_UNAVAILABLE, DEADLINE_EXCEEDED, INVALID_VECTOR. Replies contain codes
and requestId, no raw provider errors. Host may explicitly reschedule derived
index work, with persisted attempts/lease/CAS; Python has no unmanaged scan loop.

V40/V41 remain the storage asset. Current schema has project_id NOT NULL,
ROUTE/PROJECT/RESOURCE scope check, UNIQUE(project_id,source_ref), no profile,
corpus generation or persisted claimed embedding job. U1 needs additive host
profile/generation/grant/job metadata and a compatible evolution of the same
retrieval_entries table/uniqueness for generation staging and HELP corpora.
Keep legacy upserts/query paths until migration parity is proven; their unique
key cannot simply be dropped while existing ON CONFLICT SQL still uses it.
No migration is applied by U0. Index generation activation must be atomic;
mixed-profile vectors reject. Cleanup targets derived rows, never canonical
Node/Answer/Route. New requests record engine/profile/generation; rollback only
affects new requests. Two real embedding workers must never run in parallel.

U0 validation covers strict Python DTOs, schemas/fixtures, installed framework
interfaces and one real local embedding smoke; it does not claim Java RPC,
pgvector integration, production migration or 50-query Recall/MRR/P50/P95.


资源分块：source-chunks-request/response schema 是 retrieval.v1 的 additive job DTO。
调用方只传已授权原始来源；Python 必须先访问宿主 projection-grants。
chunk 的 startOffset/endOffset 为原始字符串 zero-based UTF-16 offsets（与 Java String 相同），
text/hash 指向原始连续切片，heading 是独立位置元数据，不拼入正文。
job lease/source version 过期或 canonical 来源变化时拒绝回写；模型没有权限创建 grant。


### Implemented U1–U3 boundaries (2026-10-02)

Python request bodies are bounded to 1 MiB; host/source response bodies to 2 MiB.
`GET /internal/v1/retrieval/health` requires the internal token and reports
independent storeReady/ollamaReady with the verified fixed profile/version.
Lexical readiness may remain true when Ollama is unavailable; semantic-only and
index requests fail explicitly. Health performs no model download or embedding.
The authenticated host `/internal/v1/retrieval-store/health` exposes only storage
readiness/profile, no projects or credentials.

CandidateRequest.maxVectorDistance is a Python-owned cosine-distance threshold
(default 0.50). Java executes a parameterized scoped pgvector primitive and
rejects values outside 0..0.65; it does not implement ranking/fusion. The cutoff
was calibrated on the reviewable fixture, not an independent human-labelled set.
Exact lexical/trigram candidates remain available. Embedding profile does not
change when a retrieval cutoff changes; encoding/splitter/model changes do.

Curated HELP uses only three shipped classpath documents, a fixed host corpus,
HELP_CHUNK identity, nullable project and no route. The same projection/index
jobs and retrieval_entries table are reused. Source validation compares canonical
content and business authority; HELP cannot confirm user/project facts. RESOURCE
chunks retain raw version/hash and UTF-16 offsets. Structured Node/Answer/Claim
units retain business identity; oversize units (>12000 UTF-16 chars) fail closed
for supplemental retrieval rather than being silently truncated. Mandatory
Runtime input remains governed by its existing separate freeze contract.

V51 adds a SKIPPED raw projection marker for excluded resource sources. Rejected
raw content/metadata are never retained in that marker. Invalid derived rows are
retired in bounded batches, without altering canonical nodes/answers/patches.
Generation activation waits for pending splits and all current live rows;
expired leases, stale authority, versions and profile/head mismatches fail closed.

Review corpus: evaluation/chinese-queries.v1.json has 52 AI-authored fixtures.
Human labels remain pending. APPROVED requires reviewer/time metadata and every
query labelled HUMAN_REVIEWED; metrics cannot set these values automatically.

Human review: `evaluation/HUMAN_REVIEW.md` lists all canonical fixture sources and 52 queries. Labels remain pending; only actual human review may promote the dataset to APPROVED, with reviewer identity/time and per-query HUMAN_REVIEWED status.

## Local service settings extension (2026-10-02)

The existing `retrieval.v1` profile and project defaults remain unchanged. `retrieval.v2`
uses the same workload/grant/source/version/head fences with a Java-approved SHA-256
profile from `embedding_profiles`. Its immutable semantic identity includes provider,
service identity, model tag/digest, detected dimensions, query/document strategy,
normalization, truncate policy, splitter and index schema version. Dimensions are
1–4096; vector length, finiteness, normalization and float32 checksum must match the
host-approved profile. Same-dimension different models are separate profiles.

Authenticated Java `/internal/v1/retrieval-store/embedding-profile` accepts the original
Search/IndexBatch envelope and returns only approved semantics, safe config and service
revision. `/embeddings` accepts that same envelope; Java derives the texts from the
authorized query or immutable source batch, captures the encrypted connection's key,
checks response cardinality/order/index/dimensions and rechecks grant/source/deadline
before returning vectors bound to requestId/profileId. Neither provider key nor arbitrary
caller-supplied URL/text is accepted by Python. LangChain `Embeddings` is the algorithm
interface; local Ollama remains Python-owned, remote protocol/authentication Java-owned.

HELP rebuild uses the existing pending vector columns and one vector store. Successful
preparation remains READY until explicit activation; activation verifies source versions,
complete vectors and expected head version in one transaction. Failure preserves the old
active head/vectors. Candidate settings do not change current retrieval; no automatic
project migration occurs. Connection-only timeout/batch changes do not change profile
identity. These configuration checks do not establish retrieval quality and do not
promote the independent evaluation datasets above.

Pre-release split: evaluation/chinese-queries.v1.json is DEVELOPMENT_CALIBRATION (distance .65→.50), never independent acceptance even after human review. evaluation/acceptance-candidate.v1.json has 68 unexecuted AI-authored independent candidates; config/code/data hashes in acceptance-freeze.v1.json, review table in ACCEPTANCE_HUMAN_REVIEW.md. Pending review is rejected before Python startup or indexing; candidate report output is separate. No AI tool promotes HUMAN_REVIEWED/APPROVED. Strict zero-hit diagnostics do not prove generated-answer refusal quality.

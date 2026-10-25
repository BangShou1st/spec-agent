---
status: Historical
authority: Historical Reference Only
---

# PR-22-REVIEW-FIX-01 — 验证记录(verification)

- 环境:worktree `E:\project\spec-agent-pr22fix`(基线 `25902a5` + 本修复,`core.autocrlf=false`)。
- 隔离库:容器 `spec-agent-iso-pr22fix-pg`(pgvector/pgvector:pg17,宿主端口 127.0.0.1:55460,
  库 `spec_agent_p3b_live_pr22fix_20261025`,账户 `spec_agent_iso_pr22fix`,服务器自报
  `172.17.0.4/32:5432` 经守卫核验;预建 `vector`/`pg_trgm`)。未连接任何生产/开发库。
- 模型:全部 Fake/Scripted Provider(`FakeModelProvider` 本机 HTTP fixture),零真实供应商调用;
  `SPEC_AGENT_MODEL_GATEWAY=fake`、`SPEC_AGENT_MODEL_INFERENCE=fake`(test 档)。
- C16 跨进程解释器:`scratch/tmp/rag-d3-final/venv`(CPython 3.11.14,editable
  `spec_agent_brain` + uvicorn),导出为 `SPEC_AGENT_GA_TEST_PYTHON`。

## 1. 红→绿复现(修复前基线)

| 命令 | 结果 |
|---|---|
| `testOffline --tests ModelConnectionsOfflineTest` | 新用例 **failure**:`Expecting code to raise a throwable`(P1 复现) |
| `testDatabaseIntegration --tests ModelConnectionsIntegrationTest.{两条新用例}` | **2 failures**(P1 集成级复现;临时 stash 修复后重跑确认) |

## 2. 修复后全量验证

| 任务(命令) | completed | failures | errors | skipped | 判定 |
|---|---|---|---|---|---|
| `./gradlew bootJar testOffline` | 489 | 0 | 0 | 0 | PASS |
| `./gradlew test -PdatabaseIntegration=true` | 2680 | 0 | 0 | 41 | PASS |
| `./gradlew testDatabaseIntegration -PdatabaseIntegration=true` | 1007 | 0 | 0 | 41 | PASS |

与 release-readiness-01 基线对照:testOffline 487→489、test 2676→2680、
testDatabaseIntegration 1005→1007,差值均为本次新增的 4 个回归用例(2 条集成用例同时被
`test` 与 `testDatabaseIntegration` 选中);skipped 均保持 41,逐类分布与 P2 勘误后的分类一致。

Flyway:隔离库 `flyway_schema_history` 109 行全部 success(本轮真实应用)。
C16 跨进程:`C16RetrievalAwaitGenerationEndToEndTest` 1/0/0 真实执行(非 skip)。

## 3. skipped 性质(AGENTS.md §11.2)

两任务各 41 skipped 为同一批显式环境/夹具门控(`assumeTrue`/`@EnabledIfEnvironmentVariable`:
live 模型、浏览器 UI、SSE 断连、跨语言 broker、RAG live/评测、Git HTTPS fault),逐类:

```text
GaExecutionCoordinatorIntegrationTest  23   (a3 原文误写 25,见 pr-review.md 勘误)
SharedRetrievalIntegrationTest         11
GaHostRpcIntegrationTest                5
RebuildLeaseIntegrationTest             1
GitHttpsFaultAcceptanceTest             1
合计                                    41
```

## 4. 未在本轮本地执行、由远端 CI 覆盖的部分

brain pytest、前端三件套、契约静态检查、e2e、cross-language:本修复仅触及
`backend` Java `modelsettings` 域,未触及 agent-brain/前端/契约;这些由新 HEAD 的 GitHub CI
(backend/brain/frontend/e2e/cross-language 等 job)验证,结果记入 pr-review.md。

---
status: Historical
authority: Historical Reference Only
---

# A3 — 全链路验证结果与 CI 证据(RELEASE-READINESS-01)

- 执行日期:2026-10-25。代码对象:worktree 干净检出 `bc1140e`(= `efbe11a` 代码基线 +
  A0 纯文档提交;生产代码与 `efbe11a` 字节一致)。
- 环境:本轮一次性隔离资源——容器 `spec-agent-iso-20261025-postgres`(pgvector/pgvector:pg17,
  端口 127.0.0.1:55450,专用账户 `spec_agent_iso_20261025`,库 `spec_agent_p3b_live_rr01_20261025`,
  服务器自报端点 `172.25.0.2/32:5432` 已按守卫协议核验;容器内已预建 `vector`/`pg_trgm` 扩展,
  账户被授予 superuser 以等价 CI 服务容器身份)。未连接任何生产数据库;模型网关
  `SPEC_AGENT_MODEL_GATEWAY=fake`、`SPEC_AGENT_MODEL_INFERENCE=fake`,零真实供应商调用;
  本地 Ollama 仅由真实进程类测试(c16 检索链路)使用。
- 解释器:`scratch/tmp/rag-d3-final/venv`(CPython 3.11.14,已验证版本;editable 安装重定向到
  worktree 的 agent-brain),导出为 `SPEC_AGENT_GA_TEST_PYTHON`。

## 1. 本轮本地执行结果(per-task 分离计数)

| 任务 | completed | failures | errors | skipped | 判定 |
|---|---|---|---|---|---|
| `testOffline` | 487 | 0 | 0 | 0 | PASS |
| `test`(需隔离 DB) | 2676 | 0 | 0 | 41 | PASS |
| `testDatabaseIntegration` | 1005 | 0 | 0 | 41 | PASS |
| `testCrossLanguage`(broker brain @8100,fake broker 回调 8099) | 2 | 0 | 0 | 0 | PASS |
| brain pytest(`agent-brain`,`pytest -q`) | 367 | 0 | – | 0 | PASS |
| 契约静态 pytest(`tools/tests`) | 239 | 0 | – | 0 | PASS |
| 前端 typecheck(`npm run typecheck`) | – | 0 | – | – | PASS |
| 前端单测(`npm run test:unit -- --run`) | 987 通过 | 0 | – | 1 文件级 skip | PASS |
| 前端生产构建(`npm run build`) | – | – | – | – | PASS |

### skipped 的性质(AGENTS.md §11.2 核验)

`test`/`testDatabaseIntegration` 各 41 个 skipped 为同一批 live/external 门控用例,源码显式
`@EnabledIfEnvironmentVariable` / `assumeTrue` 门控,属环境/夹具要求而非缺陷:

- `GaExecutionCoordinatorIntegrationTest`(23):live-configured-model、浏览器 UI、SSE 断连等
  真实模型/浏览器场景(RUNBOOK 声明的独立证据体系,见其「真实模型、HTTPS 和浏览器 gate 的
  独立证据」说明);
  *(勘误 2026-10-25,pr-22-review-fix-01:原文写 25,与总数 41 矛盾;重跑全量后 XML 逐类
  核实为 23——23+11+1+5+1=41,总数始终正确,错在分类分项。见
  `docs/acceptance-evidence/pr-22-review-fix-01-20261025/pr-review.md`。)*
- `SharedRetrievalIntegrationTest`(11)+ `RebuildLeaseIntegrationTest`(1):`SPEC_AGENT_RAG_EVALUATION`/
  `SPEC_AGENT_RAG_LIVE_TEST`/`SPEC_AGENT_RUNTIME_QUALIFICATION` 门控的 RAG live/评测/运行时资格;
- `GaHostRpcIntegrationTest`(5)live 框架/Git HTTPS、`GitHttpsFaultAcceptanceTest`(1)。

前端 1 个文件级 skip 为 vitest 配置既有行为(CI 同版亦 skip)。

### Flyway 真实应用核验(V104–V110)

隔离库 `flyway_schema_history`:**109 行迁移全部 success=true**,其中 V104/V105/V106/V107/
V108/V109/V110 七个迁移逐行核验为 success;V17 历史性缺席(与 c16-real-integration 记录一致)。
迁移专项测试(IsolatedMigrationAcceptance、MigrationV94V95Compatibility、MigrationV99ModelBindingBackfill)
含 CREATE DATABASE 场景库与中断回滚场景,全部通过。

### 执行轨迹备注(环境问题,非代码缺陷)

本轮前两次运行失败均由本地环境装配引起,修正后通过:(1) worktree 检出时 `autocrlf=true`
改变冻结资产字节哈希 → 以 `core.autocrlf=false` 重建 worktree;(2) 隔离库守卫要求服务器自报
地址带 CIDR 形式(`172.25.0.2/32`);(3) V40 `CREATE EXTENSION` 与夹具 `session_replication_role`
需要容器超级用户(CI 服务容器同权);(4) C16 跨进程验收需先 `bootJar`;(5) testCrossLanguage
需 `SPEC_AGENT_CROSS_LANG_PORT=8099` 与 broker brain 的回调 URL 一致。全部为环境装配项,
未修改任何产品代码与测试。

## 2. CI 证据核验(远端,可信消费)

`gh run view 40152643427`(`efbe11a` = dev tip = 本轮代码基线,CI「full validation」):

```text
backend-independent: success     contracts-static: success
validation-mode: success         brain: success
frontend: success                backend: success
eval-baseline: success           e2e: success
cross-language: success          validation-summary: success
```

- e2e job(bootRun 真实后端 + fake gateway 的既有 E2E)为 success——A3 清单的
  「已有 E2E 的可信 CI 证据」引用此项,不本地重复。
- 其余各 job 与本地执行互为印证:CI backend job = `test testDatabaseIntegration`,CI brain job
  = `pytest -q`,CI contracts-static = `tools/tests`,CI frontend = 三件套,CI cross-language =
  testCrossLanguage。
- CI 无消费者的任务(`testNonLive`、`evalP3ARetrieval`、`evalLive*`、`qualifyGaNative*`、
  `zenUpstreamContract`):本轮未执行、不计入通过声明,维持 SYSTEM-E2E-01 audit P1-4 记录。
- `bc1140e`(A0 文档提交)推送后 CI 会重跑;纯 docs 变更不影响上述结论,PR 创建时以新 run
  结果为最终引用。

## 3. 结论

本轮批准范围内(`testOffline`/`test`/`testDatabaseIntegration`/`testCrossLanguage`/brain
pytest/前端三件套/E2E CI 证据引用)全部通过,per-task 计数与 skip 性质如上;未纳入范围的任务
未被声称为通过。

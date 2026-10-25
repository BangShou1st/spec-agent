---
status: Runbook
authority: Current Authority
---

# A2 — dev→main PR 就绪性检查报告(RELEASE-READINESS-01)

- 日期:2026-10-25。本报告只做检查与准备,**不执行合并**。授权范围:创建 PR;merge 必须由
  owner 另行决策。
- 检查时点:`origin/main` = `742870d`,`origin/dev` = `efbe11a`,本地 dev tip = `bc1140e`
  (A0 文档修正 +1 提交,待随本轮证据一起 push)。

## 1. 集成资格(检查于 2026-10-25)

| 检查 | 命令 | 结果 |
|---|---|---|
| main 是否 dev 祖先 | `git merge-base --is-ancestor origin/main origin/dev` | **是** |
| dev 领先 main 提交数 | `git rev-list --count origin/main..origin/dev` | **38** |
| main 独有提交 | `git rev-list --count origin/dev..origin/main` | **0**(无分叉、无冲突面) |
| 本地 dev vs origin/dev | — | 本地 +1(A0 文档,纯 docs),无落后 |
| 两分支树差 | `git diff --stat origin/main origin/dev` | 345 文件(+48122/−4062),即 38 提交的累积内容 |

结论:dev 是 main 的严格延伸,技术无冲突。合并模式必须为 **merge commit,禁止 squash**
(AGENTS.md §3);squash 会把 38 个提交哈希挤出 main 历史,永久污染 ahead/behind 信号。

## 2. 本次验收对应的精确 commit SHA

- **代码验收基线**:`efbe11a`(SYSTEM-E2E-01 审计基线 = A3 全链路测试运行对象;
  其 CI run `40152643427` 10 job 全绿,见 A3 报告)。
- **A0 文档修正**:`bc1140e`(4 个 docs 文件,不触生产代码,不影响测试有效性)。
- **PR head**:本轮证据/报告提交后的 dev tip(创建 PR 时以实际 push 的 SHA 为准)。
- PR 描述中引用验证证据时,一律写上述 SHA,不写「最新」。

## 3. V104–V110 迁移审查(顺序/兼容性/失败处理)

迁移链位置:V103(C16 就绪代际)之后、当前 tip 之前,共 7 个:

| 版本 | 内容 | 审查结论 |
|---|---|---|
| V104 | `model_providers.legacy_source` 列 + 唯一部分索引 + `model_connection_legacy_imports` 导入溯源表(无 FK,防止用户删除的导入连接被重启复活) | 纯增量;部分索引限定 `legacy_source IS NOT NULL`,与既有行兼容 |
| V105 | `model_connection_capabilities` 能力证明表(PK 含 config_revision,CHECK 枚举) | 纯增量,无存量改写 |
| V106 | `agent_runs.model_connection_reference` 列 + 不可变触发器(改写抛 `RUN_MODEL_CONNECTION_IMMUTABLE`,SQLSTATE 23514) | 与 V100 模型绑定指纹同一 fail-closed 模式;首次 NULL→值合法、同值改写合法 |
| V107 | `model_provider_settings.active_model_same_org` 约束替换为复合 FK(先 DROP 再 ADD 同名) | 同事务内替换,失败整体回滚;新约束语义更严(活动连接必须同 org 存在) |
| V108 | 退役内置 OPENROUTER 身份(`preset→CUSTOM`)+ 清洗无 STRUCTURED VERIFIED 证明的继承验证标记 + 收紧 preset CHECK 枚举 | **数据改写型**;方向为收紧(fail-closed):被清洗的行需重新验证才能激活,不放宽任何安全位 |
| V109 | 对 `legacy_source IS NOT NULL` 的导入行重新应用 V108 清洗谓词 | 幂等补丁(谓词含 `validated_revision IS NOT NULL`);弥合 V108→V109 之间 ApplicationRunner 后导入的窗口期,见迁移文件头注释 |
| V110 | `ga_model_continuations` GA 延续 sidecar 表(UNIQUE(source_run_id,source_call_id)、tool_call_ids 正则 CHECK、CASCADE) | 纯新增表,无存量数据风险 |

- **顺序依赖**:V108 依赖 V105 的能力表;V109 依赖 V104 的 `legacy_source`;V106/V107 依赖
  V104 之前的既有表;V110 独立。当前文件名顺序满足依赖,无环。
- **失败处理**:七个迁移均为单事务 DDL/DDL+DML,Flyway 失败即整体回滚(与 c16-real-integration
  B 节在 V98–V103 上实测的回滚语义一致)。
- **真实执行证据**:本轮 A3 的 `test`/`testDatabaseIntegration` 在隔离库
  `spec_agent_p3b_live_rr01_20261025` 上由 Spring/Flyway 真实应用全链 V1→V110(见 A3 报告
  的 flyway_schema_history 核验);迁移专项测试(IsolatedMigrationAcceptance、
  MigrationV94V95Compatibility、MigrationV99ModelBindingBackfill)在该库上通过。
- **升级窗口提示(供发布 runbook 引用)**:V108/V109 清洗的是「继承的验证标记」而非凭据;
  升级后未带能力证明的 CUSTOM 连接需要重新验证才可激活——这是预期行为,不是数据丢失。

## 4. tree-hash 等价验证方案(合并执行时人工执行,本轮不执行)

```bash
# 1) PR 合并前:确认 PR head = 已验收 dev tip
git fetch origin
git rev-parse origin/dev^{tree}          # 记为 T_dev(= 已验收 tree)
git rev-parse origin/main^{tree}         # 应不同于 T_dev(否则无可合并内容)

# 2) owner 批准后以 merge commit 合并(禁 squash):
gh pr merge <N> --merge

# 3) 合并后立即核验:main 的 tree 必须等于 T_dev(dev 无 main 不含的内容 ⇒ merge 不引入新内容)
git fetch origin
git rev-parse origin/main^{tree}         # 必须等于 T_dev
git diff --stat origin/main origin/dev   # 必须为空

# 4) 任一不满足 ⇒ 立即按 runbook 回滚 main 到合并前 SHA,保留现场排查;
#    不得用 push --force 修复,不得重复合并掩盖差异。
```

依据:dev 领先 38 提交且 main 无独有提交,merge commit 的结果树必然等于 dev tip 树;
`git diff --stat` 为空 + tree hash 相等是「两分支持有等价内容」的机器可验证判据
(AGENTS.md §3:内容相等是树哈希,不是提交数)。

## 5. PR 必须列出的内容(创建时写入 PR 描述)

1. **验证证据**:本目录 A3 全链路结果(本地隔离环境,SHA `efbe11a`/`bc1140e`)+ CI
   `40152643427`(efbe11a,10 job 全绿)引用。
2. **已知风险**:P1-2 动作族执行器缺口(UPDATE_NODE/CONNECT_NODE/CREATE_ROUTE 提案可审批、
   确认后必失败);多 Worker 总开关/ga-recovery 默认关闭;SemanticRanking 影子代码未接线;
   CI 未消费 testNonLive/evalP3ARetrieval/evalLive*(见 SYSTEM-E2E-01 audit P1)。
3. **人工待办**:A1 RAG 68 条人审(不阻塞本 PR,但阻塞生产发布);A4 DB 中断演练对最终
   jar 的复验;PR 合并本身的 owner 决策。
4. **发布限制**:本 PR 的合并仅代表**代码集成就绪**;生产发布仍需 RUNBOOK 的生产切换授权、
   旧 rebuild writer 排空、A1/A4 关闭,两者判定不得混用。
5. **分支保护**:不修改 main、不强推、不自动合并、不绕过保护;PR 创建后停留在
   open 状态等待 owner。

## 6. 就绪结论

- 技术就绪:ancestry/无分叉/迁移审查/测试证据均满足创建 PR 条件。
- 流程就绪:PR 创建已获本轮任务授权;合并未获授权,保持 open。
- 阻断项:无(创建层面)。合并与发布层面的人工决策项见 §5。

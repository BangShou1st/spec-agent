---
status: Historical
authority: Historical Reference Only
---

# A0 — 文档真实性修复说明与差异清单(RELEASE-READINESS-01)

- 日期:2026-10-25。提交:`bc1140e`(4 个 tracked 文档);另 1 个未跟踪 WIP 目录内的
  delivery.md 就地修正,不改变其跟踪状态,由 owner 处置提交时机。
- 原则:保留原始历史证据、冻结资产和审计轨迹;通过更新当前文档或追加 superseded 说明建立
  事实对应关系;不借文档修复之名修改生产逻辑(本轮零生产代码改动)。
- 失真清单来源:SYSTEM-E2E-01 审计 P0-2(docs/acceptance-evidence/system-e2e-01-20261025/audit.md)。

## 差异清单

### 1. RUNBOOK 默认引擎段落(audit P0-2a)

- 文件:`docs/v2/GLOBAL_ASSISTANT_LANGCHAIN_RELEASE_RUNBOOK.md`
- 修正前:「当前默认配置 `SPEC_AGENT_GLOBAL_ASSISTANT_ENGINE=java-legacy.v1` /
  `SPEC_AGENT_RETRIEVAL_ENGINE=java-hybrid.v1`;上述变量未设置时亦保持旧默认」
- 事实:application.yml 默认已为 `langchain-ga.v1`(spec.global-assistant.engine)与
  `python-rag.v1`(spec.agent.retrieval.engine);`java-legacy.v1` 决策引擎已从代码删除
  (`RunDispatcher` 显式拒绝);`java-hybrid.v1` 检索引擎仍可选但非默认
  (`HybridRetriever` 白名单含两者)。
- 修正后:默认值改为实际的 `langchain-ga.v1`/`python-rag.v1`,并附「2026-10-25 事实校正」
  引用块说明演变过程与「代码默认≠生产发布」边界。
- 连带修正:回滚章节「将新请求引擎恢复为旧默认」补注——回滚目标只能是仍支持
  `langchain-ga.v1` 的较早成套版本,不能再回到已删除的旧 Java 决策引擎。

### 2. C16 WAIT 真实数据库验收状态(audit P0-2b)

- 文件:`docs/v2/C16_WAIT_V4.md`(末段)、`docs/v2/README.md`(C16_WAIT_V4 索引行)
- 修正前:「database migration, real transaction races, process recovery and E2E …
  remain NOT RUN in this change」「生产适配器和真实数据库验收仍未完成」
- 事实:`docs/acceptance-evidence/c16-real-integration-20261021/`(同日 2026-10-21 更晚,
  commit 40a5672/24ab221)已在隔离 PostgreSQL + 真实 Python 检索进程 + 真实本地 Ollama 上
  完成 V98–V103 迁移链、跨进程竞争/崩溃恢复、E2E 全链路验收,并修复 2 个 P0 生产缺陷。
- 修正后:C16_WAIT_V4.md 保留「原始变更中 NOT RUN」的历史事实,追加「已被
  c16-real-integration-20261021 取代」的事实对应与证据链接;README 索引行同步改写,
  并保留「生产部署验收仍未执行」的真实边界。
- 连带修正:`docs/v2/C16_RETRIEVAL_READINESS_ADAPTER.md` 状态行
  (INTEGRATION_NOT_VERIFIED/RELEASE_BLOCKED → 六档隔离环境验收状态)与「尚未验收的
  发布门槛」章节(更新为「发布门槛与验收状态」,首版 NOT RUN 断言按原文保留为历史,
  仍然有效的 writer 混跑禁令与生产部署门槛单列)。

### 3. adapter delivery.md 交付状态(audit P0-2c)

- 文件:`docs/acceptance-evidence/c16-production-adapter-20261021/delivery.md`
- 修正前:四档状态 CODE_COMPLETE/OFFLINE_VERIFIED/INTEGRATION_NOT_VERIFIED/RELEASE_BLOCKED
  与全表 NOT RUN 断言。
- 事实:同上,已被 c16-real-integration-20261021 取代。
- 修正方式:头部追加「Superseded(2026-10-25 回写)」引用块,指明取代关系与证据链接;
  四档状态行旁标注「已按上文 Superseded 说明被取代」;**原文主体一字不改**,保持当轮
  交付状态的原始记录。该文件所在目录为未跟踪用户 WIP,修正仅落工作区,不代为提交。

## 一致性检查(修正后执行)

- 相对链接有效性:5 处新引用(`../acceptance-evidence/c16-real-integration-20261021/delivery.md`
  ×3、C16_RETRIEVAL_READINESS_ADAPTER.md、c16-production-adapter delivery.md 链接)目标均存在。
- 引用 SHA 有效性:8fb328b9、22b4398、40a5672、24ab221 均为仓库真实 commit。
- 全库残留扫描:`java-legacy` 在其余文档中仅存于历史语境描述(单引擎删除过程、PHASE2
  线程绑定说明、审计本身),无「默认值」类失真残留;「真实数据库验收仍未完成」类陈述
  无残留。
- application.yml 实际值复核:spec.global-assistant.engine 默认 `langchain-ga.v1`、
  spec.agent.retrieval.engine 默认 `python-rag.v1`、spec.agent.action-eligibility.mode
  默认 `enforced`(与 audit §1 一致,本轮未改动任何 yml)。

## 未纳入本轮的事项(边界声明)

- 6 个无 summary/delivery 的证据目录补齐(audit P1-3):涉未跟踪 WIP,属 owner 处置范围。
- arch-quality-01 verification.json 未来时间戳(audit P1-7):冻结验收资产,按约束不修改,
  保留审计记录作为事实对应。
- docs 全量 frontmatter 补齐:治理规则的渐进事项,不属本轮三处失真范围。

---
status: Historical
authority: Historical Reference Only
---

# PR-22-REVIEW-FIX-01 — P2 处理与 PR/CI 状态(pr-review)

## 1. P2 数字差异的真正原因

`a3-verification.md` skipped 分类把 `GaExecutionCoordinatorIntegrationTest` 写为 25,使分项和
25+11+1+5+1=43 与申报总数 41 矛盾。本轮在修复代码基线上重跑全量 `test` 与
`testDatabaseIntegration`,对 JUnit XML 逐类统计 skipped:

| 类 | XML 实测 skipped | a3 原文 |
|---|---|---|
| GaExecutionCoordinatorIntegrationTest | **23** | 25(错) |
| SharedRetrievalIntegrationTest | 11 | 11 |
| GaHostRpcIntegrationTest | 5 | 5 |
| RebuildLeaseIntegrationTest | 1 | 1 |
| GitHttpsFaultAcceptanceTest | 1 | 1 |
| **合计** | **41** | 43 |

结论:**总数 41 从来是对的,错在单个分类的分项数**(GaExec 多写了 2)。skip 性质描述无误
(全部为 `assumeTrue`/`@EnabledIfEnvironmentVariable` 显式环境/夹具门控;该类 23 个 skip 中含
一个 4 参数 fault-injection 方法的展开)。已按真实证据在 a3 原文就地勘误(保留原文事实,附
勘误注),未改动任何测试或执行结果。原始 XML 不入库(证据治理规则),统计脚本与数字见
verification.md。

## 2. PR #22 描述更新

- 原写 "dev 领先 main 38 提交";审查时实际 40;本修复推送后以 GitHub 实时 `main...dev`
  计数为准(见下)。
- 验证证据一节补充本修复后的本地全量计数(testOffline 489/0/0、test 2680/0/41、
  testDatabaseIntegration 1007/0/41)与本证据目录。
- 旧 CI 证据(`efbe11a` run 40152643427、`25902a5` run 40165818065)保留为历史基线,最终
  合并以新 HEAD 的 CI run 为准。

## 3. Review 5641753298 逐项回应

- **P1(STRUCTURED 降级后运行时门禁放行)**:复核成立并已修复。根因、调用链、修复边界与
  回归见 audit.md / fix-summary.md。修复点:`CustomProviderSettingsService.connectionSettings`
  在新 Run 路径(`revision == null`)要求当前 (org, UUID, config_revision) STRUCTURED=VERIFIED,
  与激活门禁同源;冻结绑定路径按设计契约不重复施加门禁;未采用"清除 validated_revision"
  方案以免误杀冻结 Run。回归覆盖审查建议的全部场景(新 GA Run、Agent 解析 fail-closed、
  冻结语义保持、恢复、身份/org/revision 不继承、无凭据泄漏)。
- **P2(评审元数据)**:如上节,25 系笔误,实测 23,总数 41 正确;a3 已勘误;PR 描述统计
  已按实时数据更新。
- **CI 范围**:同意"合并裁决以最终 HEAD 检查完成为准"。本修复推送后新 HEAD 的 CI 结果
  记录于下节;历史 10/10 仅作旧基线证据。

## 4. 最终 HEAD CI(以推送后实际查询为准)

修复代码 HEAD `906ee3c`(origin/dev fast-forward `25902a5..906ee3c`,无强推):两个触发
run 均 completed/**success**,各 10/10 job 全绿:

```text
run 40170691663(2026-10-25,branch dev,head 906ee3c)
  contracts-static / validation-mode / brain / frontend / backend-independent /
  backend / eval-baseline / cross-language / e2e / validation-summary = success
run 40170687660:同 head,同 10/10 success
```

历史基线:`efbe11a` run 40152643427、`25902a5` run 40165818065(各 10/10),仅作旧证据保留。

## 5. 生产发布阻断事项(不变,与 review 一致)

RAG 68 条人工审阅与首轮阈值批准、DB 中断演练对最终发布 jar 复验、生产切换授权——均为
生产发布(hard launch)前置条件,不因本修复关闭,本 PR 合并仅代表代码集成就绪。

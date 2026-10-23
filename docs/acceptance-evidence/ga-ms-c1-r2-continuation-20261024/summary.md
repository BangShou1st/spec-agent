# GA MS-C1-R2 Continuation 安全收口验收摘要

日期：2026-10-24（Asia/Shanghai）

## 结论

P1-A（历史迁移模型身份）、P1-B（HTTP 入口 v1）、P2（混合签名）全部修复；
MS-C1-R1 既有行线闭环、跨 Run 恢复与 pending writes 兼容回归全部保持通过。

## 缺陷与修复

| 编号 | 缺陷 | 修复 | 复现方式 |
|---|---|---|---|
| P1-A | 迁移来源未比对 provider/model/settingsRevision | `recordedContinuationSource` 同一查询获取来源执行行身份并与当前冻结绑定精确比对；Custom UUID 包含在 revision 字符串中 | 不同 Provider/Model/Revision/Custom UUID 参数化负例，全部拒绝且 sidecar 计数为 0 |
| P1-B | HTTP 模型入口仍接受 v1 raw extraContent | `invoke/precheckStream/stream` 边界强制 v2 | 真实 HTTP 测试：v1/v1+raw/流式 v1 均 400，0 预算消耗、0 账本、0 Provider 调用 |
| P2 | 部分签名的多工具首轮通过、第二轮失败 | `extract()` 检测到 any+unsigned 混合即抛出 | 混合形态单测在工具执行前失败；全签名/全无签名保持合法 |

## 测试结果

- Java `testOffline`：403 completed / 0 failed / 0 skipped。
- Java `testDatabaseIntegration`（隔离库 `spec_agent_p3b_live_ms_c1_r2_20261024`）：
  992 completed / 0 failed / 41 skipped；跳过项为外部服务/实时提供商/跨语言
  broker 显式 opt-in 套件。
- Python `pytest`：344 completed / 0 failed / 0 skipped。
- 前端 `npm run build`（vue-tsc + vite）：通过。

本轮不调用真实 Gemini（策略性 NOT_RUN）。

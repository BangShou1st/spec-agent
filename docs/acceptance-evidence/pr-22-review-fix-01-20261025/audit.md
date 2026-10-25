---
status: Historical
authority: Historical Reference Only
---

# PR-22-REVIEW-FIX-01 — P1 根因审计(audit)

- 执行日期:2026-10-25。审查对象:PR #22 Review `5641753298` 的 P1 判定。
- 代码基线:`25902a5`(= PR #22 HEAD = origin/dev)。独立 worktree
  `E:\project\spec-agent-pr22fix`(`core.autocrlf=false`),主工作区用户 WIP 未触碰。
- 结论:**P1 真实成立,已按最小边界修复并回归覆盖**。

## 1. 审查判定逐条复核

| Review 断言 | 复核结果 | 证据位置(修复前 `25902a5`) |
|---|---|---|
| 1. 复验失败把 STRUCTURED 写为 TEMPORARILY_UNAVAILABLE(同 revision) | 成立 | `ModelConnectionCapabilities.check()` 捕获 `ModelProviderException`/`IllegalArgumentException` 后 `record(row, capability, "TEMPORARILY_UNAVAILABLE")`,UPSERT 键为 (org_id, provider_id, config_revision, capability) |
| 2. `validate()` 失败时不清除历史 `validated_revision` | 成立 | `ModelProvidersService.validate()` 仅在 STRUCTURED=VERIFIED 时 `markValidated`;失败路径无清除。`ModelProviderRecord.validated()` = `validatedRevision == configRevision` 仍为 true |
| 3. `connectionSettings()` 只查 `row.validated()`,不查当前 STRUCTURED | 成立 | `CustomProviderSettingsService.connectionSettings(id, revision)` 原仅检查 preset/configured/validated/(冻结 revision 一致性);NATIVE_TOOLS 只用于能力标志,STRUCTURED 未检查 |
| 4. `resolveCustom(true, selectedId)` 经此路径放行 UUID 连接 | 成立 | `GaExecutionModelTargetResolver.resolveCustom(true, selectedId)` → `requireRuntimeSettings(selectedId)` → `connectionSettings(id, null)` |
| 5. Project Agent `ActiveModelTargetService` 同路径 | 成立 | `targetForProvider(CUSTOM, selectedId)` → `custom.requireRuntimeSettings(selectedId)`;`RoutingModelInferenceGateway.resolveBoundTarget` 对未绑定 Run 走 `activeTarget()` 同路 |
| 6. `requireActivatable()` 已要求当前 STRUCTURED 证明,两门禁结论可分歧 | 成立 | `ModelProvidersService.requireActivatable()` 同时要求 `record.validated()` 与当前 (org, UUID, revision) 的 STRUCTURED=VERIFIED("defense in depth" 注释) |

## 2. 真实调用链(修复前)

新 Run 准入(三条入口,全部汇聚于 `connectionSettings`,全部只看过期 validated 标志):

```text
[新 GA Run]  GaExecutionPreparation → GaExecutionModelTargetResolver.resolveActive()
             → resolveCustom(true, selectedId) → CustomProviderSettingsService.requireRuntimeSettings(UUID)
             → connectionSettings(id, null)        ← 只查 row.validated() ⇒ 放行(缺陷)
[新 Project Agent Run] RoutingModelInferenceGateway.complete(runId)
             → resolveBoundTarget:无冻结引用 → currentTargetOrNull()
             → ActiveModelTargetService.activeTarget() → requireRuntimeSettings(selectedId)
             → connectionSettings(id, null)        ← 同上
[未绑定推理]  CustomInferenceGateway.resolve() → requireRuntimeSettings()
             → connectionSettings(activeProviderId, null)  ← 同上
```

冻结绑定路径(设计上刻意不重复施加能力门禁):

```text
GaExecutionModelTargetResolver.requireFrozen / 三个 GA 传输 / RoutingGaNativeInferenceGateway
ActiveModelTargetService.targetForConnectionReference("custom-provider:<uuid>:<rev>")
→ requireRuntimeSettings(String) → connectionSettings(uuid, frozenRev)  ← 只做绑定一致性
```

`requireFrozen` javadoc(GaExecutionModelTargetResolver.java:103-110)明确:"刻意不在此重复施加
协议/配置门禁……验证状态的中途失效会把一个已被合法接受的 Run 判死——那不是绑定漂移,而是
重复门禁"。**这排除了"复验失败时清除 validated_revision"的修复方案**:清除会使冻结路径的
`row.validated()` 检查失败,破坏冻结绑定语义。

## 3. 复现记录(红→绿)

- 单元级(`ModelConnectionsOfflineTest.structuredDowngradeFailsNewRunAdmissionButNotFrozenReferences`):
  修复前失败 `Expecting code to raise a throwable`——`requireRuntimeSettings(UUID)` 在
  STRUCTURED=TEMPORARILY_UNAVAILABLE 下未拒绝;修复后通过。
- 集成级(`ModelConnectionsIntegrationTest` 新增 2 例):修复前 2 failures,修复后 15/15 通过。
  场景:UUID CUSTOM 连接 VERIFIED+激活 → 同 revision 模拟供应商复验失败(fake.status=429)
  → 能力降级但 validated_revision 保留 → 修复前新 GA Run 解析 Ready、Project Agent 取得运行时
  配置(缺陷复现),修复后全部失败关闭;冻结引用不受影响;复验成功后恢复准入。

## 4. 并发/事务一致性说明

新 Run 准入现在与激活门禁读取同一权威能力行(`model_connection_capabilities`,按
org_id+provider_id+config_revision 键控,`OrganizationConfigurationScope` 提供组织隔离)。
"检查通过后供应商恰好劣化"的 TOCTOU 窗口与激活门禁既有语义一致:该窗口内创建的 Run 在真实
HTTP 调用时按供应商错误失败,与现行 provider 中断行为相同,不引入新的状态源。

## 5. 反证检查(未发现其他绕行入口)

对 `requireRuntimeSettings*`/`connectionSettings` 全量调用方盘点(见 fix-summary.md 附表):
UUID CUSTOM 的新 Run 路径只有 `requireRuntimeSettings()` 与 `requireRuntimeSettings(UUID)` 两个
入口,均传 `revision=null` 且都收敛到本次修复点;`revision != null` 的调用方全部是冻结绑定
路径。legacy 单例(`legacyRuntimeSettings()`)无能力行,维持其既定语义不变。

# ARCH-QUALITY-01 · 前后对比(实测指标)

status: Historical(本轮快照,2026-10-25)
authority: Historical Reference Only

基线 `a30de0c`;所有数字来自 `git diff --numstat`、实测 import 图与测试报告 XML。

## 1. 核心指标表

| 指标 | 重构前 | 重构后 |
|---|---|---|
| 确认的重复实现点(validateResponse 语义相同实现) | 3 | 1 |
| 确认的重复实现点(密钥脱敏 suffix) | 4 | 1 |
| 重复的 hasKey / equalsNullable | 2 / 2 | 1(委托)/ 1(唯一) |
| `ModelProvidersService.update` 冗余布尔比较项 | 1 | 0 |
| 新增公共实现数量 | 0 | 2(`GaResponseContractValidator`、`ApiKeyMasking`) |
| 被删除的重复逻辑分支(生产代码) | — | 5(两份 validateResponse、三份 suffix 本体、各一份 hasKey/equalsNullable 本体、一处冗余布尔项) |
| assistant.api → model.provider 跨包引用 | 1(GaNativeModelBroker:200) | 0 |
| 包级循环依赖 | 0 | 0 |
| 新增架构违规 | 0 | 0(新守卫已拦截修复前状态,修复后全绿) |
| 本轮新增抽象层级 | 0 | 2(均为叶子工具类,无层级嵌套) |
| 新增保护性测试 | 基线 | +11(校验器 6 + 脱敏边界 4 + ArchUnit 守卫 1) |
| Anthropic/Responses validateResponse 拒绝路径覆盖 | 0 测试 | 6 测试(经唯一实现直接覆盖) |
| 生产代码净行数 | — | -80(修改文件 +28/-108,新增 2 文件 +101) |
| 测试代码净行数 | — | +168 |

## 2. 依赖关系变化

- 编排层对协议实现层的耦合:`assistant.api ──> model.provider.GaChatCompletionsAdapter`(具体类 + 每调用新建实例)→ `assistant.api ──> model.contract.GaResponseContractValidator`(契约层纯静态)。方向与既有 `agentLayerShouldNotDependOnProviderImplementationPackages` 的设计意图对齐,并由新守卫机器化。
- 脱敏规则的权威点:4 个类各自私有 → `ApiKeyMasking` 单点(四个调用方仍在各自服务内,不新增跨模块依赖)。

## 3. 测试隔离收益

- `GaResponseContractValidatorTest`/`ApiKeyMaskingTest` 均为纯 JVM 单测,无 Spring/数据库/网络启动即可锁定安全语义(此前 Anthropic/Responses 的校验语义只能靠传输层集成测试间接覆盖,且实际无覆盖)。
- 新 ArchUnit 守卫在测试编译期即可阻断 assistant.api 对 provider 包的任何回归(实测先红后绿)。

## 4. 取舍记录

- **保留**:三个 Adapter 的 `validateResponse` 公共方法(一行委托)而非删除并改 6 个调用点——公共 API 不变,调用点零改动,风险最小;Kilo/OpenCode 的 `mask` 与 OpenCode 的 `OpenCodeSettingsStatusMask`(语义刻意不同);来源守卫 4 处(类型/文案差异,统一收益低于复杂度)。
- **不做**:Kilo/OpenCode 历史行 masked_suffix `""` 的数据归一(需迁移);Python 侧全部 P1/P2(范围控制,见 audit §5/§8)。
- 未引入任何新框架/注册表/反射;两簇改动均无行为语义变化(论证见 refactor-plan.md)。

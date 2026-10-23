---
status: Historical
authority: Historical Reference Only
date: 2026-10-23
---

# Model Service V2 本地交付记录

结论：`MODEL_SERVICE_V2_PARTIAL`。本轮代码、确定性协议测试、隔离数据库测试和浏览器配置验收已完成；没有执行真实供应商推理，不将其记为通过。Java 托管供应商续接引用的进一步迁移尚未完成。当前规则见 [Model Service V2](../../v2/MODEL_SERVICE_V2.md)。

## 实现结果

| 项目 | 本轮结果 |
| --- | --- |
| 改造前 | 多行 Custom 管理与旧单例运行时混用；页面只有一个 Custom；更换模型 ID 不作废行验证；自定义目录把所有模型算作免费；OpenRouter 有完整独立业务链 |
| 改造后 | 两个内置入口 Zen/Kilo；任意多个 Custom UUID；共用模型选择、冻结身份、Gateway 和协议适配器；页面使用共享 ProviderCard/摘要/模型字段/按钮样式 |
| 多 Custom | 独立创建、编辑、发现、验证、激活、重命名、删除；未保存弹窗不卸载当前卡片；模型不在目录仍可手动输入并验证 |
| OpenRouter 清理 | 删除专用枚举、运行时端口、单例服务/仓储/控制器、推理网关、目录资格实现、前端组件及状态仓；保留迁移代码、旧数据和历史报告；作为普通 Custom 使用 |
| 历史迁移 | 旧凭据按原组织/表身份解密，再以新 UUID 身份加密；原端点/模型保留；独立迁移标记防重复导入及删除后复活；旧多行验证缺少准确模型证明时要求重测 |
| 模型身份 | 两个 Agent 共用当前选择。Project Run 原子持久化引用与指纹，GA 使用现有冻结目标；Zen/Kilo 与 Custom 均测试了切换后保留原目标及跨实例重新解析；配置本身改变时失败关闭 |
| 删除 | 当前连接不能删除，服务端和数据库外键双重约束；删除其他连接不改写运行历史，旧运行再次调用明确失败，不回退到其他模型 |
| 能力资格 | CHAT、STRUCTURED、NATIVE_TOOLS 分别记录；UUID/组织/配置版本隔离；改模型、Key、URL、协议作废；重命名不作废；超时/429/坏响应不推断永久不支持 |
| 通用验证 | `NativeToolRoundTripVerifier` 驱动相同无副作用工具闭环；供应商传输只负责 wire 差异；无需改 Python/Checkpoint/Graph 即可接入新的同协议 Fake Provider |
| 权限 | Java 保留凭据、组织校验、运行/租约/预算/工具授权。能力证明不授予产品写权限。新 UUID 不继承旧 URL/model 静态工具登记 |
| 费用 | “仅免费”保留为可选目录筛选；无价格元数据为未知；输入、输出及其他列出的计价项均明确为零才进入筛选；无付费 fallback |
| SDK | 继续复用现有 Zen/Kilo 共享库与薄 HTTP Adapter；没有引入 Spring AI、LiteLLM、Node 模型服务或另一套 Agent 循环 |

## 协议与兼容边界

Custom Chat Completions 支持普通聊天、项目结构化输出及经过完整往返验证的原生工具。GA 当前为 buffered，一次响应完成后发出正文片段，不能称为供应商 SSE。Custom Responses 沿用项目适配器，GA 的文本和原生工具仍明确拒绝。Anthropic Messages 不能满足当前项目 JSON_OBJECT 门禁，不因协议枚举存在而宣称可激活。

Zen/Kilo 特有传输、身份头、免费凭据选择与严格 SSE 不重写。其现有工具资格规则仍保留；本轮共用的是行为验证流程和模型绑定门禁，没有伪称它们已全部迁入 Custom 的 UUID 资格表。

Gemini R3 的签名/extra_content 回传、Broker 认证和 SafeCheckpointCodec 基线保留；此次为依赖该基线的传输测试调整了运行时端口 mock，使其真实执行冻结修订校验，原协议断言没有删减。供应商私有续接材料尚未完全迁出 Python/Checkpoint，进一步 Java continuation-reference 持久化需要独立历史兼容与恢复验收。

历史 OpenRouter Run 的旧内置身份不能转换为推理目标时失败关闭；已存消息、运行、配置来源不删除。没有从当前设置猜测旧 Run 的丢失绑定。

## 测试与证据

| 检查 | completed / failures / skipped | 说明 |
| --- | --- | --- |
| Java `testOffline` | 396 / 0 / 0 | 审计过的离线集合；不代表全部集成测试 |
| Java 定向 `test` | 459 / 0 / 0 | model、modelsettings、architecture、模型设置隔离、GA 目标解析；专用 PostgreSQL |
| Python | 21 / 0 / 0 | Global Assistant 与 Checkpoint 回归 |
| 前端 | 988 / 0 / 1 | 987 通过；真实产品流回放由 `SPEC_AGENT_GA_LIVE_REPLAY=true` 门禁控制，本轮未配置 |
| 前端类型检查/构建 | 通过 | 仍有既有 chunk-size 提示，不是构建失败 |
| 样式复用检测 | 通过 | 检测结果为空；视觉上已检查桌面与 iPhone 14 |
| 真实供应商推理 | 未执行 | 新底层真实模型请求 0；不报告为 PASS |

两个 Java 任务包含重叠案例，不相加计算独立通过数；定向 `test` 不是全量 `testNonLive` 或 `testDatabaseIntegration`。

新增 5 个确定性测试类共 39 个案例：连接离线 10、价格元数据 1、协议 Harness 8、三提供商传输 Harness 11、隔离数据库集成 9。另增加一个默认不执行的通用 live qualification 类，按连接 UUID/模型/四次预算 opt-in。

迁移 11 个现有 Java 测试文件、重写注册表/编辑器/状态测试以适配 UUID 及共享 UI；原 OpenRouter 元数据筛选和专用卡片测试随业务入口移除，安全意图迁到通用价格、真实协议资格、冻结与权限测试。Gemini 签名专项 7、Kilo 原生传输专项 11、Zen 主传输专项 37 均通过；其他 Zen/Kilo 凭据、冻结、流式与架构专项也在上述定向 `test` 内。没有因框架迁移而跳过相关安全测试。

浏览器使用 browser-skill，操作的是 `127.0.0.1:15173` 的隔离验收构建：检查两个内置入口、已有多个 Custom 后继续添加、草稿取消保留底层卡片、目录免费筛选、目录外手动模型、三项独立验证、激活 C 后切回 B、删除 C 保留 A/B、B 改模型后资格清空、重新验证、重命名后当前名称与资格保持。桌面与 iPhone 14 的卡片、表单、按钮及滚动区已查看截图。会话在验收完成后停止。

隔离容器 `spec-agent-model-v2-acceptance`，数据库 `spec_agent_p3b_live_model_v2`，宿主端口 55438。没有连接或修改 5434 的业务数据库。日志、截图和完整测试报告只保存在 `scratch/model-service-v2/` 或构建目录，没有提交二进制证据。

## 接入新服务及未覆盖事项

同协议服务：新增 Custom → 填协议/地址/Key → 选择或手动输入模型 → 保存 → 测试 → 激活。无需新增 Provider 枚举、Python 模型适配、Checkpoint 分支或 Graph 行为。不同协议、供应商真实私有扩展、GA Responses/Anthropic 或新的供应商 SSE 语义仍需要适配开发与独立资格，不能仅添加一个名称就宣称支持。

OpenRouter 的 Base URL、聊天/目录/Key 限额接口、Bearer 鉴权和免费固定模型验收约束已记录在 [自定义 API Runbook](../../runbooks/custom-model-api-runbook.md)。本轮没有供应商 Key 入库/入日志，没有真实调用预算消耗，没有费用回退。

整体仍需：实际免费模型的 L4 验收；Gemini/Zen/Kilo 的新一轮真实网络回归；供应商续接材料完全迁往 Java 的恢复兼容；内置工具资格配置后续向通用持久化资格收敛。本轮确定性通过不替代这些门禁。

## Git 交付

在 `dev` 本地提交；不 push、不操作 main、不创建 PR。提交包含本轮模型服务改造及其依赖的既有 R3 续接基线；保留其他既有未提交修改和验收材料。原始 D3/D4 冻结标签与证据没有改写。最终本地提交 Hash 由交付回复提供。建议先完成真实免费模型资格再请求 owner 单独授权 push/release。

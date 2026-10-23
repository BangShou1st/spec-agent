---
status: Canonical
authority: Current Authority
date: 2026-10-23
---

# Model Service V2

本规范限定模型设置与调用边界，不改变 Graph、Answer、路线、工具授权或 Python Agent 的业务协议。
在模型连接身份、内置提供商和验证语义上，本规范优先于 MODEL_GATEWAY.md 的旧阶段描述。

## 连接与调用

内置入口只有 OpenCode Zen、Kilo Gateway。OpenRouter 没有独立 Provider 枚举、设置端点、状态仓或推理网关；它是普通 Custom API 的一个例子。品牌不是协议，新中转服务接入已支持协议无需新增 Provider 类。

自定义连接使用现有 `model_providers`，每条连接有 UUID、组织、名称、协议、端点、加密凭据、模型、配置版本。当前选择仍由组织级 `model_provider_settings` 唯一持有；两个 Agent 共用该选择。Zen/Kilo 保留共享库与自己的配置聚合，统一到相同的选择与运行时快照端口，不复制共享库协议。

Project Agent：`ActiveModelTargetPort → 冻结运行身份 → RoutingModelInferenceGateway → 协议/共享库传输`。
Global Assistant：`GaExecutionModelTargetResolver → ga_executions 冻结目标 → Java Broker → 原生传输`。
Java 唯一持有供应商凭据。Python 只编排推理与工具，不持有 Key、不访问供应商 API。

自定义运行引用为 `custom-provider:<UUID>:<configRevision>`；内置 Project Run 使用 `builtin:OPENCODE_ZEN` 或 `builtin:KILO` 引用，并继续用指纹校验该内置配置的完整身份。Project Run 原子保存引用与既有 SHA-256 指纹；GA 将自定义引用保存在既有 settingsRevision，内置沿用提供商及配置修订绑定。后续调用按冻结身份重新解析并校验，不随当前连接切换；配置本身改变时仍失败关闭。引用不是授权凭据，每次仍受组织、运行状态、租约和现有 Broker 权限门禁约束。

修改端点、Key、协议、模型 ID 递增版本并作废能力；重命名不作废能力。更新和激活锁定对应连接行。当前连接必须切换后才能删除，数据库外键同样禁止删除当前连接。删除其他连接保留运行引用及历史，旧运行再次调用时明确失败关闭，不切换 Provider。

历史仅指纹 Project Run 没有可恢复的 UUID，继续使用原有指纹门禁；不猜测旧绑定。历史 OpenRouter 运行因内置身份移除而失败关闭，可读历史保留。

## 发现、费用与能力

目录用于发现，不是允许名单；目录省略模型、空目录或不支持目录时，用户仍可填写模型 ID 并进行真实验证。鉴权、网络和限流错误保持可见。费用未知不能被解释为免费；只有目录明确给出输入、输出及其他计价项全为零时进入“仅免费”筛选。筛选不构成服务商计费担保，不增加付费 fallback。

真实资格验收（L4）只接受两类免费证明：目录明确为零计价，或该轮 owner 显式出具的免费额度声明（记录在验收证据中，并由请求硬上限约束）。声明绝不由模型名称、`:free` 字样或历史价格推断。目录省略价格时筛选与资格都保持“未知”，不静默当作免费。

`model_connection_capabilities` 按组织、UUID、配置版本分别保存 CHAT、STRUCTURED、NATIVE_TOOLS。模型和协议属于该版本的身份，变更后不能继承结果。缺记录为 UNTESTED，结果为 VERIFIED、UNSUPPORTED 或 TEMPORARILY_UNAVAILABLE。网络、429、鉴权、任意 400 或坏响应不推断为永久不支持；宿主尚未实现的适配能力才明确 UNSUPPORTED。

一次完整 Chat Completions 验证最多四次推理请求：普通聊天一次、项目结构化决策一次、原生工具往返两次。探测只有无副作用的 `probe_echo`，不会执行产品工具。每项单独记录，普通聊天成功不证明结构化输出或工具成功。不重试、不换模型、不换 Provider。原生工具验证通过也不会扩大 Runtime 实际授权。

## 实际协议边界

| 协议/路径 | 项目文本与结构化输出 | GA 文本 | GA 原生工具 | 流式 |
| --- | --- | --- | --- | --- |
| Custom Chat Completions | 单独验证 | 可用 | 完整闭环验证后可用 | 当前 GA 为 buffered，不能宣称 SSE |
| Custom Responses | 单独验证 | 当前明确拒绝 | UNSUPPORTED | 项目侧沿用适配器契约 |
| Anthropic Messages | 无项目 JSON_OBJECT 契约，不能激活项目 | 当前明确拒绝 | UNSUPPORTED | 不因枚举存在而宣称实现 |
| Zen / Kilo | 保留共享库及冻结传输 | 保留既有路径 | 保留当前资格规则及安全门禁 | 沿用严格 SSE |

Zen 的可信元数据、Kilo 的原有工具资格配置仍是内置路径的准入规则；本轮没有把它们伪装为新 UUID 资格。通用传输测试同时覆盖两者。Custom 新 UUID 使用持久化能力资格，旧精确 URL/model 登记只用于兼容旧单例运行，不能跨连接授权。

## 历史迁移与续接边界

旧 OpenRouter 单例通过启动迁移转为普通 Custom，固定保留其真实端点与模型。AES-GCM 密文必须先在旧表/组织上下文中解密，再绑定新表/UUID 加密；不能 SQL 复制密文。迁移标记独立于连接，用户删除导入连接后重启不会复活。V37 已有 Custom UUID 按原创建身份识别并保留。旧表只作为迁移来源及必要历史兼容数据，不再有 OpenRouter 业务服务或接口。

旧多行验证曾遗漏“模型 ID 变更作废”，V108 将没有新版本结构化证明的旧行标为需重新验证。保留配置、端点、Key 和历史，不能把旧资格当作当前模型证明。

导入一律不继承验证状态。旧单例的 `validated_revision` 描述的是旧身份，无法表达 V105 之后的 `(org_id, provider_id, config_revision, capability)` 证明，因此 `LegacyModelConnectionMigrator` 只复制配置版本，`validated_revision` / `validated_at` 写空，由重新验证重新取得。V108 只在升级那一刻执行一次，而导入是每次启动都运行的 ApplicationRunner，因此升级后新导入的行不受 V108 覆盖；V109 对已导入且缺少当前修订结构化证明的行补做同一清理，凭据、端点、模型与版本不变。激活门禁同时要求 `validated` 标记与当前修订的 `STRUCTURED=VERIFIED` 能力记录，任一缺失即失败关闭：没有任何路径能在缺少当前证明时把连接变成可激活。

MS-C1（2026-10-24）已把供应商私有续接状态迁入 Java：协议行线升级为
`ga-model-inference.v2`，Python/Checkpoint 只携带消息级不透明
`continuationRef`（格式 `gacr-<uuid-v4>`）。Java 在模型调用账本同事务内提取、
AES-GCM 加密并保存最小续接材料（当前仅 Gemini
`google.thought_signature`，签名上限 1024 字符），随后返回通用引用；下一次调用
按 Run/Thread/组织/提供商/模型/设置修订/ToolCall ID 校验后由协议适配器注回
`extra_content`。旧 `ga-json.v1` Checkpoint 仍可读取：Python 在宿主读取边界通过
`MIGRATE_CONTINUATION` RPC 换取真实 Java 引用，迁移幂等且不改写历史行；新写入
为 `ga-json.v2`，不再包含 raw thought signature。历史 `ga_execution_calls.result`
中的 D4-R3 v1 明文续接材料按历史证据受控保留，不因新写入脱敏而改写。

## 测试复用

- L1：UUID 隔离、配置作废、冻结绑定、组织与凭据安全。
- L2：通用 Fake HTTP Provider 与协议参数化；同一 `NativeToolRoundTripVerifier` 驱动 Zen、Kilo、Custom 的真实生产传输，覆盖往返、错误工具、429、不重试、SSE 终止边界。
- L3：保留 Gemini 签名、Zen 身份头与冻结报文、Kilo 凭据及 SSE 专项；通用流程不再只存在于 Gemini 测试。
- L4：`ModelConnectionLiveQualificationTest` 从环境读取连接 UUID/模型/四次上限；外部服务双重 opt-in，先核实目录免费。默认 CI 不读本地 Key 文件，不做实时推理。
- L5：隔离 PostgreSQL、两个 Fake API、实际加密/设置/资格/Java 网关，以及 browser-skill 的产品流程验收。编译或单次探测不代表整轮 PASS。

本轮继续使用薄 HTTP Gateway 与既有共享库。未引入 Spring AI、另一套 Agent 循环、LiteLLM 或 Node 模型服务。

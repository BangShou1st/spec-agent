# Backend Structure Guide

> 2026-09 结构整理后的 Java 后端归属说明。目标：同一功能的代码集中在同一个业务模块内维护；
> 分类方式统一；目录层级有实际职责含义。改造的可复现迁移脚本：`tools/backend-structure-migrate.py`。

## 顶层布局

```
backend/src/
├── main/java/com/specagent/
│   ├── SpecAgentApplication.java
│   ├── workspace/            # 工作区域（导航分组，内部边界受架构测试约束）
│   │   ├── project/          #   项目聚合、运行时状态查询、ProjectController
│   │   ├── route/            #   路线生命周期、命令服务、路线读模型（含 lineage）、CommandExecution
│   │   ├── node/             #   节点（澄清问题单元）
│   │   ├── answer/           #   不可变回答记录
│   │   ├── context/          #   ContextSnapshot 构建、RequirementState 推导
│   │   ├── patch/            #   AnswerPatch 恢复检查点
│   │   ├── graph/            #   画布命令、撤销/重做、工作区投影查询、GraphController
│   │   ├── spec/             #   规格快照、需求状态查询、Spec/RequirementStateController
│   │   └── profile/          #   需求画像（RequirementProfile）
│   ├── agent/                # Agent 运行编排
│   │   ├── protocol/         #   跨语言 wire DTO（与 Python brain 共享，纯 DTO）
│   │   ├── decision/         #   决策引擎 seam（本地/远端实现）、模型输出词汇（AgentPlan 等）
│   │   ├── gates/            #   反思/落地门禁（ContextGuard、SpecGroundingGate 等，可读仓库）
│   │   ├── action/           #   动作执行器 + 资格计算（ActionEligibilityEvaluator 等）
│   │   ├── policy/           #   提案策略评估（纯评估，无 run 编排）
│   │   ├── ranking/          #   语义动作排序
│   │   ├── runtime/          #   run 管线：AgentRun*/RunWorker/各 cycle 服务/continuation/提案执行
│   │   ├── runevent/         #   run 事件流（SSE 进度）
│   │   ├── snapshot/         #   AgentInput 快照投影（冻结输入构建）
│   │   ├── broker/           #   内部模型推理 broker（Python brain 回拨入口）+ brain 链接配置
│   │   └── api/              #   Agent run HTTP 面（Controller + 请求/响应 DTO）
│   ├── assistant/            # 全局助手（原 globalassistant）
│   │   ├── conversation/     #   会话/线程/消息/PendingTurn 持久化与生命周期
│   │   ├── runtime/          #   GlobalAssistantRuntime、turn 分发、上下文组装、SSE 流、摘要编排
│   │   ├── model/            #   brain 适配：prompt 渲染、decision 解析/校验、GlobalAssistantContext
│   │   ├── tool/             #   工具目录 + capability 适配器（Project*/Skill* Capability）
│   │   └── api/              #   HTTP 面
│   ├── model/                # 模型交互
│   │   ├── contract/         #   中立 seam：ModelInferenceGateway、推理 DTO、流式回调、
│   │   │                     #   RuntimeSettingsPort、ModelProvider 身份枚举、ModelPrompt
│   │   └── provider/         #   实现：协议适配器、OpenCode 传输、各 provider 网关、路由网关
│   ├── modelsettings/        # 模型配置管理（平铺：custom/opencode/openrouter/provider 的
│   │                         #   设置服务、凭据仓储、探针、Controller 同属一个功能）
│   ├── skill/                # Skill 运行时（导入/注册/激活/资源/本地镜像）
│   ├── connection/           # 连接管理（+ credentials/ 子包：凭据材料隔离）
│   ├── mcp/                  # MCP（domain/provider/runtime/transport；config/persistence 并入根）
│   ├── capability/           # Provider 中立能力抽象（平铺）
│   ├── retrieval/            # Memory/RAG（根包为共享词汇+EmbeddingGateway port）
│   ├── web/                  # 共享 HTTP 错误映射（ApiExceptionHandler、GatewayErrorAdvice）
│   ├── common/               # 真正共享的基础件（错误内核、Ids/Json/Hashes、出网策略、健康检查）
│   └── testing/              # 测试专用的生产 Bean（FakeModelInferenceGateway，test profile 选择）
├── main/resources/           # application.yml、Flyway 迁移、builtin-skills
├── test/java/com/specagent/  # 测试（包结构镜像 main）
└── eval/java/com/specagent/  # 独立评测 source set（com.specagent.eval + com.specagent.trace）
```

## 新代码放哪里（判断顺序）

1. **属于某个业务能力** → 对应模块包。工作区功能进 `workspace.<域>`；agent 编排进 `agent.*`；
   全局助手进 `assistant.*`；模型配置进 `modelsettings`。
2. **跨语言 wire 类型**（Java/Python brain 共享 JSON）→ `agent.protocol`，纯 DTO，禁止依赖
   任何 Service/Repository/模型层（架构测试强制）。
3. **模型 seam 类型**（中立推理 DTO/端口）→ `model.contract`；任何具体 provider HTTP 细节 →
   `model.provider`。provider 实现禁止反向依赖 agent 协议（架构测试强制）。
4. **评测代码** → `src/eval/java`（eval source set）。生产 bootRun classpath 永远看不到它；
   评测任务 `evalBFast` / `evalRetrievalFast` / `evalScenario` / `evalLive*` /
   `eligibilityShadowReplay` 均从该 source set 取类。
5. **测试专用但需在 Spring 启动时可见的 Bean** → `com.specagent.testing`（如
   FakeModelInferenceGateway；由 test profile + `@ConditionalOnProperty` 选择）。
6. **真正共享的基础能力**（错误内核、通用工具、出网策略）→ `common`；**禁止**新建
   `common/helpers/manager/utils` 大杂烩包。
7. **HTTP 错误映射**（跨模块异常 → 稳定错误码）→ `web`。业务异常放在它所属的业务模块里。

## 模块内部子包规则

- 小模块**平铺**（Controller、Service、Repository、DTO 同包）：project、answer、patch、node、
  profile、modelsettings、capability 全部平铺。
- 大模块只保留有实际职责的内部子包（见上图），**不使用** api/application/domain/infrastructure
  全套分层。
- Controller 与它服务的功能同模块：小模块平铺，大模块放 `api/` 子包（agent、assistant）。

## 架构门禁（`com.specagent.architecture`）

- `packagesAreFreeOfCycles`：**模块级（第一层包）零容忍**，任何环直接失败。
- `workspaceSubPackagesAreFreeOfCycles`：workspace 内部子包零环，唯一豁免是
  **project↔route**（真实聚合耦合：项目持有激活路线状态、路线命令校验项目归属；
  在 SliceAssignment 中合并为一个 slice 并留有注释）。不要往 workspace 内部新增循环。
- `subModulePackagesAreFreeOfCycles`：agent/assistant/model/retrieval/skill/mcp/connection
  内部子包零环（modelsettings 平铺无子包，connection 仅 credentials 子包）。
- `sliceRulesMatchRealPackages`：防门禁空匹配——每个 slice pattern 必须命中真实包。
- 其余方向规则：runtime kernel（workspace+common）不依赖 model/agent；decision 不碰
  Repository；runtime 不碰模型；broker 不碰 provider 实现/仓库/凭据；capability 自包含；
  Controller 不碰 Repository/模型/凭据；`web` 是顶层叶子，任何模块不得反向依赖它；
  MCP SDK 类型只留在 mcp 模块内。

## 已知遗留（后续项，本轮不动）

- `workspace.project`↔`workspace.route` 的相互依赖：可通过"路线归属校验端口下沉"拆开，
  但会新增一层 Port/Adapter，本轮按指令不做。
- `AnswerCycleService`、`AgentInputSnapshotBuilder`、`GlobalAssistantRuntime`、
  `UndoRedoService`、`HttpOpenCodeZenTransport` 等大文件保持核心执行流程不动；
  若需拆分状态所有权/事务/异步顺序，单独立项。
- `RunAttributionLookupPort`（snapshot 消费、runtime 实现的窄端口）是为解开
  snapshot↔runtime 环新增的唯一接口；后续同类场景沿用该模式。

## 验证入口

```bash
# 全量非实时测试（确定性；绝不带 SPEC_AGENT_BRAIN_WORKER_ENABLED=true）
SPRING_PROFILES_ACTIVE=test SPEC_AGENT_MODEL_GATEWAY=fake SPEC_AGENT_MODEL_INFERENCE=fake \
  ./gradlew.bat cleanTest test

# 确定性评测（B-fast，CI 门禁）与检索评测
./gradlew.bat evalBFast
./gradlew.bat evalRetrievalFast

# 跨语言门禁（需 broker 模式 brain，见 build.gradle.kts 注释）
./gradlew.bat testCrossLanguage

# 评测影子回放
./gradlew.bat eligibilityShadowReplay -PeligibilityArtifacts=<results.jsonl;...> -PeligibilityOutput=<dir>
```

live 评测（`evalLive*`）需要真实模型与外部账号配置，仅在显式授权时执行；本仓库默认
永远不把它们接入 CI。

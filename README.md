# Spec Agent

Spec Agent 是一个**面向需求澄清与规格沉淀的图式 Agent 工作台**。

它不是普通聊天机器人。用户从一个模糊想法开始，通过提问、回答、分支、恢复、冲突处理和规格生成，把零散想法逐步变成**可追溯、可恢复、可验证**的需求知识。

> 核心原则：**模型负责推理，Runtime 负责约束、历史和持久化。**

---

## 现在它能做什么

当前主干已经实现 Graph V2 与 Agent Runtime 的核心链路，主要包括：

- 用 Graph 表达需求探索过程，而不是把全部历史塞进一段聊天记录。
- 一个问题一个不可变 Answer，历史回答不会被原地覆盖。
- Answer 会进入结构化状态更新，并通过 Patch / checkpoint 形成可恢复的处理链。
- 支持路线分叉、历史路线恢复、replacement、软删除与 lineage 隔离。
- Agent 可以基于当前路线与上下文继续提问、更新状态、生成规格。
- 规格快照带来源引用，可以追溯到 Node、Answer、Patch 与 ContextSnapshot。
- 未处理完成的回答会阻止规格生成，避免“回答已保存但状态还没更新”时产生错误结果。
- 支持 Python Agent Brain，通过 Java 内部 broker 使用模型能力，但 Python Brain 不直接访问数据库。
- 支持 Capability / Skill / MCP，Agent 决定“做什么”，Capability Runtime 决定“怎么做”。
- Runtime 对动作资格、来源引用、路线隔离、历史恢复、外部副作用等进行确定性校验。
- 后端已建立 **0 package cycle** 的 ArchUnit 架构门禁。

---

## 核心运行链

理解这个项目，先记住这一条主链：

```text
用户操作
→ Java Runtime 接收命令
→ 持久化不可变事实
→ 创建 AgentRun
→ 构造冻结的 AgentInputSnapshot
→ Python Brain 推理
→ 返回结构化 Action / Patch / Artifact Proposal
→ Java Runtime 校验
→ 持久化结果与 checkpoint
→ 更新 Graph / RequirementState / SpecSnapshot
```

以“用户回答一个问题”为例：

```text
提交 Answer
→ Answer 落库
→ STATE_UPDATE
→ 生成 AnswerPatch
→ checkpoint 成功
→ canonical requirement state 更新
→ DECISION
→ 继续提问 / 等待用户 / 结束
```

这里最重要的边界是：

> **LLM 不能直接改数据库，也不能自己决定历史事实。**

模型只能提出结构化建议；最终能不能执行，由 Runtime 的确定性规则决定。

---

## 几个必须先理解的概念

### Project

一个需求探索工作区。

### Route

一条探索路线。分支之后，不同路线拥有不同的后续历史。

当前工作路线由：

```text
Project.activeRouteId
```

决定，而不是靠 Route 状态里的 `active` 标记。

### Node

Graph 中的工作单元。当前 V2 不再把 Node 只理解成“问题”，Question 只是其中一种交互节点。

### Answer

用户对 Question 的不可变回答。

已经存在的历史 Answer 不会被覆盖。重新回答历史问题时，需要新的 Node / revision / route 语义。

### AnswerPatch

Agent 对 Answer 的结构化解释结果，例如：

- claim
- constraint
- assumption
- risk
- conflict
- open question

Patch 是状态演进的 checkpoint 之一。

### AgentRun

一次受 Runtime 控制的 Agent 执行。

典型运行类型包括状态更新、决策、Artifact 生成等。

### ContextSnapshot / AgentInputSnapshot

一次 AgentRun 真正看到的冻结上下文。

上下文来自当前路线的 lineage，而不是“整个项目历史聊天记录”。

### SpecSnapshot

某个路线状态下生成的规格快照。

它是**派生产物**，不是系统事实源。真正的事实仍然来自 Graph、Answer、Patch、Route 和 Snapshot。

---

## 架构原则

可以把系统记成下面几层：

```text
Vue 前端
    ↓
Spring Boot API
    ↓
Application / Runtime
    ↓
Graph、Route、Answer、Patch、Policy、Gate
    ↓
Agent Decision Boundary
    ↓
Python Agent Brain
    ↓
Java Model Broker
    ↓
Provider / MCP / Capability
    ↓
PostgreSQL
```

其中：

- **PostgreSQL / Runtime**：拥有持久化事实与历史。
- **Java Runtime**：拥有生命周期、权限、资格、来源与一致性规则。
- **Python Brain**：负责推理，不拥有数据库状态。
- **Model Provider**：只提供模型能力，不拥有业务状态。
- **Capability / MCP**：负责外部能力执行，不替代 Agent 的决策边界。

一句话概括：

```text
Model proposes.
Runtime validates.
Runtime persists.
Runtime owns history.
```

也就是：

> 模型提出建议，Runtime 校验，Runtime 持久化，Runtime 掌握历史。

---

## 项目目录

```text
spec-agent/
├── backend/          # Java 21 + Spring Boot，Runtime、Graph、API、模型 Broker、MCP
├── agent-brain/      # Python Agent Brain，只做推理与结构化决策
├── frontend/         # Vue 3 + TypeScript + Vue Flow 图式工作台
├── contracts/        # Java / Python 共享契约与结构化数据定义
├── docs/             # V1 文档、验收记录、设计说明
│   └── v2/           # V2 canonical 设计文档
├── scripts/          # 开发辅助脚本
├── tools/            # 工具与辅助能力
├── docker-compose.yml
├── start-dev.bat     # Windows 本地开发启动入口
├── AGENT.md          # AI 编码 Agent 必须遵守的仓库规则
└── README.md
```

---

## 技术栈

### 后端

- Java 21
- Spring Boot 3
- PostgreSQL
- Flyway
- Spring JDBC
- ArchUnit
- JUnit / Testcontainers
- MCP Java SDK

### Agent Brain

- Python 3.11+
- FastAPI / Uvicorn
- 结构化 Agent Contract
- fake / broker 两种模型模式

### 前端

- Vue 3
- TypeScript
- Vite
- Pinia
- Vue Router
- Vue Flow
- Vitest
- Playwright

---

## 本地启动

### 推荐方式：Windows

仓库提供：

```bat
start-dev.bat
```

它负责启动本地开发所需组件。

### 基础设施

PostgreSQL 默认通过 Docker Compose 启动：

```bash
docker compose up -d
```

默认本地数据库：

```text
host: localhost
port: 5434
database: spec_agent
user: spec_agent
```

开发数据库和测试数据库必须保持隔离。

---

## 分组件启动

### Python Brain

```bash
cd agent-brain

python -m venv .venv
.venv/Scripts/pip install -e ".[dev]"
.venv/Scripts/python -m pytest

.venv/Scripts/uvicorn spec_agent_brain.app:app --host 127.0.0.1 --port 8100
```

Brain 支持（`SPEC_AGENT_BRAIN_MODEL_MODE` 是显式两值枚举）：

```text
SPEC_AGENT_BRAIN_MODEL_MODE=fake
SPEC_AGENT_BRAIN_MODEL_MODE=broker
```

- `fake`：完全离线、确定性，仅供测试/离线演示。
- `broker`：模型请求回到 Java 内部 broker，再由 Java 持有 provider 配置和凭据。
  要求同时配置 `SPEC_AGENT_INTERNAL_BROKER_URL` 与非空
  `SPEC_AGENT_BRAIN_INTERNAL_SECRET`。
- 其他任何值（含拼写错误）都会在启动时失败，不会静默回落到 fake。
- 健康检查 `GET /health` 返回 `ready` 与 `configError`，真实反映配置状态。

### 后端

```bash
cd backend
./gradlew bootRun
```

Windows：

```bat
cd backend
gradlew.bat bootRun
```

### 前端

```bash
cd frontend
npm install
npm run dev
```

---

## 安全与访问边界（单用户本机交付）

产品按"本机单用户"形态交付，默认访问边界如下：

- 后端只绑定回环接口（`server.address: 127.0.0.1`，可用 `SERVER_ADDRESS` 覆盖）。
- PostgreSQL 的 Docker 发布只绑定回环（`127.0.0.1:5434:5432`）。
- Brain 监听 `127.0.0.1`。
- Java ↔ Brain 的内部密钥没有仓库级固定默认值。未配置时，后端首次启动
  生成安装实例独有的随机密钥并持久化到 `backend/data/internal-secret.txt`；
  `start-dev.bat` 会生成同一文件并把相同值传给后端与 Brain。

把后端显式绑定到非回环地址（局域网/公网）时，产品 API 本身没有认证层，
启动守卫会拒绝启动；确要网络部署，必须同时设置
`SPEC_AGENT_ALLOW_NON_LOOPBACK=true` 并自行提供反向代理/认证入口与网络隔离。

## 模型凭据加密存储与主密钥

`model_providers`、`opencode_settings`、`openrouter_settings`、
`custom_provider_settings` 的 API Key 以 AES-GCM 密文落库（`enc:v1:<keyId>:...`），
API 响应与日志保持脱敏（只输出掩码后缀）。

主密钥解析顺序：

1. 环境变量/属性 `SPEC_AGENT_SECRET_MASTER_KEY`（32 字节 base64）。
2. 未配置时使用密钥文件 `backend/data/secret-master.key`（首次启动自动
   生成并持久化，之后每次启动读取同一文件；Windows 下继承目录 ACL）。

- 已有明文凭据在启动时自动加密改写（迁移可重复执行；失败保持明文并使
  启动失败，不会丢配置）。
- 主密钥缺失或与历史密文不匹配时启动失败/读取显式报错（fail closed），
  不会返回垃圾数据。
- 轮换：把新密钥设为主密钥、旧密钥放入
  `SPEC_AGENT_SECRET_MASTER_KEY_FALLBACK`（逗号分隔），重启一次完成重写，
  之后移除 fallback。

## 备份与恢复

1. 数据库：`pg_dump -h 127.0.0.1 -p 5434 -U spec_agent spec_agent > backup.sql`。
2. 密钥文件：同时备份 `backend/data/secret-master.key` 与
   `backend/data/internal-secret.txt`（丢失主密钥文件后，已加密的模型
   凭据无法解密，需要重新输入各 provider 的 API Key）。
3. 恢复：先恢复数据库与密钥文件，再启动后端；启动迁移会把任何残留明文
   自动重新加密。

## 故障处理

- **启动后 AI 任务仍长时间"进行中"**：进程崩溃/重启会留下孤儿任务。
  重启后启动恢复会把它们诚实置为失败（`INTERRUPTED_BY_RESTART`），
  前端可重新发起；不会自动重放模型调用或重写不可变回答。
- **删除项目返回 409（`PROJECT_HAS_RUNNING_RUNS`）**：项目存在排队或
  处理中的任务。等待任务终态化后重试；保护覆盖全部非终态，且与入队在
  项目行锁上互斥。
- **设置页提示"Base URL 已指向新的服务地址"**：修改了 provider 地址且
  未显式决定密钥。输入新地址对应的 API Key，或清空输入框表示无鉴权；
  旧密钥不会自动带到新来源。

---

## 测试

### 后端完整确定性测试

```bash
cd backend
./gradlew testNonLive
```

或者：

```bash
./gradlew cleanTest test
```

默认测试不调用真实模型。

不要给整轮测试强制设置：

```text
SPEC_AGENT_BRAIN_WORKER_ENABLED=true
```

测试 profile 默认关闭后台 worker，避免它与同步测试驱动争抢共享队列。

### Agent 确定性评估

```bash
cd backend
./gradlew evalBFast
```

这是 CI 阻塞门禁之一，不使用真实 provider。

### Java ↔ Python 跨语言门禁

```bash
cd backend
./gradlew testCrossLanguage
```

该测试需要单独启动一个 `broker` 模式的 Python Brain，并让它回调到测试进程的 broker 端口。

如果只是普通本地开发，不需要每次都跑这一项。

### Python Brain

```bash
cd agent-brain
.venv/Scripts/python -m pytest -q
```

### 前端

```bash
cd frontend

npm run typecheck
npm run test
npm run test:e2e
npm run build
```

---

## 真实模型调用

普通开发、单元测试、集成测试、B-fast 评估默认都应该使用 fake gateway。

真实 provider 验证必须显式执行，例如：

```bash
cd backend
./gradlew evalLiveSmoke
./gradlew evalLive
```

真实模型调用：

- 不能偷偷进入普通 CI。
- 不能自动 fallback 到 fake 后假装成功。
- 凭据不能写进日志、trace、数据库快照或 Python Brain。
- live evaluation 与普通回归测试必须分离。

---

## 开发规则

当前仓库默认采用单分支开发：

```text
local main ↔ remote main
```

除非项目所有者明确授权，否则：

- 不创建 feature branch。
- 不随意创建 PR。
- 保持 `main` 可运行、可测试。
- 小步提交。
- 行为变化必须补测试。
- 架构变化不能只为了让 ArchUnit 通过而“搬包”。
- Runtime 代码禁止硬编码具体业务领域逻辑。

AI 编码 Agent 开工前必须先读：

- [`AGENT.md`](AGENT.md)

---

## V2 的几个核心原则

当前实现以 V2 canonical 文档为准。

### Graph 是事实源

Node、Route、Answer、Snapshot、Operation History 等事实由 Runtime 持有。

### Context 是 lineage，不是全局聊天记录

只把当前运行真正需要的路线历史冻结进上下文。

### Agent 决定“做什么”，Capability 决定“怎么做”

Agent 不需要知道 GitHub、MCP、Skill 等具体执行细节。

### 默认 Advisor

系统默认是顾问模式。破坏性操作、外部副作用和重要意图变化必须经过更严格的 Runtime policy。

### Agent Loop 必须有边界

每次运行必须有：

- step budget
- stop condition
- waiting-for-user 边界
- failure 边界

不允许 Agent 自主无限循环。

### 历史只能追加演进

已确认事实和不可变 Answer 不能为了“修复”而原地重写。

---

## 推荐阅读顺序

如果你第一次看这个仓库，不建议从所有设计文档开始硬啃。

### 想理解 Agent 大脑

按真实代码链阅读：

```text
1. 前端提交 Answer
2. Backend API 接收
3. Answer 持久化
4. 创建 AgentRun
5. STATE_UPDATE
6. Java ↔ Python Brain 边界
7. AnswerPatch / checkpoint
8. canonical RequirementState
9. DECISION
10. Artifact / Spec generation
```

先理解 Runtime，再理解 Brain。

### 想理解 V2 设计

从这里开始：

- [`docs/v2/README.md`](docs/v2/README.md)
- [`docs/v2/AGENT_V2_OVERVIEW.md`](docs/v2/AGENT_V2_OVERVIEW.md)
- [`docs/v2/AGENT_RUNTIME_ARCHITECTURE.md`](docs/v2/AGENT_RUNTIME_ARCHITECTURE.md)
- [`docs/v2/AGENT_STATE_MODEL.md`](docs/v2/AGENT_STATE_MODEL.md)
- [`docs/v2/PYTHON_AGENT_RUNTIME_BOUNDARY.md`](docs/v2/PYTHON_AGENT_RUNTIME_BOUNDARY.md)
- [`docs/v2/AGENT_ACTION_PROTOCOL_V2.md`](docs/v2/AGENT_ACTION_PROTOCOL_V2.md)
- [`docs/v2/CAPABILITY_RUNTIME.md`](docs/v2/CAPABILITY_RUNTIME.md)

### 想理解当前生产兼容语义

再看：

- [`docs/PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/AGENT_RUNTIME.md`](docs/AGENT_RUNTIME.md)
- [`docs/CONTEXT_RULES.md`](docs/CONTEXT_RULES.md)
- [`docs/MODEL_GATEWAY.md`](docs/MODEL_GATEWAY.md)
- [`docs/DEVELOPMENT_ENVIRONMENT.md`](docs/DEVELOPMENT_ENVIRONMENT.md)

---

## 当前状态

当前 `main` 已完成：

- Graph V2 工作区核心能力
- Agent Runtime Stages A–D
- Python Brain 边界
- Answer / Patch checkpoint 与历史恢复
- 规格来源引用与生成门禁
- Capability / Skills / MCP 基础能力
- Conflict Intelligence 核心链
- Java ↔ Python 跨语言契约
- 确定性 Agent evaluation baseline
- 后端 top-level package cycle 清零

项目仍在持续演进，但核心方向已经固定：

> **把 Agent 做成一个受 Runtime 约束、历史可追踪、状态可恢复、能力可扩展的需求推理系统，而不是一个“什么都能聊”的黑盒聊天机器人。**

# 交付准备度审查 — 2026-09-27

**结论：当前不建议作为正式交付版本。可用于受控、本机、单用户演示；携带真实密钥或长期保存重要需求数据前，应先解决本文的 P1 问题。**

审查对象为 `E:/project/spec-agent` 的实际工作区，HEAD 为 `d59c575`。审查开始时有 1,227 个已修改的跟踪文件，结论覆盖这些修改后的代码，不能等同于该提交或远端 CI 的认证。没有修改业务代码、提交代码或调用真实模型。

产品文档明确以单用户为目标，因此不把缺少多人协作、租户管理当作功能缺陷；但单用户产品仍需限制接口可达范围、保护密钥，并在进程中断后给出可恢复的任务状态。

## 本次验证

| 检查 | 本次结果 | 范围及证据 |
|---|---|---|
| 前端类型检查与生产构建 | 通过 | `npm run build` 包含 vue-tsc；`scratch/delivery-frontend-build.log` |
| 前端单元测试 | 100 个文件、822 项通过 | `scratch/delivery-frontend-test.log` |
| Python Brain | 135 项通过 | `scratch/delivery-python-test.log` |
| Python 辅助工具 | 108 项通过 | `scratch/delivery-tools-test.log` |
| 后端完整确定性测试 | 255 个 suite、1,574 项通过，0 失败/错误/跳过 | `gradlew.bat testNonLive --rerun-tasks`；`scratch/delivery-backend-retest.log` 与 `backend/build/test-results/testNonLive` |
| 后端可执行 JAR | 生成成功，实际启动成功 | `backend/build/libs/spec-agent-backend-0.0.1-SNAPSHOT.jar` |
| 空数据库安装 | 通过 | 实际应用 40 个 Flyway migration，schema 版本 v41；`scratch/delivery-server.log` |
| 已安装数据库重启 | schema 校验通过 | `scratch/delivery-server-resumed.log`；不等同于跨历史版本升级演练 |
| Playwright | 77 个不同用例，76 通过、1 个截图落盘失败 | 首批 36 通过；续跑 40 通过/1 失败；`scratch/delivery-e2e-summary.json`、`scratch/delivery-e2e.log`、`scratch/delivery-e2e-clean.log` |
| 安全与异常路径复现 | 发现确定缺陷 | `scratch/delivery-security-result.json`、`scratch/delivery-runtime-result.json`、`scratch/delivery-brain-mode-result.json` |
| npm 依赖审计 | 1 high、2 moderate 包告警 | 两个不同 advisory，涉及 glob、vitest/@vitest/mocker；`scratch/delivery-npm-audit-official.json` |

首次后端测试启动时 Docker/数据库未就绪，产生 Spring context 失败，已停止该轮；数据库恢复后重新编译并完整重跑，上表采用重跑结果。首次浏览器轮次中断后留下同名项目，续跑改用全新数据库，避免把重复项目名冲突算作产品缺陷。

浏览器用例在真实 Chromium 中执行，但不是全部都贯穿真实后端：图/回答/路线/规格主流程连接隔离后端与 fake 决策引擎，部分 Global Assistant、Skills、Connections、设置页用例拦截并模拟接口。测试通过证明对应覆盖路径可用，不代表真实外部 MCP、Git 服务或模型厂商已经联调通过。

`provider-screenshots.spec.ts` 在 `.impeccable/shots` 写 PNG 时出现 `UNKNOWN: unknown error, open ...png`；独立重跑在另一张 PNG 上再次失败，之前的页面断言已通过。归类为截图产物落盘受阻，未定位底层文件系统原因，不计为页面功能缺陷，也不计为通过。证据包括 `scratch/delivery-provider-retry.log` 和相应截图/trace。

## 必须优先解决的问题

### R1 — P1：探测新提供商地址会把已保存密钥发送到不同来源

**已通过真实 HTTP 复现，使用虚构密钥及两个本机端口。**

`ModelProvidersService.discover` 允许请求覆盖 `baseUrl`；`ProviderModelCatalogService.discoverCustom` 在 `apiKey == null` 时取 `stored.apiKey()`，随后直接把它放入新目标请求的认证头。没有比较新旧 URL 的 scheme、host、port。旧单例 Custom 接口存在同类逻辑。

- 保存目标 A：`http://127.0.0.1:19090/v1`，使用合成密钥。
- 向同一 provider 的 `/probe` 提交目标 B：`http://127.0.0.1:19091/v1`，`apiKey: null`。
- B 收到原密钥对应的 `Authorization`，接口返回 200。
- 所有请求均在隔离验收数据库中执行，未读取或发送真实凭据。

定位：`backend/src/main/java/com/specagent/modelsettings/ModelProvidersService.java:155`、`ProviderModelCatalogService.java:107`、`CustomProviderSettingsService.java:83`。

影响：用户编辑服务地址并点击探测时，旧服务密钥可能泄漏给新服务；接口对未认证调用者开放会放大风险。

验收要求：跨来源探测不得自动继承密钥，必须显式输入新凭据；同来源复用、跨来源拒绝、清空凭据、旧接口都应覆盖回归测试。不要只禁止 HTTP 重定向，本问题发生在首次请求。

### R2 — P1：运行中项目删除保护实际失效，现有测试误用状态大小写

**已用有效 project/route 与模拟持久化运行状态复现。**

`ProjectDeletionService` 查询 `status = 'RUNNING'`，但 `AgentRunStatus.code()` 及 repository 保存的是小写 `running`。此外，`context_built`、`model_called` 等也属于非终态，仅检查运行态一种状态仍不够。

复现中，活跃任务接口返回 200 且包含一条运行中任务，随后 DELETE 项目返回 **204**，预期应为 **409**。测试夹具仅模拟数据库中有效的运行中记录，没有让真实模型并发执行。

定位：`backend/src/main/java/com/specagent/workspace/project/ProjectDeletionService.java:51`；`backend/src/main/java/com/specagent/agent/runtime/AgentRunStatus.java:20`；测试 `backend/src/test/java/com/specagent/workspace/project/ProjectDeletionIntegrationTest.java:212` 手写大写 `RUNNING`，因此未发现问题。

影响：删除可以拆掉正在执行的项目、运行与历史；worker 后续持久化可能失败。

验收要求：使用真实枚举编码校验所有非终态，在与入队/认领一致的并发边界内拒绝删除；用生产创建/认领路径构造测试，避免再次手写不真实的状态。

### R3 — P1：工作区 AgentRun 缺少中断后的孤儿任务恢复

**已用遗留运行记录与新服务进程复现。**

工作区 worker 只领取 `created` 行。当前恢复逻辑只恢复终态后的 continuation check，没有处理已被旧进程领取的 `running/context_built/model_called/...` 行。Global Assistant 有自己的启动恢复，但该逻辑读取的是另一张表，并不覆盖工作区 `agent_runs`。

在独立验收项目中放入创建于一小时前、无存活执行器的 `running` 记录，停止原服务后用新 JVM 连接同一数据库。服务正常启动并执行轮询后，任务仍为 `running`，结果记录在 `scratch/delivery-runtime-result.json`。

定位：`backend/src/main/java/com/specagent/agent/runtime/AgentRunRepository.java:355`、`RunWorkerPoller.java:24`；对照 `backend/src/main/java/com/specagent/assistant/runtime/GlobalAssistantRunRecoveryService.java:32`。

影响：断电、进程崩溃或重启后，任务长期停留在处理中；刷新页面不能解决服务端状态，恢复体验不可信。

验收要求：为当前单实例形态提供可靠的启动中断收敛及明确重试入口；如支持多实例则增加所有权/租约，避免误伤其他 worker。恢复应利用已有 checkpoint，不能重放不可变回答或外部副作用。补实际进程中断回归。

### R4 — P1：模型提供商密钥明文落库

**已使用合成密钥验证数据库实际存储。**

`model_providers.api_key` 直接写入 `record.apiKey()`；OpenCode、OpenRouter、旧 Custom 配置也采用同类存储。MCP 连接另有 AES-GCM 存储，不能据此推断模型提供商密钥同样加密。

在验收库中查询合成密钥是否与 `api_key` 字段相等，结果为 true；报告及日志没有输出真实密钥。

定位：`backend/src/main/java/com/specagent/modelsettings/JdbcModelProviderRepository.java:95`、`JdbcOpenCodeSettingsRepository.java:43`、`JdbcOpenRouterSettingsRepository.java:46`。

影响：数据库备份、开发库副本或数据库读取权限会直接暴露模型密钥。

验收要求：统一使用受主密钥保护的凭据存储，配置表仅保存引用/密文；提供既有明文数据迁移、主密钥持久化与备份恢复说明，并验证 API/日志保持脱敏。

### R5 — P1（网络部署）：默认运行形态没有形成可信访问边界

实测不带认证信息即可读取项目、创建提供商并执行探测。后端没有发现 Spring Security/filter 认证边界，也没有默认 `server.address=127.0.0.1`。启动脚本将 Brain 绑定 `0.0.0.0`，并硬编码开发内部 token；Compose 将数据库 5434 端口发布到默认主机接口。

定位：`backend/src/main/java/com/specagent/workspace/project/ProjectController.java:44`、`backend/src/main/resources/application.yml:17`、`start-dev.bat:170`、`docker-compose.yml:10`。

影响取决于主机防火墙和网络配置；本次没有对公网或其他主机做探测，不能声称已发生外部访问。当前形态不能直接作为局域网/公网部署交付。

验收要求：若交付本机单用户工具，默认只绑定回环接口，并使用安装实例独有的内部密钥；若交付网络服务，则配置经过验证的认证入口、TLS 与 `/internal/**` 隔离。无需为了本机产品引入完整多租户系统。

## 其他需要修正的问题

### R6 — P2：合法的无路线 NodeQuery 会使任务查询接口返回 500

**已通过公开业务 API 复现，无需手写非法记录。** 创建浮动 IDEA，调用 `/nodes/{id}/query`，传 `routeId: null`，返回 202；随后通用 `/agent-runs/{runId}` 返回 500，活跃期间 `/agent-runs/active` 也返回 500。

原因是 `AgentRunViewResponse.from` 无条件执行 `run.routeId().toString()`，而 `RunService.createQueuedNodeQuery` 明确支持空 route。前端重建进度列表时会吞掉读取异常，因此刷新可能丢失项目内的运行进度展示。NodeQuery 专用结果接口与通用查询路径不同，不能将该问题表述为所有浮动查询均无法完成。

定位：`backend/src/main/java/com/specagent/agent/runtime/AgentRunViewResponse.java:44`、`RunService.java:294`、`frontend/src/features/workspace/state/workspaceLoader.ts:227`。

验收要求：响应契约允许 routeId 为空，并覆盖无路线查询在执行中刷新、查询列表、读取终态三条路径。

### R7 — P2：Brain 错误模式配置会静默运行 fake

`config.py` 默认 `fake`；`app.py` 仅在模式等于 `broker` 时使用真实 broker，其他任何值均返回 `FakeModelClient`。

本次用内存 TestClient 配置 `model_mode='brokre'`、不可达 broker 地址和空 internal secret，合法决策请求仍返回 200 与 `REQUEST_USER_INPUT`，健康检查返回 ok。这说明拼写错误会制造“AI 正常工作”的假象。

定位：`agent-brain/src/spec_agent_brain/config.py:31`、`agent-brain/src/spec_agent_brain/app.py:77`。复现证据：`scratch/delivery-brain-mode-result.json`。

验收要求：只接受明确的 fake/broker 枚举；未知模式启动失败；交付入口显式启用 broker 并验证必需配置，健康检查区分配置错误与服务就绪。

### R8 — P2：一键启动会强杀占用端口的任意进程

`start-dev.bat` 启动前对 8080/5173/8100 或自定义端口调用 `killPort`，仅按监听端口查 PID 后执行 `taskkill /F /T`，未确认是否属于本项目。

定位：`start-dev.bat:61`、`start-dev.bat:223`。本次仅审查代码，没有执行该脚本，也没有用其他用户进程做破坏性复现。

影响：交付到已有开发服务的机器时，启动可能直接关闭其他应用及其子进程；所谓“端口占用时自动递增”发生在强杀之后，不能保护其他应用。

验收要求：仅管理本项目记录的实例 PID，并校验进程身份；未知占用应报明确冲突或选择空闲端口。

### R9 — P2：开发依赖存在已知公告，交付门禁尚未闭合

使用 npm 官方 registry 的实际审计返回 1 high、2 moderate 包告警。`glob@10.4.5` 来自 `@vue/test-utils → js-beautify`，`vitest@3.2.7` 与其 mocker 共用另一公告。

- [glob 公告](https://github.com/advisories/GHSA-5j98-mcp5-4vw2)：影响带 `-c/--cmd` 的 CLI，不影响普通库 API；本次没有发现项目调用这一危险 CLI，不能直接定性为生产远程代码执行。
- [Vitest 公告](https://github.com/advisories/GHSA-82fw-gwwq-j7x9)：涉及 mocker 路径处理；这些依赖用于开发/测试，不应把 npm 的包级告警计数等同于三个独立生产漏洞。

验收要求：在兼容性回归后升级依赖，并记录不受影响的使用边界；不要盲目执行强制升级。Java/Python 依赖本次没有完成等价的全量漏洞扫描。

## 已有能力与未通过的门禁应分开看

本次构建、单元/集成测试以及已完成浏览器路径显示，项目具备真实可运行的需求澄清、不可变回答、路线分叉、规格快照与画布交互基础。空库迁移和 JAR 启动可用。

连接验证技能中的旧参考报告已经部分过时：当前 RouteService 已记录 fork/reanswer/regenerate 的操作日志；前端存在 disconnect 的实际调用；浮动资源接入和 undo/redo 已有真实浏览器用例。本报告没有照搬旧文档中的“不可撤销/无入口”结论，也不宣称已穷举所有节点与所有状态的组合。

仍需补足的交付证据：

1. Java ↔ Python 真实 HTTP 契约门禁，本次未运行完成。启动专用 Python broker-mode 服务的命令遭自动审批拒绝，返回 `blocked by policy`，没有具体原因；没有绕过该拒绝。
2. 使用目标真实模型的澄清→回答→状态更新→规格生成、超时、限流与失败恢复验收。本次使用 fake，未消耗真实 provider 配额。
3. 从约定旧版本到当前版本的迁移、备份恢复、回滚或回退策略演练。本次只验证空库安装与当前 schema 重启校验。
4. 发布产物与确定代码版本对应、依赖可复现、生产入口/配置模板、备份及故障处理说明。现有入口以本地开发为主，README 与开发环境文档还存在 Brain 启动方式、默认模型模式等描述差异。
5. 长时间运行、大图数据量、并发负载与目标部署环境的容量验证；本次没有据测试数量推断生产容量。

## 交付分级与整改顺序

| 交付方式 | 当前判断 |
|---|---|
| 受控本机演示、合成数据、无真实密钥 | 可演示，提前说明已知限制 |
| 单用户内测，重要真实需求数据/真实模型密钥 | 先修 R1–R4，并复验 R6/R7 |
| 对客户正式交付长期使用 | 暂不通过，需要完成上述修复与交付证据 |
| 局域网/公网服务或多实例 | 暂不通过；访问边界、任务所有权与恢复需另行验收 |

建议先处理密钥发送边界和运行中删除，再补孤儿任务恢复与密钥加密；随后修正空路线响应、配置 fail-closed 与启动进程管理。使用真实生产路径构造回归测试，再跑完整确定性门禁、浏览器验收及真实模型小规模验收，最后冻结发布版本。

## 复现环境及留存

- 一次性数据库：`spec_agent_delivery_review_20260926`、`spec_agent_delivery_remaining_20260927`，均与开发库 `spec_agent` 分离。
- 后端全量确定性测试使用项目既有 `spec_agent_test`；浏览器及安全复现使用上述一次性数据库。
- 原始证据及脚本位于 `scratch/delivery-*`；测试仅发送合成密钥到回环地址。
- 运行中删除、浮动查询、孤儿任务的项目及 run ID 见 `scratch/delivery-runtime-result.json`；浏览器生成的项目全部留在验收库，便于复查。
- 审查结束时已停止本次创建的两个 Java 验收进程；Playwright 管理的前端服务随测试结束退出。Docker/PostgreSQL 保留运行，两个验收数据库和测试证据保留用于复查。
- 浏览器最终汇总为 76/77 通过；唯一失败为设置页截图 PNG 落盘错误，独立重跑仍未通过。此次没有变更测试断言来消除失败。

# 交付阻塞修复验收报告 — 2026-09-27

**结论：R1–R9 全部修复并有回归证据；本机单用户交付形态（回环绑定、合成/真实密钥均受保护）可以正式交付。局域网/公网多实例部署仍不适用（无认证层、恢复机制按单实例设计，已用启动守卫明确阻止不安全形态）。真实模型厂商的小规模端到端验收仍为外部门禁（本次零真实模型调用）。**

审查对象为 `E:/project/spec-agent` 实际工作区（原审查基线 `d59c575` + 本轮修复）。原报告：`docs/DELIVERY-REVIEW-2026-09-27.md`。本轮全部验证在隔离数据库/合成凭据上进行，未读取、输出或发送任何真实密钥，零收费模型调用。

## 逐项修复结果

### R1 — 跨来源探测不再自动复用旧密钥（P1）✅

- **根因**：`ProviderModelCatalogService.discoverCustom` 在 `apiKey == null` 时无条件取 `stored.apiKey()` 放入新目标请求的认证头；`CustomProviderSettingsService.discover`（旧单例接口）同样；`save`/`update` 修改地址时可静默沿用旧密钥。
- **修改**：
  - `ProviderUrlSecurity.sameOrigin/originOf`（`backend/.../model/provider/ProviderUrlSecurity.java`）：规范化来源比较（scheme + host 小写 + 有效端口，http→80/https→443 归一化）。
  - `ProviderModelCatalogService.discoverCustom` + `requireSameOriginForReuse`、`CustomProviderSettingsService.discover/save`、`ModelProvidersService.update`：已存密钥仅在同来源复用；跨来源一律抛出明确错误（"Base URL points to a different origin; enter the API key for it explicitly"），显式新密钥与显式清空（空串）不受限。
  - 前端 `CustomProviderForm.vue` + `shared/net/origin.ts` + `customProviderStore.ts`：Base URL 改到不同来源且未触碰密钥输入时，展示明确警告并禁用"获取模型/保存并测试"，要求显式输入新密钥或清空。
- **回归证据**：`CrossOriginCredentialGuardTest`（12 项）——本机两个独立 HTTP 接收端 + 合成密钥：同来源复用、跨来源阻断（接收端 0 请求、0 认证头）、显式新密钥只到新来源、显式清空无鉴权、默认端口/host 大小写归一化、旧单例接口 discover/save 守卫、修改地址的保存路径守卫。前端 `customOriginGuard.spec.ts`（5 项）。

### R2 — 运行中项目删除保护（P1）✅

- **根因**：`ProjectDeletionService` 查询大写 `'RUNNING'`，而 `AgentRunStatus.code()` 落库为小写；且只挡 running 一种状态，放过 `context_built/model_called/reflected/persisted` 与排队中的 `created`。旧测试手写大写状态，双向失真。
- **修改**：
  - 删除保护改为 `status IN (全部非终态码)`（`ProjectDeletionService.NON_TERMINAL_RUN_STATUSES`），并由 `ProjectDeletionRunLifecycleGuardTest.deletionGuardStatusListMatchesEnum` 对照真实枚举防漂移（workspace 包被架构规则禁止依赖 agent 包，故以字面量+测试锁定）。
  - 并发边界：入队路径（`RunService` 全部 create 方法）在事务内先取项目行锁 `FOR KEY SHARE`，与删除的 `FOR UPDATE`（先锁项目行→检查→删除）互斥串行化——删除提交后不可能再入队新任务；入队持锁期间删除会看到非终态 run 并 409。刻意选 KEY SHARE 而非 FOR UPDATE：与同线程嵌套 REQUIRES_NEW 验收事务的再次入队互不阻塞（曾用 FOR UPDATE 引发过评估套件死锁，已修正并全量复验）。
  - `ProjectDeletionIntegrationTest` 的错误测试改为通过生产入队+认领路径构造真实状态。
- **回归证据**：`ProjectDeletionRunLifecycleGuardTest`（5 项）——created/running/context_built/model_called/reflected/persisted 全部 409 且数据零删除；completed/failed 204；两个方向的并发事务测试（删除提交后入队必须失败；入队先提交则删除 409）。409 错误码 `PROJECT_HAS_RUNNING_RUNS` 保持不变（前端契约不变）。

### R3 — 工作区 AgentRun 中断恢复（P1）✅

- **根因**：worker 只认领 `created` 行；旧进程领取后的 `running/context_built/model_called/reflected/persisted` 行没有任何恢复路径，进程中断后任务永久停留处理中。
- **修改**：
  - 新增 `AgentRunOrphanRecoveryService` + `AgentRunOrphanRecoveryListener`（启动时、worker 进程内执行）：把所有被旧执行器领取过的非终态 run 诚实终态化为 FAILED（trace=`failed:INTERRUPTED_BY_RESTART`），逐 run 独立事务 + `RUN_FAILED` 事件（含用户可读文案"服务重启导致本次任务中断，未产生结果，可重新发起该操作"）。**不重放模型调用、不重写不可变 Answer、不自动派发续跑**。排队中（created）的 run 不是孤儿，保持等待认领。
  - `RunWorkerPoller` 在恢复完成前暂停轮询，避免与恢复交错；恢复失败也解除暂停（队列不停摆）。
  - 单实例边界明确写入 Javadoc：恢复依赖"同一时刻至多一个执行器进程"的产品边界；多实例需先引入租约/所有权。可用 `SPEC_AGENT_BRAIN_WORKER_STARTUP_RECOVERY=false` 关闭。
  - 失败码 `INTERRUPTED_BY_RESTART` 进入 `RunFailureReasons.USER_COPY`，前端进度面板可显示原因并重新发起。
- **回归证据**：`AgentRunOrphanRecoveryIntegrationTest`（3 项）——五个处理阶段全部被终态化、终态 run 与排队 run 不受影响、重复恢复幂等、API 单查/活跃列表正确反映失败（刷新页面不再出现幽灵进度）、不产生续跑子任务且 Answer 数不变。测试自清理，避免污染共享队列断言。

### R4 — 模型凭据加密存储与迁移（P1）✅

- **根因**：`model_providers` / `opencode_settings` / `openrouter_settings` / `custom_provider_settings` 的 `api_key` 明文落库。
- **修改**：
  - 新增 `ModelCredentialCrypto`（AES-256-GCM，随机 IV，密文格式 `enc:v1:<keyId8>:<base64(iv‖ct)>`，keyId=SHA-256 前 4 字节）。四个 JDBC repository 读写边界统一加解密，上层服务与脱敏视图不变。
  - 主密钥解析：`SPEC_AGENT_SECRET_MASTER_KEY`（32B base64，与 MCP 连接凭据共用同一属性）→ 否则密钥文件 `backend/data/secret-master.key`（首次启动生成并持久化，之后稳定读取；Windows 继承目录 ACL）。缺失/损坏/不匹配一律显式失败（fail closed），绝不返回垃圾数据。
  - 新增 `ModelCredentialEncryptionMigrator`（启动时）：残留明文一次性加密；keyId 不匹配的旧密文（轮换场景，配合 `SPEC_AGENT_SECRET_MASTER_KEY_FALLBACK`）用 fallback 解密后以主密钥重写。可重复执行；逐行提交，失败保持明文并使启动失败，不丢配置。
  - 轮换/备份/恢复说明已写入 `README.md`（"模型凭据加密存储与主密钥"、"备份与恢复"节）。
- **回归证据**：`ModelCredentialCryptoTest`（10 项：往返、随机 IV、明文兼容、错误密钥/未知 keyId 显式失败、密钥文件生成与跨重启稳定、损坏密钥文件 fail closed、轮换 fallback）；`ModelCredentialEncryptionMigrationTest`（2 项：四表明文迁移后物理密文+读取透明+幂等；repository 写入落为密文）。
- **实际演练**（见下文"安装与迁移演练"）：空库安装后注入 4 行旧版明文凭据，重启触发迁移（`plaintextEncrypted=4`），物理行全部变 `enc:v1:*`，API 仅返回 `hasKey`+掩码后缀；pg_dump→恢复库→重启解密正常。

### R5 — 回环访问边界（P1）✅

- **根因**：后端未配置 `server.address`（绑全部网卡）、无任何认证边界；Compose 把 5434 发布到默认接口；启动脚本硬编码 `dev-internal-secret` 且 Brain 绑 `0.0.0.0`。
- **修改**：
  - `application.yml`：`server.address: 127.0.0.1`（`SERVER_ADDRESS` 可覆盖）。
  - 新增 `LoopbackBindingGuard`：非回环绑定且未显式 `SPEC_AGENT_ALLOW_NON_LOOPBACK=true` 时启动失败，消息说明产品 API 无认证层、网络部署需自行提供认证与隔离。
  - 内部密钥去除仓库级固定默认值：未配置时 `InstallInternalSecret` 首次启动生成 32 字节随机值并持久化到 `backend/data/internal-secret.txt`；`start-dev.bat` 生成同一文件并把相同值传给后端与 Brain（两端一致）。test profile 固定 `dev-internal-secret` 保持离线确定性（CI 环境变量继续生效）。
  - `docker-compose.yml`：`127.0.0.1:5434:5432`。启动脚本 Brain 改绑 `127.0.0.1`。
- **回归证据**：`AccessBoundaryTest`（4 项：回环判定、非回环默认拒绝启动+显式知情放行、安装密钥生成/持久化/重启稳定/显式配置优先、内部 broker 缺失/错误 token 401 而正确 token 进入协议层）。

### R6 — 无路线 NodeQuery 响应契约（P2）✅

- **根因**：`AgentRunViewResponse.from` 对可空的 `run.routeId()` 无条件 `toString()`；`RunService.createQueuedNodeQuery` 明确支持空 route → 通用 run 查询与活跃列表 500，前端刷新丢失进度。
- **修改**：后端 `routeId == null ? null : toString()`（JSON 契约输出 null，字段名冻结不变）；前端 `AgentRunView.routeId: string | null`（`runRegistryStore` 原本已按 null 处理，类型对齐）。
- **回归证据**：`RoutelessNodeQueryRunViewContractTest`（2 项）——公开 API 入队浮动节点查询（routeId:null，202）后，运行期间单查 200+`routeId:null`、活跃列表 200 含该 run、生产认领执行到终态后单查仍 200；同项目混合"带路线 run + 无路线 run"的活跃列表同时正确。既有 `RoutelessNodeQueryIntegrationTest`（快照 NULL route + 只读完成）继续通过。

### R7 — Brain 配置 fail closed（P2）✅

- **根因**：`config.py` 未知 `model_mode` 静默返回 `FakeModelClient`，健康检查永远 ok。
- **修改**：`config.py` 模式为显式两值枚举（fake/broker，大小写归一）；未知值启动抛 `ConfigurationError`；broker 模式要求合法 http(s) broker URL 与非空内部密钥。`app.py` 的 `model_client` 防御性 fail closed；`/health` 增加 `ready`/`configError`/`authEnabled`，配置错误时 `status=config_error` 且不报就绪。
- **回归证据**：`agent-brain/tests/test_config_fail_closed.py`（9 项：拼写错误/未知模式启动失败、broker 缺密钥/坏 URL 失败、显式 fake 与合法 broker 可用、broker 模式下 broker 不可达返回 502 而非 fake 结果、健康检查如实反映配置错误与就绪）。Brain 全套 144 项通过，确定性测试继续离线运行。

### R8 — 启动脚本不再强杀任意进程（P2）✅

- **根因**：`start-dev.bat` 按端口 `taskkill /F /T` 任意监听进程树。
- **修改**：`stopOursOnly/identityKill`——通过 `Get-CimInstance` 读命令行，只有命令行包含本仓库根路径（分隔符归一化后）或 `spec_agent_brain` 标记的进程才重启；外来占用打印明确冲突提示并保留进程，端口选择器自动跳到下一个空闲端口（显式端口被外来占用则报错退出）。内部密钥生成（见 R5）、Brain 绑 `127.0.0.1`。
- **回归证据**（真实场景验证，scratch 临时目录已清理）：外来进程（相对路径 python http 监听）→ 保留并提示 `Port ... is held by another application`；本项目进程（绝对仓库路径 / 标记命令行）→ `Restarting previous Spec Agent instance` 并成功停止；陈旧 PID → 无害提示；内部密钥生成 64 位 hex 且可复用。`start-dev.bat --help` 解析正常。

### R9 — 依赖告警闭合（P2）✅

- **npm（官方 registry 实测）**：修复前 1 high（glob 10.4.5，GHSA-5j98-mcp5-4vw2）+ 2 moderate（vitest/@vitest/mocker 3.2.7，GHSA-82fw-gwwq-j7x9）。
  - `npm audit fix`（非强制）→ glob 10.5.0；
  - vitest 升级 3.2.7 → **4.1.11**（该公告的修复版本），前端全量单测 827 项通过，生产构建通过，测试语义与覆盖未变；
  - 修复后 `npm audit` = **0 vulnerabilities**（`npm ls`：glob 10.5.0、vitest/@vitest/mocker 4.1.11）。
- **Java**：仓库未配置自动化漏洞扫描（无 OWASP dependency-check / 未接 NVD），本次补齐的事实核查：Spring Boot 3.3.2 → Spring Framework 6.1.11，已知晚于该版本的 6.1.x 修复（如 CVE-2024-38820，修复于 6.1.14；参见 [Spring CVE 列表](https://spring.io/security)）不在本版本内；产品默认只绑回环且 `DataBinder` 自动装配未暴露受影响模式，可达性低。**建议后续把 Spring Boot 补丁升级列入常规维护**（需独立回归，不在本次盲改）。
- **Python**：`pip-audit` 因本机代理无法访问 PyPI/OVSI 而无法运行（非零漏洞结论，是扫描不可用）；Brain 依赖面小（fastapi/uvicorn/httpx/pydantic），建议在网络可达环境补跑 `pip audit`。
- **不把扫描失败当零漏洞**：以上两条如实记为"剩余外部验证项"。

## 截图与 E2E 环境问题

- **截图落盘失败根因处理**：`provider-screenshots.spec.ts` 原写固定路径 `.impeccable/shots/*.png`，陈旧/被占用文件导致 `UNKNOWN: unknown error, open ...png`。现改为**每次运行独立目录** `test-results/provider-shots/<UTC 时间戳>/`（可用 `PLAYWRIGHT_SHOTS_DIR` 覆盖），`beforeAll` 预建目录，运行位置写入测试注解。截图与页面断言全部保留，未删除任何截图调用；修复后单独复跑 26 张 PNG 全部落盘成功。
- **E2E 数据隔离**：`createProject` 现在自动追加唯一后缀（重复执行不再撞同名项目）并返回项目 id，且通过扩展 fixture 在每个用例结束后**只删除本次创建的项目**（204/404 即成功，409 短暂重试等待 run 终态化；失败不留死锁）。全部 spec 的 Playwright 导入统一改经 helpers 的扩展 fixture；`graph-node-visibility.spec.ts` 原来自行创建固定标题项目（'BUG02 …'，与早前中断运行的同名遗留项目相撞导致 409→超时），已改为统一走 `createProject`。
- **Windows/Linux 兼容**：`playwright.config.ts` webServer 原用 cmd 专属 `set "X=Y" &&`，Linux CI 下静默失效；改为 Playwright 原生 `webServer.env`（跨平台），代理目标同步逻辑不变。

## connection-matrix-verifier 浏览器实测（当前实现）

环境：真实 Chromium（ZCode IAB）+ 本地后端（test profile，fake 网关）+ Vite 5173。测试项目 `连接验证-20260927`（`aec8fd03-8fbc-4a6c-8d41-ddec35ba16e7`，验证后已删除，数据库残留已清理）。

| 编号 | 操作 | 实际 | 判定 |
|---|---|---|---|
| C1 | tip 未回答问题右源手柄 → 浮动 IDEA 左目标手柄（真实鼠标拖线） | `CONNECT_FLOATING_NODE` 入操作日志、reversible=true，节点入路线 | 适配 |
| C14 | undo C1 | 操作标记 `UNDONE`（undoneAt 落库），浮动徽标恢复 | 适配 |
| C7 等价 | 回答 tip → 自治续跑自动追加 Q3（路线 2→3 节点） | 正常，操作日志 `CREATE_DRAFT_NODE(AGENT)` | 适配 |
| C15 | undo agent 续跑追加 | 撤销成功（toast"已撤销…"），路线回到 2 节点，并给出"回答已经保存…继续生成"重试入口 | 适配（且优于旧参考文档） |
| C17 变体 | "继续生成"重试后再撤销 | 该重试路径记录的是可撤销 CREATE_DRAFT_NODE，撤销成功 | 适配；旧参考文档"agent 建节点一律不可撤销"已过时 |
| C2/C4/C5 | 浮动资源拖线、关系提案对话框、对称重复拒绝 | 由现行 E2E `floating-resource.spec.ts`、`connection.spec.ts` 真实覆盖（本轮全绿） | 适配 |
| C9 | 旧"添加资源直接 attach"按钮 | UI 已不存在（接入统一走拖线宽松路径），后端 strict 校验仍在 | 前后端一致（旧文档差异已由产品收敛） |
| C18 | fork/reanswer/regenerate 后撤销状态 | 由现行 E2E `fork.spec.ts`/`reanswer.spec.ts`/`regenerate.spec.ts` 覆盖（本轮全绿）；本次实测确认 fork 之外的可撤销历史不被"失明" | 适配 |

**注意**：技能自带 references（node-types/test-cases/undo-coverage）部分结论已过时——本次以当前实现为准重新核实，"agent 自动建节点不可撤销""浮动节点接入不可撤销"均与当前代码不符（当前均记录且可撤销）；`ACCEPT_AGENT_PROPOSAL` 永久 barrier 仍存在于显式受理路径（`GraphOperation reversible=false`），本轮未通过 UI 触发该路径（需待审批提案场景，属既有覆盖范围）。

## 实际执行的验证与结果

| 验证 | 命令 | 结果 |
|---|---|---|
| 后端全量确定性测试（含架构门禁、评估确定性套件） | `gradlew testNonLive --rerun-tasks` | **1,613 通过 / 0 失败 / 0 错误 / 0 跳过**（基线 1,574 → +39 新回归） |
| Java ↔ Python 跨语言 HTTP 契约 | `gradlew testCrossLanguage`（独立 broker-mode Brain :8100 → 隔离 broker :8099，fake 推理网关） | **2 通过 / 0 失败 / 0 跳过**，零真实模型调用 |
| Python Brain | `pytest tests/` | **144 通过**（基线 135 → +9） |
| 辅助工具 | `pytest tools/tests` | **108 通过**（与基线持平） |
| 前端类型检查 + 生产构建 | `npm run typecheck && npm run build` | 通过（vue-tsc 无错误，vite 构建成功） |
| 前端全量单测 | `npm run test`（vitest 4.1.11） | **827 通过 / 101 文件**（基线 822 → +5） |
| npm 依赖审计 | `npm audit --registry=https://registry.npmjs.org` | **0 vulnerabilities**（修复前 1 high + 2 moderate） |
| Playwright 全套（首轮） | `npm run test:e2e` | 76 通过 / 1 失败（`graph-routes` 侧栏用例在 12 分钟满负载下偶发；单独复跑 5/5 通过） |
| Playwright 全套（最终复跑） | `npm run test:e2e` | **77/77 全部通过（10.9 分钟）**，两处历史非确定性失败已根因修复（见下文） |
| 空数据库安装 | JAR + 空 DB `spec_agent_accept_install` :8090 | 40 个 Flyway 迁移至 v41，健康 UP，worker ENABLED，回环绑定 |
| 旧凭据升级迁移 | 注入 4 行明文 → 重启 | `plaintextEncrypted=4`，物理行全部 `enc:v1:*`，API 仅掩码输出 |
| 重启解密 | 再次重启同一库 | 读取正常，`hasKey`/掩码后缀正确 |
| 备份恢复演练 | `pg_dump` → 恢复库 `spec_agent_accept_restore` → 重启 | 解密正常，项目数据完整（同主密钥文件前提，已写入 README） |

新增回归测试清单：后端 39（CrossOriginCredentialGuardTest 12、ProjectDeletionRunLifecycleGuardTest 5、AgentRunOrphanRecoveryIntegrationTest 3、ModelCredentialCryptoTest 10、ModelCredentialEncryptionMigrationTest 2、AccessBoundaryTest 4、RoutelessNodeQueryRunViewContractTest 2、及既有套件内新增断言）；Python Brain 9（test_config_fail_closed.py）；前端 5（customOriginGuard.spec.ts）。

## 配置变化与运维要点

| 配置 | 默认值 | 说明 |
|---|---|---|
| `server.address` | `127.0.0.1` | `SERVER_ADDRESS` 覆盖；非回环需 `SPEC_AGENT_ALLOW_NON_LOOPBACK=true` 否则启动失败 |
| `spec.agent.brain.internal-secret` | 空（生成文件） | 未配置时生成 `backend/data/internal-secret.txt`；start-dev.bat 自动生成并两端同步 |
| `spec.agent.secret.master-key` | 空（生成文件） | 未配置时生成 `backend/data/secret-master.key`；备份必须包含它 |
| `SPEC_AGENT_SECRET_MASTER_KEY_FALLBACK` | 空 | 轮换期旧密钥；启动迁移自动重写后可移除 |
| `SPEC_AGENT_BRAIN_WORKER_STARTUP_RECOVERY` | `true` | 多实例部署必须关闭（当前产品不支持多实例） |
| `SPEC_AGENT_BRAIN_MODEL_MODE` | `fake` | 仅 fake/broker 两值；正式入口 start-dev.bat 显式 broker |

文档更新：`README.md`（安全与访问边界 / 凭据加密与主密钥 / 备份恢复 / 故障处理）、`docs/DEVELOPMENT_ENVIRONMENT.md`（Brain 不再进 Docker、模式枚举 fail closed、内部密钥生成、启动恢复、回环绑定守卫）。

## 测试数据与清理

- 创建的一次性数据库：`spec_agent_accept_install`、`spec_agent_accept_restore`（演练完成，**保留**供复查，可随时 `DROP DATABASE`）；备份文件 `scratch/accept-backup.sql`。
- 复现用临时进程（两个回环接收端、R8 验证脚本与哑进程）已全部停止并删除；`scratch/r8-verify` 已清理。
- 浏览器验证项目及其数据库残留已删除。Playwright 产物在 `frontend/test-results/`（含本轮截图目录）。
- 既有测试库 `spec_agent_test` 中由本轮调试产生的遗留行已手工清理（非终态 run 归零后复跑全绿）。

## 未验证事项与原因

1. **真实模型厂商端到端验收**（澄清→回答→状态更新→规格、超时/限流/失败恢复）：无授权凭据，普通测试零真实调用（fake/broker 全链路已由跨语言门禁覆盖）。仍是交付真实密钥前的外部门禁。
2. **Java/Python 全量漏洞扫描**：本机网络不可达 NVD/PyPI（npm 官方 registry 可达）。已如实记录覆盖缺口与建议（Spring Boot 补丁升级、网络可达处补跑 pip-audit）。
3. **从更早历史 schema 版本的跨版本迁移演练**：本次验证了"空库安装 + 旧版明文凭据数据升级迁移 + 重启"，未覆盖 V1→V41 逐版本升级（无历史版本产物）。
4. **`ACCEPT_AGENT_PROPOSAL` 永久 barrier 的 UI 实测**：代码路径存在（`GraphOperation reversible=false`），本轮浏览器路径未触发显式提案受理场景；"继续生成"重试路径实测可撤销。
5. **局域网/公网部署、多实例**：按产品边界不适用；启动守卫会阻止非回环不安全启动，恢复机制按单实例设计。

## 最终交付判断

- **本机单用户（推荐形态）**：**可以正式交付**。回环绑定、安装实例独立内部密钥、凭据 AES-GCM 加密与自动迁移、跨来源凭据隔离、删除保护与并发边界、中断恢复、fail-closed 配置全部落地并有回归。真实密钥接入前建议完成上节第 1、2 项外部门禁。
- **受控演示**：可以（与原审查一致，且已知限制更少）。
- **局域网/公网/多实例**：仍不适用（产品无认证层；守卫会明确阻止不安全启动）。

## 最终复跑结果（报告定稿前补充）

- Playwright 全套最终复跑：`npm run test:e2e` → **77 个用例全部通过，0 失败（10.9 分钟）**。此前的两处非确定性失败均已根除并复验：`graph-routes` 侧栏用例（满负载偶发，单独复跑 5/5）；`graph-node-visibility` 两个用例（固定标题项目与遗留数据相撞 → 409，改为统一 `createProject` 唯一标题 + 自动清理后 2/2 通过）。
- 截图用例在独立目录复跑：26 张 PNG 全部成功落盘 `frontend/test-results/provider-shots/2026-09-26T20-00-22-857Z/`。
- `gradlew evalBFast`（Agent 确定性评估门禁）：**通过**（BUILD SUCCESSFUL，0 失败）。
- 最终前端 `npm run typecheck`：通过。

---

# 第二轮修复（2026-09-27）：启动边界与失败位置独立恢复

复核文档 `docs/NODE-RECOVERY-REVIEW-AND-DESIGN-2026-09-27.md` 指出的三类问题已全部修复并有针对性回归；"失败位置独立重试"功能已按产品设计实现并在真实浏览器中验证（含截图）。本轮全部验证使用 fake Brain/确定性失败指令与合成数据，零真实模型调用。

## 一、启动边界修复

### 1.1 关闭启动恢复导致 worker 永久暂停 ✅

- **根因**：`RunWorkerPoller.recoveryCompleted` 默认 false，唯一解除入口在 `AgentRunOrphanRecoveryListener`，而该监听器在 `startup-recovery=false` 时不创建 → worker 启用但队列永久停摆。
- **修复**：新增 `WorkerPollingGate`（agent/runtime），把"等待应用就绪"与"是否先执行孤儿恢复"拆分为独立事实——恢复启用时由恢复监听器在收敛（或失败）后开门；恢复显式关闭时门控自身在应用就绪即开门。`RunWorkerPoller.poll()` 只读门控。恢复失败记录错误并照样开门（队列活性优先），健康检查暴露 `orphanRecovery=FAILED`。
- **健康状态**：`/api/health` 现区分三态——UP（轮询开放，含恢复失败告警字段 `orphanRecovery`/`orphanRecoveryError`）、503 `WORKER_POLLING_PENDING`（worker 启用但轮询未开放：就绪前/恢复进行中）、503 `AGENT_WORKER_UNAVAILABLE`（worker 关闭）。恢复失败不再可能被误读为队列正常。为满足架构规则（common 不得依赖 agent），门控状态经 `common.health.WorkerRuntimeStatus` 接口暴露，由 `WorkerPollingGate` 实现。
- **测试**：`WorkerStartupGateTest`（3 个独立上下文：恢复开/恢复关/worker 关）——恢复关闭时应用就绪后 `poll()` 立即认领并执行排队任务（修复前 run 永远停留 created）；`HealthControllerTest`（UP / PENDING 503 / 恢复失败 UP+告警 三态）。

### 1.2 空监听地址绕过回环守卫 ✅

- **根因**：`LoopbackBindingGuard` 对 null/blank 直接放行；空 `server.address` 会让嵌入式服务器通配监听。
- **修复**：两层防线——(1) 配置守卫拒绝空/空白地址（显式风险开关也不豁免空地址，它只豁免"知情的非回环部署"）与非回环地址；(2) 新增 `WebServerInitializedEvent` 校验：服务器启动后读取 Tomcat 连接器真实生效的监听地址，非回环且未声明知情部署时启动失败（无法可靠读取地址时不做猜测，由配置层守卫覆盖）。
- **测试**：`AccessBoundaryTest` 重写——null/空串/空白拒绝、IPv4/IPv6/localhost 回环放行、通配地址拒绝、显式开关对非回环生效但对空地址无效。

### 1.3 单执行器互斥（数据库范围所有权）✅

- **根因**：回环绑定只限制网络可达范围；第二个后端进程可用另一端口连同一数据库，其启动恢复会误终止第一个执行器的在途任务。启动恢复本身没有所有权检查。
- **修复**：新增 `ExecutorLease`（agent/runtime，条件 `worker.enabled=true`）——PostgreSQL 会话级 advisory lock（固定键 `736251904113`）+ 专属连接；Bean 构造即获取，获取失败（第二个执行器）以明确错误终止启动，绝不进入执行状态；孤儿恢复监听器与轮询循环都以租约为构造依赖（所有权先于一切恢复/认领），恢复前与每个轮询 tick 均校验 `owned()`；进程崩溃时连接消失、锁由数据库立即释放（所有权可恢复）；正常关闭 `destroy()` 显式释放。关闭启动恢复不改变租约语义。**守卫覆盖范围如实说明：互斥以"执行器进程持有租约"为准，覆盖同一数据库上的所有 worker 进程；API-only 进程（worker 关闭）不取租约也不执行任务。**
- **测试**：`ExecutorLeaseTest`——同库第二个执行器构造即被拒绝（明确错误消息）；释放后后续执行器可获取；失去所有权后 `assertOwned()` 拒绝。worker 启用的 Spring 测试上下文之间以 `@DirtiesContext(AFTER_CLASS)` 串行化租约获取。

## 二、失败位置独立恢复（按产品设计实施）

### 后端（可信持久化 → 任务级契约）

- **读侧**：新增 `AgentRunRecoveryService.listUnresolved` + `GET /api/v1/projects/{projectId}/agent-runs/unresolved`（`UnresolvedFailureView`）：每条未解决失败绑定完整任务身份（failedRunId、operation、routeId 允许 null、sourceNodeId）+ 服务端判定的 `availableAction`/`actionLabel`（继续处理 / 重试生成 / 重试换题 / 重试生成规格 / 重试该查询 / 前往模型设置 / 查看变化）+ 原因码与用户可读文案。收敛规则：同任务身份（路线+来源节点+操作家族）的后续成功 → 失败视为已解决不展示；更新的同身份失败 → 由最新者代表（不叠卡）；同身份在途 run 或重试链在途 → 条目携带 retryRunId（UI 禁用重复重试并显示进度）；路线关闭/来源节点被撤回/起草锚点过期 → stale，不再提供重试。
- **提交侧**：`POST /agent-runs/{runId}/retry`（`AgentRunRecoveryService.retry`）——只接收失败任务 id；原始意图从持久化 `RUN_CREATED` 事件还原，绝不信任前端 payload；目标路线永远显式取自失败任务，**绝不回退 Active/first/latest**。派发到既有领域命令：Answer 已持久化 → `RESUME_ANSWER`（复用现有修复路径，不重复创建 Answer）；未持久化的回答 → 原选项/自由文本重发；换题 → `REGENERATE_NODE`；规格 → `GENERATE_SPEC`；节点查询 → 原问题原路线（routeId 允许 null）；起草/续跑 → 原路线起草。重试幂等键 `retry:<failedRunId>`：双击/跨标签页并发最多产生一个有效尝试，在途重复请求返回同一个 run（对账语义）；重试再次失败时新尝试以 `retry:<retryRunId>` 链接旧失败，清单只显示最新者。缺 `RUN_CREATED` 事件的旧失败返回 `RECOVERY_CONTEXT_UNAVAILABLE`（明确说明原因，不猜测重放）。

### 前端（节点 UI → store → API → 服务端契约）

- `runRegistryStore`：失败清单（以 failedRunId 为键）+ `failuresForNode(nodeId, routeId)`（共享节点按路线精确隔离）+ 乐观 retrying 标记与服务端 `retryRunId` 对账 + 已被取代的 FAILED run 从注册表清除（不渲染僵尸占位卡）+ 项目切换 `clear()` 全量重置。
- `workspaceLoader`：`rebuildUnresolvedFailures` 在加载/刷新/重试后与服务端对账（硬刷新、切换项目返回、后端重启后恢复入口都在）；`pollRunChainToTerminal` 在 run 到达失败终态时立即对账（不等下一次刷新）。
- `workspaceRuns.retryFailedRunAction`：任务身份驱动（替代依赖全局 `forkDraftRetryRouteId`/`manualModelRetry` 的 `retryPendingAgentRunAction`，旧入口保留兼容）；会话过期守卫贯穿始终。
- `NodeRecoveryBar.vue`（新组件）：紧凑恢复栏，左侧两行内原因、右侧 32×32 圆箭头按钮；常驻不依赖 hover；`aria-label`/`title`/键盘焦点完整（`:focus-visible` 描边、原生 button）；`nodrag` + 事件隔离（点击不拖画布/不切换节点）；在途时转进度态并禁用；多个未解决失败显示"N 项待处理"展开列表（路线 + 动作逐项选择，绝不猜目标）；配置错误渲染"前往模型设置"按钮；过期目标渲染"查看变化"定位。
- `GraphQuestionNode`：真实节点底部渲染恢复栏；占位卡（pending 卡）的重试按钮改为携带 `pendingFailure` 任务身份（`retry-failure` 事件，不再发空事件）。**实施中发现并修复一个自引入缺陷：恢复栏最初插入在 v-if/v-else-if 内容链中间破坏分支互斥（画布节点同时渲染作答表单与历史等待块），已移出链外并有 e2e 回归锁定。**
- `GraphKnowledgeNode`：知识节点恢复栏（问 AI / 换题失败的绑定入口）。
- `SpecDock`：规格生成失败在规格面板内提供"重试生成规格"（绑定生成路线的失败任务）。
- `NodeInspector`：节点问 AI 失败在对应检查器消息内提供重试（身份来自服务端失败清单）。
- `WorkspaceView`：顶部统一入口"N 项待处理"横幅（汇总 + 定位 + 逐项动作）；`recoveryByNode` 以 `${nodeId}::${routeId}` 复合键投影（共享节点不同路线互不覆盖）；起草家族失败渲染为源节点下游的失败占位卡（任务视图，不创建业务节点、不进入导出/撤销历史，同一恢复链仅一张，重试原位转生成中，成功后被真实产物取代）。
- 占位卡语义区分严格遵守：失败文案由服务端 `actionLabel` 决定（"回答已保存，后续处理未完成→继续处理"/"重试生成"/"重试换题"/"重试生成规格"/"前往模型设置"），不再统一叫"重新生成"。

## 三、验证结果（本轮独立重跑，非沿用历史产物）

| 验证 | 命令 | 结果 |
|---|---|---|
| 后端全量确定性测试（含架构门禁） | `gradlew testNonLive --rerun-tasks` | **1,630 通过 / 0 失败 / 0 错误 / 0 跳过**（上轮 1,613 → +17） |
| 前端全量单测 | `npm run test`（vitest 4.1.11） | **837 通过 / 101 文件**（上轮 827 → +10） |
| 前端类型检查 + 生产构建 | `npm run typecheck && npm run build` | 通过 |
| 失败恢复浏览器验证 | `npx playwright test e2e/failure-recovery.spec.ts` | **2/2 通过**（含硬刷新后恢复入口仍在、键盘 Enter 触发恢复、Answer 恰好一条） |
| 全套 E2E | `npm run test:e2e` | **79/79 全部通过（11.3 分钟）** = 77 个既有用例 + 2 个新增失败恢复用例；本轮曾出现并被修复的问题：恢复栏插入破坏节点模板分支互斥（e2e `core-clarification` strict-mode 冲突暴露，修复后全绿） |

新增后端测试明细：`AgentRunRecoveryIntegrationTest`（9 项：回答已保存→RESUME 且不重复 Answer；双击幂等同 run；成功取代后隐藏+409；双路线失败各自恢复且切换 Active 不串目标——两条路线共享同一来源节点；节点撤回→STALE 拒绝重试；换题重试不会变成起草；节点查询重试保留原问题且 routeId=null 合法；旧失败缺事件→RECOVERY_CONTEXT_UNAVAILABLE；失败→重试→再失败只显示最新失败不叠卡；HTTP 契约）、`FailureMarkerRecoveryIntegrationTest`（确定性指令注入 → 真实 worker 失败 → CONTINUE_PROCESSING → 重试 → 成功 → 清单清空，Answer 恒为 1）、`WorkerStartupGateTest`（3 组合）、`ExecutorLeaseTest`、`HealthControllerTest`（3 态）、`AccessBoundaryTest`（空地址等）。

### 浏览器验证截图（真实 Chromium + 本地后端 + fake 引擎 + 确定性失败指令）

留存于 `scratch/recovery-screenshots/`（运行产物目录 `frontend/test-results/failure-recovery-shots/<时间戳>/`）：

- `answer-failed-recovery-bar.png` — **节点失败**：提交携带 `[[fail-state-update:1]]` 指令的回答后，源节点卡底部的恢复栏（原因 + 32px 恢复图标，服务端判定"继续处理"）。
- `answer-recovered.png` — **恢复成功**：重试后恢复栏消失，工作区出现"已从上次失败处恢复"，Answer 保留且经图读模型断言恰好一条；自治续跑按既有规则继续。

"重试中"的进度态（图标旋转 + 按钮禁用）在 fake 引擎下毫秒级完成、截图时机不可靠，由 `NodeRecoveryBar.spec.ts` 的禁用/进度断言（6 项）覆盖。

### 关键场景覆盖对照（任务要求的验证清单）

| 场景 | 覆盖 |
|---|---|
| 启动恢复开/关、worker 开/关组合 | `WorkerStartupGateTest`（3 上下文） |
| 空绑定地址不能静默暴露 API | `AccessBoundaryTest.emptyBindAddressIsRejected` |
| 同库第二个执行器不能误恢复 | `ExecutorLeaseTest`（构造即拒绝，恢复/认领前强制所有权） |
| 两条路线同时失败各自恢复不串目标 | `AgentRunRecoveryIntegrationTest.twoRouteFailures…`（双路线共享同一来源节点） |
| 失败后切换 Active 再点原卡仍恢复原目标 | 同上（`setActiveRoute` 后重试仍绑定原路线） |
| 共享节点不同路线任务不相互覆盖 | 后端同上 + 前端 `failuresForNode` 单测（复合键） |
| 刷新与重启后恢复入口仍存在 | e2e 硬刷新断言 + loader 对账（重启后状态在 DB，同一读侧路径） |
| Answer 已保存后恢复不重复创建 Answer | `FailureMarkerRecoveryIntegrationTest` + e2e 图读模型断言 =1 |
| 换题重试不变成起草；规格重试不变成生成问题 | `AgentRunRecoveryIntegrationTest`（REGENERATE_NODE/GENERATE_SPEC 映射断言） |
| 超时但服务端已接受时不重复提交 | 重试在途 → 返回同一 run（幂等接受语义）+ 前端 RECOVERY_IN_FLIGHT 对账 |
| 双击及并发恢复幂等 | `retry:` 确定性键 + 集成断言（两次 retry 同 runId） |
| 路线变化/节点删除/已有进行中任务冲突 | STALE 判定（路线关闭/节点撤回/锚点过期）+ SUPERSEDED/IN_FLIGHT 409 |
| 失败→重试→再失败不叠卡；成功后提示消失 | `retriedFailureThatFailsAgainShowsSingleLatestFailure` + registry 僵尸清理 + e2e |
| 键盘操作、画布事件隔离、设置页往返 | e2e 键盘用例 + NodeRecoveryBar 单测（nodrag/aria/焦点）；设置页跳转经 `GO_TO_MODEL_SETTINGS`（浏览器往返返回后失败仍由服务端清单恢复） |

## 四、本轮未通过/未验证项（如实保留）

- **真实厂商模型验收**：仍无授权凭据，未执行（与上轮一致）。
- **Java/Python 漏洞扫描**：本机仍不可达 NVD/PyPI，未执行；Spring Boot 3.3.2 补丁升级建议维持"待办门禁"。
- **从更早历史版本的逐版本升级演练**：未执行（无历史版本产物）。
- **`ACCEPT_AGENT_PROPOSAL` 永久 barrier 的浏览器实测**：本轮通过"继续生成"重试路径实测了可撤销的续跑起草；显式提案受理的 barrier 路径仍由既有代码与单元覆盖，未做浏览器实测。
- **占位卡在"起草失败"场景的浏览器实测**：确定性失败指令仅覆盖 STATE_UPDATE（回答已保存场景）；起草失败占位卡由后端集成测试 + registry/投影单测 + GraphQuestionNode 占位卡单测覆盖，浏览器实测待起草类失败注入基础设施。
- **执行器互斥的"进程崩溃后所有权恢复"**：以 advisory lock 语义（连接关闭即释放）+ `ExecutorLeaseTest` 的显式释放后重获验证；未做"kill -9 第二进程"的真实进程级演练。


---

# 第三轮修复（2026-09-27）：闭合第二轮独立复核反例

复核文档 `docs/DELIVERY-ROUND2-REVIEW-2026-09-27.md` 列出的可复现缺陷（R2-A/B/C/D/E 与测试证据缺口）已逐项修复并验证。本轮全部验证使用 fake Brain/确定性注入与隔离/测试数据库，零真实模型调用，未触碰用户业务数据。

## 结论变更（对第二轮报告的纠正）

第二轮报告中以下结论已被本轮推翻并纠正：

1. **"回环守卫提供启动保障"（R5/1.2）不成立 → 已修复。** 守卫的 `ApplicationRunner` 入口在真实启动中不会被调用（`run(ApplicationArguments)` 无人调用），实际绑定校验反射调用的 `Connector.getProperties()` 在本项目 Tomcat 10.1.26 上不存在，异常被吞后检查放行——复核的最小 Spring/Tomcat 复现（0.0.0.0 绑定成功启动）成立。本轮改为：配置校验移到 Bean 构造期（单例实例化先于内嵌服务器启动，监听前拒绝）；实际绑定校验改用真实 `Connector.getProperty("address")` API（返回 `InetAddress`）并逐个校验全部连接器；地址读取不到/为空一律 fail closed（空地址=通配绑定），绝不当作安全回环。
2. **"执行器互斥由租约保证"（1.3）不完整 → 已修复。** 旧 `owned()/assertOwned()` 只读内存布尔值：租约物理连接被终止后，新执行器可取得数据库锁而旧执行器仍自认持锁（复核的 LeaseHarness 反例成立）。本轮：所有权判断落到数据库实际状态（`pg_locks` 存在性检查 + 本进程后端 pid 比对）；丢失即永久闩锁，绝不静默重连/重取；轮询认领停止，健康检查返回 503 `EXECUTOR_LEASE_LOST`；在途写入通过 `ExecutionFence` 在 Answer finalize、补丁、规格快照、节点产物与全部 run 检查点/终态写入前同步验证所有权——新执行器接管后旧执行器的业务提交以 `LeaseLostException` 失败回滚；`fail()` 刻意不设 fence（诚实终态化永远被允许）。无租约进程（API-only/测试驱动）自动放行。
3. **"起草失败占位卡已实现"（第二轮第二节）有反例 → 已修复。** 复核的投影函数级复现成立：legacy `pendingRouteProjection` 先占 `seen` 却不带失败身份（失败卡无按钮）；registry 的 FAILED 条目加入后不进 `seen`（同一 run 两条投影）。本轮 `pendingProjections` 重写为"先按恢复链身份（operation+路线）合并状态，再生成唯一投影"，并加输出级 runId+链双重去重；legacy 卡在 run 被服务端清单/注册表清除后作为过期卡丢弃，绝不占用链槽位；恢复在途（retryRunId）时同一张卡原位转进度态。修复过程中浏览器实测还发现并修复了一个复核未列出的缺陷：**硬刷新后携带 retryRunId 的在途重试没有任何前端观察者**，失败终态永远无法对账（UI 永久停留"重试中"）——现在 `rebuildUnresolvedFailures` 会为在途重试续接链路轮询并在终态后自动重新对账。
4. **"brain_unavailable / model_provider_failure 一律导向设置页"（R2-D）→ 已修复。** 这两个失败码同样覆盖连接拒绝、5xx、服务不可用与限流，不证明凭据配置错误；`isModelConfigFailure` 收窄为确定的配置类码（`NotConfiguredException`/`model_not_configured`），临时故障按操作家族给出可重试动作——服务恢复后用户从原失败位置直接重试，重试绑定原任务与原路线（不回落 Active）。UI 的"前往模型设置"入口保留给真正的配置错误（NodeInspector 的 go-settings/locate 已实际接线）。
5. **"验收截图正常"（R2-E）→ 已修复。** `GraphNodeShell.vue` 模板内的两段 JS 风格 `/** ... */` 注释被当文本渲染，已改为合法 HTML 注释；全仓 Vue 模板扫描确认无同类问题。修复后截图经人工检查：节点内容、恢复栏与操作轨道正常，无渲染注释文本。
6. **同节点不同 NODE_QUERY 问题被错误合并（第二轮收敛规则）→ 已修复。** 恢复服务的"同任务身份"判定在 node_query 家族上追加可信意图（持久化 RUN_CREATED payload 的 question）比对——后一个不相关问题成功不再隐藏前一个失败；只有同一问题的重试链才收敛。
7. **孤儿恢复部分失败被吞掉 → 已修复。** `recoverOrphans` 返回 `OrphanRecoveryResult(recovered/failed/found)`，部分失败由监听器上报 `ORPHAN_RECOVERY_PARTIAL_FAILURE` 到健康检查，不再把"恢复了大部分"误读为恢复成功。

## 逐项改动与验证

### R2-A 回环守卫（P1）

- **改动**：`LoopbackBindingGuard` 重写——配置校验在构造期（监听前）执行；`WebServerInitializedEvent` 用真实 Tomcat API 读取每个连接器的实际绑定地址；空/null 地址按通配绑定拒绝；显式知情开关（`SPEC_AGENT_ALLOW_NON_LOOPBACK`）范围不变，只豁免"知情的非回环部署"，不豁免空地址。
- **测试**：`LoopbackBindingGuardStartupTest`（6 项，全部经真实 Spring/Tomcat 上下文启动）——回环配置真实启动成功且连接器地址为 127.0.0.1；IPv6 回环真实启动；通配配置在真实启动中失败；**配置声明回环但连接器实际绑定 0.0.0.0 时真实启动失败**（定制器制造配置/实际分歧）；额外连接器未设置地址时 fail closed；显式开关对实际绑定校验生效。`AccessBoundaryTest`（6 项）覆盖 null/空/空白/IPv4/IPv6/通配/开关。

### R2-B 执行器租约（P1）

- **改动**：`ExecutorLease` 重写——获取时记录 `pg_backend_pid`；`owned()`/`assertOwned()` 用 `pg_locks`（classid/objid 拆键）+ pid 比对做数据库同步验证（`owned()` 带 500ms 缓存窗口，`assertOwned()` 永远同步）；丢失永久闩锁（`lost/lostReason`），关闭租约连接；`WorkerPollingGate`、健康检查（503 `EXECUTOR_LEASE_LOST`）联动；`ExecutionFence` 统一接入 `RunWorker.executeRun`、`AnswerCycleService`（Answer finalize/补丁）、`DecisionExecutionService`（起草/换题/续跑产物）、`ArtifactCycleService`（规格快照）与 `AgentRunService` 全部检查点/终态写入；`RunFailureReasons` 新增 `EXECUTOR_LEASE_LOST` 用户文案。
- **测试**：`ExecutorLeaseLossIntegrationTest`（3 项，真实 PostgreSQL）——`pg_terminate_backend` 真正终止租约会话后旧执行器立即失去所有权并永久闩锁（空闲后也绝不静默重取）；同库第二个执行器被拒绝、新执行器在旧执行器失去所有权后可获取；**新执行器接管后旧执行器"延迟返回"的 checkpoint/complete 写入被 fencing 拒绝，而 fail()（诚实终态化）仍被允许**；正常释放后可重新获取。既有 `ExecutorLeaseTest` 保持通过。

### R2-C 起草失败占位卡（P1）

- **改动**：`WorkspaceView.pendingProjections` 重写（见结论变更第 3 条）；`runRegistryStore` 新增 `markRetryPending`/`clearRetryMark`（请求发出前即写入乐观标记，失败/结果未知回滚）；`workspaceRuns.retryFailedRunAction` 重排（请求前即防重+显示进度，失败/结果未知/会话切换/最终清理全路径处理）；`workspaceLoader` 在途重试对账续接（同上）。
- **起草失败注入**：`DeterministicEngineFaultPlan` 新增 `[[fail-decision:N]]` 指令——由回答文本武装，只在确定性引擎（engine=fake）内生效；回答周期内部 DECISION（kind=ANSWER_SUBMITTED）与只读 NODE_QUERY 不消耗预算，独立起草/续跑/换题按预算逐次失败；普通文本零影响（`DeterministicEngineFaultPlanDraftInjectionTest` 3 项：武装不干扰回答周期、NODE_QUERY 不消耗、无指令不武装）。
- **浏览器验证**（真实 Chromium + 本地后端 + fake 引擎，截图留存 `scratch/recovery-screenshots/`）：
  - `draft-failed-placeholder.png` — fork 分支首问起草失败后的占位卡（服务端"重试生成"身份 + 恢复按钮）。
  - `draft-recovered.png` — 原位重试 → 再次失败（仍只一张卡）→ 硬刷新后恢复身份仍在 → 再次重试成功，占位卡被真实节点取代。
  - 浏览器路径断言：分支失败 → 原位重试 → 再次失败（旧 runId 的占位卡消失、新失败接替，不叠卡）→ 硬刷新 → 成功 → 占位卡 0、顶部横幅 0、分支出现真实问题节点、该回答恰好一条（不含 fork 继承副本）。

### R2-D 恢复分类（P2）

- **测试**：`AgentRunRecoveryIntegrationTest` 新增 3 项——`brain_unavailable` 起草失败给出 `RETRY_GENERATION` 且重试绑定原路线；`model_provider_failure` 节点查询失败给出 `RETRY_NODE_QUERY`；同节点同路线不同问题的三个 NODE_QUERY 各自独立展示、无关问题成功不隐藏失败、同一问题的重试链才收敛。既有 `twoRouteFailures...` 用例中 `GO_TO_MODEL_SETTINGS` 断言已按新语义更新为可重试动作（该断言正是被复核否定的旧行为）。

### 测试证据缺口（第二轮复核"测试证据还需收紧"）

- **E2E 不再使用全局 `getByTestId('node-recovery-bar').first()`**：恢复身份先从服务端 `/agent-runs/unresolved` 取得，再定位 `[data-test="graph-question-node"][data-node-id="<sourceNodeId>"]` 容器内的恢复栏；顶部横幅（`pending-recovery-banner`）单独断言存在，重试点的是节点内图标。
- **键盘用例的画布隔离有了真实状态断言**：在恢复图标上执行真实鼠标按下-拖动-抬起手势，断言节点位置不变且恢复未发起（nodrag 隔离）；随后键盘 focus+Enter 走恢复路径并断言 Answer 恰好一条。
- **retryFailedRunAction 即时状态**：`retryFailedRunImmediate.spec.ts`（5 项）——延迟响应下乐观标记先于网络往返生效、双击不重复提交、失败/结果未知回滚、会话切换不写脏状态。后端幂等（`retry:` 确定性键）保留并由后端集成测试覆盖。
- **节点查询重试成功后的检查器展示（6-5）**：重试成功后 `refreshNodeQueryResult` 以新 runId 刷新检查器查询视图，不再停留在旧失败结果；硬刷新后恢复身份由服务端失败清单 + 节点恢复栏恢复。

## 实际执行的验证与结果

| 验证 | 命令 | 结果 |
|---|---|---|
| 后端全量确定性测试（含架构门禁） | `gradlew testNonLive --rerun-tasks` | **1,645 通过 / 0 失败 / 0 错误 / 0 跳过**（上轮 1,630 → +15：守卫启动 6、租约丢失 3、注入 3、恢复分类 3） |
| 前端全量单测 | `npm run test` | **842 通过 / 104 文件**（上轮 837 → +5：即时防重 5） |
| 前端类型检查 | `npm run typecheck` | 通过（vue-tsc 无错误） |
| 失败恢复浏览器验证（含起草失败全路径） | `npx playwright test e2e/failure-recovery.spec.ts` | **3/3 通过**（回答失败恢复栏+硬刷新+不重复 Answer；键盘+真实拖拽隔离断言；起草失败→原位重试→再次失败→刷新→成功） |
| 全套 E2E | `npm run test:e2e` | **80/80 全部通过（11.6 分钟）** = 77 个既有用例 + 3 个失败恢复用例 |
| 前端生产构建 | `npm run build` | 通过（vite 构建成功；chunk 体积警告为既有状态，非本轮引入） |
| 后端最终复跑确认 | `gradlew testNonLive` | BUILD SUCCESSFUL（当前代码状态下全量通过） |
| 恢复截图人工检查 | `frontend/test-results/failure-recovery-shots/2026-09-27T10-58-55-954Z/`（副本留存 `scratch/recovery-screenshots/`） | 4 张截图逐张检查：恢复栏/顶部横幅/占位卡/成功态渲染正确，**节点卡内无任何被渲染的开发注释**（R2-E 修复证实） |

## 本轮未完成事项（如实保留）

- **真实厂商模型验收、Java/Python 全量漏洞扫描、历史逐版本升级演练、`ACCEPT_AGENT_PROPOSAL` 永久 barrier 的浏览器实测**：与上轮一致，仍为外部门禁，未执行。
- **租约 fencing 的"单条语句窗口"**：检查与写入之间存在以单条语句为界的固有竞争窗口（无分布式共识不可消除）；丢失判定一旦做出即永久拒绝，窗口不会随时间扩大，已在代码 Javadoc 中如实说明。
- **多实例部署**：仍不支持，租约只强制"同一时刻至多一个执行器"。

---

# 第四轮修复（2026-09-27）：闭合第三轮独立复核反例

复核文档 `docs/DELIVERY-ROUND3-REVIEW-2026-09-27.md` 的 R3-A（P1 执行器写入隔离）、R3-B（P2 刷新续接完成流程）已逐项修复并验证；按用户明确要求删除了旧的全局恢复入口（继续生成/重新请求/重试起草），失败恢复统一绑定任务身份。本轮全部验证使用 fake Brain/确定性注入与隔离测试库，零真实模型调用，未触碰用户业务数据。

## 结论变更（对第三轮报告的纠正）

1. **"检查与写入之间存在以单条语句为界的固有竞争窗口（无分布式共识不可消除）" → 该表述不成立，已删除并修正实现。** 第三轮的实现只在租约连接上做一次性检查（`assertOwned()`），业务写入走另一条连接，两者之间没有数据库事务顺序——线程可以在检查通过后暂停任意时间，期间接管发生、任务被新所有者恢复，旧执行器恢复后仍能把任务改回非终态（复核的 FenceRaceHarness 反例成立：`STATE_AFTER_NEW_OWNER_RECOVERY=failed`、`STATE_AFTER_OLD_DELAYED_WRITE=model_called`）。本轮改为**同一数据库内的原子所有权代次协议**（见下），检查与写入在同一条条件 UPDATE 语句内完成，接管一提交、旧执行器的后续写入即落空——不存在"检查通过后仍可写入"的时间窗口。PostgreSQL 内可以建立可靠的事务顺序，不再需要任何"不可消除"的让步表述。
2. **"fail() 刻意不设 fence：诚实终态化永远被允许" → 已按本轮要求修正。** 旧 `fail` 的 UPDATE 只按 id、无条件：旧执行器可以把已 completed 的任务改回 failed、覆盖新所有者写入的失败原因（复核反例成立）。本轮 fail() 改为有条件的原子终态化：只允许把**非终态**的 run 置为 FAILED；丢锁执行器的 fail 落空（0 行、幂等 no-op），它只完成本地故障记录（worker 日志），业务恢复由当前合法所有者（启动孤儿恢复/接管恢复）负责。
3. **"旧执行器无法提交业务结果"（第三轮表述）在第三轮实现中并不成立**——`assertOwnership()` 只是写入前的另一次检查。本轮把业务产物写入（Answer/补丁/规格快照/节点产物）与带所有权条件的检查点写入放进**同一数据库事务**，条件不满足即整体回滚。

## R3-A / P1：所有权校验、业务写入与接管的统一原子协议

### 不变量

1. 全库单行 `executor_ownership.epoch` 是单调递增的所有权代次；代次只在 advisory lock 之下获取并递增——**接管必然意味着新代次**（advisory lock 保证旧持有者会话已消失）。
2. run 行的 `owner_epoch` 记录认领者的代次；认领语句在领取的同时验证"全局代次仍等于本执行器代次"——**丢锁执行器不能认领新任务**。
3. run 行的一切状态写入（检查点/终态）由单条条件 UPDATE 完成，同一语句内验证三个条件：(a) 全局代次仍等于本执行器代次；(b) run 行认领代次不晚于本代次；(c) run 未终态。**与线程调度无关：接管一提交，旧执行器的任何后续写入立即落空（0 行）**，`AgentRunService` 以 `LeaseLostException` 拒绝，调用方事务据此回滚。
4. 业务产物（不可变 Answer、补丁、规格快照、节点产物）的落库与带所有权条件的检查点写入在**同一事务**：旧执行器的检查点落空即产物一并回滚，**绝不脱离所有权提交业务结果**。外部副作用（模型调用、能力调用）不在事务内，继续遵守既有幂等键边界——数据库回滚不能撤销已发出的外部请求（如实边界，不声称回滚外部请求）。
5. 终态不可覆盖：complete/fail 都带 `status NOT IN ('completed','failed')` 条件；重复失败上报是幂等 no-op。
6. 无执行器租约的进程（API-only、测试驱动）保持历史写入行为（产品边界：单执行器交付，无租约即无执行器之争）。

### 实现

- **迁移 `V42__executor_ownership_epoch.sql`**：`agent_runs.owner_epoch` 列 + `executor_ownership` 单行表（epoch 从 0 开始）。
- **`ExecutorLease`**：acquire() 在 advisory lock 之下 `INSERT ... ON CONFLICT DO UPDATE ... RETURNING epoch` 原子获取代次（`epoch()` 暴露为 fencing token）；`assertOwned()` 的数据库同步校验保留（健康检查、认领入口的快速失败）。
- **`AgentRunRepository`**：全部 claim/checkpoint/complete/fail 写入带所有权与状态条件并返回行数；认领写入把代次落到 run 行。
- **`AgentRunService`**：携带 `ExecutionFence.ownerEpoch()`；0 行即抛 `LeaseLostException`（fail 除外，幂等 benign）。**检查点不再依赖"模型请求前的一次检查"**——模型返回后的实际执行位置（Answer/补丁/快照/节点落库）由同事务的条件检查点原子把关。
- **事务门**：`AnswerCycleService`（Answer finalize + markPersistedAnswer；补丁 saveOrReuse + markPersistedAnswerPatch）、`ArtifactCycleService`（快照落库 + markPersistedSpecSnapshot）、`DecisionExecutionService`（CREATE_NODE/REQUEST_USER_INPUT 的图变更 + 终态化；INVOKE_CAPABILITY 刻意不进数据库事务——外部副作用边界）、`ReplacementCycleService`（拓扑提交 + markPersistedNode + 终态化）。
- **`RunService`**：全部 claim 携带代次（丢锁执行器认领原子落空）。
- **`AgentRunFailureService`**/`RunWorker.failIfNotTerminal`：fail 条件落空只记本地日志，run 交由当前合法所有者恢复。

### 屏障与回归测试（`ExecutionFenceAtomicityBarrierTest`，真实 PostgreSQL）

精确复现第三轮反例的确定性顺序并断言新协议闭合，另覆盖全部要求场景：

| 场景 | 结果 |
|---|---|
| 检查通过 → 屏障暂停 → `pg_terminate_backend` 丢锁 → 新执行器接管（代次递增）并恢复任务为 FAILED → 放行旧执行器 | **旧写入被条件 UPDATE 拒绝（LeaseLostException），任务保持 FAILED**（旧实现反例：被改回 model_called） |
| 新所有者 complete 后旧执行器 fail | 落空（幂等），COMPLETED 不被覆盖 |
| 新所有者写入的失败原因 | 不被旧执行器覆盖（trace 保持） |
| 重复失败上报 | 幂等 no-op，不抛出 |
| 丢锁执行器认领新任务 | 认领原子落空（认领条件验证全局代次） |
| 正常执行（认领 → 检查点 → 终态）、终态不可被合法所有者改写 | 通过 |
| 真实断连检测、接管竞争（第二执行器构造即拒绝）、正常释放后重获 | 通过 |

既有 `ExecutorLeaseLossIntegrationTest` 按新语义更新：丢锁执行器的 fail 不再终态化 run（改为 no-op），由新所有者恢复为 FAILED——这正是本轮要求的行为变更。

## R3-B / P2：主动重试与刷新续接共用完成流程

- **共享收尾 `workspaceRuns.finalizeRetryCompletionAction`**：canonical 刷新（图/规格/需求状态）→ 失败清单对账 → 乐观标记清理 → 按操作刷新节点查询结果 → 反馈。主动重试（`retryFailedRunAction`）与硬刷新后由服务端清单续接的 watcher（`workspaceLoader.resumeInFlightRetryWatches`）走**同一条路径**。
- 修复前（复核反例）：续接 watcher 终态后只调 `rebuildUnresolvedFailures`，调用序列 `["poll","rebuild-failures"]`、图仍是旧版本，用户必须再次手动刷新。修复后：成功后新产物自动可见、恢复提示消失（`已从上次失败处恢复`）；失败/unknown 由服务端清单的最新失败接替；**unknown 不伪装成终态**（不显示恢复成功、乐观标记回滚）。
- 保留项目/会话过期保护（每次写入前校验会话身份，旧项目纪元绝不污染新纪元）、去重复 watcher（`watchedRetryRuns` + 终态跳过）、无递归刷新（共享收尾内的对账是幂等读取）。
- **确定性浏览器验收**（`e2e/retry-reload-auto-refresh.spec.ts`）：新增仅测试环境的确定性延迟指令 `[[delay-decision-ms:N]]`（`DeterministicEngineFaultPlan`，engine=fake 才注册，arm-then-trigger 模式——回答文本武装、独立起草 run 消耗），让重试 run 在后端**真实保持 running** 一个受控窗口：重试保持 running → 硬刷新 → 释放后端任务 → **不再刷新、不再点击**，断言失败清单收敛、分支 tip 前进（新问题节点出现）、顶部横幅与占位卡消失、共享收尾反馈可见、Answer 恰好一条。测试不依赖碰运气的 sleep（延迟是注入的确定性屏障，刷新发生在服务端确认重试在途之后）。

## 三、删除旧的全局恢复入口（用户明确要求）

### 删除清单

| 旧入口 | 位置 | 处置 |
|---|---|---|
| 全局"继续生成"（repairableAnswerId 驱动） | `recoveryPresentation.ts` saved 分支、`workspaceStore.repairableAnswerId` getter + `canonicalRepairableAnswerId` 状态、`restoreCanonicalRecoveryCheckpoints`、WorkspaceView 分发 | 连同状态、getter、canonical 检查点与 `findFinalizedAnswerForActiveTip` 一并删除（不是改名/隐藏） |
| 通用错误条全局"重新请求"分发 | `WorkspaceView.workspaceRetryLabel`/`retry()` 的 repairableAnswerId 与 manualModelRetry 分支 | 删除；错误条只保留"前往模型设置 / 再次提交 / 刷新状态"三类无歧义出口 |
| RecoveryNotice 全局重试按钮 | `recoveryPresentation.ts` `manualRetryState==='ready'`（retryable/"重新请求"）分支、`handleRecoveryAction.retry-model-operation` | 删除；`RecoveryNotice` 保留四个有锚点的模型：unknown→同步状态、历史恢复→恢复该回答（绑定具体 Answer/节点/路线）、resubmit→再次提交（绑定载荷）、blocked→仅解释 |
| toast 层"重试起草"按钮 | WorkspaceView `retry-fork-draft` 按钮 + `retryForkDraft` + `forkDraftRetryRouteId` 状态 + `retryForkDraftAction` + `findForkDraftRetryRouteIdAction` | 全部删除（含 beginProject 重置与 forkNode 写入点） |
| 无参数 `retry-pending` 事件链 | GraphQuestionNode/GraphCanvas 的 `'retry-pending': []` 声明与转发、WorkspaceView 监听 | 删除（占位卡按钮早已改发携带失败身份的 `retry-failure`，该链是死代码） |
| 兼容恢复分发 | `workspaceRuns.retryPendingAgentRunAction`（依赖全局重试状态、回退 `draftQuestion()`） | 删除；恢复绝不回退 Active/first/latest |

### 能力迁移位置（逐一核对，无一丢失）

- **Answer 已保存但处理未完成 / 已有失败 run** → 服务端未解决失败清单 + 节点恢复栏 RESUME_ANSWER（后端启动孤儿恢复保证中断 run 一定有 FAILED 条目）；E2E `historical-answer-recovery` 已更新为断言"全局横幅为 0 + 任务级恢复栏可见"。
- **历史 Answer 检查点恢复** → 保留（`historicalAnswerRecoveryTarget` 绑定具体 Answer/节点/路线，错误载荷携带身份）。
- **请求结果未知、响应丢失** → 先对账：RecoveryNotice 的 stale/同步状态路径现在实际执行 manual retry 意图的对账（needs_reconcile/ambiguous 只检查状态、绝不盲重试）；回答会话级 reconcile（`reconcileAnswerOutcome`）保留。
- **请求尚未形成 runId** → 保留对账流程（`draftQuestionAction` 在声明失败前先对账 canonical）+ 绑定原路线的正常重提交（`draftQuestion(explicitRouteId)`）；**不虚构 failedRunId**。
- **回答尚未保存、安全再次提交** → 保留（`resubmitAnswerPayload`，会话级载荷）。
- **分支已创建但首个问题未生成** → 任务级：起草失败 run 在服务端失败清单中，分支路线下游的失败占位卡原位重试（E2E `failure-recovery`/`retry-reload` 双重覆盖）；`routeCommands.forkNode` 的反馈改为"分支已创建，但首个后续问题起草失败"。
- **撤销后继续探索** → 正常节点动作（"继续生成问题""起草下一个问题""换一个问题"），本轮未触碰；`manualModelRetry` 意图状态保留（供对账流程），仅删除其全局重试按钮出口。

## 实际执行的验证与结果

| 验证 | 命令 | 结果 |
|---|---|---|
| 后端全量确定性测试（含架构门禁） | `gradlew testNonLive --rerun-tasks` | **1,650 通过 / 0 失败 / 0 错误 / 0 跳过**（272 套件；上轮 1,645 → +5：屏障 4 + 延迟注入 1；既有租约测试按新语义更新） |
| 屏障测试（复核反例确定性复现） | `ExecutionFenceAtomicityBarrierTest`（4 项，真实 PostgreSQL，`pg_terminate_backend` 真实断连） | **4/4 通过** |
| Agent 确定性评估门禁 | `gradlew evalBFast` | **84 项全部通过**（BUILD SUCCESSFUL） |
| Java ↔ Python 跨语言契约 | `gradlew testCrossLanguage` | **2/2 通过**，零真实模型调用 |
| Python Brain（未受影响模块复核） | `pytest tests/` | **144 通过** |
| 前端类型检查 | `npm run typecheck` | 通过（vue-tsc 无错误） |
| 前端全量单测 | `npm run test` | **850 通过 / 105 文件**（上轮 842 → +8：续接完成路径 6 + recoveryPresentation 契约锁定；旧全局入口用例改写为新契约断言） |
| 前端生产构建 | `npm run build` | 通过 |
| Playwright 全套 | `npm run test:e2e` | **81/81 全部通过（12.8 分钟）**= 80 既有 + 1 新增（重试硬刷新自动出产物）；historical-answer-recovery 按删除后的新入口更新 |
| 失败恢复回归 | `npx playwright test e2e/failure-recovery.spec.ts` | **3/3 通过** |

### 浏览器截图（实际查看，无重复横幅/遮挡/旧入口）

`frontend/test-results/retry-reload-shots/2026-09-27T13-12-57-309Z/`：

- `retry-reload-failed.png` — 分支首问失败：下游占位卡（服务端"重试生成"身份）、错误条出口为"刷新状态"（不再是"重新请求"）、顶部仅"△ 重试生成"汇总+定位、无全局"重试起草"按钮。
- `retry-reload-recovering.png` — **硬刷新之后**（无任何交互）：新页面从服务端清单续接在途重试，占位卡原位转"正在生成下一步问题…"进度态，顶部汇总转旋转态。
- `retry-reload-recovered.png` — **自动出现**：分支路线 1 出现真实新问题节点，占位卡/顶部横幅消失，反馈"已从上次失败处恢复"，Answer 恰好一条。

`frontend/test-results/failure-recovery-shots/2026-09-27T13-23-31-914Z/`（全套 E2E 重新生成，已逐张检查）：`draft-failed-placeholder.png`、`draft-recovered.png` — 同上，无旧全局入口、无重复横幅、无遮挡。

## 实现边界与剩余限制（如实保留）

1. **真实厂商模型验收、Java/Python 全量漏洞扫描、历史逐版本升级演练、`ACCEPT_AGENT_PROPOSAL` 永久 barrier 浏览器实测**：与上轮一致，仍为外部门禁，本轮未执行。
2. **原子协议的适用范围**：所有权代次 fencing 覆盖"同一数据库上持有执行器租约的进程"之间的竞争；无租约进程（API-only、测试）保持历史写入行为——产品边界仍是单执行器交付，多实例不支持。外部副作用（模型调用、能力调用）不受数据库回滚约束，继续由既有幂等键与所有权边界保护（如实边界，未声称"回滚外部请求"）。
3. **`INVOKE_CAPABILITY` 的执行刻意不在数据库事务内**：把外部 HTTP 调用包进数据库事务会长时间持有项目行锁；该族不产生图产物，其 run 终态化仍由条件写入把关。
4. **`[[delay-decision-ms:N]]` 仅测试基础设施**：只在 `engine=fake` 的确定性引擎内注册，正常产品配置（remote-python）永不创建该组件；普通回答文本不携带指令前缀时零影响（单元测试锁定）。
5. **测试数据与进程清理**：屏障测试使用隔离测试库 `spec_agent_test` 并自清理项目行；E2E 项目由 fixture 自动删除；用于 E2E 的本地后端进程（JAR，test profile，fake 网关）在验证结束后停止，未操作用户业务库。

---

# 第五轮修复（2026-09-28）：执行器所有权事务协议 + 顶部只读恢复汇总

独立复核 `docs/DELIVERY-ROUND4-REVIEW-2026-09-27.md`（含末尾"新会话交接前补查"R4-C）确认的两个真实数据库反例与顶部入口问题已全部修复并动态验证。本轮全部验证使用 fake Brain/确定性注入与隔离/测试数据库，零真实模型调用，未触碰用户业务库；既有真实厂商验收、漏洞扫描、历史逐版本升级等外部门禁继续保留。

## 结论变更（对第四轮报告的纠正）

1. **"检查与写入在同一条条件 UPDATE 内完成,接管一提交、旧执行器的后续写入立即落空" → 该表述不成立,已修正实现。** 第四轮把所有权校验写进同一条 UPDATE 的子查询(`(SELECT epoch FROM executor_ownership WHERE id=1) = :ownerEpoch`),但该子查询读取的是**语句快照**(Read Committed):UPDATE 在 PostgreSQL 内等待任务行锁期间,另一连接仍可提交代次递增;旧 UPDATE 恢复后依旧按语句开始时的快照通过条件,旧事务连同未提交业务产物成功提交。独立复核的 SnapshotFenceHarness 反例成立。"同一条 SQL 消除了接管竞争"的说法是错误的——单条语句的原子性不等于两个数据行/两个事务之间的提交互斥。
2. **"丢锁执行器的 fail 是幂等 benign no-op(0 行不抛出)" → 已按本轮要求修正。** 旧 `ExecutionFence.ownerEpoch()` 无条件返回租约对象的代次:租约会话被终止、`assertOwned` 已永久闩锁、但尚无新执行器接管(全局 epoch 未变)时,检查点/认领/终态写入全部仍能通过。复核的 LostOwnerHarness 反例成立。本轮起,已闩锁丢失的实例在一切写入入口立即拒绝——数据库代次比较代替不了"本进程已知丢锁"这一事实。

## R4-A / P1:所有权事务协议(接管互斥)

### 不变量

1. **接管互斥靠锁,不靠快照读。** 一切受保护的业务写入事务(Answer finalize、补丁、规格快照、节点产物、拓扑提交、检查点/终态写入、认领、失败终态化)在事务内、**任何业务行锁之前**,通过**同一事务绑定的连接**对 `executor_ownership` 单行取 `SELECT ... FOR SHARE` 并验证全局代次仍等于本执行器代次(`ExecutionFence.lockOwnershipForWrite()`)。锁由数据库保持到整个事务提交或回滚。
2. **接管的代次递增与该锁冲突。** `ExecutorLease.acquire()` 的 `INSERT ... ON CONFLICT DO UPDATE` 对所有权行取行排他锁,与 FOR SHARE 互斥——因此只有两种可接受顺序:
   - **顺序 A**:旧事务先完成(业务产物+检查点提交),接管等待它结束后才能提交新代次;
   - **顺序 B**:接管先提交,旧事务的 FOR SHARE 读取(取锁时读最新已提交版本)看到新代次,验证失败,整个业务事务(含未提交产物)立即回滚。
   禁止的结果——新代次已提交、旧事务仍按旧快照成功提交——在两种顺序下都不可能出现。快照子查询守卫保留在条件 UPDATE 内作为纵深防御,但互斥不再依赖它。
3. **已知丢锁立即拒绝(R4-C)。** `ExecutionFence.ownerEpoch()` 在租约闩锁丢失时抛出 `LeaseLostException`;`lockOwnershipForWrite()` 在取锁前后复查闩锁。已永久丢锁的实例不得重新开始写入、认领或静默重新取得所有权(租约构造失败仍使启动直接失败,绝不退化为 epoch=0 放行;无租约进程 API-only/测试驱动保持历史行为)。
4. **锁顺序。** 所有权行锁先于项目/任务/业务行锁获取。FOR SHARE 之间互相兼容——包括 REQUIRES_NEW 失败终态化在外层业务事务持锁时再取共享锁——与接管的行排他锁冲突构成的全部等待边都指向接管事务,而接管事务不持有任何其他锁,因此不存在等待环(自死锁不可能)。
5. **事务边界。** 业务产物与带所有权条件的检查点保持在同一事务(承接第四轮),事务第一条语句取所有权锁。模型请求与外部 HTTP 调用不进事务;`INVOKE_CAPABILITY` 维持数据库事务之外的幂等边界;数据库回滚不能撤销已发出的外部请求(如实边界)。

### 实现落点

- `ExecutionFence`:`ownerEpoch()` 丢锁即抛;新增 `lockOwnershipForWrite()`(FOR SHARE + 代次验证 + 闩锁复查,无租约进程返回 0 不取锁);`leaseLost()`。
- `AgentRunService`:全部检查点/complete 写入经 `fencedWrite` 包装——有租约时在事务内先取所有权锁再写入(REQUIRED 传播,自然并入调用方业务事务;自动提交检查点获得等价的短事务保护);`fail` 保持 0 行良性 no-op 但丢锁即拒绝。
- `RunService`:全部 claim 入口认领前取所有权锁(认领语句的快照窗口随之闭合)。
- `AnswerCycleService`/`ArtifactCycleService`/`ReplacementCycleService`/`DecisionExecutionService`:业务事务第一条语句取所有权锁(替换原来的单次 `assertOwnership`)。
- `AgentRunFailureService`:REQUIRES_NEW 事务内取所有权锁后执行条件 fail,返回结果分类(见下)。
- 无新迁移(V42 的 `executor_ownership` 表直接承载该协议)。

## R4-C / P1:失败状态与事件一致性

- `AgentRunFailureService.fail` 不再返回 void:返回 `FailureOutcome { APPLIED, ALREADY_TERMINAL, OWNERSHIP_REFUSED }`。
- **状态转换与 RUN_FAILED 业务事件在同一个 REQUIRES_NEW 事务内原子提交**:仅当条件更新真正生效(APPLIED)时事件才存在;已终态(ALREADY_TERMINAL)不追加事件、不覆盖新所有者的状态/原因/恢复动作;所有权拒绝(OWNERSHIP_REFUSED,含已知丢锁)不写任何业务状态。
- `RunWorker.failIfNotTerminal`、六个周期服务的 `failIfNotTerminal`、孤儿恢复 `terminalizeInterrupted` 全部改按结果分类行事:拒绝只记本地诊断日志,不追加事件;孤儿恢复只把 APPLIED/ALREADY_TERMINAL 计入收敛数,未发生的恢复不计成功。
- 与终态相关的 continuation/outbox 不变:continuation-check 请求仍与终态化同事务提交,派发走既有 afterCommit 快通道。
- **自死锁核查**:REQUIRES_NEW 的 fail 取 FOR SHARE 与外层业务事务持有的 FOR SHARE 兼容(共享锁),孤儿恢复外层 TransactionTemplate 不持冲突锁,项目删除(FOR UPDATE)不取所有权锁——均有专门回归测试与真实数据库验证。

## R4-B / P2:顶部恢复入口改为只读汇总与定位

- `NodeRecoveryBar` 新增 `locateOnly` 模式:**组件内真实不渲染**任何重试/恢复控件(不是隐藏按钮、不是只解除监听)。单项只有"查看";多项展开"⚠ N 项待处理 · 查看",列表每项显示"路线 · 简短原因"且只有"定位"按钮。
- `WorkspaceView` 顶部横幅改用 locate-only,`@retry` 绑定删除;新增 `handleLocateFailure` 按操作族真实定位:
  - 起草/续跑家族 → 定位源节点**下游的失败占位卡**(`pending:<runId>`,不是只选中源节点);
  - GENERATE_SPEC → 打开并定位对应规格面板(SpecDock 新增 `open()` 暴露);
  - NODE_QUERY → 打开对应节点与右侧检查器;
  - 共享节点 → 先把阅读 Focus 切到失败绑定的路线,定位到该路线的视觉实例(`resolveFailureVisualNodeId`,绝不回退 Active/first/latest);
  - 目标路线已删除或实例不在画布上 → 给出明确解释(feedback),绝不偷偷生成新路线。
- 正常业务"继续生成问题/起草下一题/换一个问题"、节点/占位卡/规格面板/检查器内的真正恢复动作、历史恢复、结果未知对账、无 runId 的原提交意图、安全再次提交全部保留未动。

## 两个独立反例的修复后结果

复核反例材料(`scratch/round4-review/`)的交错被原样编码进 `ExecutionFenceAtomicityBarrierTest`(真实 PostgreSQL,`pg_stat_activity` + 锁超时探测 + 同步屏障,不靠 sleep):

| 场景 | 修复后结果 |
|---|---|
| 检查点 UPDATE 已在 PostgreSQL 内等待任务行锁(pg_stat_activity 确认),期间接管 | **接管的代次递增被旧事务的 FOR SHARE 挡住**——锁超时探测两次失败(含租约会话被终止后);旧事务先提交(顺序 A),接管随后才完成 epoch=2。禁止的交错(新代次已提交而旧事务按旧快照提交)被证明不可达 |
| SQL 发出前暂停,再接管(顺序 B) | 旧事务取锁时读到新代次,整体拒绝,未提交业务产物一并回滚(`review_business_effect` 0 行) |
| 已明确丢锁、尚无人接管(全局 epoch 未变) | 检查点/attachContext/complete/fail/认领入口全部立即拒绝;失败服务返回 OWNERSHIP_REFUSED,不写状态、不追加 RUN_FAILED 事件;全局 epoch 与 run 状态零变化 |
| 丢锁执行器认领新任务 | 认领入口取锁即拒绝;仓储层代次守卫同样落空 |
| 正常执行、真实断连、接管竞争、正常释放 | 全部通过(合法所有者全链路不受影响) |
| 已终态记录不被覆盖;重复 fail | ALREADY_TERMINAL 幂等 no-op,RUN_FAILED 事件恰好一条,失败原因不被覆盖 |
| REQUIRES_NEW/孤儿恢复/项目删除组合 | 外层持 FOR SHARE 时内层 REQUIRES_NEW fail 无自死锁完成,状态+事件原子生效 |

## 迁移与启动生命周期验收(本轮新增动态验证)

| 验证 | 结果 |
|---|---|
| 空数据库 + worker 启动(JAR,隔离库 `spec_agent_accept_v42_empty`,:8095) | 41 个 Flyway 迁移全部应用至 **V42**(22:55:43.754),**之后**租约才获取所有权(22:55:44.435,epoch=1);health UP,worker ENABLED,孤儿恢复收敛后轮询开放 |
| V41 → 最新升级(既有 V41 验收库 `spec_agent_accept_install`,:8096) | 仅 V42 一个迁移应用(成功),租约获取(epoch=2),health UP,worker ENABLED |
| 启动顺序结论 | 迁移完成先于租约读取所有权表(日志时序证据:`scratch/round5-evidence/empty-db-startup.log`、`v41-upgrade-startup.log`) |

升级演练中一次失败启动如实记录:该库的凭据以旧安装的主密钥文件加密,用测试主密钥启动会 fail closed(密钥 id 不匹配)——这是 R4 加密设计的预期行为,与迁移/租约顺序无关;使用该安装的原主密钥文件后启动成功。

## 测试形态适配(如实说明,不弱化断言)

3 个 `@Transactional` 的 API 集成测试(`ArtifactGenerationAnswerGateIntegrationTest`、`HistoricalRecoveryFaultInjectionIntegrationTest`、`InheritedAnswerArtifactGateIntegrationTest`)原先依赖旧缺陷"失败服务外无条件追加事件"才能在测试事务内看到 RUN_FAILED——新协议下事件与状态转换在 REQUIRES_NEW 事务内原子提交,未提交的 run 行对该事务不可见。这 3 个类按既有先例(`TypedRunFailureIntegrationTest` 的同类注记)改为非事务形态:生产中 claim 事务在 worker 执行前已提交,测试形态与之完全一致;断言逐条保留并新增 `@AfterEach` 运行行自清理。这是测试向生产语义对齐,不是放松。

## 顶部无执行重试能力的浏览器证据

`frontend/test-results/top-recovery-locate-shots/2026-09-27T16-48-07-810Z/`(真实 Chromium + 本地后端 test profile + fake 引擎,逐张人工检查):

- `top-single-locate-only.png` — 顶部单项仅"⚠ 主路线 · 继续处理 + 查看",**无重试箭头**;节点卡内恢复栏的 ↻ 重试入口正常并存。
- `top-multi-locate-only.png` — 多项展开"⚠ 2 项待处理 · 查看",两行"主路线 · 继续处理 / 分支路线 1 · 重试生成",每项只有"定位"。

新 E2E `top-recovery-locate-only.spec.ts`(2 项)断言:横幅内 `node-recovery-retry`/`node-recovery-settings` 计数为 0、无 ↻ 文本按钮;鼠标点击与键盘 Enter 触发"查看/定位"期间,**捕获到的 `/retry`、`/agent-runs`、`/generate-spec` POST 请求恰好为零**;定位到达真实目标的几何断言(目标卡片中心落在画布中央区域)——单项定位来源节点,多项分别定位下游占位卡与来源节点且互不串目标;对应失败位置(节点恢复栏、占位卡)的重试按钮仍可见可用,重试后恢复栏收敛、Answer 恰好一条。

## 本轮实际重跑(非沿用旧产物)

| 验证 | 命令 | 结果 |
|---|---|---|
| 后端全量确定性测试(含架构门禁) | `gradlew testNonLive --rerun-tasks`(退出码 0) | **1,654 通过 / 0 失败 / 0 错误 / 0 跳过**(272 套件;上轮 1,650 → +4 净增) |
| 屏障测试(两个反例的确定性复现与闭合) | `ExecutionFenceAtomicityBarrierTest` | **8/8 通过** |
| Agent 确定性评估门禁 | `gradlew evalBFast` | 通过 |
| Java↔Python 跨语言契约 | `gradlew testCrossLanguage` | 通过,零真实模型调用 |
| Python Brain | `pytest tests/` | 144 通过 |
| 前端全量单测 | `npm run test` | **853 通过 / 105 文件**(上轮 850 → +3:locateOnly 契约) |
| 前端类型检查 + 生产构建 | `npm run typecheck && npm run build` | 通过 |
| Playwright 全套 | `npm run test:e2e` | **83/83 通过(13.5 分钟)** = 81 既有 + 2 新增顶部定位用例 |

## 实现边界与剩余限制(如实保留)

1. **真实厂商模型验收、Java/Python 全量漏洞扫描、从更早历史版本的逐版本升级演练、`ACCEPT_AGENT_PROPOSAL` 永久 barrier 浏览器实测**:与此前各轮一致,仍为外部门禁,本轮未执行。
2. **协议适用范围**:所有权事务协议覆盖"同一数据库上持有执行器租约的进程"之间的竞争;无租约进程(API-only、测试驱动)不取锁、不追加条件,保持历史写入行为。多实例部署仍不支持。
3. **FOR SHARE 的持锁时长**:受保护业务事务持有所有权共享锁直到提交——外部副作用(模型调用)本来就在事务之外,事务只含数据库写入,持锁窗口与既有项目行锁同量级;接管在旧事务提交前等待是协议的预期行为(顺序 A),不是缺陷。
4. **丢锁检测的固有边界**:FOR SHARE+代次验证挡住"接管已提交/正在提交"的一切写入;对"租约会话已死但尚无人接管且闩锁尚未置位"的窗口,写入仍可能通过(该写入随后会被接管后的代次守卫拒绝或被孤儿恢复收敛)——闩锁复查把已知丢锁的拒绝提前到每个写入入口,这已是无数据库共识前提下的诚实边界。
5. **测试数据与进程清理**:屏障测试与改造后的 API 测试自清理运行行;E2E 项目由 fixture 自动删除;E2E 与迁移验收用的本地后端进程全部停止;验收数据库 `spec_agent_accept_v42_empty` 保留供复查(可随时 DROP)。复核反例材料 `scratch/round4-review/` 原样保留,其交错已由 `ExecutionFenceAtomicityBarrierTest` 编码为常驻回归。

---

# 第五轮补充修复（2026-09-28）：R5-A 规格恢复的 operation 契约闭合

复核文档 `docs/DELIVERY-ROUND5-REVIEW-2026-09-28.md` 确认的 P2 已修复:数据库所有权协议与顶部只读定位维持原样(复核已独立重跑屏障 8/8 通过),本轮只做规格恢复的局部契约修正与浏览器回归。

## 修复内容

1. **operation 契约以 API 为准、集中管理**(`frontend/src/features/workspace/api/agentRuns.ts`):新增 `SPEC_GENERATION_OPERATION = 'GENERATE_ARTIFACT'`、`NODE_QUERY_OPERATION`、`DRAFT_QUESTION_OPERATION` 与 `DRAFT_FAMILY_OPERATIONS` 常量,注明 operation 与 triggerType 是两个不同字段(规格生成 run 是 triggerType=GENERATE_SPEC、operation=GENERATE_ARTIFACT,失败清单 `UnresolvedFailureView` 返回 operation)。
2. **`WorkspaceView.handleLocateFailure`**:规格失败判断由误写的 `GENERATE_SPEC` 改为 `SPEC_GENERATION_OPERATION`——规格失败点击顶部"查看"现在真实展开对应路线的规格面板;NODE_QUERY 分支同步改用常量;本地的 `DRAFT_FAMILY_OPERATIONS` 集合移入契约模块。全仓扫描确认生产代码不再有 `GENERATE_SPEC` 误用(仅存于说明注释)。
3. **面板筛选绑定阅读路线**:传给 SpecDock 的 `spec-failures` 由"operation 误判 + Active 路线"改为新计算的 `specFailureEntries`——以 `GENERATE_ARTIFACT` 判定,并绑定面板正在查看的路线(`specReadingRouteId`,与 `specSnapshots` 同一来源)。Active 路线 A、阅读路线 B 时,面板只展示/恢复 B 的任务,绝不因 Active 指针串目标;SpecDock 内失败条目的路线标签同步改为阅读路线。
4. **新增确定性失败注入 `[[fail-artifact:N]]`**(仅 `engine=fake` 的测试基础设施,与既有 `[[fail-state-update]]`/`[[fail-decision:N]]` 同一 arm-then-trigger 模式):回答文本武装(STATE_UPDATE 时,回答周期自身不受影响);引爆按**规格快照有效历史(lineage)匹配**而非生成时的锚点——规格生成信封的锚是路线 tip,而自治续跑会推进 tip,按锚点匹配永远打不中。预算按节点隔离、恰好消耗声明次数;无指令的普通文本零影响。生产配置(remote-python)永不创建该组件。

## 浏览器回归(`e2e/spec-failure-recovery.spec.ts`,真实失败载荷)

规格失败经真实链路产生:回答文本武装 → 真实 worker 执行的 GENERATE_ARTIFACT run 确定性失败 → 服务端失败清单返回 `operation=GENERATE_ARTIFACT`。断言覆盖复核的全部验收点:

- 顶部单项"查看"点击:展开规格面板(先收起再断言真实状态变化)、面板内出现"重试生成规格"恢复条目(服务端 RETRY_SPEC 身份)、期间 retry/生成类 POST 恰好为零;
- 阅读路线绑定:切到主路线 A → 面板不显示 B 的失败;切回 B(当前路线)→ 条目与恢复按钮回来;
- 面板内恢复:点击恢复按钮才提交该失败 runId 的重试 → 重试生成成功(预算恰好一次)→ 失败清单收敛、路线 B 的规格快照落库。
- 截图(`frontend/test-results/spec-failure-shots/2026-09-27T17-38-49-915Z/spec-failure-panel.png`,人工检查):顶部"⚠ 规格分支 · 重试生成规格 + 查看"、面板内"⚠ 重试生成规格 + ↻"、"正在查看:规格分支 / 生成目标:规格分支"。

## 本轮实际重跑(非沿用旧产物)

| 验证 | 命令 | 结果 |
|---|---|---|
| 注入单元测试(arm-then-trigger + lineage 匹配 + 无指令零影响) | `DeterministicEngineFaultPlanDraftInjectionTest`(+2 项) | 通过 |
| 后端全量确定性测试 | `gradlew testNonLive --rerun-tasks`(退出码 0) | **1,656 通过 / 0 失败**(272 套件;上轮 1,654 → +2:artifact 注入单元) |
| 规格失败浏览器回归 | `npx playwright test e2e/spec-failure-recovery.spec.ts` | 1/1 通过 |
| 规格/恢复相关既有 E2E(spec、top-recovery、failure-recovery、historical-answer-recovery) | 同上 4 个文件 | **8/8 通过** |
| 前端全量单测 + 类型检查 | `npm run test && npm run typecheck` | 853 通过、无类型错误 |
| 后端 decision 包 | `gradlew test --tests com.specagent.agent.decision.*` | 通过 |

外部门禁维持不变:真实厂商验收、Java/Python 漏洞扫描、历史逐版本升级、`ACCEPT_AGENT_PROPOSAL` 浏览器实测仍按此前各轮保留。E2E 后端进程验证结束后停止;运行日志留存 `scratch/round5-evidence/`。

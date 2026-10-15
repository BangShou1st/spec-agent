# 第二阶段：身份、组织、项目权限与凭证隔离设计

日期：2026-10-12。状态：设计提案，未实施。

## 1. 目标与范围

在第一阶段任务租约、并发调度与写入保护上，增加明确的用户身份、组织数据边界、项目授权和凭证使用权限。支持一个用户参加多个组织；任何 Run 必须归属一个组织及发起者；模型不能提升权限或切换执行身份。

本阶段完成受控多人部署的权限基础，不宣称完成企业合规认证。共享供应商 RPM/TPM、计费、完整监控、任意第三方 API access token、自动跨组织资源共享不在本阶段。

保留单机本地模式，但不能以缺少身份上下文自动降级为匿名管理员。多 Worker 的两项遗留（真实模型 GA 接管资格、目标部署环境 drain）继续保留，不因权限建设而开启 GA 自动恢复。

## 2. 本次代码依据

| 位置 | 当前状态 |
|---|---|
| `backend/build.gradle.kts` | Spring Boot 3.3.2、Java 21、Spring MVC/JDBC；未引入 Spring Security/Session |
| `workspace/project/ProjectRepository.java` | 项目按 ID 查询，未见组织归属和成员过滤 |
| `assistant/conversation/GlobalAssistantThreadRepository.java` | 会话没有 user/org 字段，摘要和 working state 按 thread ID 保存 |
| `assistant/api/GlobalAssistantController.java` | 会话、消息、Run、SSE、steer 入口需身份和资源授权 |
| `modelsettings/ModelCredentialCrypto.java` | 已有 AES-GCM、密钥文件/环境配置和轮换；需保留并增加凭证归属校验 |
| `connection/credentials/McpCredentialResolverAdapter.java` | 以 credentialRef 解析；缺失凭证当前退回未认证连接，多人模式必须区分公开连接与损坏授权凭证 |
| `connection/ConnectionRepository.java` | connection 按 ID 操作，需隔离归属与绑定权限 |
| `retrieval/protocol/RetrievalStoreController.java` | 内部 token 提供服务认证；还需校验每次检索的业务授权范围 |
| `docs/v2/PHASE1_MULTI_WORKER_IMPLEMENTATION_STATUS.md` | 第一阶段已记录模式准入、事件补发与双进程验收；仍需权限与执行 token 同时成立 |

本轮仅只读审阅和新增本文档，不改动现有业务与未提交工作。

## 3. 技术方案与取舍

采用 **Spring Security + OIDC Login + Spring Session JDBC + PostgreSQL 业务授权**。

- OIDC 对接企业已有身份系统；开发与验收可使用 Keycloak。密码、MFA、找回密码由身份提供商管理，不自行实现密码系统。
- 浏览器同源访问 Java 后端，由后端完成登录，使用 HttpOnly 会话 cookie；OIDC token 不放入 localStorage、URL、Agent snapshot 或 Python。
- 会话使用 PostgreSQL 共享，API 多实例不要求粘性会话。现阶段不为会话额外引入 Redis；测量证明确有压力后可替换会话存储。
- 角色和项目授权以本地数据库为准；OIDC 负责确认身份，不直接将 IdP group/role 自动映射为产品管理员。
- SQL 授权过滤与应用层授权同时做。RLS 可作为后续纵深保护，但不是首批实现：需要适配事务连接上下文、后台服务身份、表 owner/BYPASSRLS 和迁移角色，不能加一条 policy 就宣称隔离完成。

Spring 官方支持 OIDC/OAuth2 Login、JDBC HttpSession 与 CSRF 防护；实现时使用项目 Boot 版本匹配的依赖，不能直接照抄最新主版本示例。基础框架升级需另行评估，不与权限迁移强绑定。

## 4. 身份与归属模型

### 4.1 基础表

| 表 | 关键字段与约束 |
|---|---|
| `app_users` | id、display_name、status；禁用用户在每次授权检查生效 |
| `user_identities` | user_id、issuer、subject；唯一 `(issuer, subject)`，不以邮箱作为身份主键 |
| `organizations` | id、name、status；禁止自动用客户端 orgId 建组织 |
| `organization_members` | `(org_id, user_id)` 唯一；role、status |
| `project_members` | `(project_id, user_id)` 唯一；角色，必须属于项目组织 |
| `authorization_scopes` | scope_key、version；组织/项目/凭证范围的授权事务锁与版本来源 |
| `security_audit_events` | actor、org、action、resource、outcome、correlationId、时间；不含正文/密钥 |

本阶段一个配置的受信任 issuer；数据结构允许以后接多个 issuer。新身份可以注册为用户，但没有组织成员资格时不获得任何业务数据访问权。邀请由管理员签发，单次使用、有截止时间，只保存邀请 token 的 hash。

### 4.2 业务表改造

- `projects`：org_id、created_by；项目组织归属创建后不可任意修改。
- `global_assistant_threads`：org_id、owner_user_id；会话默认私有，不提供公共共享功能。
- 画布/GA Run 与 `runtime_tasks`：org_id、actor_user_id、authorization_context_ref。以持久化引用为准，不能由 Brain 声称自己属于某用户。
- Graph、Route、Answer、Patch、Artifact、事件：通过受约束的父关系确定所属项目/组织；关键查询必须 join 授权父资源。必要时冗余 org_id，并用复合外键防止跨组织关联。
- corpus/source/chunk/index job：保存 org_id 与项目/会话可见性，后台索引任务有明确服务身份和来源归属。
- connection、provider profile、embedding/search 设置、Skill 安装：增加 org/user 归属及可见性，不再默认所有组织共享可写配置。
- embedding 向量语义仍以内容哈希标识（Python 协议严格核验哈希），不可加入组织盐破坏语义契约；配置批准使用 `(org_id, profile_id)` 的独立登记关系，传输配置/修订/密钥只按该组织读取。同哈希不构成共享凭证或共享批准。全局语义 registry 不提供无授权配置读取接口。组织用户不得改写安装级公共 HELP head；组织配置可重建有项目管理权限的私有项目。

外部路径中的 orgId 只是选择条件。服务先验证组织成员资格，再查资源真实归属；嵌套 ID 还要检查彼此一致性。

## 5. 角色与能力矩阵

组织角色：OWNER / ADMIN / MEMBER。项目角色：MANAGER / EDITOR / VIEWER。

| 操作 | OWNER | ADMIN | MEMBER |
|---|---|---|---|
| 管理组织成员、组织配置与共享凭证 | 是 | 是 | 否 |
| 转移组织所有者、删除组织 | 是 | 否 | 否 |
| 创建项目 | 是 | 是 | 是 |
| 访问项目正文 | 按项目角色 | 按项目角色 | 按项目角色 |
| 查看组织内私有 GA 会话 | 仅自己的 | 仅自己的 | 仅自己的 |

组织管理员有项目访问治理能力，可以显式为自己授予项目角色，但授予行为须审计；不以管理角色暗中读取项目正文。最后一个 OWNER 不允许退出/降级。

| 项目能力 | MANAGER | EDITOR | VIEWER |
|---|---|---|---|
| 读取 Graph、Answer、规格、事件、导出 | 是 | 是 | 是 |
| 回答、修改画布、切路线、undo/redo、创建写入 Run | 是 | 是 | 否 |
| 接受写入 proposal、恢复/重新发起写入任务 | 是 | 是 | 否 |
| 管理项目成员、删除项目、改变连接绑定 | 是 | 否 | 否 |
| 使用已批准的项目工具连接 | 按工具能力 | 按工具能力 | 仅只读能力 |

角色只映射到确定性能力集合，例如 PROJECT_READ、PROJECT_WRITE、PROJECT_MANAGE、CREDENTIAL_USE、CREDENTIAL_MANAGE。Agent 的读任务和写任务分别校验，不把“能聊天”映射为“能执行所有工具”。

个人凭证由本人管理，组织共享凭证由组织管理员管理；明文写入后不提供读取接口。具有凭证管理权也不意味着可以执行任意目标项目操作。

## 6. 登录、会话和前端交互

浏览器 → Java 登录入口 → OIDC 提供商 → Java 回调 → 共享会话 → `/api/me`。

会话 cookie：HttpOnly、部署环境 Secure、合适的 SameSite 与固定 Path；登录后更新 session ID。登录请求由框架验证 state/nonce；使用授权码流程并按提供商支持配置 PKCE。

同源部署为默认；本地开发经前端代理访问后端。变更类请求使用 CSRF token，包括退出、取消、steer、凭证配置等；不把全部 `/api/**` 设为 CSRF 例外。内部服务链不依赖浏览器 cookie，配置单独安全链。

登录过期/用户禁用返回 JSON 401，前端明确重新登录；资源不可见返回一致的 404，已可见资源上缺少操作权限返回 403。前端权限字段只控制显示，后端不信任按钮状态。

新增最小界面：登录/退出、组织切换、成员与项目角色管理、归属清晰的设置页面。组织切换是显式路由/请求上下文，不依赖整个 session 内一个可变 orgId，避免不同标签页串组织。切换后清理对应缓存并关闭旧组织订阅。

SSE 订阅建立、补发和周期授权检查均验证用户、组织、资源权限及共享 session 有效性；撤销后停止发送并关闭连接。验收初值：既有连接在 5 秒内发现撤销；不承诺收回已发送字节。各 API 实例共享 session 和授权事实，不能只发送本机退出通知。

空闲 session 初值 30 分钟，绝对登录期限初值 8 小时，作为待评估参数。浏览器退出使会话失效，不自动撤销已经合法创建的后台任务；用户禁用、组织退出和项目权限撤销会影响任务继续执行。

## 7. 授权链路与服务身份

浏览器身份只从已验证会话解析。创建命令保存 ActorContext（user、org、来源）、目标资源与允许动作快照；恢复 Worker 根据数据库重建，不依赖 HTTP 线程上下文。

需要同时满足：**当前执行 token 有效 AND 当前用户/组织/目标资源授权有效 AND 动作符合 Runtime 业务规则**。

校验点：API 入口、列表/详情/导出读取、任务认领、恢复、冻结上下文、模型/检索/工具 RPC、新步骤发起、业务结果提交、checkpoint 写入、事件订阅/补发。UI context、source refs、tool arguments、credentialRef 均为待验证输入。

服务身份分两类：

- 用户委托任务：以 actor 当前权限执行，Worker 内部认证不能提升用户权限。
- 系统派生任务：显式 SYSTEM_INDEXING 等窄服务权限，只能操作已登记的来源/产物；无 user 字段不能自动被认作超级管理员。

内部 token/网络服务认证只说明请求来自受信任服务。运行中的 RPC 还要验证 task token 和 ActorContext；索引 grant 则绑定 job、org、source、generation、deadline。内部接口不能因为 token 正确就接受任意项目 ID。

## 8. 撤权、事务与第一阶段锁顺序

引入授权范围行，确保并发“撤权”与“提交”有明确提交顺序。受保护执行事务按：

`task → 容量槽 → execution scope → authorization scope（排序）→ 业务 Run → 业务行`。

授权检查在事务内对相关范围取共享锁并读取最新成员/状态；成员撤销、用户禁用、凭证停用在对应授权范围取排他锁后更新事实和 version。授权范围锁保存到事务结束，执行期间 HTTP 不在锁内。

写结果先拿到有效授权锁，则该短事务可先提交，撤权随后完成；撤权先提交，则后续检查拒绝。不以一次普通 SELECT、TTL 缓存或创建任务时的角色快照代替这个协议。

管理撤权事务只锁授权范围及授权事实，不在持锁时取消 task。撤权提交后写持久化取消请求/outbox，由控制面按 task 锁顺序处理，避免 `authorization → task` 与提交链形成锁环。新建任务尚未可见时可在命令事务持授权锁原子保存输入、Run 和任务；不可用“新建任务例外”访问既有任务。

权限被撤销的排队任务诚实失败；运行任务停止后续调用、拒绝 checkpoint/结果，控制面原子收敛任务/Run/事件/容量。因权限拒绝而收尾的服务可以写有限失败元数据，不能再读取/保存未授权业务正文。

冻结输入不可变但不是永久授权。撤权后，旧输入、事件、产物、模型响应均不能再次提供给该用户。已经发送给模型或外部工具的数据无法撤回，审计要如实保留。

## 9. 检索、记忆、缓存与工具

检索必须在候选生成阶段过滤 org、可读项目和来源可见性，之后再排名；不能先搜索全库再隐藏来源标题。分页、数量、摘要、解释和引用也来自授权候选集。

Java Host 生成不可伪造的检索 grant；Python 只能对该 grant 对应候选计算，不能传一份“我是管理员”的过滤器获得更多数据。组织级全局搜索不包括无项目权限的私有项目。公共帮助 corpus 独立标记，只允许部署提供的可读资料。

GA 历史/摘要/working state/checkpoint 保存 source/project 依赖和授权版本。项目撤权后原会话可能已含项目正文：本阶段保守隐藏受影响会话与派生记忆并拒绝旧 checkpoint 续跑，提示创建新会话；有独立验证过的清理/重建流程后才恢复展示。不能只过滤下一次检索，却继续把旧摘要送模型。依赖记录不全时隔离整条会话，不猜测“没有敏感内容”。

缓存键至少包含 org、主体/有效访问集合版本、资源/模型/凭证版本；撤权提交后发布失效标记，多实例依据数据库 version 校验。MCP 会话连接池、工具目录、embedding/index generation、模型绑定不能按 provider/endpoint 单独共享。跨组织原始 prompt、结果、摘要缓存默认禁用。

每次工具调用验证目标所属组织、用户动作权限、连接绑定、凭证使用权限与 Runtime gate；工具目录仅列可用能力。写入工具权限由 Java 固定，模型自称 read-only 不构成证明。

## 10. 凭证与配置隔离

新增或扩展凭证元数据：org_id、owner_kind（ORG/USER）、owner_user_id、purpose、status、version、key_id、密文引用。数据库约束确保 USER 类型必须指定本组织成员；组织共享凭证无个人 owner。

凭证使用规则：组织/用户范围匹配、允许的 provider/connection/project 绑定、用户具备 CREDENTIAL_USE、目标操作已获授权。Run 冻结模型/配置版本，执行时再次检查凭证仍可用；轮换产生新版本，正在执行任务默认不静默切换新密钥。

现有 AES-GCM 实现复用；多实例必须共享一致的加密密钥配置。多人部署不允许各实例自动生成不同本地 master key。过渡期支持密钥 ring；后续可接 KMS/Vault，通过 SecretStore 端口替换。

新增密文格式版本绑定 credentialId/orgId/purpose 为 AAD，迁移逐行重新加密，旧格式只在明确迁移窗口兼容。加密本身不代替访问控制。

“公开 MCP 连接无需凭证”和“引用凭证缺失/禁用/跨组织”区分：前者显式配置 anonymous，后者失败，不回退匿名重试。catalog、trace、snapshot、前端、日志不暴露 credentialRef 对应明文。

现有全局模型、搜索、embedding 设置迁为组织配置；个人设置只覆盖允许的偏好/个人凭证选择。安装级地址、内部 secret、master key 和部署参数仅部署管理员可操作，不暴露为组织管理员 API。

组织 Skill 安装与启停隔离；builtin Skill 可共享只读内容，启用配置按组织。脚本或外部能力的执行仍遵循现有策略及用户授权。

## 11. 本地兼容、模式准入和数据迁移

身份模式固定枚举 LOCAL / OIDC，缺配置拒绝多人模式启动，不能自动回退 LOCAL。LOCAL 创建显式本地 user/org，仅回环绑定；OIDC 禁止使用本地身份绕过登录。模式与安全协议版本在数据库范围登记，同库实例必须一致。

旧二进制不理解身份准入，必须在发布前停掉，并通过新的共享/排他 admission 锁协议兼容旧 executor key 阻止旧执行器进入。旧 API-only 进程无法靠该锁保证拒绝：部署准入、网络入口移除和全部旧进程停机是迁移前提，不能宣称数据库登记表能约束未知旧程序。

维护窗口流程：停止入队与 drain → 备份数据库和可解密密钥 → 停止全部旧进程 → 新表与归属回填 → 约束收紧 → 部署兼容 Java/Python → 验证隔离 → 开放访问。

存量项目归入显式 legacy org，存量 GA 会话归入显式 legacy user；管理员离线审核映射，不能把存量数据自动分配给第一个 OIDC 登录者。现有模型/MCP 密钥只迁入 legacy org，不复制到每个新组织。孤立引用或无法判断归属的来源进入隔离清单，不默认公开。

迁移先 nullable 回填/核验再 NOT NULL 与 FK；最终生产路径不接受 null org/actor。迁移号实现时分配，不预占当前 V58 之后的序号。

回退只允许维护窗口和一致版本集。多人数据不能直接交给旧匿名 API；需要前向修复或经过审核的备份恢复，说明可能的数据回退。授权关闭开关不能作为生产回退手段。

## 12. 模块与接口落点

新增中性 `com.specagent.access`：ActorContext、AccessDecision、AuthorizationGuard、ProjectAccessPort、ThreadAccessPort、CredentialAccessPort。OIDC/session 适配位于 security 基础设施，业务包通过端口接入，公共 runtime 不依赖 workspace/assistant。

- Controller：身份解析、CSRF 与参数转换，资源规则在应用服务校验，避免只加几个 @PreAuthorize 就漏掉 Worker/RPC。
- Repository：授权列表查询、带归属的查找/更新，禁止公共接口调用无范围 findById。
- Runtime task：持久化授权引用、检查当前权限、恢复/续跑保持 actor、受控失权终态化。
- GA：会话 owner 校验、跨项目工具授权、记忆依赖与隔离、steer/SSE 授权。
- Retrieval：候选过滤、grant 绑定、索引服务身份、来源与引用核验。
- Modelsettings/connection：组织配置、凭证归属和版本化使用；现有 global singleton 配置逐条迁移。
- 前端：/api/me、组织选择、成员管理、权限展示、统一 401/403/404 与 CSRF 请求支持。

## 13. 分批实施与交付门槛

| 批次 | 内容 | 退出条件 |
|---|---|---|
| P2-A | 全入口/归属清单、权限矩阵、数据迁移与锁顺序冻结 | 所有 controller、RPC、导出、检索、工具、缓存都有归属和校验点 |
| P2-B | user/org/成员表、OIDC、共享 session、本地模式准入 | 两个 API 实例登录可用；无身份和 CSRF 请求正确拒绝 |
| P2-C | 项目与 GA 私有会话授权、查询隔离、SSE 与缓存 | 跨组织和同组织跨项目访问矩阵通过；列表/详情/导出无旁路 |
| P2-D | Worker、恢复、工具、检索、记忆的动态授权 | 撤权竞态可验证；旧 token 不能绕过权限；记忆不泄露已撤权来源 |
| P2-E | 组织凭证、模型/MCP/搜索/embedding/Skill 配置隔离 | 密钥误绑定、缺失、轮换、跨实例解析均可解释且失败关闭 |
| P2-F | 多身份双实例/browser 验收、迁移回退、文档 | 端到端矩阵与发布演练完成，明确遗留与承载边界 |

P2-B 只完成登录，不可称多人部署完成。所有隔离入口完成前保持受控内网验收环境，不开放多人生产数据。

## 14. 必须覆盖的验证

准备两个组织、至少五个用户、不同项目角色、私有会话与组织/个人凭证。真实 PostgreSQL、两个 API/Worker 进程、OIDC 验收环境；模型与 MCP 使用可控 stub。浏览器验收统一 browser-skill。

| 场景 | 验收要求 |
|---|---|
| 修改 org/project/thread/run/answer/connection ID | 未授权资源一致不可见；嵌套跨项目引用拒绝 |
| 列表、分页、导出、事件回放、检索 | 仅授权数据，数量/摘要/来源不泄露 |
| 同一 session 不同标签页切组织 | 请求归属显式，无共享 activeOrg 串扰 |
| API A 登录、API B 请求/SSE | 共享 session 正常；退出/禁用/撤权跨实例生效 |
| 跨站变更请求、伪造回调、无效 CSRF | 请求拒绝，不创建 Run 或发生副作用 |
| VIEWER 发起写工具、接受 proposal、恢复任务 | 后端拒绝，模型文本不能改变决定 |
| 排队/推理/提交期间撤权 | 明确事务提交顺序；停止后续执行，终态/事件/容量一致 |
| 用户被禁用、组织退出、管理员自授项目角色 | 当前权限生效，最后 OWNER 约束与审计可验证 |
| 旧 Worker/有效服务 token 指定任意 org | task/actor/grant 不匹配拒绝，不提升到系统权限 |
| 已缓存 GA 摘要/checkpoint 包含撤权项目 | 会话/记忆隔离，不继续回显或送模型 |
| 凭证引用跨组织、缺失、轮换、连接池复用 | 不回退 anonymous，不借用其他组织密钥，不静默切版本 |
| 系统索引与公共帮助 | 服务 grant 限定来源；公共范围不混入私有资料 |
| 本地/OIDC 混跑和迁移 | 不自动降级；legacy 数据明确分配；旧匿名 API 退出入口 |

既有回归和 ArchUnit 继续作为门禁；历史 1654/29 项通过是第一阶段记录，不作为本阶段授权正确性的证据。权限测试重点覆盖 ID 越权、撤权竞态和数据路径，不只验证返回 200/403。

## 15. 参考与待实现时确认

以上选型为结合当前代码的设计判断，具体 API 与兼容性在实现时按当前项目依赖核实。

- [Spring Security OAuth2 / OIDC](https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html)
- [Spring Session JDBC](https://docs.spring.io/spring-session/reference/guides/boot-jdbc.html)
- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [PostgreSQL RLS 与绕过边界](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)

实现前需要确认目标 IdP、初始组织/Owner 映射与目标部署域名/TLS。设计默认一个 OIDC issuer、邀请制组织加入、GA 私有会话；这些可独立准备，不阻止 P2-A 的只读盘点和方案审查。

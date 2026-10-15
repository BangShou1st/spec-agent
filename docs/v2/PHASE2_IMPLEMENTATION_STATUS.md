# 第二阶段实施记录：P2-C/D/E 持续推进，P2-F 部分验收

日期：2026-10-15。对应 [第二阶段设计](PHASE2_IDENTITY_AUTHORIZATION_DESIGN.md) 与 [P2-A 权限入口清单](PHASE2_A_PERMISSION_ENTRY_INVENTORY.md)。

**最新核实（2026-10-15，续接四项缺口修复交接之后）**：当前 V93 全量 `testNonLive` **1972 / 0 failures / 0 errors / 34 skipped / 318 suites**，包含真实 TCP 慢连接及归属约束测试。已补齐 OIDC 双 JVM 杀进程诚实失败、数据库中断/受控重启/新任务成功、跨实例 SSE 与游标重连、协调密钥轮换/回退/备份恢复；V92/V93 已完成归属非空、FK 与全部 legacy org 默认移除；另通过 Alice/Bob 两身份的显式项目授权、私有会话隔离和**模型在途组织级撤权**。**完整 P2 仍未完成**：全来源依赖/全入口审计、完整五身份浏览器矩阵、企业 IdP/TLS 与承载边界仍需验收。最新事实见末尾 **§3.47–3.48**；下文早期数字和待办不覆盖该补充。

## 0. 当前交付边界（2026-10-14，覆盖下文早期批次记录）

| 批次 | 当前实现 | 验收边界 |
|---|---|---|
| P2-B | 身份、安全链、数据库模式准入、共享会话、CSRF | 隔离 Keycloak 已跑通真实授权码登录；两个独立 API JVM 已验证同一浏览器共享身份。企业 IdP/TLS 未验收 |
| P2-C | 项目/会话隔离；GraphCommand/UndoRedo 与 Run 授权；GA 私有性/SSE；前端身份/成员管理；V92/V93 最终归属约束 | 当前全量 1972 项通过；已验收 OIDC 创建、Alice/Bob 项目与私有会话隔离；完整端点/五身份 UI 矩阵未闭合 |
| P2-D | 任务/Run 持久委托、执行期授权与失权传播；工具/checkpoint/模型票据护栏；撤权 outbox；特定来源依赖 | 已通过单/多执行器 PG 回归、真实组织级在途撤权、迟到结果拒绝及故障诚实失败。全 source-dependency/内部入口审计尚待收口 |
| P2-E | 组织配置、个人/组织连接凭证、AAD、主密钥失败关闭与舰队准入；Skill 来源；最终 org 默认移除 | 已通过 V93 全量、独立双 JVM 轮换/回退/备份恢复、模型和 MCP 产品解密器验证；未冒充真实提供商或全部 embedding/公共索引部署组合 |
| P2-F | 隔离 PG + Keycloak + 双 Worker + 真实 Python broker + bsk；故障/回退演练与可复验驱动 | 核心故障矩阵及两身份组织撤权已通过（§3.47–3.48）；完整五身份/全 UI 入口、企业 IdP/TLS 与承载仍待验收 |

**第二阶段整体未完成，多组织投产条件尚未满足。** 以下 §1–3.4 与 §5.2 保留早期实施历史；当前状态以本节、§3.5–3.23、§4、§5.3 与 §8 为准。

未触碰现有开发库、未覆盖用户现有凭证、未部署生产、未提交或推送。当前测试和演示仅使用专用容器 `spec-agent-p2-auth` 的 `spec_agent_test`/`spec_agent_p2_oidc`（127.0.0.1:5546），与开发库分离。

---

## 1. 为什么 P2-B 是安全的第一刀

P2-B 的风险在于把 Spring Security 放进 classpath：默认安全链会立刻要求所有端点认证，破坏既有 254 个测试类。本轮的处置是**双链 + 显式本地主体**：

| 模式 | 主链行为 | 访问控制由谁承担 |
|---|---|---|
| LOCAL（默认） | `permitAll`，CSRF 关闭 | **回环绑定 + 显式本地主体**（可禁用、可审计的本地 user/org，不是匿名管理员） |
| OIDC | `authenticated`，CSRF 开启 | 企业身份系统 + 会话 |
| `/internal/**`（两种模式） | `permitAll`、STATELESS、无匿名过滤器 | 内部 token（控制器内校验）；**P2-D 追加 task token 与 ActorContext** |

关键点：LOCAL 模式"不需要登录"**不等于**"没有身份"。每个本地请求都被绑定到一个真实的 `app_users` 行与 `organization_members` 行，禁用该用户后其权限立即失效——这正是设计 §11 要求的"不能以缺少身份上下文自动降级为匿名管理员"。

---

## 2. P2-B 实现内容

### 2.1 结构登记（第一个提交，按用户要求先做）

`backend/src/test/java/com/specagent/architecture/ArchitectureTests.java`：

| 变更 | 内容 |
|---|---|
| `RUNTIME_KERNEL` | 加入 `com.specagent.access..` —— access 因此同样受"不得依赖 model/agent"与"不得含业务域类"约束 |
| `SUB_MODULE_BASES` | 加入 `com.specagent.access` —— root / identity / security / api 四个 slice 必须无环，且 root slice 必须持真实类 |
| 新增 `accessModuleStaysNeutral` | access 不得依赖 workspace / assistant / agent / model / retrieval / skill / connection / capability / mcp |
| 新增 `springSecurityTypesStayInsideTheAccessSecurityAdapter` | **只有 `com.specagent.access.security` 可以依赖 `org.springframework.security.*`** —— 这是"避免只加几个 `@PreAuthorize` 就漏掉 Worker/RPC"的结构保障 |

### 2.2 数据表

| 迁移 | 内容 |
|---|---|
| `V59__identity_and_organization.sql` | `app_users`、`user_identities`（唯一 `(issuer, subject)`，**不以邮箱为主键**）、`organizations`（含 `kind=LOCAL/LEGACY`）、`organization_members`、`project_members`、`authorization_scopes`（`CHECK scope_key LIKE 'authz:%'`）、`security_audit_events`、`security_mode`（单行登记，默认 `LOCAL`） |
| `V60__spring_session_shared_store.sql` | `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES`（Spring Session 官方 PostgreSQL schema 逐字副本，由 Flyway 建立，`initialize-schema=never`） |

### 2.3 access 模块组成

| 包 | 类型 | 职责 |
|---|---|---|
| `access` | `ActorContext` / `ActorSource` / `AccessMode` / `Capability` / `OrgRole` / `ProjectRole` / `AccessDecision` / `AccessReason` / `AccessDeniedException` / `AuthorizationScopes` / `ResourceOwnership` / `ActorContextHolder` / `IdentityModeProperties` | 纯契约与判定 |
| `access` | `AccessResourceRepository` / `AuthorizationScopeRepository` / `AccessAuditRecorder` / `AuthorizationGuard` | 判定所需的读事实、授权范围锁与版本、审计、判定编排 |
| `access.identity` | `IdentityRepository` / `IdentityService` / `SecurityModeRepository` / `SecurityModeAdmission` | 身份解析、组织成员管理、模式准入 |
| `access.security` | `LocalSecurityConfiguration` / `OidcSecurityConfiguration` / `InternalServiceSecurityConfiguration` / `ActorContextFilter` / `AbsoluteSessionTimeoutFilter` / `AccessErrorResponses` / `AccessSecurityController` | Spring Security、OIDC、共享会话、CSRF、主体解析 |
| `access.api` | `MeController` / `MeResponse` | `GET /api/me` |

### 2.4 授权判定与失败语义

判定表达式：身份启用 → 主体来自持久化事实 → 组织成员资格有效 → 资源归属一致 → 能力允许（→ 执行 token，仅执行路径）。

失败语义分别可测（不是"某处返回 403"）：

| 原因码 | HTTP 语义 |
|---|---|
| `UNAUTHENTICATED` / `USER_DISABLED` | JSON 401 |
| `ORG_SELECTION_REQUIRED` | 403（多组织用户必须显式选择，不猜） |
| `NOT_ORG_MEMBER` / `MEMBERSHIP_DISABLED` / `ORG_DISABLED` / `ORG_CAPABILITY_MISSING` | 403 |
| `RESOURCE_NOT_VISIBLE`（`concealed=true`） | **404**（不泄露"存在但无权"） |
| `SERVICE_NOT_AUTHORIZED` | 内部 token 已验证也**不**构成业务授权 |
| `SYSTEM_SCOPE_EXCEEDED` | 系统主体越出其登记组织/来源 |
| `LAST_OWNER_PROTECTED` | 最后一个 OWNER 不允许降级或退出 |
| `AUTHORIZATION_SCOPE_UNKNOWN` | 授权范围行不齐时**失败关闭** |

### 2.5 授权范围锁（与 P1 锁顺序合并）

`AuthorizationScopes` 生成 `authz:org:|authz:project:|authz:thread:|authz:credential:|authz:user:`，`AuthorizationScopeRepository` 在 SQL 内 `ORDER BY scope_key` 取 `FOR SHARE` / `FOR UPDATE`。锁顺序冻结为：

```text
task → 容量槽 → execution scope → authorization scope → 业务 Run → 业务行
```

成员变更走完整撤权事务协议：取 `authz:org:{orgId}` **排他**锁 → 读最新事实 → 判定 → 更新事实 → **递增 org 与 user 两个范围的版本**。撤权事务只锁授权范围，不在持锁时取消 task。

实施中发现并修复的真实缺陷：`bumpVersions` 原先对不存在的范围行静默更新 0 行——**撤权了但版本不变、缓存不失效**。已改为递增前先 `ensure`，并由测试锁定。

### 2.6 身份模式准入（与 P1 的 `ExecutionModeAdmission` 同构）

- 数据库登记行（`security_mode`）与本实例配置必须一致，不一致拒绝启动并给出维护窗口指引。
- `OIDC` 模式必须有 `issuer-uri`，缺配置**拒绝启动**，绝不自动回退 `LOCAL`。
- 安全协议版本不一致同样拒绝。
- 测试豁免开关 `spec.agent.security.mode-admission-enabled`（生产不得关闭）。
- `verify()` 刻意不做"已准入即返回"的短路，使模式分歧在任何时刻可被复验（运维脚本与测试都依赖这一点）。

### 2.7 内部服务链

`/internal/**` 独立的 `@Order(1)` 链：STATELESS、无匿名过滤器、`permitAll`，认证仍由控制器内的内部 token 承担。**保持与改造前逐字一致的行为**——把它改成 `denyAll` 会切断 Python brain 的合法回调，而真实缺口（token 不绑定业务授权，P2-A 的 G4/G5）依然存在。P2-D 在此链之上追加 task token 与 ActorContext 校验。

### 2.8 OIDC 链

`oauth2Login` + `CookieCsrfTokenRepository`（非 HttpOnly cookie）+ 每个请求尽早解析 CSRF token + `logout /api/logout` + **JSON 401/403**（不重定向登录页）+ `AbsoluteSessionTimeoutFilter`（绝对登录期限 8h，不因活跃延长；与空闲超时 30m 并存）。OIDC token 不进 localStorage、URL、snapshot 或 Python。

### 2.9 回环绑定收紧（闭合 P2-A 的 G10）

`LoopbackBindingGuard` 新增第三层判定：**非回环绑定必须自带认证层**。

- 原先回环绑定同时承担"网络隔离"与"事实上的访问控制"；改绑网络地址会让后者消失，而一个布尔"知情开关"不能替代登录。
- 现在：非回环 + `LOCAL` **即使显式声明知情也拒绝启动**；只有 `OIDC` 模式才允许对外提供服务。
- 拒绝发生在监听之前（Bean 构造期），且配置字符串与实际连接器绑定两处都校验。

该行为是**有意的语义变更**，因此同步更新了既有测试：`explicitRiskSwitchCoversActualBindVerification` 显式加 `--spec.agent.security.mode=OIDC`（它验证的是"知情开关对实际绑定校验生效"，与模式无关），并新增 `localModeRefusesNonLoopbackEvenWhenDeclaredInformed`。

---

## 3. P2-C 已完成的一步（仅数据层）

`V61__organization_ownership_backfill.sql`，严格按设计 §11 的三步顺序执行**第一步**：

| 动作 | 内容 |
|---|---|
| 加列（nullable） | `projects.org_id` / `projects.created_by`；`global_assistant_threads.org_id` / `owner_user_id`；`profiles.org_id`；`project_members.org_id` |
| 引导 legacy 主体 | 固定标识的 `LEGACY` 组织与 legacy 用户 + OWNER 成员资格 + 两个授权范围行 |
| 回填 | 全部存量项目/会话/配置 → legacy 组织与 legacy 用户 |
| **核验** | `DO` 块：三类残留列必须为 0 行，否则 `RAISE EXCEPTION` 使迁移失败 |
| 约束 | `profiles` 由全局唯一名改为**每组织唯一名**；`profiles(id, org_id)` 唯一；`projects(default_profile_id, org_id) → profiles(id, org_id)` 复合外键（**冻结决定 #2**的数据库侧落地）；`project_members(project_id, org_id) → projects(id, org_id)` 复合外键 |
| 索引 | `projects(org_id, created_at, id)`、`ga_threads(org_id, owner_user_id, created_at DESC, id DESC)`、`profiles(org_id)` |

**单机兼容的关键设计**：legacy 组织/用户与 LOCAL 模式的本地组织/用户使用**同一组固定标识**（`...0001` / `...0002`）。单机部署的存量数据因此天然属于本地组织，行为与改造前逐字一致；多人部署由管理员在维护窗口离线审核后显式分配。

**未做的第三步**：`SET NOT NULL + FK` 收紧。第三步必须与<b>全部</b>写入路径改造同时落地，否则新建行立即违约；写路径已在本轮 P2-C 落地（见 §3.2），因此收紧现在只差一次迁移与一次全量回归。

---

## 3.2 P2-C 后端实现（本轮新增，G1/G2/G3 闭合）

### 归属规则：判据是「主体来源」，不是「组织 id」

第一版实现把单机放行写成 `mode == LOCAL`，随即改成 `orgId == 本地/存量组织`——**两者都是错的，第二个更危险**：

- `mode == LOCAL`：在 LOCAL 测试档下与"无条件放行"完全等价，跨组织路径永远不可测。
- `orgId == 本地组织`：这是一条**授权豁免**。任何被加入 legacy/local 组织的 OIDC 用户（哪怕只是普通成员）都会获得该组织内全部存量项目的所有者权限——legacy 组织因此成了提权通道。

最终判据是**主体来源**：

| 主体来源 | 项目/会话权限来源 |
|---|---|
| `LOCAL` | 单机交付语义：该主体是本地组织所有者。**只能由受限的本地身份适配器产生**（`IdentityService.localActor`，仅 LOCAL 模式、仅回环绑定、有真实可禁用的行列） |
| `OIDC` | **项目角色**。组织管理员不因管理角色获得正文读取权；legacy 组织成员资格不带来任何特权 |
| `SYSTEM` | 仅其登记组织内、且不主张组织角色能力 |
| `SERVICE` | 内部 token 已验证也**不**构成业务授权 |
| **无主体** | **拒绝**（`UNAUTHENTICATED`）。不存在匿名或"本地管理员"回落 |

禁用判定（`USER_DISABLED`）在任何特权判定**之前**生效，因此被禁用的本地主体同样失去单机权限。

### 候选集必须在 SQL 里同时按「组织 + 项目访问权限」过滤

只按组织过滤是不够的：同组织内**没有项目角色**的成员仍会通过列表长度、标题与 GA 检索结果看到他人项目的存在与数量。因此列表与搜索的候选生成阶段使用同一组查询：

```sql
SELECT p.* FROM projects p
WHERE p.org_id = :orgId
  AND EXISTS (SELECT 1 FROM project_members m
              WHERE m.project_id = p.id AND m.user_id = :userId)
```

`unrestrictedInOrg` 只对 LOCAL 来源主体为真，且由 `AuthorizationGuard.visibilityScope()` 统一给出——它**不是授权**，只回答"候选集从哪儿取"，每个候选的读取/写入仍由 `projectDecisionFor` 判定。过滤放在 SQL 内而不是取回后筛：数量与排序本身就是信息。

### 授权的落点

| 落点 | 内容 |
|---|---|
| `AuthorizationGuard.projectDecisionFor(actor, projectId, capability)` | **新增显式主体重载**。后台任务/内部 RPC 没有请求线程，其主体必须从持久化事实重建（P2-D），因此判定必须接受显式 `ActorContext`；HTTP 路径继续用线程绑定版本 |
| `AuthorizationGuard.requireProjectFor` / `requireProjectGovernance` | 判定并抛拒绝；治理能力（组织 OWNER/ADMIN 授予项目角色）与内容读取分离 |
| `AuthorizationGuard.requireThreadOwnership` | 会话私有性的唯一收口点；非属主一律 `concealed 404` |
| `OwnershipAttribution`（中性 `access` 根包） | 归属规则的**单一事实源**：有主体用主体，无主体只在 LOCAL 回落到显式本地主体，OIDC 无主体则失败关闭。放根包是为了让 root 与仓储层共用而不产生 `root -> identity` 反向边 |
| `LocalPrincipalIds` | 本地/存量组织与用户的固定标识，下沉到 root，供判定与仓储共用 |

### 逐项改动

| 项 | 改动 |
|---|---|
| **G1** 项目全库枚举 | `ProjectRepository` 新增 `findAllInOrg` / `findByTitleContainingInOrg` / `existsByTitleIgnoreCaseInOrg`；`ProjectService.listProjects*` 按主体组织过滤；`GlobalProjectSearchService`（GA 的 `project.search` / `list_recent` 工具）改为按组织生成候选——过滤发生在**候选生成阶段**，不是排名后隐藏 |
| **G2** GA 会话列表全库 | `GlobalAssistantThreadListRepository.listRecent` 的 SQL 增加 `WHERE t.org_id = ? AND t.owner_user_id = ?`（过滤在 SQL 内，避免分页与排序泄露数量）；`GlobalAssistantThreadRepository.create()` 落 `org_id`/`owner_user_id`，缺组织选择时以 `ORG_SELECTION_REQUIRED` 拒绝而不是写入无主会话；`GlobalAssistantConversationService.requireThread` 成为私有性收口点，并新增 `findThreadOwned` 供入口使用 |
| **G3** 规格快照按 ID 直读与导出 | `SpecController` 新增 `requireVisibleSnapshot`：`snapshot -> project -> org` 推导归属并校验可见性；**读取与导出走同一条**授权路径；`listRouteSpecs` 改用授权版读取 |
| 项目写入授权 | `ProjectController` 的 get/list/active 走授权读取；`PUT /title` 要求 `PROJECT_MANAGE`（冻结决定 #1：EDITOR 不允许重命名）；`DELETE` 前先判定 `PROJECT_MANAGE` |
| 归属落库 | `ProjectRepository.save(project, orgId, createdBy)`；`ProjectService.createProject` 从主体取 org/created_by |
| 项目角色自举 | 创建者自动成为该项目 `MANAGER`（`grantProjectManager`）。理由：设计禁止组织管理员凭管理角色读正文，因此必须由创建行为产生角色，否则新建项目在多人模式下无人可读 |
| **冻结决定 #2 落地** | 复合外键要求「项目默认画像同组织」，因此新增 `ProfileRepository.ensureOrgDefaultProfile` / `ProfileService.getDefaultProfileIdForOrg`：新组织首次建项目时按模板复制一份组织内画像。对本地/存量组织解析结果仍是播种行，单机行为不变 |
| 错误契约 | `ApiException.forbidden/unauthorized`；`ApiExceptionHandler` 映射 `AccessDeniedException`：`concealed → 404`、未认证/用户禁用 → `401`、其余 → `403`，均携带稳定原因码 |

### 已知边界（如实声明）

1. **主体解析分两层，两层都不得被当作"匿名放行"**：
   - *显式主体 API*（`projectDecisionFor` / `requireProjectFor` / `requireThreadOwnershipFor`）传 `null` 就是"没有主体"，**一律拒绝**。后台任务与内部 RPC 必须走这一层，并在调用前把主体从持久化事实重建出来（P2-D）。
   - *线程绑定入口*（`projectDecision` / `requireProject` / `requireThreadOwnership`）先解析主体：已绑定则用；未绑定且为 **LOCAL** 模式则回落到受限本地身份适配器产生的**显式本地主体**（单机交付只有一个主体，有真实行列、可被禁用）；**OIDC 模式下解析失败并按未认证拒绝，不存在任何回落**。`ResourceIsolationIntegrationTest.unboundAttributionFailsClosedOutsideLocalMode` 把这条性质变成可执行断言。
   - 创建归属与列表候选集同样使用该解析（`OwnershipAttribution`），但归属**不是授权**：每个候选的读取与写入仍由判定决定。
2. `GlobalAssistantConversationService.requireThread` 是收口点，因此**GA 运行时的消息写入也经过私有性判定**：OIDC 模式下 Worker 线程此前尚无主体，会被拒绝。**P2-D-1 已闭合**（见 §3.4）：Worker 从持久化任务行重建主体并仅在本次执行绑定；**没有**用匿名或服务超级权限回退。
3. 项目标题唯一性仍是**组织级**检查：它能泄露"这个标题在本组织内已被占用"。不改为按可见性检查是因为那会允许组织内重名；这是刻意保留的取舍，记录为残留。
4. `ProfileRepository.findByName` / `findAll` 仍是组织无关查询——画像已按组织隔离，但这两条读路径尚未按组织过滤（P2-E）。
5. `LocalPrincipalIds` 与 `ActorContext.local(...)` 目前是公开构造入口，即"LOCAL 来源只能由受限适配器产生"是**约定 + 评审**，尚未由门禁强制。计划补一条 ArchUnit 规则：只有 `com.specagent.access.identity` 可以调用 `ActorContext.local(...)`。
6. SSE 订阅、缓存与记忆隔离未实施（P2-D）。

---

## 3.3 P2-C 剩余：画布只读面授权（本轮新增）

把画布只读查询的"项目可见性"从**约定**变成**服务层判定**。改动落在读模型
服务（不是控制器），因此后台调用同样受校验（设计 §12）。

| 服务 | 端点 | 改动 |
|---|---|---|
| `GraphWorkspaceQueryService.getForProject` | `GET /api/v1/projects/{projectId}/graph` | `ProjectService.getProject` → `getProjectForActor`（PROJECT_READ） |
| `RouteLineageQueryService.getForRoute` | `GET .../routes/{routeId}/lineage` | 同上 |
| `RequirementStateQueryService.getForProject` / `getForRoute` | `GET .../requirement-state`、`.../routes/{routeId}/requirement-state` | 同上 |
| `RouteController.listRoutes` | `GET /api/v1/projects/{projectId}/routes` | `getProjectForActor`（PROJECT_READ） |

**外部契约不变**：`getProjectForActor` 对"不可见/不存在"统一抛
`404 PROJECT_NOT_FOUND`，与既有 `*QueryException.PROJECT_NOT_FOUND` 的
状态码与错误码**逐字一致**（见 `ApiExceptionHandler` §63–97）。因此这两类
不可能再通过"码不同"探测他人项目是否存在。授权落在服务层而非 `@PreAuthorize`。

单元测试 `GraphWorkspaceQueryServiceTest` 由 mock `getProject` 改为
mock `getProjectForActor`（行为契约不变；mock 只是跟随了新入口）。

**仍未闭合**：`GraphCommandController` 的写端点与读端点（`relations`、
`graph-operations`、`availability` 等）仍无项目授权——它们经
`GraphCommandService`/`UndoRedoService` 直接落地，需要在服务层补
PROJECT_READ/WRITE。计入 §8 剩余。

---

## 3.4 P2-D-1：任务主体持久化与执行期重建（本轮核心）

**问题**：Recovery Worker / 内部 RPC 没有请求线程。修复前，OIDC 模式下 GA
执行期对会话正文的写入会在 `requireThreadOwnership` 私有性收口点因"线程无
主体"被拒——失败关闭但 GA 在多人模式下不可用。设计 §7 要求"恢复 Worker 根据
数据库重建 ActorContext，不依赖 HTTP 线程上下文"。

### 数据层（迁移 `V62__runtime_task_authorization_context.sql`）

`runtime_tasks` 新增：`actor_user_id`、`actor_org_id`、`actor_source`、
`authorization_context_ref`。要点：

- **三元组缺一不可**：判定规则按 `actor_source`（LOCAL/OIDC/SYSTEM）展开，
  因此只存 user/org 不足以重建主体来源。
- **默认值指向显式本地主体**（与 `LocalPrincipalIds`、V61 引导行逐字一致）：
  存量行与第一阶段遗留的直插路径都得到一个真实、可禁用、可审计的主体，而
  不是"匿名管理员"。这不是授权豁免——OIDC 模式下 `OwnershipAttribution`
  无主体时**失败关闭**，生产写路径不可能"忘记写主体→静默变成本地主体"。
- **一致性 CHECK**：LOCAL/OIDC 必须携带 userId；SYSTEM 必须不带。
- **硬核验**：`DO` 块对无法重建来源的行 `RAISE EXCEPTION`。

### 代码层

| 位置 | 改动 |
|---|---|
| `TaskActor`（`runtime.execution`，新建） | 持久化主体三元组；`toActorContext()` 把事实翻译回 `ActorContext`（LOCAL/OIDC/SYSTEM），只做翻译不做判定。放 runtime 而非 access：依赖方向恒为 runtime → access |
| `ActorSource.fromCode` | 从持久化事实解析来源，**未知值一律拒绝**（不静默回落） |
| `RuntimeTask` / 两个 RowMapper | 读视图携带 `TaskActor actor` |
| `RuntimeTaskRepository.insert` / `insertIfAbsent` | 写入主体三元组 |
| `WorkspaceTaskEnqueuer.enqueue` | 组织取自**项目**父关系，主体与项目组织必须一致，否则 `concealed 404` |
| `GaTaskEnqueuer.enqueue` | 组织与属主取自**会话**父关系；命令路径要求绑定主体与会话属主一致，**后台路径（轮次续转/恢复）沿用会话属主**（不是当前 Worker 或管理员），来源由属主身份确定 |
| `WorkspaceTaskExecutionAdapter.execute` | 从任务行重建 `ActorContext`，仅在本次执行绑定（`ActorContextHolder.bind`），结束复原 |
| `GaTaskExecutionAdapter.execute` | 同上——**这正是修复 OIDC Worker 执行缺口的地方** |

**绑定只覆盖本次执行**：`ActorContextHolder.bind` 与 `ExecutionTokenContext`
同构，用完复原，避免线程复用把上一个任务的身份泄漏给下一个。

**按用户要求区分三类主体**：
- 任务执行：以持久化**发起者**身份展开（本批）。
- 轮次续转（`TurnHandoffService`）：GA 入队的后台路径**沿用会话属主**（本批）。
- SSE 补发：服务订阅发生在 HTTP 请求线程，主体由 `ActorContextFilter` 绑定，
  天然是**订阅者**身份，本批不改（保持"每个订阅者自己的身份"）。
- 系统恢复：`GlobalAssistantRunRecoveryService` 的**受限服务权限**尚未落地，
  仍依赖 LOCAL 回落；计入 §8。

### 验证（`RuntimeTaskActorContextIntegrationTest`，5 项，真实 PostgreSQL）

- 主体三元组原样落库/读回，重建后即项目授权判定主体（创建者 MANAGER）。
- 遗留直插路径（不含主体列）默认收敛到**显式本地主体**。
- 未知 `actor_source` 失败关闭。
- 重建主体驱动 `requireThreadOwnershipFor`：发起者通过、**同组织管理员被拒且 concealed**。
- 系统主体无 user 字段，不被当作用户或超级管理员。

---

## 3.5 2026-10-13 续接实现

- **GA 独立委托**（V63）：Run 创建事务捕获 user/org/source；单执行器模式无需虚构 runtime task，多 Worker 必须有当前 task/epoch/token，且两个委托一致。缺委托、LOCAL 委托进入 OIDC、禁用/撤权均拒绝。模型 RPC 从持久化 Run 重建主体后绑定，仅在本次调用内使用，避免读取其他组织模型配置。
- **动态写授权**：TaskWriteGuard 在 task 排他锁后核验授权；GraphCommand/UndoRedo/Route 写入先授权范围锁，再项目/业务锁。GA completion/preparation 调整到授权先于 Run 锁。proposal 手工接收目前只补了项目写授权，执行 fence 仍待补。
- **撤权 outbox**（V64）：成员角色/禁用变更写 outbox；按 task → 容量槽 → execution scope → authorization scope 收敛。新增中性 AuthorizationRevocationHandler 端口，GA 实现取消无任务行的独立 Run；只写终态元数据与 RUN_CANCELLED，不访问会话正文。重复消费不重复写终态事件；授权仍有效的委托不误取消。
- **GA SSE**：订阅保存浏览器主体与 session id；补发及实时发布复查私有性/成员资格，并查共享 Spring Session 活性。另实例退出后停止补发；没有用 Worker 身份代替订阅者。
- **配置/密钥**（V65–V67）：OIDC 不允许缺主密钥而本地生成；数据库保存主密钥指纹，舰队不一致拒绝启动。模型提供商与四个模型配置仓储查询/写入带 org 条件；组织能力控制写入，并在事务内取授权锁。激活提供商复合 FK 限制同组织，删除只清空 active_provider_id。
- **模型密文**：enc:v2 使用 AES-GCM AAD 绑定 org、表/用途、行键、api_key。无范围解密、跨组织/行/用途搬运均失败。启动迁移将既有 v1 升级到 v2，按 org+行键+原密文 CAS 更新，避免多组织 singleton_id=1 互相覆盖。embedding 目前仍是未隔离的 v1；search 已在 §3.6 扩展，不能把模型这一增量写作全部凭证完成。
- **前端**：会话门控、真实 OIDC 入口、CSRF、组织选择请求头、401 重载身份、完整刷新切组织以销毁旧 store/流；组织成员与项目成员页面、账户 ID 展示。移动端顶栏改为随内容增高，避免遮住页面。
- **结构/测试修正**：补 testNonLive 的 testClassesDirs/classpath，真实执行测试。连接池返回前显式释放模式 advisory lock。增加主体构造门禁，LOCAL 签发仅限身份适配器/本地归属适配器/持久化 TaskActor 恢复；裸 ActorContext 构造仅限声明的身份与事实读取点。

## 3.6 后续加固（V68，源码已扩展，验收 JVM 仍为 V67）

- SearchSettings 改为组织复合 singleton；查询、保存、清除、探测记录带 org，写操作在授权共享锁之后取组织级配置锁。OIDC 不再读取或导入安装级环境 key，LOCAL 保留兼容；密文同样使用 AAD v2。GA capability broker 在目录与外部准备阶段就绑定重建主体。
- profiles 按 org 过滤 ID/名称/列表；复制默认模板只允许固定安装内置模板，不允许把其他组织画像当模板。无组织/无成员资格不能创建项目；创建者、项目、路线与画像在同一事务落库。
- 标题可用性检查仅查可见项目，不再泄露同组织内无角色项目；允许不可见项目同名，以 ID 区分。重命名先核实目标能力，删除与重命名在业务事务内持授权共享锁，不能在撤权提交后按旧判定继续写。
- 管理员不能把已有 OWNER 降级，即使还有另一位 OWNER；改变 OWNER 资格双向要求 ORG_OWNER。
- profiles 收紧后的 5 项失败定位为夹具未注册创建者的组织成员资格，已修正夹具并新增无资格创建拒绝测试。删除/入队竞态可能先在授权校验失败或在项目锁重读失败，测试保留这两个实际时序，但显式核验授权层 PROJECT_NOT_FOUND/404 及最终无孤儿 Run。
- 多身份浏览器已验证 Alice/Bob 独立登录与建组织、Bob 空项目列表、跨组织项目与随机不存在项目均得到 PROJECT_NOT_FOUND；跨实例退出后 API B 返回 UNAUTHENTICATED。见 [P2_OIDC_BROWSER_PARTIAL_EVIDENCE.json](evidence/P2_OIDC_BROWSER_PARTIAL_EVIDENCE.json)。这些浏览器证据对应 V67 构建，不证明 V68 或全部角色矩阵完成。

## 3.7 画布独立 Run 委托与动态授权（V69，2026-10-13 续）

- `workspace_run_authorizations` 保存不可变 user/org/source；Run 插入与委托同事务，授权范围锁先于 Run 插入。幂等竞争输家不重绑定胜出 Run 的主体。
- V69 仅从已存在的 runtime task 主体事实回填，不从项目创建者/管理员/部署模式推断发起者。旧单执行器 Run 缺少委托时诚实拒绝与元数据失败；升级前须 drain，不能把旧活动 Run 默认为 LOCAL。已有任务事实可在持有当前 token 并验证活性授权后补全 Run 委托，且不覆盖已有委托。
- `RunWorker` 从持久化事实重建主体，在本次执行内绑定 ActorContext 与 Run 路由提示；结束/异常都恢复线程上下文。单执行器不要求伪造 runtime task；多 Worker 校验 token、任务族、业务 Run 与两个主体事实一致。
- `ExecutionFence` 在业务写入起点复查绑定 Run 授权；`AgentRunService` 的 checkpoint/结果状态写入即使 epoch=0 也使用短事务，核验真实委托与当前权限。绑定 Run 与 checkpoint 目标不一致时拒绝。
- 授权拒绝后，Worker 仅可在退出执行上下文后写固定控制面失败，不落迟到模型正文。撤权 outbox 的画布独立 Run 处理器只读主体/项目标识，条件失败写入与 RUN_FAILED 事件在同一事务，重复投递不重复事件。
- 新测试揭示并修复：OIDC `projectDecisionFor` 原先只查用户状态和项目角色，漏了组织成员资格/组织状态；组织撤权后仍可提交 checkpoint。现将 `canUseOrg` 与项目角色同时作为前提，并新增直接授权与迟到 checkpoint 断言。
- 同组织无项目角色者的直读同样隐藏为 RESOURCE_NOT_VISIBLE → PROJECT_NOT_FOUND/404，与随机不存在 ID 完全相同；已有 VIEWER/EDITOR 角色但缺写/管理能力仍为 403。新增状态码及错误码对照，避免 403/404 差异探测。
- 控制面捕获授权拒绝时，嵌套 `@Transactional` 曾把 REQUIRES_NEW 失败事务标成 rollback-only，导致 UnexpectedRollbackException 遮蔽原类型化失败。授权组件改为复用现有事务，只有无事务调用才开短事务；外层业务失败依旧回滚，控制面拒绝不假装已提交失败。
- `WorkspaceExecutionAuthorizationIntegrationTest` 覆盖无任务/无请求 OIDC 委托、真实 fake 决策闭环完成、缺委托拒绝、禁用/撤权、项目降级、LOCAL 不进入 OIDC、outbox 幂等、跨 Run checkpoint 拒绝。它模拟 OIDC 主体规则并执行真实 Java 业务链，**不等于真实 IdP + 进程 Worker/RPC 的端到端验收**。
- 全量首轮被隔离 PG 的 100 连接上限阻塞，已用 jcmd 栈及 PostgreSQL FATAL too many clients 证实。保留生产参数，仅将测试 JVM Spring context cache 限制为 6；重跑 1735 项、0 失败、33 跳过。最后的无角色直读隐藏调整追加针对性回归 120 项、0 失败。

## 3.8 Workspace 模型 RPC 的目的限定授权票据（V70，2026-10-13 续）

- 共享 internal secret 只认证服务；`/internal/v1/model-inference` 现在还必须提交 Java 签发的 `X-Spec-Agent-Execution-Grant`。没有授权票据，已存在与不存在 Run 均拒绝，不再用 Run 存在性向仅持服务 secret 的调用者提供探测面。
- 票据使用 32 字节随机值，库内只存 SHA-256；绑定原始 user/org/source、业务 Run、MODEL_INFERENCE 目的、workspace-brain.v1 audience、封闭 callType、1–2 次模型额度和最长 180 秒期限。多 Worker 还绑定 task/epoch/lease/worker；单执行器绑定 epoch 与 advisory lock 会话 PID。
- `WorkspaceRpcGrantService` 先验证执行所有权与当前授权，再锁 Run、票据、账本。单执行器 callback 在 epoch 未变但原租约会话已丢失时也拒绝。票据不包含厂商密钥，不用请求参数重建主体。
- 模型调用前持久化 RESERVED，按票据与请求摘要禁止同载荷重发；有界 conflict repair 可用第二次额度，失败/不确定不退款。模型 I/O 不占数据库事务；释放输出前再查票据、租约、Run 活性与权限，账本及脱敏事件同事务提交。晚到结果只留下 UNKNOWN 元数据，不能追加正文事件。
- Java RemotePythonDecisionEngine 在每次 Brain 调用前签发、通过 header 发送，并在 finally 关闭票据。Python 按每个请求独立构造 BrokerModelClient，只转发本请求票据，缺票据不接触 broker；请求完成后关闭其自建 httpx client，外部注入的测试 client 不误关闭。
- Python /health 公告 workspaceRpcGrantVersions。当前生产者/消费者已实现票据传递；Java 侧完整 health 能力协商与旧 Brain 的明确协议不匹配验收仍需补，不能把公告当成兼容验收。
- 已有模型 RPC/RemoteEngine/ArchUnit 针对性回归通过；真实 PG 断言 service secret 单独不放行、跨 Run/跨 callType 拒绝、重复请求不重发、额度不重置、关闭/过期拒绝、关闭期间晚到提交拒绝、single epoch 未变但租约会话失去拒绝。
- Python 全量 239 项通过，包含真实 FastAPI → BrokerModelClient → mock HTTP 转发与并发请求不串票据；这不是 Java+Python 双进程端到端证据。Java 最新全量 testNonLive 已完成：1742 tests / 0 failures / 0 errors / 33 skipped / 287 suites，BUILD SUCCESSFUL（9m 12s）；直接汇总当前 XML，包含 V70 与最终同组织无角色隐藏调整。
- 其余检索/embedding RPC 的服务身份与授权范围、GA 服务身份进一步细分、记忆依赖撤权、真实双进程/双 Worker 故障矩阵仍未闭合。G4/G13 仅模型 broker 子入口取得实质闭合，不能写成全部内部 RPC 完成。

## 3.9 安全协议升级准入（V71，2026-10-13 续）

- V71 将 `security_mode.protocol_version` 从 1 升至 2；未知登记版本拒绝迁移，不擅自覆盖。新实例必须匹配协议 v2；该版本覆盖范围密文与持久化 Run/RPC 委托的读者要求。
- OIDC 准入同时核对数据库登记 issuer 与配置 issuer，防止同模式但不同 IdP 的舰队混入；复验失败清除旧 admitted 标记。这不是自动运行期监控或请求拦截器。
- 专用 PG 自动测试库已成功迁移 V71；access + architecture 针对性回归 115 项、0 失败、0 错误。最新全量仍是 V70 的 1742 项，未将针对性结果冒充 V71 全量。
- **真实旧 JVM 拒绝证据**：以保留的 V67 jar（协议 1）连接隔离 LOCAL 测试库（协议 2），关闭 Flyway 仅为了直接验证 admission；启动退出码 1，明确报 `Security protocol version mismatch: database=2, this instance=1`。原始日志在忽略的 build/p2-acceptance，jar SHA256 与结果写入回归证据 JSON。
- **强制维护窗口仍必要**：迁移前停全部 API/Worker/凭证迁移器，备份数据库和主密钥，再启动新版舰队；V71 不能阻止已经运行的旧二进制。不得把登记版本改回 1 来绕过 enc:v2 不兼容。
- OIDC 演示库与正在运行的两个旧 API 仍维持 V67，没有在线迁移或混跑；新版 OIDC Worker/RPC 双进程验收仍待完成。

## 3.10 检索授权与用途边界（V72，2026-10-13 续）

- 检索搜索 grant 新增 actor_user_id/org_id/source 与可选执行 token，主体只在 Host 签发时从已绑定身份解析；读取 RPC 从持久化 grant 重建，不依赖请求线程，也不从客户端过滤器、共享 secret 或 LOCAL 默认重建。
- V72 不猜测旧搜索 grant 的主体，全部撤销；INDEX_JOB 继续保留既有 job/source/generation/deadline 范围协议，不冒称其完整组织服务身份改造已完成。
- Java HybridRetriever 在候选 SQL 前检查可读项目，输出前再核验；Python candidates/validate-sources/embedding-profile/embeddings 共用 grant 活性核验。成员撤权、禁用、项目角色移除后旧 grant 拒绝；多 Worker grant 捕获实际 task token 并在使用时重新锁 task 验证租约与主体匹配。网络调用期间不保持这些授权锁。
- GA 检索必须匹配持久化 Run 委托与会话属主；代次改从 ga_executions 读取，移除搜索 grant 的 GA epoch=1 硬编码。画布 PROJECT_RUN 同样核实实际委托、项目父链与 owner_epoch；CONTEXT_PROJECTION 的 grant 是短期用户读取委托，不伪装成业务 Run。
- INDEX_JOB 票据只能以 SplitRequest/IndexBatch 使用，不能转换为 Search/Candidates/Validation 枚举 corpus；新增真实 index batch 的用途拒绝断言。
- 新增 RetrievalAuthorizationIntegrationTest 8 项真实 PG 测试：持久化重建、同组织无项目角色 Java/共享检索拒绝、跨组织拒绝、项目角色移除、组织成员禁用、用户禁用、GA 实际代次与接管拒绝、同组织他人 Run 拒绝。既有向量夹具从虚构 UUID/改写 workload 改为真实会话和 Run，不扩大产品放行。
- 检索+架构专项 114 项、0 失败、6 跳过；此结果在最终索引用途限制前取得，V72 全量复验已通过：1753 tests / 0 failures / 0 errors / 33 skipped / 289 suites；包含最终索引用途限制。未将跳过的真实 Python/Ollama 资格验收当成通过。
- 未完成：INDEX_JOB 组织归属和受限 SYSTEM 身份、embedding 配置组织归属/凭证 AAD、provider 费用预算与 RPC 收据、单执行器非 task 检索票据物理 lease 丢失验收、检索/记忆依赖撤权与完整双进程端到端证据。当前没有把 G4/G13 或 P2-D/E 写成全部完成。

## 3.11 索引服务身份与数据库约束（V73/V74，2026-10-13 续）

- 私有项目索引 grant 明确持久化 `SYSTEM_INDEXING + service_org_id`，组织从 corpus 的项目父关系解析；公共帮助固定为部署内置 corpus + `SYSTEM_HELP_INDEXING`，不使用本地超级用户，也不共享私有项目服务范围。
- 认领/准备索引与私有原文分块前锁组织/项目授权范围，检查组织有效；提交先从 job 读取 corpus 并取得授权，再锁 generation/job/source，避免新增 `业务行 → authorization` 反向顺序。RPC 使用持久化服务事实复验，同库任意其他组织不是替代身份。
- V73 撤销旧 INDEX_JOB grant 并将旧 CLAIMED job 标记 STALE，不追认来源未知的服务授权；计算是派生操作，后续可重新认领。
- V74 修正 PostgreSQL CHECK 对 UNKNOWN 放行的陷阱，显式要求 service_kind 非空；公共帮助 corpus 固定 UUID 校验；新增 `(project_id, service_org_id) → projects(id, org_id)` 复合外键，不允许业务行与服务组织矛盾。未改已应用的 V73 校验和。
- 新增 IndexServiceAuthorizationIntegrationTest 6 项真实 PG 验证：无请求主体后台索引、组织禁用拒绝认领/旧 grant、计算途中禁用拒绝落向量、其他组织绑定被 FK 拒绝、NULL 类型被 CHECK 拒绝、公共帮助身份独立。
- 附加核实 corpus/project 来源一致性：已有 `ck_retrieval_entries_corpus` 实际拒绝将他项目的 canonical source 挪入可见 corpus；追加数据库边界测试，没有把现有约束误报为新修复。
- V73/V74 迁移成功；检索+架构专项 121 项 / 0 失败 / 0 错误 / 6 跳过，BUILD SUCCESSFUL。最新全量仍为 V72 的 1753 项，不混写。
- 尚待核实：embedding 配置/凭证组织归属与 provider 预算收据；head 激活与 rebuild/后台枚举全部服务控制路径的锁序/禁用收敛；单执行器物理 lease；完整双进程/记忆撤权验收。此节不代表 P2-D/E/F 完成。

## 3.12 Embedding 组织配置与执行期取密钥（V75/V76，2026-10-13 续）

- embedding_service_settings/revisions 改组织复合主键；probe/rebuild 带 org 归属。迁移按加列→legacy 回填→零缺失校验→NOT NULL/FK 执行，新列没有隐式 legacy DEFAULT。
- Python 对语义哈希有严格校验，所以 embedding_profiles 的不可变语义 registry 保留；新增 embedding_profile_registrations `(org_id, profile_id)` 与同组织 revision FK，作为批准和传输配置事实。相同哈希可分别登记，不能借用别人的配置、修订或密钥；这项实施修正已同步设计 §4.2 和入口清单，不把新表关系伪装成原盘点已经指定。
- 保存/清除/注册/探测要求 CREDENTIAL_MANAGE；每条 SQL 按组织过滤、每组织独立 advisory lock；api_key 两族分别绑定 org/table/row 的 enc:v2 AAD，迁移器 CAS 加入 org，清除只撤销当前组织修订。
- 内部 broker 从有效搜索/服务 grant 取组织，profileForOrg/key 使用明确持久化登记，不从无请求线程回落 LOCAL；provider 调用后复查 grant、组织和密钥撤销。Java controller + 实际 PG grant/两组织登记 + mock transport 已验证两次分别取自己的 key，以及在 provider 调用中禁用组织不返回 vectors；不是实际 provider/Python 双进程证据。
- 重建可作用于有 PROJECT_MANAGE 的本组织项目，同时要求 CREDENTIAL_MANAGE；安装级公共 HELP 仅受限 LOCAL 来源可改写。任务列表与按 generation 操作在 SQL 内按组织+项目角色过滤，同组织 ADMIN 无项目角色不获任务 ID。项目 raw source split 支持已批准的 v2 generation，head 激活加入服务组织活性检查。
- V76 generated project_id + `(project_id, org_id)` FK 防止 rebuild 关联别组织项目；rebuild/profile 与 candidate/profile 同组织 FK 同时生效。
- OIDC 下公共 HELP 若仍激活 legacy 组织的 configured profile，内部 active-embedding 明确拒绝，不借用安装/其他组织配置；固定 v1 公共资料路径仍保留。多人切换前需核实公共帮助 profile；每组织独立 HELP corpus/向量配置仍待完善，当前不可声称公共帮助所有配置形态已验收。
- 新增 EmbeddingOrganizationIsolationIntegrationTest 14 项真实 PG 测试；检索+模型凭证+架构专项 185 项 / 0 失败 / 6 跳过。V75/V76 迁移成功；V76 最新全量回归 1774 tests / 0 failures / 0 errors / 33 skipped / 291 suites，BUILD SUCCESSFUL。
- 明确未完成：rebuild Worker 租约/启动恢复已修并取得真实 PG 与 Java/Python 进程证据（§3.14），多 Worker JVM/容量矩阵仍待验收；provider 预算/收据、OIDC 浏览器的项目重建入口、真实双进程配置/撤权验收。V75/V76 又改变读者/迁移器前提，因此准入已由 V77 升至 v3（§3.13）；仅 V71 的 v2 不足以隔离新旧 embedding reader。

## 3.13 安全协议 v3 与实际旧 v2 JVM 拒绝（V77，2026-10-13 续）

- V77 严格从登记 v2 升 v3；未知版本拒绝。新 admission 仅支持 v3，同时保留模式/issuer 校验。原因是 V75/V76 的 embedding 组织复合键、AAD 与批准关系对旧 reader/迁移器不兼容。
- 从已通过 V76 全量的代码构建并保留 protocol2 jar，未改写既有 V67 演示 jar。应用 V77 后用该真实 v2 jar 连接隔离 LOCAL 测试库，关闭 Flyway 仅为了直接测 admission；启动退出 1，报 `Security protocol version mismatch: database=3, this instance=2`。jar SHA256 和结果写入证据 JSON。
- access+architecture 专项 115 项 / 0 失败 / 0 错误；V77 success=true。最近全量仍为 V76 的 1774 项 / 0 失败 / 33 跳过，不把 V77 专项写成新全量。
- 维护窗口停所有 API/Worker/迁移器仍是强制条件；数据库版本变化不能停掉已运行的旧进程，不能降回协议 2 绕过拒绝。

## 3.14 重建 Worker 租约、接管与父子 grant（V78/V79，2026-10-13 续）

- 修掉全库启动恢复误杀：recover 仅处理 RUNNING 且数据库时钟确认租约过期的任务；QUEUED 意图和其他实例的存活租约保留。旧无所有权 RUNNING 数据由 V78 迁移重排队，不伪造 Worker 委托。
- 每个候选独立短事务认领，FOR UPDATE SKIP LOCKED + 单调 execution_epoch + 随机 lease/worker + DB lease_until；失败或完成仅有效 owner 可提交。每个 tick 是一个有界分块/索引单元，完成后回到队列或 READY，不跨网络持锁。默认 120s 是实验参数（允许 100..300s），不是承载承诺。
- Child grant 保存 rebuild_id/epoch/lease，Python 仍仅拿 opaque grant。签发/校验/分块提交/向量提交均检查父租约；父过期在 epoch 尚未变化时也拒绝结果。接管撤销旧 grant、将旧 CLAIMED child 标为 STALE，保留 generation 和已准备向量/进度；这是可重算派生工作，不承诺 provider 只调用一次。
- 授权范围 → rebuild → generation/job/source 的锁序实际应用到提交和元数据收口。项目级联删除增加项目授权范围排他锁（组织/用户为共享，统一排序），先于业务行；删除补清 embedding_rebuilds 后再删除 generation，防止父子锁环与 FK 悬挂。Agent 活跃 Run 的既有 409 删除保护仍保留，未冒称 P2-A 的全取消/outbox 删除协议已完成。
- RebuildLeaseIntegrationTest：8 项独立真实 PG 事务测试，双 Worker 原子认领、启动保活、epoch 接管保进度、旧 finish/fail 拒绝、父过期但 epoch 不变拒绝向量、继任从同 generation 完成、组织禁用允许窄元数据失败、恢复换租约，以及 PG 阻塞现场证明删除等待 authorization_scopes 而非先拿业务行。
- 第 9 项实际启动隔离 Python/uvicorn，经真实 Java→Python→Java internal RPC 驱动实际 tick 两个单元到 READY；provider transport 为测试替身（3 维向量），无付费外部调用。这不是两个 Worker JVM / OIDC 浏览器故障矩阵。明确启用 SPEC_AGENT_REBUILD_PYTHON_ACCEPTANCE=true 的专项共 73 项、0 失败、0 跳过；V79 全量回归已完成：1783 tests / 0 failures / 0 errors / 33 skipped / 292 suites，BUILD SUCCESSFUL；第 9 项真实 Python 验收在此次全量也实际执行通过。
- Windows .venv launcher 可能持有子进程/日志句柄，测试清理需杀已记录 descendants 并等退出，再有限重试删自己的临时日志；首次资格验收曾因清理失败，修复后复跑成功，不把初次失败写成通过。
- V79 安全协议升 v4，隔离库成功迁移；保留实际 V77/protocol3 jar，关闭 Flyway 直接测准入，启动退出 1，明确 database=4, this instance=3。旧二进制仍需维护窗口停全部实例，升级不隔离已运行旧进程。
- 尚未闭合：provider 预算/调用收据、多个 Worker JVM + OIDC 端到端、全局容量与公平性测量、公共帮助配置全形态、记忆撤权和其他 P2-C/D/E/F 入口。

### 3.15 Connection/MCP 归属、认证意图与配置版本（2026-10-14，数据库验收未完成）

- V80 新增 connection 的组织/个人归属、credential 同组织同连接复合 FK、显式 NONE/BEARER 认证意图与 configuration_version。V80 已在隔离自动测试库应用；V81 安全协议 v5、V82 缓存版本与配置变更触发器已编写，尚未应用验证。没有修改已经应用的 V80。
- ConnectionAccess 收口组织成员活性；候选 SQL 同时过滤组织与 USER 属主。ORG 管理要求 CREDENTIAL_MANAGE，个人连接只允许本人管理；组织管理员不能枚举他人个人连接/凭证。创建时归属来自当前主体，不能由请求伪造 user/org。
- G6 配置凭证缺失/空白/解密失败不再匿名。BEARER 在凭证 FK 解绑后仍保持认证意图；NONE 携带凭证引用也拒绝。错误不回显存储/解密异常。目标配置改变而未重新提交密钥时解除旧引用，避免把旧密钥发送到新地址。
- MCP 生产运行时必须注入可见性查询端口，没有绕过查询的两参数构造器。握手、发现、工具与资源调用均在前后重新核实主体可见性和连接快照，轮换/禁用/撤权后迟到结果不返回。远端已发生的调用不能撤销，也不承诺外部副作用只发生一次。
- 发现缓存绑定 configuration_version；读取前后查当前权限，写入在网络调用结束后的短事务锁住 connection 行核实版本。V82 清空无法证明版本的旧派生缓存，并用数据库触发器覆盖 credential FK 自动解绑的版本递增。禁用再启用不能复活旧快照。
- 网络探测结束的 TESTED/CONNECTED/FAILED 状态更新也按开始时版本 CAS，旧失败不能覆盖新配置状态。
- 编译通过；当前专项 MCP 单元测试、凭证接缝、协议准入与全部架构门禁 **96 tests / 0 failures / 0 errors / 0 skipped / 10 suites**。其中新增 McpConnectionFencingTest 9 项覆盖撤权前拒绝网络、解析期间轮换、握手撤权关闭、工具迟到输出、发现轮换、管理发现与 Agent 使用分离、缓存读取撤权与个人连接不可见。
- 新增 ConnectionOwnershipIntegrationTest 共 10 项真实 PG 用例已编译，**未执行验收**（其中缓存/FK 版本与迟到状态 3 项为本批新增）。最初 G6 修复的 connection/mcp 集成回归曾通过；随后 V80 扩展回归 114 项出现 2 个管理更新失败，已修复 PostgreSQL nullable 参数 CASE 为显式 text cast，但数据库复跑因 Flyway 连接超时失败，不能把该修复写成数据库验证通过。
- Docker/WSL 引擎返回 500，PG 端口可连接但协议握手超时。用户明确选择“暂不重启，继续独立代码工作”；未停止/重启 Docker 或 WSL。V81/V82、实际旧 protocol4 jar 拒绝启动、10 项 PG 隔离测试、全量回归与浏览器验收保留待办。最新完整全量通过证据仍为 V79 的 1783 项，不能用于证明本批。
- MCP 凭证用途 AAD、轮换/舰队主密钥准入，以及 connection 前端归属/管理能力投影仍待完成；G6 的代码修复不等于整个 P2-E 退出。

### 3.16 MCP 凭证用途 AAD 与受限启动迁移（2026-10-14，独立验证通过）

- 新 ConnectionCredentialCrypto 使用 enc:mcp:v2:<SHA256-key-id>:<payload>，AES-256-GCM 的 AAD 固定绑定 connection_credentials/encrypted_secret/MCP_BEARER 与 org_id、connection_id、ref。密文复制到其他组织、连接或引用后认证失败；错误不带明文、原始密文或底层异常 cause。
- LocalAesSecretStore 的运行时 resolve 只读取新格式，不再解密旧的裸 base64 AES-GCM。主密钥仍需显式提供；无 key 时不会生成本地 key。与现有 CredentialKeyAdmission 共用同一 primary key 属性，OIDC 舰队指纹必须一致。新增 fallback 支持只供旧 key 解密，写入始终使用当前 primary。
- ConnectionCredentialEncryptionMigrator 是启动专用数据变换，每页最多 100 个引用、每行短事务；按 authorization scope -> connection -> credential 取锁。先认证已有范围密文（包括当前 primary），旧格式或旧 key 才重写；CAS 失败不覆盖他人更新，只有成功改写的选中凭证递增连接配置版本。缺 key、损坏密文或错误范围会使启动失败，保留原数据。
- 存量裸密文本来没有密码学范围证明；迁移依据经过归属回填/FK 核验的数据库事实建立新绑定，不能把历史来源说成已被密码学验证。迁移需要可信数据库与维护窗口停旧二进制；不允许旧 reader 混跑。凭证删除补取 connection 行锁后再删 credential，避免 FK 自动解绑与迁移形成父子反向锁。
- ArchUnit 增加旧格式读取只能由启动 migrator 调用的门禁。纯代码专项 **109 tests / 0 failures / 0 errors / 0 skipped / 12 suites**，含加密 7 项、迁移编排 5 项与新增门禁；MCP 会话/缓存/凭证接缝及协议准入一起通过。另有 compileTestJava 成功，新增 2 项真实 PG 持久化复制拒绝/旧格式迁移幂等用例已编译，但未执行。
- V83 安全协议升至 v6，防止 v5 reader 无范围读取新凭证。V81/V82/V83 均尚未应用；保存了改造前的真实 v5 jar：backend/build/p2-acceptance/runtime-v82-protocol5.jar，SHA256 A945A35E535B4D116DE22772A741DEDE8C6260CDF48ABB38F60D2E43E704629A。真实 JVM 对 v6 数据库的启动拒绝仍待验收。
- 轮换流程仍须维护窗口：停全部实例、备份 DB/旧 key；配置新 primary + 旧 fallback，协调更新现有舰队主密钥指纹登记，再由新实例完成迁移；核实所有行已由新 key 写入及双实例解密，再移除 fallback。该流程是操作设计，未执行，不算迁移/回退或多实例验收。
- Docker/WSL 未停止或重启；用户要求继续独立代码工作。数据库集成、全量回归、密钥分歧真实启动拒绝、轮换/回退与 OIDC 浏览器验收仍待完成。MCP 凭证代码与单测完成不能推导 P2-E/P2-F 已完成。

### 3.17 Proposal 接受的来源任务/Run 保护（2026-10-14，G7 尚未闭合）

- 核实 ProposalAcceptanceService 原先先锁 project，随后才通过 event/产物 UPDATE 锁来源 Run；attachApprovalProducedNode 既不检查来源 Run 终态，也不检查项目一致性。原测试甚至用 CREATED Run 当成“已终态来源”，不符合服务注释与真实 Advisor 流程。
- 新 ProposalOriginGuard：先核实提案读取可见性，再锁来源 runtime task（存在时必须终态；不存在不作为硬拒绝条件），随后取得授权范围锁，再锁来源 Run 并核实同项目且已终态，最后才锁 project/proposal/graph。来源 metadata 与锁内 proposal 的 project/run 必须一致；ghost run 的现有语义保持显式空来源，不能回写或派发该 ghost。
- attachApprovalProducedNode 改成带 project_id + completed/failed 的受约束更新，0 行拒绝并回滚整个接受事务。新增 ArchUnit 门禁，只允许 guarded ProposalAcceptanceService 调用这条人工审批效果写入路径。冲突通过统一 HTTP handler 返回 409 PROPOSAL_EXECUTION_CONFLICT。
- 同时收口 proposal 的父项目读取：不可见与不存在的提案决策统一 404 PROPOSAL_NOT_FOUND；不会因已知 id 的错误码差异探测他项目。身份/成员状态拒绝不被伪装为不存在。reject/accept 共用该读取收口，原缺失提案的 400 改为资源 404。
- 修改原“持久化来源”夹具为真正 COMPLETED；新增来源未终态、跨项目来源的真实 PG 用例，断言 proposal 仍 PROPOSED、节点数不变、无 continuation check。两项已编译但尚未执行；数据库恢复后必须跑全部 proposal/续跑套件，不能用单测证明事务原子性。
- 新独立测试 ProposalOriginGuardTest 5 项、AgentProposalVisibilityTest 3 项通过。合并本批 + 前述 MCP/凭证/协议 + 全部架构门禁的结果 **118 tests / 0 failures / 0 errors / 0 skipped / 14 suites**，BUILD SUCCESSFUL。首次可见性单测有 1 项 Mockito 对既有抛异常 stub 的重新 when 调用失败，改为 doThrow 后重跑通过，非产品缺陷或被忽略测试。
- **G7 仍开放**：来源 task/Run 保护只覆盖提案的来源执行，不等于整个项目的 execution scope 已与其他 Run 的 Worker 协调。当前 project 行锁与新来源锁也不能防止空 execution scope 的竞争。下一步须补共享项目事务互斥原语，接入公共调度的 scope 认领、TaskWriteGuard、单执行器 WorkspaceExecutionAuthorization 与人工接受；先 scope 再 authorization，不能把新的执行锁放在授权/业务行锁之后。
- 建议实现为按 scope_key 排序的 PostgreSQL transaction advisory mutex，try-lock 认领不等待，Worker 写入在 task 锁之后、授权锁之前核实，人工接受在来源 task 锁后取得项目 scope mutex，并检查存活 scope owner / 单执行器活跃 Run。两种模式都要覆盖“空范围同时认领”、长事务越过租约、撤权与迟到提交、不同项目不互相阻塞；不能只检查 hasLiveOwner 的一次快照，也不能拿 mutex 后反向锁其他 task。
- 仍待真实 PG 并发/撤权/回滚及浏览器验收。Docker/WSL 未重启，P2-C/D/E/F 的完整退出条件没有改变。

### 3.18 项目执行范围事务互斥与验收环境恢复（2026-10-14）

- RuntimeScopeMutex 用 PostgreSQL transaction advisory lock 覆盖尚无 runtime_scope_owners 行的空范围；key 从服务器生成的 scope_key 和固定 hash seed 派生，统一排序/去重。哈希碰撞只会保守串行化或拒绝，不扩大权限。没有实际事务时立即拒绝，不能用 autocommit 假装持锁。
- RuntimeTaskRepository.tryAcquireScopes 在 scope owner 插入前 try-lock；冲突立即回滚候选/容量/epoch 并扫描其他项目。TaskWriteGuard 在 task 锁之后、授权锁之前拿 scope mutex，并核实全部声明范围的 owner 绑定 task/epoch/lease/worker；缺失、错误或替代范围失败关闭。人工接受来源 task 之后、授权之前拿同一项目 mutex，并在锁内读取活跃范围 owner/单执行器活跃 Run；不反向锁其他 task。
- 单执行器 WorkspaceExecutionAuthorization 在上下文/模型工作之前、授权范围之前使用同一 mutex；等待上限 3 秒，保留更严格的调用方 lock_timeout，成功后恢复原设置。超时归一为 EXECUTION_SCOPE_UNAVAILABLE，RunWorker 做受保护的窄元数据失败，不留下尚未开始模型工作的 RUNNING run。
- 核实并修复一处真实调用边界：ExecutionFence.assertOwnership 的模型前探测原先在事务外调用 TaskWriteGuard。现在事务外探测使用真实短事务，业务事务内重复核验复用外层事务；任何锁都不因探测而跨模型调用持有。两项独立单测锁定该区别。
- 新增 RuntimeScopeMutexIntegrationTest 4 项真实 PG 独立连接测试：空范围竞争立即拒绝、不同项目独立、回滚释放、单执行器严格超时/成功恢复 timeout。MultiWorkerSchedulingIntegrationTest 新增真实调度器“手工持空范围 -> 同项目候选回滚 -> 扫描其他项目 -> 松锁后可认领”用例。TaskWriteGuardTest 增补真实 scope owner 正例夹具和缺 owner/错误范围拒绝两项。来源 task/Run 单测增加项目 busy 与查询顺序三项。
- 新锁要求舰队全部 Writer 参与，V84 安全协议升级至 v7。保存 protocol6 探针 jar（已含本轮 scope 原型，不冒称它是完全没有 mutex 的旧实现），SHA256 C2E7096EA9EC7B3ECB21067BB64402BA36C8AB8FF0844E113AE172BD2FF04CBB。真实独立 JVM、Flyway disabled，启动退出 1，明确 database=7,this instance=6。准入拒绝不隔离已经运行的旧进程，升级仍需维护窗口。
- 独立代码专项 90 项/0失败/0错误/0跳过/10 suites，通过编译与架构门禁；该专项不是全量回归。初次编译暴露新调度用例 family 参数应为 List 且缺协议类 import，修正后编译与专项通过。
- 用户随后明确授权重启 Docker。正常 docker desktop restart 在 stopping 超时；核实路径后停止 Docker Desktop 自有进程、只 terminate docker-desktop 发行版，再后台启动 E:/DevTools/DockerDesktop/Docker Desktop.exe。Docker server 29.8.0 恢复，原有服务容器恢复，专用 PG/Keycloak 容器手动启动；pg_isready accepting，Keycloak realm discovery 正常。没有调用 Windows Docker 服务、重置/删除容器或卷，也未操作 Ubuntu 发行版。
- V81/V82/V83/V84 已在隔离 spec_agent_test 成功应用（success=true），protocol=7。凭证旧格式剩余数查询为 0；这只是当前隔离数据核验，不代表生产迁移或回退已经验收。
- 集中真实 PG + 单元/架构回归 194 项中 192 通过、2 项 ConnectionControllerIntegrationTest 失败，真实问题是 enable 增加配置版本后使刚发现的目录缓存不可见。修正为：enable/disable 同一事务锁父 connection，返回锁内旧版本；只把该版本且内容/认证未变的缓存推进至新可见性版本，不重新执行网络发现；旧会话仍失效，配置/凭证变更仍丢弃缓存。管理接口套件复跑通过。
- 完整 testNonLive 首跑 1851 项 / 1 失败 / 33 跳过，唯一失败是同提案并发接受的输家得到 scope conflict 而不是原有已决定契约。人工接受改为至多 3 秒的项目 mutex 等待，等待后重读决定；调度认领继续 try-lock 不等待。修复后并发接受/提案执行/来源护栏/范围 PG 专项 23 项 0 失败，完整 testNonLive 复跑已通过：1851 tests / 0 failures / 0 errors / 33 skipped / 302 suites，BUILD SUCCESSFUL（6m35s）。显式 SPEC_AGENT_REBUILD_PYTHON_ACCEPTANCE=true，实际 Python 重建用例未跳过、执行通过；provider transport 为替身，没有付费外部调用。最终计数逐份汇总本次 JUnit XML，证据更新在 P2_CURRENT_BACKEND_REGRESSION.json。G7 项目协调实现和基础 PG 原语已补，完整撤权/双实例 OIDC/画布 SSE/回退矩阵仍未闭合，P2 总目标保持未完成。

### 3.19 SSE 逐事件授权、跨实例游标与订阅状态回收（2026-10-14）

- 磁盘事实修正：当前仅 GlobalAssistantController / GlobalAssistantStreamService 使用 SSE。画布 AgentRunController 返回经过 getRunForActor 父项目授权的 JSON DTO，DTO 再组装 RunProgress / continuation 事件信息；不能把不存在的画布 SSE 端点标成“已验收”或必须修复的现有漏点。画布轮询入口完整矩阵仍需盘点。
- 确认并修复初次 replay 与 catch-up 的批次中途撤权漏点：订阅前核实共享 session，查询前与每条发送前都重新核实用户/组织/私有会话属主及 session。中途退出、成员禁用或读取授权事实失败即关闭订阅；未发送的后续私有事件不再投递。失败不取消业务 Run，不承诺收回已经发送的字节。
- 真正将 SQL 读取限制为 sequence>cursor ORDER BY sequence LIMIT 200，初次回放也有界。已终态但超过一批的 backlog 不能提前关闭；由后续轮询补齐直到终态事件。旧两参数完整历史查询保留给现有非 SSE 调用，不影响其契约。
- 发现另一个真实 P1 顺序缺口：本机 publish 若直接发送高序号并推进游标，会漏掉其他实例已提交的中间事件。publish 现在仅作唤醒，发送统一从权威数据库游标读取，校验 event.runId；不依赖传入 payload，也不直接跳序号。
- 关闭订阅后原 live 空条目及 locks 对象一直保留，轮询成本随历史 Run 增长。改为单张 per-run SubscriptionBucket 表，操作引用数在 per-key compute 内维护，同 Run 仍串行；订阅空且无进行中/等待中操作后才释放 bucket。不能简单删除锁对象，否则等待旧锁与新锁的线程会分裂排序域。新增 100 个终态 Run 无残留状态、并发 publish/poll 不重复不漏号两项测试。
- 新 GlobalAssistantReplayAuthorizationTest 10 项，覆盖预先失效 session、replay 中途退出、catch-up 中途撤权、session/事件 DB 读取失败、跨实例漏号、终态大 backlog、错误 Run 发布、状态回收、并发发送。新 GlobalAssistantReplayRevocationIntegrationTest 4 项真实 PostgreSQL 测试，两个独立服务对象共享会话与成员事实，故障注入实际删除 session / 禁用成员，验证第二条事件被拒绝、其他实例也关闭；真实 206 条终态 backlog 完整送达。
- PG 用例的身份通过测试显式绑定，spring_session_attributes 的安全上下文为存在性标记，不经过 IdP 登录/认证反序列化。它证明共享事实和服务层逐事件授权，不冒充真实 OAuth 浏览器或两个 JVM 验收。默认 1 秒 poll 的慢客户端与真实 5 秒撤权时限仍需进程/浏览器测量。
- 首跑专项 92 项出现 8 项上下文失败：专用 PG 容器当时已正常退出，Flyway connection refused。Docker 引擎健康；依据已有授权仅启动专用 PG/Keycloak，不再重启共享引擎。就绪后重跑 92 项全过。初版扩大助手专项 265 项全过；bucket 清理与并发测试后最终扩大专项 **267 tests / 0 failures / 0 errors / 26 skipped / 32 suites**（整个 assistant + 全部架构 + 协议准入）。历史全量基线仍为 V84 的 1851 项，不能当成本批 V85 已跑全量。
- V85 协议升 v8，隔离数据库迁移 success=true / protocol=8。保留通过上一轮 V84 全量后的真实 protocol7 class 文件打包的旧 jar（在重编译 SSE 改动之前，bootJar 明确跳过 compileJava/processResources），SHA256 88EA6A1A757499C9A0393E0C5594E98D62ABFEA47C59318F29F0DB5C1E182103。Flyway disabled 实际 JVM 启动退出 1，database=8,this instance=7；维护窗口仍需停已运行旧实例。
- 仍需完成：完整 HTTP/bsk + 双 JVM OIDC SSE/登出/撤权/慢客户端矩阵；服务恢复主体、trace/模型会话/记忆依赖和其他 C/D/E/F 入口。本批不代表第二阶段完成。

### 3.20 Skill 组织配置、受约束父链与真实盘点修正（2026-10-14）

- Docker 引擎现已恢复为 29.8.0，专用 PostgreSQL/Keycloak 在运行，PG accepting connections。没有再次重启已经健康的共享引擎。
- 实证纠正：P2-A 将 V3 provider_credentials 当成待改造表，但 V5 已显式删除。Java 无引用，隔离 spec_agent_test 的 to_regclass 为 NULL。修正清单/待办，实际 provider/settings 密钥继续走已实现的组织 SQL/AAD 路径，不重新建废表。
- Skill 按已冻结的组织配置矩阵实施：成员可读，管理写入要求 ORG_MANAGE。SkillRepository 所有根列表/按 ID 查询在 SQL 中过滤组织；版本、包文件、资源清单、暂存文件沿受约束父记录过滤。搜索/发现共享此候选入口，排名前过滤。不是个人私有 Skill 模式；若以后增加 USER 所有权须单独定义矩阵及产品入口。
- V86 加 skills/staged_imports/activations 的 org_id，回填显式本地组织，核验后 NOT NULL/FK，**不设 legacy DEFAULT**。非本地项目已有激活与存量 Skill 归属冲突时迁移诚实失败，不能悄悄重归属。启用名称唯一性改为组织内；新外部 skillId 为随机稳定 ID，跨组织相同字节不因全局哈希 ID 冲突而泄露存在性。
- 不可变版本的 content_hash 去重从全库改为所属 Skill。安装查询必须传 skillRowId；相同包跨组织有独立安装、版本和启用状态。同组织同安装重复字节复用真实版本号，返回 version.versionNo()，不拿 max(version_no) 推测旧版本身份。
- V87 补版本的受约束 skill_id 父关系与 activation(version_id,skill_id) FK；currentVersion 必须属于本 Skill。删版本只 SET NULL 当前指针，不改 Skill 主键。V86 已在首轮测试应用，保留其校验和；补充约束走 V87 前向迁移，两者随同一 protocol v9 版本发布。
- 短配置事务按授权范围锁 → 组织配置互斥 → Skill/暂存/版本行执行，解决空目录并发安装、同名启用与重复安装的竞态；管理判定放在生命周期方法开始，并在仓储写入口再次核验。组织配置互斥保留更严格现有 lock_timeout，否则最多 3 秒，锁失败诚实拒绝，事务已失败时不继续恢复设置。不持新配置锁跨 git 下载；下载前检查权限，持久化时重新检查。真实生产 GA 路径采用 prepare/commit 分离；兼容调用方自己的事务边界仍须继续审计，不能泛称所有网络调用都已无锁。
- 激活审计核对项目 PROJECT_WRITE 与 Skill/version 父链；激活最近版本查询要求 PROJECT_READ。组织读权限仍不等于某次 Run 的已激活证明，skill.read_resource 的精确 Run 激活集合及 checkpoint/记忆依赖失效尚待补齐。
- OIDC 启动 seeder/backfill 不伪造本地管理员；本地文件镜像只允许 LOCAL、本地主体投影，OIDC 即使配置 local-mirror-enabled=true 也不写/删除。不会自动清除旧镜像或用户备份；宿主已有文件的维护清理不冒充数据库权限。builtin 现为按组织安装/启用；新 OIDC 组织自动只读 builtin 目录投影/引导尚未实现，不宣称全球 builtin 注册服务已就绪。
- 集中回归 **232 tests / 0 failures / 0 errors / 1 skipped / 31 suites**：整个 skill、assistant.tool、架构和协议准入；新增真实 PG 隔离用例 10 项及镜像边界单测 2 项。主体经测试显式绑定，**不是真实 OIDC HTTP/bsk 验收**。随后补互斥锁超时保护，最终全量 **1877 tests / 0 failures / 0 errors / 33 skipped / 305 suites**，BUILD SUCCESSFUL in 12m 5s；启用实际 Java→Python→Java rebuild Worker 验收（provider transport mocked），未重跑独立 Python pytest 239 项历史套件。隔离库 V86/V87 已成功应用、security protocol=9。
- 实际旧 protocol7 jar（前述 SHA256 88EA6A1A757499C9A0393E0C5594E98D62ABFEA47C59318F29F0DB5C1E182103），Flyway disabled 接入当前 protocol9 库，因 database=9,this instance=7 退出 1；protocol8 的拒绝为单元测试证据，没有伪造真实 v8 jar。仍需维护窗口停止已运行旧实例。证据见 [P2_SKILL_ORGANIZATION_PG_ACCEPTANCE.json](evidence/P2_SKILL_ORGANIZATION_PG_ACCEPTANCE.json)。
- 真实双 JVM/OIDC/撤权/Skill 工具投影、完整个人凭证与舰队轮换/回退矩阵仍未完成；第二阶段不标完成。

### 3.21 Run/续跑链 Skill 激活授权、能力重放与冻结输入来源（2026-10-14）

- 实证：SkillReadResourceHostTool 先前直接调用管理视图 readResource(versionId,path)，完全未核对该执行 Run 的激活集合；CapabilityRuntime 命中幂等键就返回已存结果，未核对项目/Run/能力/参数，也没有在 Skill 重放前重新授权。已修复，不把组织内可读当成任意运行可用。
- 中性 WorkspaceRunExecutionAccessPort 由 agent.runtime 实现：必须处于事务，先全局/任务所有权与执行 scope，再授权范围与持久化委托，随后 Run FOR SHARE；核实调用主体与 Run 委托一致、同项目、正在执行且未终态。单执行器仍允许没有 runtime_tasks 行的真实 Run；任务模式必须有真实拥有 token。不存在/缺失 Run、不同主体的同项目 MANAGER、终态/尚未执行状态不授权。
- GuardedCapabilityAdapter 在 invocation 行认领和读取缓存结果之前检查上下文，保持 task → execution scope → authorization → Run → 业务行锁序。Skill 激活/资源工具同时在实际服务入口核实，不允许工具绕到管理读方法；新增 ArchUnit 门禁锁定这条边界。
- V88 verified_run_id/actor_user_id/actor_source 与业务 Run 项目、workspace_run_authorizations 委托建立复合 FK，记录同一 Run/version 仅一次。旧激活记录保留，新增授权列全部 NULL；既有 run_id 不是调用主体证据，不做推断回填，也不赋予旧记录新权限。protocol 升 v10；只是当前隔离库，不对开发/生产库迁移。
- 资源使用允许当前 Run，或真实 CONTINUE_CYCLE parent 链中同项目、同 user/org/source 的已验证祖先激活；不认 root_run_id 自报标识，不继承其他用户/项目、不让不相关新用户轮次复用。递归 visited 防环、最多 64 层；更深未找到证明时须显式重新激活。版本仍不可变，不因续跑重新安装/重置预算；父链数据整体完整性及真正 Worker 续跑矩阵仍待验证。
- Skill root FOR SHARE 将 enabled 与选定版本验证保持在短事务中；停用后已存 activation instructions / resource result 也不能重放。管理 API 的组织成员读取仍独立于执行激活，符合冻结矩阵。
- 通用缓存重放先验证同 key 的 project/run/capability/arguments 身份，不匹配只返回 CAPABILITY_INVOCATION_IDENTITY_MISMATCH，不暴露原结果，也不执行新适配器。JSONB 比较避免 Map 顺序、Java Integer/Long 差异误拒绝。
- 进一步发现 snapshot 的 route 可见性会在未来用户轮次重新注入旧 Skill 正文。首次投影加入当前执行链来源过滤；冻结 payload 复用及首次提交在短事务中重新核验 Skill 来源。已经冻结的字节与 hash 保持不变；不允许时 FROZEN_INPUT_SOURCE_NOT_AUTHORIZED，拒绝继续而非改写/回建旧投影。hash/identity 校验额外核实 projectId。安全失败 reason-only 观察可保留，FAILED 标志不能夹带 instructions 绕过来源证明。
- 全套 skill/capability/agent.snapshot/architecture/协议准入/单执行器实际 Workspace 执行专项 **284 tests / 0 failures / 0 errors / 1 skipped / 41 suites**。新增 SkillRunActivationIntegrationTest 19 项真实 PG，含真实 RunService 续跑创建、成员撤权、停用、缓存、伪造委托/父链、冻结字节不变；续跑 child 状态由夹具置 executing，不冒充真实模型 Worker 执行。另有 guard 顺序 3、JSONB 重放身份 2、来源策略 6、架构 1 项。主体为测试绑定 OIDC actor，不是 OAuth/双 JVM/bsk 证据。随后全量与 GA 夹具修正见 §3.22；最终 **1916 tests / 0 failures / 0 errors / 33 skipped / 309 suites**。
- 明确剩余：模型 RPC 使用已经发出的 grant 时、其他 MCP/检索/记忆/trace/模型 session 来源还需统一依赖核验；不是全部 G9 已闭合。多身份真实 HTTP/Worker/浏览器、长链边界/并发撤权与慢客户端矩阵、原有 snapshot/private MCP 观察和成功审计都仍需逐路径审计。
- 本批先核实的独立缺陷（当时未修，后续修复见 §3.22）：IdentityService.localActor 首次 localReady=false 调用 IdentityRepository.upsertMembership，后者 ON CONFLICT 将 role 写 OWNER、status 写 ACTIVE，会恢复已有禁用/降级成员资格。下一步应以只插入缺失事实替代权限覆盖，增加新实例初始化后仍 MEMBERSHIP_DISABLED 的真实 PG 断言；不能以 User_DISABLED 已测替代 membership 禁用的证明。

### 3.22 本地身份解析不能恢复撤权事实（2026-10-14）

- 确认首次 localActor() 的 upsertMembership 会将已有 status=DISABLED 恢复 ACTIVE、将 MEMBER 恢复 OWNER。只改为 INSERT IF ABSENT 仍会重建被删除成员，不能满足撤权语义。
- V61 已一次性创建 local/legacy user、org、OWNER 与授权范围。本轮直接移除 localReady 和全部请求期事实创建/更新；localActor() 只在 LOCAL 模式解析固定显式身份。权限继续读取当前数据库事实；禁用、降级、删除不会因新实例解析身份被恢复，公共 health 不被额外身份事实 IO 阻塞。没有匿名或超级服务主体回退。
- V89 协议 v11 拒绝旧初始化器；隔离测试库已应用，尚未升级 OIDC 演示库及部署。
- LocalPrincipalInitializationIntegrationTest **7 项真实 PG**：新 IdentityService 实例保持禁用成员、已删除成员、降级角色、禁用用户与禁用组织事实；迁移后的有效本地主体正常工作；OIDC 模式不生成本地身份。所有 identity/authorization + architecture 专项 **125 tests / 0 failures / 0 errors / 0 skipped / 11 suites**。
- §3.21 首次全量 **1908 / 1 failure / 0 errors / 33 skipped / 308 suites**，唯一失败为旧 GA 夹具用同 key 但每次生成不同 title 却要求成功重放。正向测试改为复用同一参数，并新增参数变化必须明确失败、返回不含旧项目 ID、不创建第二个项目的负向断言；对应 GA/capability 专项已通过。不能放宽新增身份校验迁就旧夹具。
- 合并本地身份修复后的最终全量 **1916 tests / 0 failures / 0 errors / 33 skipped / 309 suites**，BUILD SUCCESSFUL in 2m 49s；启用实际 Java/Python rebuild Worker 验收（provider transport mocked）。未重跑独立 Python pytest 239 项历史套件。真实旧 protocol9 jar，SHA256 9A8606FBC153E8A5A19D115BA4F64347CA1263F5475528EAE7928B140001BA90，Flyway disabled 接入当前 protocol11 库，因 database=11,this instance=9 退出 1。protocol10 的拒绝为单元测试证据；真实双 JVM 新实例/撤权/浏览器矩阵仍未完成。

### 3.23 工作空间模型 RPC 票据固定冻结来源（2026-10-14）

- 实证：旧 WorkspaceRpcGrantService 只核对 Run、主体、类型、预算、租约，没有任何 Skill 来源依赖。即使 snapshot builder 已拒绝停用来源，之前签发的票据仍可把旧输入送到模型；完成回执也不查来源。
- V90 为票据固定 source_snapshot_id、source_payload_hash 和有界 source_skill_version_ids（最多 64、无 NULL 元素）。从宿主已冻结、同项目输入解析版本，不信 Python prompt 自报来源；RemotePythonDecisionEngine 传实际 snapshot。事务内在签发、admit 预算/RESERVED 前、finish 状态/事件前重新核实已固定版本仍属于当前授权执行链且 Skill enabled。没有复制 prompt、秘密或模型响应入票据。
- 模型调用期间停用来源时，完成回执拒绝、事件 writer 不执行；UNKNOWN 保留消费，不重置预算、不伪称模型没有运行。不能撤回已发送的模型请求或已完成的外部工作。
- Snapshot 字节/摘要改变（即便攻击夹具重新计算了合法 hash）、记录删除、版本删除都诚实拒绝；版本引用没有删除级联，不能删版本后让票据静默变成零依赖。旧四参数签发从 Run 的已挂载冻结输入解析来源；初始零来源票据不能扩展到后来挂载的私有 Skill 输入。实际源记录不存在不能自动回落。
- 旧票据全部 revoke；V90 登记安全协议 v12，隔离测试库迁移已成功；不修改已应用迁移，不迁移开发/生产库。
- 新 WorkspaceRpcSourceBindingIntegrationTest **9 项真实 PG**：固定来源正常准入/完成、停用/删除拒绝且不消费、迟到完成 UNKNOWN、重算摘要也拒绝、删投影不消除依赖、旧签发模式派生来源/拒绝来源扩张、无权签发无票据写入。Broker/RemotePythonDecisionEngine/架构/协议准入专项 **106 tests / 0 failures / 0 errors / 0 skipped / 9 suites**。模型在这些用例中未实际发出；它验证 grant/receipt 边界，不冒充 provider cancel、真实多 JVM 或浏览器验收。
- 新依赖为必需。编译首先定位到旧真实租约测试手工构造器少了 sourceAuthorization 参数，补入真实 bean；RemotePythonDecisionEngine mock 改为新签发重载。没有无来源验证的生产构造器回退。
- 最终全量 **1925 tests / 0 failures / 0 errors / 33 skipped / 310 suites**，BUILD SUCCESSFUL in 2m 48s；启用实际 Java/Python rebuild Worker 验收（provider transport mocked），独立 Python pytest 239 项仍为历史证据，未在本批重跑。真实旧 protocol11 jar，SHA256 DCDFCD7C916754070A369DB83D7D810367C1A58D19021F3083D884AD479D0C04，Flyway disabled 接入当前 protocol12 库，因 database=12,this instance=11 退出 1。仍需维护窗口停已运行旧实例。剩余仍包括非 Skill 的 MCP/检索/记忆/trace 来源与模型 session namespace、系统恢复显式服务身份、全面入口/前端与 OIDC 双 Worker 及舰队/回退矩阵。不是全部 G9 退出条件已满足。

### 3.24 MCP 历史结果来源与模型票据（2026-10-14，当前增量）

MCP 成功结果记录连接行 ID 与配置版本；历史结果的候选过滤、冻结输入重用、能力缓存重放及模型 RPC 的出票/准入/完成均核验来源。USER 连接不能由同组织管理员借用，配置失效后即使重新启用也不复活旧版本。无版本证据的旧结果失败关闭，不借当前配置补授权。校验只读取持久化事实，不访问 MCP 服务、不读取凭证。

V91 增加票据的 MCP 来源绑定，撤销旧票据并将安全协议升级为 13；已应用隔离自动测试库，不修改已应用迁移。连接来源读取持授权范围共享锁与连接行共享锁。Agent 通过 Capability 中性端口依赖 MCP：首次集中验证暴露直接实现依赖，已修正依赖而未放宽架构门禁。

真实 PG `McpRecordedSourceIntegrationTest` 13 项通过，包括所有者、同组织他人/跨组织拒绝、禁用/重新启用、旧来源拒绝、有效/失权缓存重放，以及新增的新快照过滤、冻结快照拒绝、已出票据撤权不扣预算、模型调用中撤权拒绝迟到提交并记 UNKNOWN。架构边界测试通过。当前全量 `testNonLive` 1938 项 / 0 失败 / 0 错误 / 33 跳过 / 311 suites，通过（2m57s）；旧 protocol12 实际 JVM 启动被拒绝（database=13，instance=12，退出码1）。证据见 `evidence/P2_MCP_RECORDED_SOURCE_PG_ACCEPTANCE.json`。尚无 protocol13 真实 OIDC 双 Worker/浏览器验收证据。完整 P2 仍未完成。

### 3.25 当前 protocol13 OIDC 双 API 与浏览器初验（2026-10-14）

保留原 V67 `spec_agent_p2_oidc`，复制专用 `spec_agent_p2_oidc_v91` 并成功应用 V68–V91，OIDC protocol13 准入通过。当前验收候选的两个独立 JVM 已启动：API A 8381、API B 8282，Vite 8281 代理 A。均为 API-only test profile，不代表 Worker 已验收。

browser-skill 会话 `hasg` / tab `702810789`：真实 Keycloak 登录成功，页面显示 Alice 的组织及项目；同浏览器访问 API B `/api/me` 返回 200、OIDC 主体一致。通过 A 的 UI 退出后，B `/api/me` 返回 401 `UNAUTHENTICATED`。这证明当前代码的浏览器登录与跨实例共享会话路径；未模拟旧 cookie 重放，不以此声称所有撤权链闭合。捕获已导出（仅停止 debug，浏览器会话未 stop）。证据见 `evidence/P2_OIDC_V91_BROWSER_PARTIAL_EVIDENCE.json`。

仍需多身份/项目角色、真实 Worker、撤权/迟到响应/SSE/慢客户端矩阵。自动审批曾拒绝读取旧 realm 配置与启动服务的组合命令；改为隔离已知测试配置及分步启动后成功，未读取企业凭证或修改原演示库。

### 3.26 OIDC 项目角色撤销读取与未知路径错误契约（2026-10-14）

在专用克隆库备份并移除 Alice 的一个项目成员行：既有 OIDC 会话仍为组织 OWNER，但 API A 的项目列表立即为空，API B 的 `/api/v1/projects/{id}` 返回 concealed `PROJECT_NOT_FOUND`；恢复原行后同一会话读回项目。原行已恢复并核验。此为直接 SQL 故障注入，只证明动态读取授权，不证明成员管理事务/outbox/Worker 取消收敛。证据见 `evidence/P2_OIDC_V91_PROJECT_REVOCATION_EVIDENCE.json`，browser-skill 会话保留。

首次直读误用了 `/api/projects`，出现真实 `NoResourceFoundException` 被通用 advice 包为500。已修源码为安全404 `RESOURCE_NOT_FOUND`，新增真实 MockMvc 回归验证路径片段及异常名不泄漏；API 错误契约与完整 architecture 集中 67 项 / 0 失败 / 0 错误通过。当前运行的 V91 验收 jar 尚不包含这个后续修复。历史全量1938仍有效，但不覆盖这次后续修复，回归 JSON 已明确区分；未宣称新全量通过。

### 3.27 前端项目权限状态、路线写入口与快捷键（2026-10-14，部分）

新增 `useProjectAccess`，按 project/user/org/session revision 读取真实 access endpoint。权限默认拒绝；项目/身份切换同步清空旧权限，取消过期响应，失败与畸形响应不放行。Workspace 的成员入口、路线起草/切换/恢复/归档和全局 undo/redo 快捷键已接入；只读定位/镜头/筛选保留，并显示查看权限说明。原有一次 onMounted 管理权限读取移除，避免迟到权限污染。

类型检查通过；集中42项通过；前端全量109文件通过、1文件跳过，875项通过、1项跳过。新增测试覆盖迟到响应、身份版本切换、错误响应、角色能力拆分和默认只读路线导航。旧编辑夹具明确提供能力，未以默认放行修测试。真实 browser-skill OIDC Viewer 页面确认提示及路线/成员入口收敛；临时降级的精确角色已恢复。

画布工具栏/节点/Inspector/规格生成等仍需全面接入；浏览器显示「想法」「撤销」仍可见，明确作为下一步。Node 卡片还显示模板中的块注释文本，需清理。未把部分前端改造标为全部完成，未声称远端撤权即时更新 UI。证据见 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。

### 3.28 画布工具栏、连接和知识节点权限（2026-10-14，部分）

GraphCanvas 显式传 PROJECT_WRITE 给工具栏、知识卡片与空项目占位。默认不显示新增想法/资源、undo/redo、空项目起草；缩放、适应视图、布局与只读阅读保留。VueFlow 禁止只读拖连线，onConnect 与工具栏事件再次检查权限。知识节点写动作过滤，双击编辑/保存/确认/继续生成都失败关闭；权限丢失关闭编辑器，恢复栏只保留定位，写入提示不对只读用户显示。清除知识节点 template 中被当正文显示的 JS 块注释。

类型检查通过；最终前端全量877项通过、1项跳过（109文件通过、1文件跳过，10.76s）。新增默认拒绝/撤权工具栏及知识节点编辑拒绝测试。真实 OIDC VIEWER 页面确认只显示缩放/适应视图、知识正文可读、源码注释消失；精确测试角色已恢复。证据更新 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。

Question 作答/恢复、Inspector/proposal 与 Spec 生成/重试写面仍需接入；当前实现不得写成画布全部只读入口已闭合。完整 P2 仍未完成。

### 3.29 问题作答、Inspector、提案与规格的写权限（2026-10-14）

GraphQuestionNode 的当前可作答状态同时要求 PROJECT_WRITE；失权后输入/提交、激活回答路线、写动作、失败重试消失。Inspector 的项目修改动作与提案确认/拒绝失败关闭，问 AI 保持只读能力。AgentProposalCard 的查看内容不依赖写权限，审批控件默认隐藏。SpecDock 生成/重试要求写能力；选快照、阅读、来源与导出仍开放。Workspace 的20个写处理函数再次检查权限，覆盖已打开对话框；只读全局恢复只刷新，不执行回答重提/恢复写入。

类型检查通过；最终前端全量880项通过、1项跳过（109文件通过、1文件跳过）。新增三个负面测试：当前问题失权后无回答输入、只读提案无审批、只读规格可以导出而无生成。真实 OIDC VIEWER 展开规格面板确认无生成按钮，临时角色已恢复。浏览器现有夹具没有真实问题/提案/规格快照，这三类的证据是组件测试，不冒充完整端到端。证据继续在 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。

远端撤权后仍打开页面的权限刷新、全入口矩阵、实际 Worker/SSE 与完整 P2 收尾尚未完成。

### 3.30 已打开页面的权限续验（2026-10-14）

useProjectAccess 增加完成后5秒续验、回到窗口/可见页面时刷新；并发焦点事件合并。请求5秒超时后 abort、清空权限，迟到响应不能复活；切换主体/项目与卸载清理请求、timer 和事件监听。轮询续验不周期性清空有效权限，避免按钮闪烁；焦点恢复主动清空后再确认。HTTP get 增加可选 AbortSignal，不改变其他请求默认语义。两个时间均是实验初值，不作 SLA；后端每次操作仍是授权边界。

类型检查通过；权限/HTTP集中23项通过；前端全量884项通过、1项跳过（109文件通过、1文件跳过）。真实 OIDC 浏览器保持同一个已打开页面，未 navigate/登录：MANAGER→VIEWER 后写/管理入口自动消失，恢复原 MANAGER 后自动重新出现。捕获 operations=0、8次请求、0 errors，已保存 `backend/build/p2-acceptance/frontend-live-role-refresh-capture.json`；测试角色恢复，浏览器会话保留。证据见 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。

当前证明的是写/管理入口收敛。完全失去 PROJECT_READ 后已加载的私有画布/快照内容与缓存清理仍需补齐；直接 SQL 角色注入不证明管理事务/outbox/Worker 的收敛。完整 P2 仍未完成。

### 3.31 画布读取权限丢失与私有状态清理（2026-10-14）

ProjectAccess 增加 PROJECT_READ 状态；Workspace 在读取权限失效时隐藏私有布局及全部对话框，显示不含项目标题的通用提示。先 beginProject 推进现有会话代次、清 canonical/规格/提案/查询/Run 状态，再清 projectId，防止旧请求重新写入。InputDraftStore 按完整 projectId 前缀清除该项目所有路线草稿并同步 sessionStorage，其它项目保留。权限恢复从服务器重新加载，不重用旧图。焦点核验期间标 loading，避免一次正常窗口切换直接删除仍有权的草稿。

类型检查通过；最终前端886项通过、1项跳过（109文件通过、1文件跳过）。新增真实 Pinia/组件测试锁定草稿清理边界及读取失权后的迟到图响应拒绝。首轮两项旧中心状态夹具没有模拟 access endpoint，已明确补编辑权限而未放行无权限路径。

真实 OIDC 同页面、无 navigate/login：移除单个项目成员行后标题/画布/规格/Inspector 消失；恢复精确原成员行后自动重新加载。捕获 `frontend-read-revocation-capture.json`（operations=0，23 requests，4个撤权期拒绝请求，不声称 error-free）。原角色已核验还原，browser-skill session hasg 保留。证据更新 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。

本轮覆盖工作台；其它页面缓存/登出换人隔离与完整入口矩阵仍需核实。直接 SQL 夹具不证明管理 outbox/真实 Worker 取消链。完整 P2 尚未完成。

### 3.32 回答草稿归属与用户级根页面身份（2026-10-14，部分）

确认回答草稿只有 project/node/route 键、没有身份归属：同标签页换人且两人可访问同项目时可能复用上一个人的未提交文本。新增 `spec-agent:input-drafts:owner:v1`，精确绑定 `[userId,orgId]`。同已验证身份刷新保留；换用户/组织或退出清空；无归属 legacy 草稿不能自动认领。App 同步绑定归属后才渲染认证路由，初始未知身份不抢先删同用户刷新草稿。RouterView/助手外壳 key 从仅 org 改为 user+org。成功 logout 在跳转前清空身份并递增 revision，触发清理。

类型检查通过；最终前端889项通过、1项跳过（110文件通过、1文件跳过）。新增 Pinia 持久化归属测试和真实 App/Router/Pinia 组合测试，验证初始 bootstrap 保留同用户草稿、同组织换人清空并重挂载、退出清空。不是两身份真实浏览器登录验收，未混称。

源码确认 GlobalAssistantStore 是单例，历史消息与 initialized 不能靠外壳重挂载失效；该 store 的在途响应/流/后继观测以及其它缓存仍需优先处理。当前仅草稿与根页面身份边界完成，完整登出换人隔离和 P2 未完成。证据继续在 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。

### 3.33 助手单例身份隔离与异步续体保护（2026-10-14）

App 将 GA store 绑定到 `[userId,orgId]`。身份变化/退出关闭流、取消重连等待及后继观测，完整 reset 单例（包含 initialized、历史消息/列表、草稿、pending steer/导航），保持单调身份 epoch；GA 本地 thread/run 指针只在持久化 owner 一致时保留，无归属旧指针清除。panel 的开关仅是 UI 偏好。

21个异步动作在 await、catch/finally 和实际 store 读写处校验 epoch。只检查 promise 完成还不够：promise 完成与 caller 续体之间可能换身份，因此续体用受限 store 视图；旧事件回调额外捕获 epoch，run ID 重用也不能接收旧消息。作用域视图复用真实根 store，避免后继观测不断嵌套代理。首次回归暴露该嵌套导致旧长观测测试超时，已修并保持原5秒测试时限；集中竞态/后继15项32ms通过。

类型检查通过；前端最终894项通过、1项跳过（111文件通过、1文件跳过，10.21s）。新增5项身份测试：消息/初始化/指针 reset、同 owner 记忆保留及 legacy 拒绝、迟到历史列表、promise 与续体之间换人、旧流回调复用 run ID 拒绝。证据更新 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。

未以此代替真实两身份浏览器或 Worker/SSE 验收。最新 `bsk session list` 返回空，历史 hasg 已不在线，恢复浏览器时需重新开会话，不复用旧 refs；本 agent 没有调用 session stop。其它单例/页面缓存仍需审计；provider 请求已发出不由前端 reset 撤销。完整 P2 继续未完成。

### 3.34 会话刷新、CSRF 与退出竞态（2026-10-14）

确认旧 refreshSession 的 /me 或 CSRF 响应可能在退出/新刷新后迟到，重新写回身份/组织/请求头。新增单调 session generation 与 AbortController，fetch 和 JSON decode 每个异步边界校验；只有全套 bootstrap 成功才同时发布 actor/org/CSRF。旧 catch/finally 不污染新错误或 loading。切组织使旧请求失效、清私有身份和 CSRF，再切新的请求 scope。

退出开始时先准备请求头，失效旧刷新并清客户端身份/CSRF/组织，触发草稿/GA 清理；以已准备请求头调用真实 logout。期间拒绝 refresh，失败显示稳定失败提示，不恢复旧身份。清客户端不冒充服务端退出成功。requestIdentity 在 storage 不可用时保留正确的内存 scope。

新增 accessSession 8项竞态测试；类型检查通过；前端最终902项通过、1项跳过（112文件通过、1文件跳过，10.49s）。覆盖 actor/CSRF 原子发布、旧 actor 迟到、退出后的旧 CSRF、组织切换、退出竞态/失败、刷新失败清 token、旧 finally 不清新 loading、storage 失败。证据更新 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。

本轮没有新的真实两用户浏览器验收；其它页面单例缓存、真实 Worker/SSE/撤权 outbox 矩阵及完整 P2 仍需继续。不得将前端客户端状态清理当成服务器 session 删除证据。

### 3.35 项目列表与六类设置缓存的跨身份隔离（2026-10-14）

ProjectStore 绑定身份，推进 epoch 并失效列表令牌；列表/创建/重命名/删除的结果、catch/finally 均核验原代次，旧创建不能加回旧项目或清新用户忙碌标志。App 统一绑定连接、Skill、OpenCode 模型设置、Provider 选择、OpenRouter、自定义 Provider 六个独立 store。身份变化 reset 内容、维持单调 epoch。

46个异步设置动作通过 shared/state/privateIdentity 的中性原语保护：工作 factory 在旧续体中不会发新调用，await 和实际 store 访问核验身份，旧 catch/finally 不改新状态。该原语的受限视图始终使用真实根 store，保持 Vue reactivity 并防止重复代理嵌套。功能族仍彼此独立，跨族生命周期编排放 App 层。

类型检查通过；集中30项通过；新增5项验证旧列表/创建拒绝、六类 reset、旧连接详情不再请求 tools、模型 promise/续体之间换人。最终前端全量907项通过、1项跳过（113文件通过、1文件跳过，27.20s）。证据更新 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。

当前证据是测试，不是实际两身份浏览器。相同用户/组织下的角色撤销与设置私有内容失效、持久化缓存完整盘点、实际 Worker/SSE 仍需继续。重置缓存不撤销已经发出的 provider 调用。完整 P2 未完成。

### 3.36 部署配置前缀修正与 Docker 环境恢复尝试（2026-10-14）

从磁盘确认生产 YAML 的 security/execution 在 spec 根下，但 Java/ConditionalOnProperty 读取 spec.agent.security / spec.agent.execution。已将两块归入 agent；主资源与测试资源的两个 application-test.yml 的 security 也同步修正。模式、issuer、调度开关与容量参数现在在公布的 canonical 路径可见。命令行覆盖的历史验收不能证明原 YAML 正确；具体环境属性也可能通过 Spring 高优先级绑定直接覆盖，不声称所有旧环境变量都失效。

新增4项真实 YAML loader + Binder 测试：生产 OIDC canonical 属性、多 Worker/slots/lease、测试 profile、两份 profile 资源一致性。连同已有协议门禁集中 7项 / 0 失败 / 0 错误通过。首次断言错误假设测试 profile 必然压过高优先级环境值，已按实际 Spring 规则修正；另发现测试资源副本遮住主资源，已修源码而未删缺失前缀断言。证据见 `evidence/P2_DEPLOYMENT_PROPERTY_BINDING.json`。历史全量1938不覆盖这次配置修复，JSON 已标 false；旧 V91 jar 尚不含修复。

本轮 Docker Server 先挂起，API/Keycloak 未正常响应。按用户已有授权执行 Desktop 自带 restart，因进程未退出超时失败；随后只停止 Docker 安装目录内进程、尝试 terminate docker-desktop（超时）、后台启动主程序。新的 Desktop/backend PID 已出现，但最终 Linux engine pipe 仍不可用。没有 terminate 其它 WSL 发行版，没有称环境恢复成功。数据库全量与真实 Worker/OIDC 验收待环境恢复；仍可继续独立代码工作，完整 P2 未完成。

### 3.37 同身份组织能力变化的设置缓存与后台续验（2026-10-14）

核实服务端 OrganizationConfigurationScope：readOrg 允许当前组织成员，writeOrg 要求 CREDENTIAL_MANAGE。因此不能把全部配置状态对 MEMBER 隐藏。Root 的设置作用域新增 orgRole 与排序后的 orgCapabilities；变化使六类设置缓存 epoch 失效，设置路由 key 同步变化以卸载旧本地表单（包括未提交的凭据输入）。相同能力集仅顺序改变不重置。工作台/助手仍使用其原用户/组织边界，不把组织管理角色当作项目正文授权。

SettingsLayout 完成后5秒后台续验和焦点续验，合并在途调用，卸载清 timer/监听；renewSession 不周期性隐藏有效页面，仍完整校验 bootstrap/CSRF/代次。会话核验5秒超时清私有身份并使旧代次失效，迟到结果不能恢复身份。两个时间是实验初值，不是承载或撤权 SLA。

类型检查通过；最终前端912项通过、1项跳过（114文件通过、1文件跳过，11.82s）。新增5项：同用户降级缓存/表单重置及能力顺序稳定、后台能力更新、挂起身份核验超时/迟到拒绝、周期续验卸载停止、焦点合并/晚完成不重建timer。证据更新 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。

当前是测试证据；Docker engine 仍不可用，实际浏览器组织降级、完整设置写控件能力投影、真实 Worker/SSE/回退等尚待。完整 P2 继续未完成。

### 3.38 模型配置写入口与凭据草稿权限投影（2026-10-14）

新增 useOrganizationCapability 只读能力投影，要求已认证/已选组织并使用服务器公布的能力，不以 role 字符串推导权限、不为 LOCAL 增加客户端超级回退。OpenCode/OpenRouter/自定义 Provider 的保存、probe、重测、激活、换 key、创建/编辑入口接 CREDENTIAL_MANAGE；UI 隐藏且处理函数再次检查。状态读取和模型目录刷新保留；Member 的模型选择不可修改配置。权限丢失清未提交 key，恢复不复活旧 key；普通等价身份续验不会覆盖编辑。

旧编辑组件夹具显式绑定已认证 credential manager，未以默认放行迁就测试。类型检查通过；前端全量916项通过、1项跳过（115文件通过、1文件跳过，11.72s）。新增4项只读/撤权与能力投影测试；额外加强“同能力刷新保留编辑、恢复能力不复活 key”断言后集中9项通过。只更新测试断言的这次集中检查未再次重复全量，源码已由全量覆盖。

证据更新 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。当前覆盖模型页，不把搜索/检索/Skill/连接其它管理页记成全部完成；真实浏览器矩阵与 Worker/SSE、数据库全量尚待 Docker 环境恢复。完整 P2 继续未完成。

### 3.39 搜索/检索管理入口与组件请求范围（2026-10-14）

SearchSettingsView 和 RetrievalSettingsView 的管理表单、保存/测试/清凭据、索引重建/重试/启用/放弃接 CREDENTIAL_MANAGE，action 入口再次判定。普通成员仍可读配置状态、索引进度并刷新。失权清未提交 key、确认状态。新增 useSettingsRequestFence 绑定组件创建时的已认证 user/org/role/capability 范围与生命周期，每次真实 service 调用前后核验；旧页面异步完成不能继续以新的身份请求接口。

捕获 rebuild 的 corpus/profile 和 retry/activate/discard 的 job ID 为稳定本地值，避免在异步工厂中重新取变化中的 Ref；未使用非空断言掩盖类型检查。旧编辑夹具显式提供 credential manager 身份。

类型检查通过；源码最终全量919项通过、1项跳过（115文件通过、1文件跳过，12.15s）。新增 Member 搜索只读、Member READY 索引无启用/放弃、身份变化后的旧 probe 无后续读取三个测试包含于全量；随后补“页面卸载后的 probe 无后续读取”，集中8项通过（未对仅新增测试再重复全量，报告计数仍明确919）。证据更新 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。

当前仍无新的真实浏览器/PG验收。Skill、连接管理入口与其它 P2 收尾继续待办；API已发出的副作用不由UI fence撤销。完整 P2 未完成。

### 3.40 Skill 管理入口的组织能力投影（2026-10-14）

SkillsListView、SkillDetailView、SkillsList、SkillImportDialog/Review 接 ORG_MANAGE。添加/导入/探测/暂存/安装/拒绝/启停/删除入口隐藏或禁用，处理函数与事件再次检查。普通成员保留列表选择、状态、详情资源和暂存 manifest 读取（按钮文案从“查看并安装”变为“查看”）。角色丢权关闭导入/删除确认，清已选文件、Git URL/ref/subpath，审批按钮不再呈现。

旧编辑组件夹具与设置导航测试明确模拟组织管理身份；首次全量暴露导航测试没有身份却要求添加按钮，已补 fixture，未改默认拒绝。类型检查通过；最终前端923项通过、1项跳过（115文件通过、1文件跳过，12.36s）。新增3项负面验证：Member 列表可读可选择而无 lifecycle/delete、暂存原文可读无审批、详情资源可读无启停/删除调用。证据更新 `evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json`。

当前是组件测试证据。连接管理与真实浏览器/Worker/SSE 仍需继续；最终 Docker Server 独立检查仍无 Linux engine pipe。完整 P2 未完成。

## 4. 当前入口闭合表

“已覆盖”表示此落点有实现与测试，不意味着整个批次已满足退出条件。

| 入口族 | 当前落点 | 状态 |
|---|---|---|
| 项目枚举/GA 搜索、会话列表、快照读取与导出（G1/G2/G3） | SQL 候选组织+项目角色、会话属主、snapshot父链 | 已闭合 |
| Graph/Route/Lineage/RequirementState 读、GraphCommand/UndoRedo 写 | ProjectService/AuthorizationGuard | 已覆盖；全部入口矩阵尚待核实 |
| 画布 Run 读取、proposal 操作 | 项目授权、来源 task/Run 终态同项目、共享 scope 事务互斥 | 实现 + 基础 PG 并发已验证；完整撤权/双实例矩阵待补（§3.17–3.18） |
| GA Run/私有会话/SSE | requireThread、subscriber actor+shared session | 已覆盖；多身份浏览器矩阵待验收 |
| GA claim/model/tool/checkpoint | 持久化委托+任务模式 fence | 已覆盖；其他内部 RPC 未闭合（G4/G13）；G5 GA claim 不再仅凭共享 secret 放行 |
| 撤权 | outbox+task取消+独立GA Run元数据收敛 | 部分；画布独立 Run 已补（§3.7）；记忆/工具缓存还未完成 |
| 模型配置/凭证 | org SQL、AAD v2、主密钥准入 | 已覆盖此族；G6/G11 的其他配置与 MCP 部分仍开放 |
| 健康公开内容（G12） | OIDC `/api/health` 只返回 status；production actuator details=never | 已覆盖；部署级验收待完整执行 |
| SSE 跨实例（G8） | GA 权威 DB 游标、逐事件权限/session 检查、独立有界发送与状态回收 | 双 JVM 游标、真实 TCP 反压、30s 重连已通过；全部撤权/退出与负载形态仍待。画布为授权 JSON 轮询（§3.19） |
| 记忆/trace/模型会话/MCP缓存/检索（G9） | MCP/Skill 与若干 frozen 来源、已发模型票据依赖已补（§3.21–3.24） | 全来源依赖与入口审计未收口，不能称整个 G9 已闭合 |
| Skill 安装/版本/暂存/发现/资源 | org 候选 SQL + 父链 FK + ORG_MANAGE 写入 | 组织隔离、Run/续跑链激活、重放/frozen 来源、LOCAL 撤权保持已测；其他 checkpoint 依赖和真实 OIDC 矩阵待补（§3.20–3.21） |
| 前端身份与成员管理 | App、AccountBar、组织/项目成员页、统一请求身份 | 已实现基础；角色操作入口门控、邀请、全部错误文案未完成 |

**多组织投产验收尚未闭合。** 剩余入口见 §8，不能用本次全量回归替代完整隔离验收。

## 5. 测试与验收证据

| 测试 | 结果 | 说明 |
|---|---|---|
| `ArchitectureTests`（含 2 条新规则） | ✅ 通过 | access 中立性 + Spring Security 只允许在 `access.security`；模块 slice 无环 |
| `HttpBoundaryGateFixtureTest` | ✅ 通过 | HTTP 边界门禁夹具 |
| `AccessAuthorizationIntegrationTest`（**20 项**） | ✅ 全部通过 | 身份/组织/成员/模式准入/安全链/范围锁 |
| `ResourceIsolationIntegrationTest`（**16 项**，P2-C 新增） | ✅ 全部通过 | 跨组织不可见、组织管理员不读正文、项目角色能力矩阵、创建者成为 MANAGER、组织级默认画像、禁用即失权、**同组织无项目角色在列表与检索里完全不可见**、**legacy 组织对 OIDC 用户不是豁免**、**无主体回落只在 LOCAL 存在**、会话私有、列表按属主过滤 |
| `AccessBoundaryTest`（含新增 LOCAL 拒绝非回环） | ✅ 通过 | |
| `LoopbackBindingGuardStartupTest`（含新增 LOCAL 拒绝用例） | ✅ 通过 | 真实 Tomcat 启动路径 |
| `HealthControllerTest`（3 项，已调整切片语义） | ✅ 通过 | 见 §5.2 |
| `RuntimeTaskActorContextIntegrationTest`（**5 项**，P2-D-1 新增） | ✅ 全部通过 | 任务主体三元组落库/读回、遗留默认收敛到显式本地主体、未知来源失败关闭、重建主体驱动会话私有性、系统主体无 user |
| `GraphWorkspaceQueryServiceTest`（14 项，已随入口迁移更新 mock） | ✅ 全部通过 | 读模型行为契约不变，仅跟随 `getProjectForActor` |
| 迁移 V59 / V60 / V61 / **V62** | ✅ 全部 `success=t` | 回填核验 `projects_without_org=0`、`threads_without_owner=0`；V62 `actor_source` 核验块通过 |
| 后端全量（非 live） | ✅ **1698 项 / 0 失败 / 0 错误 / 33 跳过 / 282 suites** | 见 §5.2；= 上一基线 1693 + 本批 5 |

### 5.1 `AccessAuthorizationIntegrationTest` 覆盖（真实 PostgreSQL）

- **LOCAL 是显式主体**：本地 actor 携带真实 `organization_members`(OWNER) 行；`/api/me` 返回 `mode=LOCAL`、`authenticated=true`、`orgRole=OWNER`、三项组织能力；`/api/me` 响应体**不含** `internal-secret` / `credentialRef` / `master-key`。
- **角色 → 能力矩阵（冻结决定回归锁）**：OWNER 有 `ORG_OWNER`；ADMIN 有 `ORG_MANAGE`/`CREDENTIAL_MANAGE` 但**无** `ORG_OWNER`；MEMBER 无组织管理能力；**EDITOR 有 `PROJECT_WRITE` 但无 `PROJECT_MANAGE`（项目重命名）**；VIEWER 无 `PROJECT_WRITE`、无 `CREDENTIAL_USE`。
- **失败原因可区分**：`USER_DISABLED`（且用户级撤权同时停用其全部成员资格；重新启用后恢复）／`NOT_ORG_MEMBER`／`MEMBERSHIP_DISABLED`／`ORG_DISABLED`／`ORG_SELECTION_REQUIRED`（403，非隐藏 404）／`RESOURCE_NOT_VISIBLE`（`concealed=true`）。
- **服务与系统主体不提升**：`service()` → `SERVICE_NOT_AUTHORIZED`；`system(orgA)` 访问 `orgB` → `SYSTEM_SCOPE_EXCEEDED`；系统主体携带组织角色能力 → 同样 `SYSTEM_SCOPE_EXCEEDED`。
- **最后一个 OWNER 保护**：降级与退出都被拒（`LAST_OWNER_PROTECTED`）；存在第二个 OWNER 后降级成功且事实确实更新。
- **授权范围锁**：键按 `scope_key` 升序取锁；`ensure` 幂等；版本从 0 递增到 1；成员变更同时递增 org 与 user 两个范围版本。
- **审计**：拒绝写入 `security_audit_events` 且原因码稳定。
- **模式准入**：数据库登记为 `OIDC` 而本实例为 `LOCAL` → 拒绝启动（测试结束恢复登记行）。

### 5.2 全量回归：两次真实问题与最终基线

`SPRING_PROFILES_ACTIVE=test SPEC_AGENT_MODEL_GATEWAY=fake SPEC_AGENT_MODEL_INFERENCE=fake gradlew testNonLive`。

引入 Security 后全量回归暴露了**两个非代码逻辑的真实问题**，两者都不是权限设计缺陷，但都不是能靠"多跑一次就好了"处理的：

**① 测试 JVM 堆不足（第一次运行）**
大量测试类以 `IllegalStateException at DefaultCacheAwareContextLoaderDelegate`（上下文加载失败）报错，看起来像大范围回归。栈回溯的根因是 **`java.lang.OutOfMemoryError`（`ConfigurationClassParser` 处）**：Security 与 Session 使每个 `@SpringBootTest` 上下文更重，在全量套件的上下文缓存下耗尽 Gradle 默认测试堆。
处置：`backend/build.gradle.kts` 的 `tasks.withType<Test>` 增加 `maxHeapSize = "3g"`（只影响测试 JVM，不改动产品运行参数）。该值应被视为**待测量参数**，不是承载承诺。

**② `@WebMvcTest` 切片被 Boot 默认安全链拦截（第二次运行）**
`HealthControllerTest` 3 项全部失败。根因：切片测试只加载 Web 层，不加载 `com.specagent.access.security` 下的真实链，因此 Spring Security 的**默认**链生效，`/api/health` 变成 401。
处置：该切片显式 `@AutoConfigureMockMvc(addFilters = false)`，并加注释说明"它验证的是健康 JSON 语义，不是安全边界"；**安全边界改由加载真实链的集成测试覆盖**（见 §5.1 中 `healthEndpointIsNotChallengedByTheRealSecurityChain` 与 `internalServiceChainDoesNotCreateSessions`）。
第三/四次运行另暴露一处**我自己写错的断言**：把健康端点断言为 200，而它在 worker 门控未开放时会合法返回 503。已改为断言"未被 401/403 质询"——把安全边界与业务语义分开，避免用一个断言同时押两件事。

**最终基线（本批重跑）**：`SPRING_PROFILES_ACTIVE=test SPEC_AGENT_MODEL_GATEWAY=fake SPEC_AGENT_MODEL_INFERENCE=fake gradlew test` = **1698 项 / 0 失败 / 0 错误 / 33 跳过 / 282 suites**。

> 口径说明：`test` 任务与 `testNonLive` 使用**同一组排除**（`EvalLive*` 与
> `PythonBrainCrossLanguageIntegrationTest`）。本批实测使用 `test`，因为它是
> Java 插件完全装配的任务；自定义的 `testNonLive` 任务未显式设置
> `testClassesDirs`，本次执行未产生新结果集（记录为待核实的既有仓内问题，
> 不影响本轮结论）。上一基线 1693 项 + 本批 5 项 = 1698。

P2-C 落地过程中暴露的五次真实问题，全部修复并留档：

1. 未知项目从 `PROJECT_NOT_FOUND` 变成通用 `RESOURCE_NOT_FOUND`（5 个既有测试失败）→ 在服务层把 concealed 拒绝映射回原有契约，并明确"跨组织与不存在在状态码与错误码上都必须一致"。
2. `projectDecision` 只认线程绑定主体，直接调用时退化为无主体 → 补 `projectDecisionFor` / `requireProjectFor` / `requireThreadOwnershipFor` 显式主体重载（P2-D 的 Worker/RPC 正需要）。
3. **legacy/local 组织被当成授权豁免**（按要求自查发现）→ 判据从 `orgId == 本地组织` 改为 `actor.source() == LOCAL`；OIDC 用户不再能借 legacy 组织取得所有者权限。
4. **同组织无项目角色者仍能看到项目**（按要求自查发现）→ 候选集在 SQL 内同时按组织与项目角色过滤，HTTP 列表与 GA 搜索共用同一查询。
5. **74 个 GA 测试失败**：给 `requireThread` 加私有性收口点后，进程内夹具与 LOCAL 单机的后台组件（SSE 补发、轮次续转、孤儿恢复）都因没有主体被拒。这不是"测试要迁就实现"，而是**后台组件缺主体**这一真实缺口被暴露。按两层解析规则处置（显式 API 拒绝 null；线程绑定入口 LOCAL 回落受限本地主体、OIDC 拒绝），缺口如实记入 §3.2 边界第 2 条，**没有用匿名回退掩盖**。

> **本记录不引用历史测试数量**（P1 的 1654 项是第一阶段记录，不构成本阶段授权正确性的证据）。
> 本阶段的授权正确性证据是 §5.1 的原因码级断言（例如"能区分 NOT_ORG_MEMBER 与 MEMBERSHIP_DISABLED"），而不是"跑了多少用例"。

### 5.3 最新验证与端到端边界

- 最新全量 `SPEC_AGENT_REBUILD_PYTHON_ACCEPTANCE=true gradlew.bat testNonLive`（V79）：**1783 tests / 0 failures / 0 errors / 33 skipped / 292 suites**，直接汇总 XML；含 ArchUnit。证据 [P2_CURRENT_BACKEND_REGRESSION.json](evidence/P2_CURRENT_BACKEND_REGRESSION.json)。此数字包括 skipped，不写成全部实际执行；覆盖 V79 父子租约、项目删除授权锁序与实际 Java/Python RPC 资格验收，以及此前 embedding/检索隔离。历史 V69 全量与 120 项针对性证据保留在 §3.7，不冒充最新结果。
- 前端 `npm run typecheck` 通过；Vitest **108 files passed / 1 skipped，870 tests passed / 1 skipped**。新增页面仍须浏览器验证，已有单元套件绿色不是新 UI 完整验收证明。
- 模型/搜索/画像隔离真实 PG 测试 7 项：跨组织空配置不回落、同组织成员不得修改/禁用失权、AAD 抗搬运、多个组织同 singleton 的迁移不串写且幂等。
- GA 动态授权测试含：单执行器无任务恢复主体、任务模式必须有 task、禁用/撤权、私有 Run 不可由同组织 ADMIN 读取或取消、SSE 订阅身份、共享会话删除后停止补发、任务/独立Run撤权、重复 outbox 幂等与有效委托不误取消。
- 真实 Keycloak 授权码登录已执行；Alice 经前端建组织/项目，真实画布新想法 undo/redo，成员页面显示真实库角色；同浏览器访问第二 JVM 的 /api/me 得到同一用户与组织。最新两个 JVM 均使用含 V67 的构建；测试档为 API-only，不冒充 OIDC 双 Worker 验收。
- 桌面/390px 手机成员页面已截图并人工查看，修复顶栏覆盖正文；静态 Impeccable detector 对改动界面返回 []。浏览器全部使用 bsk，截图仅存本地 build/p2-acceptance。
- 未完成：多身份操作矩阵、OIDC 真执行 Worker/RPC/工具链、在真实 SSE 中撤权与退出的完整跨实例证据、全部连接组合、迁移/回退演练、企业 IdP/TLS。当前部分验收环境不是生产部署。

### 5.4 当前结论

已实现、已测试、端到端已验收必须分别判断。P2-C/D/E 尚有明确代码缺口，P2-F 仅部分执行；不得标记第二阶段完成。

---

## 6. 迁移与回退操作说明

### 6.1 迁移顺序（冻结）

```text
停入队与 drain → 备份数据库与可解密密钥 → 停全部旧进程
→ 加列回填（V59/V60/V61/V62 已按此实现） → 0 行核验
→ NOT NULL + FK 收紧（待写入路径改造完成） → 部署兼容 Java/Python → 验证隔离 → 开放访问
```

V61 的核验块是硬门禁：任何一类残留行都会 `RAISE EXCEPTION` 使迁移失败，不允许"带着空归属继续启动"。V62 同样有核验块（`actor_source` 无法重建时 `RAISE EXCEPTION`）。

### 6.2 本轮的迁移与回退姿态

- V59/V60/V61/V62 全部为**加列/加表**语义，未删除任何列或数据；`profiles` 的唯一约束由全局改为 `(org_id, name)`（不丢数据）。
- **回退**：`security_mode` 登记行改回 `LOCAL` + 停全部实例 + 启动旧舰队即可回到改造前行为；V59–V62 新增的列与表被旧代码忽略，**不需要**数据回退。若必须回退 `profiles` 约束，需先确认不存在跨组织同名 profile。
- **V62 回退注意**：`actor_source`/`actor_org_id` 带数据库 DEFAULT（指向显式本地主体），旧二进制忽略这些列即可；无需反向迁移。若回退到不含 V62 的版本集，删除本批新增列即可（不丢业务数据）。
- 回退**不允许**在多人数据上直接交给旧匿名 API（设计 §11）：需要前向修复或经审核的备份恢复。
- **授权关闭开关不是生产回退手段**；`mode-admission-enabled=false` 仅供测试。

### 6.3 旧执行器隔离（沿用 P1，不受本批影响）

`runtime_execution_mode` 与 admission advisory lock 未改动。旧 *API-only* 实例不认识本层的模式登记，仍由发布流程的维护窗口覆盖。

---

## 7. 配置清单

### 7.1 新增（`application.yml`）

| 键 | 默认 | 说明 |
|---|---|---|
| `spec.agent.security.mode` | `LOCAL` | `LOCAL` / `OIDC`。必须显式；未知值抛异常而非回退 |
| `spec.agent.security.issuer-uri` | 空 | OIDC 必填，缺失拒绝启动 |
| `spec.agent.security.mode-admission-enabled` | `true` | 数据库模式准入校验；**生产不得关闭** |
| `spec.agent.security.absolute-timeout` | `8h` | 绝对登录期限（不因活跃延长） |
| `spec.agent.security.local-org-name` / `local-user-name` | `Local Workspace` / `Local User` | 本地主体显示名 |
| `spring.session.jdbc.initialize-schema` | `never` | 会话表由 Flyway 建立 |
| `spring.session.timeout` | `30m` | 空闲会话超时 |

### 7.2 OIDC 部署还需（本轮未提供，属部署配置）

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          enterprise:            # 供应商标识
            provider: enterprise
            client-id: <部署提供>
            client-secret: <部署提供>
            scope: openid,profile,email
            authorization-grant-type: authorization_code
        provider:
          enterprise:
            issuer-uri: <目标 IdP issuer>
```

`issuer-uri` 同时用于 `spec.agent.security.issuer-uri`（模式准入校验）与 Spring 的 provider 配置；两者必须一致。

### 7.3 启动前置（迁移）

`security_mode` 登记行必须与 `spec.agent.security.mode` 一致。切换到 OIDC 的**正确顺序**：

1. 停全部实例（含 API-only）。
2. `UPDATE security_mode SET mode='OIDC', issuer_uri='<issuer>' WHERE id=1;`
3. 启动新舰队（带 OIDC 注册配置）。

直接改配置而不改登记行会在启动时被拒绝——这是刻意的，防止舰队内模式分歧。

### 7.4 新增依赖

`spring-boot-starter-security`、`spring-boot-starter-oauth2-client`、`spring-session-jdbc`（后端）；`spring-security-test` 尚未引入（当前测试不驱动过滤链认证）。

---

## 8. 精确续接点与环境

### 8.1 仍需完成

2026-10-15 更新：慢连接、杀进程、数据库中断、协调密钥轮换/回退与备份恢复已补证据（§3.47）。归属约束收紧已由 V92/V93 + 新库全量回归闭合；组织级在途撤权和两身份浏览器/API 隔离亦已补（§3.48）。下列历史条目中这些部分不应再当作完全未做；剩余重点是完整五身份/全入口/来源依赖矩阵。未改写 V61/V66 等既有迁移。

IdentityService.localActor 恢复禁用/降级/删除成员资格已修并通过 PG（§3.22）；模型 RPC 的 Skill 已发出票据依赖已补（§3.23）；private MCP 缓存/冻结输入/模型票据来源已补（§3.24）；继续其他 source dependencies 与实际 OIDC 双 Worker 矩阵。

1. 全面核实 C 剩余入口：画布恢复/Run/proposal 全部父链与能力，proposal accept 的来源 task/Run fence 已补（§3.17），项目 scope 协调和基础 PG 原语已补（§3.18），完整撤权/双实例矩阵待补；画布现有授权 JSON 轮询及 GA 真实 HTTP/双 JVM/bsk/慢客户端 SSE 矩阵（§3.19）。项目标题探测已改为只检查可见项目（§3.6）；同组织不可见项目允许同名。
2. 画布独立 Run 委托、执行主体与 checkpoint 授权已实施（§3.7）；workspace model RPC 的票据与主体绑定已实现（§3.8）；retrieval/embedding RPC、系统恢复、轮次续转等无请求路径全都要以事实重建主体。不得添加匿名/本地超级主体回退。
3. 检索搜索 grant 主体与动态权限、Java/Python 候选前授权已实施（§3.10）；私有/公共索引 grant 服务身份、组织活性与数据库同域约束已补（§3.11）；head/rebuild 租约与服务控制路径已补（§3.14）；跨实例验收、单执行器 lease 与费用预算仍待补。记忆/checkpoint 依赖项与撤权失效、隔离 trace/模型会话/MCP 发现缓存，工具能力按实际权限投影。capability_invocations RUNNING 超时仍需审计。
4. P2-E 其余：connection/credential 的组织/个人归属、MCP 配置版本与凭证复制拒绝/旧格式迁移已通过真实 PG 验证（§3.15–3.18）；provider_credentials 已在 V5 删除（§3.20）；Skill 组织安装/管理/版本父链已实施，Run/续跑链激活及 Skill frozen 输入已补（§3.21），builtin 引导与真实 OIDC 工具矩阵待补；embedding 设置/批准/密钥/项目重建已实施（§3.12），多实例 Worker/公共帮助全形态仍未验收；search 与 profiles 查询已收口（§3.6）、MCP 缺凭证不得匿名回退；MCP 凭证用途 AAD、版本失效与旧格式迁移已通过真实 PG，舰队密钥轮换/回退仍待演练（§3.16–3.18），其余族仍需扩展。模型配置新 org 无配置应诚实未配置，不能复制 legacy 密钥。
5. 迁移归属 NOT NULL/FK 收紧前核实全部写入；V66 的 legacy 默认仅兼容过渡，最终须移除，避免遗漏 org 的新代码静默归属 legacy。V67 的部分列 SET NULL 要求 PostgreSQL 15+，生产版本应核实。enc:v2 与新增委托读者不兼容旧二进制，安全协议版本已由 V71 升级至 v2，真实旧 jar 启动拒绝已验证（§3.9）；V75/V76 增加的 embedding 读者不兼容前提已由 V77 协议 v3 + 实际旧 v2 JVM 拒绝覆盖（§3.13）；仍需完整维护窗口与迁移回退演练，不能把启动拒绝当成已运行旧进程的隔离。
6. 前端按项目角色收敛所有写操作入口、邀请/成员流程与统一产品错误文案；成员页面不得当作全部前端完成。当前已实现按已注册 user ID 添加，不是邀请链接系统。
7. P2-F 多身份双 API + 真 Worker + Keycloak + bsk、数据库中断/撤权/迟到输出/跨实例 SSE/登出、迁移回退与 secret/key 分歧启动拒绝矩阵。企业 IdP/TLS 属部署验收，不阻塞隔离环境工作。

### 8.2 当前隔离环境（仅测试）

2026-10-14：用户后来明确授权重启 Docker；引擎、专用 PG 与 Keycloak 已恢复并核验。V81–V84 已应用于 spec_agent_test，V85–V91 也已成功迁移、protocol=13；原 OIDC 演示库仍为 V67；新增克隆库 `spec_agent_p2_oidc_v91` 已升级并完成 §3.25 的有限当前验收。

- `spec-agent-p2-auth`：PostgreSQL 127.0.0.1:5546；数据库 `spec_agent_test`（LOCAL 自动测试）与 `spec_agent_p2_oidc`（OIDC 浏览器验收）。
- `spec-agent-p2-keycloak`：127.0.0.1:8184/realms/spec-agent-p2；隔离测试用户，无企业账号/生产 secret。
- 历史 API-only JVM 验收：8281/8282，使用同一 `backend/build/p2-acceptance/runtime-20261013-1405.jar`，Flyway 至 V67；worker disabled（test profile），多 Worker 模式准入 PER_TASK。
- 历史 Vite 验收：8511，代理 API 8281；forward headers 保持授权码回调落在前端域。构建/realm 与测试参数在 build/p2-acceptance，仅本地测试。当前进程/端口与浏览器会话需重新核实，不能仅凭旧记录声称在线或验收新代码。
- bsk 会话不可在验收未完成前主动 stop。恢复时先 session list，再 observe；新会话不复用旧 refs。

上一批 V87 代码全量通过后构建的历史验收 jar：`backend/build/p2-acceptance/runtime-v87-accepted-protocol9.jar`，SHA256 `9A8606FBC153E8A5A19D115BA4F64347CA1263F5475528EAE7928B140001BA90`，bootJar 成功，未部署/未启动为 OIDC 服务。该 v9 候选仅可作为旧二进制；当前真实进程/浏览器验收须重建 protocol11 或后续实测候选，不能继续用 V67 演示 jar 证明当前实现。

历史 V89 全量 1916 项通过后构建的 v11 候选：`backend/build/p2-acceptance/runtime-v89-accepted-protocol11.jar`，SHA256 `DCDFCD7C916754070A369DB83D7D810367C1A58D19021F3083D884AD479D0C04`，bootJar 成功；尚未作为 OIDC API/Worker 启动、未部署。该 v11 候选已作为实际旧二进制拒绝证据；不能用来验收当前 v12 来源协议。

当前 V90 全量 1925 项通过后的 P2-F 候选：`backend/build/p2-acceptance/runtime-v90-accepted-protocol12.jar`，SHA256 `0193334FD00D5B3B70B65886F8481D87B8F1BE565ACE1B90CBDEC9F4DACE855F`，bootJar 成功；未部署、未作为真实 OIDC 服务启动验收。

最新 V91 候选：`backend/build/p2-acceptance/runtime-v91-accepted-protocol13.jar`，SHA256 `D813BC4A91C3877438583AE2BDB82032C6E932CB58D9733DED76012B5BC94BFB`；bootJar 成功，未部署/未启动为 OIDC 服务。V91 已在隔离自动测试库成功迁移。

### 8.3 迁移/回退补充

V63 GA Run 委托并移除 runtime task actor 默认；V64 撤权 outbox；V65 主密钥指纹；V66 模型 org 与复合 singleton 主键；V67 同组织激活 FK；V68 search 组织归属；V69 画布 Run 委托；V70 workspace 模型 RPC 票据与调用账本；V71 安全协议 v2；V72 检索 grant 主体与旧票据撤销；V73/V74 索引服务身份与复合约束；V75/V76 embedding 组织配置/重建同域约束；V77 安全协议 v3；V78 重建租约/父子 grant；V79 安全协议 v4、V80 connection 归属（自动测试库已应用）；V81 安全协议 v5、V82 MCP 缓存配置版本、V83 安全协议 v6、V84 安全协议 v7、V85 安全协议 v8、V86 Skill 组织归属及协议 v9、V87 Skill 版本父链、V88 已验证 Run 激活与协议 v10、V89 本地身份安全协议 v11、V90 workspace RPC 来源及协议 v12 均已在隔离自动测试库成功应用。OIDC 演示库仍为 V67。当前仅对隔离库应用，未对开发/生产库应用。

enc:v2 不能被旧二进制无范围读取。回退不是直接重启旧 jar：维护窗口停全部实例，备份数据库/主密钥，保持新 reader 或实施经验证的显式导出/降级，再切回；当前该回退演练**尚未执行**。不得移除 fallback key 或删除归属列后继续开放服务。

未提交、未推送、未覆盖现有凭证、未切换开发库、未部署生产。完整 P2 目标继续保持未完成。


### 环境补记：Docker 再次恢复（2026-10-14）

用户明确授权重启后，Docker Desktop Server 29.8.0 与 docker-desktop WSL2 已恢复。启动失败根因为本地运行时 socket 重命名报 Windows 1920；相关临时目录已保留为带时间戳备份，未删除容器/卷。专用 PostgreSQL accepting connections，spec_agent_test 最后成功迁移仍为 V91。Keycloak discovery 返回 200，issuer=http://127.0.0.1:8184/realms/spec-agent-p2。

Keycloak 原绑定整个验收目录，四份报告 JSON 被误当 realm 导入；本轮临时改名后启动，健康后已全部恢复原路径。后续需改为专用 realm 导入目录，避免再次重启失败。此条只证明环境恢复，不代表本轮新增后端/前端代码或完整 P2 已验收。证据：evidence/P2_DOCKER_RECOVERY_20261014.json。


### 3.41 连接管理的个人/组织能力投影（2026-10-14）

后端 ConnectionResponse/ConnectionDetailResponse 补只读 ownerKind；不新增对外 ownerUserId、orgId 或 credentialRef。前端根据实际归属判断：当前已认证、选择组织的成员可创建 USER 个人连接；组织共享连接管理要求 CREDENTIAL_MANAGE。新建默认显式发送 USER，只有具备能力才展示 ORG 选项。列表、详情、生命周期、编辑/创建对话框与操作处理函数同步校验；旧响应缺字段时管理失败关闭。能力丢失清除未提交凭证与删除确认，读取能力目录仍保留。

新增前端 7 项验证成员共享只读、个人管理、旧响应失败关闭、共享/个人创建请求以及降权清除凭证。前端连接专项 27 全过，typecheck 通过，完整前端 930 passed / 1 skipped（115 passed files / 1 skipped）。机械 UI detector 无结果。后端真实 PostgreSQL 连接专项 76 tests / 0 failures / 0 errors / 0 skipped / 10 suites，含新增归属输出与内部敏感字段不泄露断言。当前后端全量正在重新验证，浏览器连接矩阵未验收；完整 P2 未完成。证据见 evidence/P2_FRONTEND_PROJECT_ACCESS_PARTIAL.json。


§3.41 收尾证据补记：browser-skill 会话 xhze 使用当前 jar（SHA256 7CD0DB50CD012F56E097ED1CC9BC517813623C07DDBE333EC7D10C395043BDA2）、V91/protocol13 OIDC 克隆库与本地 Keycloak，完成 Alice OWNER 登录、默认 USER 新建、详情管理与确认删除；测试连接最终 count=0。首次实际页面暴露 CREATED/disabled 仍请求能力目录并显示内部 agent-visible 错误，已修为仅 enabled 且 TESTED/CONNECTED 时读取目录，新增两项回归。修后浏览器详情无该错误。会话保留，不把 OWNER 冒充 MEMBER/动态撤权浏览器矩阵。

最终 typecheck 通过；前端连接 29 项，与设置身份隔离一起 32 项；完整前端 **932 passed / 1 skipped**（115 passed files / 1 skipped）。后端完整 testNonLive 使用专用 PG 重新执行并逐 XML 核验：**1944 tests / 0 failures / 0 errors / 34 skipped / 312 suites**；覆盖当前 safe404、配置绑定和连接归属输出，不再引用旧 1938 数字作当前证明。额外 Python rebuild 进程验收开关本轮未启用，跳过数如实记录。成员管理页的异步身份/权限变化处理仍待加固，完整 P2 与真实双 Worker/SSE/撤权/回退矩阵仍未完成。


### 3.42 成员管理的请求身份与动态权限隔离（2026-10-14）

OrganizationMembersView 与 ProjectMembersView 新增 useMemberRequestScope：每次请求捕获用户、组织、组织能力、资源与本轮权限代次；切换资源/主体、失权或销毁后旧响应、错误、finally、后继读请求不能再写当前页面。组织角色变更与 OWNER 提升/移除在处理函数复核能力，移除改为可随失权关闭的 UiConfirmDialog。项目 URL 改为响应 projectId，读取及操作 UI 都由已确认 PROJECT_MANAGE 控制。权限确认暂态关闭操作并清除 pending/busy，保留有效用户草稿；确认失权才清除私有列表、草稿与确认。

9 项新增测试验证旧组织列表、旧 grant 后继读、撤权清理、OWNER 选择降权、项目 viewer、projectId 变更、项目撤权、权限暂态保留草稿与卸载后的旧完成。与 useProjectAccess 专项共 17 项通过；typecheck 通过，完整前端 **941 passed / 1 skipped**（116 passed files / 1 skipped）。本轮仅前端变化，后端全量仍为上一轮当前后端 1944 / 0 failures / 0 errors / 34 skipped / 312 suites，未冒充本轮重跑。

browser-skill 会话 vfzi 核验组织 OWNER 列表/移除确认并取消；在专用 OIDC V91/protocol13 克隆库使用真实页面将 Alice 项目 MANAGER 降为 VIEWER，页面收敛为无管理权限，成员列表与添加表单消失。验收脚本初次 CLI 解码失败，后以 UTF-8 读取并确认数据库 VIEWER，再按隔离库精确原行恢复 MANAGER；最终数据库与浏览器均重新确认 MANAGER 与列表/表单恢复。恢复为测试环境数据库救援，不是产品 regrant 验收；没有操作开发/生产库。浏览器会话保留。证据：evidence/P2_MEMBER_FRONTEND_ISOLATION_PARTIAL.json。

完整 P2 仍未完成：组织成员真实撤权矩阵、双 API/双 Worker 动态授权及撤权 outbox/SSE/故障/回退的进程级收敛证据仍待；下步直接推进这些验收，已有后端/前端全量证据不替代它们。


### 3.43 OIDC 双 JVM Worker 的实际主体执行验收（2026-10-14）

新增 tools/p2_oidc_dual_worker_acceptance.py。只从既有隔离 OIDC V91/protocol13 库导出到新的 spec_agent_p2_workers_20261014a，不删除/重建容器、不覆盖数据库；拒绝复制有活跃任务的源库，排除 Spring Session 数据并检查克隆后 session=0；后续复跑拒绝覆盖同名库并预检端口。使用当前验收 jar SHA256 7CD0DB50CD012F56E097ED1CC9BC517813623C07DDBE333EC7D10C395043BDA2 启动两个独立 JVM（A PID52852/8383、B PID26616/8384），明确 brain/worker enabled=true，均日志核验 OIDC protocol13/PER_TASK 与 WorkerPollingGate opened。真实 Python brain PID51980/8103 在 broker 模式就绪；这不等于模型协议已执行。

browser-skill 会话 oebj 从 8282→A 代理进行真实 Keycloak OIDC 登录，未导出 cookie/CSRF/凭证。为同时提交并发任务，在浏览器中通过 bsk evaluate 调用应用自身 API client（可复现表达式 tools/p2_oidc_workspace_batch.js），创建 8 个不同项目/节点并同时入队查询；此为已认证 API 批量派发，不冒充完整 UI 流程。实际数据库核验：8/8 runtime_tasks=SUCCEEDED、业务 agent_runs=completed、attempt_count=1；actor_source 全部 OIDC，user/org 全部匹配 Alice 与验收组织；attempt 有 2 个不同 worker_id，跨 Worker 区间重叠 4 对。每进程 WorkerIdentity 为固定随机 UUID，当前仅两个 JVM 在新库运行 Worker。观察器严格同时检查任务、业务终态、主体、attempt 与两 Worker 重叠，并检查两个 API/brain HTTP 健康，实际 observe 通过。

首次观察器把画布业务状态误按 GA 的大写 COMPLETED 比较，严格门禁拒绝；读取 AgentRunStatus.code() 确认为小写 completed 后修正验收器，实际数据不作修改、不放宽终态要求。Python语法编译通过。没有后端/前端生产代码变更，不重复旧全量冒充新证据。当前两个 JVM/Python 由同一验收启动器持续管理，工具执行句柄17498；Vite8282句柄54166，浏览器保持开放。恢复时必须检查实际 PID/健康，不按清单推断进程活性。

只闭合实际 OIDC Worker 主体重建与画布成功执行的双进程证据。推理为 Java deterministic fake；Python 模型调用、GA、共享会话/SSE、撤权/迟到写入、杀进程、数据库中断、迁移/密钥回退仍未验收。本轮无峰值容量实测，15s租约与200ms轮询是验收参数，不是承载承诺。源库未改，新库保留所有测试事实。证据：evidence/P2_OIDC_DUAL_WORKER_PARTIAL.json。完整 P2 未完成。


### 3.44 真实 Python GA 失败链与跨 API OIDC/SSE 游标补发（2026-10-14）

继续使用 §3.43 的两个已核实存活 JVM/Python 与新隔离库。组织 OpenCode 配置原为空；仅在该组织行不存在时按 ModelCredentialCrypto 的 enc:v2 与 org/NUL/table/NUL/row/NUL/api_key AAD 插入测试占位凭证与 test-model。首个 fixture 因 masked_suffix varchar4 校验失败，未落行；该轮无配置的四个业务失败保留。修正测试夹具后新建四个不同 GA 会话，实际 Python broker 执行请求到达，四条 MODEL 调用均记录 UNKNOWN、业务 Run FAILED/GA_EXECUTION_FAILED；runtime_tasks SUCCEEDED 只是适配器正常返回且业务已终态化，绝不写成 GA COMPLETED。没有重放 UNKNOWN 调用、重置预算或覆盖历史 Run。

核实 GaNativeModelBroker 直接使用 OpenCodeZenTransport 原生流，通用 FakeModelInferenceGateway 不覆盖它。即使 generic inference=fake，也不能声称 GA 原生链为 fake/offline。这次没有成功模型结果；后续须增加显式 test-only 原生流替身，不得以生产回退绕过授权或把 fake 当真实提供商资格。

新增 Vite 8286→API B8384（句柄33564），browser-skill 同一 oebj 会话无需二次登录，即读取 A 执行的 Run8125130d-82c2-4a0a-9a36-fbc085eff7f2。A worker663243b3 的归属由其日志初始协调失败 Runb70e5e30 与数据库对应 worker核实；每进程 WorkerIdentity 固定。B 返回 SSE 从0开始的 sequence1/2/3/4，末帧RUN_FAILED；从Last-Event-ID=2重连只收到3/4；无客户端错误，与数据库四行事件精确一致。该证据证明共享真实 OIDC Session、跨 API 已终态事件补发与游标，不冒充在途 catch-up poller 或 COMPLETED。

未修改生产代码、未部署、未触碰开发/生产库。新增事实均保留于独立验收库；尚缺原生 GA 成功、动态撤权、慢客户端、真实任务接管与回退完整矩阵。证据 evidence/P2_OIDC_GA_SSE_PARTIAL.json。下一步先落实显式原生流测试替身，再在当前授权链上验收 GA 成功/在途 SSE/撤权。完整 P2 未完成。


### 3.45 原生流替身收尾、GA 成功链、租约心跳缺陷修复与跨实例在途 SSE（2026-10-14）

**替身收尾。** `FakeGaNativeTransport` 仅在 test profile 且显式 `spec.agent.testing.ga-native-transport=fake` 时装配为 Primary，只替换原生 GA 模型流；授权、预算、账本仍走真实 broker，普通设置/模型调用仍委托原 HTTP transport。首跑 71 项中 3 项失败（测试请求误用 PLAN，契约只接受 AGENT/SUMMARY），改为 AGENT 后：`FakeGaNativeTransportTest` 7 + `architecture.*` 64 = **71 tests / 0 fail / 0 err / 0 skip**，bootJar 成功。候选 jar `runtime-nativefake-protocol13.jar`，SHA256 `3C75B697B58CFFCC791116D070464F197D208F8AD0A2861B4F13928EED23A37B`，未覆盖历史 jar。

**隔离环境重启。** 从 `E:\DevTools\DockerDesktop\Docker Desktop.exe` 后台启动（daemon ~5s 就绪），只启动 `spec-agent-p2-auth`(5546) 与 `spec-agent-p2-keycloak`(8184)，不触碰开发库 `spec-agent-postgres`。Keycloak 重启失败根因：容器把整个 `backend/build/p2-acceptance` 挂成 import 目录，非 realm 的报告 JSON 被当作 realm 导入（`Unrecognized field "session"`）。已把 7 份非 realm JSON 移入 `p2-acceptance/_reports` 子目录（DirImportProvider 只扫顶层），仅保留 `realm.json`；Keycloak ~8s 就绪，issuer 正确。此改动仍未覆盖历史 jar/数据库。

**GA 成功验收（真实 OIDC + 双 JVM Worker + 真实 Python broker + 显式 test-only 原生流替身）。** 使用 `runtime-nativefake-protocol13.jar`、仅从既有 V91/protocol13 库克隆的 `spec_agent_p2_workers_20261014a`、本地 Keycloak。4 个 GA run 全部业务 **COMPLETED**；账本 MODEL 4 条 **SUCCEEDED**（call_sequence 296–299）；每 run `model_calls=1`（预算未重置）；§3.44 的 4 条 MODEL/UNKNOWN 原样保留、未重放、预算未重置；`runtime_tasks` SUCCEEDED、actor_source=OIDC 全部匹配 Alice 与验收组织、两个不同 worker（每次 attempt_count=1）；每 run 7 条事件（RUN_STARTED → ANSWER_* → ASSISTANT_COMPLETED → RUN_COMPLETED）；Python broker 收到 4 次 `POST /internal/v1/global-assistant/executions` 全部 200。助手正文为替身确定性输出，`model_id=test-model`。证据 `evidence/P2_OIDC_GA_NATIVE_SUCCESS.json`（内含"UNKNOWN 现为 5"的补记，不覆盖历史结论）。

**真实缺陷（本轮验收发现）。** `RuntimeTaskScheduler.heartbeat()` 在**全部生产代码中没有任何调用者**（死代码），`heartbeat-seconds` 配置只被读写、从不被消费，也不存在任何续租循环；同时所有 `@Scheduled` 共享 Spring Boot 默认**单线程**调度器。后果：任何执行时长超过 `lease-seconds` 的任务都会被过期恢复判为 `LEASE_EXPIRED` → `EXECUTION_OUTCOME_UNKNOWN`。这是产品缺陷，不是验收参数问题：默认 60s 租约下超过 60s 的真实推理同样会失败，文档承诺的 180s GA 执行栅栏无法兑现；也**不能**用调大 `lease-seconds` 绕过。本轮实际复现：run `1e53f260…`（fixture 延迟 6s）耗时 15.1s 被中断，MODEL 调用留 UNKNOWN（call_sequence 300）、attempt 结果 `LEASE_EXPIRED`、业务 `FAILED/RUN_INTERRUPTED`；证据 `evidence/P2_LEASE_EXPIRY_HONEST_FAILURE.json`（UNKNOWN 调用不重放、预算不重置）。

**修复（对齐 design §6.4）。** 新增 `ExecutionHeartbeatRegistry`（本进程在途 token 登记）与 `LeaseHeartbeat`（**独立单线程 `ScheduledExecutorService`**，明确不使用模型调用线程池、也不使用共享 `@Scheduled`；周期 `min(heartbeatSeconds, leaseSeconds/2)`）。`WorkspaceMultiWorkerDriver` 与 `GaMultiWorkerDriver` 在 `adapter.execute` 前后 `try/finally` 登记/注销 claim。续租失败（条件不满足或数据库不可达）即判定失去所有权、摘除登记并停止续租；数据库条件续租仍是唯一权威。另设 `spring.task.scheduling.pool.size=4`，避免长执行把撤销/跨实例 SSE 补发等调度一并饿死。无需迁移（`lease_until`/`heartbeat_at` 列已存在；纯行为修复，不改数据格式与安全边界）。

**回归。** 新增 2 项真实 PG 回归（`heartbeatRenewsLeaseWhileExecutionBlocksBeyondLease`：慢适配器执行超过租约仍完成；`heartbeatMarksOwnershipLostWhenLeaseAlreadyExpired`：租约已过期即判定失去所有权）；`testNonLive --tests com.specagent.runtime.execution.* --tests com.specagent.agent.runtime.* --tests com.specagent.assistant.runtime.* --tests com.specagent.architecture.* bootJar` = **300 tests / 0 fail / 0 err / 0 skip / 50 suites**。判据：聚焦回归必须同时设置 `SPRING_DATASOURCE_URL` 与 `SPRING_DATASOURCE_PASSWORD`（只设 URL 会用开发库默认密码，报到 28P01）。候选 jar `runtime-heartbeatfix-wid-protocol13.jar`，SHA256 `6DE299B06BF7BFA7D793059450B38B474C1872DE7E5C01975699F6C1A8B940F4`（另含 worker 日志的 `runtime-heartbeatfix-protocol13.jar`，SHA256 `7B7AE70A7B7563F7A318E53139F6EF33CFEFDC2151AA79F679A8384A0FE36D16`）；均不覆盖历史 jar。

**Worker 身份可核验。** `WorkerPollingGate` 在既有 "Worker polling opened" 日志追加本进程 worker UUID（design §9 明确要求日志能映射 `runtime_tasks.worker_id`；OIDC `/api/health` 脱敏行为不变）。本轮 **A=eea6300e-4ad1-4acb-a14a-a260201a6e8c（8383）**、**B=bd0dc231-cc2c-4d43-bad0-52a4c54c3b57（8384）**。

**跨实例在途 SSE 验收（闭合 §3.44 明确未证的部分）。** 用 `runtime-heartbeatfix-wid-protocol13.jar`、同一独立库、同一原生流替身（6s 延迟）。run `b75defb2…` 由 **A** 执行、SSE 连接在 **B**（8286→8384）：B 在途收到 seq1–3（订阅时已提交，回放）、seq4@600ms、seq5@6696ms、ASSISTANT_COMPLETED@7720ms、RUN_COMPLETED@7727ms，序号连续、无缺号无重号；业务 COMPLETED、账本 MODEL SUCCEEDED、`model_calls=1`、任务 SUCCEEDED、actor OIDC。从游标 2 重连只收到 3–7。另有一次同实例（executor=B、订阅=B）结果不构成跨实例证据，已丢弃、不冒充。证据 `evidence/P2_OIDC_CROSS_INSTANCE_SSE_HEARTBEAT.json`。

**尚未验收（完整 P2 未完成）**：在途撤权/outbox 收敛、迟到结果拒绝、慢客户端、杀进程接管、数据库故障与回退、密钥轮换与迁移回退演练。桥接新代码的完整后端全量回归尚未重跑（本轮只跑了受改动影响的聚焦 300 项）；不以此冒充全量。


### 3.46 项目级在途撤权与"迟到写入"缺陷（2026-10-14）

**组织级自撤权不可达（正确不变量）。** `DELETE /api/v1/organizations/{orgId}/members/{userId}` 对唯一的 OWNER 被产品正确拒绝：`The last active OWNER cannot leave the organization`。单成员组织无法自撤权，这是正确的 fail-closed 行为，不是缺陷；真正的组织级在途撤权验收需要第二 OWNER/第二身份会话（本轮不具备）。

**项目级在途撤权（产品路径，收敛成立）。** 用仅 `engine=fake` 生效的确定性延迟指令 `[[delay-decision-ms:30000]]` 把 ANSWER_TIP run 拖成 ~30s 在途（`DeterministicEngineFaultPlan.delayDecisionIfDirected`，已在生产代码接线）。run 启动后 **5.1s**（仍处 DECIDING 在途）以 `DELETE /api/v1/projects/{p}/members/{alice}` 触发撤权（`ProjectMemberManagementService.remove` → 撤销 outbox）。结果：`runtime_task=CANCELLED`（attempt outcome `CANCELLED`，reason "cancelled by the control plane"）、业务 run `failed`（completed_at 23:43:00）、outbox 全部 `processed_at` 有值（`pending=0`）。任务取消与 outbox 收敛成立。

**新发现的真实缺陷 `LATE_WRITE_AFTER_TERMINAL`（尚未修复）。** 该 run 已于 23:43:00.071 写终态 `RUN_FAILED`（reason `TASK_CANCELLED`），但**同一 run 在 23:43:25.70（+25.6s）继续追加非终态事件** `PROPOSAL_CREATED` / `PROCESS_NOTE` / `EXECUTING` / `PROCESS_NOTE`（sequence 8–11）——此时任务租约已撤销、run 已终态。根因：该延迟是普通 `Thread.sleep`，不观察任务取消；当它返回时，仍在途的 answer 周期把 `REQUEST_USER_INPUT` 提案持久化并追加事件。生产等价形态是"取消后返回的长模型调用"。这正是"迟到结果拒绝"要求禁止的行为：事件序号虽连续，但**终态之后仍写入非终态事件**，SSE 契约（客户端在终态关闭）与落库事实不一致。**未修复**；待审计业务周期的事件追加路径是否受 `TaskWriteGuard`/`ExecutionFence` 对"已取消（非仅被接管）"任务的约束。证据 `evidence/P2_OIDC_INFLIGHT_REVOCATION_LATE_WRITE.json`（含组织级不变量与项目级撤权收敛记录）。

**仍在途/未验收**：慢客户端、杀进程接管、数据库故障与回退、密钥轮换与迁移回退演练；桥接新代码的完整后端全量回归尚未重跑。

### 3.47 交接后续接：全量回归、SSE 反压与实际故障演练（2026-10-15）

后续归属约束收紧与第二身份验收见 §3.48；本节末尾的归属迁移待办已被后续批次覆盖。

本节覆盖 §3.45–3.46 的旧待办状态。上一轮四项修复及撤权复验以 `PHASE2_GAP_FIX_HANDOVER_20261015.md` 与 `evidence/P2_P2GAPS_FIX_DUAL_WORKER_LONG_RUN.json` 为历史证据；本轮没有重做 P2-B，也没有修改旧 Flyway 迁移。

**完整回归阻塞的真实原因。** 测试 profile 的 datasource.password 是固定值 `spec_agent_dev`；只传 `SPEC_AGENT_DB_PASSWORD` 不会覆盖它，不能据此认定 Git Bash 丢失了环境。改用 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` 明确覆盖，单套件通过。首轮完整回归 1962 项只有 1 项结构门禁失败，并非此前归类的“全是环境问题”：`GaHostRpcController.modelStream` 新增预检直接调用 `GaModelContract.readRequest`，违反 HTTP 不依赖模型内部类型规则。解析与判断移回 Broker，Controller 只处理预检状态；未放宽门禁。

**追加修复一：失权取消使用正确租约且不占用心跳。** `GaOwnershipLostCancelPropagator` 原实现仍发送 runtime task 的 claim lease，而 Python 身份是 prepare 阶段生成的独立 GA binding lease；同时同步网络请求会阻塞心跳线程。现由独立单线程、32 个排队项的有界投递器查询 `GaExecutionStore.cancellationScope(runId, epoch)`，使用该 epoch 的绑定 lease。新 attempt 已替换绑定时查不到旧 epoch，绝不向新 attempt 发取消；Python 的 epoch+lease 校验处理查询后发生的接管。取消尽力而为，队列满不阻塞心跳、不写 task 的协作取消位。新增真实 HTTP 测试验证 binding lease、慢响应下立即返回、过期 epoch；真实 PG 验证绑定代次切换。数据库故障演练日志还记录到实际 Python 取消 HTTP 200。

**追加修复二：SSE 发送不再持 Run 全局锁或占用提交/轮询线程。** `publish` 和生产 catch-up poller 只提交游标通知；每订阅者合并通知并串行发送，仍逐事件检查授权/session，发送成功后推进 DB 游标。独立线程池 4 个线程、128 个排队项，不把事件正文放入内存队列。过载摘除订阅者，发布线程不调用可能被 socket 写阻塞的 complete；默认 emitter 生命周期 30s，客户端用已有游标重连。该容量是实现初值，不是承载承诺；全部发送线程被慢连接占用时仍存在延迟上限/容器写超时的部署验证边界。

自动测试：同 Run 一个阻塞 emitter 不妨碍正常订阅者；一万次通知不会按事件数扩张队列；140 个订阅者全部待发时仅保留 4 个执行 + 128 个排队，溢出不在发布线程做 socket completion。另新增真实 PG + Tomcat + TCP 测试：接收窗口 1024、读完 HTTP 头后不再读取；生产发送线程实际进入 `NioSocketWrapper.doWrite`，120 条 16KiB 事件可提交，同 Run 正常客户端收齐 1–122（含终态），业务 Run 不因传输阻塞失败。测试 profile 不启用 Spring scheduling，因此最终夹具每秒调用真实生产 catch-up poller；此前遗漏 poller 的失败日志保留，没有以扩大超时掩盖。最终 1/1 通过，用时 3.705s。此项是 LOCAL 嵌入式真实传输验收，不冒充 OIDC 多身份矩阵。

**新候选包**：`backend/build/p2-acceptance/runtime-p2-bounded-sse-cancel-protocol13.jar`；SHA256 `CBCC5F1BA1C9E7CBE0C1CA7983065215A2A168F2F482600088300FD6E9438846`。专项 323 项通过，队列饱和增量测试通过；最终完整 `testNonLive` **1967 / 0 failures / 0 errors / 34 skipped / 316 suites**，8m05s。随后仅新增上述 TCP 测试，未改生产代码；不得把两次不同执行伪称“1968 项单次全量”。证据 `evidence/P2_RESUMED_FINAL_FULL_REGRESSION.json`、`evidence/P2_RESUMED_REAL_SLOW_SOCKET.json`。

**当前包真实故障验收（隔离环境，均通过）：**

| 场景 | 持久化/进程证据 | 边界 |
|---|---|---|
| 模型在途杀执行 JVM | Run `fa8820b6-a080-4ed1-9143-c6e8c06bee3f`，MODEL=RESERVED 时杀 A；存活 B 收敛 task=FAILED、epoch 1→2、业务 RUN_INTERRUPTED；model_calls=1，容量 2→0，无助手消息/迟到事件；等待原调用返回后事实不变 | 不确定账本仍为 RESERVED，未重放；不是 GA 安全 checkpoint 接管后业务 COMPLETED |
| B 执行、A 订阅 + 游标 | Run `6d8fd65b-b487-485e-bd90-6120a898d885`，B worker `10fab441…`，浏览器 8282→A 收到 1–7；后续帧在途到达；游标 2 只回放 3–7 | 原生模型为显式 test-only fake，Python broker、OIDC、PG、JVM 均真实 |
| PostgreSQL 真实中断 | Run `a52e68e5-9432-4ae1-9c6d-99aea895e800` MODEL 在途时仅停止 `spec-agent-p2-auth`，保持超过 15s 租约；双实例 503；DB 恢复后准入仍失败关闭；重启恢复 task/Run FAILED、预算不重置、槽释放、无迟到产物 | 准入失效故意不可自动复活，需要受控重启；未重启 Docker Desktop、未影响开发容器 |
| DB 恢复后新任务 | Run `871e03ec-6251-4900-888d-7055040d5cef` COMPLETED，MODEL SUCCEEDED，事件完整，容量归零 | 只证明受控恢复，不是不中断可用性 SLA |
| 舰队 key 分歧、轮换、回退 | 新副本 `spec_agent_p2_rotation_20261015b`：错误主 key 实际 JVM 启动拒绝；新 primary+旧 fallback 启动迁移；第二新 key-only JVM 启动；独立 Java 进程使用产品解密器验证 2 条模型凭证 + 1 条 MCP 凭证；跨组织复制 3/3 拒绝；停舰队后旧 primary+新 fallback 反向重加密，再用旧 key-only 读取 | 合成凭证，未联系真实模型/MCP；是协调维护窗口，不承诺滚动轮换 |
| 备份恢复 | 从轮换前 pg_dump 恢复到独立 `spec_agent_p2_rotation_20261015b_restore`，当前兼容二进制启动且产品解密器读取通过 | 不是允许把多人数据回退到不支持隔离的旧二进制；没有反向删除权限表 |

证据分别为 `evidence/P2_RESUMED_WORKER_CRASH.json`、`P2_RESUMED_CROSS_INSTANCE_SSE.json`、`P2_RESUMED_DATABASE_OUTAGE.json`、`P2_RESUMED_KEY_ROTATION_RESTORE.json`。可复用驱动：`tools/p2_kill_worker_acceptance.py`、`tools/p2_database_outage_acceptance.py`、`tools/p2_key_rotation_acceptance.py`、`tools/P2CredentialRotationProbe.java`。前两者通过 bsk 的真实 OIDC 会话与应用 HTTP client 提交，没有导出 cookie/CSRF；DB 故障脚本拒绝在 Gradle 测试或轮换进程存活时运行。驱动只针对隔离库/已核实 jar 与 PID；rotation 脚本拒绝覆盖既有库，失败副本 `...20261015a` 亦保留。该首次失败是把 API-only 的 `/api/health`（故意 503）当作 worker ready；最终按 `/actuator/health` 检查 DB/进程就绪，未修改产品健康语义。

**剩余入口审计的确定事实与未完成范围：**

- 本地主体构造已存在 ArchUnit 白名单门禁 `actorConstructionIsRestrictedToIdentityAdaptersAndPersistedDelegationReaders`，生产 `ActorContext.local` 调用仅 IdentityService / OwnershipAttribution / TaskActor 三处；早期“只是约定”已不是当前事实。
- 真实库仍允许 `projects.org_id`、`profiles.org_id`、`global_assistant_threads.org_id/owner_user_id` 为 NULL；V66 的五张模型配置表 org_id 仍带 legacy DEFAULT。当前测试库与验收库零空值，不意味着新遗漏写入会被拒绝。本轮未追加迁移，不能称归属约束已最终收紧。
- 其余 checkpoint/记忆/检索的完整 source-dependency 失效与全部 Controller/工具/恢复入口、五身份两组织的浏览器角色矩阵仍需按原设计逐项验收；不能因以上故障矩阵通过就把 G9/整个 P2 写成已闭合。
- 企业 IdP/TLS、真实模型的恢复后业务完成与承载测量仍不是本轮证据；`ga-recovery-enabled` 默认关闭未改变。当前恢复策略保留不确定账本并诚实失败。

未部署、未修改开发库、未覆盖用户真实凭证、未提交或推送。验收 A/B、brain 与 Vite 继续保留；仅轮换演练的临时 JVM 已停止。原验收库无在途任务。历史 jar 与失败证据保留。

### 3.48 V92/V93 归属约束收尾、可移植测试与真实组织级撤权（2026-10-15）

**最终归属约束已实施，不再是待办。** 逐一核对 ProjectRepository 两个 save 入口、ProfileRepository 默认画像复制、GlobalAssistantThreadRepository 创建、项目成员三个写入口，以及模型/搜索配置仓储，生产写入均显式落 org/owner。新增 V92（未改 V61/V66）：先验证缺失行数为 0，再收紧 `projects.org_id/created_by`、`profiles.org_id`、`global_assistant_threads.org_id/owner_user_id`、`project_members.org_id` 六列 NOT NULL；补上项目组织/创建人、画像组织、会话组织/属主五条已验证 FK。画像随所属组织清理，项目与会话的父归属不允许悬空。移除 V66 五张模型配置表的 org DEFAULT；全 schema 复核还发现 search_settings 的 legacy 默认，由 V93 独立移除，保留已执行 V92 的校验和。任务 actor 字段此前已去掉默认值，本次未重复更改。

`ExplicitOwnershipConstraintIntegrationTest` 四项真实 PG 测试验证缺组织/创建人/会话属主、缺项目成员 org、配置遗漏 org、未知组织/用户均被数据库拒绝（23502/23503），而不是仅测试应用 guard。三个旧测试文件的少量直接 SQL 夹具改为显式 legacy org/user。全新测试库与 OIDC 验收库均核实：`version=93`、六列可空数=0、全 schema legacy org 默认数=0、五条新 FK 均已 validate。证据 `evidence/P2_V93_SCHEMA_OWNERSHIP.json`。

**存量缺口迁移反例已验证。** 从 V91 隔离库创建专用副本 `spec_agent_p2_v92_gap_rejection`，注入一个无 org/creator 的项目，再启动真实 V92 jar。进程退出码 1，原因精确命中 V92 缺归属核验；该行仍无归属，V92 没有成功历史记录，列仍保持旧 nullable 状态——整个迁移回滚，没有悄悄归给本地用户。旧库有缺失或悬空归属时必须先离线核实修复，不得为了启动自动分配。证据 `evidence/P2_V92_MIGRATION_REJECTION.json`。

**新库完整回归还定位了一处旧夹具错误。** `ExecutionFenceAtomicityBarrierTest` 的 PostgreSQL 锁等待观察写死 `datname='spec_agent_test'`。实际已在新库发生的行锁等待看不见，15s 后屏障超时；旧清理先断言线程结束、再释放 lease，导致租约/行锁残留和后续级联阻塞。已改为 `current_database()`，lease 清理放 finally。第一次 V92 全量因此只完成 600 项、10 失败、1 跳过，后续确认已挂在残留行锁上，保留日志/线程转储后仅停止该测试 JVM；不冒充完整回归，也没有终止验收 JVM 或 PG。重新建立 `spec_agent_p2_hardening_test2`，屏障/租约/约束专项通过，再跑真正完整回归。

**当前最终基线：1972 tests / 0 failures / 0 errors / 34 skipped / 318 suites，7m49s。** 包含新增 TCP 与约束测试、ArchUnit，以及修正后的屏障测试。证据 `evidence/P2_V93_FINAL_FULL_REGRESSION.json`，日志 `backend/build/p2-acceptance/resume-v93-final-full.log`。当前候选 `runtime-p2-explicit-ownership-v93-protocol13.jar`，SHA256 `90A8B3991BE2D9DDC86932D91EADBD5BF526E9073037646280C861E362F8B9A8`。与 §3.47 故障验收包逐一比较 **1091 个 runtime class 的字节全部一致**；后续是迁移资源/测试变更，不将旧包的实际故障证据改写成新包重新执行。新候选仍另做了下面的真实 OIDC 验收。

**V93 新候选真实 OIDC 两身份验收：**

1. Alice 的项目/私有会话正控读取成功。Bob 是同组织 OWNER、无项目角色时，项目读取 404（PROJECT_NOT_FOUND）、列表不出现该项目、Alice 私有会话 404。依冻结设计 §5，组织管理员可以显式自授项目角色并审计；Bob 明确授予自己 MANAGER 后项目可读，审计有 PROJECT_ROLE 记录，Alice 会话仍 404。初版脚本误把这种显式自授断言为拒绝；核对设计后只修脚本，未改产品授权规则，失败尝试保留。
2. Alice 提交 GA Run `70dd24fb-6e4c-4b9b-ab82-b16709a2a3bd`。真实 OIDC 切换到 Bob 并选同一组织，核实 MODEL 仍 RESERVED，再由第二 OWNER 通过 `DELETE /organizations/{org}/members/{alice}` 撤销 Alice 组织资格，避开“最后 OWNER 不可退出”的正确约束。
3. Run 与 task 均 CANCELLED，MODEL=UNKNOWN，model_calls=1，容量归零，outbox pending=0，事件只有 RUN_STARTED/RUN_CANCELLED，无助手消息。等待 32 秒（原生流 fixture 每片 10 秒）后，账本、终态与事件完全不变。随后由 Bob 通过产品 API 恢复 Alice 为 OWNER；SQL 核实 ACTIVE，outbox 再次归零。

该场景使用真实 Keycloak 授权码登录、两个 JVM Worker、真实 Python broker、显式 test-only native fixture；不是简单 cancel API，也未直接 SQL 改成员资格。浏览器仅用 bsk，账户为隔离 realm 的合成测试账户，没有导出 cookie/CSRF。驱动 `tools/p2_org_revocation_acceptance.py`；证据 `evidence/P2_V93_OIDC_ORGANIZATION_REVOCATION.json`。此项覆盖 Alice/Bob，不冒充完整五身份/全 UI 控件矩阵。

**V93 正向长任务与有限连接重连：** Bob 的新 Run `52430045-a4d1-49a9-a958-a5d812a61cb0` 经真实 Python broker 完成，MODEL SUCCEEDED，model_calls=1，容量归零。30 秒原生流跨过 emitter 生命周期，现有生产 stream client 按 Last-Event-ID 重连，两条连接合计收到严格连续 1–7，没有重复或缺号。一次 `stream closed` 是有限连接的预期传输断开，不是业务失败；没有取消 Run 或重置预算。证据 `evidence/P2_V93_LONG_RUN_RECONNECT.json`。

**当前环境**：隔离数据库名称仍为 `spec_agent_p2_workers_20261015v92`，实际 Flyway 已是 V93；新候选 A=8383/B=8384，brain=8104，Vite=8282/8286，OIDC protocol13，lease15s/heartbeat3s，native fixture 每片10s。旧 V91/V92 数据库、jar、失败与成功证据均保留；现有开发库未改。安全协议没有新增数据格式，本次是显式归属写入的数据库强制约束，不修改 Java/Python 协议或打开 GA 自动恢复。

**本轮之后真正剩余：**全 Controller/RPC/工具/恢复入口与全部 memory/checkpoint/source-dependency 审计闭合；完整五身份、项目角色与全部 UI/跨实例撤权退出矩阵；embedding/公共索引全部部署组合与费用边界；企业 IdP/TLS 和承载测量。已完成的归属收紧、故障矩阵、两身份组织撤权不应重复列为“未开始”。能力调用 RUNNING 记录目前保持类型化 IN_PROGRESS、无自动 TTL 重放；其全调用点/崩溃一致性与人工核对流程仍须另审，不能把“没有自动重试”说成完整外部副作用只执行一次。

完整 P2 仍未标完成。未部署、未切换开发库、未提交或推送；验收服务保留、无在途任务、outbox pending=0。最后检查时 `bsk session list` 已为空，本 agent 没有调用 session stop；续跑必须先检查 browsers/session list，并创建新的后台会话、重新 observe，不能复用历史 qfge/klgv 等句柄。

# Skill 包结构与导入规范 v1（含改造方案）

> 背景：用户尝试从 `obra/superpowers` 导入 Skill 失败（`Git package entry escapes the package root: .agents/plugins/marketplace.json`），
> 由此暴露两个层次的问题：**包发现能力缺失**与**结构规定过窄**。
> 本文是「市面上能装的 Skill 该怎么被我们接受」的规范与落地计划。
> 状态：P0 已完成；P1 已实现；P2–P4 见 §六。

---

## 一、现状评估（"是不是有点简单了"——是）

当前导入模型只有一种形态：**一个仓库 = 一个 Skill 包 = 仓库根目录有 SKILL.md**。

已经具备的部分（不用重做）：

| 能力 | 落点 |
|---|---|
| 强制 SKILL.md + YAML front-matter（`name`/`description` 必填） | `SkillMarkdownParser` |
| 包内路径 containment（`..`/绝对路径/超深/超长/控制字符） | `SafeZipExtractor.validatePath`、`GitSkillImporter.normalizePath` |
| 规模上限（归档 2MiB / 展开 10MiB / 256 文件 / 深度 8 / 路径 512 字符） | `SkillProperties` |
| 暂存 → 审核 → 安装（不可变版本、内容哈希去重、装完默认 disabled） | `SkillImportService` |
| 零执行姿态（不跑脚本、不装依赖、不跑 hooks、不解析 LFS） | `SafeZipExtractor` / `GitSkillImporter` |

缺口（就是"太简单"的地方）：

1. **只认根目录 SKILL.md** —— 多技能仓库、插件市场仓库一律失败。
2. **隐藏目录被当逃逸**（P0 已修）—— `.claude-plugin/`、`.agents/` 这类元数据目录会让整次导入中止。
3. **front-matter 被拍平成 `String→String`** —— 数组变 `[a, b]`、嵌套 map 变 `{k=v}`，真实技能里的 `allowed-tools` 列表、`metadata` 结构全部失真；且 `name`/`description` 无字符集与长度校验。
4. **provenance 不含路径** —— `sourceIdentity` 只有 `git:<sha>`，同一仓库同一 commit 导两个技能无法区分来源。
5. **交叉引用会断** —— 实测 `skills/brainstorming/SKILL.md` 正文用**仓库相对路径**引用兄弟文件（`skills/brainstorming/visual-companion.md`）；剥离前缀后包内只有 `visual-companion.md`，引用失效。
6. **助手没有安装能力** —— `GlobalAssistantToolCatalog.TOOL_IDS` 只有 4 个只读/建项目工具，模型只能说「Opening skills now.」并跳转设置页（已中文化）。

---

## 二、市面形态实测（决定"要适配到什么程度"）

以 `obra/superpowers`（main，2026-09-18 实测）为最复杂样本：

| 形态 | 实例 | 现状 |
|---|---|---|
| A. 单技能仓库（根 SKILL.md） | 大量个人技能仓库 | ✅ 支持 |
| B. `skills/<name>/SKILL.md` | superpowers 的 14 个技能 | ❌ 不支持 |
| C. 市场清单 + 插件根下技能 | `.claude-plugin/marketplace.json` → `plugins[].source: "./"`，技能在插件根的 `skills/` | ❌ 不支持（且曾被隐藏目录规则误杀） |
| D. 深层嵌套 | `plugins/<plugin>/skills/<name>/SKILL.md` | ❌ 不支持 |
| E. 正文交叉引用仓库相对路径 | `skills/brainstorming/visual-companion.md` | ❌ 剥离前缀后断裂 |

superpowers 的市场清单实测内容（节选）：

```json
{ "name": "superpowers-dev",
  "plugins": [ { "name": "superpowers", "version": "6.3.0", "source": "./" } ] }
```

技能自身的 front-matter 实测非常克制：只有 `name` + `description`（`description` 是一整段"何时必须使用"的触发说明，长度约 200–300 字符）。

⇒ 结论：**要适配的是 A–E 五种形态；核心是"包发现"，不是"更宽松的校验"。** 安全姿态不允许放宽，只能把"找包"这一步做对。

---

## 三、结构规范 v1（写死的规定）

### 3.1 front-matter 字段

| 字段 | 必填 | 类型 | 边界 | 说明 |
|---|---|---|---|---|
| `name` | 是 | string | `^[a-z0-9]+(-[a-z0-9]+)*$`，≤ 64 | 对齐 Agent Skills 规范；现在是"非空即可" |
| `description` | 是 | string | ≤ 1024 | 现在是"非空即可"；catalog 另有 320 字符的展示截断 |
| `version` | 否 | string | ≤ 64 | 仅展示与追溯，不参与版本号（版本号由内容哈希决定） |
| `license` | 否 | string | ≤ 128 | 仅展示 |
| `allowed-tools` | 否 | string 或 string[] | 每项 ≤ 64，≤ 32 项 | **只记录不授予**；我们的工具授权由 CapabilityRegistry 决定 |
| `metadata` | 否 | map<string, string> | 键 ≤ 64、值 ≤ 512、≤ 32 项 | 保留结构，不解释 |
| 其他未知键 | 否 | 任意 | 保留原始 JSON，不解释 | 前向兼容，不因未知字段失败 |

解析层要求：**保留 YAML 结构**（不再拍平），越界字段按上表拒绝并给出具体字段名。

### 3.2 包内目录约定（约定但不强制）

- `SKILL.md`：必须在**包根**
- `references/`、`scripts/`、`assets/`、`examples/`、`templates/`、`docs/`：约定用途，仅用于分类展示与资源浏览
- `scripts/` 内文件**永不执行**，只作为可读文本资源（现状保持）
- 其他任意路径：允许（只要通过 containment 与规模上限），归类为"资源文件"

### 3.3 路径与规模（统一一份语义）

- POSIX 分隔符；禁止 `..`、绝对路径、控制字符、空段
- **隐藏条目（任一段以 `.` 开头）在导入时跳过**：它们属于仓库/工具元数据，不是技能内容
- 规模上限沿用 `SkillProperties`（不改默认值）
- 资源读取：**包内相对路径为主**；新增"原仓库相对路径"别名（见 3.4）

### 3.4 provenance 与别名（解决交叉引用断裂）

导入时记录：

- `sourceIdentity = git:<commitSha>#<subPath>`（`subPath` 为空则不带 `#`）——新增路径维度
- `packagePrefix`：被剥离的原仓库前缀（如 `skills/brainstorming`）
- 资源读取（`SkillResourceService`）解析顺序：包内相对路径 → 若未命中，尝试去掉 `packagePrefix + "/"` 前缀后的路径 → 仍未命中则报错

⇒ `skills/brainstorming/visual-companion.md` 与 `visual-companion.md` 都能读到同一份内容，市面技能正文不用改。

---

## 四、包发现（discovery）

新增 `SkillPackageLayout`（纯函数、可离线单测）：

```
discover(paths) -> List<SkillRoot { path, kind, declaredBy }>
```

分类规则（按优先级）：

1. `SKILL.md` 在仓库根 → `ROOT`（形态 A）
2. 任意深度的 `<dir>/SKILL.md` → `NESTED`（形态 B/D）
3. 市场清单（`.claude-plugin/marketplace.json`、`.agents/plugins/marketplace.json`）声明了 `plugins[].source` → 在这些前缀下再找 `skills/*/SKILL.md`，标 `MARKETPLACE` 并记录 `declaredBy`（形态 C）
4. 去重、按路径排序、候选数上限（默认 50），超出部分只报数量

市场清单解析约束：清单里的路径必须是**仓库内相对路径**（拒绝绝对路径、`..`、URL、符号链接）；解析失败只忽略该清单，不使导入失败。

切片（`slice(files, subPath)`）：只保留 `subPath/` 下的文件并剥掉前缀，要求剥完根目录有 `SKILL.md`，否则报"该子目录不是 Skill 包"。

---

## 五、导入 API 与助手能力

### 5.1 API

- `POST /api/v1/skills/imports/git`：body 增可选 `subPath`（向后兼容，缺省 = 仓库根目录）
- `POST /api/v1/skills/imports/git/discover`：`{url, ref?}` → `{commitSha, suggestedPath, candidates:[{path,name,description,kind,declaredBy,fileCount,parseable}]}`
- `sourceIdentity` = `git:<sha>#<subPath>`，审核页据此确认装的是哪一个

### 5.2 助手安装能力 `skill.import`

**定位：只暂存，不安装、不启用。** 一次调用闭环：

1. 参数 `{ url, ref?, skill? }`（`skill` = 子目录路径或技能名）
2. 内部：clone → `discover` → 选定候选 → `stage`
3. 返回 `{stagedImportId, name, description, fileCount, candidates?}`，模型据此说明"已暂存，请到 Skills 设置页审核安装"
4. `skill` 缺省时：候选唯一 → 直接暂存；多候选 → **不猜**，返回候选清单让模型追问或让用户指定
5. 导航：UiAction 目的地 `SKILLS`（复用现有导航能力）

安全边界（必须保持）：

- `SideEffectClass.LOCAL_DURABLE`，只写"暂存行"，不执行任何包内容
- **不**自动 `install`、**不**自动 `enable`（安装后默认 disabled 是既有不变量）
- 编辑白名单 `GlobalAssistantToolCatalog.TOOL_IDS` / `FINGERPRINT` / `allowedArguments` + 决策校验 + 提示词四处，缺一不可
- 为什么不做自动安装：GA 目前没有审批 UI（`approvalRequired` 是占位文案"当前版本暂不支持审批操作"），写能力必须有可回滚的暂存语义才安全

---

## 六、落地阶段

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 隐藏目录误判修复 + 可操作报错 + 弹窗 hint | ✅ 已完成 |
| P1 | `SkillPackageLayout`（发现/分类/清单解析/切片）+ git `subPath` + discover API + provenance | ✅ 已完成（`SkillPackageLayoutTest` 32 例 + `GitSkillImporterPathRulesTest` 8 例） |
| P2 | 助手 `skill.import` 能力（白名单/fingerprint/参数校验/提示词 + 测试） | ✅ 已接线（用户确认；冻结不变量按"精确点名 Skill Runtime 宿主工具"收紧，见下） |
| P3 | 前端：Git 弹窗子目录字段 + 候选列表（调 discover API）+ 批量导入入口 | ✅ 子目录与候选列表已完成；批量导入未做 |
| P4 | 规范化收口：parser 保留 front-matter 结构 + 字段边界校验 + 资源路径别名 + 本文件定稿 | 待开工 |

### P2 落地（已接线）

- `SkillImportCapability`（`skill.import`，`SideEffectClass.LOCAL_DURABLE`）：参数 `{url, ref?, skill?}`；`discoverGit` → 匹配候选 → `stageGit`。**唯一候选才暂存；多候选返回 `requiresChoice=true` + 候选清单让模型追问，不猜**。候选描述截断到 160 字符、候选数上限 20——候选元数据来自**不可信仓库**，只以有界形式进模型上下文。
- `TOOL_IDS` 第 5 项 + `FINGERPRINT` → `ga-v1:...,skill.import`（fingerprint 是"本次 run 的工具集指纹"，随工具集变化是预期）。
- **冻结不变量的收紧方式（不是放宽）**：`gaCatalogIsolatedFromSkillMcp` 原来用 `skill.` **前缀黑名单**，现改为**精确点名 Skill Runtime 的宿主工具**（`skill.activate` / `skill.search` / `skill.read_resource`）——真正的风险面是"把不可信指令或远端工具定义拉进模型上下文"，而 `skill.import` 只暂存一条待人工审核的记录、只回元数据。同时保留 `mcp.` 前缀黑名单。`contextIsBounded` 与 `modelCatalogHoldsExactlyTheV1Tools` 的数量断言同步为 5。
- 提示词无需手改：`GlobalAssistantPromptRenderer` 从 `context.toolDescriptors()` 渲染，登记后 descriptor 自动进提示词。**并有测试钉住**：`promptOffersTheSkillImportToolToTheModel` 断言渲染出的工具段包含 `skill.import` / `"url"` / `"ref"` / `"skill"` / `sideEffectClass=LOCAL_DURABLE` / `stagedImportId`——否则"接上了"只是名义上的。
- **内容映射禁止 null 值**（`CapabilityResult` 会 `Map.copyOf`）：未暂存时不落 `stagedImportId` 键，而不是置 null。
- 前端无需改动：暂存结果复用现有 Skills 设置页审核流（装完默认 disabled，需要用户显式启用）。

### 七、Git 传输失败（`Git import failed: TransportException`）

**根因（2026-09-18 本机实测）**：不是代码强制直连，而是 **JVM 天然不继承浏览器/系统代理**。

```
jvmDefaultSelector=sun.net.spi.DefaultProxySelector
useSystemProxies=null        # 未开启 ⇒ 走直连
httpsProxyHost=null
DIRECT:  FAIL TransportException(connection failed) <- ConnectException(Connection timed out: getsockopt github.com)
PROXY 127.0.0.1:7897: OK head=b36e0829c6d0140e93cfef2ca599b1b07d4a7797
```

浏览器能打开 GitHub 是因为它走 Windows/WinINET 系统代理（Clash 7897），而 JVM 只有显式设置 `-Djava.net.useSystemProxies=true` 或 `https.proxyHost` 才会用代理；JGit 的 HTTPS 传输读的是 JVM 默认 `ProxySelector`。

**产品化**：`spec.agent.skill.git.proxy`

| 值 | 行为 |
|---|---|
| `AUTO`（默认） | `HTTPS_PROXY`/`ALL_PROXY`/`HTTP_PROXY` 等环境变量 → 否则探测**正在监听的本地代理**（7897/7890）→ 否则直连（不改动 JVM 默认选择器） |
| `DIRECT` | 显式不使用代理（`ProxySelector.of(null)`），用于绕过系统代理 |
| `host:port` | 固定走该 HTTP 代理（容忍 `http://` 前缀与结尾 `/`；不支持带凭据的 URL） |

失败信息也修好了：不再只说 `Git import failed: TransportException`，而是带上**实际路由**与**最底层原因**（截断 300 字符），并直接点出该旋钮：

> `Git import failed: TransportException via direct (no proxy configured or detected) (Connection timed out: getsockopt github.com). If this host needs a proxy, set spec.agent.skill.git.proxy=host:port (or DIRECT to force a direct connection)`

只影响 Skill git 导入这一条链路：模型网关（Zen）本身显式 `NO_PROXY`，不受影响；反之本旋钮也不会去动它。

### 八、真实仓库端到端验证（2026-09-18）

用 `https://github.com/obra/superpowers` 全链路实跑（`discoverGit` + `importHttps(subPath)`，即生产代码路径，只把仓储/Spring 换成直接构造）：

```
route=HTTPS_PROXY=http://127.0.0.1:62397     # AUTO 按优先级命中环境代理
commitSha=b36e0829c6d0140e93cfef2ca599b1b07d4a7797   # = GitHub main HEAD
suggestedPath=skills/brainstorming
candidates=14   全部 parseable=true、kind=MARKETPLACE、declaredBy=（插件源为仓库根）
slicedFiles=8   slicePaths=[SKILL.md, scripts/*, spec-document-reviewer-prompt.md, visual-companion.md]
skillMdHead=---  （front-matter 完整，name: brainstorming）
```

这次实跑还抓到一个**规则级缺陷并已修**：市场清单 `.claude-plugin/marketplace.json` 原先被"跳过隐藏条目"规则一起跳过 ⇒ 归属信息永远为空、所有候选都退化成 `NESTED`。现改为 `EntryDisposition` 三分法——`MANIFEST`（读取但不入包）/ `PACKAGE` / `SKIP`，清单计入大小上限但不进 Skill 包，并补了单测。

### P4 待办要点

- `SkillMarkdownParser` 保留 front-matter 结构（现在数组/嵌套 map 被拍平）+ 字段边界校验（`name` kebab-case ≤64、`description` ≤1024 等）
- 资源读取的仓库相对路径别名（解决 superpowers 正文 `skills/brainstorming/visual-companion.md` 式交叉引用）
- 批量导入（多技能仓库一次导入多个候选）

### 非目标（明确不做）

- 执行包内任何脚本 / 安装依赖 / 跑 git hooks（零执行姿态不变）
- 远程市场索引与在线商店（不做"应用市场"服务端）
- 技能签名与信任链（可后置）
- 放宽 containment 与规模上限（适配靠"找对包"，不靠"放宽校验"）

# 交接文档：文档解析 + 资源独立 + 多路线独立（2026-09-16 凌晨，分支 `system-bugfix-v1`）

> 本轮按你的四项指令开工：① PDF/.docx/.xlsx 解析 + 扫描件 OCR；② Q2 先做 D；
> ③ Q1 开工（后端 + 前端）；④ 资源独立开工。
> **当前验证状态**：前端 `vue-tsc` 干净 + 单测 **678/678**（基线 648 → +30）；
> 后端 `compileJava` / `compileTestJava` 通过（离线编译）。
> ⚠️ **后端 JUnit 集成测试与浏览器实测均未执行**（按你要求未启动任何服务），
> 这是本轮唯一未闭环的部分，新会话必须先补（见 §五）。
>
> 前一轮（图谱 UI 修复 +「只看这条路线」）见 `docs/HANDOFF_GRAPH_UI_2026-09-15.md`；
> 决策流水见 `.workbuddy/memory/2026-09-15.md`、`.workbuddy/memory/2026-09-16.md`。

---

## 一、文档解析（PDF / .docx / .xlsx / OCR）——前端，已验证

**结论先行**：解析发生在浏览器本地、模型之前。本项目模型传输层是纯文本 chat completions
（`ChatCompletionsProtocolAdapter` 只写 `content: <string>`），二进制永远到不了模型，
所以只有抽出的文本会随资源节点的 `content.text` 走完整链路。全程零解析模型、零上传。

| 项 | 说明 |
|---|---|
| 新模块 | `frontend/src/util/documentText.ts`：`extractDocumentText(file, onProgress)` 单入口，按扩展名分流 |
| PDF | `pdfjs-dist@4.10.38` 文本层逐页抽取；**整份无文本层 = 扫描件 → 自动退回 OCR**；部分页无文本层 → 逐页警告 |
| .docx | `jszip@3.10.2` 解 OPC 包 → `word/document.xml`，按段落/制表/换行 tokenize（`docxXmlToText`） |
| .xlsx/.xlsm | 同一 zip → `xl/workbook.xml` + 关系表 + `sharedStrings`，逐工作表抽单元格值（含 inlineStr/数值/共享字符串），每个工作表一行 `# 表名` 头 |
| OCR | `tesseract.js@5.1.1`，`chi_sim+eng`；**本地优先**（见下），失败自动退回 CDN |
| 上限 | 抽出文本 > 256KB 时截断并给用户提示（`MAX_EXTRACTED_TEXT_BYTES`，按字节计算） |
| 失败语义 | 一律显式报错（`DocumentExtractionError` + 用户可读文案），绝不静默附加空资源 |
| 接入点 | `ResourceDialog.vue`（accept 列表、进度提示、`extractKind`/`extractedByOcr` 写入 content）；store 动作改为 `createFloatingResource` |
| 测试 | `src/util/__tests__/documentText.spec.ts` 18 项：spec 级 OOXML 夹具（真实 zip）、共享字符串/内联/空单元格、截断、PDF 页循环与 OCR 回退（注入假 pdfjs/假 OCR，见 `configureDocumentExtractorsForTests`） |

**OCR"内置"的落地方式**（回答你"是不是也可以内置"）：

- 语言模型：`frontend/public/tessdata/{chi_sim,eng}.traineddata.gz`（tessdata **fast** 版，共 3.7MB）**已随仓库提交** —— 这是唯一没有 npm 包可用的部分。
- 运行时（worker + WASM core，约 7MB）：`npm run vendor:ocr`（`frontend/tools/vendor-ocr-assets.mjs`）从 node_modules 复制到 `frontend/public/tesseract/`，已挂到 `predev`/`prebuild`，并已加入 `.gitignore`（避免 7MB WASM 进 git）。
- 运行时取用顺序：本地 `/tesseract` + `/tessdata` → 失败则退回 tesseract.js 的 CDN 默认值。
- ⚠️ 因此 **`npm install` 之后第一次 `npm run dev` 会自动补齐 WASM**；但语言包是提交的，克隆即有。

**新依赖**：`pdfjs-dist@^4.10.38`、`jszip@^3.10.1`、`tesseract.js@^5.1.1`（package.json + package-lock 已更新）。

---

## 二、Q2-D：共享节点"当前查看"默认填满——前端，已验证

**单一真源**：`frontend/src/graph/graphInteraction.ts` 新增 `resolveReadingRouteId({membershipRouteIds, visibleRouteIds, focusRouteId})`，规则固定为：

1. 显式 Focus 命中归属 → 直接生效（只看这条路线 / 点卡片已经定死，不用再选）；
2. 否则归属路线中**只有一条可见** → 取它（隐藏/筛掉其它归属同样让歧义消失）；
3. 否则 → null（真正歧义，由卡片显式询问）。绝不回退 Active/first/latest。

**两处消费方对齐**（此前两个解析器不一致，会出现"读的是 A、命令却没有来源路线"）：

- 投影 `graphProjection.ts` 的 `readingRouteId`（卡片展示 / 继续入口 / 问 AI / 需求状态）；
- `WorkspaceView.sourceRouteForNode()`（fork / 重答 / 换题的来源路线，现在会算可见集）。

**卡片 UI**（`GraphQuestionNode.vue`）：`readingRouteId` 已确定 → 只读徽标（`data-test="reading-route-resolved"`，靛蓝胶囊）；**只有真歧义**才渲染原下拉（`reading-route-select`）。这就是"共享节点只聚焦这个节点"的落点：默认不用选，只有系统真的定不下来才问你。

**测试**：`graphInteraction.spec.ts` 6 项新用例 + `GraphQuestionNode.spec.ts` 重写 2 项（已确定→只读；歧义→选择器可切换）。

---

## 三、资源独立：先浮动、用户自己连线——后端 + 前端

### 后端（已编译，⚠️ 测试未跑）

| 改动 | 位置 |
|---|---|
| `NodeRepository.updateParent(nodeId, parentNodeId, updatedAt)` | `node/NodeRepository.java`（血缘归属 = `parent_node_id` 链 + 路线 tip，无 join 表） |
| `GraphOperation.Type` 新增 `CONNECT_FLOATING_NODE` / `DISCONNECT_NODE` | `graph/GraphOperation.java` |
| `connectFloatingNodeToRoute(projectId, routeId, nodeId, parentNodeId)`：浮动节点接入路线成为新 tip。复用 `attachResource` 的摆放规则（**只接受当前 tip** + `validateQuestionCanHaveChild`，手画连线不可能插入历史）；节点 id/kind/content 不变，只改 parent + tip | `graph/GraphCommandService.java` |
| `detachNodeFromRoute(...)`：仅当前 tip 可断开、有存活子节点拒绝；内容永不销毁 | 同上 |
| Undo/Redo：connect 的撤销 = **detach（不 retract，内容保留）**；redo 重放前校验 tip 未被移动，否则 fail-closed；`describeUndo/Redo` 已补分支（switch 表达式穷尽性由编译器保证） | `graph/UndoRedoService.java` |
| 端点：`POST /nodes/{nodeId}/connect`、`POST /nodes/{nodeId}/disconnect`；`CreateDraftNodeRequest` 新增可选 `nodeKind`（**仅** floating 端点解释，默认 KNOWLEDGE）——浮动资源由此可行 | `api/graph/GraphCommandController.java` |

### 前端（已验证）

- `ResourceDialog` → `store.createFloatingResource()`：资源创建为**浮动 RESOURCE**（不再要求 Active 路线，不再挂 tip）；`attachResource`/`store.attachResource` 保留未删（直接挂载路径 + 存量测试）。
- 画布拖线：`GraphCanvas.onConnect` 识别 **floating ↔ routed** → 发 `connect-floating` 事件（routed ↔ routed 仍是语义关系提案）；`WorkspaceView.handleConnectFloating` 把锚点解析成"显式路线 + 末端父节点"——候选 = 锚点为 tip 的 OPEN 路线，再走 `resolveReadingRouteId`；**解析不出就明确报错**（`CONNECT_REQUIRES_ROUTE_TIP` / `SOURCE_ROUTE_REQUIRED`），绝不猜 Active/first/latest。
- 知识/资源卡（`GraphKnowledgeNode.vue`）：浮动节点显示"独立节点"徽标 + "还没接入任何路线…"提示（明说 AI 接入前读不到）；`isTipOfReadingRoute` 时提供"断开路线"。资源子类型标签（文档/链接/图片…）单独成表。

### 必须知道的既有约束（未改变，别当成新 bug）

资源/知识节点成为路线 tip 后，`AnswerCycleService` 的输入校验会拒绝作答（无 options 且 `allowFreeAnswer=false`）——**今天的 `attachResource` 就是这个行为**，本轮只是复用同一校验。资源永远不该被当作可答节点。

---

## 四、Q1：多路线各自独立生成/回答——后端 + 前端

### 设计原则（务必保持）

**加法式改造**：请求不带显式路线时，所有行为与之前**逐字等价**（包括"Active 指针变了要 fail-closed"的既有守卫）；带了显式路线才进入 EXPLICIT 模式。原"非 active 拒绝"的语义只在默认路径保留。

### 后端改动链（已编译，⚠️ 测试未跑）

1. **入口**：`AnswerCycleRunController` 把 `CreateRunRequest.sourceRouteId` 复用为**显式路线**（ANSWER_TIP / RESUME_ANSWER / DRAFT_QUESTION / GENERATE_ARTIFACT；REGENERATE_NODE 本来就用它）。传入即校验"属于本项目"，OPEN 校验统一在 `RunService.resolveTargetRoute`（IllegalStateException → 409 RUNTIME_CONFLICT）。
2. **指纹**：`AgentRunRequestFingerprint.forClientRequest` **本来就包含 `sourceRouteId`**，无需改动 → 幂等重放不会串线。
3. **RunService**：`resolveTargetRoute(projectId, explicitRouteId)`；新增 `createQueuedRunWithInputResultForRoute(..., explicitRouteId)`、`createQueuedDraftQuestion(…, explicitRouteId)`、`createQueuedArtifactGeneration(…, explicitRouteId)`（旧签名全部保留为 `null` 委托）；run payload 写入 **`routeSelection: "ACTIVE" | "EXPLICIT"`**（worker 复原用）。`createContinueRun` 按父路线是否为 Active 写入同一标记（子链继承）。
4. **RunWorker**：`readRunInput` 读 `routeSelection` → 还原 `explicitRouteId` → 传给 `submitAnswer/resumeAnswer/draftQuestion`。
5. **AnswerCycleService**：`submitAnswer/resumeAnswer` 各加一个带 `UUID explicitRouteId` 的重载（旧签名保留，测试驱动不受影响）；`resolveRunRoute` 显式时要求 OPEN + 同项目；tip/陈旧校验**照常执行**，只是比较对象换成解析出的路线；显式路径上下文用 `contextBuilder.buildForRoute(...)`（该方法本就存在且不读 Active）。
6. **DecisionCycleService**：`draftQuestion(run, explicitRouteId)`；显式时跳过"必须是 Active"检查（tip 检查保留）、上下文走 `buildForRoute`、`contextGuard.validate(snapshot, true)`。
7. **ContextGuard**：新增 `validate(snapshot, explicitRoute)`；显式时**只**跳过 Active 相等性检查，归属/OPEN/hash 检查全部保留。1 参重载保留原语义。
8. **ContinuationCycleService / ArtifactCycleService**：通过 RUN_CREATED payload 的 `routeSelection` 判定显式性（读取各自新增的 `isExplicitRouteRun(run)`），显式子链不再被 Active 指针拒绝。

### 前端改动（已验证）

- `SubmitAnswerRequest` 新增可选 `nodeId` / `routeId`（显式回答目标）。
- `GraphQuestionNode.submit()` 始终带上 `nodeId: canonicalNodeId` 与 `routeId: readingRouteId`。
- **投影 `canAnswer` 放宽（关键决策）**：可回答 = (a) 运行路线当前节点未答（原语义逐字不变）**或** (b) 用户**显式聚焦**的那条路线（Focus / 只看这条路线）的末端未答。门槛故意收紧到"显式 Focus"——默认视图绝不冒出第二个作答入口。
- `workspaceStore.submitAnswer`：目标可由 payload 指定；**`sourceRouteId` 只在目标 ≠ Active 路线时才发送**（默认路径完全保持后端的 fail-closed 语义）；答题锁从全局改为**按路线**（`answerRunsInFlight: string[]`，`submitting` = 派生的"任一路线忙"）；结算/修复判定全部改用提交时捕获的路线（新增 `answerTargetRouteTip()`、`findFinalizedAnswerForNode` 改查提交路线的 answers），修掉了"用运行路线 tip 判断已提交目标"的隐性错配。
- 显式生成规格：`GENERATE_ARTIFACT` 也接受显式路线（控制器已接；前端 SpecDock 的 reading route 尚未传，见 §六）。

---

## 五、⚠️ 未验证清单（新会话第一件事）

> ### 执行结果（2026-09-16 上午，已补跑）
>
> - **后端测试**：✅ **1338 用例，仅 2 个失败**，且两个失败都是环境依赖、与本轮改动无关 ——
>   `PythonBrainCrossLanguageIntegrationTest` 需要真实 brain，而 brain 的 `SPEC_AGENT_INTERNAL_BROKER_URL`
>   固定指向 8080、该测试实例却是随机端口 → brain 转发到 dev 后端（opencode，无可用模型）得 500 →
>   brain 回 502。把 brain 指向该实例端口（`SPEC_AGENT_CROSS_LANG_PORT=8123` + 临时 brain 容器）后
>   **BUILD SUCCESSFUL**。§五.1 点名的重点类**全部通过**（含 ContextGuardTest、AnswerCycleRunApiIntegrationTest、
>   ScriptedRouteIsolationIntegrationTest、AnswerRouteIsolationApiIntegrationTest、UndoRedoIntegrationTest、
>   FloatingNodeResponseRouteIdTest、GraphLineageInvariantIntegrationTest 等）。
> - **e2e**：✅ 点名的 4 个（fork / graph-routes / lifecycle / shared-focus）跑出 3 个红灯，
>   **全部是测试自身没跟上代码**，已修复 → 7 passed：
>   ① `lifecycle` 漏 import `openRouteFilters`；
>   ② `graph-routes` 的 show-all 历史节点期望 3 → 实际 2（用例先点了非运行路线卡设 Focus，
>   其末端因此可回答、渲染为"当前"，这是 Q1 canAnswer 放宽的预期结果）；
>   ③ `shared-focus` 仍在等 `reading-route-select`，而 Q2-D 已把"已确定"改成只读徽标 ——
>   改用 `shared-reading-route` 容器定位并断言"下拉消失 + 徽标出现"。
> - **新增** `e2e/floating-resource.spec.ts`（本轮 connect 原本零覆盖）。
> - ⚠️→✅ **曾发现一个产品语义阻塞，已按决策 A 修复**（详见 `.workbuddy/memory/2026-09-16.md` 第七、八轮）：
>   `connectFloatingNodeToRoute` 要求父节点是"有 finalized answer 的当前 tip"，但常规流程里
>   回答后 tip 恒为新生成的**未回答**问题 → 手动拖线接入**必然 409 被拒**（资源创建为浮动节点是正常的，
>   只有"接入"这一步不可达）。
>   决策 A：**资源/知识节点不可回答，挂在未回答末端不会跳过任何待回答问题，应豁免该校验**。
>   落地：`GraphInvariantValidator` 新增 kind-aware 重载（4 参），`childKind` 非 INTERACTION 时
>   跳过 `UNANSWERED_QUESTION_HAS_CHILD`；3 参重载委托 `childKind=null`（严格模式），
>   既有调用点逐字等价。`connectFloatingNodeToRoute` 传 `node.kind()`；
>   `appendContinuation` **保持严格**（既有测试 `appendContinuationFromUnansweredQuestionRejects`
>   断言它必须拒绝）。e2e 已改为断言接入成功 + undo/redo 可逆，实测通过。
> - ⏳ **仍未做**：§五.3 OCR 真实浏览器实测（`.pdf/.docx/.xlsx/图片` 各来一次）。

1. **后端集成测试**（必须先跑，本轮改动最多的一层）：
   ```bash
   cd backend
   set SPRING_PROFILES_ACTIVE=test
   set SPEC_AGENT_MODEL_GATEWAY=fake
   set SPEC_AGENT_MODEL_INFERENCE=fake
   set SPEC_AGENT_BRAIN_WORKER_ENABLED=true
   gradlew.bat test
   ```
   重点观察（这些断言"非 active 拒绝"，我的改动刻意只在默认路径保留它们）：
   `ContextGuardTest`（3 项）、`QuestionDraftIntegrationTest.draftFailsWithoutActiveRoute`、
   `QuestionDraftFailureIntegrationTest.staleDraftTargetFailsClosed`、
   `AnswerCycleRunApiIntegrationTest`（stale/无 active/resume 三项）、
   `ArtifactRouteBindingIntegrationTest.artifactRunFailsClosedWhenActiveRouteChangesBeforeExecution`、
   `ScriptedModelGatewayFullLoopIntegrationTest`、`ScriptedRouteIsolationIntegrationTest`、
   `AnswerRouteIsolationApiIntegrationTest`、`FakeFullLoopContextIsolationIntegrationTest`、
   `CapabilityIntegrationTest.resourceCannotBeAttachedAtHistoricalNode`、
   `GraphLineageInvariantIntegrationTest.attachResourceFromUnansweredQuestionRejectsWhenItAdvancesLineage`、
   `UndoRedoIntegrationTest`、`FloatingNodeResponseRouteIdTest`、`RelationInvariantIntegrationTest.relationNeverChangesFloatingPlacementOrRouteState`。
2. **浏览器实测**（后端改动需重启 dev 后端才生效）：
   - 资源：添加资源 → 出现"独立节点"卡 → 拖线到路线末端 → 接入成功、undo/redo 正确；
   - 多路线：聚焦探索分支（或"只看"）→ 直接回答其末端 → 答案写入该路线而非运行路线；两条路线同时各答一题互不阻塞；
   - 文档：真实 PDF（有/无文本层）、.docx、.xlsx、图片 OCR 各来一次，看进度提示与截断警告。
3. **OCR**：`tesseract.js` worker + 本地语言包只在真实浏览器跑过路径推断，未实测；若失败先看 `/tesseract/worker.min.js`、`/tessdata/*.gz` 是否 200。

---

## 六、已知取舍 / 后续建议（按优先级）

1. **规格生成的路线一致性**：控制器/服务已支持显式路线，但前端 `generateSpec` 还没把 `specReadingRouteId` 传成 `sourceRouteId` —— 用户在 B 路线点"生成规格"仍会生成 A 的。一行改动 + 一个测试。
2. **资源接入选路 UX**：锚点是多条路线的末端时会报错让用户先选路线；可以改成弹一个小选择器（复用 Q2-D 的解析结果做默认值）。
3. **答题pending卡**：两条路线同时答题时，`pendingAnswerNodeId` 仍只标最近一次提交的节点（另一条链正确运行、由刷新呈现，但画布上没有 pending 徽标）。要彻底做成"每路线一张 pending 卡"需把 `pendingAnswerNodeId/answerRunId/answerRunStatus` 改成按 routeId 的 map——动到链路轮询与 5 个 F 用例，建议单独立项。
4. **仓库体积**：`public/tessdata` 提交了 3.7MB 语言包；若在意可改为脚本下载（README 说明），但会失去"克隆即离线 OCR"。
5. **e2e**：上一轮已改写的 4 个 e2e 仍未跑（需 test profile）；本轮新增的 connect/disconnect 也没有 e2e 覆盖。

---

## 七、验证基线（本轮结束时）

| 层 | 命令 | 结果 |
|---|---|---|
| 前端类型 | `node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | ✅ 干净 |
| 前端单测 | `node_modules/vitest/vitest.mjs run` | ✅ **678/678**（87 个文件） |
| 后端编译 | `gradlew.bat compileJava compileTestJava --offline` | ✅ 通过 |
| 后端测试 | `gradlew.bat test`（test profile） | ⚠️ **未执行** |
| 浏览器 | dev 探针 | ⚠️ **未执行**（按你要求未启动/未重启任何服务） |

命令备忘（本机 Bash PATH 是坏的，每条命令前先修）：

```bash
export PATH="/usr/bin:/bin:/c/Windows/System32:$PATH"
cd E:/project/spec-agent/frontend
"C:/Users/32962/.workbuddy/binaries/node/versions/22.22.2-3/node.exe" node_modules/vue-tsc/bin/vue-tsc.js --noEmit
"C:/Users/32962/.workbuddy/binaries/node/versions/22.22.2-3/node.exe" node_modules/vitest/vitest.mjs run
```

---

## 八、衔接词（新会话直接粘贴）

> 继续 `E:\project\spec-agent` 的 `system-bugfix-v1` 分支。
> 先读 `docs/HANDOFF_MULTI_ROUTE_2026-09-16.md`（本轮四项工作 + 未验证清单），
> 再读 `docs/HANDOFF_GRAPH_UI_2026-09-15.md`、`.workbuddy/memory/2026-09-16.md`、`.workbuddy/memory/MEMORY.md`。
> 第一件事：跑后端 test profile 集成测试（§五 的命令与重点类清单），修复红灯；
> 然后重启 dev 栈做浏览器实测（资源浮动→连线接入、聚焦 B 回答 B、文档解析、OCR）。
> 之后按 §六 优先级继续：规格生成传 reading route、资源接入选路 UX、每路线 pending 卡。
> 所有前端改动必须保持 `vue-tsc` 干净 + `vitest run` 全绿（当前 678/678）；
> 后端改动必须保持"不传显式路线时行为逐字等价"这条不变量。

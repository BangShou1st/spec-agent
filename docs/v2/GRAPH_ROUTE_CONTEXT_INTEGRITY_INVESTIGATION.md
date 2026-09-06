# Graph / Route / Edge / Context Integrity Investigation

日期：2026-09-06。基线分支 main，基线提交 0d44e63。
本轮只调查，不修改 production behavior。未改动 Graph、Edge、Context、Route、AI policy、prompt、scorer 的任何行为，未新增生产代码与迁移。
方法：读 DB migration、实体、Repository、各 Service、Context 与 Snapshot builder、Policy 与 Stale 校验、前后端 mutation 入口，并静态审计测试断言内容（只认断言，不认测试名）。未执行全量测试套件。
阅读指南：第一部分是给普通用户看的结论；第二部分是技术证据。两者结论一致，可互相印证。

# 第一部分：给普通用户看的结论

## 1. 节点能不能随便加？

能加，但只能往前长，不能插进历史。新节点要么长在当前路线的末尾，要么从某个历史节点长出一条明确的新分支。
系统不允许把新节点塞进两个已有节点之间、假装它一直就在那里。从历史节点继续时，系统会自动建分支并把工作台切过去，旧路线原样保留。
另有一种浮动想法节点：它先飘着，不属于任何路线，也不会进入任何 AI 上下文，直到你亲手把它接上去。

## 2. 已有连线能不能随便改？

不能。后端根本没有改连线、删连线、搬节点、换父节点的接口，前端也没有拖拽改线的功能，全库搜索零命中。
画布上拖一根线，只会弹出语义关系提议（相关、支持、冲突等），确认后也只是记一条备注式关联，不改变路线父子结构，不改变 AI 看到的历史主链。
唯一能反悔的是撤销：把刚建的节点软隐藏并把路线末尾退回去，历史数据行本身不被删除或改写；重做也只恢复同一行，不另起新身份。

## 3. 旧节点能不能移动？

不能。没有移动、换父、合并路线这类操作。删除路线也只是标记删除，节点、答案、决定都还在，不会误删别的路线共用的东西。

## 4. 两条路线会不会串？

问答正文、答案、补丁这条主链不会串。Personal 路线看不到 Enterprise 路线的私有问答，反之亦然，该隔离有专门的信封级测试覆盖。
但有一个例外：AI 输入里的最近能力调用结果（最多 5 条）是按整个项目取的，不按路线过滤。若 Enterprise 刚跑过工具，Personal 下一轮输入里可能顺带看到它。这是大结论下唯一真实的缝隙。
## 5. Shared Node 会不会污染其他路线？

会，而且是设计如此。Shared Node 就是同一个节点被多条路线引用，不是复制。
它的正文若还能改（仅限仍是草稿的用户知识节点），所有引用它的路线立刻可见。它的问答案更是全局唯一：一道共享的题全项目只有一个标准答案，所有路线看到的是同一个答案 ID。
所以共享节点适合放真正的共识，不适合放某条路线才需要的私密信息。私密信息应放在分支自己的新节点里。

## 6. AI 有没有修改历史结构的能力？

没有。AI 只能提议新建节点或记一条语义关联；想改连线、建路线、改节点正文这类拓扑提议会被策略层直接拒绝，连待确认框都不会产生。
能落到图上的 AI 写入只有两条窄路：直接追加（锚点必须仍是路线末尾，否则按过期拒绝）或用户点确认后执行（执行前重验新鲜度）。AI 够不着历史边的改写。

## 7. 修改历史后旧上下文会不会变化？

旧快照不变，新一轮看新状态。每一次模型输入都先冻结成带哈希的只读存档，之后重跑重试都回放该存档，不会偷偷用新数据，历史可复现。
但下一轮新任务会基于当前数据库重新取数：草稿正文、知识状态等变化会进入新快照。这是符合预期的向前看新、向后看旧。

## 8. 目前最危险的地方是什么？

按对用户的实际伤害排序：第一，共享节点的惊讶传播，改了一个共享草稿以为只影响当前路线，实际所有路线都变了。
第二，能力结果的项目级顺带，最近 5 条工具结果不分路线，私密工具输出可能出现在另一条路线的模型输入里。
第三，想改线的心理预期，用户以为能像思维导图一样拖线改结构，实际只支持分支。若将来有人绕过后端直接改库，历史语义会被破坏，故历史边不可原地改应立为铁律。

一句话 verdict：新节点追加安全，已有连线改不动，路线主链隔离强，共享节点故意不隔离，旧存档只读，AI 碰不到历史结构。唯一短板是把能力结果按路线收敛。
# 第二部分：技术证据

## 2. 真实 Graph 数据模型

结论先行：Node 行上没有 routeId 列。路线归属由边的父指针加路线的 tip 推导出来，不是节点自己声明的。

核心表（backend/src/main/resources/db/migration）：V2 建 projects、routes、nodes、answers、answer_patches、agent_runs、context_snapshots、spec_snapshots。
V4 加 routes 的 branch_type、source_route_id、branch_at_node_id，以及 route_inherited_answers（分支对源答案的冻结引用，只存引用不复制内容）。
V10 加 nodes 的 kind、subtype、content、author_kind、knowledge_status、retracted_at、updated_at，并建 node_relations 与 graph_operations。
V18 加对称关系 DB 唯一后挡；V19 给 context_snapshots 加 related_node_ids 与 relations_json；V20 建 agent_input_projections（冻结模型输入，一快照一行）。

字段存在性（存在表示库与实体均有）：Node 有 id、projectId、parentNodeId、createdByRunId、supersedesNodeId、question、purpose、options、allowFreeAnswer、createdAt、kind、subtype、content、authorKind、knowledgeStatus、retractedAt、updatedAt。
Node 上没有 routeId、anchor、tip、edgeType、sharedState、version 列，这些是推导或别表概念。
可见延续边就是 nodes.parent_node_id：单父可空外键指向 nodes，无 CASCADE（默认 NO ACTION），不存在独立 edge 表。
语义边是 node_relations 行：id、projectId、sourceNodeId、targetNodeId、relationType、origin、status、createdByProposalId、createdByRunId、createdAt、retractedAt，物理不删除。
Route 有 id、projectId、rootNodeId、tipNodeId、lifecycleStatus、label、createdFromNodeId、supersedesRouteId、replacementOfNodeId、createdByRunId、branchType、sourceRouteId、branchAtNodeId、createdAt、updatedAt。
active 由 projects.active_route_id 表示，不存在 lifecycle 值为 active 的行。
快照存 ID 清单与哈希，不存完整图状态；冻结投影按 snapshot_id 唯一，一次冻结永不原地更新。

## 3. Edge 到底是什么

结论：多种边混合，但职责严格分离。把视觉上线和语义上线混为一谈是必须纠正的误解。
- 可见延续边（parent_node_id）：含义是探索从此节点继续到彼节点；影响 Route 是；影响 AI 主链是；允许修改否。
- RELATED_TO：对称语义关联；影响 Route 否；影响 AI 仅 NODE_QUERY 一跳是；允许创建是、撤销恢复是、原地改否。
- CONFLICTS_WITH：对称语义冲突；其余同上。
- DEPENDS_ON：有向因果依赖，参与 DAG 环检查；其余同上。
- DERIVED_FROM：有向派生来源，参与 DAG 环检查；其余同上。
- SUPPORTS：有向支持关系，不参与 DAG 环检查；其余同上。
## 4. 新节点完整调用链

用户路径（GraphCommandService，每个方法皆 Transactional 且先取 project 行锁）：
- 空路线首节点 createRootDraftNode：要求 tip 为空，否则必须走 continuation。
- 浮动草稿 createFloatingDraftNode：routeId 可空，不推进任何 tip，节点 parent 为空，日志记 floating 标记。
- 通用续写 appendContinuation：源是 tip 则直连并推进 tip；源是历史节点则建显式分支路线（含 sourceRouteId 与 branchAtNodeId），冻结 inherited prefix，把 active 切到新分支，绝不插入历史。
- 资源 attachResource：只允许空路线根或当前 tip，不允许历史分支。
- 草稿原地改 reviseDraftNode：仅 PROPOSED 的用户 KNOWLEDGE 草稿可改 subtype 与 content，并记 before 与 after。
- 知识状态 setKnowledgeStatus：显式状态机转换，记 before 与 after。
- 语义关系 createSemanticRelation：校验端点同项目、非收回、非自环，对称类型规范端点序，判重插入，记操作日志。

AI 路径：模型与策略在事务外决策，落库只进 AgentGraphMutationService.executeNodeCreation（Transactional 加 project 锁，重读 route 为 OPEN，要求锚点仍是当前 tip，否则 StaleProposalException，节点 insert 与 tip 推进同事务）。
待确认路径走 ProposalAcceptanceService.acceptAndExecute（project 锁、proposal 行锁单赢者仲裁、StaleContextChecker 重验、按 family 分发；UPDATE_NODE、CREATE_ROUTE、GENERATE_ARTIFACT、CONTINUATION 类连接直接拒绝执行）。
NodeService.createWorkspaceNode 负责 insert 后紧跟 advanceRouteTip（root 为空则同时设 root，否则保 root 只推进 tip）；createFloatingWorkspaceNode 只 insert 不推进。
Capability 本轮不直接建节点：调用结果以最近完成 5 条观察的形式进入快照证据，不是 lineage 成员。

## 5. 先建节点还是先定边，原子性如何

真实顺序在同一事务内：校验、insert 节点行（含 parentNodeId 即边）、update 路线 tip 与 root、append 操作日志。
情况 A（节点成边败）：同事务回滚，不存在 orphan；浮动节点是有意 orphan，不属失败。
情况 B（边成 tip 败）：同事务回滚，不存在边悬空。
情况 C（图成快照败）：快照构建在 run 时独立发生，不在 mutation 事务内；mutation 成功不受快照失败污染，失败 run 按过期重试处理，重放读冻结投影。
情况 D（同 tip 并发 append）：project 行锁串行化。用户两人同从 C 出发，一人直连推进 tip 为 D，另一人提交时源 C 已非 tip，转为显式分支；AI 两人同锚，一人成功一人按 StaleProposalException 失败，不静默 rebase。
结论：Graph mutation 是原子的（单事务加 project 锁，顺序为 project、node 与 route、mutation、operation log）。
## 6. 已有 Edge 是否可以修改

全库搜索 updateEdge、deleteEdge、replace、move、reparent、reconnect、unlink、detach、merge route、SET parent_node_id、DeleteMapping：lineage 方向零命中。
矩阵：lineage 边 CREATE 经由新节点追加是（但只能长新边，不能改旧边）；UPDATE 否；DELETE 否；REPARENT 否；RECONNECT 否。
语义关系 CREATE 是；UPDATE 否（无原地改接口）；物理 DELETE 否，软 RETRACT 经 Undo 是，Redo 恢复同一行是；REPARENT 不适用。
Node 行仅有 updateDraft（PROPOSED 草稿）、updateKnowledgeStatus、updateRetracted 三个窄 UPDATE，无 parent 列 UPDATE。
Route 行仅有 updateTipAndRoot（追加与撤销补偿）、updateLifecycle（归档、删除、恢复、取代）、clearTipAndRoot（仅撤销根创建），无历史父子改写。
delete old edge 加 create new edge 的等价操作不存在；Undo 是补偿（retract 加 tip 回滚），不是重写历史行。

## 7. 改旧边、删边、删点、移点的假设推演与现实对照

现实是改不了，本节为万一以后放开的推演，用于锁定 invariant。
假设 A 到 B 到 C 到 D，把 A 到 B 改成 X 到 B：B 的 lineage 全变，C 与 D 的有效答案组合与补丁链全变，所属 route 的 path 全变，context_hash 全变，tip 在 D 则未来快照全变；
旧 snapshot 行虽 immutable，但新旧快照的 lineage 交集断裂，来源引用（answer、claim、capability 的 sourceRefs）与图 lineage 会出现图说来自 X、引用说来自 B 的分叉。
假设删边 A 到 B：B 成 orphan，C 整串脱离原路线，tip 若在 C 则 lineage 解析到断头，ContextBuilder 与读模型对缺失、跨项目、环、超深一律 fail-closed。
假设删点 B：A 到 C 直连需要改 C 的 parent（无接口），断开需要改 C（无接口），级联删被 FK（无 CASCADE）与 immutable answers 禁止，三者皆不可达。
假设移点 C 到 Y 下：历史来源、后代整串、引用合法性、原路线引用、旧快照 lineage 须同时改，漏一项即污染；若未来引入必须标 HIGH RISK，建议永远不要加移动并保留 ID 的操作，真改方向请用新分支、新替换节点或显式 supersedes。
现实对照：撤销只处理栈顶可逆操作，要求节点仍是 active 叶子、无答案占用、tip 未继续推进，否则直接拒绝；补偿是 retract 加 tip 回滚到 parent，不碰 parent 列。

## 8. Branch 真实实现

Route 是数据库实体，不是推导视图；path 由从 tip 沿 parentNodeId 走到 root 推导。同一节点 A 可同时属于两条路线的 lineage，B 与 X 的 parent 可同为 A。
Route 用 rootNodeId 与 tipNodeId 记两端，用 sourceRouteId、branchAtNodeId、branchType 记出处，用 route_inherited_answers 冻结分支点之前的源答案引用（存引用 ID，不复制内容）。
fork 要求分支点在源 lineage 上且该点已有 finalized 有效答案；reanswer 为旧题复制语义建新题节点，只继承祖先前缀，旧答案留在源路线；
regenerate（replacement）要求源 tip 未移动且问题文本确有变化，提交后源路线标 SUPERSEDED 并切 active 到新路线；tip 推进只发生在建节点、撤销补偿、重做恢复三处，无后台偷改。
## 9. Shared Node 专项

实现：同一 Node ID 被多条 Route 的 tip lineage 同时包含，无复制，无 route 粒度的 parent。
内容改：仅 PROPOSED 用户草稿可原地改，所有引用路线即时可见，不存在只改一条路线可见的语义。
加语义边：不进任何路线 lineage，不改 tip、root 与成员；仅当某次 NODE_QUERY 锚点恰是该端点时，以有界一跳形式进入那一次快照的 relations 与 relatedNodeIds，且 related 永不污染 lineage。
删一条 route 关系：删的是 route 行（标 DELETED）或分支可见性，不删 node 行，不影响其他路线。
在共享节点后建 child：写入时显式带 routeId 并只推进该 route 的 tip，故 child 只属于当前路线，不属于所有路线；共享的是前缀，分支的是后缀，这是隔离成立的关键。
canonical Question 的共享答案全局唯一身份：第二份答案在任何路线上都会被 SHARED_STATE_DIVERGENCE 拒绝；
读模型若发现同一题对应多个有效答案 ID，或部分路线有答部分无答，直接抛不变式异常，不做 active、first、latest 回退。

## 10. Cross-route pollution 推演

案例：Root 下 Personal 路线有 P1，Enterprise 路线有 E1，给 Enterprise 加管理员权限答案。
若该权限是某节点答案、补丁或 claim：ContextBuilder 只取当前 routeId 的 tip lineage 加该 lineage 上的有效答案加这些答案的补丁，excludedRouteIds 记其余所有路线，Personal 下轮输入不含 E1，反向亦然。
若该权限是 capability 工具结果：AgentInputSnapshotBuilder.capabilityResults 取全项目最近完成 5 条，不按 route 过滤，Personal 下一轮可能顺带看到它。这是本轮发现的唯一主链外泄漏。
判定：lineage、answer、patch 维度为 STRONG；完整模型输入因 capability 观察为 PARTIAL。NODE_QUERY 的语义一跳有界且显式，不算污染，但调用方须知道 related 节点正文会被模型读到。

## 11. Context 生成与顺序

五个真实入口：buildFromActiveRoute（读 active 指针）；buildForRoute（显式 routeId 加 inputNodeId，且 inputNodeId 必须等于当前 tip，否则 fail-closed，排队 run 不会因 active 漂移串到别的路线）；
buildForRegenerate（只取目标父链，故意排除目标与其子树）；buildForReplacement（校验源路线 tip 链与父链）；buildForNodeQuery（锚点链加可选 routeId，浮动节点要求 routeId 为空且锚点不属于任何路线）。
准入规则：same route 是（显式 routeId，无回退）；ancestor 是（tip 到 root 链）；shared 当且仅当在该链上；confirmed、unresolved、latest、reachable 否（不按状态过滤整链）；sibling 否；superseded、archived、deleted 路线默认进 excluded。
顺序确定：includedNodeIds 即 root 到 tip 的 lineage 序，投影按 manifest 序逐节点配答案与补丁；context_hash 为哈希稳定对各 ID 集排序后计算，但投影不按哈希序重排。
例外：capabilityResults 是最近完成序，related 与 relations 按快照存序与规范序；主链 deterministic，观察附带 deterministic 但非 lineage 序，消费者不应把观察序当因果序。
## 12. Snapshot、Stale、并发、环与杂项

旧 ContextSnapshot 行 immutable（无 UPDATE 语句）；旧 AgentInput 冻结 payload 按 snapshot_id 唯一行回放，hash 校验失败即 fail-closed，不静默用活表重建。
新 run 按当前 DB 重建新快照：lineage 变化、答案增减、知识状态变化进入新 hash；NODE_QUERY 重算锚点一跳语义集比对，不一致按 stale 处理。
AI 在 tip C 思考、用户抢先建 C 到 D、旧 AI 再交 C 到 X：锚点非 live tip，直接 StaleProposalException，不覆盖、不 rebase、不静默分支。
并发 C 到 D 与 C 到 E：用户侧经 project 行锁串行，一直连一分支；AI 侧一胜一 stale；答案并发经 node 行锁加 SHARED_STATE_DIVERGENCE 单赢者；冻结投影并发经 insert-if-absent，输者认领胜者 payload，无 last-writer-win。
保护机制是悲观锁（project 行锁、node 行锁、proposal 行锁），非版本号与乐观锁；答案有（route、node）唯一，语义关系有活跃唯一与对称唯一，无 tip 唯一（tip 必须能动）。
环：经正常 API 不可达（新节点 ID 全新，parent 只能指已存在节点，改 parent 无接口）；resolveLineage 有环检测与万深上限，命中 fail-closed；语义边仅 DEPENDS_ON 与 DERIVED_FROM 联合 DAG 检查，SUPPORTS 允许环。
多父：lineage 单父不可达；语义多入边可达但不参与 lineage。孤儿：浮动节点是有意孤儿，正常路线上下文看不到它，routeless NODE_QUERY 可读它，AI 不会顺带看到它。
重复：同文本不同 ID 合法，ContextBuilder 不去重，提示词需容忍重复。内容改：INTERACTION 的 question、purpose、options 创建后不可变，再生走 replacement 新节点；KNOWLEDGE 草稿仅 PROPOSED 可改；CONFIRMED 后走状态机；C 与 D 基于旧 B 而 B 被改时系统不自动 invalidate 后代（MEDIUM）。
Decision 与已决：答案 immutable 单终态，re-answer 必须新题新 ID，regenerate 必须新路线新节点并取代旧路线。来源引用与图 lineage 独立存储，改语义边不改 provenance。
Route Merge 无此功能；Route Delete 为软删 lifecycle，不删节点边答案；Route Focus 是浏览器只读意图，后端 mutation 从不读 focus，切 focus 不 activate 不重连；
前端拖拽 connect 只发 relation-proposal pending，确认落语义边，取消不留痕，无拖节点删边重连边，节点位移只存本地视图坐标。

## 13. 权限边界

用户：create node 是；create semantic relation 是；delete lineage edge 否；reparent 否；modify old node 仅 PROPOSED 草稿与状态机是；change route（activate、archive、delete、fork、reanswer、regenerate）是。
AI：create node 经追加窄路是；create semantic relation 经确认是；其余 lineage 改写全否；UPDATE_NODE、CREATE_ROUTE、GENERATE_ARTIFACT、CONTINUATION 连接提案直接 deny。
System 与 Runtime：tip 推进、inherited prefix 冻结、快照构建、冻结投影、stale 校验是；历史改写否。最高风险项 AI 改历史边不存在：无接口、无策略、无执行器三重缺失。
## 14. 推荐 invariant（建议，不实现）

历史语义边 immutable：已进入历史的 parent、tip、lineage 关系不原地改；改方向请用新分支、新替换节点、supersedes 关系或显式 detach 建新路线。
现状已符合该 invariant（无改边接口），建议写成架构测试锁死：断言 nodes.parent_node_id 无 UPDATE 可达、GraphCommandService 外无 tip 写入、AI family 中拓扑改写类恒为 deny。
共享即共识：共享节点默认全员可见，私密信息禁止放共享前缀。观察按路线收敛：capabilityResults 改为按 lineage 或显式 route 白名单过滤，过滤前先补测试。
草稿改后代不自动失效：若未来放宽编辑，必须引入 revision 加后代 invalidate 或显式 supersedes，否则保持现状门禁。

## 15. 风险分级

CRITICAL：无（未发现可直接改历史拓扑或跨路线偷答案的路径）。
HIGH：共享节点惊讶传播（设计如此但用户易误解）；绕过应用层直改库 parent 与 tip（replay 断裂，属运维红线）。
MEDIUM：capability 最近 5 条项目级可见；PROPOSED 草稿改后后代不自动失效；duplicate 同文本不去重。
LOW：浮动节点长期悬空；对称关系端点序历史遗留（已有迁移与规范化）；观察序非因果序。

## 16. 真实案例

案例一（共享草稿惊讶）：用户在路线 A 的共享知识节点 S 改一句话，以为只影响 A；实际 S 被路线 B 引用同一 ID，B 下轮 lineage 即见新文。正确做法是先分支再写私密后代。
案例二（能力结果顺带）：Enterprise 跑工具输出管理员密钥，Personal 下一轮模型输入附带最近 5 条观察，可能含该密钥。修复方向是观察按路线过滤。
案例三（历史追问变分支）：用户在历史节点 H 点继续，期望插进历史；实际建分支并切 active 到新路线，旧路线原样保留。若没注意到 active 已切，会疑惑旧路线为何没变，需 UI 强调分支提示。
案例四（旧 AI 被拒）：AI 按 tip C 决策，用户抢先建 C 到 D，旧 AI 的 C 到 X 被 StaleProposalException 拒绝。宁可拒绝不覆盖，调用方须把拒绝转成可读提示而非静默丢弃。

## 17. Tests audit（只认断言内容，不认名字）

branch isolation：ScriptedRouteIsolationIntegrationTest 中 fork 信封断言不含 sibling sentinel 与兄弟节点 ID、共享根答案仍在，强。
regenerate 隔离：同文件 regenerate 投影排除旧答案补丁与子树，强。shared 单一答案：SharedStateInvariantIntegrationTest 与读模型 SHARED_STATE_DIVERGENCE 断言，强。
lineage 不变量：GraphLineageInvariantIntegrationTest，强。关系不变量：RelationInvariantIntegrationTest 与 SemanticRelationApiIntegrationTest（端点、自环、重复、DAG），强。
stale 与过期：StaleContextCheckerTest、ProposalMutationStalenessIntegrationTest、ProposalAcceptanceIntegrationTest、E25FrozenStaleTest，强。
并发：AnswerFinalizeConcurrencyIntegrationTest、AgentProposalIdempotencyConcurrencyIntegrationTest、AgentInputFrozenProjectionConcurrencyIntegrationTest，强。
冻结重放：AgentInputFrozenProjectionIntegrationTest（篡改、错版、异 snapshot fail-closed），强；LegacyReplayIntegrationTest，部分。
环：ContextBuilderLineageTest 仅 mock 构造环断言 fail-closed 与不落库，部分；无经 API 建环测试（因无建环接口，缺是合理的）。
多父 lineage、edge 改动、reparent、move、delete：缺（无功能，缺合理，但建议加架构测试锁死无此类接口）。
草稿改门禁与知识状态：部分（本轮未逐断言展开）。route 删除共享安全：部分（软删语义有覆盖，跨路线共享删除显式断言待补）。
capability 观察隔离：缺（现状即泄漏，无测试锁死行为，补测试前先定产品语义）。context 重建 determinism：ContextBuilderNodeQueryContextTest 与 ContextSnapshotTest，部分。

## 18. Verdicts

NODE_APPEND_SAFETY = SAFE
EXISTING_EDGE_MUTATION = FORBIDDEN
ROUTE_ISOLATION = PARTIAL
SHARED_NODE_ISOLATION = WEAK
SNAPSHOT_REPLAY_SAFETY = STRONG
STALE_WRITE_PROTECTION = STRONG
CONTEXT_POLLUTION_RISK = MEDIUM

注释：EXISTING_EDGE_MUTATION 指 lineage 边为 FORBIDDEN；语义边为受控的创建加撤销恢复（GUARDED append-only），无原地改。
ROUTE_ISOLATION 取 PARTIAL 仅因 capability 观察项目级可见；lineage 与答案维度为 STRONG。
SHARED_NODE_ISOLATION 取 WEAK 是设计语义（共享即共识），非缺陷，但需产品明示。

## 19. 最重要的问题

问：如果用户今天修改了一条已经存在很久的连线，明天 AI 沿这条路线继续工作，它看到的是原来的历史还是修改后的历史？
答：当前实现下没有修改旧连线这个操作，AI 不会看到任何一种被改写的历史。能改的东西分两种：旧快照（过去）与新运行（未来）。
旧 ContextSnapshot 行与 AgentInput 冻结 payload 是 immutable 的，重跑只回放冻结字节，看到的是原来的历史。
新 run 按当前 DB 重建快照，草稿正文、知识状态、新增语义边、路线生命周期变化进入新 lineage 与新 hash，AI 看到的是修改后的未来。
一句话：向后看旧，向前看新；历史边本身改不动，不存在历史被悄悄换掉而旧存档跟着变的情况。唯一要警惕的是绕过应用层直改库，这会破坏上述保证。

## 20. 本轮未改动与观测缺口

未改动：Graph、Edge、Context、Route mutation、AI policy、prompt、scorer、快照与投影逻辑、任何迁移与测试。
观测缺口（先记录不实现）：缺某旧 snapshot 与当前 DB 的 lineage 差异对比只读接口；缺 capability 观察跨路线出现的计数与告警；缺共享节点被多路线引用时的 UI 强提示埋点。

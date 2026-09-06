# Context Consistency Residual Risk Investigation

日期：2026-09-06。基线分支 main（承接上一轮 a5178d7）。
本轮只调查，不修代码、不改 prompt、不改 ranking、不改 eligibility、不跑 full90。上一轮已证明的结论（主链不可改、append 安全、replay 强、stale 强、主链隔离强）不再重复验证，直接复用。
方法：追 capability 调用链与投影代码、共享节点读写模型、reviseDraftNode 全行为、快照哈希与指纹的真实输入，并静态审计测试断言。

# 第一部分：给普通用户看的结论

## 1. 工具结果为什么会串路线？

工具结果进模型输入时只按项目取最近 5 条，不看路线。调用记录上没有路线字段，构建输入时也没有按路线过滤，所以 A 路线调的工具，B 路线下一轮也能看到。
好消息是工具结果只是参考资料，不会变成正式结论（结论只认问答链），而且旧存档不受影响，新任务才可能看到。

## 2. 到底哪些情况下应该共享？

在分支点之前产生的工具结果（两条分支共享的祖先处），两边都应该看到；在某条分支自己内部产生的，只有那条分支该看到；在共享节点上提取的内容，所有含该节点的路线看到是合理的。
一句话：谁的祖先，谁可见；分支私事，不互看。

## 3. Shared Node 改了会影响谁？

共享节点是同一个东西被多条路线引用，不是复制。只要它还能改（仅限草稿状态的用户知识节点），一改所有引用它的路线立刻都变。
已经确认的内容、已经作出的回答改不了，所以影响范围只限草稿。但草稿一旦被多条路线共享，改之前最好看一眼它被几条路线引用。
## 4. 旧节点内容变了以后，后面的节点会不会过期？

会，但系统发现不了。能改的只有草稿，改完后基于它写的后代节点还留在原地，下轮照样作为有效历史进 AI 上下文，模型看到的是新父加旧后代拼在一起的矛盾历史。
确认过的内容和答案改不了，所以这个问题只困在草稿范围内，影响可控但真实存在。

## 5. 当前系统能不能发现这种过期？

不能。上下文构建器只保证结构正确（链不断、路线对、快照可复现），不检查父改了后代还合不合理，也没有版本号、修订号这类机制。
它能保证的是另一件事：旧存档永远是旧样子（有测试覆盖），新任务看到新内容（也有测试覆盖）。过期本身不报错。

## 6. 哪个问题最值得先修？

工具结果按路线收敛。因为它是三个问题里唯一真正的跨信任边界泄漏：另一条路线的私密工具输出会出现在本路线的模型输入里。
共享传播和草稿过期都是设计语义范围内的事，靠产品提示和规范能管住；串线的工具结果靠规范管不住，必须改读取规则。

# 第二部分：技术证据

## A. Capability 全链路与归属字段

调用：ProposalActionExecutor.executeInvokeCapability 用键 run 加 runId 加 proposal 加幂等键，经 CapabilityRuntime.invoke 落库，runId 透传（测试里可为 null，列可空）。
落库：capability_invocations 只有 id、invocation_key（唯一）、project_id、run_id（可空）、capability_id、arguments、status、result、created_at、completed_at，没有 routeId、nodeId、snapshotId 列。
结果：result 内含 content、sourceRefs、provenance、warnings；内置资源摘录能力只放 2000 字有界摘录加截断标记，但框架层对通用能力 content 无大小约束，兜底只有冻结投影的总大小上限。
投影：AgentInputSnapshotBuilder.capabilityResults 取全项目最近完成 5 条（status 非 RUNNING，含 FAILED 与 REPLAYED，按 created_at 倒序），原样映射 content、sourceRefs、provenance 到 CapabilityResultView，模型可读完整内容。
归属是否可定：可以但要绕路。runId 可 join agent_runs 得 route_id 与 input_node_id；参数里的 node 引用被校验器限制在快照允许引用内；结果自带 sourceRefs（如 node 加某 ID）。三者结合足以定位产生语境，但构建器一行都没用。
## B. 可见性规则：不能简单按 routeId 过滤

设 A 到 B 到 C 为主链，B 处分出 D 到 E。结论：B 处产生的结果两条分支都该见（B 是共同祖先，证据与两边都相关）；C 处产生的结果 D 与 E 不该见（分支私事）；
共享节点 S 处提取的内容，所有含 S 的路线见是合理的（证据讲的就是 S 本身）；但若调用发生在路线 A 的问答语境里、问题只属于 A，则只 A 该见。
所以正确规则不是 WHERE route 等于当前路线，而是：观察引用的每个 node 引用都在本路线 lineage 上，或调用 run 的归属路线就是本路线（无引用时）。分支共享祖先自然双见，分支私有自然隔离。
遗留行（runId 为空的调用）无归属可查，只能二选一：继续项目可见，或收紧为不可见。定规则时必须先给它们名分。

## C. 泄漏实际影响

进入哪个字段：AgentInputSnapshot.capabilityResults 数组，每个元素带完整 content、sourceRefs、provenance，T3 测试证实新快照会带上、旧冻结不受影响。
模型能读原始内容：能，资源摘录是原文前 2000 字，通用能力无框架级长度约束。
分类：privacy leakage 为主（私密工具输出跨路线，MEDIUM）；context relevance pollution 次之（无关观察占上下文，LOW 到 MEDIUM）；
semantic decision pollution 轻（契约写明观察永不自动进入 claims，RequirementStateBuilder.buildForContext 只重放快照 patch，模型也不能拿观察 ID 当 claim 来源）；token pollution 轻（5 条上限加冻结大小上限）。
另有新鲜度缺口：快照哈希与可变源指纹都不含 capability 结果，新工具结果到达不改变 context_hash，也不让待执行提案变 stale，模型可能在新证据到达后仍按旧证据行动。
## D. Shared Node 全生命周期

节点何时变共享：没有任何 shared 标记列（Node 字段已在上一轮列全，无此列）。共享是推导的：当第二个路线的 tip lineage 包含该节点时，它就是共享的。
变共享的入口只有：从该节点建分支（含 continuation 历史分支、fork）、reanswer 与 replacement 建新路线时共享前缀。单路线追加永不产生共享。
如何知道被几条路线引用：无索引，只能遍历项目全部路线逐条走 lineage（isAnchorMemberOfAnyRoute 与读模型的 routesByNode 就是这么做的，复杂度为路线数乘深度）。
哪些 kind 可共享：全部。INTERACTION（共享问题，全局唯一答案）、KNOWLEDGE（含 DECISION 子类）、RESOURCE（摘录源共享）、ARTIFACT，lineage 与读写模型都不分 kind，读模型跨路线按 ID 去重展示一次。

## E. Shared 内容修改传播

设 Route A 为 S 到 A1 到 A2，Route B 为 S 到 B1 到 B2，S 是 PROPOSED 用户 KNOWLEDGE 草稿。修改 S 后：A1、A2、B1、B2 结构上全部继续有效，无 invalidation、无 revision、无通知。
updatedAt 变新，操作日志记 EDIT_DRAFT_NODE（含 before 与 after 的 subtype 加 content），无 revision ID。
snapshot hash 不变：context_hash 只哈希各类 ID 与 relations，不哈希节点正文，改正文前后新快照哈希相同（身份靠 snapshot_id 区分，这是设计）。
claims 与 provenance 不变：claims 只重放 patch，与节点正文无关；但 claim 文本可能从此与 S 正文矛盾，无人检查。
核心回答：父语义变后，已有 descendants 照样作为有效历史进入下轮 AI Context，系统不做任何语义一致性判断。

## F. 共享矩阵

（1）共享问题与答案：全局共享，题面不可改，答案全局唯一身份 immutable，不可编辑。
（2）共享 Decision：PROPOSED 且用户 authored 时可原地改；一旦 CONFIRMED 只走状态机转换（显式、记日志、可撤销补偿），不走自由编辑。
（3）共享 Knowledge 草稿：PROPOSED 加用户 authored 可原地改，这是唯一的洞。
（4）共享 confirmed claim：patch 行无更新接口（只有 save 与按 ID 查询），immutable，随 lineage 共享前缀走继承引用。
（5）共享 RESOURCE 与 ARTIFACT：正文不可原地改（revise 仅限 KNOWLEDGE），摘录能力读到什么取决于当时正文，旧冻结不受影响。
（6）收回（retracted）：软标记，仅叶子无答案可退；模型可见节点被收回会使待执行提案变 stale。
## G. 共享变私有：分叉路径是否够用

场景：共享的是所有用户必须登录，Enterprise 要企业 SSO 登录，Personal 保留邮箱登录。现状支持三条路：forkFromNode 建分支（共享前缀冻结、新节点各自长）；
reanswerFromNode 为旧题复制语义建新题节点（旧答案留源路线，新路线重答）；commitReplacementFromNode 建替换路线与替换节点并取代源路线。
结论：分叉语义够用，不需要原地分化共享节点。反模式是直接改共享草稿凑合，那会把 Enterprise 的 SSO 写进 Personal 的历史。

## H. reviseDraftNode 全行为

允许：同时满足未收回、authorKind 为 USER、kind 为 KNOWLEDGE、knowledgeStatus 为 PROPOSED。AGENT 写的 KNOWLEDGE、RESOURCE、ARTIFACT、INTERACTION 全都不可改，CONFIRMED 之后也不可改（测试 draftEditIsAllowedWhileProposedAndRejectedAfterwards 覆盖）。
改：仅 subtype（重走白名单校验）与 content；不动 question、purpose、options、parent、kind、author。updatedAt 变 now。
记：操作日志 EDIT_DRAFT_NODE 带 before 与 after 的 subtype 加 content；无 revision ID；不检查、不通知、不 invalidate 任何后代；撤销补偿要求节点仍是可编辑草稿否则拒绝。
对哈希：context_hash 输入只有各类 ID、relations 与 specialInputs，不含正文，改正文不改变哈希；但可变源指纹变，旧快照上的待执行提案在执行与确认时按 stale 拒绝。

## I. 语义过期案例

设 B 为登录必须使用邮箱，C 为因此发送邮箱验证码，D 为验证码有效 10 分钟。此时把 B 改成登录只使用手机号，再建新快照。
AI 实际看到：B（新，手机号）、C（旧，邮箱验证码）、D（旧，10 分钟），三者作为一条连贯历史并列呈现，无任何过期标记。后代不会被过滤、降权或标注。
定性：SEMANTIC_DESCENDANT_STALENESS，MEDIUM。影响半径被 PROPOSED 门禁收敛（确认过的内容改不了），但共享草稿可把 MEDIUM 放大到多条路线，需按 HIGH 的谨慎度提示用户。
## J. 答案与已确认态是否同病

不同病，范围明确。final answer：immutable，单终态，同路线重答拒绝，跨路线同题重答按 SHARED_STATE_DIVERGENCE 拒绝，并发经 node 行锁单赢者。
resolved decision 与 confirmed knowledge：正文不走自由编辑，只走显式状态转换（setKnowledgeStatus，记 before 与 after，撤销补偿要求状态未再变否则拒绝）；
replacement：新节点新 ID 带 supersedesNodeId，老节点保留；replacement route：新路线，老路线标 SUPERSEDED。
retracted：软标记，叶子无答案才可退。所以语义过期问题只局限在 PROPOSED 用户 KNOWLEDGE 草稿，其余一律走新节点、新路线、新身份。

## K. 旧快照冻旧文，新快照见新文

按冻结投影实现判断：旧 snapshot 重放的是冻结字节，不是活表。T1、T2、T6 测试分别覆盖主链节点、关联节点、浮动节点的改后重放仍见改前正文（before），而新快照见改后正文（after），断言精确到 body text 与 snapshotId。
T3 覆盖能力结果：冻结后完成的新结果不进旧回放，只进新快照。后代在新旧快照里都照常进入，无过滤。

## L. 系统只保结构一致，不保语义一致

ContextBuilder 无以下任何检查：祖先 updatedAt 与后代 createdAt 对比、无 revision 血缘（根本不存在 revision 概念）、supersedes 在 lineage 里故意忽略、knowledgeStatus 不参与是否纳入。
它保证的是 structural consistency：lineage 成员正确、缺失跨项目成环超深一律 fail-closed、快照可复现。不保证的是 mutable 祖先改后、后代是否还对得上，重要区别，使用方（prompt 与人工）须知。
## M. 不变量候选（仅建议，不实现）

（1）Shared confirmed state immutable：现状已成立，建议加架构测试锁死（无 patch 更新接口、无草稿改已确认路径）。
（2）共享草稿编辑前提示影响面：编辑时展示引用路线数，纯 UI 加只读查询即可，最便宜。
（3）有后代的可变祖先禁原地改：最强但兼容性差，会改变现有编辑 UX，需产品决策，暂不推荐直接上。
（4）编辑改走 revision 或新节点：与现有撤销补偿语义冲突（撤销是恢复旧正文），成本高于（3），不推荐。
（5）观察按 lineage 可见：推荐。用 run 归属路线加 sourceRefs 与参数引用定位产生语境，可见规则为引用的 node 全在本路线 lineage 上或调用归属路线即本路线；runId 为空的遗留行先定名分再收敛。
推荐顺序：（5）修泄漏，（2）做提示，（1）锁测试；（3）（4）维持现状门禁即可。

## N. 风险矩阵

capability cross-route observation：现状为项目级最近 5 条；影响为私密工具输出跨路线进模型输入；Severity 为 MEDIUM。
shared draft propagation：现状为同 ID 即时全员可见；影响为误改共享草稿污染多路线；Severity 为 MEDIUM（HIGH 谨慎度提示）。
descendant semantic staleness：现状为新父加旧后代照常进上下文；影响为模型基于矛盾历史决策；Severity 为 MEDIUM。
shared answer divergence：现状为全局唯一加读写双 fail-closed；影响为无（ invariant 成立）；Severity 为 LOW。
old snapshot mutation：现状为冻结回放加哈希校验；影响为无（测试覆盖）；Severity 为 LOW。
capability freshness gap：现状为新结果不改哈希不致 stale；影响为模型按旧证据行动；Severity 为 LOW。

## O. 测试覆盖（读断言）

capability route visibility：无。CapabilityIntegrationTest 全是单项目单路线，只断言摘录有界、来源引用、幂等回放、进入后继快照，无跨路线断言。
branch capability inheritance：无。shared-node draft propagation：无，只有分歧 invariant（SharedState 系列），无内容传播断言。
shared-node branch continuation：结构有（分支测试），内容传播无。mutable ancestor with descendants：无，revise 测试只断言单节点改后状态与已确认拒绝。
old snapshot after draft revision：强（T1、T2、T6 精确到 before）。new snapshot after draft revision：强（同测试精确到 after）。

## P. Verdicts

CAPABILITY_ROUTE_VISIBILITY = PARTIAL
SHARED_CONTENT_PROPAGATION = PARTIAL
DESCENDANT_SEMANTIC_STALENESS = MEDIUM
FROZEN_SNAPSHOT_CONTENT_SAFETY = STRONG
NEXT_FIX_PRIORITY = Capability observations follow lineage visibility

注释：CAPABILITY 取 PARTIAL 是因有界摘录、来源引用、冻结重放三重保护，只缺路线收敛；SHARED 取 PARTIAL 是因已确认态 SAFE_BY_DESIGN，缺口仅在 PROPOSED 草稿；
STALENESS 取 MEDIUM 是因影响半径被 PROPOSED 门禁收敛，多路线共享时按 HIGH 谨慎度对待。

## Q. 本轮未改动

未改动任何生产代码、prompt、ranking、eligibility、迁移与测试，未跑 full90，只新增本报告。观测缺口沿用上一轮三项，另加一项：capability 调用归属（run 到 route 的可视化）缺只读查询。

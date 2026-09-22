# Agent Memory + RAG V1

基于 `a9deda73e399f94fbebd40cb331736f753fe76a4` 的增量实现记录。

## 已冻结的边界

- Project 仍然使用现有 Workspace 数据模型，Route 仍然是数据库和代码中的
  Route；产品语义上它是一条 structured Conversation。
- `retrieval_entries` 是 PostgreSQL 中可删除、可重建的派生投影，不是事实源。
- 自动检索只在首次构造 `ContextSnapshot -> AgentInputSnapshot` 时执行；已有
  `snapshot_id` 的投影继续只从 `agent_input_projections` 回放。
- durable projection 新版本为 `agent-input-projection.v2`，读取兼容
  `agent-input-projection.v1` 和早期 `agent-input.v2`。
- `RetrievedContextItem` 只向 Brain 暴露 `sourceRef`、`sourceKind`、`scope`、
  `originRouteId`、`authority`、正文和 provenance；不会暴露 cosine、RRF 或
  embedding 向量。
- 每个检索结果都会加入 `allowedSourceRefs`，后续 Brain 引用仍由 Java
  Runtime whitelist 校验。

## 检索实现

首次 projection 会从 canonical Node、Answer、Claim 和 Resource 重建当前
Project 的检索投影。Resource 文本被确定性切分成带 `resourceId`、chunk index、
字符范围、URL/page 等位置元数据的 `RESOURCE_CHUNK`。

候选通道为：

- current Route source refs + bounded graph node candidates；
- PostgreSQL `tsvector` lexical search；
- PostgreSQL `pg_trgm` search；
- optional pgvector lane；

候选使用 Reciprocal Rank Fusion 合并，随后再按 Route / Project / Resource
scope 和 authority 做 Runtime 规则筛选。Vector provider 不可用时，Noop
gateway 让 lexical、trigram、graph 继续工作。

`memory.search` 是 read-only capability，强制 project ownership、Route scope
和最多 8 条/8000 字符的 bounded result；结果仍然带完整 source refs 和
provenance。

## 不能被本实现改变的事实

- 不会写回 Node、Answer、Patch、Route、GraphOperation 或 Resource canonical
  记录。
- 不会让 Project 检索结果自动成为当前 Route 的 confirmed fact；跨 Route
  结果带 `scope=PROJECT` 和其 `originRouteId`。
- Resource 文本是 untrusted external evidence，资料中的指令不会提升为
  system policy。
- secret-shaped source fields 和明显的 authorization/private-key 文本不会
  进入检索投影。

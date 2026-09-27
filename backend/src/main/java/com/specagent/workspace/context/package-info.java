/**
 * 文件名:package-info.java
 *
 * 包用途:context 包负责基于世系(lineage)的上下文快照构建。上下文从活跃
 * route 的 tip 节点出发,沿父世系回放得到;兄弟 route、被取代 route、已删除
 * route 默认排除。快照一经构建即冻结,供 Brain 推理复现。
 */
package com.specagent.workspace.context;
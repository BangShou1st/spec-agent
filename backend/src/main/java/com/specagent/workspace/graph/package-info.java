/**
 * 文件名:package-info.java
 *
 * 用途:graph 包说明——图工作区的命令层,包含事务化的图变更命令、
 * 语义关系,以及支撑 Undo/Redo 的类型化操作日志。
 *
 * 命令层独占图不变量(只追加的 lineage、非 tip 续写必须显式分支、
 * 不可变答案),每条持久变更都追加一条类型化的
 * {@link com.specagent.workspace.graph.GraphOperation}。这里不存放任何
 * 业务专属的节点命令;外部 agent 动作族经策略审批后映射到这些命令。
 */
package com.specagent.workspace.graph;

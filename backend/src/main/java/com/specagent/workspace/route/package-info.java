/**
 * 文件名:package-info.java
 *
 * 用途:route 包说明。覆盖路线生命周期:创建、分叉(fork)、重新生成
 * (regenerate)、恢复(restore)、归档(archive)、软删除。
 * 路线生命周期状态:open、superseded、archived、deleted。
 * 活跃路线由 Project.activeRouteId 记录,而不是由 Route.lifecycleStatus 表达。
 */
package com.specagent.workspace.route;
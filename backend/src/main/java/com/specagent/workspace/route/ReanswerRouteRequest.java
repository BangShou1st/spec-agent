package com.specagent.workspace.route;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 文件名:ReanswerRouteRequest.java
 *
 * 用途:对某个问题换一个答案重新作答的显式来源命令,从指定路线
 * (sourceRouteId)分叉出新路线继续探索,label 为可选备注。
 */
public record ReanswerRouteRequest(
        @NotNull(message = "must be provided")
        UUID sourceRouteId,
        @Size(max = 255, message = "must be at most 255 characters")
        String label) {
}

package com.specagent.agent.api;

import com.specagent.agent.runtime.AcceptedRunView;
import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunRecoveryService;
import com.specagent.agent.runtime.AnswerCycleRunCommandService;
import com.specagent.agent.runtime.UnresolvedFailureView;
import com.specagent.common.ApiException;
import com.specagent.common.PreciseConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:AgentRunRecoveryController.java
 *
 * 用途:任务级失败恢复的 HTTP 契约面。
 *
 * GET  /agent-runs/unresolved        未解决失败清单(服务端判定资格与动作)
 * POST /agent-runs/{runId}/retry     从失败任务身份重试(202 + 运行视图)
 *
 * 重试的身份来自服务端持久化,而不是客户端 payload;目标路线永远取自
 * 失败任务本身,绝不回退 Active/first/latest。双击/并发由确定性幂等键
 * 裁决——重复请求返回同一个 run。冲突(已在途/已被成功取代/目标已过期)
 * 以精确错误码返回,由前端映射为对应的用户动作。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-runs")
public class AgentRunRecoveryController {

    private final AgentRunRecoveryService recoveryService;
    private final AnswerCycleRunCommandService commandService;

    public AgentRunRecoveryController(AgentRunRecoveryService recoveryService,
                                      AnswerCycleRunCommandService commandService) {
        this.recoveryService = recoveryService;
        this.commandService = commandService;
    }

    @GetMapping("/unresolved")
    public List<UnresolvedFailureView> listUnresolved(@PathVariable UUID projectId) {
        return recoveryService.listUnresolved(projectId);
    }

    @PostMapping("/{runId}/retry")
    public ResponseEntity<?> retry(@PathVariable UUID projectId,
                                   @PathVariable UUID runId) {
        try {
            AgentRun run = recoveryService.retry(projectId, runId);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(AcceptedRunView.from(run, "CREATED"));
        } catch (ApiException | PreciseConflictException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            // 领域命令的 route/node 校验失败:恢复目标已不存在或已变化
            throw ApiException.conflict("STALE_RECOVERY_TARGET",
                    "The recovery target no longer exists");
        }
    }
}

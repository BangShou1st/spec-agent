package com.specagent.agent.runtime;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.route.RouteHistoryResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 文件名:AnswerProcessingGate.java
 *
 * 用途:"规格所要依据的状态是否还拖欠未处理回答"的共享判定器——命令入口
 * (入队 GENERATE_ARTIFACT run 之前)与执行器(领取并执行已排队 run 之前)
 * 使用完全相同的判定逻辑。
 *
 * 判定器读取路线的<em>有效</em>回答历史——路线自身回答 + 冻结的继承前缀
 * ({@code route_inherited_answers})——与 {@code ContextBuilder} 折入规格上下文
 * 的是同一集合。此前的两个检查各只看路线自身的 tip 回答,导致从"STATE_UPDATE
 * 从未完成"的已回答节点分叉时,分支可能发布一份静默遗漏用户继承回答的规格。
 *
 * 已有 {@code AnswerPatch} checkpoint 的回答视为通过:checkpoint 之后的
 * DECISION 失败或过期不会重新关闭该门(修复会复用 checkpoint;claims 已在
 * 状态之中)。
 *
 * 兄弟路线隔离:只判定本路线 tip 谱系上的回答,无关兄弟路线上未处理的
 * 回答绝不会阻塞本路线的生成。
 */
@Service
public class AnswerProcessingGate {

    private final AnswerService answerService;
    private final AnswerPatchService answerPatchService;
    private final RouteHistoryResolver routeHistoryResolver;

    public AnswerProcessingGate(AnswerService answerService,
                                AnswerPatchService answerPatchService,
                                RouteHistoryResolver routeHistoryResolver) {
        this.answerService = answerService;
        this.answerPatchService = answerPatchService;
        this.routeHistoryResolver = routeHistoryResolver;
    }

    /**
     * 返回该路线第一个从未落成 checkpoint 的有效回答,按根到 tip 的顺序排列;
     * 所有有效回答都已具备 {@code AnswerPatch} 时返回空。
     *
     * @param routeId 规格将要生成的来源路线
     * @param tipNodeId 路线当前 tip;规格上下文会重放其父代谱系
     */
    public Optional<Answer> firstUnprocessedAnswer(UUID routeId, UUID tipNodeId) {
        if (routeId == null || tipNodeId == null) {
            return Optional.empty();
        }
        List<UUID> lineage = routeHistoryResolver.resolveLineage(tipNodeId);
        for (Answer answer : routeHistoryResolver.resolveEffectiveAnswers(routeId, lineage)) {
            if (answerPatchService.findBySourceAnswerId(answer.id()).isEmpty()) {
                return Optional.of(answer);
            }
        }
        return Optional.empty();
    }
}

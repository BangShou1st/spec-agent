package com.specagent.workspace.answer;

import com.specagent.common.Ids;
import com.specagent.common.SharedQuestionStatePort;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:AnswerService.java
 *
 * 用途:记录不可变回答,并强制"每条 route 流程内只能定稿一次"的约束。回答
 * 一旦写入即不可变;重新作答必须走新建 route、替换节点或回答修订,绝不覆盖
 * 已有回答。它是 answer 包的应用服务层,衔接 REST 请求与仓储/端口。
 *
 * 共享状态不变量:一个规范化 Question 节点在全项目范围内只对应一个不可变的
 * 语义 Answer 身份。任何 route 已为该节点定稿 Answer 后,其他 route 不得在同一
 * 规范化节点上再定稿第二个 Answer——分支通过继承的 ref 引用同一 Answer,重新
 * 作答则创建新的 Question 节点(见
 * {@link SharedQuestionStatePort#validateSharedQuestionState})。
 */
@Service
public class AnswerService {

    private final AnswerRepository answerRepository;
    private final NodeRepository nodeRepository;
    private final SharedQuestionStatePort sharedQuestionStatePort;
    private final ProjectRowLockPort projectRowLock;
    private final AnswerIndexPort answerIndexPort;

    public AnswerService(AnswerRepository answerRepository,
                         NodeRepository nodeRepository,
                         SharedQuestionStatePort sharedQuestionStatePort,
                         ProjectRowLockPort projectRowLock,
                         AnswerIndexPort answerIndexPort) {
        this.answerRepository = answerRepository;
        this.nodeRepository = nodeRepository;
        this.sharedQuestionStatePort = sharedQuestionStatePort;
        this.projectRowLock = projectRowLock;
        this.answerIndexPort = answerIndexPort;
    }

    /**
     * 为一条 route 流程在节点上定稿不可变回答。
     *
     * 整个定稿过程在单个事务内完成。共享同一规范化 Question 的并发 route
     * 绝不能各自落库一条 Answer:先对规范化节点行加锁
     * ({@code SELECT ... FOR UPDATE}),再做节点维度的存在性复查,从而保证并发
     * 事务中恰好只有一个胜出,后来者都会经由
     * {@link SharedQuestionStatePort#validateSharedQuestionState} 的冲突路径感知
     * 已落库的 Answer,而不会插入第二个 Answer 身份。
     */
    @Transactional
    public Answer finalizeAnswer(UUID projectId,
                                 UUID routeId,
                                 UUID nodeId,
                                 String selectedOptionId,
                                 String freeText,
                                 String createdByUser) {
        return finalizeAnswerWithSelections(projectId, routeId, nodeId,
                selectedOptionId == null ? List.<String>of() : List.of(selectedOptionId),
                freeText, createdByUser);
    }

    /** 多选变体:{@code selectedOptionIds} 是按用户选择顺序排列的完整选择集。 */
    @Transactional
    public Answer finalizeAnswerWithSelections(UUID projectId,
                                               UUID routeId,
                                               UUID nodeId,
                                               List<String> selectedOptionIds,
                                               String freeText,
                                               String createdByUser) {
        if (answerRepository.existsByRouteAndNode(routeId, nodeId)) {
            throw new IllegalStateException(
                    "Answer already finalized for node " + nodeId + " in route " + routeId);
        }
        // 与 Undo/Redo 及其他项目级修改串行化:先锁项目行(与 Undo 路径的
        // project -> node 加锁顺序一致),使回答 INSERT 在项目行上的外键
        // key-share 锁,不可能与"已持有项目锁、正在等待该节点"的 Undo 互相死锁。
        projectRowLock.lockProject(projectId);
        // 对同一规范化节点的并发定稿串行化:加锁之后,下面的节点级存在性检查即为权威结论。
        nodeRepository.lockById(nodeId);
        // 被胜出的 Undo/Redo 回撤的节点绝不能再获得不可变 Answer。若缺少此检查,
        // 先提交回撤的 Undo 可能在竞态中输给随后的定稿,导致已回撤节点带着一条
        // 不可变 Answer(这正是 Undo/Redo 必须守住的不变量)。重读发生在节点锁
        // 之内,因此能看到权威的回撤状态。
        Node lockedNode = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        if (lockedNode.isRetracted()) {
            throw new IllegalStateException(
                    "RETRACTED_NODE_REFERENCE: cannot finalize an immutable Answer on a retracted node " + nodeId);
        }
        sharedQuestionStatePort.validateSharedQuestionState(projectId, nodeId);
        UUID answerId = Ids.random();
        Instant now = Instant.now();
        // The legacy column keeps the FIRST selected option so every existing
        // single-selection consumer reads the same value it always has.
        String firstSelectedOptionId = selectedOptionIds == null || selectedOptionIds.isEmpty()
                ? null : selectedOptionIds.get(0);
        Answer answer = new Answer(answerId, projectId, routeId, nodeId,
                firstSelectedOptionId, selectedOptionIds, freeText, createdByUser, now);
        answerRepository.save(answer);
        answerIndexPort.index(answer);
        return answer;
    }

    /**
     * 只读批量读取:一条 route 在给定节点集合上已定稿的回答。纯委托仓储查询;
     * 此处不应包含任何生命周期逻辑、复制、修改或回退。
     */
    public List<Answer> findAnswersForRouteAndNodeIds(UUID routeId, List<UUID> nodeIds) {
        return answerRepository.findByRouteAndNodeIds(routeId, nodeIds);
    }

    public Optional<Answer> getAnswer(UUID answerId) {
        return answerRepository.findById(answerId);
    }

    /**
     * 返回给定 route 与节点上唯一一条已定稿的回答,不存在则为空。
     */
    public Optional<Answer> findAnswerForNode(UUID routeId, UUID nodeId) {
        return answerRepository.findByRouteAndNodeIds(routeId, List.of(nodeId))
                .stream().findFirst();
    }

    /**
     * 只读存在性检查,服务于"单次定稿"不变量:判断给定 route 流程是否已在该
     * 节点上定稿过回答。
     */
    public boolean existsAnswerFor(UUID routeId, UUID nodeId) {
        return answerRepository.existsByRouteAndNode(routeId, nodeId);
    }
}

package com.specagent.retrieval.index;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerIndexPort;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeIndexPort;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchIndexPort;
import org.springframework.stereotype.Service;

/**
 * 文件名:RetrievalIndexService.java
 *
 * 用途:增量派生索引的应用层边界适配器。规范化写入方(Node/Answer/
 * AnswerPatch 的写服务)只依赖各自窄的出站端口,本服务实现这些端口,
 * 把新写入的领域对象投影进检索索引。
 *
 * 它只拥有检索投影,绝不调用嵌入服务(向量增强是独立环节)。
 */
@Service
public class RetrievalIndexService implements NodeIndexPort, AnswerIndexPort, AnswerPatchIndexPort {

    private final RetrievalSourceProjector projector;

    public RetrievalIndexService(RetrievalSourceProjector projector) {
        this.projector = projector;
    }

    @Override
    public void index(Node node) {
        projector.indexNode(node);
    }

    @Override
    public void index(Answer answer) {
        projector.indexAnswer(answer);
    }

    @Override
    public void index(AnswerPatch patch) {
        projector.indexPatch(patch);
    }
}

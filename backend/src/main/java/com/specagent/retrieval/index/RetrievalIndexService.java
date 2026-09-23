package com.specagent.retrieval.index;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerIndexPort;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeIndexPort;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchIndexPort;
import org.springframework.stereotype.Service;

/**
 * Application-boundary adapter for incremental derived indexing. Canonical
 * writers depend only on their own narrow outbound ports; this service owns
 * the retrieval projection and never calls an embedding provider.
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

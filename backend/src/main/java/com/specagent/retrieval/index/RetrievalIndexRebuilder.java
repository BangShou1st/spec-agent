package com.specagent.retrieval.index;

import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 文件名:RetrievalIndexRebuilder.java
 *
 * 用途:重建派生检索投影的运维入口,支持按项目、按来源、或全量
 * 重建检索索引(当索引逻辑变更或数据不一致时使用)。
 */
@Service
public class RetrievalIndexRebuilder {

    private final RetrievalSourceProjector projector;
    private final ProjectRepository projectRepository;

    public RetrievalIndexRebuilder(RetrievalSourceProjector projector,
                                   ProjectRepository projectRepository) {
        this.projector = projector;
        this.projectRepository = projectRepository;
    }

    public void rebuildProject(UUID projectId) {
        projector.rebuildProject(projectId);
    }

    public void rebuildSource(UUID projectId, String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new IllegalArgumentException("sourceRef is required");
        }
        projector.rebuildSource(projectId, sourceRef);
    }

    public void rebuildAll() {
        for (Project project : projectRepository.findAll()) {
            projector.rebuildProject(project.id());
        }
    }
}

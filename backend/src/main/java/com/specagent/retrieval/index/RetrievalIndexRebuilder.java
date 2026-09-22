package com.specagent.retrieval.index;

import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Operational entry point for rebuilding the derived retrieval projection. */
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

    /** Source rebuild currently uses the same deterministic project rebuild. */
    public void rebuildSource(UUID projectId, String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new IllegalArgumentException("sourceRef is required");
        }
        projector.rebuildProject(projectId);
    }

    public void rebuildAll() {
        for (Project project : projectRepository.findAll()) {
            projector.rebuildProject(project.id());
        }
    }
}

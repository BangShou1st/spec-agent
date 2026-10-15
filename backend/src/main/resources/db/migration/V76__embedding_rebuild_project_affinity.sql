-- Installation HELP is the sole non-project corpus accepted by this rebuild service.
ALTER TABLE embedding_rebuilds ADD COLUMN project_id UUID GENERATED ALWAYS AS (
    CASE WHEN corpus_id='a71ae401-fff4-3f8a-82f1-023ab37608da'::uuid THEN NULL ELSE corpus_id END
) STORED;
ALTER TABLE embedding_rebuilds ADD CONSTRAINT embedding_rebuild_project_same_org
    FOREIGN KEY(project_id,org_id) REFERENCES projects(id,org_id) ON DELETE CASCADE;

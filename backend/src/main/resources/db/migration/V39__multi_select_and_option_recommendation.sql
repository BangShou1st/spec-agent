-- Multi-select questions and option recommendations.
--
-- allow_multi_select: an INTERACTION node whose answer is not necessarily a
-- single option; the UI renders checkboxes and the answer may carry several
-- selected option ids. Default FALSE keeps every existing question
-- single-select.
--
-- answers.selected_option_ids: the authoritative full selection for an answer.
-- The legacy selected_option_id column stays the FIRST selected option so all
-- existing single-selection consumers keep their semantics unchanged.
ALTER TABLE nodes
    ADD COLUMN allow_multi_select BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE answers
    ADD COLUMN selected_option_ids JSONB;

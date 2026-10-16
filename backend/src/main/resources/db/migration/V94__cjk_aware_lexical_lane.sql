-- P3-A: CJK-aware lexical lane.
--
-- Root cause (measured on the frozen 52-query calibration set): the lexical lane
-- indexed Chinese with `to_tsvector('simple', ...)`. The `simple` configuration has
-- no CJK segmenter, so it keeps each punctuation/whitespace-delimited Chinese run as
-- ONE whole-sentence token:
--
--   to_tsvector('simple','用户取消后不得继续追加回答正文。规则编号 REQ-107。')
--     -> '-107':4 'req':3 '用户取消后不得继续追加回答正文':1 '规则编号':2
--
-- Consequence: any Chinese query that is not a byte-identical whole sentence cannot
-- match (measured: 0 hits across all rows for ordinary Chinese paraphrases), while
-- ASCII identifiers keep working because the tokenizer does split on non-CJK.
--
-- Fix: store the document vector augmented with CJK character bigrams and OR the
-- query's bigram terms into the query tsquery. Bigrams are the standard
-- segmenter-free approach for CJK and match the existing pg_trgm byte-level lane's
-- granularity. ASCII behaviour is deliberately untouched: for a query with no CJK
-- characters the bigram set is empty, so `cjk_tsquery` degrades to exactly the old
-- `plainto_tsquery('simple', ...)` semantics.
--
-- This changes a rebuildable derived projection only: `retrieval_entries` is derived
-- data (canonical graph/answer/patch/resource rows remain the source of truth), and
-- no fact, scope, authority or authorization rule is altered.

CREATE OR REPLACE FUNCTION cjk_bigrams(txt text) RETURNS text AS $$
DECLARE
    out text := '';
    run text := '';
    ch text;
    i int;
    j int;
    stripped text;
BEGIN
    IF txt IS NULL THEN
        RETURN '';
    END IF;
    -- Punctuation/whitespace separates runs; only CJK runs are expanded.
    stripped := regexp_replace(txt, '[[:punct:][:space:]]+', ' ', 'g');
    FOR i IN 1..char_length(stripped) LOOP
        ch := substr(stripped, i, 1);
        IF ch ~ '[\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff]' THEN
            run := run || ch;
        ELSE
            IF char_length(run) >= 2 THEN
                FOR j IN 1..(char_length(run) - 1) LOOP
                    out := out || substr(run, j, 2) || ' ';
                END LOOP;
            END IF;
            run := '';
        END IF;
    END LOOP;
    IF char_length(run) >= 2 THEN
        FOR j IN 1..(char_length(run) - 1) LOOP
            out := out || substr(run, j, 2) || ' ';
        END LOOP;
    END IF;
    RETURN btrim(out);
END
$$ LANGUAGE plpgsql IMMUTABLE;

-- Query side: keep the old ASCII AND semantics, OR in the CJK bigram alternatives.
CREATE OR REPLACE FUNCTION cjk_tsquery(txt text) RETURNS tsquery AS $$
DECLARE
    acc text := '';
    tok text;
    base tsquery;
BEGIN
    base := plainto_tsquery('simple', txt);
    FOR tok IN SELECT DISTINCT value
               FROM regexp_split_to_table(cjk_bigrams(txt), '\s+') AS value
               WHERE value <> ''
               ORDER BY value LOOP
        acc := acc || CASE WHEN acc = '' THEN '' ELSE ' | ' END || quote_literal(tok);
    END LOOP;
    IF acc = '' THEN
        RETURN base;
    END IF;
    -- `||` on tsquery is OR: ASCII tokens keep their AND-of-plainto semantics,
    -- CJK bigrams contribute recall.
    RETURN base || to_tsquery('simple', acc);
END
$$ LANGUAGE plpgsql IMMUTABLE;

-- Rebuild the derived vector with bigrams. The dropped/recreated column makes
-- PostgreSQL recompute every stored row in one pass.
DROP INDEX IF EXISTS idx_retrieval_entries_search_vector;
ALTER TABLE retrieval_entries DROP COLUMN search_vector;
ALTER TABLE retrieval_entries ADD COLUMN search_vector TSVECTOR GENERATED ALWAYS AS (
    to_tsvector('simple',
        coalesce(content, '') || ' ' || cjk_bigrams(coalesce(content, '')))
) STORED;
CREATE INDEX idx_retrieval_entries_search_vector
    ON retrieval_entries USING GIN (search_vector);

COMMENT ON FUNCTION cjk_bigrams(text) IS
    'P3-A: CJK character bigrams; empty for non-CJK input so ASCII behaviour is unchanged.';
COMMENT ON COLUMN retrieval_entries.search_vector IS
    'Lexical vector over content plus CJK bigrams (see cjk_tsquery).';

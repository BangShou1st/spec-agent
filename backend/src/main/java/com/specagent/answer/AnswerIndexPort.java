package com.specagent.answer;

/** Narrow outbound port for rebuildable projections interested in Answer writes. */
public interface AnswerIndexPort {

    void index(Answer answer);
}

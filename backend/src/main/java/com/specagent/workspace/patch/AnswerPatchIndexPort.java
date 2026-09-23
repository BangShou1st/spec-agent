package com.specagent.workspace.patch;

/** Narrow outbound port for rebuildable projections interested in Patch writes. */
public interface AnswerPatchIndexPort {

    void index(AnswerPatch patch);
}

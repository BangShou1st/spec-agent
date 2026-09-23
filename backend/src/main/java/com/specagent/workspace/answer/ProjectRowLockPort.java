package com.specagent.workspace.answer;

import java.util.UUID;

/**
 * Write-side port for the project row lock used when finalizing answers.
 *
 * <p>{@code AnswerService} only needs to serialize finalization on the
 * project row; depending on the full project repository (while route history
 * depends on answers) closes the answer -&gt; project -&gt; route -&gt; answer
 * cycle. Implemented by the project-side {@code ProjectRepository}.
 */
public interface ProjectRowLockPort {

    /** Blocks until this project's row lock is held (FOR UPDATE). */
    void lockProject(UUID projectId);
}

package com.specagent.workspace.answer;

import java.util.UUID;

/**
 * 文件名:ProjectRowLockPort.java
 *
 * 用途:定稿回答时所用的项目行锁的写侧端口。{@code AnswerService} 只需要在
 * 项目行上把定稿操作串行化;若直接依赖完整的项目仓储(而 route 历史又依赖
 * 回答),会形成 answer -&gt; project -&gt; route -&gt; answer 的依赖环,故抽出此
 * 窄端口。由 project 侧的 {@code ProjectRepository} 实现。
 */
public interface ProjectRowLockPort {

    /** 阻塞直到持有该项目的行锁(FOR UPDATE)。 */
    void lockProject(UUID projectId);
}

package com.specagent.workspace.patch;

/**
 * 文件名:AnswerPatchIndexPort.java
 *
 * 用途:面向"可重建投影"的窄出站端口,关注 Patch 的写入事件。
 */
public interface AnswerPatchIndexPort {

    void index(AnswerPatch patch);
}

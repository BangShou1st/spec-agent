package com.specagent.workspace.node;

/**
 * 文件名:NodeIndexPort.java
 *
 * 用途:面向"可重建投影"的窄出站端口,关注 Node 的写入事件。
 * 接口由 Node 领域自己拥有,这样检索(retrieval)包就无法对规范写入
 * 路径形成依赖环。
 */
public interface NodeIndexPort {

    void index(Node node);
}

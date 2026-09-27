package com.specagent.workspace.project;

/**
 * 文件名:DuplicateProjectTitleException.java
 *
 * 用途:项目标题与其他项目重复时抛出的异常。标题比较不区分大小写,
 * 与 {@code ProjectRepository#findByTitleContaining} 的搜索语义一致。
 * 删除项目会释放其标题:唯一性只约束"当前存在的项目",
 * 从不约束"曾经用过的标题"。
 */
public class DuplicateProjectTitleException extends RuntimeException {

    public DuplicateProjectTitleException(String title) {
        super("Project title already exists: " + title);
    }
}

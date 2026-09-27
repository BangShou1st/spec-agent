package com.specagent.agent.protocol;

import java.util.UUID;

/**
 * 文件名:OptionView.java
 *
 * 用途:节点选项的视图——Runtime 独有的选项 id 加上展示用的
 * 标签文本。
 */
public record OptionView(UUID id, String label) {
}

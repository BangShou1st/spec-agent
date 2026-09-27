package com.specagent.agent.protocol;

import java.util.UUID;

/**
 * 文件名:ClaimView.java
 *
 * 用途:面向模型的 claim(断言)视图,只包含内容与出处。
 *
 * 约束:Runtime 独有的 claim id 绝不出现在线上(wire)契约中;
 * 响应里一旦携带 claim id,将被 fail-closed 拒绝。
 */
public record ClaimView(String kind,
                        String text,
                        String status,
                        Double confidence,
                        UUID sourceNodeId,
                        UUID sourceAnswerId) {
}

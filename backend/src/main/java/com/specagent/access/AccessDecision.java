package com.specagent.access;

/**
 * 文件名:AccessDecision.java
 *
 * 用途:一次授权判定的结果,包含稳定原因码。
 *
 * 原因码必须是<b>可审计且可测试</b>的稳定字符串,不用自然语言描述替代,
 * 因为验收矩阵要按原因码区分"未认证""未选组织""非成员""角色不足"
 * "资源不可见"等不同失败,而不是只断言返回了 403。
 *
 * @param allowed    是否放行
 * @param reasonCode 稳定原因码({@link AccessReason} 的常量之一)
 */
public record AccessDecision(boolean allowed, String reasonCode) {

    public static AccessDecision allow() {
        return new AccessDecision(true, AccessReason.ALLOWED);
    }

    public static AccessDecision deny(String reasonCode) {
        return new AccessDecision(false, reasonCode);
    }

    public AccessDecision {
        if (allowed && !AccessReason.ALLOWED.equals(reasonCode)) {
            throw new IllegalArgumentException("an allowing decision must use the ALLOWED reason");
        }
        if (!allowed && AccessReason.ALLOWED.equals(reasonCode)) {
            throw new IllegalArgumentException("a denying decision must carry a failure reason");
        }
    }
}

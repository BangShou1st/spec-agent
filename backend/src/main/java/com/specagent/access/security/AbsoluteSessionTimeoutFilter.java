package com.specagent.access.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 文件名:AbsoluteSessionTimeoutFilter.java
 *
 * 用途:绝对登录期限(第二阶段 §6,初值 8 小时)。
 *
 * 与空闲超时的区别:空闲超时由共享会话存储的 TTL 决定,只要用户活跃就会
 * 顺延;绝对期限从登录时刻起算,<b>不因活跃而延长</b>。两者都必须存在——
 * 只有空闲超时会让一个长期活跃的会话永不重认证。
 *
 * 超时后销毁会话并清理安全上下文,然后返回 JSON 401(由
 * {@link AccessErrorResponses} 写出),而不是重定向:前端据此明确要求重登录。
 *
 * 初值 8 小时是待评估参数,不是承载承诺。
 */
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    static final String LOGIN_AT_ATTRIBUTE = "spec.agent.security.loginAt";

    private final Duration absoluteTimeout;
    private final AccessErrorResponses errorResponses;

    public AbsoluteSessionTimeoutFilter(Duration absoluteTimeout, AccessErrorResponses errorResponses) {
        this.absoluteTimeout = absoluteTimeout;
        this.errorResponses = errorResponses;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null || !isAuthenticatedUser()) {
            filterChain.doFilter(request, response);
            return;
        }
        long now = System.currentTimeMillis();
        Object recorded = session.getAttribute(LOGIN_AT_ATTRIBUTE);
        if (!(recorded instanceof Long loginAt)) {
            // 首次见到该会话的已认证请求:以此刻为登录时刻起算。
            session.setAttribute(LOGIN_AT_ATTRIBUTE, now);
            filterChain.doFilter(request, response);
            return;
        }
        if (now - loginAt > absoluteTimeout.toMillis()) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            errorResponses.commence(request, response,
                    new InsufficientAuthenticationException(
                            "Absolute session lifetime exceeded; sign in again"));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}

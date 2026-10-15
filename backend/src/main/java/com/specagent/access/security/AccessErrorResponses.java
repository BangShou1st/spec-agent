package com.specagent.access.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.common.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 文件名:AccessErrorResponses.java
 *
 * 用途:未认证/无权在 Spring Security 过滤链内的 JSON 响应(第二阶段 §6)。
 *
 * 契约与业务错误一致:前端永远看到 {@link ApiErrorResponse} 形状,
 * 而不是登录页重定向或 HTML 错误页——登录过期必须返回 JSON 401,
 * 前端据此明确要求重新登录。
 *
 * 无权(403)在这里只覆盖"过滤链层面"的拒绝(例如 CSRF 失败后的访问、
 * 被 denyAll 的内部端点)。业务资源的可见性判定发生在应用层,那里会抛出
 * {@code AccessDeniedException} 并由 web 边界映射为 403/404——两者最终
 * 收敛到同一份错误契约。
 */
@Component
public class AccessErrorResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public AccessErrorResponses(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required; sign in again");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                "This operation is not permitted for the current principal");
    }

    private void write(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(code, message));
    }
}

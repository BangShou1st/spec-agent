package com.specagent.access.security;

import com.specagent.access.AccessMode;
import com.specagent.access.ActorContext;
import com.specagent.access.ActorContextHolder;
import com.specagent.access.IdentityModeProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.web.csrf.CsrfToken;

/**
 * 文件名:AccessSecurityController.java
 *
 * 用途:前端引导所需的两个只读端点。
 *
 * <ul>
 *   <li>{@code GET /api/csrf} —— 取 CSRF token 与回填位置。前端在同源下
 *       读取非 HttpOnly 的 cookie 或直接调用本端点,再回填请求头。它必须
 *       在<b>登录前</b>可达,否则登录表单本身无法通过 CSRF 校验。</li>
 *   <li>{@code GET /api/session} —— 会话与模式概览,供前端决定显示登录页
 *       还是应用壳。</li>
 * </ul>
 *
 * token 通过请求属性读取而不是声明为方法参数,原因是本地模式<b>关闭</b>了
 * CSRF,此时 {@code CsrfToken} 参数没有解析来源;声明成参数会让本地模式的
 * 引导端点直接失败,而本地模式正是最常用的交付形态。这里显式回答
 * {@code required=false} 而不是报错。
 *
 * 放在 {@code access.security} 而不是 {@code access.api},是因为它需要
 * Spring Security 的 {@link CsrfToken} 类型——按门禁,只有本包允许
 * 依赖 Spring Security。
 */
@RestController
public class AccessSecurityController {
    private final OidcLoginMetadata loginMetadata;

    private final IdentityModeProperties properties;

    public AccessSecurityController(IdentityModeProperties properties,OidcLoginMetadata loginMetadata) {
        this.properties = properties;
        this.loginMetadata=loginMetadata;
    }

    @GetMapping("/api/csrf")
    public Map<String, Object> csrf(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        boolean required = properties.accessMode() == AccessMode.OIDC;
        body.put("required", required);
        if (attribute instanceof CsrfToken token) {
            body.put("headerName", token.getHeaderName());
            body.put("parameterName", token.getParameterName());
            body.put("token", token.getToken());
        } else {
            // 本地模式:变更类请求不需要 token。仍然返回头名,让前端代码
            // 只有一条路径(空 token 时前端不做回填)。
            body.put("headerName", "X-XSRF-TOKEN");
            body.put("parameterName", "_csrf");
            body.put("token", "");
        }
        return body;
    }

    @GetMapping("/api/session")
    public Map<String, Object> session() {
        ActorContext actor = ActorContextHolder.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", properties.accessMode().name());
        body.put("loginUrl",loginMetadata.loginUrl());
        body.put("requiresLogin", properties.accessMode() == AccessMode.OIDC);
        body.put("authenticated", actor != null);
        body.put("orgSelected", actor != null && actor.hasOrg());
        return body;
    }
}

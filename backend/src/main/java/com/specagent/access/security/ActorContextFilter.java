package com.specagent.access.security;

import com.specagent.access.AccessMode;
import com.specagent.access.AccessResourceRepository;
import com.specagent.access.ActorContext;
import com.specagent.access.ActorContextHolder;
import com.specagent.access.ActorSource;
import com.specagent.access.IdentityModeProperties;
import com.specagent.access.identity.IdentityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 文件名:ActorContextFilter.java
 *
 * 用途:把已验证的会话解析成 {@link ActorContext} 并绑定到当前线程。
 *
 * 三条不可妥协的规则:
 *
 * <ol>
 *   <li><b>主体只从已验证会话解析</b>。请求头里的 orgId 只是"选择条件",
 *       必须与数据库中的有效成员资格比对后才生效;不一致时得到
 *       {@code ORG_SELECTION_REQUIRED} 而不是被静默接受。</li>
 *   <li><b>组织切换是显式请求上下文</b>(每请求头),不是一个 session 内
 *       可变的活动组织——后者会让同一浏览器不同标签页互相串组织。</li>
 *   <li><b>LOCAL 模式也是有身份的主体</b>:显式本地 user + 显式本地 org。
 *       它不是匿名管理员,禁用该用户后其权限立即失效。</li>
 * </ol>
 *
 * 组织成员资格的读取直接使用 {@link AccessResourceRepository}:这属于
 * "授权侧的读事实",与判定共用同一份查询,避免两处过滤条件漂移。
 */
public class ActorContextFilter extends OncePerRequestFilter {

    /** 显式组织选择的请求头。缺失时有且仅有一个有效成员资格才自动采用。 */
    public static final String ORG_HEADER = "X-Spec-Agent-Org-Id";

    private final IdentityModeProperties properties;
    private final IdentityService identity;
    private final AccessResourceRepository resources;

    public ActorContextFilter(IdentityModeProperties properties, IdentityService identity,
                              AccessResourceRepository resources) {
        this.properties = properties;
        this.identity = identity;
        this.resources = resources;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ActorContext context = resolve(request);
        if (context == null) {
            // 未认证:不绑定任何主体,让后续判定以 UNAUTHENTICATED 失败关闭,
            // 而不是让"没有主体"退化成"没有限制"。
            filterChain.doFilter(request, response);
            return;
        }
        var session=request.getSession(false);
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(context);
             var browser=com.specagent.access.BrowserSessionContext.bind(session==null?null:session.getId())) {
            filterChain.doFilter(request, response);
        }
    }

    private ActorContext resolve(HttpServletRequest request) {
        if (properties.accessMode() == AccessMode.LOCAL) {
            return identity.localActor();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            String issuer = oidcUser.getIssuer() == null ? null : oidcUser.getIssuer().toString();
            if (issuer == null || issuer.isBlank()) {
                return null;
            }
            UUID userId = identity.resolveOrCreateUser(
                    issuer, oidcUser.getSubject(), displayNameOf(oidcUser));
            return new ActorContext(userId, selectOrg(request, userId), ActorSource.OIDC);
        }
        return null;
    }

    private String displayNameOf(OidcUser oidcUser) {
        String name = oidcUser.getFullName();
        if (name == null || name.isBlank()) {
            name = oidcUser.getPreferredUsername();
        }
        return name == null || name.isBlank() ? oidcUser.getSubject() : name;
    }

    /**
     * 解析本次请求生效的组织。
     *
     * 未显式指定时:恰好一个有效成员资格则自动采用;零个或多个则不做选择
     * (多组织用户必须显式选择,绝不猜)。显式指定的组织若不在有效成员资格
     * 内,同样不生效——避免"猜到别人的组织"。
     */
    private UUID selectOrg(HttpServletRequest request, UUID userId) {
        List<AccessResourceRepository.MembershipFacts> memberships =
                resources.findActiveMemberships(userId);
        String raw = request.getHeader(ORG_HEADER);
        if (raw != null && !raw.isBlank()) {
            UUID requested;
            try {
                requested = UUID.fromString(raw.trim());
            } catch (IllegalArgumentException ex) {
                return null;
            }
            return memberships.stream().anyMatch(m -> m.orgId().equals(requested))
                    ? requested
                    : null;
        }
        return memberships.size() == 1 ? memberships.get(0).orgId() : null;
    }
}

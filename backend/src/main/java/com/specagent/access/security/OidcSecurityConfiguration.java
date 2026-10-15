package com.specagent.access.security;

import com.specagent.access.AccessResourceRepository;
import com.specagent.access.IdentityModeProperties;
import com.specagent.access.identity.IdentityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 文件名:OidcSecurityConfiguration.java
 *
 * 用途:OIDC 多人模式的安全链。
 *
 * 关键取舍:
 *
 * <ul>
 *   <li><b>浏览器同源访问后端</b>,由后端完成登录,使用 HttpOnly 会话
 *       cookie。OIDC token 绝不放进 localStorage、URL、Agent snapshot
 *       或 Python 进程。</li>
 *   <li><b>CSRF 对变更类请求开启</b>,并且<b>不</b>把全部 {@code /api/**}
 *       设为例外。SPA 通过 {@code GET /api/csrf} 取 token(见
 *       {@code AccessSecurityController});token 存放在非 HttpOnly 的
 *       cookie 里由前端读取后回填请求头。</li>
 *   <li><b>登录过期/用户禁用返回 JSON 401</b>,不重定向到登录页——前端
 *       据此明确要求重新登录。</li>
 *   <li><b>绝对登录期限</b>由 {@link AbsoluteSessionTimeoutFilter} 承担,
 *       空闲超时由 {@code spring.session.timeout} 承担。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "spec.agent.security.mode", havingValue = "OIDC")
public class OidcSecurityConfiguration {

    @Bean
    @Order(2)
    SecurityFilterChain oidcFilterChain(HttpSecurity http,
                                        IdentityModeProperties properties,
                                        IdentityService identity,
                                        AccessResourceRepository resources,
                                        AccessErrorResponses errorResponses) throws Exception {
        ActorContextFilter actorContextFilter =
                new ActorContextFilter(properties, identity, resources);
        AbsoluteSessionTimeoutFilter absoluteTimeoutFilter =
                new AbsoluteSessionTimeoutFilter(properties.getAbsoluteTimeout(), errorResponses);

        // token 必须在每个请求上尽早解析,否则 CookieCsrfTokenRepository
        // 不会写出 cookie,SPA 永远取不到 token。
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
                .securityMatcher("/**")
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/csrf", "/api/session", "/error").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(login -> login
                        .defaultSuccessUrl("/", false))
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setStatus(HttpStatus.NO_CONTENT.value());
                        }))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errorResponses)
                        .accessDeniedHandler(errorResponses))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .addFilterBefore(absoluteTimeoutFilter, AnonymousAuthenticationFilter.class)
                .addFilterAfter(actorContextFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}

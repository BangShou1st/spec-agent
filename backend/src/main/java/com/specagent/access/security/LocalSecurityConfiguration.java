package com.specagent.access.security;

import com.specagent.access.AccessResourceRepository;
import com.specagent.access.IdentityModeProperties;
import com.specagent.access.identity.IdentityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * 文件名:LocalSecurityConfiguration.java
 *
 * 用途:LOCAL 单机模式的安全链。
 *
 * 本地模式的访问控制由<b>回环绑定</b>({@code server.address} 默认
 * 127.0.0.1)与<b>显式本地主体</b>共同承担,两者缺一不可:
 *
 * <ul>
 *   <li>这条链本身不要求登录——本地开发与既有测试因此不需要登录流程。
 *       但它<b>不是</b>匿名管理员:每个请求都被绑定到一个显式的本地
 *       user/org,该用户可被禁用,其组织成员资格可被撤销。</li>
 *   <li>CSRF 在本地模式关闭:同源回环 + 无跨站可达面,开启只会让既有
 *       客户端全部失效。OIDC 模式必须开启(见 OidcSecurityConfiguration)。</li>
 *   <li>把回环绑定改为对外监听<b>之前</b>必须先把授权层做实,且必须
 *       切换到 OIDC——直接改绑定会让这条无登录链暴露到网络上。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "spec.agent.security.mode", havingValue = "LOCAL",
        matchIfMissing = true)
public class LocalSecurityConfiguration {

    @Bean
    @Order(2)
    SecurityFilterChain localFilterChain(HttpSecurity http, IdentityModeProperties properties,
                                         IdentityService identity,
                                         AccessResourceRepository resources) throws Exception {
        ActorContextFilter actorContextFilter =
                new ActorContextFilter(properties, identity, resources);
        http
                .securityMatcher("/**")
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterAfter(actorContextFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 抑制 Boot 的默认用户自动配置。
     *
     * 存在的唯一理由是避免 Boot 生成随机密码并打印到日志——LOCAL 模式的
     * 主体来自显式的本地 user/org,不来自 UserDetails。空实现因此是
     * 正确的语义,而不是占位。
     */
    @Bean
    UserDetailsService localModeUserDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}

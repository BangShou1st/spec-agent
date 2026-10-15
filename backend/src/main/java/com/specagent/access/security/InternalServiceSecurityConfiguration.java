package com.specagent.access.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 文件名:InternalServiceSecurityConfiguration.java
 *
 * 用途:内部服务链的独立安全链(第二阶段 §6、§7)。
 *
 * 设计明确:内部服务链<b>不依赖浏览器 cookie</b>,配置单独安全链。因此
 * 这条链:
 *
 * <ul>
 *   <li>不创建会话(STATELESS)、不需要登录、不校验 CSRF;</li>
 *   <li>认证仍由内部 token(控制器内的 {@code authorized(token)})承担。</li>
 * </ul>
 *
 * <b>如实声明</b>:内部 token 的正确性只说明"请求来自受信任服务",
 * 绝不单独构成业务授权。这一层在 P2-B 保持与改造前逐字一致的行为,
 * 追加 task token 与 ActorContext 校验属于 P2-D(见 P2-A 清单 G4/G5)。
 * 不把这条链改成 denyAll ——那会切断 Python brain 的合法回调,而真实
 * 缺口(不绑定业务授权)依然存在。
 */
@Configuration
public class InternalServiceSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain internalServiceFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}

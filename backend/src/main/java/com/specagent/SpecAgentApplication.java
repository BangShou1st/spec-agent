package com.specagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 文件名:SpecAgentApplication.java
 *
 * 用途:整个后端的 Spring Boot 启动类。组件扫描从 {@code com.specagent}
 * 包根开始,自动装配全部业务模块(agent、workspace、retrieval、connection、
 * mcp 等),通过 {@link SpringApplication#run} 启动内嵌服务器对外提供 REST API。
 */
@SpringBootApplication
public class SpecAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpecAgentApplication.class, args);
    }
}
package com.specagent.web;

import com.specagent.model.contract.ModelGatewayErrorCategory;
import com.specagent.model.contract.ModelGatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:GatewayErrorAdviceTest.java
 *
 * 用途:锁定「提供商错误分类 -> HTTP 状态与稳定错误码」这条边界契约。
 *
 * 这条契约是 Provider 连通性语义在服务端的落点,前端据此区分:
 * 上游被限流(429)、上游拒绝凭据(503)、上游不可达(502)、上游超时(504)。
 * 特别地:上游拒绝凭据绝不能映射成 401 —— 401 在本系统里唯一表示
 * 「用户自己的会话失效」,两者混为一谈会让上游错误被当成会话过期。
 */
class GatewayErrorAdviceTest {

    private final GatewayErrorAdvice advice = new GatewayErrorAdvice();

    private HttpStatus statusOf(ModelGatewayErrorCategory category, Integer upstreamStatus) {
        var response = advice.handleGatewayFailure(
                new ModelGatewayException(category, "upstream failure", upstreamStatus));
        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(response.getBody()).isNotNull();
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    private String codeOf(ModelGatewayErrorCategory category, Integer upstreamStatus) {
        var response = advice.handleGatewayFailure(
                new ModelGatewayException(category, "upstream failure", upstreamStatus));
        assertThat(response.getBody()).isNotNull();
        return response.getBody().code();
    }

    @Test
    void aRateLimitedUpstreamBecomesItsOwnStableCodeAndStatus() {
        assertThat(statusOf(ModelGatewayErrorCategory.RATE_LIMITED, 429)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(codeOf(ModelGatewayErrorCategory.RATE_LIMITED, 429)).isEqualTo("MODEL_PROVIDER_RATE_LIMITED");
    }

    @Test
    void aRejectedUpstreamCredentialIsNeverMappedToAUserSessionUnauthorized() {
        // 上游回了 401/403:请求已经到达上游,失败在凭据。
        assertThat(statusOf(ModelGatewayErrorCategory.AUTHENTICATION, 401))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(statusOf(ModelGatewayErrorCategory.AUTHENTICATION, 403))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(codeOf(ModelGatewayErrorCategory.AUTHENTICATION, 401))
                .isEqualTo("MODEL_PROVIDER_AUTHENTICATION");
    }

    @Test
    void anUnreachableUpstreamAndATimeoutKeepTheirOwnStableCodes() {
        assertThat(statusOf(ModelGatewayErrorCategory.CONNECTION, null)).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(codeOf(ModelGatewayErrorCategory.CONNECTION, null)).isEqualTo("MODEL_PROVIDER_UNREACHABLE");
        assertThat(statusOf(ModelGatewayErrorCategory.TIMEOUT, null)).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(codeOf(ModelGatewayErrorCategory.TIMEOUT, null)).isEqualTo("MODEL_PROVIDER_TIMEOUT");
    }

    @Test
    void anUpstreamServerErrorKeepsItsOwnStableCode() {
        assertThat(statusOf(ModelGatewayErrorCategory.SERVER_ERROR, 500)).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(codeOf(ModelGatewayErrorCategory.SERVER_ERROR, 500)).isEqualTo("MODEL_PROVIDER_ERROR");
    }

    @Test
    void theMappedMessageNeverEchoesTheUpstreamPayload() {
        var response = advice.handleGatewayFailure(
                new ModelGatewayException(ModelGatewayErrorCategory.RATE_LIMITED, "raw upstream payload", 429));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("The model provider is temporarily rate limited");
        assertThat(response.getBody().message()).doesNotContain("raw upstream payload");
    }
}

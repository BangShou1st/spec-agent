package com.specagent.model.provider;

import com.specagent.model.contract.ModelGatewayErrorCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:HttpErrorShapesTest.java
 *
 * 用途:锁定「上游 HTTP 状态 -> 提供商中立错误分类」这条通用分类器的行为。
 *
 * 这条分类器是 Provider 连通性语义的唯一真相来源:它决定一次上游应答
 * 到底表示「可达」「可达但被限流」「认证失败」还是「根本没到达上游」。
 * 前端的可达性判定必须以这里的分类为准,绝不按裸 HTTP 状态猜测。
 */
class HttpErrorShapesTest {

    private static ModelGatewayErrorCategory categoryOf(int status) {
        return HttpErrorShapes.map(status, "", "probe").gatewayCategory();
    }

    @Test
    void aRateLimitedUpstreamIsItsOwnCategoryAndKeepsTheUpstreamStatus() {
        var failure = HttpErrorShapes.map(429, "slow down", "probe");

        // 429 表示上游可达但被限流:它绝不是连接失败,也绝不是认证失败。
        assertThat(failure.gatewayCategory()).isEqualTo(ModelGatewayErrorCategory.RATE_LIMITED);
        assertThat(failure.httpStatus()).isEqualTo(429);
    }

    @Test
    void aRejectedCredentialIsAnAuthenticationFailureAndKeepsTheUpstreamStatus() {
        for (int status : new int[] { 401, 403 }) {
            var failure = HttpErrorShapes.map(status, "nope", "probe");

            // 请求已经到达上游:失败在认证/权限,不是「不可达」。
            assertThat(failure.gatewayCategory()).isEqualTo(ModelGatewayErrorCategory.AUTHENTICATION);
            assertThat(failure.httpStatus()).isEqualTo(status);
        }
    }

    @Test
    void anUpstreamServerErrorIsItsOwnCategory() {
        for (int status : new int[] { 500, 502, 503 }) {
            assertThat(categoryOf(status)).isEqualTo(ModelGatewayErrorCategory.SERVER_ERROR);
        }
    }

    @Test
    void otherStatusesAreClassifiedByTheErrorContractAndNeverSilentlyAccepted() {
        assertThat(categoryOf(404)).isEqualTo(ModelGatewayErrorCategory.INVALID_MODEL);
        assertThat(categoryOf(400)).isEqualTo(ModelGatewayErrorCategory.PROVIDER_REQUEST_ERROR);
        assertThat(categoryOf(422)).isEqualTo(ModelGatewayErrorCategory.PROVIDER_REQUEST_ERROR);
        assertThat(categoryOf(418)).isEqualTo(ModelGatewayErrorCategory.PROVIDER_REQUEST_ERROR);
    }

    @Test
    void theFailureMessageNeverEchoesTheRawBodyBeyondTheBoundedSnippet() {
        var failure = HttpErrorShapes.map(429, "x".repeat(1000), "probe");

        assertThat(failure.getMessage()).contains("HTTP 429");
        assertThat(failure.getMessage().length()).isLessThan(400);
    }
}

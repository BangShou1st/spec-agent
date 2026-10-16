package com.specagent.eval;

import com.specagent.eval.P3AClaimGrounding.AnswerRow;
import com.specagent.eval.P3AClaimGrounding.ClaimView;
import com.specagent.eval.P3AClaimGrounding.NodeRow;
import com.specagent.eval.P3AClaimGrounding.PatchSource;
import com.specagent.eval.P3AClaimGrounding.RouteRow;
import com.specagent.eval.P3AClaimGrounding.SourceCatalog;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:P3AClaimGroundingTest.java
 *
 * 测试目标:P3-A B 层 CONFIRMED claim 来源校验的确定性用例,直接锁定
 * {@link P3AClaimGrounding} 的判定边界:
 *   正确来源 / 缺失来源 / 引用不存在的 Answer / 错项目来源 / 错路线来源 /
 *   已失效来源。
 *
 * 纯内存目录,不启动 Spring,不接触数据库。
 */
class P3AClaimGroundingTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID OTHER_PROJECT = UUID.randomUUID();
    private static final UUID ROUTE = UUID.randomUUID();
    private static final UUID OTHER_ROUTE = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID ANSWER = UUID.randomUUID();

    /** 可变的纯内存来源目录。 */
    private static final class MemoryCatalog implements SourceCatalog {
        final Map<UUID, AnswerRow> answers = new HashMap<>();
        final Map<UUID, NodeRow> nodes = new HashMap<>();
        final Map<UUID, RouteRow> routes = new HashMap<>();

        @Override
        public AnswerRow findAnswer(UUID id) {
            return answers.get(id);
        }

        @Override
        public NodeRow findNode(UUID id) {
            return nodes.get(id);
        }

        @Override
        public RouteRow findRoute(UUID id) {
            return routes.get(id);
        }
    }

    private static MemoryCatalog consistentCatalog() {
        MemoryCatalog catalog = new MemoryCatalog();
        catalog.answers.put(ANSWER, new AnswerRow(ANSWER, PROJECT, ROUTE, NODE));
        catalog.nodes.put(NODE, new NodeRow(NODE, PROJECT));
        catalog.routes.put(ROUTE, new RouteRow(ROUTE, PROJECT, "open"));
        return catalog;
    }

    private static PatchSource patch() {
        return new PatchSource(PROJECT, ROUTE, NODE, ANSWER);
    }

    private static ClaimView confirmed(UUID sourceNodeId, UUID sourceAnswerId) {
        return new ClaimView("fact", "CONFIRMED", "用户确认的需求", sourceNodeId, sourceAnswerId);
    }

    @Test
    void validClaimSourceIsGrounded() {
        MemoryCatalog catalog = consistentCatalog();
        // claim 自身引用与 patch 的权威来源一致。
        assertThat(P3AClaimGrounding.isGrounded(patch(),
                confirmed(NODE, ANSWER), catalog)).isTrue();
    }

    @Test
    void claimWithoutOwnSourceFallsBackToAuthoritativePatchSource() {
        MemoryCatalog catalog = consistentCatalog();
        // 缺失 claim 自身引用:回退到 patch 的权威来源——使用不同字段表达,
        // 不要求所有来源都用同一种字段。
        assertThat(P3AClaimGrounding.isGrounded(patch(),
                confirmed(null, null), catalog)).isTrue();
    }

    @Test
    void missingSourceIsNotGrounded() {
        MemoryCatalog catalog = consistentCatalog();
        // claim 与 patch 都拿不出完整的来源 -> 不 grounded。
        PatchSource missing = new PatchSource(PROJECT, ROUTE, null, null);
        assertThat(P3AClaimGrounding.isGrounded(missing,
                confirmed(null, null), catalog)).isFalse();
    }

    @Test
    void referenceToNonexistentAnswerIsNotGrounded() {
        MemoryCatalog catalog = consistentCatalog();
        // 引用一条不存在的 Answer(patch 权威来源缺失指向它)。
        PatchSource dangling = new PatchSource(PROJECT, ROUTE, NODE, UUID.randomUUID());
        assertThat(P3AClaimGrounding.isGrounded(dangling,
                confirmed(null, null), catalog)).isFalse();
    }

    @Test
    void wrongProjectSourceIsNotGrounded() {
        MemoryCatalog catalog = consistentCatalog();
        // Answer 属于别的 project。
        catalog.answers.put(ANSWER, new AnswerRow(ANSWER, OTHER_PROJECT, ROUTE, NODE));
        assertThat(P3AClaimGrounding.isGrounded(patch(),
                confirmed(null, null), catalog)).isFalse();

        // Node 属于别的 project(恢复 Answer 归属,只让 Node 错)。
        catalog.answers.put(ANSWER, new AnswerRow(ANSWER, PROJECT, ROUTE, NODE));
        catalog.nodes.put(NODE, new NodeRow(NODE, OTHER_PROJECT));
        assertThat(P3AClaimGrounding.isGrounded(patch(),
                confirmed(null, null), catalog)).isFalse();
    }

    @Test
    void wrongRouteSourceIsNotGrounded() {
        MemoryCatalog catalog = consistentCatalog();
        // Answer 位于另一条 route(与 patch 声明的 route 不符)。
        catalog.answers.put(ANSWER, new AnswerRow(ANSWER, PROJECT, OTHER_ROUTE, NODE));
        assertThat(P3AClaimGrounding.isGrounded(patch(),
                confirmed(null, null), catalog)).isFalse();
    }

    @Test
    void claimSourceNotAnchoredToPatchIsNotGrounded() {
        MemoryCatalog catalog = consistentCatalog();
        // claim 自带的来源指向别处(不锚定 patch 的权威来源)。
        UUID strayAnswer = UUID.randomUUID();
        UUID strayNode = UUID.randomUUID();
        catalog.answers.put(strayAnswer, new AnswerRow(strayAnswer, PROJECT, ROUTE, strayNode));
        catalog.nodes.put(strayNode, new NodeRow(strayNode, PROJECT));
        assertThat(P3AClaimGrounding.isGrounded(patch(),
                confirmed(strayNode, strayAnswer), catalog)).isFalse();
    }

    @Test
    void invalidatedRouteSourceIsNotGrounded() {
        MemoryCatalog catalog = consistentCatalog();
        // route 已 superseded -> 来源失效。
        catalog.routes.put(ROUTE, new RouteRow(ROUTE, PROJECT, "superseded"));
        assertThat(P3AClaimGrounding.isGrounded(patch(),
                confirmed(NODE, ANSWER), catalog)).isFalse();
        catalog.routes.put(ROUTE, new RouteRow(ROUTE, PROJECT, "deleted"));
        assertThat(P3AClaimGrounding.isGrounded(patch(),
                confirmed(NODE, ANSWER), catalog)).isFalse();
    }

    @Test
    void ungroundedConfirmedReturnsOnlyUnbackedConfirmedClaims() {
        MemoryCatalog catalog = consistentCatalog();
        catalog.routes.put(ROUTE, new RouteRow(ROUTE, PROJECT, "archived"));
        List<ClaimView> claims = List.of(
                confirmed(NODE, ANSWER),                                    // CONFIRMED 但来源失效
                new ClaimView("fact", "UNRESOLVED", "还没定的需求", NODE, ANSWER));
        assertThat(P3AClaimGrounding.ungroundedConfirmed(patch(), claims, catalog))
                .extracting(ClaimView::text)
                .containsExactly("用户确认的需求");
    }
}

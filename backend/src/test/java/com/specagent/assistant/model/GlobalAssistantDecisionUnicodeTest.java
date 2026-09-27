package com.specagent.assistant.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
/**
 * 文件名:GlobalAssistantDecisionUnicodeTest.java
 *
 * 测试目标:权威解析/校验路径必须拒绝 Jackson 本身会接受的
 * 未配对代理字符;合法的成对 emoji 则正常通过。
 */
class GlobalAssistantDecisionUnicodeTest {
    private final GlobalAssistantDecisionParser parser =
            new GlobalAssistantDecisionParser(new ObjectMapper());
    private final GlobalAssistantDecisionValidator validator = new GlobalAssistantDecisionValidator();
    private void rejects(String json) {
        String content;
        try {
            content = parser.parse(json).assistantText();
        } catch (GlobalAssistantModelException ex) {
            return;
        }
        assertThatThrownBy(() -> validator.validate(parser.parse(json)))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test void escapedHighPlusNormalCharRejected() {
        rejects("{\"kind\":\"FINAL\",\"assistantText\":\"\\uD83DA\"}");
    }
    @Test void escapedLoneLowRejected() {
        rejects("{\"kind\":\"FINAL\",\"assistantText\":\"\\uDE00\"}");
    }
    @Test void literalUnpairedRejected() {
        assertThatThrownBy(() -> validator.validate(new GlobalAssistantDecision(
                GlobalAssistantDecision.DecisionKind.FINAL, "x\uD83DA", null, null)))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
    @Test void validEmojiAccepted() {
        GlobalAssistantDecision d = parser.parse("{\"kind\":\"FINAL\",\"assistantText\":\"hi \\uD83D\\uDE00\"}");
        validator.validate(d);
        assertThat(d.assistantText()).isEqualTo("hi \uD83D\uDE00");
    }
}

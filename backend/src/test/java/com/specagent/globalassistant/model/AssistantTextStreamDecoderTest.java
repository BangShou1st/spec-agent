package com.specagent.globalassistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Generic incremental decode: structure only, never prompt or scenario content. */
class AssistantTextStreamDecoderTest {

    private static String stream(String full, int... cuts) {
        AssistantTextStreamDecoder d = new AssistantTextStreamDecoder();
        StringBuilder out = new StringBuilder();
        int prev = 0;
        for (int cut : cuts) { out.append(d.append(full.substring(prev, cut)).releasableText()); prev = cut; }
        out.append(d.append(full.substring(prev)).releasableText());
        return out.toString();
    }

    @Test void finalTextAcrossChunks() {
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"Hello streaming world\"}";
        assertThat(stream(full, 10, 25, 40)).isEqualTo("Hello streaming world");
    }

    @Test void kindAfterTextStillReleases() {
        String full = "{\"assistantText\":\"Hi there\",\"kind\":\"FINAL\"}";
        assertThat(stream(full, 15, 30)).isEqualTo("Hi there");
    }

    @Test void clarifyReleases() {
        assertThat(stream("{\"kind\":\"CLARIFY\",\"assistantText\":\"Which one?\"}", 12)).isEqualTo("Which one?");
    }

    @Test void toolNeverReleases() {
        String full = "{\"kind\":\"TOOL\",\"toolRequest\":{\"capabilityId\":\"project.search\"}}";
        assertThat(stream(full, 10, 20, 30)).isEmpty();
    }

    @Test void unknownKindBuffers() {
        assertThat(stream("{\"kind\":\"SOMEDAY\",\"assistantText\":\"Hi\"}", 12)).isEmpty();
    }

    @Test void nonStringTextReleasesNothing() {
        assertThat(stream("{\"kind\":\"FINAL\",\"assistantText\":42}", 20)).isEmpty();
    }

    @Test void bracesInsideTextAreContent() {
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"a {b} [c]\"}";
        assertThat(stream(full, 20, 35)).isEqualTo("a {b} [c]");
    }

    @Test void whitespaceAndExtraFields() {
        String full = "{ \"kind\" : \"FINAL\" , \"extra\" : 12, \"assistantText\" : \"ok\" }";
        assertThat(stream(full, 10, 25, 45)).isEqualTo("ok");
    }

    @Test void truncatedYieldsPrefixOnly() {
        AssistantTextStreamDecoder d = new AssistantTextStreamDecoder();
        assertThat(d.append("{\"kind\":\"FINAL\",\"assistantText\":\"par").releasableText()).isEqualTo("par");
    }

    @Test void navigateTextReleasesPerContract() {
        String full = "{\"kind\":\"NAVIGATE\",\"assistantText\":\"go\",\"uiAction\":{\"destination\":\"PROJECTS\"}}";
        assertThat(stream(full, 15, 30)).isEqualTo("go");
    }

    @Test void emptyAndNullFragments() {
        AssistantTextStreamDecoder d = new AssistantTextStreamDecoder();
        assertThat(d.append("").releasableText()).isEmpty();
        assertThat(d.append(null).releasableText()).isEmpty();
    }

    @Test void escapedQuoteSplitAcrossBoundary() {
        // Raw JSON text value: Say \"hi\" now
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"Say \\\"hi\\\" now\"}";
        int cut = full.indexOf("hi") - 1;
        assertThat(stream(full, cut, cut + 3)).isEqualTo("Say \"hi\" now");
    }

    @Test void backslashNewlineAndUnicode() {
        // Raw JSON text value: a\\b<newline>c<\u4f60>d
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"a\\\\b\\nc\\u4f60d\"}";
        assertThat(stream(full, 20, 30, 40)).isEqualTo("a\\b\nc\u4f60d");
    }

    @Test void unicodeEscapeSplitMidSequence() {
        // Truly raw JSON: the runtime string carries literal backslash-u sequences.
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"\\u4f60\\u597d\"}";
        int cut = full.indexOf("\\u597d") + 2;
        assertThat(stream(full, cut)).isEqualTo("\u4f60\u597d");
    }

    @Test void escapedHighPlusNormalCharReleasesNoUnpaired() {
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"hi \\uD83DA\"}";
        int cut = full.indexOf("\\uD83DA") + 7;
        AssistantTextStreamDecoder d = new AssistantTextStreamDecoder();
        String r1 = d.append(full.substring(0, cut)).releasableText();
        assertThat(r1).isEqualTo("hi ");
        assertThat(hasUnpairedSurrogate(r1)).isFalse();
        String r2 = d.append(full.substring(cut)).releasableText();
        assertThat(r2).isEmpty();
        assertThat(hasUnpairedSurrogate(r2)).isFalse();
    }

    @Test void escapedLoneLowNeverReleased() {
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"a\\uDE00b\"}";
        assertMalformedEmitsNoUnpaired(full);
        assertThat(stream(full, 20)).isEqualTo("a");
    }

    @Test void literalHighPlusNormalCharHeld() {
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"x\uD83DA\"}";
        int cut = full.indexOf("\uD83D") + 1;
        AssistantTextStreamDecoder d = new AssistantTextStreamDecoder();
        String r1 = d.append(full.substring(0, cut)).releasableText();
        assertThat(r1).isEqualTo("x");
        assertThat(hasUnpairedSurrogate(r1)).isFalse();
        String r2 = d.append(full.substring(cut)).releasableText();
        assertThat(hasUnpairedSurrogate(r2)).isFalse();
        assertThat(hasUnpairedSurrogate(r1 + r2)).isFalse();
    }

    @Test void literalLoneLowNeverReleased() {
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"a\uDE00b\"}";
        assertMalformedEmitsNoUnpaired(full);
    }

    @Test void highHighLowFailsClosed() {
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"\\uD83D\\uD83D\\uDE00\"}";
        assertMalformedEmitsNoUnpaired(full);
        AssistantTextStreamDecoder d = new AssistantTextStreamDecoder();
        String r = d.append(full).releasableText();
        assertThat(r).isEmpty();
    }

    private static void assertMalformedEmitsNoUnpaired(String full) {
        for (int cut = 0; cut <= full.length(); cut++) {
            AssistantTextStreamDecoder d = new AssistantTextStreamDecoder();
            String r1 = d.append(full.substring(0, cut)).releasableText();
            String r2 = d.append(full.substring(cut)).releasableText();
            assertThat(hasUnpairedSurrogate(r1)).as("first delta at split %s", cut).isFalse();
            assertThat(hasUnpairedSurrogate(r2)).as("second delta at split %s", cut).isFalse();
        }
    }

    private static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)
                    && (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1)))) {
                return true;
            }
            if (Character.isLowSurrogate(c)
                    && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)))) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoUnpairedAtAnySplit(String full, String expected) {
        for (int cut = 0; cut <= full.length(); cut++) {
            AssistantTextStreamDecoder d = new AssistantTextStreamDecoder();
            String r1 = d.append(full.substring(0, cut)).releasableText();
            String r2 = d.append(full.substring(cut)).releasableText();
            assertThat(hasUnpairedSurrogate(r1)).as("first delta at split %s", cut).isFalse();
            assertThat(hasUnpairedSurrogate(r2)).as("second delta at split %s", cut).isFalse();
            assertThat(r1 + r2).as("reassembled at split %s", cut).isEqualTo(expected);
        }
    }

    @Test void rawUnicodeEscapeSurrogatePairNeverSplits() {
        // Raw JSON: literal backslash-u sequences, NOT Java unicode escapes.
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"hi \\uD83D\\uDE00 bye\"}";
        assertNoUnpairedAtAnySplit(full, "hi \uD83D\uDE00 bye");
    }

    @Test void rawSurrogateEscapeCompletedThenLowPending() {
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"\\uD83D\\uDE00\"}";
        int cut = full.indexOf("\\uDE00");
        AssistantTextStreamDecoder d = new AssistantTextStreamDecoder();
        String r1 = d.append(full.substring(0, cut)).releasableText();
        assertThat(hasUnpairedSurrogate(r1)).isFalse();
        String r2 = d.append(full.substring(cut)).releasableText();
        assertThat(r1 + r2).isEqualTo("\uD83D\uDE00");
    }

    @Test void literalEmojiSplitAtUtf16Boundary() {
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"a\uD83D\uDE00b\"}";
        assertNoUnpairedAtAnySplit(full, "a\uD83D\uDE00b");
        int emojiHigh = full.indexOf("\uD83D");
        AssistantTextStreamDecoder d = new AssistantTextStreamDecoder();
        String r1 = d.append(full.substring(0, emojiHigh + 1)).releasableText();
        assertThat(hasUnpairedSurrogate(r1)).isFalse();
    }

    @Test void multipleEmojiAndEscapesAtEverySplit() {
        String full = "{\"kind\":\"FINAL\",\"assistantText\":\"\\uD83D\\uDE00 x \\\"q\\\" \\\\ \\n \\uD83C\\uDF89!\"}";
        assertNoUnpairedAtAnySplit(full, "\uD83D\uDE00 x \"q\" \\ \n \uD83C\uDF89!");
    }
}

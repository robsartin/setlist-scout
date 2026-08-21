package com.robsartin.setlistscout.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link AnthropicMessages}, the single reader of an Anthropic Messages API
 * response shared by every LLM caller (#215). Each case here pins a behaviour that used to be
 * hand-rolled three times, once wrong in two of the three copies for months before anyone
 * noticed (#211, #213) -- {@code content.get(0)} instead of scanning for the first block actually
 * typed {@code "text"}.
 */
class AnthropicMessagesTest {

    // ---- textBlock: the cases every original call site checked by hand ----------------------

    @Test
    @DisplayName("returns empty, not null, when the response itself is null")
    void nullResponseReturnsEmpty() {
        assertThat(AnthropicMessages.textBlock(null)).isEmpty();
    }

    @Test
    @DisplayName("returns empty when content is absent from the response")
    void absentContentReturnsEmpty() {
        assertThat(AnthropicMessages.textBlock(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("returns empty when content is present but empty")
    void emptyContentReturnsEmpty() {
        assertThat(AnthropicMessages.textBlock(Map.of("content", List.of()))).isEmpty();
    }

    @Test
    @DisplayName("returns the text of a single text-typed block")
    void returnsTextOfSingleTextBlock() {
        Map<String, Object> response = Map.of(
                "content", List.of(Map.of("type", "text", "text", "Bon Iver\nFleet Foxes")));

        assertThat(AnthropicMessages.textBlock(response)).contains("Bon Iver\nFleet Foxes");
    }

    @Test
    @DisplayName("skips a leading thinking block and reads the text block that follows it (#211)")
    void skipsLeadingThinkingBlock() {
        Map<String, Object> response = Map.of(
                "content", List.of(
                        Map.of("type", "thinking", "thinking", "reasoning..."),
                        Map.of("type", "text", "text", "the answer")));

        assertThat(AnthropicMessages.textBlock(response)).contains("the answer");
    }

    @Test
    @DisplayName("returns empty when every block is typed something other than text (#211)")
    void returnsEmptyWhenNoBlockIsTypedText() {
        Map<String, Object> response = Map.of(
                "content", List.of(Map.of("type", "thinking", "thinking", "reasoning...")));

        assertThat(AnthropicMessages.textBlock(response)).isEmpty();
    }

    @Test
    @DisplayName("returns a present-but-blank value when the text block legitimately parses to nothing")
    void returnsBlankTextWhenTextBlockIsLegitimatelyEmpty() {
        Map<String, Object> response = Map.of("content", List.of(Map.of("type", "text", "text", "")));

        assertThat(AnthropicMessages.textBlock(response)).contains("");
    }

    @Test
    @DisplayName("mutation guard: a same-shaped response where the FIRST block is thinking and the SECOND "
            + "is text must not be satisfied by returning the first block regardless of type")
    void doesNotJustReturnTheFirstBlock() {
        Map<String, Object> response = Map.of(
                "content", List.of(
                        Map.of("type", "thinking", "thinking", "not this"),
                        Map.of("type", "text", "text", "this one")));

        Optional<String> result = AnthropicMessages.textBlock(response);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("this one").isNotEqualTo("not this");
    }

    // ---- stopReason ---------------------------------------------------------------------------

    @Test
    @DisplayName("stopReason returns empty when the response is null")
    void stopReasonNullResponseReturnsEmpty() {
        assertThat(AnthropicMessages.stopReason(null)).isEmpty();
    }

    @Test
    @DisplayName("stopReason returns empty when stop_reason is absent")
    void stopReasonAbsentReturnsEmpty() {
        assertThat(AnthropicMessages.stopReason(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("stopReason returns the value when present")
    void stopReasonReturnsValueWhenPresent() {
        assertThat(AnthropicMessages.stopReason(Map.of("stop_reason", "max_tokens")))
                .contains("max_tokens");
    }

    // ---- THINKING_DISABLED ----------------------------------------------------------------------

    @Test
    @DisplayName("THINKING_DISABLED is the {\"type\": \"disabled\"} request entry")
    void thinkingDisabledIsTheRightShape() {
        assertThat(AnthropicMessages.THINKING_DISABLED).containsExactly(Map.entry("type", "disabled"));
    }
}

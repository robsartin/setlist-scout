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

    // ---- #253: structured name lists via tool use ----------------------------------------

    @Test
    @DisplayName("nameList reads the strings from the named tool_use block")
    void nameListReadsToolUseBlock() {
        Map<String, Object> response = Map.of("content", List.of(
                Map.of("type", "tool_use", "name", "record_artists",
                        "input", Map.of("artists", List.of("Bon Iver", "Fleet Foxes")))));

        assertThat(AnthropicMessages.nameList(response, "record_artists", "artists"))
                .contains(List.of("Bon Iver", "Fleet Foxes"));
    }

    @Test
    @DisplayName("nameList finds the tool_use block even when a thinking block precedes it")
    void nameListSkipsPrecedingBlocks() {
        Map<String, Object> response = Map.of("content", List.of(
                Map.of("type", "thinking", "thinking", "considering..."),
                Map.of("type", "text", "text", "Here are 8 similar artists:"),
                Map.of("type", "tool_use", "name", "record_artists",
                        "input", Map.of("artists", List.of("The National")))));

        assertThat(AnthropicMessages.nameList(response, "record_artists", "artists"))
                .contains(List.of("The National"));
    }

    @Test
    @DisplayName("nameList returns an empty list -- present, not absent -- when the model names none")
    void nameListEmptyArrayIsPresent() {
        Map<String, Object> response = Map.of("content", List.of(
                Map.of("type", "tool_use", "name", "record_artists",
                        "input", Map.of("artists", List.of()))));

        assertThat(AnthropicMessages.nameList(response, "record_artists", "artists"))
                .contains(List.of());
    }

    @Test
    @DisplayName("nameList is absent when no tool_use block was produced -- the failure case (#213)")
    void nameListAbsentWhenNoToolUse() {
        Map<String, Object> response = Map.of(
                "content", List.of(Map.of("type", "text", "text", "I could not do that.")),
                "stop_reason", "max_tokens");

        assertThat(AnthropicMessages.nameList(response, "record_artists", "artists")).isEmpty();
    }

    @Test
    @DisplayName("nameList is absent for a tool_use block with a different name")
    void nameListAbsentForOtherTool() {
        Map<String, Object> response = Map.of("content", List.of(
                Map.of("type", "tool_use", "name", "something_else",
                        "input", Map.of("artists", List.of("Bon Iver")))));

        assertThat(AnthropicMessages.nameList(response, "record_artists", "artists")).isEmpty();
    }

    @Test
    @DisplayName("nameList is absent on a null response, and when input or the field is missing")
    void nameListAbsentOnMalformedShapes() {
        assertThat(AnthropicMessages.nameList(null, "record_artists", "artists")).isEmpty();
        assertThat(AnthropicMessages.nameList(Map.of(), "record_artists", "artists")).isEmpty();
        assertThat(AnthropicMessages.nameList(Map.of("content", List.of(
                Map.of("type", "tool_use", "name", "record_artists"))),
                "record_artists", "artists")).isEmpty();
        assertThat(AnthropicMessages.nameList(Map.of("content", List.of(
                Map.of("type", "tool_use", "name", "record_artists", "input", Map.of()))),
                "record_artists", "artists")).isEmpty();
    }

    @Test
    @DisplayName("nameList drops blank entries and trims, but keeps punctuation inside real names")
    void nameListTrimsWithoutMangling() {
        Map<String, Object> response = Map.of("content", List.of(
                Map.of("type", "tool_use", "name", "record_artists",
                        "input", Map.of("artists", java.util.Arrays.asList(
                                "  Does It Offend You, Yeah?  ", "", "   ", null,
                                "Grover Washington, Jr.", "I See Hawks in L.A.")))));

        assertThat(AnthropicMessages.nameList(response, "record_artists", "artists"))
                .contains(List.of("Does It Offend You, Yeah?", "Grover Washington, Jr.",
                        "I See Hawks in L.A."));
    }

    @Test
    @DisplayName("nameListTool builds a tool definition whose input schema requires the field")
    void nameListToolShape() {
        Map<String, Object> tool = AnthropicMessages.nameListTool("record_artists", "artists", "why");

        assertThat(tool.get("name")).isEqualTo("record_artists");
        assertThat(tool.get("description")).isEqualTo("why");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tool.get("input_schema");
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("required")).isEqualTo(List.of("artists"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> field = (Map<String, Object>) props.get("artists");
        assertThat(field.get("type")).isEqualTo("array");
        assertThat(((Map<?, ?>) field.get("items")).get("type")).isEqualTo("string");
    }

    @Test
    @DisplayName("forceTool names the tool the model must call")
    void forceToolShape() {
        assertThat(AnthropicMessages.forceTool("record_artists"))
                .isEqualTo(Map.of("type", "tool", "name", "record_artists"));
    }
}

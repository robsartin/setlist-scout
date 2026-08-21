package com.robsartin.setlistscout.shared;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the parts of an Anthropic Messages API response every LLM caller in this app needs, and
 * carries the one request entry they all send.
 *
 * <p>{@code content} is a list of typed blocks, not always text-first: claude-sonnet-5 returns
 * extended thinking by default, so {@code content[0]} can be a {@code "thinking"} block with no
 * {@code "text"} key. Indexing {@code content.get(0)} instead of scanning for the first block
 * actually typed {@code "text"} returned essentially nothing in production for months, and the bug
 * was fixed in only one of three hand-rolled copies of this exact logic before the other two were
 * even noticed (#211, #213). This class exists so a fourth caller gets the correct shape by
 * construction instead of by copying a neighbour that might still be wrong.
 */
public final class AnthropicMessages {

    /**
     * The {@code "thinking"} request entry that disables extended thinking. Every caller here is a
     * mechanical extraction or recall task, not a reasoning task, and extended thinking is on by
     * default -- it can consume the whole output budget before a text block is ever produced
     * (#211). Send as {@code "thinking", AnthropicMessages.THINKING_DISABLED} in the request body.
     */
    public static final Map<String, Object> THINKING_DISABLED = Map.of("type", "disabled");

    private AnthropicMessages() {
    }

    /**
     * @param response the parsed Messages API response body, or {@code null}
     * @return the text of the first {@code content} block typed {@code "text"}; empty when
     *     {@code response} is {@code null}, {@code content} is absent or empty, or no block is
     *     typed {@code "text"} (e.g. a thinking-only response that exhausted its output budget
     *     before producing text, #211) -- never {@code null}.
     */
    @SuppressWarnings("unchecked")
    public static Optional<String> textBlock(Map<String, Object> response) {
        if (response == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content == null) {
            return Optional.empty();
        }
        for (Map<String, Object> block : content) {
            if ("text".equals(block.get("type"))) {
                return Optional.ofNullable((String) block.get("text"));
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code stop_reason} lookup was identical hand-rolled duplication in all three original
     * call sites (not the buggy part, but duplicated all the same), and it is the detail that
     * tells "the model ran out of output budget while thinking" apart from any other cause when
     * {@link #textBlock} comes back empty (#211) -- worth a caller getting it without re-deriving
     * the lookup. Deliberately separate from {@link #textBlock}'s return rather than bundled into
     * it: the two are read together but logged differently (a caller only needs this when it is
     * about to WARN), and callers keep their own WARN wording and {@code source} key (see #215).
     *
     * @param response the parsed Messages API response body, or {@code null}
     * @return the response's {@code stop_reason}, when present.
     */
    public static Optional<Object> stopReason(Map<String, Object> response) {
        if (response == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(response.get("stop_reason"));
    }
}

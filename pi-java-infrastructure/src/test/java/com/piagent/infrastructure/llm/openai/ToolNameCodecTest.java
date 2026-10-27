package com.piagent.infrastructure.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.model.llm.LlmTool;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolNameCodecTest {

    private static final String PATTERN = "[a-zA-Z0-9_-]+";

    @Test
    void mapsDottedNameToWireAndBack() {
        ToolNameCodec codec = ToolNameCodec.of(List.of(tool("echo.search")));

        String wire = codec.wire("echo.search");

        assertThat(wire).isEqualTo("echo_search").matches(PATTERN);
        assertThat(codec.internal(wire)).isEqualTo("echo.search");
    }

    @Test
    void keepsNamesThatAlreadyMatchThePattern() {
        ToolNameCodec codec = ToolNameCodec.of(List.of(tool("read")));

        assertThat(codec.wire("read")).isEqualTo("read");
        assertThat(codec.internal("read")).isEqualTo("read");
    }

    @Test
    void disambiguatesNamesThatEncodeToTheSameWireName() {
        ToolNameCodec codec = ToolNameCodec.of(List.of(tool("a.b"), tool("a_b")));

        String first = codec.wire("a.b");
        String second = codec.wire("a_b");

        assertThat(first).matches(PATTERN).isNotEqualTo(second);
        assertThat(codec.internal(first)).isEqualTo("a.b");
        assertThat(codec.internal(second)).isEqualTo("a_b");
    }

    @Test
    void truncatesLongNamesToOpenAiLimit() {
        String longName = "com.piagent.example." + "x".repeat(80);
        ToolNameCodec codec = ToolNameCodec.of(List.of(tool(longName)));

        String wire = codec.wire(longName);

        assertThat(wire).hasSizeLessThanOrEqualTo(ToolNameCodec.MAX_LENGTH).matches(PATTERN);
        assertThat(codec.internal(wire)).isEqualTo(longName);
    }

    @Test
    void leavesUnknownNamesUntouchedWhenRestoring() {
        ToolNameCodec codec = ToolNameCodec.of(List.of());

        assertThat(codec.internal("unknown_tool")).isEqualTo("unknown_tool");
        assertThat(codec.wire("unknown.tool")).isEqualTo("unknown_tool");
    }

    private static LlmTool tool(String name) {
        return new LlmTool(name, "", "{\"type\":\"object\",\"properties\":{}}");
    }
}

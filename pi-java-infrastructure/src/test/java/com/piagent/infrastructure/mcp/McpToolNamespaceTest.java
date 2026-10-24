package com.piagent.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolName;
import org.junit.jupiter.api.Test;

/**
 * 命名空间与远程工具名清洗测试。
 */
class McpToolNamespaceTest {

    @Test
    void qualifiesSanitizedToolWithServerPrefix() {
        assertThat(McpToolNamespace.qualify("github", "list_issues"))
                .isEqualTo("github.list_issues");
        assertThat(McpToolNamespace.qualify("fs", "My Tool!")).isEqualTo("fs.my-tool");
        assertThat(McpToolNamespace.qualify("fs", "2fast")).isEqualTo("fs.tool-2fast");
        assertThat(McpToolNamespace.qualify("fs", "___")).isEqualTo("fs.tool");
    }

    @Test
    void truncatesLongNamesButKeepsThemUniqueAndValid() {
        String remote = "very-" + "long-tool-name-".repeat(20);
        String first = McpToolNamespace.qualify("server", remote);
        String second = McpToolNamespace.qualify("server", remote + "x");

        assertThat(first.length()).isLessThanOrEqualTo(McpToolNamespace.MAX_LENGTH);
        assertThat(second.length()).isLessThanOrEqualTo(McpToolNamespace.MAX_LENGTH);
        assertThat(first).isNotEqualTo(second);
        assertThat(new ToolName(first).value()).isEqualTo(first);
        assertThat(new ToolName(second).value()).isEqualTo(second);
    }

    @Test
    void rejectsToolsOutsideServerNamespace() {
        assertThatThrownBy(() ->
                McpToolNamespace.requireOwned("fs", new ToolName("github.search")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.MCP_TOOL_CONFLICT));
    }
}

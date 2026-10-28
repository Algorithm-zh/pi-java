package com.piagent.infrastructure.tool.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.governance.service.PathSandbox;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.infrastructure.tool.RealPathResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoMapToolHandlerTest {

    @TempDir
    Path workspace;

    private RepoMapToolHandler handler;
    private SandboxPolicy policy;

    @BeforeEach
    void setUp() throws IOException {
        handler = new RepoMapToolHandler(new RealPathResolver(new PathSandbox()));
        policy = SandboxPolicy.readOnly(workspace);
        Path java = Files.createDirectories(
                workspace.resolve("src/main/java/com/demo"));
        Files.writeString(java.resolve("OrderService.java"), """
                public class OrderService {
                    public void approve(String orderId) {
                    }
                }
                """);
        Path docs = Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(docs.resolve("guide.md"), "# 审批流程\n\n审批流程说明\n");
        Files.write(workspace.resolve("blob.bin"), new byte[] {'h', 0, 'i'});
    }

    @Test
    void returnsOverviewWithoutArguments() {
        ToolResult result = execute(Map.of());

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("仓库地图", "已索引 2 个文本文件",
                "java 1", "md 1", "- docs/（1 个文件）", "- src/（1 个文件）");
        assertThat(result.details()).containsEntry("matchedFiles", 2)
                .containsEntry("indexedFiles", 2)
                .containsEntry("truncated", false);
    }

    @Test
    void searchesByQuery() {
        ToolResult result = execute(Map.of("query", "审批流程"));

        assertThat(result.content()).contains("查询「审批流程」", "docs/guide.md");
    }

    @Test
    void limitsSearchToPath() {
        ToolResult result = execute(Map.of("query", "order", "path", "src"));

        assertThat(result.content()).contains("范围：src/", "OrderService.java")
                .doesNotContain("docs/guide.md");
    }

    @Test
    void searchesSingleFile() {
        ToolResult result = execute(Map.of("query", "approve",
                "path", "src/main/java/com/demo/OrderService.java"));

        assertThat(result.content()).contains("OrderService.java", "approve")
                .doesNotContain("docs/guide.md");
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        assertThatThrownBy(() -> execute(Map.of("path", "..")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
    }

    @Test
    void rejectsOutOfRangeMaxFiles() {
        assertThatThrownBy(() -> execute(Map.of("maxFiles", 0)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void rejectsUnknownArguments() {
        assertThatThrownBy(() -> execute(Map.of("pattern", "x")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    private ToolResult execute(Map<String, Object> arguments) {
        ToolInvocation invocation = new ToolInvocation(new ToolCallId("call-1"),
                new ToolName("repo_map"), arguments, policy);
        return handler.execute(invocation);
    }
}

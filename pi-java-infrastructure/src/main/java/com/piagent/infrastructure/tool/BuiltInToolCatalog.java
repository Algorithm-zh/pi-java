package com.piagent.infrastructure.tool;

import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.time.Duration;
import java.util.List;

/**
 * 内置只读工具目录。
 *
 * <p>M3 只注册读工具：{@code read}、{@code ls}、{@code grep}、{@code glob}。
 * 它们全部为低风险、可并行执行；默认策略对低风险工具直接放行，因此无需审批。
 * 参数 schema 使用 camelCase，与主流 OpenAI-compatible 模型的生成习惯一致。</p>
 */
public final class BuiltInToolCatalog {

    private static final int READ_TIMEOUT_SECONDS = 10;
    private static final int SCAN_TIMEOUT_SECONDS = 20;

    private BuiltInToolCatalog() {
    }

    /**
     * 内置工具定义。
     *
     * @return 工具定义
     */
    public static List<ToolDescriptor> descriptors() {
        return List.of(read(), ls(), grep(), glob());
    }

    private static ToolDescriptor read() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "path": {"type": "string", "description": "File path, relative to the workspace."},
                    "offset": {"type": "integer", "minimum": 1, "description": "1-based first line."},
                    "limit": {"type": "integer", "minimum": 1, "maximum": 2000,
                              "description": "Maximum number of lines."}
                  },
                  "required": ["path"],
                  "additionalProperties": false
                }
                """;
        return descriptor("read", "Read a UTF-8 text file with line numbers. "
                + "Use offset/limit for large files.", schema, READ_TIMEOUT_SECONDS);
    }

    private static ToolDescriptor ls() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "path": {"type": "string", "description": "Directory, defaults to workspace root."},
                    "depth": {"type": "integer", "minimum": 1, "maximum": 3,
                              "description": "Recursion depth, defaults to 1."}
                  },
                  "additionalProperties": false
                }
                """;
        return descriptor("ls", "List files and directories under a workspace directory.",
                schema, READ_TIMEOUT_SECONDS);
    }

    private static ToolDescriptor grep() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "pattern": {"type": "string", "description": "Java regular expression."},
                    "path": {"type": "string", "description": "File or directory, defaults to workspace root."},
                    "glob": {"type": "string", "description": "Optional file filter, e.g. *.java."},
                    "ignoreCase": {"type": "boolean", "description": "Case-insensitive match."},
                    "maxResults": {"type": "integer", "minimum": 1, "maximum": 500,
                                   "description": "Maximum matches, defaults to 100."}
                  },
                  "required": ["pattern"],
                  "additionalProperties": false
                }
                """;
        return descriptor("grep", "Search file contents with a regular expression. "
                + "Skips binary files, build output and hidden directories.", schema,
                SCAN_TIMEOUT_SECONDS);
    }

    private static ToolDescriptor glob() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "pattern": {"type": "string",
                                "description": "Glob pattern relative to the search root, e.g. **/*.java."},
                    "path": {"type": "string", "description": "Search root, defaults to workspace root."},
                    "maxResults": {"type": "integer", "minimum": 1, "maximum": 1000,
                                   "description": "Maximum paths, defaults to 200."}
                  },
                  "required": ["pattern"],
                  "additionalProperties": false
                }
                """;
        return descriptor("glob", "Find files by glob pattern. "
                + "Build output and hidden directories are skipped.", schema,
                SCAN_TIMEOUT_SECONDS);
    }

    private static ToolDescriptor descriptor(String name, String description, String schema,
            int timeoutSeconds) {
        return new ToolDescriptor(new ToolName(name), description, schema, ToolRisk.LOW,
                ExecutionMode.PARALLEL, Duration.ofSeconds(timeoutSeconds));
    }
}

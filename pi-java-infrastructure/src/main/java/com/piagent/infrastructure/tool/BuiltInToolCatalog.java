package com.piagent.infrastructure.tool;

import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.time.Duration;
import java.util.List;

/**
 * 内置工具目录。
 *
 * <p>只读工具 {@code read}/{@code ls}/{@code grep}/{@code glob} 为低风险、
 * 可并行；写工具 {@code write}/{@code edit} 为高风险，{@code bash} 为最高风险，
 * 三者都必须顺序执行。风险级别只决定默认策略，最终效果仍由策略表解析。</p>
 */
public final class BuiltInToolCatalog {

    private static final int READ_TIMEOUT_SECONDS = 10;
    private static final int SCAN_TIMEOUT_SECONDS = 20;
    private static final int WRITE_TIMEOUT_SECONDS = 20;
    private static final int BASH_TIMEOUT_SECONDS = 120;

    private BuiltInToolCatalog() {
    }

    /**
     * 内置工具定义。
     *
     * @return 工具定义
     */
    public static List<ToolDescriptor> descriptors() {
        return List.of(read(), ls(), grep(), glob(), write(), edit(), bash());
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

    private static ToolDescriptor write() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "path": {"type": "string", "description": "File path, relative to the workspace."},
                    "content": {"type": "string", "description": "Full UTF-8 file content."}
                  },
                  "required": ["path", "content"],
                  "additionalProperties": false
                }
                """;
        return descriptor("write", "Create or overwrite a UTF-8 text file. "
                + "Parent directories are created automatically.", schema, ToolRisk.HIGH,
                ExecutionMode.SEQUENTIAL, WRITE_TIMEOUT_SECONDS);
    }

    private static ToolDescriptor edit() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "path": {"type": "string", "description": "File path, relative to the workspace."},
                    "oldText": {"type": "string", "description": "Exact text to replace."},
                    "newText": {"type": "string", "description": "Replacement text."},
                    "replaceAll": {"type": "boolean",
                                   "description": "Replace every occurrence, defaults to false."}
                  },
                  "required": ["path", "oldText", "newText"],
                  "additionalProperties": false
                }
                """;
        return descriptor("edit", "Replace exact text in an existing UTF-8 file. "
                + "By default oldText must match exactly once.", schema, ToolRisk.HIGH,
                ExecutionMode.SEQUENTIAL, WRITE_TIMEOUT_SECONDS);
    }

    private static ToolDescriptor bash() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "command": {"type": "string", "description": "Shell command to run."},
                    "cwd": {"type": "string", "description": "Working directory inside the workspace."},
                    "timeoutSeconds": {"type": "integer", "minimum": 1, "maximum": 120,
                                       "description": "Timeout in seconds, defaults to 60."}
                  },
                  "required": ["command"],
                  "additionalProperties": false
                }
                """;
        return descriptor("bash", "Run a shell command inside the workspace. "
                + "stdout and stderr are merged; the process tree is killed on timeout.",
                schema, ToolRisk.CRITICAL, ExecutionMode.SEQUENTIAL, BASH_TIMEOUT_SECONDS);
    }

    private static ToolDescriptor descriptor(String name, String description, String schema,
            int timeoutSeconds) {
        return descriptor(name, description, schema, ToolRisk.LOW, ExecutionMode.PARALLEL,
                timeoutSeconds);
    }

    private static ToolDescriptor descriptor(String name, String description, String schema,
            ToolRisk risk, ExecutionMode mode, int timeoutSeconds) {
        return new ToolDescriptor(new ToolName(name), description, schema, risk, mode,
                Duration.ofSeconds(timeoutSeconds));
    }
}

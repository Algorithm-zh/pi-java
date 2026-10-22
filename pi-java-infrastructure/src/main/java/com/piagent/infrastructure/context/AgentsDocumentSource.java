package com.piagent.infrastructure.context;

import com.piagent.domain.context.port.WorkspacePromptSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 读取工作区根目录的 {@code AGENTS.md}。
 *
 * <p>v0.1 只读根目录一份文件，不支持从父目录向上继承（那会让 system prompt 依赖
 * 用户机器上不确定的上级目录，难以复现）。文件缺失、超大或不可读时返回空，
 * 调用方按“没有额外约定”处理，而不是报错。</p>
 *
 * <p>每次调用都直接读文件：AGENTS.md 很小，且用户可能在 run 之间修改它；
 * 缓存收益不足以抵消“改了不生效”的困惑。</p>
 */
public final class AgentsDocumentSource implements WorkspacePromptSource {

    /** 文件名。 */
    public static final String FILE_NAME = "AGENTS.md";

    /** 最大字节数，与文档约定一致（32 KB）。 */
    public static final long MAX_BYTES = 32L * 1024L;

    @Override
    public Optional<String> readAgentsDocument(Path cwd) {
        if (cwd == null) {
            return Optional.empty();
        }
        Path file = cwd.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            if (Files.size(file) > MAX_BYTES) {
                return Optional.empty();
            }
            String content = Files.readString(file, StandardCharsets.UTF_8).strip();
            return content.isEmpty() ? Optional.empty() : Optional.of(content);
        } catch (IOException error) {
            return Optional.empty();
        }
    }
}

package com.piagent.infrastructure.tool;

import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.governance.service.PathSandbox;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * 真实路径解析器：词法沙箱 + 物理路径复核。
 *
 * <p>只做字符串判断无法识别符号链接、Windows junction 与大小写别名，
 * 因此这里先让 {@link PathSandbox} 做规范化与词法包含判断，再对结果调用
 * {@code toRealPath()}，把真实路径与真实根目录重新比较。任何一步失败都转成
 * 稳定的领域错误码，由工具调用器包装成 {@code isError=true} 的结果。</p>
 *
 * <p>遍历工具（ls/grep/glob）不跟随符号链接，避免目录环与越界访问；
 * 显式打开文件时才解析真实路径，因此符号链接指向工作区外会被拒绝。</p>
 */
public final class RealPathResolver {

    private final PathSandbox sandbox;

    /**
     * 创建解析器。
     *
     * @param sandbox 纯路径沙箱
     */
    public RealPathResolver(PathSandbox sandbox) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox 不能为空");
    }

    /**
     * 解析已存在且允许读取的真实路径。
     *
     * @param policy 沙箱策略
     * @param requested 请求路径，空白表示工作区根
     * @return 真实路径
     */
    public Path resolveReadable(SandboxPolicy policy, String requested) {
        Path lexical = sandbox.resolveReadable(policy, requested);
        Path real = toRealPath(lexical, requested);
        if (!policy.allowReadOutsideWorkspace() && !isWithin(workspaceRoot(policy), real)) {
            throw new DomainException(DomainErrorCode.PATH_OUTSIDE_WORKSPACE,
                    "路径解析后不在允许读取的工作区内: " + requested);
        }
        return real;
    }

    /**
     * 解析允许写入的目标路径，目标可以尚不存在。
     *
     * <p>对最近的存在祖先取真实路径，再拼回缺失段；这样既能拒绝经符号链接
     * 指向外部的目录，又允许 {@code write} 创建新文件。</p>
     *
     * @param policy 沙箱策略
     * @param requested 请求路径
     * @return 规范化绝对路径
     */
    public Path resolveWritable(SandboxPolicy policy, String requested) {
        Path lexical = sandbox.resolveWritable(policy, requested);
        Path existing = lexical;
        Deque<Path> missing = new ArrayDeque<>();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new DomainException(DomainErrorCode.PATH_INVALID,
                    "无法解析写入路径: " + requested);
        }
        Path target = toRealPath(existing, requested);
        for (Path segment : missing) {
            target = target.resolve(segment);
        }
        Path normalized = target.normalize();
        boolean allowed = policy.writableRoots().stream()
                .map(this::realOrSelf)
                .anyMatch(normalized::startsWith);
        if (!allowed) {
            throw new DomainException(DomainErrorCode.PATH_OUTSIDE_WORKSPACE,
                    "路径解析后不在允许写入的根目录内: " + requested);
        }
        return normalized;
    }

    /**
     * 返回工作区根的真实路径；根不存在时抛 {@code PATH_NOT_FOUND}。
     *
     * @param policy 沙箱策略
     * @return 真实路径
     */
    public Path workspaceRoot(SandboxPolicy policy) {
        return toRealPath(policy.workspaceRoot(), policy.workspaceRoot().toString());
    }

    private Path toRealPath(Path path, String requested) {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException error) {
            throw new DomainException(DomainErrorCode.PATH_NOT_FOUND,
                    "路径不存在: " + requested, error);
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.PATH_INVALID,
                    "无法解析路径: " + requested, error);
        }
    }

    private Path realOrSelf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    private boolean isWithin(Path root, Path target) {
        return target.startsWith(root);
    }
}

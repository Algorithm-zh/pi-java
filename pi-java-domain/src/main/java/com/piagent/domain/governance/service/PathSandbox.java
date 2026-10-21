package com.piagent.domain.governance.service;

import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 纯路径沙箱：只做规范化、绝对化与根目录包含判断，不访问文件系统。
 *
 * <p>为什么还要在基础设施层再检查一次真实路径？因为本类只能看到字符串路径，
 * 无法识别符号链接、junction 或大小写别名；{@code RealPathResolver} 会先
 * {@code toRealPath()}，再把真实路径交给本类复核，形成“语法 + 物理”双保险。</p>
 */
public final class PathSandbox {

    /**
     * 解析可读路径。
     *
     * @param policy 沙箱策略
     * @param requested 用户/模型提供的路径，空白表示工作区根
     * @return 规范化绝对路径
     */
    public Path resolveReadable(SandboxPolicy policy, String requested) {
        Path resolved = resolve(policy.workspaceRoot(), requested);
        if (!policy.allowsRead(resolved)) {
            throw new DomainException(DomainErrorCode.PATH_OUTSIDE_WORKSPACE,
                    "路径不在允许读取的工作区内: " + requested);
        }
        return resolved;
    }

    /**
     * 解析可写路径。
     *
     * @param policy 沙箱策略
     * @param requested 用户/模型提供的路径
     * @return 规范化绝对路径
     */
    public Path resolveWritable(SandboxPolicy policy, String requested) {
        Path resolved = resolve(policy.workspaceRoot(), requested);
        if (!policy.allowsWrite(resolved)) {
            throw new DomainException(DomainErrorCode.PATH_OUTSIDE_WORKSPACE,
                    "路径不在允许写入的根目录内: " + requested);
        }
        return resolved;
    }

    private Path resolve(Path workspaceRoot, String requested) {
        String value = requested == null ? "" : requested;
        if (value.indexOf('\0') >= 0) {
            throw new DomainException(DomainErrorCode.PATH_INVALID, "路径包含非法字符");
        }
        try {
            Path raw = Path.of(value.strip());
            Path candidate = raw.isAbsolute() ? raw : workspaceRoot.resolve(raw);
            return candidate.normalize().toAbsolutePath();
        } catch (InvalidPathException error) {
            throw new DomainException(DomainErrorCode.PATH_INVALID,
                    "路径格式不合法: " + value, error);
        }
    }
}

package com.piagent.domain.governance.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 工具执行的路径沙箱策略。
 *
 * <p>只描述边界，不做 IO：{@code workspaceRoot} 是默认读取根；默认禁止读取工作区之外。
 * 写操作必须落在 {@code writableRoots} 之一。真实路径解析与符号链接检测在基础设施层
 * 完成，并把解析后的真实路径再次交给 {@link com.piagent.domain.governance.service.PathSandbox} 校验。</p>
 *
 * @param workspaceRoot 工作区根目录（绝对、规范化）
 * @param writableRoots 允许写入的根目录
 * @param allowReadOutsideWorkspace 是否允许读取工作区之外的路径
 */
public record SandboxPolicy(Path workspaceRoot, List<Path> writableRoots,
        boolean allowReadOutsideWorkspace) {

    /**
     * 校验并规范化策略。
     *
     * @param workspaceRoot 工作区根
     * @param writableRoots 可写根
     * @param allowReadOutsideWorkspace 是否允许越界读
     */
    public SandboxPolicy {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot 不能为空");
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        writableRoots = writableRoots == null ? List.of() : writableRoots.stream()
                .map(root -> Objects.requireNonNull(root, "可写根不能为空")
                        .toAbsolutePath().normalize())
                .toList();
    }

    /**
     * 只读工作区策略：默认读工作区、不允许写。
     *
     * @param workspaceRoot 工作区根
     * @return 策略
     */
    public static SandboxPolicy readOnly(Path workspaceRoot) {
        return new SandboxPolicy(workspaceRoot, List.of(), false);
    }

    /**
     * 工作区可写策略：读写都限制在工作区内。
     *
     * @param workspaceRoot 工作区根
     * @return 策略
     */
    public static SandboxPolicy workspaceWritable(Path workspaceRoot) {
        return new SandboxPolicy(workspaceRoot, List.of(workspaceRoot), false);
    }

    /**
     * 返回是否允许写入指定绝对路径。
     *
     * @param absolutePath 已规范化的绝对路径
     * @return true 表示落在某个可写根内
     */
    public boolean allowsWrite(Path absolutePath) {
        return writableRoots.stream().anyMatch(absolutePath::startsWith);
    }

    /**
     * 返回是否允许读取指定绝对路径。
     *
     * @param absolutePath 已规范化的绝对路径
     * @return true 表示允许读取
     */
    public boolean allowsRead(Path absolutePath) {
        return allowReadOutsideWorkspace || absolutePath.startsWith(workspaceRoot);
    }
}

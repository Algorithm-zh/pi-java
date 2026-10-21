package com.piagent.domain.session.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 会话创建时固定的沙箱快照。
 *
 * <p>快照只读：运行中修改工具策略不应改变历史会话的沙箱语义。
 * 路径必须是绝对路径；真实路径与符号链接检查在 infrastructure 完成。
 *
 * @param writableRoots 可写根目录
 * @param denyGlobs 禁止写入的 glob 模式
 */
public record SandboxSnapshot(List<Path> writableRoots, List<String> denyGlobs) {

    /**
     * 校验并冻结沙箱快照。
     *
     * @param writableRoots 可写根
     * @param denyGlobs 拒绝规则
     */
    public SandboxSnapshot {
        Objects.requireNonNull(writableRoots, "writableRoots 不能为空");
        Objects.requireNonNull(denyGlobs, "denyGlobs 不能为空");
        Guard.require(!writableRoots.isEmpty(), DomainErrorCode.INVALID_ARGUMENT, "至少需要一个可写根");
        for (Path root : writableRoots) {
            Guard.notNull(root, DomainErrorCode.INVALID_ARGUMENT, "可写根不能为空");
            Guard.require(root.isAbsolute(), DomainErrorCode.INVALID_ARGUMENT, "可写根必须是绝对路径: " + root);
        }
        writableRoots = List.copyOf(writableRoots);
        denyGlobs = List.copyOf(denyGlobs);
    }

    /**
     * 以工作目录作为唯一可写根的默认沙箱。
     *
     * @param cwd 工作目录
     * @return 沙箱快照
     */
    public static SandboxSnapshot forWorkspace(Path cwd) {
        Objects.requireNonNull(cwd, "cwd 不能为空");
        return new SandboxSnapshot(List.of(cwd.normalize()), List.of());
    }
}

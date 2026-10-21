package com.piagent.domain.governance.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 工作区键：{@code sha256(normalized cwd)}。
 *
 * <p>直接拿 cwd 做唯一键会超过索引长度，且 Windows 的盘符大小写、反斜杠与尾部分隔符
 * 会产生多个“同一个目录”。规范化规则：绝对化、去 {@code .}/{@code ..}、统一为
 * {@code /}、去掉尾部分隔符；Windows 盘符路径整体转小写（该类路径大小写不敏感）。</p>
 *
 * @param value 64 位小写十六进制
 */
public record WorkspaceKey(String value) {

    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[a-z]:/.*");

    /** 用户级全局规则使用的键；真实工作区哈希不会得到全零。 */
    public static final WorkspaceKey GLOBAL = new WorkspaceKey("0".repeat(64));

    /**
     * 校验工作区键。
     *
     * @param value 键值
     */
    public WorkspaceKey {
        Objects.requireNonNull(value, "workspaceKey 不能为空");
        value = Guard.notBlank(value, DomainErrorCode.INVALID_ARGUMENT, "workspaceKey 不能为空");
        Guard.require(HEX_64.matcher(value).matches(), DomainErrorCode.INVALID_ARGUMENT,
                "workspaceKey 必须是 64 位小写十六进制");
    }

    /**
     * 从工作区根目录计算键。
     *
     * @param workspaceRoot 工作区根（可为相对路径）
     * @return 工作区键
     */
    public static WorkspaceKey of(Path workspaceRoot) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot 不能为空");
        String normalized = workspaceRoot.toAbsolutePath().normalize().toString()
                .replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (WINDOWS_DRIVE.matcher(lower).matches()) {
            normalized = lower;
        }
        return new WorkspaceKey(sha256(normalized));
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", error);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

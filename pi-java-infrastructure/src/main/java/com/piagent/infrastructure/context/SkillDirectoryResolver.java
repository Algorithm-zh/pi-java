package com.piagent.infrastructure.context;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Skill 扫描目录的解析器。
 *
 * <p>配置里允许写 {@code ~/.pi-java/skills} 这类用户目录写法，Windows 上没有 {@code ~}，
 * 统一展开为 {@code user.home}。解析失败（空串、非法路径）不抛异常，只跳过该目录，
 * 避免一条错误配置导致整个应用无法启动。</p>
 */
public final class SkillDirectoryResolver {

    private static final String HOME_PREFIX = "~/";
    private static final String HOME_PREFIX_WINDOWS = "~\\";

    private SkillDirectoryResolver() {
    }

    /**
     * 展开 {@code ~} 前缀。
     *
     * @param configured 配置中的原始路径
     * @return 绝对路径；无法解析时返回 null
     */
    public static Path expand(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String value = configured.strip();
        String home = System.getProperty("user.home", "");
        if (value.startsWith(HOME_PREFIX) || value.startsWith(HOME_PREFIX_WINDOWS)) {
            value = home + value.substring(1);
        } else if (value.equals("~")) {
            value = home;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException error) {
            return null;
        }
    }
}

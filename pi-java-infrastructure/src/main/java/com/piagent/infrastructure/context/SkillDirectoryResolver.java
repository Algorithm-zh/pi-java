package com.piagent.infrastructure.context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

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
    private static final String CLASSPATH_PREFIX = "classpath:";

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
        if (value.startsWith(CLASSPATH_PREFIX)) {
            // classpath 资源必须先解包成本地目录，交给下面的统一路径处理。
            return materializeClasspath(value);
        }
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

    /**
     * 把 {@code classpath:} 前缀的根目录解包到用户目录下的稳定路径。
     *
     * <p>内置技能随 jar 分发，但扫描器只认文件系统目录，因此启动时把 classpath 下的
     * 资源复制到 {@code ~/.pi-java/bundled-skills/}。选这里而不是临时目录有两个原因：
     * 一是路径稳定，用户能在设置页看到并直接打开、改完重启即生效；二是系统清理
     * tmp 目录时不会把技能目录一起删掉。</p>
     *
     * <p>用 {@link ResourcePatternResolver} 而不是 {@code ClassLoader.getResource}，
     * 因为 Spring Boot 可执行 jar 里的资源走 {@code jar:} 协议，普通 API 只能取到单个
     * URL 无法列目录；{@code classpath*:} 模式能穿透 jar 把资源逐个列出来。</p>
     *
     * <p>复制是幂等的：目标已存在且大小相同就跳过，避免每次启动重复写盘。
     * 资源不存在时返回 null 而不是抛异常，与 {@link #expand(String)} 的容错策略一致：</p>
     *
     * @param location classpath 下的资源目录，如 {@code skills}
     * @return 解包后的本地目录；资源不存在或复制失败时返回 null
     */
    public static Path materializeClasspath(String location) {
        String root = normalizeLocation(location);
        if (root.isBlank()) {
            return null;
        }
        String normalizedRoot = root.endsWith("/") ? root : root + "/";
        Path target = Path.of(System.getProperty("user.home", "."), ".pi-java", "bundled-skills");
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + normalizedRoot + "**/*");
            boolean copied = false;
            for (Resource resource : resources) {
                if (!resource.isReadable() || resource.getFilename() == null
                        || !resource.getFilename().endsWith(".md")) {
                    continue;
                }
                String relative = relativePath(resource, normalizedRoot);
                if (relative == null || relative.isBlank()) {
                    continue;
                }
                copyIfChanged(resource, target.resolve(relative));
                copied = true;
            }
            return copied ? target.toAbsolutePath().normalize() : null;
        } catch (IOException error) {
            return null;
        }
    }

    /**
     * 从资源 URL 里截出相对路径，例如
     * {@code .../skills/codebase-tutorial/SKILL.md} → {@code codebase-tutorial/SKILL.md}。
     */
    private static String relativePath(Resource resource, String normalizedRoot) {
        try {
            String url = resource.getURL().toString().replace('\\', '/');
            int index = url.indexOf(normalizedRoot);
            if (index < 0) {
                return null;
            }
            return url.substring(index + normalizedRoot.length());
        } catch (IOException error) {
            return null;
        }
    }

    private static void copyIfChanged(Resource source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        if (Files.isRegularFile(destination) && Files.size(destination) == source.contentLength()) {
            return;
        }
        try (InputStream input = source.getInputStream()) {
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String normalizeLocation(String location) {
        if (location == null) {
            return "";
        }
        String value = location.strip();
        return value.startsWith(CLASSPATH_PREFIX) ? value.substring(CLASSPATH_PREFIX.length()) : value;
    }
}

package com.piagent.infrastructure.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.extension.model.ApiVersion;
import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.model.PluginPackage;
import com.piagent.domain.extension.port.PluginPackageInspector;
import com.piagent.domain.extension.service.PluginCompatibilityChecker;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 插件 jar 检查器。
 *
 * <p>安装与启用都会重新检查：安装时得到持久化所需的事实，启用时确认 jar 在
 * 安装后没有被替换（路径 + SHA-256）。检查只保证完整性与结构，不保证作者可信。</p>
 */
public final class PluginJarInspector implements PluginPackageInspector {

    static final String MANIFEST_ENTRY = "META-INF/pi-plugin.json";
    private static final String API_CLASS_PREFIX = "com/piagent/plugin/api/";
    private static final long MAX_JAR_BYTES = 64L * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;

    private final ObjectMapper mapper;

    /**
     * 创建检查器。
     *
     * @param mapper JSON 映射器
     */
    public PluginJarInspector(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
    }

    @Override
    public PluginPackage inspect(Path jarPath) {
        Objects.requireNonNull(jarPath, "jarPath 不能为空");
        Path path = jarPath.toAbsolutePath().normalize();
        requireReadableJar(path);
        try (JarFile jar = new JarFile(path.toFile())) {
            rejectBundledApiClasses(jar);
            JsonNode root = readManifest(jar);
            String mainClass = text(root, "mainClass", 256);
            requireMainClassEntry(jar, mainClass);
            ApiVersion apiVersion = ApiVersion.parse(text(root, "apiVersion", 16));
            PluginCompatibilityChecker.requireCompatible(apiVersion);
            return new PluginPackage(
                    new PluginId(text(root, "id", 128)),
                    text(root, "name", 128),
                    text(root, "version", 32),
                    apiVersion,
                    path,
                    sha256(path),
                    mainClass,
                    root.path("description").asText(""));
        } catch (IOException error) {
            throw failure("无法读取插件 jar: " + path, error);
        }
    }

    private void requireReadableJar(Path path) {
        if (!Files.isRegularFile(path)) {
            throw failure("插件 jar 不存在或不是文件: " + path, null);
        }
        try {
            if (Files.size(path) > MAX_JAR_BYTES) {
                throw failure("插件 jar 不能超过 64 MB", null);
            }
        } catch (IOException error) {
            throw failure("无法读取插件 jar 大小: " + path, error);
        }
    }

    private void rejectBundledApiClasses(JarFile jar) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(API_CLASS_PREFIX)) {
                throw failure("插件 jar 不能包含 plugin-api 类: " + name, null);
            }
        }
    }

    private JsonNode readManifest(JarFile jar) {
        JarEntry entry = jar.getJarEntry(MANIFEST_ENTRY);
        if (entry == null) {
            throw failure("插件缺少 " + MANIFEST_ENTRY, null);
        }
        try (InputStream input = jar.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) {
                throw failure("插件清单不能超过 16 KB", null);
            }
            JsonNode root = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                throw failure("插件清单必须是 JSON 对象", null);
            }
            return root;
        } catch (IOException error) {
            throw failure("无法解析插件清单", error);
        }
    }

    private void requireMainClassEntry(JarFile jar, String mainClass) {
        String entry = mainClass.replace('.', '/') + ".class";
        if (jar.getJarEntry(entry) == null) {
            throw failure("插件 jar 中不存在入口类: " + mainClass, null);
        }
    }

    private String text(JsonNode root, String field, int maxLength) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw failure("插件清单缺少字段: " + field, null);
        }
        String text = value.asText().trim();
        if (text.length() > maxLength) {
            throw failure("插件清单字段过长: " + field, null);
        }
        return text;
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", error);
        } catch (IOException error) {
            throw failure("计算插件校验和失败: " + path, error);
        }
    }

    private DomainException failure(String message, Throwable cause) {
        return cause == null
                ? new DomainException(DomainErrorCode.PLUGIN_LOAD_FAILED, message)
                : new DomainException(DomainErrorCode.PLUGIN_LOAD_FAILED, message, cause);
    }
}

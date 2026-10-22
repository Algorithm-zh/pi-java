package com.piagent.app.plugin;

import com.piagent.app.plugin.fixture.SamplePlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * 把测试夹具类打成合法插件 jar。
 *
 * <p>直接复用测试类路径上已编译的 {@link SamplePlugin} 字节码，不需要在测试期
 * 调用 Java 编译器；插件类加载器会以 child-first 方式从 jar 中重新加载它们。</p>
 */
final class SamplePluginJar {

    private static final String MANIFEST = """
            {
              "id": "app.test.sample",
              "name": "Sample",
              "version": "1.0.0",
              "apiVersion": "1.0",
              "mainClass": "com.piagent.app.plugin.fixture.SamplePlugin",
              "description": "HTTP 集成测试插件"
            }
            """;

    private SamplePluginJar() {
    }

    static Path create(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path jarPath = directory.resolve("sample-plugin.jar");
        try (OutputStream file = Files.newOutputStream(jarPath);
                JarOutputStream jar = new JarOutputStream(file)) {
            write(jar, "META-INF/pi-plugin.json", MANIFEST.getBytes(StandardCharsets.UTF_8));
            writeClass(jar, SamplePlugin.class);
            writeClass(jar, SamplePlugin.EchoTool.class);
        }
        return jarPath;
    }

    private static void writeClass(JarOutputStream jar, Class<?> type) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream("/" + entry)) {
            if (input == null) {
                throw new IOException("测试类路径缺少字节码: " + entry);
            }
            write(jar, entry, input.readAllBytes());
        }
    }

    private static void write(JarOutputStream jar, String name, byte[] content) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }
}

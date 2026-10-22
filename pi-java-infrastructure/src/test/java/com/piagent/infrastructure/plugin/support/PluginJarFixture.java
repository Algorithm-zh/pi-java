package com.piagent.infrastructure.plugin.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import com.piagent.plugin.api.PiPlugin;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * 测试插件 jar 生成器。
 *
 * <p>插件必须来自宿主 classpath 之外，才能真正验证类加载隔离。因此这里在测试
 * 运行时用 JDK 编译器把插件源码编译到临时目录，再连同清单打成 jar。</p>
 */
public final class PluginJarFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MANIFEST_ENTRY = "META-INF/pi-plugin.json";

    private PluginJarFixture() {
    }

    /**
     * 正常插件：注册一个 echo 工具。
     *
     * @param workDir 临时目录
     * @return jar 路径
     * @throws IOException 编译或打包失败
     */
    public static Path hello(Path workDir) throws IOException {
        return build(workDir, "example.hello", "Hello Plugin", "1.0.0", "1.0",
                "example.hello.HelloPlugin", Map.of(
                        "example/hello/HelloPlugin.java", HELLO_SOURCE), Map.of());
    }

    /**
     * 正常插件，但 jar 中夹带 plugin-api 类。
     *
     * @param workDir 临时目录
     * @return jar 路径
     * @throws IOException 编译或打包失败
     */
    public static Path withBundledApiClass(Path workDir) throws IOException {
        return build(workDir, "example.hello", "Hello Plugin", "1.0.0", "1.0",
                "example.hello.HelloPlugin", Map.of(
                        "example/hello/HelloPlugin.java", HELLO_SOURCE),
                Map.of("com/piagent/plugin/api/PiPlugin.class", new byte[]{0}));
    }

    /**
     * 启动时报错，且在报错前已注册工具。
     *
     * @param workDir 临时目录
     * @return jar 路径
     * @throws IOException 编译或打包失败
     */
    public static Path failingStart(Path workDir) throws IOException {
        return build(workDir, "example.fail", "Failing Plugin", "1.0.0", "1.0",
                "example.fail.FailingPlugin", Map.of(
                        "example/fail/FailingPlugin.java", FAILING_SOURCE), Map.of());
    }

    /**
     * 工具 schema 不是合法 JSON。
     *
     * @param workDir 临时目录
     * @return jar 路径
     * @throws IOException 编译或打包失败
     */
    public static Path invalidSchema(Path workDir) throws IOException {
        return build(workDir, "example.badschema", "Bad Schema Plugin", "1.0.0", "1.0",
                "example.badschema.BadSchemaPlugin", Map.of(
                        "example/badschema/BadSchemaPlugin.java", BAD_SCHEMA_SOURCE), Map.of());
    }

    /**
     * 工具执行超过 100ms 超时。
     *
     * @param workDir 临时目录
     * @return jar 路径
     * @throws IOException 编译或打包失败
     */
    public static Path slow(Path workDir) throws IOException {
        return build(workDir, "example.slow", "Slow Plugin", "1.0.0", "1.0",
                "example.slow.SlowPlugin", Map.of(
                        "example/slow/SlowPlugin.java", SLOW_SOURCE), Map.of());
    }

    /**
     * API 主版本不兼容的插件。
     *
     * @param workDir 临时目录
     * @return jar 路径
     * @throws IOException 编译或打包失败
     */
    public static Path incompatibleApi(Path workDir) throws IOException {
        return build(workDir, "example.hello", "Hello Plugin", "1.0.0", "2.0",
                "example.hello.HelloPlugin", Map.of(
                        "example/hello/HelloPlugin.java", HELLO_SOURCE), Map.of());
    }

    /**
     * 清单声明的入口类不存在。
     *
     * @param workDir 临时目录
     * @return jar 路径
     * @throws IOException 编译或打包失败
     */
    public static Path missingMainClass(Path workDir) throws IOException {
        return build(workDir, "example.hello", "Hello Plugin", "1.0.0", "1.0",
                "example.hello.Missing", Map.of(
                        "example/hello/HelloPlugin.java", HELLO_SOURCE), Map.of());
    }

    /**
     * 没有清单的 jar。
     *
     * @param workDir 临时目录
     * @return jar 路径
     * @throws IOException 打包失败
     */
    public static Path missingManifest(Path workDir) throws IOException {
        Path jar = Files.createDirectories(workDir).resolve("plugin.jar");
        try (OutputStream output = Files.newOutputStream(jar);
                JarOutputStream entries = new JarOutputStream(output)) {
            addEntry(entries, "readme.txt", new byte[]{1});
        }
        return jar;
    }

    /**
     * 自定义元数据与源码的插件 jar，用于检查器负例。
     *
     * @param workDir 临时目录
     * @param id 插件标识
     * @param version 插件版本
     * @param apiVersion API 版本
     * @param mainClass 入口类
     * @param sources 源码文件（相对路径 -> 内容）
     * @return jar 路径
     * @throws IOException 编译或打包失败
     */
    public static Path custom(Path workDir, String id, String version, String apiVersion,
            String mainClass, Map<String, String> sources) throws IOException {
        return build(workDir, id, id, version, apiVersion, mainClass, sources, Map.of());
    }

    private static Path build(Path workDir, String id, String name, String version,
            String apiVersion, String mainClass, Map<String, String> sources,
            Map<String, byte[]> extraEntries) throws IOException {
        Path root = Files.createDirectories(workDir);
        Path sourceRoot = Files.createDirectories(root.resolve("src"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Map<String, Path> sourceFiles = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path file = sourceRoot.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            sourceFiles.put(entry.getKey(), file);
        }
        compile(sourceFiles.values().stream().toList(), classes);
        Path jar = root.resolve("plugin.jar");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("id", id);
        manifest.put("name", name);
        manifest.put("version", version);
        manifest.put("apiVersion", apiVersion);
        manifest.put("mainClass", mainClass);
        manifest.put("description", "fixture plugin");
        writeJar(jar, classes, MAPPER.writeValueAsBytes(manifest), extraEntries);
        return jar;
    }

    private static void compile(List<Path> sources, Path classes) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("测试需要 JDK（java.compiler 不可用）");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sources);
            List<String> options = List.of("-classpath", classpath(), "-d", classes.toString());
            boolean ok = compiler.getTask(null, files, diagnostics, options, null, units).call();
            if (!ok) {
                throw new IllegalStateException("插件测试源码编译失败: " + diagnostics.getDiagnostics());
            }
        }
    }

    private static String classpath() {
        String testClasspath = System.getProperty("surefire.test.class.path");
        String classpath = testClasspath == null ? System.getProperty("java.class.path") : testClasspath;
        try {
            Path api = Path.of(PiPlugin.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            return classpath + java.io.File.pathSeparator + api;
        } catch (java.net.URISyntaxException error) {
            throw new IllegalStateException("无法定位 plugin-api 路径", error);
        }
    }

    private static void writeJar(Path jar, Path classes, byte[] manifest,
            Map<String, byte[]> extraEntries) throws IOException {
        try (OutputStream output = Files.newOutputStream(jar);
                JarOutputStream entries = new JarOutputStream(output)) {
            addDirEntry(entries, "META-INF/");
            addEntry(entries, MANIFEST_ENTRY, manifest);
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = classes.relativize(file).toString().replace('\\', '/');
                    addEntry(entries, name, Files.readAllBytes(file));
                }
            }
            for (Map.Entry<String, byte[]> extra : extraEntries.entrySet()) {
                addEntry(entries, extra.getKey(), extra.getValue());
            }
        }
    }

    private static void addDirEntry(JarOutputStream entries, String name) throws IOException {
        entries.putNextEntry(new JarEntry(name));
        entries.closeEntry();
    }

    private static void addEntry(JarOutputStream entries, String name, byte[] content)
            throws IOException {
        entries.putNextEntry(new JarEntry(name));
        entries.write(content);
        entries.closeEntry();
    }

    private static final String HELLO_SOURCE = """
            package example.hello;

            import com.piagent.plugin.api.PiPlugin;
            import com.piagent.plugin.api.PluginContext;
            import com.piagent.plugin.api.PluginDescriptor;
            import com.piagent.plugin.api.PluginId;
            import com.piagent.plugin.api.PluginRisk;
            import com.piagent.plugin.api.PluginTool;
            import com.piagent.plugin.api.PluginToolCall;
            import com.piagent.plugin.api.PluginToolContext;
            import com.piagent.plugin.api.PluginToolResult;
            import com.piagent.plugin.api.ToolSpec;
            import java.time.Duration;

            public final class HelloPlugin implements PiPlugin {

                public PluginDescriptor descriptor() {
                    return new PluginDescriptor(PluginId.parse("example.hello"), "Hello Plugin",
                            "1.0.0", "1.0", "example.hello.HelloPlugin", "test plugin");
                }

                public void start(PluginContext context) {
                    context.registerTool(new EchoTool());
                    context.log().info("hello plugin started");
                }

                private static final class EchoTool implements PluginTool {

                    public ToolSpec spec() {
                        return new ToolSpec("echo", "echo arguments", "{\\"type\\":\\"object\\"}",
                                PluginRisk.LOW, Duration.ofSeconds(2), true);
                    }

                    public PluginToolResult execute(PluginToolCall call, PluginToolContext context) {
                        return PluginToolResult.success("echo:" + call.arguments().get("text"));
                    }
                }
            }
            """;

    private static final String FAILING_SOURCE = """
            package example.fail;

            import com.piagent.plugin.api.PiPlugin;
            import com.piagent.plugin.api.PluginContext;
            import com.piagent.plugin.api.PluginDescriptor;
            import com.piagent.plugin.api.PluginId;
            import com.piagent.plugin.api.PluginRisk;
            import com.piagent.plugin.api.PluginTool;
            import com.piagent.plugin.api.PluginToolCall;
            import com.piagent.plugin.api.PluginToolContext;
            import com.piagent.plugin.api.PluginToolResult;
            import com.piagent.plugin.api.ToolSpec;
            import java.time.Duration;

            public final class FailingPlugin implements PiPlugin {

                public PluginDescriptor descriptor() {
                    return new PluginDescriptor(PluginId.parse("example.fail"), "Failing Plugin",
                            "1.0.0", "1.0", "example.fail.FailingPlugin", "fails on start");
                }

                public void start(PluginContext context) {
                    context.registerTool(new EchoTool());
                    throw new IllegalStateException("boom");
                }

                private static final class EchoTool implements PluginTool {

                    public ToolSpec spec() {
                        return new ToolSpec("echo", "echo", "{\\"type\\":\\"object\\"}",
                                PluginRisk.LOW, Duration.ofSeconds(1), true);
                    }

                    public PluginToolResult execute(PluginToolCall call, PluginToolContext context) {
                        return PluginToolResult.success("unused");
                    }
                }
            }
            """;

    private static final String BAD_SCHEMA_SOURCE = """
            package example.badschema;

            import com.piagent.plugin.api.PiPlugin;
            import com.piagent.plugin.api.PluginContext;
            import com.piagent.plugin.api.PluginDescriptor;
            import com.piagent.plugin.api.PluginId;
            import com.piagent.plugin.api.PluginRisk;
            import com.piagent.plugin.api.PluginTool;
            import com.piagent.plugin.api.PluginToolCall;
            import com.piagent.plugin.api.PluginToolContext;
            import com.piagent.plugin.api.PluginToolResult;
            import com.piagent.plugin.api.ToolSpec;
            import java.time.Duration;

            public final class BadSchemaPlugin implements PiPlugin {

                public PluginDescriptor descriptor() {
                    return new PluginDescriptor(PluginId.parse("example.badschema"), "Bad Schema",
                            "1.0.0", "1.0", "example.badschema.BadSchemaPlugin", "bad schema");
                }

                public void start(PluginContext context) {
                    context.registerTool(new BrokenTool());
                }

                private static final class BrokenTool implements PluginTool {

                    public ToolSpec spec() {
                        return new ToolSpec("broken", "broken", "not-json",
                                PluginRisk.LOW, Duration.ofSeconds(1), true);
                    }

                    public PluginToolResult execute(PluginToolCall call, PluginToolContext context) {
                        return PluginToolResult.success("unused");
                    }
                }
            }
            """;

    private static final String SLOW_SOURCE = """
            package example.slow;

            import com.piagent.plugin.api.PiPlugin;
            import com.piagent.plugin.api.PluginContext;
            import com.piagent.plugin.api.PluginDescriptor;
            import com.piagent.plugin.api.PluginId;
            import com.piagent.plugin.api.PluginRisk;
            import com.piagent.plugin.api.PluginTool;
            import com.piagent.plugin.api.PluginToolCall;
            import com.piagent.plugin.api.PluginToolContext;
            import com.piagent.plugin.api.PluginToolResult;
            import com.piagent.plugin.api.ToolSpec;
            import java.time.Duration;

            public final class SlowPlugin implements PiPlugin {

                public PluginDescriptor descriptor() {
                    return new PluginDescriptor(PluginId.parse("example.slow"), "Slow Plugin",
                            "1.0.0", "1.0", "example.slow.SlowPlugin", "slow tool");
                }

                public void start(PluginContext context) {
                    context.registerTool(new SleepTool());
                }

                private static final class SleepTool implements PluginTool {

                    public ToolSpec spec() {
                        return new ToolSpec("sleep", "sleep", "{\\"type\\":\\"object\\"}",
                                PluginRisk.LOW, Duration.ofMillis(100), true);
                    }

                    public PluginToolResult execute(PluginToolCall call, PluginToolContext context) {
                        try {
                            Thread.sleep(5_000);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            return PluginToolResult.failure("interrupted");
                        }
                        return PluginToolResult.success("done");
                    }
                }
            }
            """;
}

package com.piagent.infrastructure.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.infrastructure.plugin.support.PluginJarFixture;
import com.piagent.plugin.api.PiPlugin;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 插件类加载器测试：API 由宿主提供，宿主内部类不可见。
 */
class PluginClassLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsPluginClassFromJar() throws Exception {
        Path jar = PluginJarFixture.hello(tempDir.resolve("hello"));

        try (PluginClassLoader loader = new PluginClassLoader(jar, PiPlugin.class.getClassLoader())) {
            Class<?> type = loader.loadClass("example.hello.HelloPlugin");

            assertThat(PiPlugin.class.isAssignableFrom(type)).isTrue();
            assertThat(type.getClassLoader()).isSameAs(loader);
        }
    }

    @Test
    void sharesPluginApiWithHost() throws Exception {
        Path jar = PluginJarFixture.hello(tempDir.resolve("hello"));

        try (PluginClassLoader loader = new PluginClassLoader(jar, PiPlugin.class.getClassLoader())) {
            assertThat(loader.loadClass("com.piagent.plugin.api.PiPlugin"))
                    .isSameAs(PiPlugin.class);
            assertThat(loader.loadClass("java.lang.String")).isSameAs(String.class);
        }
    }

    @Test
    void hidesHostInternalClasses() throws Exception {
        Path jar = PluginJarFixture.hello(tempDir.resolve("hello"));

        try (PluginClassLoader loader = new PluginClassLoader(jar, PiPlugin.class.getClassLoader())) {
            assertThatThrownBy(() -> loader.loadClass(
                    "com.piagent.domain.tool.model.ToolName"))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void failsWhenPluginClassIsAbsent() throws Exception {
        Path jar = PluginJarFixture.hello(tempDir.resolve("hello"));

        try (PluginClassLoader loader = new PluginClassLoader(jar, PiPlugin.class.getClassLoader())) {
            assertThatThrownBy(() -> loader.loadClass("example.hello.Missing"))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }
}

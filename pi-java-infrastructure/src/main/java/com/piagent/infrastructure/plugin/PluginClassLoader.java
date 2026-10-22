package com.piagent.infrastructure.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * 插件类加载器：child-first，只允许父加载器暴露 JDK 与 plugin-api。
 *
 * <p>插件自身的类与 shade 依赖从 jar 中读取；同名宿主类不会被意外链接。
 * plugin-api 必须由宿主加载，保证插件实现的接口与宿主看到的接口是同一个
 * {@code Class} 对象，从而可以直接类型转换。</p>
 */
public final class PluginClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final ClassLoader allowedParent;

    /**
     * 为插件 jar 创建加载器。
     *
     * @param jarPath 插件 jar
     * @param hostLoader 宿主加载器（用于提供 plugin-api）
     * @throws IOException jar 路径无法转换为 URL
     */
    public PluginClassLoader(Path jarPath, ClassLoader hostLoader) throws IOException {
        super(new URL[]{jarPath.toUri().toURL()}, new PluginApiClassLoader(hostLoader));
        this.allowedParent = getParent();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = mustDelegate(name) ? allowedParent.loadClass(name)
                        : loadChildFirst(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> loadChildFirst(String name) throws ClassNotFoundException {
        try {
            return findClass(name);
        } catch (ClassNotFoundException missingFromPlugin) {
            return allowedParent.loadClass(name);
        }
    }

    private boolean mustDelegate(String name) {
        return PluginApiClassLoader.isJdkClass(name)
                || name.startsWith("com.piagent.plugin.api.");
    }
}

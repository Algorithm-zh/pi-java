package com.piagent.infrastructure.plugin;

/**
 * 只向插件暴露 JDK 与 plugin-api 的父加载器。
 *
 * <p>它不做任何类文件读取：JDK 交由平台加载器，plugin-api 交给宿主加载器，
 * 其余请求一律失败。因此插件默认的父委派路径看不到 Spring、domain 或宿主内部类。</p>
 *
 * <p>这是类加载隔离，不是安全边界；插件仍可显式使用系统加载器访问宿主。</p>
 */
final class PluginApiClassLoader extends ClassLoader {

    private final ClassLoader host;

    PluginApiClassLoader(ClassLoader host) {
        super(ClassLoader.getPlatformClassLoader());
        this.host = host;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("com.piagent.plugin.api.")) {
            Class<?> type = host.loadClass(name);
            if (resolve) {
                resolveClass(type);
            }
            return type;
        }
        if (isJdkClass(name)) {
            return super.loadClass(name, resolve);
        }
        throw new ClassNotFoundException(name);
    }

    static boolean isJdkClass(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("com.sun.")
                || name.startsWith("org.w3c.")
                || name.startsWith("org.xml.")
                || name.startsWith("org.ietf.");
    }
}

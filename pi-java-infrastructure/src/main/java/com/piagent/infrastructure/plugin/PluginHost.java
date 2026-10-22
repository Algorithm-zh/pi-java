package com.piagent.infrastructure.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.extension.model.ApiVersion;
import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.model.PluginInstallation;
import com.piagent.domain.extension.model.PluginPackage;
import com.piagent.domain.extension.port.PluginPackageInspector;
import com.piagent.domain.extension.port.PluginRuntime;
import com.piagent.domain.extension.service.PluginCompatibilityChecker;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.plugin.api.PiPlugin;
import com.piagent.plugin.api.PluginDescriptor;
import com.piagent.plugin.api.PluginLogger;
import com.piagent.plugin.api.PluginTool;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 插件宿主：安装后加载、停用与工具分派。
 *
 * <p>加载顺序是“校验 → 隔离加载 → 实例化 → start → 发布工具”。任一步失败都会
 * 回滚工具目录、关闭类加载器并抛出领域异常。停用时立即从工具目录移除，但保留
 * 工具句柄与类加载器，直到进程退出：这样停用前已启动的 run 仍能用旧快照完成。</p>
 */
public final class PluginHost implements PluginRuntime, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PluginHost.class.getName());

    private final PluginPackageInspector inspector;
    private final RuntimeToolRegistry toolRegistry;
    private final ObjectMapper mapper;
    private final PluginToolExecutor toolExecutor;
    private final Map<PluginId, LoadedPlugin> active = new LinkedHashMap<>();
    private final List<LoadedPlugin> retired = new ArrayList<>();
    private boolean closed;

    /**
     * 创建插件宿主。
     *
     * @param inspector jar 检查器
     * @param toolRegistry 运行时工具目录
     * @param mapper JSON 映射器
     * @param executor 插件工具执行器
     */
    public PluginHost(PluginPackageInspector inspector, RuntimeToolRegistry toolRegistry,
            ObjectMapper mapper, ExecutorService executor) {
        this.inspector = Objects.requireNonNull(inspector, "inspector 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
        this.toolExecutor = new PluginToolExecutor(Objects.requireNonNull(executor, "executor 不能为空"));
    }

    @Override
    public synchronized void load(PluginInstallation installation) {
        Objects.requireNonNull(installation, "installation 不能为空");
        if (closed) {
            throw new DomainException(DomainErrorCode.PLUGIN_INVALID_STATE, "插件宿主已关闭");
        }
        PluginId id = installation.id();
        if (active.containsKey(id)) {
            throw new DomainException(DomainErrorCode.PLUGIN_INVALID_STATE, "插件已加载: " + id);
        }
        PluginPackage pkg = verify(installation);
        PluginCompatibilityChecker.requireCompatible(pkg.apiVersion());
        PluginClassLoader loader = createLoader(pkg);
        PluginContextImpl context = new PluginContextImpl(id, new PluginLoggerAdapter(id));
        PiPlugin plugin = null;
        try {
            plugin = instantiate(pkg, loader);
            verifyDescriptor(plugin.descriptor(), pkg);
            start(plugin, context, loader);
            List<PluginToolAdapter> adapters = adapters(id, context.tools(), loader, context.log());
            toolExecutor.register(adapters);
            toolRegistry.replace(id, adapters.stream().map(PluginToolAdapter::descriptor).toList());
            active.put(id, new LoadedPlugin(id, pkg, loader, plugin, context));
        } catch (Throwable error) {
            rollback(id, context, plugin, loader);
            throw asFailure(id, error);
        }
    }

    @Override
    public synchronized void unload(PluginId id) {
        LoadedPlugin loaded = active.remove(id);
        if (loaded == null) {
            throw new DomainException(DomainErrorCode.PLUGIN_INVALID_STATE, "插件未加载: " + id);
        }
        toolRegistry.remove(id);
        retired.add(loaded);
    }

    @Override
    public synchronized boolean isLoaded(PluginId id) {
        return active.containsKey(id);
    }

    /**
     * 是否存在可执行的插件工具句柄。
     *
     * @param name 工具名
     * @return true 表示由插件提供
     */
    public boolean hasTool(ToolName name) {
        return toolExecutor.has(name);
    }

    /**
     * 执行插件工具。
     *
     * @param invocation 调用
     * @return 工具结果
     */
    public ToolResult invoke(ToolInvocation invocation) {
        return toolExecutor.invoke(invocation);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<LoadedPlugin> all = new ArrayList<>(active.values());
        all.addAll(retired);
        for (LoadedPlugin loaded : all) {
            loaded.context().close();
            stopQuietly(loaded.plugin());
            closeQuietly(loaded.loader());
            toolRegistry.remove(loaded.id());
        }
        active.clear();
        retired.clear();
        toolExecutor.clear();
    }

    private PluginPackage verify(PluginInstallation installation) {
        PluginPackage pkg = inspector.inspect(installation.artifact().jarPath());
        boolean matches = pkg.id().equals(installation.id())
                && pkg.version().equals(installation.artifact().version())
                && pkg.apiVersion().equals(installation.artifact().apiVersion())
                && pkg.checksum().equals(installation.artifact().checksum());
        if (!matches) {
            throw failure("插件 jar 与安装记录不一致，请重新安装: " + installation.id());
        }
        return pkg;
    }

    private PluginClassLoader createLoader(PluginPackage pkg) {
        try {
            return new PluginClassLoader(pkg.jarPath(), PiPlugin.class.getClassLoader());
        } catch (IOException error) {
            throw failure("无法创建插件类加载器: " + pkg.jarPath(), error);
        }
    }

    private PiPlugin instantiate(PluginPackage pkg, PluginClassLoader loader) {
        try {
            Class<?> type = Class.forName(pkg.mainClass(), true, loader);
            if (!PiPlugin.class.isAssignableFrom(type)) {
                throw failure("入口类未实现 PiPlugin: " + pkg.mainClass());
            }
            return (PiPlugin) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException error) {
            throw failure("无法实例化插件入口类: " + pkg.mainClass(), error);
        }
    }

    private void verifyDescriptor(PluginDescriptor descriptor, PluginPackage pkg) {
        if (descriptor == null) {
            throw failure("插件 descriptor() 不能返回 null");
        }
        boolean matches = descriptor.id().value().equals(pkg.id().value())
                && descriptor.mainClass().equals(pkg.mainClass())
                && descriptor.version().equals(pkg.version())
                && ApiVersion.parse(descriptor.apiVersion()).equals(pkg.apiVersion());
        if (!matches) {
            throw failure("插件 descriptor() 与清单不一致: " + pkg.id());
        }
    }

    private void start(PiPlugin plugin, PluginContextImpl context, PluginClassLoader loader) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            plugin.start(context);
        } catch (Exception error) {
            throw failure("插件启动失败: " + error.getMessage(), error);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private List<PluginToolAdapter> adapters(PluginId id, List<PluginTool> tools,
            PluginClassLoader loader, PluginLogger logger) {
        List<PluginToolAdapter> adapters = new ArrayList<>(tools.size());
        Set<ToolName> names = new HashSet<>();
        for (PluginTool tool : tools) {
            PluginToolAdapter adapter = new PluginToolAdapter(id, tool, loader, logger);
            validateSchema(adapter);
            if (!names.add(adapter.descriptor().name())) {
                throw failure("插件注册了重复工具: " + adapter.descriptor().name());
            }
            adapters.add(adapter);
        }
        return adapters;
    }

    private void validateSchema(PluginToolAdapter adapter) {
        try {
            JsonNode schema = mapper.readTree(adapter.schemaJson());
            if (schema == null || !schema.isObject()) {
                throw failure("工具参数 schema 必须是 JSON 对象: " + adapter.descriptor().name());
            }
        } catch (IOException error) {
            throw failure("工具参数 schema 不是合法 JSON: " + adapter.descriptor().name(), error);
        }
    }

    private void rollback(PluginId id, PluginContextImpl context, PiPlugin plugin,
            PluginClassLoader loader) {
        context.close();
        if (plugin != null) {
            stopQuietly(plugin);
        }
        closeQuietly(loader);
        toolRegistry.remove(id);
        toolExecutor.removeAll(id);
    }

    private void stopQuietly(PiPlugin plugin) {
        try {
            plugin.stop();
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "插件 stop() 失败", error);
        }
    }

    private void closeQuietly(PluginClassLoader loader) {
        try {
            loader.close();
        } catch (IOException error) {
            LOG.log(System.Logger.Level.WARNING, "关闭插件类加载器失败", error);
        }
    }

    private DomainException asFailure(PluginId id, Throwable error) {
        if (error instanceof DomainException domain) {
            return domain;
        }
        String message = error.getMessage() == null ? error.getClass().getSimpleName()
                : error.getMessage();
        return failure("插件加载失败: " + id + ": " + message, error);
    }

    private DomainException failure(String message) {
        return failure(message, null);
    }

    private DomainException failure(String message, Throwable cause) {
        return cause == null
                ? new DomainException(DomainErrorCode.PLUGIN_LOAD_FAILED, message)
                : new DomainException(DomainErrorCode.PLUGIN_LOAD_FAILED, message, cause);
    }

    /**
     * 已加载插件的运行时资产。
     *
     * @param id 插件标识
     * @param pkg 清单快照
     * @param loader 类加载器
     * @param plugin 插件实例
     * @param context 启动上下文
     */
    private record LoadedPlugin(PluginId id, PluginPackage pkg, PluginClassLoader loader,
            PiPlugin plugin, PluginContextImpl context) {
    }
}

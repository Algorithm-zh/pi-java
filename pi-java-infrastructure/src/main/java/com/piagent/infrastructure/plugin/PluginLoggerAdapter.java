package com.piagent.infrastructure.plugin;

import com.piagent.domain.extension.model.PluginId;
import com.piagent.plugin.api.PluginLogger;
import java.util.Objects;

/**
 * 插件日志适配器。
 *
 * <p>输出统一带 {@code plugin.<id>} 前缀，便于在宿主日志中按插件过滤。
 * 参数使用 {@code {}} 占位符，与常见日志门面习惯一致。</p>
 */
final class PluginLoggerAdapter implements PluginLogger {

    private final System.Logger logger;

    PluginLoggerAdapter(PluginId id) {
        this.logger = System.getLogger("plugin." + id);
    }

    @Override
    public void debug(String message, Object... args) {
        log(System.Logger.Level.DEBUG, message, args, null);
    }

    @Override
    public void info(String message, Object... args) {
        log(System.Logger.Level.INFO, message, args, null);
    }

    @Override
    public void warn(String message, Object... args) {
        log(System.Logger.Level.WARNING, message, args, null);
    }

    @Override
    public void error(String message, Throwable error, Object... args) {
        log(System.Logger.Level.ERROR, message, args, error);
    }

    private void log(System.Logger.Level level, String message, Object[] args, Throwable error) {
        if (!logger.isLoggable(level)) {
            return;
        }
        String rendered = render(Objects.requireNonNullElse(message, ""), args);
        if (error == null) {
            logger.log(level, rendered);
        } else {
            logger.log(level, rendered, error);
        }
    }

    private String render(String template, Object[] args) {
        StringBuilder result = new StringBuilder(template.length());
        int cursor = 0;
        int argIndex = 0;
        while (true) {
            int placeholder = template.indexOf("{}", cursor);
            if (placeholder < 0 || argIndex >= args.length) {
                result.append(template, cursor, template.length());
                break;
            }
            result.append(template, cursor, placeholder)
                    .append(String.valueOf(args[argIndex++]));
            cursor = placeholder + 2;
        }
        return result.toString();
    }
}

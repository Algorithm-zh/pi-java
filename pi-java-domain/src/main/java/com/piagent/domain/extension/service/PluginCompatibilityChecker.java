package com.piagent.domain.extension.service;

import com.piagent.domain.extension.model.ApiVersion;
import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.tool.model.ToolName;
import java.util.Collection;

/**
 * 插件兼容性与命名校验。
 *
 * <p>这些规则必须在加载插件之前执行：API 主版本不匹配的插件即使能实例化，
 * 也可能在前言不匹配的调用点上崩溃；工具名冲突则会覆盖内置能力。把校验放在
 * 领域层可以脱离 ClassLoader 单测，也能被安装与启用两个用例复用。</p>
 */
public final class PluginCompatibilityChecker {

    /** 宿主支持的插件 API 主版本。 */
    public static final int SUPPORTED_MAJOR = 1;

    private PluginCompatibilityChecker() {
    }

    /**
     * 要求 API 主版本兼容。
     *
     * @param version 插件声明的版本
     */
    public static void requireCompatible(ApiVersion version) {
        Guard.require(version.major() == SUPPORTED_MAJOR, DomainErrorCode.PLUGIN_INCOMPATIBLE,
                "插件 API 主版本必须为 " + SUPPORTED_MAJOR + "，实际为 " + version.text());
    }

    /**
     * 要求工具名带插件前缀。
     *
     * @param id 插件标识
     * @param tool 完整工具名
     */
    public static void requireToolPrefix(PluginId id, ToolName tool) {
        Guard.require(id.owns(tool), DomainErrorCode.PLUGIN_TOOL_CONFLICT,
                "插件工具必须以 " + id + ". 为前缀: " + tool);
    }

    /**
     * 要求工具名未被占用。
     *
     * @param candidate 待注册工具
     * @param existing 已存在工具
     */
    public static void requireNoConflict(ToolName candidate, Collection<ToolName> existing) {
        if (existing.contains(candidate)) {
            throw new DomainException(DomainErrorCode.PLUGIN_TOOL_CONFLICT,
                    "工具名已被占用: " + candidate);
        }
    }
}

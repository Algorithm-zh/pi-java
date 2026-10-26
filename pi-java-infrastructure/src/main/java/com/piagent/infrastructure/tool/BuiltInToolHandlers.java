package com.piagent.infrastructure.tool;

import com.piagent.application.plan.SessionPlanService;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.infrastructure.tool.handler.BashToolHandler;
import com.piagent.infrastructure.tool.handler.EditToolHandler;
import com.piagent.infrastructure.tool.handler.GlobToolHandler;
import com.piagent.infrastructure.tool.handler.GrepToolHandler;
import com.piagent.infrastructure.tool.handler.LsToolHandler;
import com.piagent.infrastructure.tool.handler.ReadToolHandler;
import com.piagent.infrastructure.tool.handler.TodoToolHandler;
import com.piagent.infrastructure.tool.handler.WriteToolHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 内置工具执行体装配。
 *
 * <p>目录（{@link BuiltInToolCatalog}）与实现必须一一对应；集中在这里装配，
 * 避免 Spring 配置与测试各自维护一份 switch，新增工具时漏掉某一处。</p>
 */
public final class BuiltInToolHandlers {

    private BuiltInToolHandlers() {
    }

    /**
     * 为全部内置工具创建执行体。
     *
     * @param resolver 路径解析器
     * @param plans 会话计划服务，供 todo 工具写入快照
     * @return 按工具名索引的执行体
     */
    public static Map<ToolName, BuiltInToolHandler> create(RealPathResolver resolver,
            SessionPlanService plans) {
        Objects.requireNonNull(resolver, "resolver 不能为空");
        Objects.requireNonNull(plans, "plans 不能为空");
        Map<ToolName, BuiltInToolHandler> handlers = new LinkedHashMap<>();
        handlers.put(name("read"), new ReadToolHandler(resolver));
        handlers.put(name("ls"), new LsToolHandler(resolver));
        handlers.put(name("grep"), new GrepToolHandler(resolver));
        handlers.put(name("glob"), new GlobToolHandler(resolver));
        handlers.put(name("todo"), new TodoToolHandler(plans));
        handlers.put(name("write"), new WriteToolHandler(resolver));
        handlers.put(name("edit"), new EditToolHandler(resolver));
        handlers.put(name("bash"), new BashToolHandler(resolver));
        return Map.copyOf(handlers);
    }

    private static ToolName name(String value) {
        return new ToolName(value);
    }
}

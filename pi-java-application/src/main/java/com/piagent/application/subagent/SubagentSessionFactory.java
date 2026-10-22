package com.piagent.application.subagent;

import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.Origin;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.subagent.SubagentMode;
import com.piagent.domain.subagent.SubagentRequest;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 子代理会话工厂：把父会话与请求翻译成待持久化的子会话。
 *
 * <p>为什么独立成组件：子会话的“继承规则”比单看代码更容易出错——
 * 工作目录与沙箱必须来自父会话（只能收紧不能放宽）、模型档案沿用父会话、
 * 标题与来源要能让人看出这是子代理。集中在一处实现，测试也能直接断言。</p>
 */
@Component
public class SubagentSessionFactory {

    private final IdGenerator idGenerator;

    /**
     * 创建工厂。
     *
     * @param idGenerator 标识生成器
     */
    public SubagentSessionFactory(IdGenerator idGenerator) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator 不能为空");
    }

    /**
     * 构造子会话。
     *
     * <p>FORK 模式的 {@code forkFromEntryId} 取父会话当前叶子；SPAWN 模式为 null。
     * 无论哪种模式，子会话的 {@code origin} 都是 {@link Origin#SUBAGENT}，
     * 深度为父会话 + 1。</p>
     *
     * @param parent 父会话
     * @param request 子代理请求
     * @return 尚未持久化的子会话
     */
    public ChatSession create(ChatSession parent, SubagentRequest request) {
        Objects.requireNonNull(parent, "parent 不能为空");
        Objects.requireNonNull(request, "request 不能为空");
        SessionId childId = new SessionId(Ulid.parse(idGenerator.newId()));
        SessionConfig config = childConfig(parent);
        String title = titleOf(parent, request);
        if (request.mode() == SubagentMode.FORK) {
            return ChatSession.createChild(childId, parent, parent.leafEntryId(), Origin.SUBAGENT,
                    title, config);
        }
        return ChatSession.createChild(childId, parent, null, Origin.SUBAGENT, title, config);
    }

    /**
     * 子会话沿用父会话的模型、思考级别与沙箱，只改标题。
     *
     * <p>沙箱不能在这里放宽：{@code SessionConfig} 是值对象，直接复用父配置即等于
     * “父子沙箱完全相同”，满足“只收紧不放宽”的底线。更强的收紧（例如限制到子目录）
     * 留给后续里程碑，避免一次引入过多语义。</p>
     */
    private SessionConfig childConfig(ChatSession parent) {
        return parent.config();
    }

    private String titleOf(ChatSession parent, SubagentRequest request) {
        String task = request.task().replaceAll("\\s+", " ").strip();
        String shortTask = task.length() <= 24 ? task : task.substring(0, 24) + "…";
        return "子代理 · " + shortTask;
    }
}

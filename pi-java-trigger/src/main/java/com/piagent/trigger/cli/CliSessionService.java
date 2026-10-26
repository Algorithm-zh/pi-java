package com.piagent.trigger.cli;

import com.piagent.application.model.service.ModelProfileApplicationService;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.service.ModelSelectionPolicy;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.service.SessionTitles;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * CLI 的会话准备用例。
 *
 * <p>把“解析工作目录 / 选择默认模型 / 校验思考级别 / 创建会话”收敛到一处，
 * 供 {@code run} 与 {@code chat} 复用；仍只调用应用层与领域策略，不碰持久化。</p>
 */
@Component
public class CliSessionService {

    private final SessionApplicationService sessions;
    private final ModelProfileApplicationService models;
    private final ModelSelectionPolicy selectionPolicy;

    /**
     * 创建 CLI 会话服务。
     *
     * @param sessions 会话用例
     * @param models 模型档案用例
     * @param selectionPolicy 模型能力策略
     */
    public CliSessionService(SessionApplicationService sessions,
            ModelProfileApplicationService models, ModelSelectionPolicy selectionPolicy) {
        this.sessions = sessions;
        this.models = models;
        this.selectionPolicy = selectionPolicy;
    }

    /**
     * 创建并校验新会话。
     *
     * @param cwd 工作目录
     * @param modelId 模型档案
     * @param level 思考级别
     * @param title 标题
     * @return 新会话
     */
    public ChatSession create(Path cwd, String modelId, ThinkingLevel level, String title) {
        ModelProfile profile = models.requireEnabled(modelId);
        selectionPolicy.requireCompatible(profile, false, level);
        return sessions.createSession(new CreateSessionCommand(title, cwd, modelId, level));
    }

    /**
     * 加载已有会话。
     *
     * @param rawId 会话 ID
     * @return 会话
     */
    public ChatSession require(String rawId) {
        try {
            return sessions.find(SessionId.parse(rawId.strip()));
        } catch (IllegalArgumentException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "会话 ID 不合法: " + rawId, error);
        }
    }

    /**
     * 加载会话当前分支。
     *
     * @param sessionId 会话标识
     * @return 分支 entry
     */
    public List<SessionEntry> branch(SessionId sessionId) {
        return sessions.listBranch(sessionId, null);
    }

    /**
     * 解析工作目录，默认进程当前目录。
     *
     * @param raw 用户输入
     * @return 规范化后的绝对目录
     */
    public Path cwd(String raw) {
        try {
            Path path = raw == null || raw.isBlank()
                    ? Path.of(".").toAbsolutePath().normalize()
                    : expandHome(raw.strip()).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                        "工作目录不存在或不是目录: " + path);
            }
            return path;
        } catch (InvalidPathException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "工作目录不是合法路径: " + raw, error);
        }
    }

    private static Path expandHome(String value) {
        if ("~".equals(value)) {
            return Path.of(System.getProperty("user.home"));
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home") + value.substring(1));
        }
        return Path.of(value);
    }

    /**
     * 解析思考级别。
     *
     * @param raw 用户输入，null/空表示 OFF
     * @return 思考级别
     */
    public ThinkingLevel thinking(String raw) {
        if (raw == null || raw.isBlank()) {
            return ThinkingLevel.OFF;
        }
        try {
            return ThinkingLevel.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "思考级别只能是 OFF/LOW/MEDIUM/HIGH: " + raw, error);
        }
    }

    /**
     * 第一个启用模型；没有启用档案时抛出可读错误。
     *
     * @return 模型档案标识
     */
    public String defaultModelId() {
        List<ModelProfile> enabled = models.listEnabled();
        if (enabled.isEmpty()) {
            throw new DomainException(DomainErrorCode.MODEL_NOT_FOUND,
                    "没有启用的模型档案，请先在 Web 设置中心创建并启用模型");
        }
        return enabled.getFirst().id().value();
    }

    /**
     * 从首条消息生成会话标题。
     *
     * @param text 用户文本
     * @return 折叠空白后的短标题
     */
    public static String defaultTitle(String text) {
        String title = SessionTitles.fromText(text);
        return title.isBlank() ? "CLI 会话" : title;
    }
}

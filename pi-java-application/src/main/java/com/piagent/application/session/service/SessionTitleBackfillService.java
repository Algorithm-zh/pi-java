package com.piagent.application.session.service;

import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.session.service.SessionTitles;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 为存量会话回填标题。
 *
 * <p>自动命名只对之后的新会话生效；升级前创建的会话仍显示“新会话”之类占位
 * 标题，在侧栏里无法区分。启动时扫描最近会话，用首条用户消息生成标题，
 * 只覆盖占位标题，手动命名的会话保持原样。</p>
 */
@Service
public class SessionTitleBackfillService {

    private static final System.Logger LOG =
            System.getLogger(SessionTitleBackfillService.class.getName());
    private static final int SCAN_LIMIT = 200;

    private final SessionRepository sessions;
    private final SessionEntryStore entries;

    /**
     * 创建回填服务。
     *
     * @param sessions 会话仓储
     * @param entries entry 存储
     */
    public SessionTitleBackfillService(SessionRepository sessions, SessionEntryStore entries) {
        this.sessions = sessions;
        this.entries = entries;
    }

    /**
     * 按首条用户消息回填占位标题。
     *
     * @return 被重命名的会话数量
     */
    @Transactional
    public int backfill() {
        int renamed = 0;
        for (ChatSession session : sessions.findRecent(SCAN_LIMIT)) {
            if (!SessionTitles.isPlaceholder(session.title()) || session.leafEntryId() == null) {
                continue;
            }
            String title = SessionTitles.fromText(firstUserText(session));
            if (title.isBlank() || title.equals(session.title())) {
                continue;
            }
            try {
                sessions.update(session.rename(title));
                renamed++;
            } catch (RuntimeException error) {
                // 单条更新冲突（例如会话刚被手动改名）不应影响其他会话。
                LOG.log(System.Logger.Level.WARNING,
                        "回填会话标题失败: " + session.identity().id(), error);
            }
        }
        return renamed;
    }

    private String firstUserText(ChatSession session) {
        List<SessionEntry> branch = entries.loadBranch(session.identity().id(), session.leafEntryId());
        for (SessionEntry entry : branch) {
            if (entry.payload() instanceof MessagePayload payload
                    && payload.message() instanceof UserMessage message) {
                return message.content().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::text)
                        .reduce("", (left, right) -> left.isEmpty() ? right : left + " " + right);
            }
        }
        return "";
    }
}

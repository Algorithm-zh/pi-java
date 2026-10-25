package com.piagent.application.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionIdentity;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.service.SessionContextProjector;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 悬空工具调用闭合器的单元测试。
 */
class InterruptedToolCallCloserTest {

    private static final SessionId SESSION_ID = SessionEntryFactory.sessionId(1);

    private InMemorySessionRepository sessions;
    private InMemorySessionEntryStore entries;
    private InterruptedToolCallCloser closer;

    @BeforeEach
    void setUp() {
        sessions = new InMemorySessionRepository();
        entries = new InMemorySessionEntryStore();
        closer = new InterruptedToolCallCloser(sessions, entries,
                new SequentialIdGenerator(), Clock.fixed(SessionEntryFactory.NOW, ZoneOffset.UTC));
    }

    @Test
    void closesDanglingCallOnIdleSession() {
        danglingSession(SessionStatus.IDLE);

        assertThat(closer.closeDangling(SESSION_ID)).isEqualTo(1);

        ChatSession after = sessions.findById(SESSION_ID).orElseThrow();
        assertThat(after.status()).isEqualTo(SessionStatus.IDLE);
        List<SessionEntry> branch = entries.loadBranch(SESSION_ID, after.leafEntryId());
        assertThat(branch).hasSize(3);
        assertThat(branch.get(2).payload()).isInstanceOfSatisfying(MessagePayload.class, payload ->
                assertThat(payload.message()).isInstanceOfSatisfying(ToolResultMessage.class, result -> {
                    assertThat(result.toolCallId()).isEqualTo("call-1");
                    assertThat(result.isError()).isTrue();
                }));
        assertThat(new SessionContextProjector().project(branch, after.leafEntryId()).messages())
                .hasSize(3);
    }

    @Test
    void keepsWaitingApprovalStatus() {
        danglingSession(SessionStatus.WAITING_APPROVAL);

        assertThat(closer.closeDangling(SESSION_ID)).isEqualTo(1);

        ChatSession after = sessions.findById(SESSION_ID).orElseThrow();
        assertThat(after.status()).isEqualTo(SessionStatus.WAITING_APPROVAL);
        assertThat(entries.loadBranch(SESSION_ID, after.leafEntryId())).hasSize(3);
    }

    @Test
    void rewindsLeafWhenMessagesWereAddedAfterDanglingCall() {
        SessionEntry user = SessionEntryFactory.message(SESSION_ID, 1, null,
                SessionEntryFactory.user("写文件"), 1);
        SessionEntry call = SessionEntryFactory.message(SESSION_ID, 2, user.id(),
                SessionEntryFactory.assistantWithToolCall("call-1", "write"), 1);
        SessionEntry later = SessionEntryFactory.message(SESSION_ID, 3, call.id(),
                SessionEntryFactory.user("后来发送的消息"), 1);
        SessionEntry misplaced = SessionEntryFactory.message(SESSION_ID, 4, later.id(),
                SessionEntryFactory.toolResult("call-1", "write", "迟到的结果"), 1);
        entries.append(user);
        entries.append(call);
        entries.append(later);
        entries.append(misplaced);
        sessions.insert(restore(SessionStatus.IDLE, misplaced.id()));

        assertThat(closer.closeDangling(SESSION_ID)).isEqualTo(1);

        ChatSession after = sessions.findById(SESSION_ID).orElseThrow();
        assertThat(after.status()).isEqualTo(SessionStatus.IDLE);
        List<SessionEntry> branch = entries.loadBranch(SESSION_ID, after.leafEntryId());
        assertThat(branch).hasSize(3);
        assertThat(branch.get(2).isToolResult()).isTrue();
        assertThat(new SessionContextProjector().project(branch, after.leafEntryId()).messages())
                .hasSize(3);
        assertThat(entries.countBySession(SESSION_ID)).isEqualTo(5);
    }

    @Test
    void ignoresCompletedBranch() {
        SessionEntry user = SessionEntryFactory.message(SESSION_ID, 1, null,
                SessionEntryFactory.user("你好"), 1);
        SessionEntry reply = SessionEntryFactory.message(SESSION_ID, 2, user.id(),
                SessionEntryFactory.assistant("你好"), 1);
        entries.append(user);
        entries.append(reply);
        sessions.insert(restore(SessionStatus.IDLE, reply.id()));

        assertThat(closer.closeDangling(SESSION_ID)).isZero();
        assertThat(sessions.findById(SESSION_ID).orElseThrow().version())
                .isEqualTo(ChatSession.INITIAL_VERSION);
        assertThat(entries.countBySession(SESSION_ID)).isEqualTo(2);
    }

    private void danglingSession(SessionStatus status) {
        SessionEntry user = SessionEntryFactory.message(SESSION_ID, 1, null,
                SessionEntryFactory.user("写文件"), 1);
        SessionEntry call = SessionEntryFactory.message(SESSION_ID, 2, user.id(),
                SessionEntryFactory.assistantWithToolCall("call-1", "write"), 1);
        entries.append(user);
        entries.append(call);
        sessions.insert(restore(status, call.id()));
    }

    private ChatSession restore(SessionStatus status, EntryId leaf) {
        return ChatSession.restore(SessionIdentity.root(SESSION_ID), "测试会话",
                SessionConfig.forWorkspace(Path.of("D:/demo"), "fake-demo"),
                status, leaf, ChatSession.INITIAL_VERSION);
    }
}

package com.piagent.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.app.PiJavaApplication;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.Origin;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Ulid;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2 + Flyway + MyBatis 的会话持久化集成测试。
 *
 * <p>覆盖聚合读写、乐观锁、entry 分支加载、唯一键与两级外键行为。
 */
@SpringBootTest(classes = PiJavaApplication.class)
@ActiveProfiles("test")
class SessionPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private SessionRepository repository;

    @Autowired
    private SessionEntryStore entryStore;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
    }

    @Test
    void sessionRoundTripPreservesMetadata() {
        ChatSession session = rootSession(1, "持久化", "target/ws-a");
        repository.insert(session);

        ChatSession loaded = repository.findById(session.identity().id()).orElseThrow();

        assertThat(loaded.title()).isEqualTo("持久化");
        assertThat(loaded.config().cwd()).isEqualTo(workspace("target/ws-a"));
        assertThat(loaded.config().modelProfileId()).isEqualTo("fake-model");
        assertThat(loaded.config().thinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(loaded.config().sandbox().writableRoots()).containsExactly(workspace("target/ws-a"));
        assertThat(loaded.status()).isEqualTo(session.status());
        assertThat(loaded.leafEntryId()).isNull();
        assertThat(loaded.version()).isEqualTo(ChatSession.INITIAL_VERSION);
    }

    @Test
    void findByCwdFiltersByWorkspace() {
        ChatSession first = rootSession(1, "工作区 A", "target/ws-a");
        ChatSession second = rootSession(2, "工作区 B", "target/ws-b");
        repository.insert(first);
        repository.insert(second);

        List<ChatSession> found = repository.findByCwd(workspace("target/ws-a"), 10);

        assertThat(found).extracting(session -> session.identity().id()).containsExactly(first.identity().id());
    }

    @Test
    void findChildrenReturnsChildSession() {
        ChatSession parent = rootSession(1, "父会话", "target/ws-a");
        repository.insert(parent);
        ChatSession child = ChatSession.createChild(sid(2), parent, null, Origin.SUBAGENT,
                "子会话", parent.config());
        repository.insert(child);

        List<ChatSession> children = repository.findChildren(parent.identity().id());

        assertThat(children).hasSize(1);
        assertThat(children.getFirst().identity().parentSessionId()).isEqualTo(parent.identity().id());
        assertThat(children.getFirst().identity().depth()).isEqualTo(1);
        assertThat(children.getFirst().identity().rootSessionId()).isEqualTo(parent.identity().id());
    }

    @Test
    void updateUsesVersionAsCasCondition() {
        ChatSession session = rootSession(1, "CAS", "target/ws-a");
        repository.insert(session);
        ChatSession loaded = repository.findById(session.identity().id()).orElseThrow();

        repository.update(loaded.rename("第一次更新"));

        assertThatThrownBy(() -> repository.update(loaded.rename("过期版本更新")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.CONCURRENT_MODIFICATION));
        assertThat(repository.findById(session.identity().id()).orElseThrow().title()).isEqualTo("第一次更新");
    }

    @Test
    void appendAndLoadBranchRoundTrip() {
        ChatSession session = rootSession(1, "分支", "target/ws-a");
        repository.insert(session);
        SessionEntry first = entry(session.identity().id(), eid(1), 1, null,
                new MessagePayload(new UserMessage(List.of(new TextBlock("你好")), NOW)));
        SessionEntry second = entry(session.identity().id(), eid(2), 2, first.id(),
                new BranchSummaryPayload(eid(9), "分支摘要", Map.of("reason", "test")));
        entryStore.append(first);
        entryStore.append(second);
        repository.update(session.moveLeaf(second.id()));

        List<SessionEntry> branch = entryStore.loadBranch(session.identity().id(), second.id());

        assertThat(branch).extracting(SessionEntry::id).containsExactly(first.id(), second.id());
        assertThat(branch.get(0).payload()).isInstanceOf(MessagePayload.class);
        MessagePayload message = (MessagePayload) branch.get(0).payload();
        assertThat(((UserMessage) message.message()).content()).containsExactly(new TextBlock("你好"));
        assertThat(branch.get(1).type()).isEqualTo(EntryType.BRANCH_SUMMARY);
        assertThat(entryStore.countBySession(session.identity().id())).isEqualTo(2);
        assertThat(entryStore.findById(session.identity().id(), first.id()).orElseThrow().seq()).isEqualTo(1);
    }

    @Test
    void loadBranchRejectsUnknownLeaf() {
        ChatSession session = rootSession(1, "缺失", "target/ws-a");
        repository.insert(session);

        assertThatThrownBy(() -> entryStore.loadBranch(session.identity().id(), eid(99)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.ENTRY_NOT_FOUND));
    }

    @Test
    void duplicateSessionSeqIsRejectedByUniqueKey() {
        ChatSession session = rootSession(1, "唯一键", "target/ws-a");
        repository.insert(session);
        SessionEntry first = entry(session.identity().id(), eid(1), 1, null, textPayload("a"));
        entryStore.append(first);
        SessionEntry duplicate = entry(session.identity().id(), eid(2), 1, null, textPayload("b"));

        assertThatThrownBy(() -> entryStore.append(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteAllRemovesOnlyTargetSessionLog() {
        ChatSession target = rootSession(1, "撤销", "target/ws-a");
        ChatSession other = rootSession(2, "保留", "target/ws-b");
        repository.insert(target);
        repository.insert(other);
        entryStore.append(entry(target.identity().id(), eid(1), 1, null, textPayload("a")));
        entryStore.append(entry(target.identity().id(), eid(2), 2, eid(1), textPayload("b")));
        entryStore.append(entry(other.identity().id(), eid(3), 1, null, textPayload("c")));

        entryStore.deleteAll(target.identity().id());

        assertThat(entryStore.countBySession(target.identity().id())).isZero();
        assertThat(entryStore.countBySession(other.identity().id())).isEqualTo(1);
    }

    @Test
    void deletingSessionCascadesEntries() {
        ChatSession session = rootSession(1, "级联", "target/ws-a");
        repository.insert(session);
        entryStore.append(entry(session.identity().id(), eid(1), 1, null, textPayload("a")));
        entryStore.append(entry(session.identity().id(), eid(2), 2, eid(1), textPayload("b")));

        jdbc.update("DELETE FROM pi_session WHERE id = ?", session.identity().id().toString());

        assertThat(countEntries()).isZero();
    }

    @Test
    void deletingParentSessionKeepsChildWithNullParent() {
        ChatSession parent = rootSession(1, "父", "target/ws-a");
        repository.insert(parent);
        ChatSession child = ChatSession.createChild(sid(2), parent, null, Origin.SUBAGENT, "子", parent.config());
        repository.insert(child);

        jdbc.update("DELETE FROM pi_session WHERE id = ?", parent.identity().id().toString());

        String parentId = jdbc.queryForObject(
                "SELECT parent_session_id FROM pi_session WHERE id = ?", String.class,
                child.identity().id().toString());
        assertThat(parentId).isNull();
        ChatSession orphan = repository.findById(child.identity().id()).orElseThrow();
        assertThat(orphan.identity().parentSessionId()).isNull();
        assertThat(orphan.identity().rootSessionId()).isEqualTo(parent.identity().id());
        assertThat(orphan.identity().origin()).isEqualTo(Origin.SUBAGENT);
        assertThat(orphan.identity().depth()).isEqualTo(1);
    }

    private long countEntries() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM pi_session_entry", Long.class);
        return count == null ? 0 : count;
    }

    private ChatSession rootSession(int n, String title, String cwd) {
        Path root = workspace(cwd);
        SessionConfig config = new SessionConfig(root, "fake-model", ThinkingLevel.MEDIUM,
                SandboxSnapshot.forWorkspace(root));
        return ChatSession.createRoot(sid(n), title, config);
    }

    private SessionEntry entry(SessionId sessionId, EntryId id, long seq, EntryId parent, EntryPayload payload) {
        return new SessionEntry(id, sessionId, seq, parent, payload.entryType(),
                SessionEntry.CURRENT_PAYLOAD_VERSION, payload, 5, NOW.plusSeconds(seq));
    }

    private EntryPayload textPayload(String text) {
        return new MessagePayload(new UserMessage(List.of(new TextBlock(text)), NOW));
    }

    private static Path workspace(String relative) {
        return Path.of(relative).toAbsolutePath().normalize();
    }

    private static SessionId sid(int n) {
        return new SessionId(Ulid.parse("01SESS10" + String.format("%018d", n)));
    }

    private static EntryId eid(int n) {
        return new EntryId(Ulid.parse("01ENTRY00" + String.format("%017d", n)));
    }
}

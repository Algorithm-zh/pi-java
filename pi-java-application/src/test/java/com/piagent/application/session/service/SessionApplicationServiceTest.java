package com.piagent.application.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.session.command.AppendUserMessageCommand;
import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.command.ForkSessionCommand;
import com.piagent.application.session.command.MoveBranchCommand;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.Origin;
import com.piagent.domain.session.model.ProjectedContext;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.BranchSummaryMessage;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.service.SessionBranchPolicy;
import com.piagent.domain.session.service.SessionContextProjector;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Ulid;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 会话用例服务的单元测试。
 *
 * <p>使用内存端口替代 MyBatis，专注验证编排逻辑：seq 分配、leaf 移动、
 * fork 子会话与错误分支；真实 SQL 行为由 app 模块的 H2 集成测试覆盖。
 */
class SessionApplicationServiceTest {

    private static final Path WORKSPACE = Path.of("target/unit-workspace").toAbsolutePath().normalize();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private InMemorySessionRepository repository;
    private InMemorySessionEntryStore entryStore;
    private SessionApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemorySessionRepository();
        entryStore = new InMemorySessionEntryStore();
        service = new SessionApplicationService(repository, entryStore, new SessionContextProjector(),
                new SessionBranchPolicy(), new SequentialIdGenerator(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createSessionStoresRootIdentityAndDefaults() {
        ChatSession session = createSession("单测");

        assertThat(repository.findById(session.identity().id())).isPresent();
        assertThat(session.identity().origin()).isEqualTo(Origin.USER);
        assertThat(session.identity().depth()).isZero();
        assertThat(session.identity().rootSessionId()).isEqualTo(session.identity().id());
        assertThat(session.identity().parentSessionId()).isNull();
        assertThat(session.config().cwd()).isEqualTo(WORKSPACE);
        assertThat(session.config().modelProfileId()).isEqualTo("fake-model");
        assertThat(session.config().thinkingLevel()).isEqualTo(ThinkingLevel.OFF);
        assertThat(session.config().sandbox().writableRoots()).containsExactly(WORKSPACE);
        assertThat(session.version()).isEqualTo(ChatSession.INITIAL_VERSION);
        assertThat(session.isEmpty()).isTrue();
    }

    @Test
    void appendUserMessageAssignsMonotonicSeqAndMovesLeaf() {
        ChatSession session = createSession("追加");

        SessionEntry first = append(session, "第一句");
        SessionEntry second = append(session, "第二句");

        assertThat(first.seq()).isEqualTo(1);
        assertThat(first.parentId()).isNull();
        assertThat(second.seq()).isEqualTo(2);
        assertThat(second.parentId()).isEqualTo(first.id());
        ChatSession reloaded = repository.findById(session.identity().id()).orElseThrow();
        assertThat(reloaded.leafEntryId()).isEqualTo(second.id());
        assertThat(reloaded.version()).isEqualTo(3);

        ProjectedContext context = service.projectContext(session.identity().id(), null);
        assertThat(context.trace()).containsExactly(first.id(), second.id());
        assertThat(context.messages()).hasSize(2);
        assertThat(((UserMessage) context.messages().getFirst()).content())
                .containsExactly(new TextBlock("第一句"));
    }

    @Test
    void appendRejectsMissingSession() {
        SessionId missing = missingSessionId();

        assertThatThrownBy(() -> service.appendUserMessage(new AppendUserMessageCommand(missing, "hi")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.SESSION_NOT_FOUND));
    }

    @Test
    void appendRejectsNonIdleSession() {
        ChatSession session = createSession("运行中");
        repository.update(session.markRunning());

        assertThatThrownBy(() -> append(session, "hi"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void moveBranchRewindsLeafAndRejectsUnknownTarget() {
        ChatSession session = createSession("分支");
        SessionEntry first = append(session, "a");
        append(session, "b");

        ChatSession moved = service.moveBranch(new MoveBranchCommand(session.identity().id(), first.id()));

        assertThat(moved.leafEntryId()).isEqualTo(first.id());
        assertThat(service.projectContext(session.identity().id(), null).trace()).containsExactly(first.id());
        assertThatThrownBy(() -> service.moveBranch(new MoveBranchCommand(session.identity().id(), entryId(900))))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.ENTRY_NOT_FOUND));
    }

    @Test
    void forkCreatesChildWithBranchSummaryAndKeepsParentIntact() {
        ChatSession source = createSession("源");
        SessionEntry first = append(source, "第一轮");
        append(source, "第二轮");

        ChatSession child = service.fork(new ForkSessionCommand(source.identity().id(), first.id(), "探索分支"));

        assertThat(child.title()).isEqualTo("探索分支");
        assertThat(child.identity().origin()).isEqualTo(Origin.FORK);
        assertThat(child.identity().parentSessionId()).isEqualTo(source.identity().id());
        assertThat(child.identity().rootSessionId()).isEqualTo(source.identity().id());
        assertThat(child.identity().forkFromEntryId()).isEqualTo(first.id());
        assertThat(child.identity().depth()).isEqualTo(1);
        assertThat(child.config().cwd()).isEqualTo(source.config().cwd());
        assertThat(child.version()).isEqualTo(2);
        assertThat(repository.findChildren(source.identity().id())).extracting(ChatSession::title)
                .containsExactly("探索分支");

        ProjectedContext childContext = service.projectContext(child.identity().id(), null);
        BranchSummaryMessage summary = (BranchSummaryMessage) childContext.messages().getFirst();
        assertThat(summary.fromEntryId()).isEqualTo(first.id());
        assertThat(summary.summary()).contains(source.identity().id().toString());
        assertThat(service.projectContext(source.identity().id(), null).messages()).hasSize(2);
    }

    @Test
    void forkWithoutTitleGeneratesDefaultTitle() {
        ChatSession source = createSession("源");
        SessionEntry first = append(source, "起点");

        ChatSession child = service.fork(new ForkSessionCommand(source.identity().id(), first.id(), null));

        assertThat(child.title()).startsWith("分支 ").contains(source.identity().id().toString());
    }

    @Test
    void forkRejectsMissingSourceOrEntry() {
        assertThatThrownBy(() -> service.fork(new ForkSessionCommand(missingSessionId(), entryId(900), null)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.SESSION_NOT_FOUND));

        ChatSession source = createSession("源");
        assertThatThrownBy(() -> service.fork(new ForkSessionCommand(source.identity().id(), entryId(901), null)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.ENTRY_NOT_FOUND));
    }

    @Test
    void emptySessionProjectsEmptyContext() {
        ChatSession session = createSession("空");

        ProjectedContext context = service.projectContext(session.identity().id(), null);

        assertThat(context.isEmpty()).isTrue();
    }

    @Test
    void projectContextAcceptsExplicitHistoricLeaf() {
        ChatSession session = createSession("显式叶子");
        SessionEntry first = append(session, "a");
        append(session, "b");

        ProjectedContext context = service.projectContext(session.identity().id(), first.id());

        assertThat(context.trace()).containsExactly(first.id());
    }

    private ChatSession createSession(String title) {
        return service.createSession(new CreateSessionCommand(title, WORKSPACE, "fake-model", null));
    }

    private SessionEntry append(ChatSession session, String text) {
        return service.appendUserMessage(new AppendUserMessageCommand(session.identity().id(), text));
    }

    private static SessionId missingSessionId() {
        return new SessionId(Ulid.parse("01SESS10" + String.format("%018d", 999)));
    }

    private static EntryId entryId(int n) {
        return new EntryId(Ulid.parse("01ENTRY00" + String.format("%017d", n)));
    }
}

package com.piagent.app.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.app.PiJavaApplication;
import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.application.session.command.AppendUserMessageCommand;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.command.ForkSessionCommand;
import com.piagent.application.session.command.MoveBranchCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.application.session.service.SessionTitleBackfillService;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.Origin;
import com.piagent.domain.session.model.ProjectedContext;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.message.BranchSummaryMessage;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Ulid;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 会话用例 + H2 持久化的端到端集成测试。
 *
 * <p>验证创建、追加消息、上下文投影、分支回退与 fork 子会话在真实仓储上的行为。
 */
@SpringBootTest(classes = PiJavaApplication.class)
@ActiveProfiles("test")
class SessionApplicationIntegrationTest {

    @Autowired
    private SessionApplicationService service;

    @Autowired
    private SessionRepository repository;

    @Autowired
    private SessionEntryAppender appender;

    @Autowired
    private SessionTitleBackfillService titleBackfill;

    @Autowired
    private JdbcTemplate jdbc;

    private Path workspace;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        workspace = Path.of("target/app-workspace").toAbsolutePath().normalize();
    }

    @Test
    void createAppendAndProjectContext() {
        ChatSession session = createSession("端到端");
        SessionEntry first = append(session, "你好");
        SessionEntry second = append(session, "继续");

        ProjectedContext context = service.projectContext(session.identity().id(), null);

        assertThat(context.messages()).hasSize(2);
        assertThat(context.trace()).containsExactly(first.id(), second.id());
        UserMessage message = (UserMessage) context.messages().getFirst();
        assertThat(message.content()).containsExactly(new TextBlock("你好"));
        assertThat(context.estimatedTokens()).isPositive();

        ChatSession reloaded = repository.findById(session.identity().id()).orElseThrow();
        assertThat(reloaded.leafEntryId()).isEqualTo(second.id());
        assertThat(reloaded.version()).isEqualTo(3);
    }

    @Test
    void moveBranchRewindsToEarlierEntry() {
        ChatSession session = createSession("分支回退");
        SessionEntry first = append(session, "第一步");
        append(session, "第二步");

        ChatSession moved = service.moveBranch(new MoveBranchCommand(session.identity().id(), first.id()));
        ProjectedContext context = service.projectContext(session.identity().id(), null);

        assertThat(moved.leafEntryId()).isEqualTo(first.id());
        assertThat(context.trace()).containsExactly(first.id());
        assertThat(context.messages()).hasSize(1);
    }

    @Test
    void forkCreatesChildSessionWithBranchSummary() {
        ChatSession source = createSession("源会话");
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
        assertThat(child.leafEntryId()).isNotNull();
        assertThat(child.version()).isEqualTo(2);

        ProjectedContext childContext = service.projectContext(child.identity().id(), null);
        assertThat(childContext.messages()).hasSize(1);
        BranchSummaryMessage summary = (BranchSummaryMessage) childContext.messages().getFirst();
        assertThat(summary.fromEntryId()).isEqualTo(first.id());
        assertThat(summary.summary()).contains("fork").contains(source.identity().id().toString());

        List<ChatSession> children = repository.findChildren(source.identity().id());
        assertThat(children).extracting(session -> session.identity().id())
                .containsExactly(child.identity().id());
        assertThat(service.projectContext(source.identity().id(), null).messages()).hasSize(2);
    }

    @Test
    void unknownSessionIsReportedAsDomainError() {
        SessionId missing = new SessionId(Ulid.parse("01SESS10" + String.format("%018d", 999)));

        assertThatThrownBy(() -> service.projectContext(missing, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.SESSION_NOT_FOUND));
    }

    @Test
    void blankTitleFallsBackToPlaceholder() {
        ChatSession session = createSession(null);

        assertThat(session.title()).isEqualTo("新会话");
    }

    @Test
    void firstMessageNamesPlaceholderSession() {
        ChatSession session = createSession("新会话");

        appender.appendUserAndStart(session.identity().id(), "帮我看看这个项目为什么启动失败");

        ChatSession reloaded = repository.findById(session.identity().id()).orElseThrow();
        assertThat(reloaded.title()).isEqualTo("帮我看看这个项目为什么启动失败");
        assertThat(reloaded.status()).isEqualTo(SessionStatus.RUNNING);
    }

    @Test
    void backfillRenamesOnlyPlaceholderTitles() {
        ChatSession placeholder = createSession("新会话");
        append(placeholder, "把文档里的旧链接清理一下");
        ChatSession manual = createSession("排障记录");
        append(manual, "第一轮");

        int renamed = titleBackfill.backfill();

        assertThat(renamed).isEqualTo(1);
        assertThat(repository.findById(placeholder.identity().id()).orElseThrow().title())
                .isEqualTo("把文档里的旧链接清理一下");
        assertThat(repository.findById(manual.identity().id()).orElseThrow().title())
                .isEqualTo("排障记录");
    }

    private ChatSession createSession(String title) {
        return service.createSession(new CreateSessionCommand(title, workspace, "fake-model", null));
    }

    private SessionEntry append(ChatSession session, String text) {
        return service.appendUserMessage(new AppendUserMessageCommand(session.identity().id(), text));
    }
}

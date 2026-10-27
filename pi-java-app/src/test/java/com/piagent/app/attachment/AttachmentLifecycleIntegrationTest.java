package com.piagent.app.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.app.PiJavaApplication;
import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.application.attachment.AttachmentReclaimResult;
import com.piagent.application.attachment.AttachmentStats;
import com.piagent.application.attachment.port.AttachmentReferenceSource;
import com.piagent.application.attachment.port.AttachmentStore;
import com.piagent.application.attachment.service.AttachmentApplicationService;
import com.piagent.application.attachment.service.AttachmentMaintenanceService;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.ThinkingLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 附件生命周期集成测试。
 *
 * <p>真实 H2 与文件存储：验证内容去重、统计、payload 引用扫描，以及回收任务
 * 只删除无引用的附件。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class,
        properties = {"pi.fake.demo-mode=true", "pi.attachments.gc-min-age=0s"})
@ActiveProfiles("test")
class AttachmentLifecycleIntegrationTest {

    private static final String MODEL_ID = "attachment-lifecycle-fake";

    @Autowired
    private AttachmentApplicationService attachments;

    @Autowired
    private AttachmentMaintenanceService maintenance;

    @Autowired
    private AttachmentReferenceSource references;

    @Autowired
    private AttachmentStore store;

    @Autowired
    private AgentLoopService agentLoop;

    @Autowired
    private SessionApplicationService sessions;

    @Autowired
    private ModelProfileRepository models;

    @Autowired
    private JdbcTemplate jdbc;

    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspace = Path.of("target/attachment-lifecycle-workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "Attachment Fake",
                ModelEndpoint.fake(MODEL_ID)));
    }

    @Test
    void deduplicatesContentAndReclaimsOnlyUnreferencedAttachments() {
        byte[] png = png(7);
        AttachmentMetadata referenced = attachments.upload("keep.png", png);
        AttachmentMetadata duplicate = attachments.upload("copy.png", png);
        AttachmentMetadata orphan = attachments.upload("orphan.png", png(8));

        assertThat(duplicate.id()).isEqualTo(referenced.id());

        ChatSession session = sessions.createSession(
                new CreateSessionCommand("附件回收", workspace, MODEL_ID, ThinkingLevel.OFF));
        agentLoop.awaitTerminal(agentLoop.start(new SendMessageCommand(
                session.identity().id(), "这张图里有什么", List.of(referenced.id())))
                .runId(), Duration.ofSeconds(15));

        assertThat(references.referencedIds()).contains(referenced.id())
                .doesNotContain(orphan.id());

        AttachmentStats stats = attachments.stats();
        assertThat(stats.count()).isGreaterThanOrEqualTo(2);
        assertThat(stats.bytes()).isGreaterThanOrEqualTo(referenced.size() + orphan.size());

        AttachmentReclaimResult result = maintenance.reclaim();

        assertThat(result.deleted()).isGreaterThanOrEqualTo(1);
        assertThat(store.find(referenced.id())).isPresent();
        assertThat(store.find(orphan.id())).isEmpty();
    }

    private static byte[] png(int marker) {
        return new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, (byte) marker
        };
    }
}

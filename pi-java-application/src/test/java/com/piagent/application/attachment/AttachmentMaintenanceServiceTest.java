package com.piagent.application.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.attachment.service.AttachmentApplicationService;
import com.piagent.application.attachment.service.AttachmentMaintenanceService;
import com.piagent.application.testsupport.InMemoryAttachmentStore;
import com.piagent.application.testsupport.SequentialIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 附件回收测试：只有同时满足“无引用”和“超过宽限期”的附件才会被删除。
 */
class AttachmentMaintenanceServiceTest {

    private static final Instant START = Instant.parse("2026-10-27T10:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryAttachmentStore store =
            new InMemoryAttachmentStore(new SequentialIdGenerator(), clock);
    private final AttachmentApplicationService uploads =
            new AttachmentApplicationService(store, (content, format) -> content, 0);

    @Test
    void deletesOnlyUnreferencedExpiredAttachments() {
        AttachmentMetadata orphan = uploads.upload("orphan.png", png(1));
        clock.advance(Duration.ofMinutes(30));
        AttachmentMetadata referenced = uploads.upload("keep.png", png(2));
        clock.advance(Duration.ofHours(1));
        AttachmentMetadata fresh = uploads.upload("fresh.png", png(3));
        clock.advance(Duration.ofMinutes(30));

        AttachmentReclaimResult result = maintenance(Duration.ofHours(1), Set.of(referenced.id()))
                .reclaim();

        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.freedBytes()).isEqualTo(orphan.size());
        assertThat(store.find(orphan.id())).isEmpty();
        assertThat(store.find(referenced.id())).isPresent();
        assertThat(store.find(fresh.id())).isPresent();
    }

    @Test
    void keepsEverythingWhenNothingIsReferencedButGracePeriodHasNotElapsed() {
        uploads.upload("one.png", png(1));

        AttachmentReclaimResult result = maintenance(Duration.ofHours(2), Set.of()).reclaim();

        assertThat(result.deleted()).isZero();
        assertThat(store.list()).hasSize(1);
    }

    @Test
    void normalizesNegativeGracePeriodToZero() {
        AttachmentMaintenanceService service = maintenance(Duration.ofHours(-1), Set.of());

        assertThat(service.minAge()).isZero();
    }

    private AttachmentMaintenanceService maintenance(Duration minAge, Set<String> referenced) {
        return new AttachmentMaintenanceService(store, () -> referenced, clock, minAge);
    }

    private static byte[] png(int marker) {
        return new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, (byte) marker
        };
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

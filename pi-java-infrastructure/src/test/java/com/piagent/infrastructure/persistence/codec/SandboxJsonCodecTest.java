package com.piagent.infrastructure.persistence.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 沙箱快照 JSON 编解码单元测试。
 */
class SandboxJsonCodecTest {

    private final SandboxJsonCodec codec = new SandboxJsonCodec(new ObjectMapper());

    @Test
    void roundTripsWritableRootsAndDenyGlobs() {
        Path root = Path.of("D:/code/pi-java").toAbsolutePath().normalize();
        SandboxSnapshot snapshot = new SandboxSnapshot(List.of(root), List.of("**/.env", "**/*.key"));

        SandboxSnapshot decoded = codec.decode(codec.encode(snapshot));

        assertThat(decoded).isEqualTo(snapshot);
    }

    @Test
    void emptyRootsAreRejectedOnDecode() {
        assertThatThrownBy(() -> codec.decode("{\"writableRoots\":[],\"denyGlobs\":[]}"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void malformedJsonIsReported() {
        assertThatThrownBy(() -> codec.decode("null"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }
}

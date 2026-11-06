package com.piagent.app;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RemoteBindingGuardTest {

    @Test
    void allowsLoopbackByDefault() {
        assertThatCode(() -> RemoteBindingGuard.verify(new String[0], null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsExplicitLoopbackAddress() {
        assertThatCode(() -> RemoteBindingGuard.verify(
                new String[] {"--server.address=127.0.0.1"}, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> RemoteBindingGuard.verify(
                new String[] {"--server.address=LOCALHOST"}, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> RemoteBindingGuard.verify(
                new String[] {"--server.address=::1"}, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonLoopbackAddressFromArguments() {
        assertThatThrownBy(() -> RemoteBindingGuard.verify(
                new String[] {"--server.address=0.0.0.0"}, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PI_ALLOW_REMOTE=true");
    }

    @Test
    void rejectsNonLoopbackAddressFromEnvironment() {
        assertThatThrownBy(() -> RemoteBindingGuard.verify(
                new String[0], "192.168.1.10", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("192.168.1.10");
    }

    @Test
    void argumentTakesPrecedenceOverEnvironment() {
        assertThatCode(() -> RemoteBindingGuard.verify(
                new String[] {"--server.address=127.0.0.1"}, "0.0.0.0", null))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsNonLoopbackWhenExplicitlyPermitted() {
        assertThatCode(() -> RemoteBindingGuard.verify(
                new String[] {"--server.address=0.0.0.0"}, null, "TRUE"))
                .doesNotThrowAnyException();
    }

    @Test
    void blankEnvironmentAddressFallsBackToLoopback() {
        assertThatCode(() -> RemoteBindingGuard.verify(new String[0], "  ", null))
                .doesNotThrowAnyException();
    }
}

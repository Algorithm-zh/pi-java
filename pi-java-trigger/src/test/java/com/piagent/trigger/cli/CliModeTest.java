package com.piagent.trigger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CliModeTest {

    @Test
    void noArgumentsStartsWeb() {
        assertThat(CliMode.isCli(new String[0])).isFalse();
    }

    @Test
    void serveStartsWeb() {
        assertThat(CliMode.isCli(new String[]{"serve"})).isFalse();
        assertThat(CliMode.isCli(new String[]{"serve", "--port", "9090"})).isFalse();
    }

    @Test
    void springArgumentsStartWeb() {
        assertThat(CliMode.isCli(new String[]{"--server.port=9090"})).isFalse();
        assertThat(CliMode.isCli(new String[]{"--spring.profiles.active=mysql"})).isFalse();
    }

    @Test
    void subcommandsAndRootHelpEnterCli() {
        assertThat(CliMode.isCli(new String[]{"run", "你好"})).isTrue();
        assertThat(CliMode.isCli(new String[]{"chat"})).isTrue();
        assertThat(CliMode.isCli(new String[]{"--help"})).isTrue();
        assertThat(CliMode.isCli(new String[]{"--version"})).isTrue();
    }

    @Test
    void serveOptionsMapToSpringProperties() {
        assertThat(CliMode.webArgs(new String[]{"serve", "--port", "9090", "--profile", "mysql"}))
                .containsExactly("--server.port=9090", "--spring.profiles.active=mysql");
        assertThat(CliMode.webArgs(new String[]{"serve", "--port=9091"}))
                .containsExactly("--server.port=9091");
        assertThat(CliMode.webArgs(new String[]{"serve", "--server.address=0.0.0.0"}))
                .containsExactly("--server.address=0.0.0.0");
    }

    @Test
    void webArgumentsPassThroughUnchanged() {
        String[] args = {"--server.port=9090"};
        assertThat(CliMode.webArgs(args)).isSameAs(args);
    }

    @Test
    void serveHelpIsDetectedOnlyForServe() {
        assertThat(CliMode.serveHelpRequested(new String[]{"serve", "--help"})).isTrue();
        assertThat(CliMode.serveHelpRequested(new String[]{"run", "--help"})).isFalse();
        assertThat(CliMode.serveHelpRequested(new String[0])).isFalse();
    }
}

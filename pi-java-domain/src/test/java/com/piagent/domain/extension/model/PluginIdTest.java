package com.piagent.domain.extension.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolName;
import org.junit.jupiter.api.Test;

class PluginIdTest {

    @Test
    void acceptsManifestStyleId() {
        PluginId id = new PluginId("com.example.hello");

        assertThat(id.value()).isEqualTo("com.example.hello");
        assertThat(id.toString()).isEqualTo("com.example.hello");
        assertThat(id.toolName("echo").value()).isEqualTo("com.example.hello.echo");
        assertThat(id.owns(new ToolName("com.example.hello.echo"))).isTrue();
        assertThat(id.owns(new ToolName("com.example.hello2.echo"))).isFalse();
    }

    @Test
    void acceptsHyphenatedSegments() {
        assertThat(new PluginId("com.example.hello-world").value())
                .isEqualTo("com.example.hello-world");
    }

    @Test
    void rejectsInvalidCharacters() {
        assertThatThrownBy(() -> new PluginId("Com.Example"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> new PluginId("com..hello"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PluginId("com.example."))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PluginId(" "))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsOverlongId() {
        String value = "a" + "b".repeat(128);
        assertThatThrownBy(() -> new PluginId(value))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }
}

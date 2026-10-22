package com.piagent.domain.extension.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.extension.model.ApiVersion;
import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolName;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginCompatibilityCheckerTest {

    private static final PluginId ID = new PluginId("com.example.hello");

    @Test
    void acceptsSupportedMajor() {
        assertThatCode(() -> PluginCompatibilityChecker.requireCompatible(new ApiVersion(1, 9)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsupportedMajor() {
        assertThatThrownBy(() ->
                PluginCompatibilityChecker.requireCompatible(new ApiVersion(2, 0)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PLUGIN_INCOMPATIBLE));
    }

    @Test
    void toolMustCarryPluginPrefix() {
        ToolName prefixed = ID.toolName("echo");
        assertThatCode(() -> PluginCompatibilityChecker.requireToolPrefix(ID, prefixed))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> PluginCompatibilityChecker.requireToolPrefix(ID,
                new ToolName("other.echo")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PLUGIN_TOOL_CONFLICT));
    }

    @Test
    void rejectsExistingToolName() {
        ToolName candidate = ID.toolName("echo");
        assertThatThrownBy(() -> PluginCompatibilityChecker.requireNoConflict(candidate,
                List.of(candidate)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PLUGIN_TOOL_CONFLICT));
    }
}

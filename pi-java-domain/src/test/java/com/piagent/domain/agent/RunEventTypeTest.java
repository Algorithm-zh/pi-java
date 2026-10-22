package com.piagent.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * SSE/CLI 事件契约测试：名称稳定、唯一，终态判断正确。
 */
class RunEventTypeTest {

    @Test
    void wireNamesAreStableAndUnique() {
        Set<String> names = Arrays.stream(RunEventType.values())
                .map(RunEventType::wireName)
                .collect(Collectors.toSet());

        assertThat(names).hasSameSizeAs(RunEventType.values());
        assertThat(names).contains("run.started", "turn.started", "message.started",
                "message.delta", "message.completed", "tool.started", "tool.completed",
                "run.completed", "run.failed", "heartbeat", "replay.gap");
    }

    @Test
    void onlyCompletedAndFailedAreTerminal() {
        assertThat(RunEventType.RUN_COMPLETED.isTerminal()).isTrue();
        assertThat(RunEventType.RUN_FAILED.isTerminal()).isTrue();
        assertThat(RunEventType.TOOL_COMPLETED.isTerminal()).isFalse();
        assertThat(RunEventType.MESSAGE_COMPLETED.isTerminal()).isFalse();
        assertThat(RunEventType.HEARTBEAT.isTerminal()).isFalse();
    }
}

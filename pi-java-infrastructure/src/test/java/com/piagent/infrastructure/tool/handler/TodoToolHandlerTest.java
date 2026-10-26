package com.piagent.infrastructure.tool.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.piagent.application.plan.SessionPlanService;
import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.plan.model.Plan;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Ulid;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class TodoToolHandlerTest {

    @TempDir
    Path workspace;

    private final SessionPlanService plans = mock(SessionPlanService.class);
    private final TodoToolHandler handler = new TodoToolHandler(plans);

    @Test
    void replacesWholePlanAndReturnsSummary() {
        SessionId sessionId = sessionId();

        ToolResult result = handler.execute(invocation(sessionId, List.of(
                Map.of("content", "读代码", "status", "done"),
                Map.of("content", "写实现", "status", "in_progress"),
                Map.of("content", "跑测试", "status", "pending"))));

        ArgumentCaptor<Plan> plan = ArgumentCaptor.forClass(Plan.class);
        verify(plans).replace(eq(sessionId), plan.capture());
        assertThat(plan.getValue().items()).extracting(item -> item.status())
                .containsExactly(PlanItemStatus.DONE, PlanItemStatus.IN_PROGRESS,
                        PlanItemStatus.PENDING);
        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("待办已更新（1/3 完成）", "当前：写实现",
                "[x] 读代码", "[>] 写实现", "[ ] 跑测试");
        assertThat(result.details()).containsEntry("total", 3)
                .containsEntry("completed", 1L);
    }

    @Test
    void emptyListClearsPlan() {
        SessionId sessionId = sessionId();

        ToolResult result = handler.execute(invocation(sessionId, List.of()));

        assertThat(result.content()).contains("已清空");
        verify(plans).replace(eq(sessionId), any(Plan.class));
    }

    @Test
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> handler.execute(invocation(sessionId(), List.of(
                Map.of("content", "第一步", "status", "blocked")))))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void rejectsMoreThanTwentyItems() {
        List<Map<String, Object>> todos = IntStream.range(0, Plan.MAX_ITEMS + 1)
                .mapToObj(index -> Map.<String, Object>of("content", "步骤 " + index,
                        "status", "pending"))
                .toList();

        assertThatThrownBy(() -> handler.execute(invocation(sessionId(), todos)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void rejectsUnknownItemField() {
        assertThatThrownBy(() -> handler.execute(invocation(sessionId(), List.of(
                Map.of("content", "第一步", "status", "pending", "owner", "me")))))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void requiresOriginSession() {
        ToolInvocation invocation = new ToolInvocation(new ToolCallId("call-1"),
                new ToolName("todo"), Map.of("todos", List.of()),
                SandboxPolicy.readOnly(workspace));

        assertThatThrownBy(() -> handler.execute(invocation))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_EXECUTION_FAILED));
    }

    private ToolInvocation invocation(SessionId sessionId, List<Map<String, Object>> todos) {
        return new ToolInvocation(new ToolCallId("call-1"), new ToolName("todo"),
                Map.of("todos", todos), SandboxPolicy.readOnly(workspace), sessionId);
    }

    private static SessionId sessionId() {
        return SessionId.parse(Ulid.generate(Instant.parse("2026-10-26T00:00:00Z")).toString());
    }
}

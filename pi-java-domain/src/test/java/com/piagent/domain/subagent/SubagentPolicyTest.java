package com.piagent.domain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 子代理策略与请求校验测试。
 *
 * <p>重点是三条不变量：深度上限、超时不静默截断、请求参数在进入执行前就被拒绝。</p>
 */
class SubagentPolicyTest {

    private final SubagentPolicy policy = SubagentPolicy.withMaxDepth(2);

    @Test
    void defaultPolicyAllowsUserAndOneLevelBelow() {
        SubagentPolicy defaults = SubagentPolicy.defaults();

        assertThat(defaults.maxDepth()).isEqualTo(2);
        assertThat(defaults.allowsChildOf(0)).as("用户会话可以派生子代理").isTrue();
        assertThat(defaults.allowsChildOf(1)).as("子代理可以派发孙代理").isTrue();
        assertThat(defaults.allowsChildOf(2)).as("深度 2 不能再派生").isFalse();
    }

    @Test
    void rejectsInvalidTimeoutConfiguration() {
        assertThatThrownBy(() -> new SubagentPolicy(0, 60, 600))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SubagentPolicy(2, 5, 600))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SubagentPolicy(2, 600, 60))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void resolvesTimeoutWithDefaultAndUpperBound() {
        assertThat(policy.resolveTimeoutSeconds(null)).isEqualTo(300);
        assertThat(policy.resolveTimeoutSeconds(0)).isEqualTo(300);
        assertThat(policy.resolveTimeoutSeconds(120)).isEqualTo(120);
    }

    @Test
    void rejectsTimeoutAboveUpperBoundInsteadOfSilentlyTruncating() {
        assertThatThrownBy(() -> policy.resolveTimeoutSeconds(10_000))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void requestNormalizesWhitelistAndPersona() {
        // 用 Arrays.asList 而不是 List.of：后者不允许 null，而这里要验证 null 被过滤
        SubagentRequest request = new SubagentRequest(SubagentMode.SPAWN, "  分析代码  ",
                "   ", java.util.Arrays.asList(" read ", "read", "", null, "grep"), null);

        assertThat(request.task()).isEqualTo("分析代码");
        assertThat(request.persona()).isNull();
        assertThat(request.allowedTools()).containsExactly("read", "grep");
        assertThat(request.hasToolWhitelist()).isTrue();
        assertThat(request.timeoutSeconds()).isNull();
    }

    @Test
    void emptyWhitelistMeansInherit() {
        SubagentRequest request = new SubagentRequest(SubagentMode.FORK, "任务", null,
                List.of(), 60);

        assertThat(request.hasToolWhitelist()).isFalse();
        assertThat(request.timeoutSeconds()).isEqualTo(60);
    }

    @Test
    void rejectsBlankTaskAndOversizedWhitelist() {
        assertThatThrownBy(() -> new SubagentRequest(SubagentMode.SPAWN, "  ", null, null, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));

        List<String> tooMany = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> "tool" + index).toList();
        assertThatThrownBy(() -> new SubagentRequest(SubagentMode.SPAWN, "任务", null, tooMany, null))
                .isInstanceOf(DomainException.class);
    }
}

package com.piagent.domain.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.session.SessionFixtures;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.ImageBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.ModelChangePayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * entry 级 token 估算测试。
 *
 * <p>重点：落库估算与投影汇总同源；审计 entry 不占上下文预算；结果与
 * {@link TokenEstimator#estimate(String)} 口径一致。</p>
 */
class EntryTokenCounterTest {

    private static final SessionId SESSION = SessionFixtures.sessionId(1);

    private final EntryTokenCounter counter = new EntryTokenCounter();
    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void countsMessagesWithSameRuleAsTextEstimator() {
        assertThat(counter.countMessage(SessionFixtures.user("hello world")))
                .isEqualTo(estimator.estimate("hello world").tokens());
        assertThat(counter.countMessage(SessionFixtures.user("你好世界"))).isEqualTo(4);
    }

    @Test
    void countsEachImageAsFixedCost() {
        UserMessage message = new UserMessage(List.of(
                new TextBlock("看这两张图"),
                new ImageBlock("att-1", "image/png", "a.png"),
                new ImageBlock("att-2", "image/jpeg", "b.jpg")), SessionFixtures.NOW);

        assertThat(counter.countMessage(message))
                .isEqualTo(estimator.estimate("看这两张图").tokens()
                        + 2 * EntryTokenCounter.IMAGE_TOKENS);
    }

    @Test
    void countsToolCallArgumentsAsContext() {
        int tokens = counter.countMessage(SessionFixtures.assistantWithToolCall("call-1", "read"));

        assertThat(tokens).isGreaterThan(estimator.estimate("calling tool").tokens());
    }

    @Test
    void emptyMessageStillReservesOneToken() {
        assertThat(counter.countMessage(SessionFixtures.assistant(""))).isEqualTo(1);
    }

    @Test
    void ignoresAuditOnlyPayloads() {
        SessionEntry label = SessionFixtures.label(SESSION, 1, null, SessionFixtures.entryId(1));
        BranchSummaryPayload branch = new BranchSummaryPayload(
                SessionFixtures.entryId(2), "分支摘要", Map.of());

        assertThat(counter.count(label.payload())).isZero();
        assertThat(counter.count(branch)).isPositive();
    }

    @Test
    void sumsOnlyContextEntries() {
        SessionEntry user = SessionFixtures.entry(SESSION, 1, null,
                new MessagePayload(SessionFixtures.user("aaaa")), 10);
        SessionEntry audit = SessionFixtures.entry(SESSION, 2, user.id(),
                new ModelChangePayload("m-1", ThinkingLevel.OFF), 99);

        assertThat(counter.sum(List.of(user, audit))).isEqualTo(109);
        assertThat(counter.sumContext(List.of(user, audit), Set.of(user.id()))).isEqualTo(10);
    }

    @Test
    void capsAtIntegerMaxValue() {
        SessionEntry huge = SessionFixtures.entry(SESSION, 1, null,
                new MessagePayload(SessionFixtures.user("x")), Integer.MAX_VALUE);

        assertThat(counter.sum(List.of(huge, huge))).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void rejectsNullPayload() {
        assertThatThrownBy(() -> counter.count(null)).isInstanceOf(NullPointerException.class);
        assertThatNoException().isThrownBy(() -> estimator.estimateChars(-1, 0));
    }

    @Test
    void estimateCharsMatchesStringEstimate() {
        String text = "abc你好defgh";

        assertThat(estimator.estimateChars(2, 8).tokens())
                .isEqualTo(estimator.estimate(text).tokens());
    }

    @Test
    void rejectsNegativeTokenEstimate() {
        assertThatThrownBy(() -> new TokenEstimate(-1, TokenEstimate.Method.HEURISTIC))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }
}

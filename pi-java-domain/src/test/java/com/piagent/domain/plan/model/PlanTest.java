package com.piagent.domain.plan.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PlanTest {

    @Test
    void emptyPlanHasNoCurrentItem() {
        Plan plan = Plan.empty();

        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.allDone()).isFalse();
        assertThat(plan.completedCount()).isZero();
        assertThat(plan.currentIndex()).isEmpty();
    }

    @Test
    void currentIndexPrefersInProgressThenPending() {
        Plan plan = new Plan(List.of(
                item("第一步", PlanItemStatus.DONE),
                item("第二步", PlanItemStatus.PENDING),
                item("第三步", PlanItemStatus.IN_PROGRESS)));

        assertThat(plan.currentIndex()).hasValue(2);
        assertThat(plan.completedCount()).isEqualTo(1);
        assertThat(plan.allDone()).isFalse();
    }

    @Test
    void currentIndexFallsBackToFirstPending() {
        Plan plan = new Plan(List.of(
                item("第一步", PlanItemStatus.DONE),
                item("第二步", PlanItemStatus.PENDING),
                item("第三步", PlanItemStatus.PENDING)));

        assertThat(plan.currentIndex()).hasValue(1);
    }

    @Test
    void allDoneWhenEveryItemIsDone() {
        Plan plan = new Plan(List.of(
                item("第一步", PlanItemStatus.DONE),
                item("第二步", PlanItemStatus.DONE)));

        assertThat(plan.allDone()).isTrue();
        assertThat(plan.completedCount()).isEqualTo(2);
        assertThat(plan.currentIndex()).isEmpty();
    }

    @Test
    void rejectsMoreThanTwentyItems() {
        List<PlanItem> items = IntStream.range(0, Plan.MAX_ITEMS + 1)
                .mapToObj(index -> item("步骤 " + index, PlanItemStatus.PENDING))
                .toList();

        assertThatThrownBy(() -> new Plan(items))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void rejectsTwoInProgressItems() {
        List<PlanItem> items = List.of(
                item("第一步", PlanItemStatus.IN_PROGRESS),
                item("第二步", PlanItemStatus.IN_PROGRESS));

        assertThatThrownBy(() -> new Plan(items))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void planItemTrimsAndRejectsBlankContent() {
        assertThat(new PlanItem("  写测试  ", PlanItemStatus.PENDING).content())
                .isEqualTo("写测试");
        assertThatThrownBy(() -> new PlanItem("   ", PlanItemStatus.PENDING))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void statusParsesWireNamesLeniently() {
        assertThat(PlanItemStatus.fromWire("in_progress")).isEqualTo(PlanItemStatus.IN_PROGRESS);
        assertThat(PlanItemStatus.fromWire("IN-PROGRESS")).isEqualTo(PlanItemStatus.IN_PROGRESS);
        assertThat(PlanItemStatus.fromWire(" done ")).isEqualTo(PlanItemStatus.DONE);
        assertThat(PlanItemStatus.IN_PROGRESS.wireName()).isEqualTo("in_progress");
        assertThatThrownBy(() -> PlanItemStatus.fromWire("blocked"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PlanItem item(String content, PlanItemStatus status) {
        return new PlanItem(content, status);
    }
}

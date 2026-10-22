package com.piagent.domain.extension.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

class ApiVersionTest {

    @Test
    void parsesMajorOnly() {
        ApiVersion version = ApiVersion.parse("1");

        assertThat(version.major()).isEqualTo(1);
        assertThat(version.minor()).isZero();
        assertThat(version.text()).isEqualTo("1.0");
    }

    @Test
    void parsesMajorMinor() {
        ApiVersion version = ApiVersion.parse("1.7");

        assertThat(version.text()).isEqualTo("1.7");
    }

    @Test
    void rejectsTrailingDotAndExtraSegments() {
        assertThatThrownBy(() -> ApiVersion.parse("1.2.3"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ApiVersion.parse("1."))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNonNumericAndNegative() {
        assertThatThrownBy(() -> ApiVersion.parse("one"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ApiVersion(-1, 0))
                .isInstanceOf(DomainException.class);
    }
}

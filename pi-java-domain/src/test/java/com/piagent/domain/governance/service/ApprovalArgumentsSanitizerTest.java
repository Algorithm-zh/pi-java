package com.piagent.domain.governance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.governance.model.ApprovalArguments;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 审批参数脱敏与 canonical 摘要测试。
 */
class ApprovalArgumentsSanitizerTest {

    @Test
    void digestIgnoresMapKeyOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("path", "a.txt");
        first.put("nested", Map.of("b", 2, "a", 1));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("nested", Map.of("a", 1, "b", 2));
        second.put("path", "a.txt");

        assertThat(ApprovalArgumentsSanitizer.sanitize(first).digest())
                .isEqualTo(ApprovalArgumentsSanitizer.sanitize(second).digest())
                .matches("[0-9a-f]{64}");
    }

    @Test
    void digestIsStableAcrossEquivalentNumbers() {
        ApprovalArguments integer = ApprovalArgumentsSanitizer.sanitize(Map.of("count", 1));
        ApprovalArguments decimal = ApprovalArgumentsSanitizer.sanitize(
                Map.of("count", new BigDecimal("1.00")));

        assertThat(integer.digest()).isEqualTo(decimal.digest());
    }

    @Test
    void redactsSensitiveKeysRecursively() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("path", "notes.txt");
        arguments.put("apiKey", "sk-secret");
        arguments.put("headers", Map.of("Authorization", "Bearer token", "Accept", "json"));
        arguments.put("items", List.of(Map.of("password", "p@ss"), Map.of("name", "demo")));

        ApprovalArguments sanitized = ApprovalArgumentsSanitizer.sanitize(arguments);

        assertThat(sanitized.redacted()).containsEntry("path", "notes.txt")
                .containsEntry("apiKey", "***");
        assertThat(asMap(sanitized.redacted().get("headers")))
                .containsEntry("Authorization", "***")
                .containsEntry("Accept", "json");
        assertThat(sanitized.redacted().toString()).doesNotContain("sk-secret", "p@ss", "Bearer token");
    }

    @Test
    void replacesNullValuesInDisplayCopyOnly() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("value", null);
        arguments.put("nested", Map.of("missing", "placeholder"));

        ApprovalArguments sanitized = ApprovalArgumentsSanitizer.sanitize(arguments);

        assertThat(sanitized.redacted()).containsEntry("value", "<null>");
        assertThat(sanitized.digest()).matches("[0-9a-f]{64}");
    }

    @Test
    void emptyArgumentsUseEmptyJsonObjectDigest() {
        ApprovalArguments empty = ApprovalArgumentsSanitizer.sanitize(Map.of());
        ApprovalArguments nul = ApprovalArgumentsSanitizer.sanitize(null);

        assertThat(empty.redacted()).isEmpty();
        assertThat(empty.digest()).isEqualTo(nul.digest());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}

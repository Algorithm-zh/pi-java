package com.piagent.infrastructure.tool.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeTokenizerTest {

    @Test
    void splitsCamelCaseAndSnakeCase() {
        assertThat(CodeTokenizer.tokenize("AttachmentService.MAX_FILE_SIZE"))
                .contains("attachment", "service", "max", "file", "size");
    }

    @Test
    void dropsEnglishStopWords() {
        assertThat(CodeTokenizer.tokenize("the user can use this tool"))
                .contains("user", "tool")
                .doesNotContain("the", "can", "use", "this");
    }

    @Test
    void splitsChineseIntoBigramsAndKeepsShortPhrase() {
        assertThat(CodeTokenizer.tokenize("审批流程"))
                .containsExactly("审批", "批流", "流程", "审批流程");
    }

    @Test
    void handlesMixedScripts() {
        assertThat(CodeTokenizer.tokenize("write 附件 quota"))
                .contains("write", "附件", "quota");
    }

    @Test
    void ignoresSingleCharacterAsciiTokens() {
        assertThat(CodeTokenizer.tokenize("a b c d")).isEmpty();
    }

    @Test
    void countsTermsAndHonoursLimit() {
        Map<String, Integer> counts = CodeTokenizer.counts("alpha alpha beta gamma", 2);

        assertThat(counts).hasSize(2).containsEntry("alpha", 2).containsEntry("beta", 1);
    }

    @Test
    void pathTokensIncludeDirectoriesAndExtension() {
        assertThat(CodeTokenizer.pathTokens("src/main/java/ToolAccessPolicy.java"))
                .contains("src", "main", "java", "tool", "access", "policy");
    }

    @Test
    void returnsEmptyForMissingText() {
        assertThat(CodeTokenizer.tokenize("")).isEmpty();
        assertThat(CodeTokenizer.tokenize(null)).isEmpty();
        assertThat(CodeTokenizer.pathTokens("")).isEmpty();
    }
}

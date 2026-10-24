package com.piagent.trigger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 内置斜杠命令的判定边界。
 *
 * <p>只有内置命令交给本地处理，其余 `/xxx` 必须原样发给模型，
 * 否则 Fake demo 的 `/write`、`/read`、`/skill:` 会被当成未知命令。</p>
 */
class CliChatSupportTest {

    @Test
    void recognizesBuiltinCommandsWithAndWithoutArguments() {
        assertThat(CliChatSupport.isCommand("/help")).isTrue();
        assertThat(CliChatSupport.isCommand("/exit")).isTrue();
        assertThat(CliChatSupport.isCommand("/model fake-demo")).isTrue();
    }

    @Test
    void treatsToolSkillAndPlainTextAsMessages() {
        assertThat(CliChatSupport.isCommand("/write notes.txt 你好")).isFalse();
        assertThat(CliChatSupport.isCommand("/read README.md")).isFalse();
        assertThat(CliChatSupport.isCommand("/skill:deploy 发布")).isFalse();
        assertThat(CliChatSupport.isCommand("你好")).isFalse();
    }
}

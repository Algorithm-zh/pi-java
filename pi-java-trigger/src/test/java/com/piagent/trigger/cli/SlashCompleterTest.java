package com.piagent.trigger.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;

class SlashCompleterTest {

    private final SlashCompleter completer = new SlashCompleter();

    @Test
    void completesMatchingSlashCommands() {
        List<Candidate> candidates = complete("/mo");

        assertThat(candidates).extracting(Candidate::value).containsExactly("/model");
    }

    @Test
    void suggestsAllCommandsForBareSlash() {
        List<Candidate> candidates = complete("/");

        assertThat(candidates).extracting(Candidate::value)
                .contains("/help", "/exit", "/compact", "/skill:", "/tree");
    }

    @Test
    void ignoresPlainText() {
        assertThat(complete("帮我看看")).isEmpty();
    }

    private List<Candidate> complete(String word) {
        ParsedLine line = mock(ParsedLine.class);
        when(line.word()).thenReturn(word);
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, line, candidates);
        return candidates;
    }
}

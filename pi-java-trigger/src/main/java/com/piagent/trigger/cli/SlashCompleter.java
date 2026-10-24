package com.piagent.trigger.cli;

import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/**
 * 斜杠命令补全：只补全命令名，无需理解参数。
 */
final class SlashCompleter implements Completer {

    private static final List<String> COMMANDS = List.of(
            "/abort", "/compact", "/exit", "/help", "/model", "/quit",
            "/session", "/skill:", "/tree");

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word();
        if (word == null || !word.startsWith("/")) {
            return;
        }
        for (String command : COMMANDS) {
            if (command.startsWith(word)) {
                candidates.add(new Candidate(command));
            }
        }
    }
}

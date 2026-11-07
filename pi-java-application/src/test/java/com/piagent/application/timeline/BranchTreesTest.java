package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BranchTreesTest {

    private static final SessionId SESSION = SessionEntryFactory.sessionId(1);

    @Test
    void usesTheLatestEntryThatCarriesATree() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(entry(1, null, new GitCheckpointPayload("run-1", null, "tree-0",
                "head-0", "main")));
        branch.add(entry(2, branch.getLast().id(), change("tree-1")));
        branch.add(entry(3, branch.getLast().id(), new GitCheckpointPayload("run-2",
                branch.getLast().id(), "tree-2", "head-1", "main")));

        assertThat(BranchTrees.treeOf(branch)).isEqualTo("tree-2");
    }

    @Test
    void prefersPositionOverPayloadType() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(entry(1, null, change("tree-old")));
        branch.add(entry(2, branch.getLast().id(), new GitCheckpointPayload("run-1",
                null, "tree-new", "head", "main")));

        assertThat(BranchTrees.treeOf(branch)).isEqualTo("tree-new");
    }

    @Test
    void fallsBackToBranchSummaryToTreeSha() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(entry(1, null, new MessagePayload(SessionEntryFactory.user("开始"))));
        branch.add(entry(2, branch.getLast().id(), new BranchSummaryPayload(
                SessionEntryFactory.entryId(9), "切回另一条分支", Map.of(
                        "kind", "resume", "toTreeSha", "tree-brief"))));

        assertThat(BranchTrees.treeOf(branch)).isEqualTo("tree-brief");
    }

    @Test
    void ignoresBlankEvidenceAndReturnsNullWhenNothingExists() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(entry(1, null, new BranchSummaryPayload(SessionEntryFactory.entryId(9),
                "没有树", Map.of("kind", "rewind", "toTreeSha", " "))));
        branch.add(entry(2, branch.getLast().id(), new MessagePayload(
                SessionEntryFactory.user("无树"))));

        assertThat(BranchTrees.treeOf(branch)).isNull();
    }

    private SessionEntry entry(long seq, EntryId parent, EntryPayload payload) {
        return SessionEntryFactory.entry(SESSION, seq, parent, payload, 0);
    }

    private GitChangeSetPayload change(String treeSha) {
        return new GitChangeSetPayload("run-1", 1, null, "tree-0", treeSha,
                List.of(new FileChange("a.txt", GitChangeKind.MODIFIED, 1, 0, false)),
                "diff", false, "");
    }
}

package com.piagent.trigger.http.session;

import com.piagent.application.git.GitCheckpointService;
import com.piagent.application.git.GitUndoService;
import com.piagent.domain.session.model.SessionId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话工作区控制器：查看最近一次运行的改动、撤销上一轮。
 *
 * <p>与消息/设置控制器分开：改动查询是只读投影，撤销是显式的破坏性操作，
 * 单独放置便于前端只在这两个动作上做二次确认。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionWorkspaceController {

    private final GitCheckpointService checkpoints;
    private final GitUndoService undo;

    /**
     * 创建控制器。
     *
     * @param checkpoints 检查点用例
     * @param undo 撤销用例
     */
    public SessionWorkspaceController(GitCheckpointService checkpoints, GitUndoService undo) {
        this.checkpoints = checkpoints;
        this.undo = undo;
    }

    /**
     * 查询最近一次运行的改动。
     *
     * @param id 会话 ID
     * @return 改动汇总
     */
    @GetMapping("/{id}/changes")
    public SessionGitChangesResponse changes(@PathVariable String id) {
        return SessionGitChangesResponse.from(checkpoints.changes(SessionId.parse(id)));
    }

    /**
     * 撤销最近一次运行。
     *
     * @param id 会话 ID
     * @return 撤销结果；冲突时 {@code rewound=false}
     */
    @PostMapping("/{id}/undo")
    public GitUndoResponse undo(@PathVariable String id) {
        return GitUndoResponse.from(undo.undo(SessionId.parse(id)));
    }
}

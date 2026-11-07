package com.piagent.trigger.http.session;

import com.piagent.application.timeline.SessionBranchResumeService;
import com.piagent.domain.session.model.SessionId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分支切换控制器。
 *
 * <p>预览与执行共用同一接口，由 {@code dryRun} 区分；冲突与树不可用仍返回 200，
 * 只有分支关系非法、会话状态不允许或执行失败才返回 4xx/5xx。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionResumeController {

    private final SessionBranchResumeService resumes;

    /**
     * 创建控制器。
     *
     * @param resumes 切换用例
     */
    public SessionResumeController(SessionBranchResumeService resumes) {
        this.resumes = resumes;
    }

    /**
     * 预览或执行一次分支切换。
     *
     * @param id 会话 ID
     * @param request 切换请求
     * @return 预览或执行结果
     */
    @PostMapping("/{id}/resume")
    public SessionResumeResponse resume(@PathVariable String id,
            @RequestBody SessionResumeRequest request) {
        return SessionResumeResponse.from(resumes.resume(SessionId.parse(id),
                request.requireBranchEntryId(), request.preview(), request.note()));
    }
}

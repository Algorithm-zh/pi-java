package com.piagent.trigger.http.session;

import com.piagent.application.timeline.SessionBranchComparisonService;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分叉对比控制器。
 *
 * <p>只读会话 entry，不访问磁盘，也不改变任何状态；没有分支简报时返回
 * {@code available=false}，指定不在当前分支上的简报返回 409。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionComparisonController {

    private final SessionBranchComparisonService comparisons;

    /**
     * 创建控制器。
     *
     * @param comparisons 对比用例
     */
    public SessionComparisonController(SessionBranchComparisonService comparisons) {
        this.comparisons = comparisons;
    }

    /**
     * 对比当前分支与最近一次（或指定一次）分支切换前的分支。
     *
     * @param id 会话 ID
     * @param branch 分支简报 entry；省略时取最近一次
     * @param rewind 旧参数名，等价于 {@code branch}
     * @return 对比结果
     */
    @GetMapping("/{id}/comparison")
    public SessionComparisonResponse comparison(@PathVariable String id,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String rewind) {
        String raw = branch == null || branch.isBlank() ? rewind : branch;
        EntryId entryId = raw == null || raw.isBlank() ? null : EntryId.parse(raw);
        return SessionComparisonResponse.from(
                comparisons.compare(SessionId.parse(id), entryId));
    }
}
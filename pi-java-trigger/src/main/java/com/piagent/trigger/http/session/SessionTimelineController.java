package com.piagent.trigger.http.session;

import com.piagent.application.timeline.SessionRewindService;
import com.piagent.application.timeline.SessionTimelineService;
import com.piagent.domain.session.model.SessionId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 决策时间线与任意检查点回退控制器。
 *
 * <p>查询只读数据库，不访问磁盘；回退把预览与执行放在同一个接口里，由
 * {@code dryRun} 区分。服务端不信任预览结果，执行时重新计算冲突。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionTimelineController {

    private final SessionTimelineService timelines;
    private final SessionRewindService rewinds;

    /**
     * 创建控制器。
     *
     * @param timelines 时间线查询用例
     * @param rewinds 回退用例
     */
    public SessionTimelineController(SessionTimelineService timelines,
            SessionRewindService rewinds) {
        this.timelines = timelines;
        this.rewinds = rewinds;
    }

    /**
     * 查询当前分支的决策时间线。
     *
     * @param id 会话 ID
     * @return 时间线
     */
    @GetMapping("/{id}/timeline")
    public SessionTimelineResponse timeline(@PathVariable String id) {
        return SessionTimelineResponse.from(timelines.timeline(SessionId.parse(id)));
    }

    /**
     * 预览或执行一次回退。
     *
     * @param id 会话 ID
     * @param request 回退请求
     * @return 预览或执行结果；冲突时 HTTP 仍为 200
     */
    @PostMapping("/{id}/rewind")
    public SessionRewindResponse rewind(@PathVariable String id,
            @RequestBody SessionRewindRequest request) {
        return SessionRewindResponse.from(rewinds.rewind(SessionId.parse(id),
                request.requireCheckpointId(), request.preview(), request.note()));
    }
}

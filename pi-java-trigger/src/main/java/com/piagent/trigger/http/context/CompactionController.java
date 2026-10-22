package com.piagent.trigger.http.context;

import com.piagent.application.context.CompactionApplicationService;
import com.piagent.application.context.ManualCompactionView;
import com.piagent.domain.session.model.SessionId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上下文压缩控制器。
 *
 * <p>只提供手动压缩：自动压缩发生在 run 内部，通过 SSE {@code compaction.*} 事件可见。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class CompactionController {

    private final CompactionApplicationService compactions;

    /**
     * 创建控制器。
     *
     * @param compactions 压缩用例
     */
    public CompactionController(CompactionApplicationService compactions) {
        this.compactions = compactions;
    }

    /**
     * 手动压缩会话上下文。
     *
     * <p>成功时返回 200；内容不需要压缩时同样返回 200，只是 {@code applied=false}
     * 并附带原因——这不是错误，而是“当前无需压缩”的业务结论。</p>
     *
     * @param id 会话 ID
     * @return 压缩结果
     */
    @PostMapping("/{id}/compact")
    public CompactionResponse compact(@PathVariable String id) {
        ManualCompactionView view = compactions.compact(SessionId.parse(id));
        return CompactionResponse.from(view);
    }
}

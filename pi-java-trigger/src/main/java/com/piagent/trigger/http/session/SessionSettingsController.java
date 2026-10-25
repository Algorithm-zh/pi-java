package com.piagent.trigger.http.session;

import com.piagent.application.session.command.ChangeSessionModelCommand;
import com.piagent.application.session.command.RenameSessionCommand;
import com.piagent.application.session.service.SessionSettingsApplicationService;
import com.piagent.domain.session.model.SessionId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话设置 REST 控制器：重命名、切换模型与归档。
 *
 * <p>与消息/ SSE 控制器分开，便于在设置界面出现故障时快速定位；所有响应
 * 复用 {@link SessionResponse}，前端更新后可直接替换本地会话对象。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionSettingsController {

    private final SessionSettingsApplicationService settings;

    /**
     * 创建控制器。
     *
     * @param settings 会话设置用例
     */
    public SessionSettingsController(SessionSettingsApplicationService settings) {
        this.settings = settings;
    }

    /**
     * 重命名会话。
     *
     * @param id 会话 ID
     * @param request 重命名请求
     * @return 更新后的会话
     */
    @PatchMapping("/{id}")
    public SessionResponse rename(@PathVariable String id,
            @RequestBody RenameSessionRequest request) {
        return SessionResponse.from(settings.rename(new RenameSessionCommand(
                SessionId.parse(id), request.title())));
    }

    /**
     * 切换会话模型。
     *
     * @param id 会话 ID
     * @param request 切换请求
     * @return 更新后的会话
     */
    @PutMapping("/{id}/model")
    public SessionResponse changeModel(@PathVariable String id,
            @RequestBody ChangeSessionModelRequest request) {
        return SessionResponse.from(settings.changeModel(new ChangeSessionModelCommand(
                SessionId.parse(id), request.modelProfileId(), request.thinkingLevelOrNull())));
    }

    /**
     * 归档会话。
     *
     * @param id 会话 ID
     * @return 更新后的会话
     */
    @PostMapping("/{id}/archive")
    public SessionResponse archive(@PathVariable String id) {
        return SessionResponse.from(settings.archive(SessionId.parse(id)));
    }

    /**
     * 删除会话及其历史。
     *
     * @param id 会话 ID
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        settings.delete(SessionId.parse(id));
    }
}

package com.piagent.trigger.http.session;

import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.trigger.http.run.RunHandleResponse;
import com.piagent.trigger.http.run.RunStreamService;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 会话 REST 与 SSE 控制器。
 *
 * <p>创建、查询、发送消息、取消分别映射到应用层用例；SSE 由
 * {@link RunStreamService} 负责，控制器只决定订阅起点。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionApplicationService sessions;
    private final AgentLoopService agentLoop;
    private final RunStreamService streams;

    /**
     * 创建控制器。
     *
     * @param sessions 会话用例
     * @param agentLoop Agent Loop
     * @param streams SSE 流服务
     */
    public SessionController(SessionApplicationService sessions, AgentLoopService agentLoop,
            RunStreamService streams) {
        this.sessions = sessions;
        this.agentLoop = agentLoop;
        this.streams = streams;
    }

    /**
     * 创建会话。
     *
     * @param request 创建请求
     * @return 新会话
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse create(@RequestBody CreateSessionRequest request) {
        ChatSession session = sessions.createSession(new CreateSessionCommand(request.title(),
                Path.of(request.cwd()), request.modelProfileId(), request.thinkingLevel()));
        return SessionResponse.from(session);
    }

    /**
     * 查询会话列表。
     *
     * @param cwd 工作目录过滤，可选
     * @param limit 最大条数
     * @return 会话列表
     */
    @GetMapping
    public List<SessionResponse> list(@RequestParam(name = "cwd", required = false) String cwd,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        Path path = cwd == null || cwd.isBlank() ? null : Path.of(cwd);
        return sessions.list(path, limit).stream().map(SessionResponse::from).toList();
    }

    /**
     * 查询会话详情。
     *
     * @param id 会话 ID
     * @return 会话
     */
    @GetMapping("/{id}")
    public SessionResponse find(@PathVariable String id) {
        return SessionResponse.from(sessions.find(SessionId.parse(id)));
    }

    /**
     * 查询会话分支 entry。
     *
     * @param id 会话 ID
     * @param leafId 叶子，可选
     * @return entry 列表
     */    @GetMapping("/{id}/entries")
    public List<SessionEntryResponse> entries(@PathVariable String id,
            @RequestParam(name = "leafId", required = false) String leafId) {
        SessionId sessionId = SessionId.parse(id);
        EntryId leaf = leafId == null || leafId.isBlank() ? null : EntryId.parse(leafId);
        return sessions.listBranch(sessionId, leaf).stream()
                .map(SessionEntryResponse::from)
                .toList();
    }

    /**
     * 查询会话当前活跃 run，供页面刷新后重连 SSE。
     *
     * @param id 会话 ID
     * @return 活跃 run；没有时 204
     */
    @GetMapping("/{id}/run")
    public ResponseEntity<RunHandleResponse> activeRun(@PathVariable String id) {
        return agentLoop.findActive(SessionId.parse(id))
                .map(RunHandleResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 发送消息并订阅本次 run 的 SSE。
     *
     * @param id 会话 ID
     * @param request 消息请求
     * @return SSE 流
     */
    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@PathVariable String id,
            @RequestBody SendMessageRequest request) {
        RunHandle handle = agentLoop.start(new SendMessageCommand(
                SessionId.parse(id), request.text(), request.attachmentIds()));
        return streams.open(handle.runId(), 0);
    }

    /**
     * 取消会话当前 run。
     *
     * @param id 会话 ID
     * @return 被取消的 run
     */
    @PostMapping("/{id}/abort")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunHandleResponse abort(@PathVariable String id) {
        return RunHandleResponse.from(agentLoop.abort(SessionId.parse(id)));
    }
}

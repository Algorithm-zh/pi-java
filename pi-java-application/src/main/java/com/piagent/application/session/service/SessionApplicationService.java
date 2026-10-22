package com.piagent.application.session.service;

import com.piagent.application.session.command.AppendUserMessageCommand;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.command.ForkSessionCommand;
import com.piagent.application.session.command.MoveBranchCommand;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.Origin;
import com.piagent.domain.session.model.ProjectedContext;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.session.service.SessionBranchPolicy;
import com.piagent.domain.session.service.SessionContextProjector;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.time.Clock;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话用例服务：创建、追加用户消息、分支移动、fork 与上下文投影。
 *
 * <p>事务边界在用例层：LLM 与工具执行不在这里发生，因此每个方法都是短事务。
 * 同一会话的并发写由进程内单写者锁（M4 接入）与仓储乐观锁共同保护。
 */
@Service
public class SessionApplicationService {

    private final SessionRepository repository;
    private final SessionEntryStore entryStore;
    private final SessionContextProjector projector;
    private final SessionBranchPolicy branchPolicy;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 创建用例服务。
     *
     * @param repository 会话仓储
     * @param entryStore entry 存储
     * @param projector 上下文投影器
     * @param branchPolicy 分支策略
     * @param idGenerator 标识生成器
     * @param clock 时钟
     */
    public SessionApplicationService(SessionRepository repository, SessionEntryStore entryStore,
                                     SessionContextProjector projector, SessionBranchPolicy branchPolicy,
                                     IdGenerator idGenerator, Clock clock) {
        this.repository = repository;
        this.entryStore = entryStore;
        this.projector = projector;
        this.branchPolicy = branchPolicy;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 创建用户会话。
     *
     * @param command 创建命令
     * @return 新会话
     */
    @Transactional
    public ChatSession createSession(CreateSessionCommand command) {
        SessionConfig config = new SessionConfig(command.cwd(), command.modelProfileId(),
                command.thinkingLevel(), SandboxSnapshot.forWorkspace(command.cwd()));
        ChatSession session = ChatSession.createRoot(nextSessionId(), command.title(), config);
        repository.insert(session);
        return session;
    }

    /**
     * 追加一条用户消息并移动叶子。
     *
     * @param command 追加命令
     * @return 新 entry
     */
    @Transactional
    public SessionEntry appendUserMessage(AppendUserMessageCommand command) {
        ChatSession session = require(command.sessionId());
        Guard.require(session.status() == SessionStatus.IDLE, DomainErrorCode.INVALID_STATE_TRANSITION,
                "只有空闲会话可以追加用户消息，当前状态: " + session.status());
        long seq = entryStore.countBySession(command.sessionId()) + 1;
        Instant now = clock.instant();
        SessionEntry entry = new SessionEntry(nextEntryId(), command.sessionId(), seq, session.leafEntryId(),
                EntryType.MESSAGE, SessionEntry.CURRENT_PAYLOAD_VERSION,
                new MessagePayload(new UserMessage(List.of(new TextBlock(command.text())), now)),
                estimateTokens(command.text()), now);
        entryStore.append(entry);
        repository.update(session.moveLeaf(entry.id()));
        return entry;
    }

    /**
     * 移动当前分支叶子。
     *
     * @param command 移动命令
     * @return 更新后的会话
     */
    @Transactional
    public ChatSession moveBranch(MoveBranchCommand command) {
        ChatSession session = require(command.sessionId());
        SessionEntry target = entryStore.findById(command.sessionId(), command.targetEntryId())
                .orElseThrow(() -> new DomainException(DomainErrorCode.ENTRY_NOT_FOUND,
                        "目标 entry 不存在: " + command.targetEntryId()));
        branchPolicy.requireBranchTarget(command.sessionId(), List.of(target), command.targetEntryId());
        ChatSession moved = session.moveLeaf(target.id());
        repository.update(moved);
        return moved.withVersion(session.version() + 1);
    }

    /**
     * 从源会话 fork 一个子会话。
     *
     * <p>M1 的初始 BRANCH_SUMMARY 只记录 fork 起点与消息数量；真正的 LLM 摘要
     * 在 M7 接入压缩能力后生成。
     *
     * @param command fork 命令
     * @return 新子会话
     */
    @Transactional
    public ChatSession fork(ForkSessionCommand command) {
        ChatSession source = require(command.sourceSessionId());
        Guard.require(source.status() == SessionStatus.IDLE, DomainErrorCode.INVALID_STATE_TRANSITION,
                "只有空闲会话可以 fork，当前状态: " + source.status());
        SessionEntry from = entryStore.findById(command.sourceSessionId(), command.fromEntryId())
                .orElseThrow(() -> new DomainException(DomainErrorCode.ENTRY_NOT_FOUND,
                        "fork 起点不存在: " + command.fromEntryId()));
        branchPolicy.requireBranchTarget(command.sourceSessionId(), List.of(from), command.fromEntryId());
        String title = command.title() == null || command.title().isBlank()
                ? "分支 " + command.sourceSessionId()
                : command.title();
        ChatSession child = ChatSession.createChild(nextSessionId(), source, from.id(), Origin.FORK,
                title, source.config());
        repository.insert(child);
        int messageCount = projector.project(
                entryStore.loadBranch(command.sourceSessionId(), from.id()), from.id()).messages().size();
        SessionEntry summary = forkSummary(child.identity().id(), command.sourceSessionId(), from.id(), messageCount);
        entryStore.append(summary);
        ChatSession updated = child.moveLeaf(summary.id());
        repository.update(updated);
        return updated.withVersion(child.version() + 1);
    }

    /**
     * 加载会话。
     *
     * @param sessionId 会话标识
     * @return 会话
     */
    @Transactional(readOnly = true)
    public ChatSession find(SessionId sessionId) {
        return require(sessionId);
    }

    /**
     * 列出最近会话。
     *
     * @param cwd 工作目录；为 null 时不限制目录
     * @param limit 最大条数
     * @return 会话列表
     */
    @Transactional(readOnly = true)
    public List<ChatSession> list(Path cwd, int limit) {
        Guard.require(limit >= 1 && limit <= 200, DomainErrorCode.INVALID_ARGUMENT,
                "limit 必须在 1 到 200 之间");
        if (cwd == null) {
            return repository.findRecent(limit);
        }
        return repository.findByCwd(cwd.toAbsolutePath().normalize(), limit);
    }

    /**
     * 查询指定分支的 entry，用于 Web UI 时间线。
     *
     * @param sessionId 会话标识
     * @param leafId 叶子；为 null 时使用当前叶子
     * @return 按 seq 升序排列的分支
     */
    @Transactional(readOnly = true)
    public List<SessionEntry> listBranch(SessionId sessionId, EntryId leafId) {
        ChatSession session = require(sessionId);
        EntryId effective = leafId == null ? session.leafEntryId() : leafId;
        if (effective == null) {
            return List.of();
        }
        return entryStore.loadBranch(sessionId, effective);
    }

    /**
     * 投影指定叶子的上下文；leaf 为 null 时使用会话当前叶子。
     *
     * @param sessionId 会话标识
     * @param leafId 目标叶子
     * @return 上下文
     */
    @Transactional(readOnly = true)
    public ProjectedContext projectContext(SessionId sessionId, EntryId leafId) {
        ChatSession session = require(sessionId);
        EntryId effectiveLeaf = leafId == null ? session.leafEntryId() : leafId;
        if (effectiveLeaf == null) {
            return ProjectedContext.empty();
        }
        List<SessionEntry> branch = entryStore.loadBranch(sessionId, effectiveLeaf);
        return projector.project(branch, effectiveLeaf);
    }

    private ChatSession require(SessionId sessionId) {
        return repository.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
    }

    private SessionEntry forkSummary(SessionId childId, SessionId sourceId, EntryId fromEntryId,
                                     int messageCount) {
        String summary = "从会话 " + sourceId + " 的 entry " + fromEntryId + " fork，父分支共 "
                + messageCount + " 条消息。";
        return new SessionEntry(nextEntryId(), childId, 1, null, EntryType.BRANCH_SUMMARY,
                SessionEntry.CURRENT_PAYLOAD_VERSION,
                new BranchSummaryPayload(fromEntryId, summary,
                        Map.of("sourceSessionId", sourceId.toString(), "messageCount", messageCount)),
                estimateTokens(summary), clock.instant());
    }

    private SessionId nextSessionId() {
        return new SessionId(Ulid.parse(idGenerator.newId()));
    }

    private EntryId nextEntryId() {
        return new EntryId(Ulid.parse(idGenerator.newId()));
    }

    private int estimateTokens(String text) {
        // M1 仅做字符启发式估算；M7 接入 usage 锚点后替换。
        return Math.max(1, (text.length() + 2) / 3);
    }
}

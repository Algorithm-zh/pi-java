package com.piagent.application.context;

import com.piagent.domain.context.CompactionPromptBuilder;
import com.piagent.domain.context.SummaryDraft;
import com.piagent.domain.context.SummarySection;
import com.piagent.domain.context.TokenEstimator;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.llm.LlmCallContext;
import com.piagent.domain.model.llm.LlmDelta;
import com.piagent.domain.model.llm.LlmGateway;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.model.llm.LlmStream;
import com.piagent.domain.model.llm.TextDelta;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 调用模型生成结构化压缩摘要。
 *
 * <p>为什么单独一个组件：它承担“把领域请求翻译成一次普通文本 LLM 调用，再把模型
 * 输出解析回结构”的适配职责，既不属于领域（需要 LLM 网关），也不应在 Agent Loop
 * 里展开。压缩服务只关心“拿到 SummaryDraft 或失败”。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>不传工具、不要求函数调用，任何文本模型都能完成压缩；</li>
 *   <li>温度等解码参数由 provider 默认值决定，不在这里做模型特化；</li>
 *   <li>模型输出先按二级标题切分，再映射到 {@link SummarySection}；识别不出小节时，
 *       把全文作为“目标”兜底，保证不会因为格式不合规而丢掉全部摘要；</li>
 *   <li>把“读取文件 / 修改文件”从小节正文里解析出来，单独存进 details。</li>
 * </ul>
 */
@Component
public class CompactionSummarizer {

    private static final String READ_PREFIX = "读取文件：";
    private static final String MODIFIED_PREFIX = "修改文件：";
    private static final int MAX_SUMMARY_CHARS = 32_000;
    private static final int MAX_TITLE_LENGTH = 16;

    private final LlmGateway gateway;
    private final CompactionPromptBuilder promptBuilder;
    private final TokenEstimator estimator;
    private final Clock clock;

    /**
     * 创建摘要器。
     *
     * @param gateway LLM 网关
     * @param promptBuilder 请求组装器
     * @param estimator token 估算器
     * @param clock 时钟
     */
    public CompactionSummarizer(LlmGateway gateway, CompactionPromptBuilder promptBuilder,
            TokenEstimator estimator, Clock clock) {
        this.gateway = gateway;
        this.promptBuilder = promptBuilder;
        this.estimator = estimator;
        this.clock = clock;
    }

    /**
     * 生成摘要。
     *
     * @param profile 模型档案
     * @param entries 待摘要 entry
     * @param splitTurnPrefix split-turn 前缀
     * @param previousSummary 上一次摘要
     * @return 摘要草稿
     * @throws DomainException 模型返回空内容或调用失败时
     */
    public SummaryDraft summarize(ModelProfile profile, List<SessionEntry> entries,
            List<SessionEntry> splitTurnPrefix, String previousSummary) {
        LlmRequest request = new LlmRequest(profile, promptBuilder.systemPrompt(),
                List.of(new UserMessage(List.of(new TextBlock(promptBuilder.userMessage(
                        entries, splitTurnPrefix, previousSummary))), clock.instant())),
                List.of(), ThinkingLevel.OFF);
        String text = collect(profile, request);
        SummaryDraft draft = parse(text);
        if (draft.isEmpty()) {
            throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "压缩摘要为空，模型没有返回可用的交接内容");
        }
        return draft;
    }

    /**
     * 估算摘要 token。
     *
     * @param summary 摘要文本
     * @return token
     */
    public int estimateTokens(String summary) {
        return estimator.estimate(summary).tokens();
    }

    private String collect(ModelProfile profile, LlmRequest request) {
        LlmStream stream = gateway.stream(request, LlmCallContext.defaults());
        StringBuilder text = new StringBuilder();
        try {
            while (stream.hasNext()) {
                LlmDelta delta = stream.next();
                if (delta instanceof TextDelta value) {
                    text.append(value.text());
                }
            }
            LlmResponse response = stream.response().orElse(null);
            if (response != null && text.isEmpty()) {
                for (ContentBlock block : response.content()) {
                    if (block instanceof TextBlock value) {
                        text.append(value.text());
                    }
                }
            }
        } finally {
            stream.close();
        }
        String summary = text.toString().strip();
        if (summary.isEmpty()) {
            throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "模型 " + profile.id().value() + " 没有返回摘要内容");
        }
        return summary.length() <= MAX_SUMMARY_CHARS
                ? summary : summary.substring(0, MAX_SUMMARY_CHARS);
    }

    /**
     * 把模型输出解析为结构化草稿。
     *
     * @param text 模型输出
     * @return 草稿；完全无法识别时各小节为空
     */
    SummaryDraft parse(String text) {
        Map<SummarySection, String> sections = new EnumMap<>(SummarySection.class);
        List<String> readFiles = new ArrayList<>();
        List<String> modifiedFiles = new ArrayList<>();
        SummarySection current = null;
        StringBuilder buffer = new StringBuilder();
        for (String line : text.lines().toList()) {
            Optional<SummarySection> heading = headingOf(line);
            if (heading.isPresent()) {
                flush(sections, current, buffer);
                current = heading.get();
                buffer.setLength(0);
                String inline = inlineContentOf(line);
                if (!inline.isEmpty()) {
                    sections.merge(current, inline, (left, right) -> left + "\n" + right);
                }
                continue;
            }
            buffer.append(line).append('\n');
        }
        flush(sections, current, buffer);
        if (sections.isEmpty()) {
            // 兜底：模型完全没按标题输出时，把全文当作“目标 + 关键上下文”。
            sections.put(SummarySection.GOAL, text.strip());
        }
        extractFiles(sections, readFiles, modifiedFiles);
        return new SummaryDraft(sections, readFiles, modifiedFiles);
    }

    /**
     * 判断一行是否为小节标题。
     *
     * <p>只有“标题部分足够短”时才认作标题，避免把正文里的 “第一步: 打包” 误判成新小节。
     * 支持三种常见写法：{@code ## 目标}、{@code **目标**}、{@code 目标：}、
     * 以及标题与内容同行的 {@code **目标**: 内容}。</p>
     */
    private Optional<SummarySection> headingOf(String line) {
        String stripped = line.strip();
        if (stripped.isEmpty()) {
            return Optional.empty();
        }
        SummarySection whole = CompactionPromptBuilder.parseSection(stripped);
        if (whole != null) {
            return Optional.of(whole);
        }
        String titlePart = titlePartOf(stripped);
        if (titlePart.isBlank() || titlePart.length() > MAX_TITLE_LENGTH) {
            return Optional.empty();
        }
        return Optional.ofNullable(CompactionPromptBuilder.parseSection(titlePart));
    }

    /**
     * 截取行首的标题部分：遇到冒号或加粗结束标记即停止。
     */
    private String titlePartOf(String line) {
        int cut = line.length();
        for (String marker : new String[] {"：", ":", "**"}) {
            int index = line.indexOf(marker);
            if (index > 0 && index < cut) {
                cut = index;
            }
        }
        return line.substring(0, cut).strip();
    }

    /**
     * 标题与内容同行时，取出冒号之后的内容；没有同行内容时返回空串。
     */
    private String inlineContentOf(String line) {
        for (String marker : new String[] {"：", ":"}) {
            int index = line.indexOf(marker);
            if (index > 0) {
                return line.substring(index + 1).replace("**", "").strip();
            }
        }
        return "";
    }

    private void flush(Map<SummarySection, String> sections, SummarySection section,
            StringBuilder buffer) {
        if (section == null) {
            return;
        }
        String content = buffer.toString().strip();
        if (!content.isEmpty()) {
            String existing = sections.get(section);
            sections.put(section, existing == null ? content : existing + "\n" + content);
        }
    }

    private void extractFiles(Map<SummarySection, String> sections, List<String> readFiles,
            List<String> modifiedFiles) {
        String critical = sections.get(SummarySection.CRITICAL_CONTEXT);
        if (critical == null) {
            return;
        }
        StringBuilder rest = new StringBuilder();
        for (String line : critical.lines().toList()) {
            String stripped = line.strip();
            if (stripped.startsWith(READ_PREFIX)) {
                addAll(readFiles, stripped.substring(READ_PREFIX.length()));
            } else if (stripped.startsWith(MODIFIED_PREFIX)) {
                addAll(modifiedFiles, stripped.substring(MODIFIED_PREFIX.length()));
            } else {
                rest.append(line).append('\n');
            }
        }
        String remaining = rest.toString().strip();
        if (remaining.isEmpty()) {
            sections.remove(SummarySection.CRITICAL_CONTEXT);
        } else {
            sections.put(SummarySection.CRITICAL_CONTEXT, remaining);
        }
    }

    private void addAll(List<String> target, String raw) {
        for (String item : raw.split("[,，、;；\\s]+")) {
            String value = item.strip();
            if (!value.isEmpty() && !target.contains(value) && target.size() < 100) {
                target.add(value);
            }
        }
    }
}

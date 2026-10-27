package com.piagent.domain.session.model.message;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 用户消息。
 *
 * @param content 内容块，至少一块
 * @param timestamp 创建时间
 */
public record UserMessage(List<ContentBlock> content, Instant timestamp) implements AgentMessage {

    /**
     * 校验并冻结内容。
     *
     * @param content 内容块
     * @param timestamp 时间
     */
    public UserMessage {
        Objects.requireNonNull(content, "content 不能为空");
        Guard.require(!content.isEmpty(), DomainErrorCode.INVALID_ARGUMENT, "用户消息至少需要一个内容块");
        Objects.requireNonNull(timestamp, "timestamp 不能为空");
        content = List.copyOf(content);
    }

    /**
     * 由用户文本与图片块组装消息，空白文本不产生文本块。
     *
     * @param text 用户文本
     * @param images 图片块
     * @param timestamp 创建时间
     * @return 用户消息
     */
    public static UserMessage create(String text, List<ImageBlock> images, Instant timestamp) {
        Objects.requireNonNull(images, "images 不能为空");
        List<ContentBlock> content = new ArrayList<>(images.size() + 1);
        if (!text.isBlank()) {
            content.add(new TextBlock(text));
        }
        content.addAll(images);
        return new UserMessage(content, timestamp);
    }
}

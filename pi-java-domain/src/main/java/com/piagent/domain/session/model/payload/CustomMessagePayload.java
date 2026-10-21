package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.EntryType;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 插件注入、参与上下文的消息载荷。
 *
 * @param customType 扩展类型标识
 * @param content 内容块
 * @param display 是否在 UI/CLI 显示
 * @param details 扩展细节，只允许 JSON 可序列化值
 */
public record CustomMessagePayload(
        String customType,
        List<ContentBlock> content,
        boolean display,
        Map<String, Object> details) implements EntryPayload {

    /**
     * 校验自定义消息。
     *
     * @param customType 扩展类型
     * @param content 内容块
     * @param display 是否显示
     * @param details 细节
     */
    public CustomMessagePayload {
        Guard.notBlank(customType, DomainErrorCode.INVALID_ARGUMENT, "customType 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(details, "details 不能为空");
        content = List.copyOf(content);
        details = Map.copyOf(details);
    }

    @Override
    public EntryType entryType() {
        return EntryType.CUSTOM_MESSAGE;
    }
}

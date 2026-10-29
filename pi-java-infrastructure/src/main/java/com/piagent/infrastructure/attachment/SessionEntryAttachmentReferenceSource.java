package com.piagent.infrastructure.attachment;

import com.piagent.application.attachment.port.AttachmentReferenceSource;
import com.piagent.application.identity.AuthContext;
import com.piagent.infrastructure.persistence.session.SessionEntryMapper;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Repository;

/**
 * 从会话 entry 的 payload 中收集附件引用。
 *
 * <p>只做文本级扫描：图片块序列化后固定包含 {@code "attachmentId"} 字段，
 * 扫描全部会话即可覆盖 fork、分支与已删除会话留下的历史记录。</p>
 */
@Repository
public class SessionEntryAttachmentReferenceSource implements AttachmentReferenceSource {

    private static final Pattern ATTACHMENT_ID =
            Pattern.compile("\"attachmentId\"\\s*:\\s*\"([0-9A-Za-z_-]{1,64})\"");

    private final SessionEntryMapper mapper;

    /**
     * 创建引用来源。
     *
     * @param mapper 会话 entry 映射器
     */
    public SessionEntryAttachmentReferenceSource(SessionEntryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Set<String> referencedIds() {
        Set<String> ids = new HashSet<>();
        for (String payload : mapper.findAllPayloadsByTenant(AuthContext.tenantId().toString())) {
            if (payload == null) {
                continue;
            }
            Matcher matcher = ATTACHMENT_ID.matcher(payload);
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        }
        return Set.copyOf(ids);
    }
}

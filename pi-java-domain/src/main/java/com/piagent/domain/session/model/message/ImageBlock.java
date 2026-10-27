package com.piagent.domain.session.model.message;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Locale;
import java.util.Objects;

/**
 * 图片内容块。
 *
 * <p>只引用附件 ID，不内嵌二进制；附件由独立的附件存储负责保存与读取。
 * 运行期按档案的 {@code vision} 能力决定是否允许发送。</p>
 *
 * @param attachmentId 附件标识
 * @param mediaType 图片媒体类型，如 image/png
 * @param name 展示用文件名
 */
public record ImageBlock(String attachmentId, String mediaType, String name) implements ContentBlock {

    private static final int MAX_NAME_LENGTH = 120;

    /**
     * 校验图片块。
     *
     * @param attachmentId 附件标识
     * @param mediaType 媒体类型
     * @param name 文件名
     */
    public ImageBlock {
        attachmentId = Guard.notBlank(attachmentId, DomainErrorCode.INVALID_ARGUMENT,
                "附件 ID 不能为空");
        mediaType = Guard.notBlank(mediaType, DomainErrorCode.INVALID_ARGUMENT, "图片媒体类型不能为空")
                .toLowerCase(Locale.ROOT);
        Guard.require(mediaType.startsWith("image/"), DomainErrorCode.INVALID_ARGUMENT,
                "不支持的媒体类型: " + mediaType);
        name = name == null || name.isBlank() ? "image" : name.strip();
        Guard.maxLength(name, MAX_NAME_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "图片文件名不能超过 " + MAX_NAME_LENGTH + " 个字符");
        Objects.requireNonNull(name, "name 不能为空");
    }
}

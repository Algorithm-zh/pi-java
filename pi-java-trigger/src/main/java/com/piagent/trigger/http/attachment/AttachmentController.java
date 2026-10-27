package com.piagent.trigger.http.attachment;

import com.piagent.application.attachment.AttachmentContent;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.application.attachment.service.AttachmentApplicationService;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片附件 REST 控制器。
 *
 * <p>上传只接受 PNG/JPEG/WebP/GIF，格式按文件头识别，不信任客户端声明的
 * Content-Type；下载按元数据返回原始字节，供聊天历史里的图片直接引用。</p>
 */
@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

    private final AttachmentApplicationService attachments;

    /**
     * 创建控制器。
     *
     * @param attachments 附件应用服务
     */
    public AttachmentController(AttachmentApplicationService attachments) {
        this.attachments = attachments;
    }

    /**
     * 上传图片。
     *
     * @param file multipart 文件
     * @return 附件元数据
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@RequestParam("file") MultipartFile file) {
        if (file == null) {
            throw new DomainException(DomainErrorCode.ATTACHMENT_INVALID, "缺少上传文件");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.ATTACHMENT_INVALID, "读取上传文件失败", error);
        }
        AttachmentMetadata metadata = attachments.upload(file.getOriginalFilename(), content);
        return AttachmentResponse.from(metadata);
    }

    /**
     * 读取附件内容。
     *
     * @param id 附件标识
     * @return 原始字节
     */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> load(@PathVariable String id) {
        AttachmentContent content = attachments.load(id);
        AttachmentMetadata metadata = content.metadata();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.mediaType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(metadata.name(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(content.content());
    }
}

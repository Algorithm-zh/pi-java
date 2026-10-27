package com.piagent.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.attachment.port.AttachmentStore;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.infrastructure.attachment.FileSystemAttachmentStore;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 附件存储装配。
 *
 * <p>默认与 H2 数据目录相邻（{@code PI_DATA_DIR/attachments}），容器内随
 * {@code /app/data} 一起挂载即可保留图片。</p>
 */
@Configuration
public class AttachmentConfiguration {

    /**
     * 文件系统附件存储。
     *
     * @param directory 附件目录
     * @param idGenerator 标识生成器
     * @param mapper Jackson 映射器
     * @return 附件存储
     */
    @Bean
    public AttachmentStore attachmentStore(@Value("${pi.attachments.dir}") String directory,
            IdGenerator idGenerator, ObjectMapper mapper) {
        return new FileSystemAttachmentStore(Path.of(directory), idGenerator, mapper);
    }
}

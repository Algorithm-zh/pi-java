package com.piagent.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.infrastructure.persistence.codec.EntryPayloadCodec;
import com.piagent.infrastructure.persistence.codec.SandboxJsonCodec;
import com.piagent.infrastructure.persistence.session.SessionEntryPoConverter;
import com.piagent.infrastructure.persistence.session.SessionPoConverter;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 持久化装配：MyBatis 映射器扫描与行转换器。
 *
 * <p>JSON 编解码复用 Spring Boot 提供的 ObjectMapper，但只使用树模型，
 * 领域类型不引入 Jackson 注解。
 */
@Configuration
@MapperScan(basePackages = "com.piagent.infrastructure.persistence", annotationClass = Mapper.class)
public class PersistenceConfiguration {

    /**
     * entry 载荷编解码。
     *
     * @param mapper Jackson 映射器
     * @return 编解码器
     */
    @Bean
    public EntryPayloadCodec entryPayloadCodec(ObjectMapper mapper) {
        return new EntryPayloadCodec(mapper);
    }

    /**
     * 沙箱快照编解码。
     *
     * @param mapper Jackson 映射器
     * @return 编解码器
     */
    @Bean
    public SandboxJsonCodec sandboxJsonCodec(ObjectMapper mapper) {
        return new SandboxJsonCodec(mapper);
    }

    /**
     * 会话行转换器。
     *
     * @param sandboxCodec 沙箱编解码
     * @return 转换器
     */
    @Bean
    public SessionPoConverter sessionPoConverter(SandboxJsonCodec sandboxCodec) {
        return new SessionPoConverter(sandboxCodec);
    }

    /**
     * entry 行转换器。
     *
     * @param payloadCodec 载荷编解码
     * @return 转换器
     */
    @Bean
    public SessionEntryPoConverter sessionEntryPoConverter(EntryPayloadCodec payloadCodec) {
        return new SessionEntryPoConverter(payloadCodec);
    }
}

package com.piagent.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.infrastructure.persistence.codec.ApprovalArgumentsCodec;
import com.piagent.infrastructure.persistence.codec.EntryPayloadCodec;
import com.piagent.infrastructure.persistence.codec.ModelCapabilitiesCodec;
import com.piagent.infrastructure.persistence.codec.SandboxJsonCodec;
import com.piagent.infrastructure.persistence.codec.ToolPolicyConfigCodec;
import com.piagent.infrastructure.persistence.extension.PluginPoConverter;
import com.piagent.infrastructure.persistence.governance.ApprovalPoConverter;
import com.piagent.infrastructure.persistence.model.ModelProfilePoConverter;
import com.piagent.infrastructure.persistence.model.ToolPolicyPoConverter;
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
     * 模型能力编解码。
     *
     * @param mapper Jackson 映射器
     * @return 编解码器
     */
    @Bean
    public ModelCapabilitiesCodec modelCapabilitiesCodec(ObjectMapper mapper) {
        return new ModelCapabilitiesCodec(mapper);
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
     * 模型档案行转换器。
     *
     * @param capabilitiesCodec 能力编解码
     * @return 转换器
     */
    @Bean
    public ModelProfilePoConverter modelProfilePoConverter(ModelCapabilitiesCodec capabilitiesCodec) {
        return new ModelProfilePoConverter(capabilitiesCodec);
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

    /**
     * 工具策略配置编解码。
     *
     * @param mapper Jackson 映射器
     * @return 编解码器
     */
    @Bean
    public ToolPolicyConfigCodec toolPolicyConfigCodec(ObjectMapper mapper) {
        return new ToolPolicyConfigCodec(mapper);
    }

    /**
     * 工具策略行转换器。
     *
     * @param configCodec 配置编解码
     * @return 转换器
     */
    @Bean
    public ToolPolicyPoConverter toolPolicyPoConverter(ToolPolicyConfigCodec configCodec) {
        return new ToolPolicyPoConverter(configCodec);
    }

    /**
     * 插件安装记录行转换器。
     *
     * @return 转换器
     */
    @Bean
    public PluginPoConverter pluginPoConverter() {
        return new PluginPoConverter();
    }

    /**
     * 审批参数编解码。
     *
     * @param mapper Jackson 映射器
     * @return 编解码器
     */
    @Bean
    public ApprovalArgumentsCodec approvalArgumentsCodec(ObjectMapper mapper) {
        return new ApprovalArgumentsCodec(mapper);
    }

    /**
     * 审批记录行转换器。
     *
     * @param argumentsCodec 参数编解码
     * @return 转换器
     */
    @Bean
    public ApprovalPoConverter approvalPoConverter(ApprovalArgumentsCodec argumentsCodec) {
        return new ApprovalPoConverter(argumentsCodec);
    }
}

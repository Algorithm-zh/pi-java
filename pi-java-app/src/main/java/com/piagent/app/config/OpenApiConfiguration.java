package com.piagent.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档元信息。
 *
 * <p>接口清单由 springdoc 在运行时扫描 Controller 生成，这里只补充标题、版本与
 * 许可证，避免文档元信息散落在各个模块。文档只描述本地单机 API，不包含鉴权。</p>
 */
@Configuration
public class OpenApiConfiguration {

    /**
     * 构建 OpenAPI 元信息。
     *
     * @param version 应用版本
     * @return OpenAPI 文档
     */
    @Bean
    public OpenAPI piJavaOpenApi(@Value("${pi.version:0.2.0}") String version) {
        return new OpenAPI().info(new Info()
                .title("pi-java")
                .version(version)
                .summary("本地单机 coding agent 的 HTTP 与 SSE 接口")
                .description("pi-java 只监听回环地址，v0.2 不提供鉴权；"
                        + "所有模型密钥只通过环境变量读取。")
                .license(new License().name("MIT License")));
    }
}

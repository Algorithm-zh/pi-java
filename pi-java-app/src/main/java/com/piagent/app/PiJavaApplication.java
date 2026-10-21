package com.piagent.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * pi-java 的唯一启动入口与装配点。
 *
 * <p>本模块只负责“把零件接起来”：组件扫描覆盖 {@code com.piagent} 下的
 * trigger、infrastructure 与 application Bean，自身不承载业务逻辑。
 * Web 与 CLI 共用同一个 Spring 容器，CLI 通过命令模式复用应用层用例。
 */
@SpringBootApplication(scanBasePackages = "com.piagent")
public class PiJavaApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行参数，支持 Spring Boot 标准参数与 profile 切换
     */
    public static void main(String[] args) {
        SpringApplication.run(PiJavaApplication.class, args);
    }
}

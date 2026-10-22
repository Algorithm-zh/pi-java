package com.piagent.trigger.http.system;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行环境信息控制器。
 *
 * <p>Web UI 用它获取默认工作目录与版本号，避免把开发者机器路径硬编码进静态资源。
 * 只暴露无敏感信息的进程级配置。</p>
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    private final String version;

    /**
     * 创建控制器。
     *
     * @param version 应用版本
     */
    public SystemInfoController(@Value("${pi.version:0.1.0-SNAPSHOT}") String version) {
        this.version = version;
    }

    /**
     * 查询运行环境信息。
     *
     * @return 版本、进程工作目录与操作系统
     */
    @GetMapping("/info")
    public SystemInfoResponse info() {
        Path root = Path.of("").toAbsolutePath().normalize();
        return new SystemInfoResponse(version, root.toString(), System.getProperty("os.name", ""));
    }
}

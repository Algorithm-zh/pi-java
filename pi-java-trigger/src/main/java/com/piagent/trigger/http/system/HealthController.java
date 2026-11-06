package com.piagent.trigger.http.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 无鉴权健康检查，供 CI 与容器探活使用。
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    /**
     * 查询存活状态。
     *
     * @return 固定 ok
     */
    @GetMapping
    public HealthResponse health() {
        return new HealthResponse("ok");
    }
}

package com.piagent.trigger.http.model;

import com.piagent.application.model.command.CreateModelProfileCommand;
import com.piagent.application.model.command.UpdateModelProfileCommand;
import com.piagent.application.model.service.ModelProfileApplicationService;
import com.piagent.domain.model.ModelProfile;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型档案 REST 控制器。
 *
 * <p>只负责 HTTP 契约与 DTO 映射；创建、更新、启停和校验全部由应用层完成。
 * 响应只暴露密钥环境变量名，任何情况下都不返回密钥值。</p>
 */
@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private final ModelProfileApplicationService service;

    /**
     * 创建控制器。
     *
     * @param service 模型档案用例服务
     */
    public ModelController(ModelProfileApplicationService service) {
        this.service = service;
    }

    /**
     * 查询全部模型档案。
     *
     * @return 档案列表
     */
    @GetMapping
    public List<ModelProfileResponse> list() {
        return service.listAll().stream().map(ModelController::response).toList();
    }

    /**
     * 查询单个模型档案。
     *
     * @param id 档案标识
     * @return 档案
     */
    @GetMapping("/{id}")
    public ModelProfileResponse find(@PathVariable String id) {
        return response(service.find(id));
    }

    /**
     * 创建模型档案。
     *
     * @param request 创建请求
     * @return 新档案
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelProfileResponse create(@RequestBody CreateModelRequest request) {
        ModelProfile created = service.create(new CreateModelProfileCommand(request.id(),
                request.displayName(), request.endpoint().toDomain()));
        return response(created);
    }

    /**
     * 更新模型档案的展示名与端点。
     *
     * @param id 档案标识
     * @param request 更新请求
     * @return 更新后的档案
     */
    @PutMapping("/{id}")
    public ModelProfileResponse update(@PathVariable String id, @RequestBody UpdateModelRequest request) {
        ModelProfile updated = service.update(new UpdateModelProfileCommand(id, request.displayName(),
                request.endpoint().toDomain()));
        return response(updated);
    }

    /**
     * 启用模型档案。
     *
     * @param id 档案标识
     * @return 启用后的档案
     */
    @PostMapping("/{id}/enable")
    public ModelProfileResponse enable(@PathVariable String id) {
        return response(service.enable(id));
    }

    /**
     * 停用模型档案。
     *
     * @param id 档案标识
     * @return 停用后的档案
     */
    @PostMapping("/{id}/disable")
    public ModelProfileResponse disable(@PathVariable String id) {
        return response(service.disable(id));
    }

    private static ModelProfileResponse response(ModelProfile profile) {
        return ModelProfileResponse.from(profile, ApiKeyStatus.configured(profile.endpoint()));
    }
}

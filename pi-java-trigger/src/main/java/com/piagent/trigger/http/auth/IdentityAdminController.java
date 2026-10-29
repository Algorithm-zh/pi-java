package com.piagent.trigger.http.auth;

import com.piagent.application.identity.service.IdentityAdminService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户与账号管理控制器；全部接口要求管理员身份。
 *
 * <p>租户列表是全局的（含已归档空间），账号列表只包含当前租户；新建租户同时
 * 创建其首个管理员，新账号只能落在当前租户内。删除租户是软删除，可随时恢复。</p>
 */
@RestController
public class IdentityAdminController {

    private final IdentityAdminService admin;

    /**
     * 创建控制器。
     *
     * @param admin 管理用例
     */
    public IdentityAdminController(IdentityAdminService admin) {
        this.admin = admin;
    }

    /**
     * 查询全部租户。
     *
     * @return 租户列表
     */
    @GetMapping("/api/v1/tenants")
    public List<TenantResponse> listTenants() {
        return admin.listTenants().stream().map(TenantResponse::from).toList();
    }

    /**
     * 创建租户及其首个管理员。
     *
     * @param request 创建请求
     * @return 新租户
     */
    @PostMapping("/api/v1/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse createTenant(@RequestBody CreateTenantRequest request) {
        return TenantResponse.from(admin.createTenant(request.toCommand()));
    }

    /**
     * 归档（软删除）租户；数据保留，成员立即无法登录。
     *
     * @param id 租户标识
     */
    @DeleteMapping("/api/v1/tenants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTenant(@PathVariable("id") String id) {
        admin.archiveTenant(id);
    }

    /**
     * 恢复归档租户。
     *
     * @param id 租户标识
     */
    @PostMapping("/api/v1/tenants/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreTenant(@PathVariable("id") String id) {
        admin.restoreTenant(id);
    }

    /**
     * 查询当前租户的账号。
     *
     * @return 账号列表
     */
    @GetMapping("/api/v1/users")
    public List<UserResponse> listUsers() {
        return admin.listUsers().stream().map(UserResponse::from).toList();
    }

    /**
     * 在当前租户内创建账号。
     *
     * @param request 创建请求
     * @return 新账号
     */
    @PostMapping("/api/v1/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@RequestBody CreateUserRequest request) {
        return UserResponse.from(admin.createUser(request.toCommand()));
    }
}

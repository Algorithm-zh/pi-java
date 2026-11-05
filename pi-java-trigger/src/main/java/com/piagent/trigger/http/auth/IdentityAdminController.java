package com.piagent.trigger.http.auth;

import com.piagent.application.identity.service.IdentityAdminService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 空间与成员管理控制器。
 *
 * <p>空间列表只包含当前账号加入的空间（含已归档）；任何登录用户都能创建团队
 * 空间并成为其管理员。成员列表、创建账号、邀请已有账号与删除/恢复空间要求
 * 该空间的管理员身份。删除空间是软删除，可随时恢复。</p>
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
     * 查询当前账号加入的空间。
     *
     * @return 空间列表
     */
    @GetMapping("/api/v1/tenants")
    public List<TenantResponse> listTenants() {
        return admin.listTenants().stream().map(TenantResponse::from).toList();
    }

    /**
     * 创建团队空间，创建者自动成为管理员。
     *
     * @param request 创建请求
     * @return 新空间
     */
    @PostMapping("/api/v1/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse createTenant(@RequestBody CreateTenantRequest request) {
        return TenantResponse.from(admin.createTenant(request.toCommand()));
    }

    /**
     * 归档（软删除）团队空间；默认空间、个人空间与当前空间不能删除。
     *
     * @param id 空间标识
     */
    @DeleteMapping("/api/v1/tenants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTenant(@PathVariable("id") String id) {
        admin.archiveTenant(id);
    }

    /**
     * 恢复归档空间。
     *
     * @param id 空间标识
     */
    @PostMapping("/api/v1/tenants/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreTenant(@PathVariable("id") String id) {
        admin.restoreTenant(id);
    }

    /**
     * 查询当前空间的成员。
     *
     * @return 成员列表
     */
    @GetMapping("/api/v1/users")
    public List<UserResponse> listUsers() {
        return admin.listUsers().stream().map(UserResponse::from).toList();
    }

    /**
     * 在当前空间创建新账号；新账号同时获得自己的个人空间。
     *
     * @param request 创建请求
     * @return 新账号
     */
    @PostMapping("/api/v1/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@RequestBody CreateUserRequest request) {
        return UserResponse.from(admin.createUser(request.toCommand()));
    }

    /**
     * 按登录名查找可邀请进当前空间的已有账号。
     *
     * @param username 登录名
     * @return 匹配的账号；没有匹配时为空列表
     */
    @GetMapping("/api/v1/users/lookup")
    public List<AccountResponse> lookupUsers(@RequestParam("username") String username) {
        return admin.findInvitable(username).stream().map(AccountResponse::from).toList();
    }

    /**
     * 把已有账号邀请进指定空间。
     *
     * @param id 空间标识
     * @param request 邀请请求
     * @return 新成员
     */
    @PostMapping("/api/v1/tenants/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse inviteMember(@PathVariable("id") String id,
            @RequestBody InviteMemberRequest request) {
        return UserResponse.from(admin.inviteMember(id, request.toCommand()));
    }
}

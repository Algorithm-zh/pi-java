package com.piagent.trigger.http.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.piagent.application.identity.readmodel.AccountSummary;
import com.piagent.application.identity.readmodel.TenantSummary;
import com.piagent.application.identity.readmodel.UserSummary;
import com.piagent.application.identity.service.IdentityAdminService;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 空间与成员管理控制器测试：DTO 映射与错误状态。
 */
class IdentityAdminControllerTest {

    private IdentityAdminService admin;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        admin = mock(IdentityAdminService.class);
        mvc = MockMvcBuilders.standaloneSetup(new IdentityAdminController(admin))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsTenantsWithKindAndRole() throws Exception {
        when(admin.listTenants()).thenReturn(List.of(
                new TenantSummary("01TST000000000000000000021", "default", "默认空间", "DEFAULT",
                        "ADMIN", null),
                new TenantSummary("01TST000000000000000000022", "team-b", "团队 B", "TEAM",
                        "MEMBER", null)));

        mvc.perform(get("/api/v1/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("DEFAULT"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[1].name").value("团队 B"))
                .andExpect(jsonPath("$[1].role").value("MEMBER"));
    }

    @Test
    void createsTeamSpaceAndUser() throws Exception {
        when(admin.createTenant(any()))
                .thenReturn(new TenantSummary("01TST000000000000000000023", "team-b", "团队 B",
                        "TEAM", "ADMIN", null));
        when(admin.createUser(any())).thenReturn(new UserSummary(
                "01TST000000000000000000024", "bob", "Bob", "MEMBER", "ACTIVE"));

        mvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"team-b","name":"团队 B"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("team-b"))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","displayName":"Bob",
                                 "password":"secret-pass-1","role":"MEMBER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    @Test
    void looksUpAndInvitesExistingAccount() throws Exception {
        when(admin.findInvitable("bob")).thenReturn(List.of(
                new AccountSummary("01TST000000000000000000024", "bob", "Bob")));
        when(admin.inviteMember(eq("01TST000000000000000000022"), any()))
                .thenReturn(new UserSummary("01TST000000000000000000024", "bob", "Bob",
                        "MEMBER", "ACTIVE"));

        mvc.perform(get("/api/v1/users/lookup").param("username", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("bob"));

        mvc.perform(post("/api/v1/tenants/01TST000000000000000000022/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"01TST000000000000000000024\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("bob"));
    }

    @Test
    void mapsForbiddenTo403() throws Exception {
        when(admin.listUsers()).thenThrow(new DomainException(
                DomainErrorCode.AUTH_FORBIDDEN, "只有空间管理员可以执行该操作"));

        mvc.perform(get("/api/v1/users"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void archivesAndRestoresTenant() throws Exception {
        mvc.perform(delete("/api/v1/tenants/01TST000000000000000000022"))
                .andExpect(status().isNoContent());
        verify(admin).archiveTenant("01TST000000000000000000022");

        mvc.perform(post("/api/v1/tenants/01TST000000000000000000022/restore"))
                .andExpect(status().isNoContent());
        verify(admin).restoreTenant("01TST000000000000000000022");
    }

    @Test
    void mapsProtectedTenantTo409() throws Exception {
        when(admin.archiveTenant(any())).thenThrow(new DomainException(
                DomainErrorCode.TENANT_PROTECTED, "默认空间不能删除"));

        mvc.perform(delete("/api/v1/tenants/01TST000000000000000000021"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_PROTECTED"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }
}

package com.piagent.application.identity;

import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 当前线程的认证上下文。
 *
 * <p>HTTP 过滤器在请求线程绑定主体，仓储据此追加租户条件；run 与工具线程通过
 * {@link #wrap(Runnable)} 显式传播。CLI 与启动任务没有请求上下文时回退到默认
 * 租户，保证单机升级后的存量数据仍可访问。</p>
 *
 * <p>回退只发生在进程内部调用；所有 {@code /api/**} 请求都必须先通过鉴权过滤器，
 * 客户端不能借回退绕过租户隔离。</p>
 */
public final class AuthContext {

    private static final ThreadLocal<AuthPrincipal> PRINCIPAL = new ThreadLocal<>();
    private static final ThreadLocal<TenantId> TENANT = new ThreadLocal<>();

    private AuthContext() {
    }

    /**
     * 绑定用户主体。
     *
     * @param principal 主体
     */
    public static void bind(AuthPrincipal principal) {
        PRINCIPAL.set(principal);
        TENANT.set(principal.tenantId());
    }

    /**
     * 仅绑定租户，用于启动任务与后台线程。
     *
     * @param tenantId 租户
     */
    public static void bindTenant(TenantId tenantId) {
        PRINCIPAL.remove();
        TENANT.set(tenantId);
    }

    /**
     * 清空当前线程绑定。
     */
    public static void clear() {
        PRINCIPAL.remove();
        TENANT.remove();
    }

    /**
     * 当前用户主体。
     *
     * @return 主体；系统任务或未登录时为空
     */
    public static Optional<AuthPrincipal> principal() {
        return Optional.ofNullable(PRINCIPAL.get());
    }

    /**
     * 要求存在用户主体。
     *
     * @return 主体
     */
    public static AuthPrincipal requirePrincipal() {
        return principal().orElseThrow(() -> new DomainException(DomainErrorCode.AUTH_REQUIRED,
                "请先登录"));
    }

    /**
     * 当前租户；未绑定时回退到默认租户。
     *
     * @return 租户标识
     */
    public static TenantId tenantId() {
        TenantId current = TENANT.get();
        if (current != null) {
            return current;
        }
        AuthPrincipal principal = PRINCIPAL.get();
        return principal == null ? TenantId.DEFAULT : principal.tenantId();
    }

    /**
     * 以指定主体执行并恢复原上下文。
     *
     * @param principal 主体
     * @param action 动作
     * @param <T> 返回类型
     * @return 动作结果
     */
    public static <T> T callAs(AuthPrincipal principal, Supplier<T> action) {
        Snapshot previous = capture();
        bind(principal);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /**
     * 以指定租户的系统身份执行并恢复原上下文。
     *
     * @param tenantId 租户
     * @param action 动作
     * @param <T> 返回类型
     * @return 动作结果
     */
    public static <T> T callAsTenant(TenantId tenantId, Supplier<T> action) {
        Snapshot previous = capture();
        bindTenant(tenantId);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /**
     * 包装任务，在子线程恢复当前上下文。
     *
     * @param task 任务
     * @return 包装后的任务
     */
    public static Runnable wrap(Runnable task) {
        TenantId tenant = tenantId();
        AuthPrincipal principal = PRINCIPAL.get();
        return () -> {
            Snapshot previous = capture();
            if (principal != null) {
                bind(principal);
            } else {
                bindTenant(tenant);
            }
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * 包装有返回值的任务，在子线程恢复当前上下文。
     *
     * @param task 任务
     * @param <T> 返回类型
     * @return 包装后的任务
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        TenantId tenant = tenantId();
        AuthPrincipal principal = PRINCIPAL.get();
        return () -> {
            Snapshot previous = capture();
            if (principal != null) {
                bind(principal);
            } else {
                bindTenant(tenant);
            }
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    private static Snapshot capture() {
        return new Snapshot(PRINCIPAL.get(), TENANT.get());
    }

    private static void restore(Snapshot snapshot) {
        if (snapshot.principal() == null) {
            PRINCIPAL.remove();
        } else {
            PRINCIPAL.set(snapshot.principal());
        }
        if (snapshot.tenant() == null) {
            TENANT.remove();
        } else {
            TENANT.set(snapshot.tenant());
        }
    }

    private record Snapshot(AuthPrincipal principal, TenantId tenant) {
    }
}

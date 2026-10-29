package com.piagent.application.identity;

import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.port.TenantRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * 逐租户执行后台任务。
 *
 * <p>启动自愈、标题回填与附件回收都没有请求线程，必须显式绑定租户，否则仓储
 * 会回退到默认租户，其他租户的遗留数据永远得不到处理。单个租户失败只记录日志，
 * 不影响其余租户。</p>
 */
@Service
public class TenantTaskRunner {

    private static final System.Logger LOG =
            System.getLogger(TenantTaskRunner.class.getName());

    private final TenantRepository tenants;

    /**
     * 创建执行器。
     *
     * @param tenants 租户仓储
     */
    public TenantTaskRunner(TenantRepository tenants) {
        this.tenants = tenants;
    }

    /**
     * 在每个租户上下文中执行任务。
     *
     * @param task 以租户为入参的任务
     * @param <T> 结果类型
     * @return 成功租户的结果列表，失败租户不出现
     */
    public <T> List<T> forEachTenant(Function<TenantId, T> task) {
        List<T> results = new ArrayList<>();
        for (TenantId tenantId : tenants.findAllIds()) {
            try {
                results.add(AuthContext.callAsTenant(tenantId, () -> task.apply(tenantId)));
            } catch (RuntimeException error) {
                LOG.log(System.Logger.Level.WARNING,
                        "租户 " + tenantId + " 的后台任务失败", error);
            }
        }
        return results;
    }
}

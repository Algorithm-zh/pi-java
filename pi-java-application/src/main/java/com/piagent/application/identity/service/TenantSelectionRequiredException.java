package com.piagent.application.identity.service;

import com.piagent.application.identity.readmodel.TenantChoice;
import java.util.List;

/**
 * 登录需要用户选择空间时抛出。
 *
 * <p>只在账号密码已校验通过、且同一登录名在多个空间中匹配时抛出，因此
 * 响应里附带的空间清单不会向不知道密码的人泄漏账号归属。</p>
 */
public class TenantSelectionRequiredException extends RuntimeException {

    private final transient List<TenantChoice> choices;

    /**
     * 创建异常。
     *
     * @param choices 候选空间
     */
    public TenantSelectionRequiredException(List<TenantChoice> choices) {
        super("该账号属于多个空间，请选择要进入的空间");
        this.choices = List.copyOf(choices);
    }

    /**
     * 候选空间。
     *
     * @return 空间列表
     */
    public List<TenantChoice> choices() {
        return choices;
    }
}

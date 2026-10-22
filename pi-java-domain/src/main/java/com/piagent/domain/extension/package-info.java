/**
 * 插件上下文：插件安装记录、兼容性校验与激活策略。
 *
 * <p>插件运行时状态在基础设施层维护；领域层只负责安装与版本不变量。
 * 插件工具的具体类型位于零依赖的 {@code pi-java-plugin-api}，领域层不引用。</p>
 */
package com.piagent.domain.extension;

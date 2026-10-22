/**
 * 插件 API：第三方插件编译期依赖的稳定契约。
 *
 * <p>本包只允许依赖 JDK；JSON 使用字符串或 Map，不暴露 Jackson、Spring 或领域类型。
 * v0.1 开放工具注册与日志；命令、hook、拦截器与 prompt 片段会以新增类型和
 * default 方法的方式加入，保持主版本 1 兼容。</p>
 */
package com.piagent.plugin.api;

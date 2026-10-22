/**
 * 示例插件：演示零依赖工具注册与插件生命周期。
 *
 * <p>本模块只依赖 {@code pi-java-plugin-api}（provided），用于验证插件契约
 * 不泄漏宿主实现。hook 与命令注册会在后续里程碑加入。
 */
package com.piagent.example;

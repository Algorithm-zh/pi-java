package com.piagent.application.identity.readmodel;

/**
 * 可选空间，用于“同一账号存在于多个空间”时让用户直接点选。
 *
 * @param slug 空间短名
 * @param name 空间名称
 */
public record TenantChoice(String slug, String name) {
}

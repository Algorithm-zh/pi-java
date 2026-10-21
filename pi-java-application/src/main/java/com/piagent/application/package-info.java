/**
 * 应用层：用例编排、Agent Loop、事务边界与读模型。
 *
 * <p>允许使用 Spring 的 stereotype/transaction 注解，但不得依赖 MyBatis、Jackson、
 * Web 或基础设施实现；所有外部能力通过领域端口注入。
 */
package com.piagent.application;

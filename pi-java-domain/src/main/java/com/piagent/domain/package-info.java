/**
 * 纯领域层。
 *
 * <p>本包只允许依赖 JDK，不允许出现 Spring、Jackson、MyBatis 等框架类型。
 * 七个限界上下文以子包隔离：session、agent、model、tool、governance、context、extension。
 */
package com.piagent.domain;

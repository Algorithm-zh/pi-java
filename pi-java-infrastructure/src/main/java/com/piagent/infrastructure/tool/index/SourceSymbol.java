package com.piagent.infrastructure.tool.index;

/**
 * 单个源码符号的摘要。
 *
 * @param line 行号，从 1 开始
 * @param name 声明名，供检索加权
 * @param text 规范化后的声明文本
 */
record SourceSymbol(int line, String name, String text) {
}

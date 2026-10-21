/**
 * 内置只读工具执行体。
 *
 * <p>每个工具一个类，只做参数解析与 IO；路径必须先经 {@code RealPathResolver}，
 * 任何越界或符号链接逃逸都会变成错误结果。输出有行数、结果数与字符数上限，
 * 保证大仓库不会把上下文撑爆。</p>
 */
package com.piagent.infrastructure.tool.handler;

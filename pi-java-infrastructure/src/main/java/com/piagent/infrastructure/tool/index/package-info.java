/**
 * 工作区代码索引与仓库地图。
 *
 * <p>只用 JDK：遍历工作区、跳过二进制与构建目录，按文件指纹缓存词频；
 * 检索采用 BM25 风格打分并对路径与符号加权。不引入语法树、LSP 或向量库，
 * 目标是让模型先定位少量相关文件，再用 read/grep 精读。</p>
 */
package com.piagent.infrastructure.tool.index;

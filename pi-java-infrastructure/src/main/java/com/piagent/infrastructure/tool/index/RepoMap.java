package com.piagent.infrastructure.tool.index;

/**
 * 一次仓库地图或代码检索的结果。
 *
 * @param text 给模型看的文本
 * @param matchedFiles 命中文件总数
 * @param indexedFiles 索引中的文本文件数
 * @param truncated 索引是否因上限截断
 */
public record RepoMap(String text, int matchedFiles, int indexedFiles, boolean truncated) {
}

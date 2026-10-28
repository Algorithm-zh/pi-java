package com.piagent.infrastructure.tool.index;

/**
 * 一次检索命中的文件与得分。
 *
 * @param file 文件摘要
 * @param score 相关度得分
 */
record FileHit(IndexSnapshot.IndexedFile file, double score) {
}

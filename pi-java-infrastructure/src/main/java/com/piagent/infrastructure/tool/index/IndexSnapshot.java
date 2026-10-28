package com.piagent.infrastructure.tool.index;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作区代码索引快照。
 *
 * @param files 相对路径到文件摘要的映射
 * @param docFreq 每个词出现在多少个文件里
 * @param skippedBinary 跳过的二进制文件数
 * @param skippedLarge 跳过的超大文件数
 * @param truncated 是否因文件数或总量上限截断
 * @param indexedBytes 已索引文件的总字节数
 */
record IndexSnapshot(Map<String, IndexedFile> files, Map<String, Integer> docFreq,
        int skippedBinary, int skippedLarge, boolean truncated, long indexedBytes) {

    /**
     * 单个文件的可检索摘要。
     *
     * @param relativePath 相对索引根目录的路径
     * @param size 文件字节数
     * @param terms 内容词频
     * @param pathTerms 路径词
     * @param symbolTerms 符号名词
     * @param symbols 符号列表
     * @param tokenCount 内容词总数，用于长度归一化
     */
    record IndexedFile(String relativePath, long size, Map<String, Integer> terms,
            Set<String> pathTerms, Set<String> symbolTerms, List<SourceSymbol> symbols,
            int tokenCount) {
    }

    /**
     * 文件指纹；大小或修改时间变化即认为需要重建。
     *
     * @param relativePath 相对路径
     * @param size 字节数
     * @param modifiedMillis 最后修改时间
     */
    record Stamp(String relativePath, long size, long modifiedMillis) {
    }
}

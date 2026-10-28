package com.piagent.infrastructure.tool.index;

/**
 * 检索范围：工作区全量、某个子目录或单个文件。
 *
 * @param prefix 使用 {@code /} 的相对前缀，空串表示整个索引根
 * @param singleFile true 表示只匹配 prefix 对应的单个文件
 */
record IndexScope(String prefix, boolean singleFile) {

    /**
     * 判断文件是否在范围内。
     *
     * @param relativePath 相对路径
     * @return true 表示命中
     */
    boolean matches(String relativePath) {
        if (prefix.isEmpty()) {
            return true;
        }
        if (singleFile) {
            return relativePath.equals(prefix);
        }
        return relativePath.equals(prefix) || relativePath.startsWith(prefix + "/");
    }

    /**
     * 去掉范围前缀，用于概览里的目录统计。
     *
     * @param relativePath 相对路径
     * @return 范围内路径；范围外返回空串
     */
    String strip(String relativePath) {
        if (prefix.isEmpty()) {
            return relativePath;
        }
        if (singleFile) {
            return relativePath.equals(prefix) ? fileName(relativePath) : "";
        }
        if (relativePath.equals(prefix)) {
            return "";
        }
        return relativePath.startsWith(prefix + "/")
                ? relativePath.substring(prefix.length() + 1)
                : "";
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}

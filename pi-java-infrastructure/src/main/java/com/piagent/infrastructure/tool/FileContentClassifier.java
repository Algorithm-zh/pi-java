package com.piagent.infrastructure.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文本/二进制文件探测。
 *
 * <p>只读取前 8KB 并检查 NUL 字节，足以识别常见二进制格式（jar、图片、class）；
 * 不做字符集猜测，读取时统一按 UTF-8 解码，避免为探测引入额外依赖。</p>
 */
public final class FileContentClassifier {

    private static final int PROBE_BYTES = 8 * 1024;

    private FileContentClassifier() {
    }

    /**
     * 判断文件是否疑似二进制。
     *
     * @param file 文件
     * @return true 表示前 8KB 含 NUL 字节
     * @throws IOException 读取失败
     */
    public static boolean isBinary(Path file) throws IOException {
        byte[] buffer = new byte[PROBE_BYTES];
        try (InputStream input = Files.newInputStream(file)) {
            int read = input.read(buffer);
            for (int index = 0; index < read; index++) {
                if (buffer[index] == 0) {
                    return true;
                }
            }
        }
        return false;
    }
}

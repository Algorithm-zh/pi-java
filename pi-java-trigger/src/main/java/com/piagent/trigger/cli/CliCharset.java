package com.piagent.trigger.cli;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * CLI 输出编码选择。
 *
 * <p>默认跟随终端原生编码：Windows 中文控制台按 GBK 解码，强行输出 UTF-8 会乱码。
 * Git Bash/MSYS 的终端按 UTF-8 解码，但 Java 仍可能报告 GBK，因此当 LANG/LC_ALL
 * 声明 UTF-8 时改用 UTF-8。可用 {@code PI_CLI_ENCODING} 显式覆盖。</p>
 */
public final class CliCharset {

    private CliCharset() {
    }

    /**
     * 解析本次进程应使用的输出编码。
     *
     * @return 输出编码
     */
    public static Charset resolve() {
        String explicit = System.getenv("PI_CLI_ENCODING");
        if (explicit != null && !explicit.isBlank()) {
            try {
                return Charset.forName(explicit.strip());
            } catch (RuntimeException ignored) {
                // 非法编码名忽略，继续按环境推断
            }
        }
        if (localeIsUtf8()) {
            return StandardCharsets.UTF_8;
        }
        return System.out.charset();
    }

    /**
     * 按选定编码包装输出流。
     *
     * @param stream 目标流
     * @return 自动 flush 的 writer
     */
    public static PrintWriter writer(OutputStream stream) {
        return new PrintWriter(new OutputStreamWriter(stream, resolve()), true);
    }

    private static boolean localeIsUtf8() {
        for (String key : new String[] {"LC_ALL", "LC_CTYPE", "LANG"}) {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.toUpperCase(Locale.ROOT).replace("-", "");
            return normalized.contains("UTF8");
        }
        return false;
    }
}

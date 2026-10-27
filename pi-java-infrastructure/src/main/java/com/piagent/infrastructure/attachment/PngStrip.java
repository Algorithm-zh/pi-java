package com.piagent.infrastructure.attachment;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * PNG 元数据清理。
 *
 * <p>丢弃 eXIf、tEXt、zTXt、iTXt 与 tIME 块；保留颜色配置、透明度和像素块。
 * 必须看到 IEND 才返回裁剪结果，结构不完整时原样返回。</p>
 */
final class PngStrip {

    private static final byte[] SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final Set<String> METADATA =
            Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");
    private static final int HEADER = 8;
    private static final int CHUNK_OVERHEAD = 12;

    private PngStrip() {
    }

    static byte[] strip(byte[] data) {
        if (data.length < HEADER || !startsWith(data)) {
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(data, 0, HEADER);
        int pos = HEADER;
        boolean changed = false;
        while (pos + CHUNK_OVERHEAD <= data.length) {
            long length = readInt(data, pos);
            if (length > data.length - pos - CHUNK_OVERHEAD) {
                return data;
            }
            int size = (int) length;
            String type = new String(data, pos + 4, 4, StandardCharsets.ISO_8859_1);
            if (METADATA.contains(type)) {
                changed = true;
            } else {
                out.write(data, pos, size + CHUNK_OVERHEAD);
            }
            pos += size + CHUNK_OVERHEAD;
            if ("IEND".equals(type)) {
                out.write(data, pos, data.length - pos);
                return changed ? out.toByteArray() : data;
            }
        }
        return data;
    }

    private static boolean startsWith(byte[] data) {
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (data[i] != SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static long readInt(byte[] data, int pos) {
        return ((long) (data[pos] & 0xFF) << 24)
                | ((long) (data[pos + 1] & 0xFF) << 16)
                | ((long) (data[pos + 2] & 0xFF) << 8)
                | (data[pos + 3] & 0xFF);
    }
}

package com.piagent.infrastructure.attachment;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * WebP 元数据清理。
 *
 * <p>丢弃 RIFF 中的 EXIF 与 XMP 块，并清除 VP8X 扩展头里对应的标志位，
 * 再修正 RIFF 总长度。只处理结构完整的文件。</p>
 */
final class WebpStrip {

    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};
    private static final int VP8X_EXIF = 0x08;
    private static final int VP8X_XMP = 0x04;
    private static final int CHUNK_HEADER = 8;

    private WebpStrip() {
    }

    static byte[] strip(byte[] data) {
        if (data.length < 20 || !matches(data, 0, RIFF) || !matches(data, 8, WEBP)) {
            return data;
        }
        List<Chunk> kept = new ArrayList<>();
        boolean changed = false;
        int pos = 12;
        while (pos + CHUNK_HEADER <= data.length) {
            int size = readLe32(data, pos + 4);
            if (size < 0 || pos + CHUNK_HEADER + size > data.length) {
                return data;
            }
            String fourCc = new String(data, pos, 4, StandardCharsets.ISO_8859_1);
            if ("EXIF".equals(fourCc) || "XMP ".equals(fourCc)) {
                changed = true;
            } else {
                kept.add(new Chunk(fourCc, pos, size));
            }
            pos += CHUNK_HEADER + size + (size & 1);
        }
        if (pos != data.length || !changed) {
            return data;
        }
        return rebuild(data, kept);
    }

    private static byte[] rebuild(byte[] data, List<Chunk> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(data, 0, 12);
        for (Chunk chunk : chunks) {
            out.write(data, chunk.start, CHUNK_HEADER);
            int dataStart = chunk.start + CHUNK_HEADER;
            if ("VP8X".equals(chunk.fourCc) && chunk.size > 0) {
                byte flags = (byte) (data[dataStart] & ~(VP8X_EXIF | VP8X_XMP));
                out.write(flags);
                out.write(data, dataStart + 1, chunk.size - 1);
            } else {
                out.write(data, dataStart, chunk.size);
            }
            if ((chunk.size & 1) == 1) {
                out.write(0);
            }
        }
        byte[] result = out.toByteArray();
        writeLe32(result, 4, result.length - 8);
        return result;
    }

    private static boolean matches(byte[] data, int offset, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int readLe32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void writeLe32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private record Chunk(String fourCc, int start, int size) {
    }
}

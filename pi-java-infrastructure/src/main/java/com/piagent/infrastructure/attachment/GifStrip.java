package com.piagent.infrastructure.attachment;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * GIF 元数据清理。
 *
 * <p>只移除注释扩展块（0xFE）；保留 NETSCAPE 循环、图形控制与其余应用扩展，
 * 以免破坏动画行为。</p>
 */
final class GifStrip {

    private static final byte[] GIF87 = "GIF87a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GIF89 = "GIF89a".getBytes(StandardCharsets.US_ASCII);

    private static final int EXTENSION = 0x21;
    private static final int COMMENT = 0xFE;
    private static final int IMAGE = 0x2C;
    private static final int TRAILER = 0x3B;

    private GifStrip() {
    }

    static byte[] strip(byte[] data) {
        if (data.length < 13 || !(matches(data, GIF87) || matches(data, GIF89))) {
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(data, 0, 13);
        int packed = data[10] & 0xFF;
        int pos = 13;
        if ((packed & 0x80) != 0) {
            int tableSize = 3 * (1 << ((packed & 0x07) + 1));
            if (pos + tableSize > data.length) {
                return data;
            }
            out.write(data, pos, tableSize);
            pos += tableSize;
        }
        boolean changed = false;
        while (pos < data.length) {
            int block = data[pos] & 0xFF;
            if (block == TRAILER) {
                out.write(data, pos, data.length - pos);
                return changed ? out.toByteArray() : data;
            }
            if (block == EXTENSION) {
                int end = skipSubBlocks(data, pos + 2);
                if (end < 0) {
                    return data;
                }
                if ((data[pos + 1] & 0xFF) == COMMENT) {
                    changed = true;
                } else {
                    out.write(data, pos, end - pos);
                }
                pos = end;
            } else if (block == IMAGE) {
                int end = imageEnd(data, pos);
                if (end < 0) {
                    return data;
                }
                out.write(data, pos, end - pos);
                pos = end;
            } else {
                return data;
            }
        }
        return data;
    }

    private static int imageEnd(byte[] data, int start) {
        int pos = start + 10;
        if (pos > data.length) {
            return -1;
        }
        int packed = data[start + 9] & 0xFF;
        if ((packed & 0x80) != 0) {
            pos += 3 * (1 << ((packed & 0x07) + 1));
        }
        if (pos >= data.length) {
            return -1;
        }
        return skipSubBlocks(data, pos + 1);
    }

    private static int skipSubBlocks(byte[] data, int pos) {
        while (pos < data.length) {
            int size = data[pos] & 0xFF;
            pos++;
            if (size == 0) {
                return pos;
            }
            pos += size;
            if (pos > data.length) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean matches(byte[] data, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (data[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}

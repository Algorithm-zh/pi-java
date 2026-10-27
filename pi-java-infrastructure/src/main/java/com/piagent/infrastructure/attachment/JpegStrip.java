package com.piagent.infrastructure.attachment;

import java.io.ByteArrayOutputStream;

/**
 * JPEG 元数据清理。
 *
 * <p>丢弃 APP1（EXIF/XMP）、APP13（Photoshop IPTC）与 COM 注释段；保留 JFIF、
 * ICC 色彩配置和其余段。遇到 SOS 后剩余压缩数据原样拷贝。</p>
 */
final class JpegStrip {

    private static final int SOI = 0xD8;
    private static final int SOS = 0xDA;
    private static final int EOI = 0xD9;
    private static final int APP1 = 0xE1;
    private static final int APP13 = 0xED;
    private static final int COMMENT = 0xFE;

    private JpegStrip() {
    }

    static byte[] strip(byte[] data) {
        if (data.length < 4 || u8(data[0]) != 0xFF || u8(data[1]) != SOI) {
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(data, 0, 2);
        int pos = 2;
        boolean changed = false;
        while (pos + 4 <= data.length) {
            if (u8(data[pos]) != 0xFF) {
                return data;
            }
            int marker = u8(data[pos + 1]);
            if (marker == 0xFF) {
                pos++;
                continue;
            }
            if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= SOI)) {
                out.write(data, pos, 2);
                pos += 2;
                continue;
            }
            if (marker == SOS || marker == EOI) {
                out.write(data, pos, data.length - pos);
                return changed ? out.toByteArray() : data;
            }
            int length = (u8(data[pos + 2]) << 8) | u8(data[pos + 3]);
            if (length < 2 || pos + 2 + length > data.length) {
                return data;
            }
            if (isMetadata(marker)) {
                changed = true;
            } else {
                out.write(data, pos, length + 2);
            }
            pos += length + 2;
        }
        return data;
    }

    private static boolean isMetadata(int marker) {
        return marker == APP1 || marker == APP13 || marker == COMMENT;
    }

    private static int u8(byte value) {
        return value & 0xFF;
    }
}

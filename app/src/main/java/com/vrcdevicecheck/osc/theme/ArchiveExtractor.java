package com.vrcdevicecheck.osc.theme;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 主题包的解压器，支持 .zip 与 .tar.gz / .tgz。
 *
 * Android 自带 ZipInputStream，但没有 tar；这里手写一个最小的 tar 读取器
 * （ustar 512 字节头 + 八进制长度 + 512 对齐），只处理普通文件和目录。
 * 所有解压路径都做了目录穿越校验。
 */
final class ArchiveExtractor {

    private ArchiveExtractor() {
    }

    static void extract(InputStream raw, File dest) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(4);
        int b1 = in.read();
        int b2 = in.read();
        in.reset();
        if (b1 == 'P' && b2 == 'K') {
            extractZip(in, dest);
        } else if (b1 == 0x1F && b2 == 0x8B) {
            extractTarGz(in, dest);
        } else {
            throw new IOException("无法识别的压缩格式，只支持 zip / tar.gz");
        }
    }

    // ---------------- zip ----------------

    private static void extractZip(InputStream in, File dest) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        byte[] buffer = new byte[8192];
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = safeChild(dest, entry.getName());
                if (out == null) continue;
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    int read;
                    while ((read = zip.read(buffer)) > 0) fos.write(buffer, 0, read);
                } finally {
                    closeQuietly(fos);
                }
            }
        } finally {
            closeQuietly(zip);
        }
    }

    // ---------------- tar.gz ----------------

    private static void extractTarGz(InputStream in, File dest) throws IOException {
        GZIPInputStream gz = new GZIPInputStream(in);
        byte[] header = new byte[512];
        String pendingName = null;
        try {
            while (true) {
                int read = readFully(gz, header, 512);
                if (read < 512) break;
                if (isZeroBlock(header)) break;

                char type = (char) (header[156] & 0xFF);
                long size = parseOctal(header, 124, 12);
                String name = readString(header, 0, 100);
                String prefix = readString(header, 345, 155);
                if (!prefix.isEmpty() && !name.startsWith(prefix)) {
                    name = prefix + "/" + name;
                }

                if (type == 'L') {
                    // GNU 长文件名：下一块是真实名字
                    byte[] data = new byte[(int) size];
                    readFully(gz, data, (int) size);
                    pendingName = trimNul(new String(data, "UTF-8"));
                    skip(gz, padding(size));
                    continue;
                }
                if (type == 'x' || type == 'g') {
                    skip(gz, size + padding(size));
                    continue;
                }
                if (pendingName != null) {
                    name = pendingName;
                    pendingName = null;
                }

                boolean isDir = type == '5' || name.endsWith("/");
                if (isDir) {
                    File dir = safeChild(dest, name);
                    if (dir != null) dir.mkdirs();
                    skip(gz, size + padding(size));
                } else if (type == '0' || type == 0) {
                    File out = safeChild(dest, name);
                    if (out == null) {
                        skip(gz, size);
                    } else {
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        FileOutputStream fos = new FileOutputStream(out);
                        try {
                            copy(gz, fos, size);
                        } finally {
                            closeQuietly(fos);
                        }
                    }
                    skip(gz, padding(size));
                } else {
                    // 设备节点、符号链接等一律跳过
                    skip(gz, size + padding(size));
                }
            }
        } finally {
            closeQuietly(gz);
        }
    }

    private static void copy(InputStream in, FileOutputStream out, long size) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read <= 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static long padding(long size) {
        long remainder = size % 512;
        return remainder == 0 ? 0 : 512 - remainder;
    }

    private static int readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, total, length - total);
            if (read <= 0) break;
            total += read;
        }
        return total;
    }

    private static void skip(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static long parseOctal(byte[] header, int offset, int length) {
        long result = 0;
        boolean started = false;
        for (int i = offset; i < offset + length; i++) {
            int value = header[i] & 0xFF;
            if (value == 0) break;
            if (value == ' ') {
                // 允许前导空格；数字开始后再遇到空格就是字段结束
                if (started) break;
                continue;
            }
            if (value < '0' || value > '7') {
                if (started) break;
                continue;
            }
            started = true;
            result = result * 8 + (value - '0');
        }
        return result;
    }

    private static String readString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, java.nio.charset.Charset.forName("UTF-8")).trim();
    }

    private static String trimNul(String value) {
        int index = value.indexOf('\0');
        return (index >= 0 ? value.substring(0, index) : value).trim();
    }

    private static File safeChild(File dest, String name) throws IOException {
        if (name == null) return null;
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isEmpty()) return null;
        File out = new File(dest, normalized);
        String base = dest.getCanonicalPath();
        if (!out.getCanonicalPath().startsWith(base + File.separator)) return null;
        return out;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}

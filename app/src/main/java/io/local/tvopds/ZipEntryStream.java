package io.local.tvopds;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Opens one unencrypted entry through a seekable SAF file descriptor; no archive is copied locally. */
final class ZipEntryStream {
    private ZipEntryStream() {}

    static InputStream open(Context context, Uri archive, String wantedName) throws IOException {
        if ("file".equals(archive.getScheme())) return openFile(new File(archive.getPath()), wantedName);
        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(archive, "r");
        if (descriptor == null) throw new IOException("Не удалось открыть ZIP-архив");
        FileInputStream file = new FileInputStream(descriptor.getFileDescriptor());
        try {
            FileChannel channel = file.getChannel();
            Entry entry = find(channel, wantedName);
            long dataOffset = localDataOffset(channel, entry.localOffset);
            channel.position(dataOffset);
            InputStream compressed = new BoundedChannelInputStream(channel, entry.compressedSize);
            InputStream contents;
            if (entry.method == 0) contents = compressed;
            else if (entry.method == 8) contents = new InflaterInputStream(compressed, new Inflater(true), 16 * 1024);
            else throw new IOException("Неподдерживаемое сжатие ZIP: " + entry.method);
            return new ClosingInputStream(contents, file, descriptor);
        } catch (Exception e) {
            try { file.close(); } catch (Exception ignored) {}
            try { descriptor.close(); } catch (Exception ignored) {}
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Не удалось открыть книгу в ZIP", e);
        }
    }

    private static InputStream openFile(File path, String wantedName) throws IOException {
        FileInputStream file = new FileInputStream(path);
        try {
            FileChannel channel = file.getChannel();
            Entry entry = find(channel, wantedName);
            channel.position(localDataOffset(channel, entry.localOffset));
            InputStream compressed = new BoundedChannelInputStream(channel, entry.compressedSize);
            InputStream contents = entry.method == 0 ? compressed : entry.method == 8
                    ? new InflaterInputStream(compressed, new Inflater(true), 16 * 1024)
                    : null;
            if (contents == null) throw new IOException("Неподдерживаемое сжатие ZIP: " + entry.method);
            return new ClosingInputStream(contents, file, null);
        } catch (Exception e) {
            try { file.close(); } catch (Exception ignored) {}
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Не удалось открыть книгу в ZIP", e);
        }
    }

    private static final class Entry {
        final int method;
        final long compressedSize;
        final long localOffset;
        Entry(int method, long compressedSize, long localOffset) {
            this.method = method; this.compressedSize = compressedSize; this.localOffset = localOffset;
        }
    }

    private static Entry find(FileChannel channel, String wantedName) throws IOException {
        long length = channel.size();
        if (length < 22) throw new IOException("Некорректный ZIP-архив");
        int tailLength = (int) Math.min(length, 65_557L);
        byte[] tail = readAt(channel, length - tailLength, tailLength);
        int eocd = -1;
        for (int p = tail.length - 22; p >= 0; p--) {
            if (u32(tail, p) == 0x06054b50L && p + 22 + u16(tail, p + 20) == tail.length) { eocd = p; break; }
        }
        if (eocd < 0) throw new IOException("В ZIP не найден центральный каталог");
        if (u16(tail, eocd + 4) != 0 || u16(tail, eocd + 6) != 0) throw new IOException("Многотомный ZIP не поддерживается");
        long count = u16(tail, eocd + 10);
        long directoryOffset = u32(tail, eocd + 16);
        if (count == 0xffff || directoryOffset == 0xffffffffL) {
            long[] zip64 = zip64Directory(channel, length - tailLength + eocd);
            count = zip64[0];
            directoryOffset = zip64[1];
        }
        long position = directoryOffset;
        Charset cp437 = Charset.forName("CP437");
        for (long i = 0; i < count; i++) {
            byte[] h = readAt(channel, position, 46);
            if (u32(h, 0) != 0x02014b50L) throw new IOException("Некорректный центральный каталог ZIP");
            int flags = u16(h, 8), method = u16(h, 10), nameLength = u16(h, 28);
            int extraLength = u16(h, 30), commentLength = u16(h, 32);
            long uncompressedSize = u32(h, 24), compressedSize = u32(h, 20), localOffset = u32(h, 42);
            if (compressedSize == 0xffffffffL || localOffset == 0xffffffffL) {
                long[] values = zip64Entry(readAt(channel, position + 46L + nameLength, extraLength),
                        uncompressedSize == 0xffffffffL, compressedSize == 0xffffffffL, localOffset == 0xffffffffL);
                if (compressedSize == 0xffffffffL) compressedSize = values[1];
                if (localOffset == 0xffffffffL) localOffset = values[2];
            }
            byte[] nameBytes = readAt(channel, position + 46, nameLength);
            String name = new String(nameBytes, (flags & 0x0800) != 0 ? StandardCharsets.UTF_8 : cp437);
            if (wantedName.equals(name)) {
                if ((flags & 1) != 0) throw new IOException("Зашифрованный ZIP не поддерживается");
                return new Entry(method, compressedSize, localOffset);
            }
            position += 46L + nameLength + extraLength + commentLength;
        }
        throw new IOException("Книга отсутствует в ZIP-архиве: " + wantedName);
    }

    /** Returns total entry count and central-directory offset from ZIP64 end records. */
    private static long[] zip64Directory(FileChannel channel, long eocdOffset) throws IOException {
        if (eocdOffset < 20) throw new IOException("Некорректный ZIP64-архив");
        byte[] locator = readAt(channel, eocdOffset - 20, 20);
        if (u32(locator, 0) != 0x07064b50L) throw new IOException("В ZIP64 не найден locator");
        long recordOffset = u64(locator, 8);
        byte[] record = readAt(channel, recordOffset, 56);
        if (u32(record, 0) != 0x06064b50L) throw new IOException("В ZIP64 не найден центральный каталог");
        if (u32(record, 16) != 0 || u32(record, 20) != 0) throw new IOException("Многотомный ZIP не поддерживается");
        return new long[]{u64(record, 32), u64(record, 48)};
    }

    /** Returns uncompressed size, compressed size and local-header offset from the ZIP64 extra field. */
    private static long[] zip64Entry(byte[] extra, boolean needUncompressed, boolean needCompressed, boolean needOffset) throws IOException {
        int p = 0;
        while (p + 4 <= extra.length) {
            int id = u16(extra, p), length = u16(extra, p + 2);
            p += 4;
            if (p + length > extra.length) throw new IOException("Некорректное дополнительное поле ZIP");
            if (id == 0x0001) {
                int v = p;
                long uncompressed = -1, compressed = -1, offset = -1;
                if (needUncompressed) { requireZip64Value(v, p + length); uncompressed = u64(extra, v); v += 8; }
                if (needCompressed) { requireZip64Value(v, p + length); compressed = u64(extra, v); v += 8; }
                if (needOffset) { requireZip64Value(v, p + length); offset = u64(extra, v); }
                return new long[]{uncompressed, compressed, offset};
            }
            p += length;
        }
        throw new IOException("В ZIP64 отсутствуют размеры записи");
    }

    private static void requireZip64Value(int position, int end) throws IOException {
        if (position + 8 > end) throw new IOException("Некорректное ZIP64-дополнение");
    }

    private static long localDataOffset(FileChannel channel, long offset) throws IOException {
        byte[] header = readAt(channel, offset, 30);
        if (u32(header, 0) != 0x04034b50L) throw new IOException("Некорректная локальная запись ZIP");
        return offset + 30L + u16(header, 26) + u16(header, 28);
    }

    private static byte[] readAt(FileChannel channel, long position, int length) throws IOException {
        byte[] bytes = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) throw new IOException("Неожиданный конец ZIP-архива");
        }
        return bytes;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (long) u16(data, offset) | ((long) u16(data, offset + 2) << 16);
    }

    private static long u64(byte[] data, int offset) throws IOException {
        long low = u32(data, offset), high = u32(data, offset + 4);
        if (high > 0x7fffffffL) throw new IOException("ZIP-значение слишком велико");
        return low | (high << 32);
    }

    private static final class BoundedChannelInputStream extends InputStream {
        private final FileChannel channel;
        private long remaining;
        BoundedChannelInputStream(FileChannel channel, long remaining) { this.channel = channel; this.remaining = remaining; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }
        @Override public int read(byte[] data, int offset, int length) throws IOException {
            if (remaining == 0) return -1;
            int request = (int) Math.min(remaining, length);
            int read = channel.read(ByteBuffer.wrap(data, offset, request));
            if (read > 0) remaining -= read;
            return read;
        }
        @Override public void close() {}
    }

    private static final class ClosingInputStream extends FilterInputStream {
        private final FileInputStream file;
        private final ParcelFileDescriptor descriptor;
        private boolean closed;
        ClosingInputStream(InputStream input, FileInputStream file, ParcelFileDescriptor descriptor) {
            super(input); this.file = file; this.descriptor = descriptor;
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException error = null;
            try { super.close(); } catch (IOException e) { error = e; }
            try { file.close(); } catch (IOException e) { if (error == null) error = e; }
            // FileInputStream owns the descriptor after it is created from FileDescriptor.
            // Close the ParcelFileDescriptor as well, but do not turn a harmless second close into a failed download.
            if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) {}
            if (error != null) throw error;
        }
    }
}

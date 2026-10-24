package mod.omnit.patcher;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Минимальный ZIP-ридер/писатель.
 *
 * Зачем свой: нужно копировать не изменённые записи APK как есть (без распаковки и
 * повторного сжатия сотен мегабайт) и выравнивать несжатые записи так же, как zipalign:
 * resources.arsc и прочее — по 4 байта, нативные .so — по границе страницы (16 КБ).
 * Без выравнивания APK под Android 11+ не установится.
 */
final class ZipIo {
    private ZipIo() {}

    static final int STORED = 0;
    static final int DEFLATED = 8;

    // 2000-01-01 00:00:00 в формате MS-DOS
    private static final int DOS_DATE = ((2000 - 1980) << 9) | (1 << 5) | 1;
    private static final int DOS_TIME = 0;

    static final class Entry {
        byte[] nameBytes;
        String name;
        int flags;
        int method;
        int time;
        int date;
        long crc;
        long csize;
        long usize;
        long lhOffset;

        Entry copy() {
            Entry e = new Entry();
            e.nameBytes = nameBytes;
            e.name = name;
            e.flags = flags;
            e.method = method;
            e.time = time;
            e.date = date;
            e.crc = crc;
            e.csize = csize;
            e.usize = usize;
            e.lhOffset = lhOffset;
            return e;
        }
    }

    // ---------------------------------------------------------------- little-endian helpers

    static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    static void p16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
    }

    static void p32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    // ---------------------------------------------------------------- reader

    static List<Entry> readDirectory(RandomAccessFile f) throws IOException {
        long len = f.length();
        if (len < 22) throw new IOException("Файл слишком мал для ZIP/APK");
        int max = (int) Math.min(len, 22L + 65535L);
        byte[] tail = new byte[max];
        f.seek(len - max);
        f.readFully(tail);

        int eocd = -1;
        for (int i = max - 22; i >= 0; i--) {
            if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) throw new IOException("Это не ZIP/APK (не найден конец каталога)");

        int total = u16(tail, eocd + 10);
        long cdSize = u32(tail, eocd + 12);
        long cdOff = u32(tail, eocd + 16);
        if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
            throw new IOException("ZIP64 не поддерживается");
        }

        byte[] cd = new byte[(int) cdSize];
        f.seek(cdOff);
        f.readFully(cd);

        List<Entry> list = new ArrayList<Entry>(total);
        int p = 0;
        for (int i = 0; i < total; i++) {
            if (u32(cd, p) != 0x02014b50L) throw new IOException("Повреждён центральный каталог ZIP");
            Entry e = new Entry();
            e.flags = u16(cd, p + 8);
            e.method = u16(cd, p + 10);
            e.time = u16(cd, p + 12);
            e.date = u16(cd, p + 14);
            e.crc = u32(cd, p + 16);
            e.csize = u32(cd, p + 20);
            e.usize = u32(cd, p + 24);
            int nl = u16(cd, p + 28);
            int el = u16(cd, p + 30);
            int cl = u16(cd, p + 32);
            e.lhOffset = u32(cd, p + 42);
            e.nameBytes = Arrays.copyOfRange(cd, p + 46, p + 46 + nl);
            e.name = new String(e.nameBytes, "UTF-8");
            list.add(e);
            p += 46 + nl + el + cl;
        }
        return list;
    }

    // ---------------------------------------------------------------- writer

    static final class Writer implements Closeable {
        private final OutputStream out;
        private long pos;
        private final List<Entry> done = new ArrayList<Entry>();
        private final byte[] buf = new byte[1 << 16];

        Writer(File f) throws IOException {
            out = new BufferedOutputStream(new FileOutputStream(f), 1 << 16);
        }

        private void raw(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            pos += len;
        }

        /** Пишет локальный заголовок; align > 1 — выровнять начало данных через extra-поле. */
        private void header(Entry e, int align) throws IOException {
            int nl = e.nameBytes.length;
            int pad = 0;
            if (align > 1) {
                pad = (int) ((align - ((pos + 30 + nl) % align)) % align);
                if (pad > 0 && pad < 4) pad += align; // extra-поле не может быть короче 4 байт
            }
            byte[] h = new byte[30 + nl + pad];
            p32(h, 0, 0x04034b50L);
            p16(h, 4, 20);
            p16(h, 6, e.flags & 0x0800); // только флаг UTF-8; data descriptor не используем
            p16(h, 8, e.method);
            p16(h, 10, e.time);
            p16(h, 12, e.date);
            p32(h, 14, e.crc);
            p32(h, 18, e.csize);
            p32(h, 22, e.usize);
            p16(h, 26, nl);
            p16(h, 28, pad);
            System.arraycopy(e.nameBytes, 0, h, 30, nl);
            if (pad >= 4) {
                p16(h, 30 + nl, 0xD935); // тот же id, что использует zipalign
                p16(h, 32 + nl, pad - 4);
            }
            e.lhOffset = pos;
            raw(h, 0, h.length);
        }

        /** Копирует запись из исходного файла без распаковки. */
        void copyRaw(RandomAccessFile src, Entry e, int align) throws IOException {
            byte[] lh = new byte[30];
            src.seek(e.lhOffset);
            src.readFully(lh);
            if (u32(lh, 0) != 0x04034b50L) throw new IOException("Повреждён локальный заголовок: " + e.name);
            long dataOff = e.lhOffset + 30 + u16(lh, 26) + u16(lh, 28);

            Entry o = e.copy();
            header(o, align);

            src.seek(dataOff);
            long left = e.csize;
            while (left > 0) {
                int n = src.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("Неожиданный конец файла: " + e.name);
                raw(buf, 0, n);
                left -= n;
            }
            done.add(o);
        }

        /** Добавляет запись из памяти. */
        void addBytes(byte[] nameBytes, byte[] data, boolean store) throws IOException {
            Entry e = new Entry();
            e.nameBytes = nameBytes;
            e.name = new String(nameBytes, "UTF-8");
            e.flags = 0x0800;
            e.time = DOS_TIME;
            e.date = DOS_DATE;
            CRC32 crc = new CRC32();
            crc.update(data, 0, data.length);
            e.crc = crc.getValue();
            e.usize = data.length;
            byte[] payload;
            if (store) {
                e.method = STORED;
                payload = data;
            } else {
                e.method = DEFLATED;
                payload = deflate(data);
            }
            e.csize = payload.length;
            header(e, store ? 4 : 1);
            raw(payload, 0, payload.length);
            done.add(e);
        }

        /** Пишет центральный каталог. Вызывать один раз в конце. */
        void finish() throws IOException {
            int n = done.size();
            if (n >= 0xFFFF) throw new IOException("Слишком много записей для ZIP без ZIP64");
            long cdStart = pos;
            for (int i = 0; i < n; i++) {
                Entry e = done.get(i);
                int nl = e.nameBytes.length;
                byte[] h = new byte[46 + nl];
                p32(h, 0, 0x02014b50L);
                p16(h, 4, 20);
                p16(h, 6, 20);
                p16(h, 8, e.flags & 0x0800);
                p16(h, 10, e.method);
                p16(h, 12, e.time);
                p16(h, 14, e.date);
                p32(h, 16, e.crc);
                p32(h, 20, e.csize);
                p32(h, 24, e.usize);
                p16(h, 28, nl);
                // extra, comment, disk, internal attrs, external attrs = 0
                p32(h, 42, e.lhOffset);
                System.arraycopy(e.nameBytes, 0, h, 46, nl);
                raw(h, 0, h.length);
            }
            long cdSize = pos - cdStart;
            byte[] eocd = new byte[22];
            p32(eocd, 0, 0x06054b50L);
            p16(eocd, 8, n);
            p16(eocd, 10, n);
            p32(eocd, 12, cdSize);
            p32(eocd, 16, cdStart);
            raw(eocd, 0, eocd.length);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(6, true);
        try {
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, data.length / 3));
            byte[] b = new byte[1 << 16];
            while (!d.finished()) {
                int n = d.deflate(b);
                bos.write(b, 0, n);
            }
            return bos.toByteArray();
        } finally {
            d.end();
        }
    }
}

package com.dreamstatic.mpcremote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A minimal tar reader/writer in GNU format: enough for streaming plugin folders to the MPC
 * and for turning the tar the MPC sends back (export) into a zip. Mirrors the parts of
 * Python's tarfile that mpc_remote.py uses.
 */
public final class Tar {

    private static final int BLOCK = 512;
    private static final int RECORD = 10240; // blocking factor 20, same as Python/GNU tar

    private Tar() {
    }

    /** One member of an archive. */
    public static final class Entry {
        public final String name;   // path inside the archive, '/'-separated
        public final boolean dir;
        public final int mode;      // permission bits
        public final long mtime;    // epoch seconds
        public final byte[] data;   // null for directories

        public Entry(String name, boolean dir, int mode, long mtime, byte[] data) {
            this.name = name;
            this.dir = dir;
            this.mode = mode;
            this.mtime = mtime;
            this.data = data;
        }
    }

    // ---------------------------------------------------------------- writing

    /** Build a complete GNU-format tar image (the shape Python's tarfile GNU_FORMAT produces). */
    public static byte[] create(List<Entry> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (Entry e : entries) {
            byte[] nameBytes = e.name.getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length > 100) {
                writeLongLink(bos, nameBytes);
            }
            byte[] nameField = Arrays.copyOf(nameBytes, Math.min(100, nameBytes.length));
            byte[] header = buildHeader(nameField, e.dir ? '5' : '0',
                    e.dir || e.data == null ? 0 : e.data.length, e.mode, e.mtime, "root");
            bos.write(header);
            if (!e.dir && e.data != null && e.data.length > 0) {
                bos.write(e.data);
                int pad = BLOCK - (e.data.length % BLOCK);
                if (pad != BLOCK) {
                    bos.write(new byte[pad]);
                }
            }
        }
        // end-of-archive: two zero blocks, padded out to a whole record
        bos.write(new byte[BLOCK * 2]);
        int total = bos.size();
        int rem = total % RECORD;
        if (rem != 0) {
            bos.write(new byte[RECORD - rem]);
        }
        return bos.toByteArray();
    }

    private static void writeLongLink(ByteArrayOutputStream bos, byte[] nameBytes) throws IOException {
        byte[] content = Arrays.copyOf(nameBytes, nameBytes.length + 1); // name + NUL
        byte[] header = buildHeader("././@LongLink".getBytes(StandardCharsets.US_ASCII), 'L',
                content.length, 0, 0, "");
        bos.write(header);
        bos.write(content);
        int pad = BLOCK - (content.length % BLOCK);
        if (pad != BLOCK) {
            bos.write(new byte[pad]);
        }
    }

    private static byte[] buildHeader(byte[] name100, char type, long size, int mode, long mtime, String uname)
            throws IOException {
        byte[] h = new byte[BLOCK];
        System.arraycopy(name100, 0, h, 0, Math.min(100, name100.length));
        writeOctal(h, 100, 8, mode);
        writeOctal(h, 108, 8, 0); // uid
        writeOctal(h, 116, 8, 0); // gid
        writeOctal(h, 124, 12, size);
        writeOctal(h, 136, 12, mtime);
        // checksum is computed with the checksum field itself as spaces
        Arrays.fill(h, 148, 156, (byte) ' ');
        h[156] = (byte) type;
        byte[] magic = "ustar ".getBytes(StandardCharsets.US_ASCII); // GNU magic
        System.arraycopy(magic, 0, h, 257, magic.length);
        h[263] = ' ';
        h[264] = 0;
        byte[] user = uname.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(user, 0, h, 265, Math.min(32, user.length));
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        // checksum: 6 octal digits, NUL, space (the layout Python's tarfile writes)
        String chk = Long.toOctalString(sum);
        if (chk.length() > 6) {
            chk = chk.substring(chk.length() - 6);
        }
        chk = "0".repeat(6 - chk.length()) + chk;
        for (int i = 0; i < 6; i++) {
            h[148 + i] = (byte) chk.charAt(i);
        }
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    /** n octal digits, NUL-terminated (Python's itn for GNU/ustar formats). */
    private static void writeOctal(byte[] h, int off, int width, long value) {
        String s = Long.toOctalString(value);
        int digits = width - 1;
        if (s.length() > digits) {
            s = s.substring(s.length() - digits); // keep the low bits, like tar does
        }
        String padded = "0".repeat(digits - s.length()) + s;
        for (int i = 0; i < digits; i++) {
            h[off + i] = (byte) padded.charAt(i);
        }
        h[off + digits] = 0;
    }

    // ---------------------------------------------------------------- reading

    /** Parse a tar image (GNU/ustar, long names, pax) into entries. */
    public static List<Entry> read(byte[] data) throws IOException {
        List<Entry> out = new ArrayList<>();
        int pos = 0;
        String pendingName = null;
        while (pos + BLOCK <= data.length) {
            byte[] h = Arrays.copyOfRange(data, pos, pos + BLOCK);
            pos += BLOCK;
            if (isZeroBlock(h)) {
                break; // end of archive
            }
            verifyChecksum(h);
            long size = parseNumeric(h, 124, 12);
            int type = h[156] & 0xff;
            byte[] content = null;
            if (size > 0) {
                if (pos + size > data.length) {
                    throw new IOException("truncated tar entry");
                }
                content = Arrays.copyOfRange(data, pos, pos + (int) size);
                pos += (int) ((size + BLOCK - 1) / BLOCK * BLOCK);
            }
            if (type == 'L') { // GNU long name for the following entry
                pendingName = trimNul(content);
                continue;
            }
            if (type == 'K') { // GNU long link name: not used for plugin folders
                continue;
            }
            if (type == 'x' || type == 'X') { // per-entry pax header
                String path = paxValue(content, "path");
                if (path != null) {
                    pendingName = path;
                }
                continue;
            }
            if (type == 'g') { // global pax header
                continue;
            }
            String name = pendingName != null ? pendingName : headerName(h);
            pendingName = null;
            int mode = (int) parseNumeric(h, 100, 8);
            long mtime = parseNumeric(h, 136, 12);
            if (type == '0' || type == 0) {
                out.add(new Entry(name, false, mode, mtime, content == null ? new byte[0] : content));
            } else if (type == '5') {
                out.add(new Entry(name, true, mode, mtime, null));
            }
            // links and anything else carry no payload we need
        }
        return out;
    }

    private static String trimNul(byte[] content) {
        if (content == null) {
            return null;
        }
        int end = content.length;
        while (end > 0 && content[end - 1] == 0) {
            end--;
        }
        return new String(content, 0, end, StandardCharsets.UTF_8);
    }

    private static String headerName(byte[] h) {
        String name = field(h, 0, 100);
        String prefix = field(h, 345, 155);
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String field(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) {
            end++;
        }
        return new String(h, off, end - off, StandardCharsets.UTF_8);
    }

    private static boolean isZeroBlock(byte[] h) {
        for (byte b : h) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static void verifyChecksum(byte[] h) throws IOException {
        long stored = parseNumeric(h, 148, 8);
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xff);
        }
        if (sum != stored) {
            throw new IOException("bad tar header checksum (expected " + stored + ", computed " + sum + ")");
        }
    }

    /** Octal field (or GNU base-256 for very large values), NUL/space terminated. */
    private static long parseNumeric(byte[] h, int off, int len) {
        if (len == 0) {
            return 0;
        }
        if ((h[off] & 0x80) != 0) {
            // base-256 (GNU extension for sizes beyond octal range)
            long value = (h[off] & 0x40) != 0 ? -1 : 0; // sign extension for negatives
            for (int i = off; i < off + len; i++) {
                value = (value << 8) | (h[i] & 0xff);
            }
            return value;
        }
        long value = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            byte b = h[i];
            if (b == 0 || b == ' ') {
                break;
            }
            if (b < '0' || b > '7') {
                break;
            }
            value = (value << 3) + (b - '0');
            any = true;
        }
        return any ? value : 0;
    }

    private static String paxValue(byte[] content, String key) {
        if (content == null) {
            return null;
        }
        int i = 0;
        while (i < content.length) {
            int sp = i;
            while (sp < content.length && content[sp] != ' ') {
                sp++;
            }
            if (sp >= content.length) {
                break;
            }
            int len;
            try {
                len = Integer.parseInt(new String(content, i, sp - i, StandardCharsets.US_ASCII).trim());
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || i + len > content.length) {
                break;
            }
            String record = new String(content, sp + 1, i + len - sp - 1 - 1, StandardCharsets.UTF_8);
            int eq = record.indexOf('=');
            if (eq > 0 && record.substring(0, eq).equals(key)) {
                return record.substring(eq + 1);
            }
            i += len;
        }
        return null;
    }
}

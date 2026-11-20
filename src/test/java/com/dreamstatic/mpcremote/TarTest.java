package com.dreamstatic.mpcremote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TarTest {

    @Test
    void roundTripsDirectoriesAndFiles() throws Exception {
        byte[] elf = new byte[64];
        elf[0] = 0x7f;
        elf[1] = 'E';
        elf[2] = 'L';
        elf[3] = 'F';
        List<Tar.Entry> entries = List.of(
                new Tar.Entry("Plug - VST - X", true, 0755, 1700000000, null),
                new Tar.Entry("Plug - VST - X/plugin.json", false, 0644, 1700000001,
                        "{\"a\":1}".getBytes(StandardCharsets.UTF_8)),
                new Tar.Entry("Plug - VST - X/VST", true, 0755, 1700000002, null),
                new Tar.Entry("Plug - VST - X/VST/x.so", false, 0755, 1700000003, elf));
        byte[] archive = Tar.create(entries);
        assertEquals(0, archive.length % 10240); // padded to a whole record, like Python/GNU tar
        List<Tar.Entry> back = Tar.read(archive);
        assertEquals(4, back.size());
        assertEquals("Plug - VST - X", back.get(0).name);
        assertTrue(back.get(0).dir);
        assertEquals(0755, back.get(0).mode);
        assertEquals(1700000000L, back.get(0).mtime);
        assertEquals("{\"a\":1}", new String(back.get(1).data, StandardCharsets.UTF_8));
        assertEquals(0644, back.get(1).mode);
        assertArrayEquals(elf, back.get(3).data);
        assertEquals(0755, back.get(3).mode);
    }

    @Test
    void longNamesGoThroughLongLink() throws Exception {
        String longName = "A Very Long Plugin Folder Name - VST - " + "x".repeat(120) + "/file.txt";
        assertTrue(longName.getBytes(StandardCharsets.UTF_8).length > 100);
        byte[] archive = Tar.create(List.of(
                new Tar.Entry(longName, false, 0644, 1000, "hi".getBytes(StandardCharsets.UTF_8))));
        // the first header is the GNU @LongLink marker
        String first = new String(archive, 0, 13, StandardCharsets.US_ASCII);
        assertEquals("././@LongLink", first);
        List<Tar.Entry> back = Tar.read(archive);
        assertEquals(1, back.size());
        assertEquals(longName, back.get(0).name);
        assertEquals("hi", new String(back.get(0).data, StandardCharsets.UTF_8));
    }

    @Test
    void headerLayoutMatchesGnuTar() throws Exception {
        byte[] archive = Tar.create(List.of(
                new Tar.Entry("f.txt", false, 0644, 1700000000, "x".getBytes(StandardCharsets.UTF_8))));
        // magic "ustar " + version " \0" (GNU), as written by Python's tarfile GNU_FORMAT
        byte[] magic = new byte[8];
        System.arraycopy(archive, 257, magic, 0, 8);
        assertArrayEquals(new byte[]{'u', 's', 't', 'a', 'r', ' ', ' ', 0}, magic);
        // checksum field: 6 octal digits, NUL, space
        assertEquals(0, archive[154]);
        assertEquals(' ', archive[155]);
        for (int i = 148; i < 154; i++) {
            assertTrue(archive[i] >= '0' && archive[i] <= '7', "octal checksum digit");
        }
        // uid/gid are root
        assertEquals('0', archive[108]);
        // owner name is root
        assertEquals('r', archive[265]);
    }

    @Test
    void readsUstarPrefixAndRejectsCorruption() throws Exception {
        // hand-built ustar header using the prefix field for a long path
        byte[] h = new byte[512];
        put(h, 0, "file.txt");
        put(h, 100, "0000644");
        put(h, 108, "0000000");
        put(h, 116, "0000000");
        put(h, 124, "00000000004");
        put(h, 136, "1700000000");
        java.util.Arrays.fill(h, 148, 156, (byte) ' ');
        h[156] = '0';
        put(h, 257, "ustar");
        h[263] = '0';
        h[264] = '0';
        put(h, 345, "some/where/deep");
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        String chk = String.format("%06o", sum);
        for (int i = 0; i < 6; i++) {
            h[148 + i] = (byte) chk.charAt(i);
        }
        h[154] = 0;
        h[155] = ' ';
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        byte[] archive = new byte[512 + 512];
        System.arraycopy(h, 0, archive, 0, 512);
        System.arraycopy(content, 0, archive, 512, content.length);
        List<Tar.Entry> back = Tar.read(archive);
        assertEquals(1, back.size());
        assertEquals("some/where/deep/file.txt", back.get(0).name);
        assertEquals(4, back.get(0).data.length);

        // a wrong checksum must be rejected ('9' is not an octal digit, so nothing parses)
        h[148] = (byte) '9';
        byte[] broken = archive.clone();
        System.arraycopy(h, 0, broken, 0, 512);
        assertThrows(java.io.IOException.class, () -> Tar.read(broken));
    }

    @Test
    void emptyArchiveIsTwoZeroBlocksPaddedToARecord() throws Exception {
        byte[] archive = Tar.create(List.of());
        assertEquals(10240, archive.length);
        for (int i = 0; i < 1024; i++) {
            assertEquals(0, archive[i]);
        }
        assertEquals(List.of(), Tar.read(archive));
    }

    private static void put(byte[] h, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, b.length);
    }
}

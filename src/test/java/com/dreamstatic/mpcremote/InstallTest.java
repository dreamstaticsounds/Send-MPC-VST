package com.dreamstatic.mpcremote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallTest {

    private static byte[] fakeElf(int machine) {
        byte[] b = new byte[64];
        b[0] = 0x7f;
        b[1] = 'E';
        b[2] = 'L';
        b[3] = 'F';
        b[4] = 1;
        b[5] = 1;
        b[16] = 3;
        b[18] = (byte) (machine & 0xff);
        b[19] = (byte) (machine >> 8);
        return b;
    }

    private static Path plugin(Path parent) throws Exception {
        Path dir = parent.resolve("Acme - VST - TestSynth");
        Files.createDirectories(dir.resolve("VST"));
        Files.createDirectories(dir.resolve("Plugin Skins"));
        Files.createDirectories(dir.resolve("__MACOSX"));
        Files.writeString(dir.resolve("plugin.json"),
                "{\"name\":\"TestSynth\",\"manufacturer\":\"Acme\",\"uid\":\"TSth\",\"file64\":\"VST/t.so\"}",
                StandardCharsets.UTF_8);
        Files.write(dir.resolve("VST").resolve("t.so"), fakeElf(0xB7));
        Files.writeString(dir.resolve("VST").resolve("data.txt"), "plain data");
        Files.writeString(dir.resolve("Plugin Skins").resolve("knob.png"), "png");
        Files.writeString(dir.resolve(".DS_Store"), "junk");
        Files.writeString(dir.resolve("._resource"), "junk");
        Files.writeString(dir.resolve("__MACOSX").resolve("junk"), "junk");
        Files.writeString(dir.resolve("VST").resolve("._nested"), "junk");
        return dir;
    }

    @Test
    void buildTarNormalisesModesAndSkipsJunk(@TempDir Path tmp) throws Exception {
        Path dir = plugin(tmp);
        Sources.LocalPlugin lp = new Sources.LocalPlugin(dir, Sources.readManifest(dir));
        Install.TarBuild tar = Install.buildTar(lp);
        assertEquals(0, tar.bytes().length % 10240);
        List<Tar.Entry> entries = Tar.read(tar.bytes());

        assertEquals(lp.folder, entries.get(0).name);
        assertTrue(entries.get(0).dir);
        assertEquals(0755, entries.get(0).mode);

        Map<String, Tar.Entry> byName = new HashMap<>();
        for (Tar.Entry e : entries) {
            byName.put(e.name, e);
        }
        String root = lp.folder;
        assertEquals(0755, byName.get(root + "/VST").mode);
        assertEquals(0755, byName.get(root + "/VST/t.so").mode, "ELF binaries are executable");
        assertEquals(0644, byName.get(root + "/VST/data.txt").mode, "everything else is not");
        assertEquals(0644, byName.get(root + "/plugin.json").mode);
        assertEquals("png", new String(byName.get(root + "/Plugin Skins/knob.png").data, StandardCharsets.UTF_8));

        // junk is left out entirely
        assertFalse(byName.containsKey(root + "/.DS_Store"));
        assertFalse(byName.containsKey(root + "/._resource"));
        assertFalse(byName.containsKey(root + "/__MACOSX"));
        assertFalse(byName.containsKey(root + "/VST/._nested"));

        // sizes are relative to the plugin folder, for the post-copy verification
        assertEquals(4, tar.sizes().size());
        assertEquals(Files.size(dir.resolve("VST").resolve("t.so")),
                tar.sizes().get("VST/t.so").longValue());
        assertEquals(Files.size(dir.resolve("plugin.json")),
                tar.sizes().get("plugin.json").longValue());
    }

    @Test
    void tarToZipConvertsAndFilters(@TempDir Path tmp) throws Exception {
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] tarBytes = Tar.create(List.of(
                new Tar.Entry("Plug - VST - X", true, 0755, 1700000000, null),
                new Tar.Entry("Plug - VST - X/.DS_Store", false, 0644, 1700000000, new byte[]{1}),
                new Tar.Entry("Plug - VST - X/../escape.txt", false, 0644, 1700000000, new byte[]{1}),
                new Tar.Entry("Plug - VST - X/t.so", false, 0755, 1700000001, data),
                new Tar.Entry("Plug - VST - X/readme.txt", false, 0644, 1700000002, data)));
        Path zip = tmp.resolve("out.zip");
        int files = Actions.tarToZip(tarBytes, zip);
        assertEquals(2, files, "junk and escaping names are skipped");

        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            assertNotNull(z.getEntry("Plug - VST - X/"));
            assertNotNull(z.getEntry("Plug - VST - X/t.so"));
            assertNotNull(z.getEntry("Plug - VST - X/readme.txt"));
            assertNullSafe(z.getEntry("Plug - VST - X/.DS_Store"));
            assertNullSafe(z.getEntry("Plug - VST - X/../escape.txt"));
            assertEquals("payload", new String(z.getInputStream(z.getEntry("Plug - VST - X/t.so"))
                    .readAllBytes(), StandardCharsets.UTF_8));
        }
        // the executable bits survive into the archive's central directory, like Python's zipfile
        Map<String, Integer> modes = readCenModes(zip);
        assertEquals(0100755, modes.get("Plug - VST - X/t.so") >>> 16);
        assertEquals(0100644, modes.get("Plug - VST - X/readme.txt") >>> 16);
        assertEquals(3, readCenHost(zip, "Plug - VST - X/t.so"), "version made by: Unix");
    }

    private static void assertNullSafe(java.util.zip.ZipEntry e) {
        assertEquals(null, e);
    }

    /** Minimal central-directory walk: external attributes per entry name. */
    private static Map<String, Integer> readCenModes(Path zip) throws IOException {
        byte[] data = Files.readAllBytes(zip);
        Map<String, Integer> out = new HashMap<>();
        int pos = 0;
        while (pos + 46 <= data.length) {
            int sig = le32(data, pos);
            if (sig != 0x02014b50) {
                pos++;
                continue;
            }
            int nameLen = le16(data, pos + 28);
            int extraLen = le16(data, pos + 30);
            int commentLen = le16(data, pos + 32);
            if (pos + 46 + nameLen > data.length) {
                break;
            }
            out.put(new String(data, pos + 46, nameLen, StandardCharsets.UTF_8), le32(data, pos + 38));
            pos += 46 + nameLen + extraLen + commentLen;
        }
        return out;
    }

    private static int readCenHost(Path zip, String wanted) throws IOException {
        byte[] data = Files.readAllBytes(zip);
        int pos = 0;
        while (pos + 46 <= data.length) {
            int sig = le32(data, pos);
            if (sig != 0x02014b50) {
                pos++;
                continue;
            }
            int nameLen = le16(data, pos + 28);
            int extraLen = le16(data, pos + 30);
            int commentLen = le16(data, pos + 32);
            String name = new String(data, pos + 46, nameLen, StandardCharsets.UTF_8);
            if (name.equals(wanted)) {
                return data[pos + 5] & 0xff; // high byte of "version made by"
            }
            pos += 46 + nameLen + extraLen + commentLen;
        }
        return -1;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    @Test
    void applyUnixModesPatchesOnlyTheRequestedEntries(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("plain.zip");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new java.util.zip.ZipEntry("a.txt"));
            z.write("a".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new java.util.zip.ZipEntry("b.txt"));
            z.write("b".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Map<String, Integer> before = readCenModes(zip);
        Map<String, Integer> modes = new HashMap<>();
        modes.put("a.txt", 0100755);
        Util.applyUnixModes(zip, modes);
        Map<String, Integer> after = readCenModes(zip);
        assertEquals(0100755, after.get("a.txt") >>> 16);
        assertEquals(before.get("b.txt"), after.get("b.txt"), "untouched entries stay untouched");
    }

    @Test
    void installRefusesBadInputWithoutTouchingTheNetwork(@TempDir Path tmp) throws Exception {
        Path dir = plugin(tmp);
        Sources.LocalPlugin lp = new Sources.LocalPlugin(dir, Sources.readManifest(dir));
        // an arm32 device: this plugin only has an arm64 build, so install stops at the arch check
        Context ctx = new Context(new Remote("127.0.0.1", "root", 22, null),
                Map.of("arch", "armv7l"), new Drive("/dev/sda1", "/media/usb", "vfat", "rw"), true, tmp);
        assertFalse(Install.install(ctx, lp, false));
        // a folder name that starts with "-" (the MPC would mangle it) is refused up front
        Sources.LocalPlugin evil = new Sources.LocalPlugin(dir, Map.of(
                "name", "y", "manufacturer", "-x", "uid", "TSth"));
        assertFalse(Install.install(ctx, evil, false));
    }
}

package com.dreamstatic.mpcremote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyConvertTest {

    /** A minimal but structurally valid ARM ELF binary header. */
    private static byte[] fakeElf(int machine) {
        byte[] b = new byte[64];
        b[0] = 0x7f;
        b[1] = 'E';
        b[2] = 'L';
        b[3] = 'F';
        b[4] = 1; // 32-bit
        b[5] = 1; // little-endian
        b[16] = 3; // ET_DYN
        b[18] = (byte) (machine & 0xff);
        b[19] = (byte) (machine >> 8);
        return b;
    }

    /** Build one community package folder: plugin-meta.xml plus its binary. */
    private static Path legacyPackage(Path root, String folder, String mfr, String name, String uid,
                                      int machine, String bin) throws Exception {
        Path dir = root.resolve(folder);
        Files.createDirectories(dir);
        String meta = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<PLUGIN name=\"" + name + "\" manufacturer=\"" + mfr + "\" uid=\"" + uid + "\"\n"
                + "        isInstrument=\"1\" version=\"2.1.0\" category=\"Synth\" numInputs=\"2\" numOutputs=\"2\"\n"
                + "        file=\"%payload-path%/" + folder + "/" + bin + "\"/>\n";
        Files.writeString(dir.resolve("plugin-meta.xml"), meta, StandardCharsets.UTF_8);
        Files.write(dir.resolve(bin), fakeElf(machine));
        return dir;
    }

    // ---------------------------------------------------------------- small helpers

    @Test
    void convertsUidsLikePython() {
        assertEquals("AcId", LegacyConvert.convertUid("41634964"));
        assertEquals("AcId", LegacyConvert.convertUid(" 41634964 "));
        assertEquals("0x000004D2", LegacyConvert.convertUid("1234")); // not printable as a 4-char code
        assertThrows(LegacyConvert.ConvertError.class, () -> LegacyConvert.convertUid("not-an-uid"));
        assertThrows(LegacyConvert.ConvertError.class, () -> LegacyConvert.convertUid(null));
    }

    @Test
    void padsVersions() {
        assertEquals("2.1.0.0", LegacyConvert.padVersion("2.1.0"));
        assertEquals("1.2.3.4", LegacyConvert.padVersion("1.2.3.4.5"));
        assertEquals("1.0.0.0", LegacyConvert.padVersion("abc"));
        assertEquals("10.20.0.0", LegacyConvert.padVersion("10.20"));
    }

    @Test
    void slugsNamesLikePython() {
        assertEquals("mpc-plaits", LegacyConvert.makeSlug("MPC Plaits"));
        assertEquals("sysex-bandit-", LegacyConvert.makeSlug("Sysex Bandit!"));
        assertEquals("abc-123", legacySlugLower("ABC 123"));
    }

    private static String legacySlugLower(String s) {
        return LegacyConvert.makeSlug(s);
    }

    @Test
    void readsElfArchitecture(@TempDir Path tmp) throws Exception {
        Path arm64 = tmp.resolve("a.so");
        Files.write(arm64, fakeElf(0xB7));
        Path arm32 = tmp.resolve("b.so");
        Files.write(arm32, fakeElf(0x28));
        Path x86 = tmp.resolve("c.so");
        Files.write(x86, fakeElf(0x3E));
        Path text = tmp.resolve("d.so");
        Files.writeString(text, "not an elf");
        assertEquals("arm64", LegacyConvert.elfArch(arm64));
        assertEquals("arm32", LegacyConvert.elfArch(arm32));
        assertThrows(LegacyConvert.ConvertError.class, () -> LegacyConvert.elfArch(x86));
        assertThrows(LegacyConvert.ConvertError.class, () -> LegacyConvert.elfArch(text));
    }

    // ---------------------------------------------------------------- reading legacy folders

    @Test
    void readsPluginMeta(@TempDir Path tmp) throws Exception {
        Path pkg = legacyPackage(tmp, "Acme - VST - TestSynth", "Acme", "TestSynth", "41634964",
                0xB7, "testsynth.so");
        Map<String, Object> m = LegacyConvert.readLegacy(pkg);
        assertEquals("TestSynth", m.get("name"));
        assertEquals("Acme", m.get("manufacturer"));
        assertEquals("AcId", m.get("uid"));
        assertEquals("instrument", m.get("kind"));
        assertEquals("2.1.0", m.get("version"));
        assertEquals("Synth", m.get("category"));
        assertEquals(2, m.get("numInputs"));
        assertEquals(2, m.get("numOutputs"));
        assertEquals("testsynth.so", m.get("rel"));
        assertEquals("arm64", m.get("arch"));
    }

    @Test
    void shellPluginsAreRefused(@TempDir Path tmp) throws Exception {
        Path pkg = legacyPackage(tmp, "Acme - VST - Shell", "Acme", "Shell", "41634964", 0xB7, "shell.so");
        Files.writeString(pkg.resolve("plugin-meta.xml"),
                "<PLUGIN name=\"Shell\" manufacturer=\"Acme\" uid=\"41634964\" isShell=\"1\" "
                        + "file=\"x/shell.so\"/>", StandardCharsets.UTF_8);
        LegacyConvert.ConvertError e = assertThrows(LegacyConvert.ConvertError.class,
                () -> LegacyConvert.readLegacy(pkg));
        assertTrue(e.getMessage().contains("shell plugin"), e.getMessage());
    }

    @Test
    void missingBinaryIsRefused(@TempDir Path tmp) throws Exception {
        Path pkg = legacyPackage(tmp, "Acme - VST - Gone", "Acme", "Gone", "41634964", 0xB7, "gone.so");
        Files.delete(pkg.resolve("gone.so"));
        LegacyConvert.ConvertError e = assertThrows(LegacyConvert.ConvertError.class,
                () -> LegacyConvert.readLegacy(pkg));
        assertTrue(e.getMessage().contains("is missing"), e.getMessage());
    }

    @Test
    void missingMetaIsRefused(@TempDir Path tmp) throws Exception {
        Path plain = Files.createDirectory(tmp.resolve("plain"));
        LegacyConvert.ConvertError e = assertThrows(LegacyConvert.ConvertError.class,
                () -> LegacyConvert.readLegacy(plain));
        assertTrue(e.getMessage().contains("cannot read plugin-meta.xml"), e.getMessage());
    }

    // ---------------------------------------------------------------- full conversion

    @Test
    void convertsASinglePackage(@TempDir Path tmp) throws Exception {
        legacyPackage(tmp, "Acme - VST - TestSynth", "Acme", "TestSynth", "41634964", 0xB7, "testsynth.so");
        Path out = tmp.resolve("converted");
        List<LegacyConvert.Result> results = LegacyConvert.convert(
                List.of(tmp.toString()), out.toString(), false, false);
        assertEquals(1, results.size());
        Path dest = out.resolve("Acme - VST - TestSynth");
        assertEquals(dest.toString(), results.get(0).folder);
        assertTrue(Files.isRegularFile(dest.resolve("plugin.json")));
        assertTrue(Files.isRegularFile(dest.resolve("version.xml")));
        assertTrue(Files.isRegularFile(dest.resolve("testsynth.so")));
        // the installer's own files must not be copied, but everything else is
        assertTrue(Files.isRegularFile(pkgFile(tmp, "Acme - VST - TestSynth", "plugin-meta.xml")));

        Map<String, Object> manifest = Json.parseObject(Files.readString(dest.resolve("plugin.json")));
        assertEquals("Acme - VST - TestSynth", dest.getFileName().toString());
        assertEquals("TestSynth", manifest.get("name"));
        assertEquals("AcId", manifest.get("uid"));
        assertEquals("testsynth.so", manifest.get("file64"));
        assertFalse(manifest.containsKey("file32"));
        assertEquals("community mpc-vst-plugins package", manifest.get("convertedFrom"));
        assertEquals("vst2", manifest.get("format"));
        // key order follows the fixed list, then convertedFrom
        String json = Files.readString(dest.resolve("plugin.json"));
        assertTrue(json.indexOf("\"name\"") < json.indexOf("\"manufacturer\""));
        assertTrue(json.indexOf("\"uid\"") < json.indexOf("\"file64\""));
        assertTrue(json.endsWith("}\n"));
        // version.xml uses the padded version
        assertTrue(Files.readString(dest.resolve("version.xml")).contains("<version>2.1.0.0</version>"));
        // the warnings name the missing architecture
        assertTrue(results.get(0).warnings.stream().anyMatch(w -> w.contains("no 32-bit build")));
    }

    private static Path pkgFile(Path tmp, String folder, String name) {
        return tmp.resolve(folder).resolve(name);
    }

    @Test
    void mergesBothArchitecturesIntoOneFolder(@TempDir Path tmp) throws Exception {
        Path armv7 = Files.createDirectory(tmp.resolve("armv7"));
        legacyPackage(armv7, "Acme - VST - Both", "Acme", "Both", "41634964", 0x28, "both.so");
        Path a64 = Files.createDirectory(tmp.resolve("aarch64"));
        legacyPackage(a64, "Acme - VST - Both", "Acme", "Both", "41634964", 0xB7, "both.so");
        Path out = tmp.resolve("converted");
        List<LegacyConvert.Result> results = LegacyConvert.convert(
                List.of(armv7.toString(), a64.toString()), out.toString(), false, false);
        assertEquals(1, results.size(), "the two packages must merge into one plugin");
        Map<String, Object> manifest = results.get(0).manifest;
        assertEquals("both.so", manifest.get("file32"));
        assertEquals("both-arm64.so", manifest.get("file64"),
                "the second architecture must be renamed, not overwrite the first");
        assertEquals(0, results.get(0).warnings.size(), "a complete plugin has no arch warnings: "
                + results.get(0).warnings);
        assertTrue(Files.isRegularFile(out.resolve("Acme - VST - Both").resolve("both.so")));
        assertTrue(Files.isRegularFile(out.resolve("Acme - VST - Both").resolve("both-arm64.so")));
    }

    @Test
    void refusesMismatchedPackages(@TempDir Path tmp) throws Exception {
        Path armv7 = Files.createDirectory(tmp.resolve("armv7"));
        legacyPackage(armv7, "Acme - VST - Odd", "Acme", "Odd", "41634964", 0x28, "odd.so");
        Path a64 = Files.createDirectory(tmp.resolve("aarch64"));
        legacyPackage(a64, "Acme - VST - Odd", "Acme", "Odd", "DEADBEEF", 0xB7, "odd.so");
        LegacyConvert.ConvertError e = assertThrows(LegacyConvert.ConvertError.class,
                () -> LegacyConvert.convert(List.of(armv7.toString(), a64.toString()),
                        tmp.resolve("out").toString(), false, false));
        assertTrue(e.getMessage().contains("uid differs"), e.getMessage());
    }

    @Test
    void existingOutputNeedsForce(@TempDir Path tmp) throws Exception {
        legacyPackage(tmp, "Acme - VST - Once", "Acme", "Once", "41634964", 0xB7, "once.so");
        Path out = Files.createDirectory(tmp.resolve("converted"));
        Files.createDirectory(out.resolve("Acme - VST - Once"));
        LegacyConvert.ConvertError e = assertThrows(LegacyConvert.ConvertError.class,
                () -> LegacyConvert.convert(List.of(tmp.toString()), out.toString(), false, false));
        assertTrue(e.getMessage().contains("already exists; use --force"), e.getMessage());
        // with --force it converts
        List<LegacyConvert.Result> results = LegacyConvert.convert(
                List.of(tmp.toString()), out.toString(), false, true);
        assertEquals(1, results.size());
    }

    @Test
    void nonPackageIsRefused(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("readme.txt"), "hello");
        LegacyConvert.ConvertError e = assertThrows(LegacyConvert.ConvertError.class,
                () -> LegacyConvert.convert(List.of(tmp.resolve("readme.txt").toString()),
                        tmp.resolve("out").toString(), false, false));
        assertTrue(e.getMessage().contains("not a valid zip file"), e.getMessage());
        Path plainDir = Files.createDirectory(tmp.resolve("plain"));
        LegacyConvert.ConvertError e2 = assertThrows(LegacyConvert.ConvertError.class,
                () -> LegacyConvert.convert(List.of(plainDir.toString()),
                        tmp.resolve("out").toString(), false, false));
        assertTrue(e2.getMessage().contains("has no plugin-meta.xml"), e2.getMessage());
    }

    @Test
    void makesAZipWithInstallText(@TempDir Path tmp) throws Exception {
        legacyPackage(tmp, "Acme - VST - Zippy", "Acme", "Zippy", "41634964", 0xB7, "zippy.so");
        Path out = tmp.resolve("converted");
        List<LegacyConvert.Result> results = LegacyConvert.convert(
                List.of(tmp.toString()), out.toString(), true, false);
        Path zip = out.resolve("zippy-2.1.0-mpc.zip");
        assertEquals(zip.toString(), results.get(0).zip);
        assertTrue(Files.isRegularFile(zip));
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            assertTrue(z.getEntry("INSTALL.txt") != null, "INSTALL.txt must be in the zip");
            String install = new String(z.getInputStream(z.getEntry("INSTALL.txt")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(install.startsWith("Zippy 2.1.0 - plugin for Akai MPC standalone\n"));
            assertTrue(install.contains("by Acme"));
            assertTrue(install.contains("Acme - VST - Zippy/plugin.json"));
            assertTrue(install.endsWith("\n"));
            assertTrue(z.getEntry("Acme - VST - Zippy/plugin.json") != null);
            assertTrue(z.getEntry("Acme - VST - Zippy/zippy.so") != null);
        }
    }
}

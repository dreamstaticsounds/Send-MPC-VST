package com.dreamstatic.mpcremote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcesTest {

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

    /** A complete, installable plugin folder. */
    private static Path pluginFolder(Path parent, String folder) throws Exception {
        Path dir = parent.resolve(folder);
        Files.createDirectories(dir.resolve("VST"));
        Files.writeString(dir.resolve("plugin.json"), "{\n"
                + "  \"name\": \"TestSynth\",\n"
                + "  \"manufacturer\": \"Acme\",\n"
                + "  \"version\": \"1.2.3\",\n"
                + "  \"uid\": \"TSth\",\n"
                + "  \"kind\": \"instrument\",\n"
                + "  \"file64\": \"VST/testsynth.so\"\n"
                + "}\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("version.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<plugincontent version=\"1.0\">\n"
                        + "    <version>1.2.3.0</version>\n</plugincontent>\n", StandardCharsets.UTF_8);
        Files.write(dir.resolve("VST").resolve("testsynth.so"), fakeElf(0xB7));
        return dir;
    }

    // ---------------------------------------------------------------- manifests

    @Test
    void readsManifests(@TempDir Path tmp) throws Exception {
        Path dir = pluginFolder(tmp, "Acme - VST - TestSynth");
        Map<String, Object> m = Sources.readManifest(dir);
        assertNotNull(m);
        assertEquals("TestSynth", m.get("name"));
        // missing, invalid and non-object manifests all read as null
        assertNull(Sources.readManifest(tmp.resolve("nope")));
        Files.writeString(tmp.resolve("plugin.json"), "{oops");
        assertNull(Sources.readManifest(tmp));
        Files.writeString(tmp.resolve("plugin.json"), "[1,2]");
        assertNull(Sources.readManifest(tmp));
    }

    // ---------------------------------------------------------------- scanning

    @Test
    void findsTheBuiltFolderOfAnSdkProject(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("MySynth"));
        Files.writeString(project.resolve("plugin.json"), "{\"name\":\"X\",\"manufacturer\":\"Y\"}");
        Files.createDirectories(project.resolve("src"));
        Path built = pluginFolder(project.resolve("build"), "Acme - VST - X");
        List<Path> found = Sources.scanForPlugins(project);
        assertEquals(1, found.size());
        assertEquals(built, found.get(0));
    }

    @Test
    void anSdkProjectWithoutABuildFallsBackToItself(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("MySynth"));
        Files.writeString(project.resolve("plugin.json"), "{\"name\":\"X\",\"manufacturer\":\"Y\"}");
        Files.createDirectories(project.resolve("src"));
        List<Path> found = Sources.scanForPlugins(project);
        assertEquals(List.of(project.toAbsolutePath().normalize()), found);
    }

    @Test
    void findsPluginFoldersTwoLevelsDeep(@TempDir Path tmp) throws Exception {
        // a manifest two levels below the search root is found
        Path nested = pluginFolder(tmp.resolve("a"), "Acme - VST - Deep");
        assertEquals(List.of(nested), Sources.scanForPlugins(tmp));
        // three levels down is beyond the search depth
        Path root2 = Files.createDirectory(tmp.resolve("r2"));
        pluginFolder(root2.resolve("x").resolve("y"), "Acme - VST - Deeper");
        assertEquals(List.of(), Sources.scanForPlugins(root2));
    }

    @Test
    void builtFoldersNeedAVstDirectory(@TempDir Path tmp) throws Exception {
        Path ok = pluginFolder(tmp, "Acme - VST - Ok");
        Path noVst = tmp.resolve("Acme - VST - NoVst");
        Files.createDirectories(noVst);
        Files.writeString(noVst.resolve("plugin.json"), "{\"name\":\"A\",\"manufacturer\":\"B\"}");
        assertEquals(List.of(ok), Sources.builtFolders(tmp));
        assertEquals(List.of(), Sources.builtFolders(tmp.resolve("missing")));
    }

    // ---------------------------------------------------------------- zips

    @Test
    void readsTheManifestOutOfAZip(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("plug.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("Acme - VST - X/plugin.json"));
            z.write("{\"name\":\"X\",\"manufacturer\":\"Y\"}".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Map<String, Object> m = Sources.pluginZipManifest(zip);
        assertNotNull(m);
        assertEquals("X", m.get("name"));
        // a zip with no manifest at the top or one level down is not a plugin zip
        Path other = tmp.resolve("other.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(other))) {
            z.putNextEntry(new ZipEntry("deep/a/b/plugin.json"));
            z.write("{}".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        assertNull(Sources.pluginZipManifest(other));
        // __MACOSX entries are ignored
        Path junk = tmp.resolve("junk.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(junk))) {
            z.putNextEntry(new ZipEntry("__MACOSX/plugin.json"));
            z.write("{".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        assertNull(Sources.pluginZipManifest(junk));
        assertNull(Sources.pluginZipManifest(tmp.resolve("no-such.zip")));
    }

    @Test
    void listsZipsSorted(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("b.zip"));
        Files.createFile(tmp.resolve("a.zip"));
        Files.createFile(tmp.resolve("c.txt"));
        List<Path> zips = Sources.zipsIn(tmp);
        assertEquals(2, zips.size());
        assertEquals("a.zip", zips.get(0).getFileName().toString());
        assertEquals("b.zip", zips.get(1).getFileName().toString());
    }

    @Test
    void extractsSafelyAndRefusesZipSlip(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("ok.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("dir/file.txt"));
            z.write("hello".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Path dest = Files.createDirectory(tmp.resolve("out"));
        Sources.extractZip(zip, dest);
        assertEquals("hello", Files.readString(dest.resolve("dir").resolve("file.txt")));

        Path evil = tmp.resolve("evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(evil))) {
            z.putNextEntry(new ZipEntry("../escaped.txt"));
            z.write("boom".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Path dest2 = Files.createDirectory(tmp.resolve("out2"));
        UsageError e = assertThrows(UsageError.class, () -> Sources.extractZip(evil, dest2));
        assertTrue(e.getMessage().contains("unsafe path"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("escaped.txt")));
    }

    // ---------------------------------------------------------------- checking plugins

    @Test
    void checkAcceptsAWellFormedPlugin(@TempDir Path tmp) throws Exception {
        Path dir = pluginFolder(tmp, "Acme - VST - TestSynth");
        Sources.LocalPlugin lp = new Sources.LocalPlugin(dir, Sources.readManifest(dir));
        lp.check();
        assertEquals(List.of(), lp.errors);
        assertEquals(List.of(), lp.warnings);
        assertTrue(lp.builds.containsKey("arm64"));
        assertFalse(lp.builds.containsKey("arm32"));
        assertEquals("VST/testsynth.so", lp.builds.get("arm64"));
        assertEquals("Acme - VST - TestSynth", lp.folder);
    }

    @Test
    void checkReportsMissingAndWrongBinaries(@TempDir Path tmp) throws Exception {
        Path dir = pluginFolder(tmp, "Acme - VST - TestSynth");
        // an x86 binary instead of ARM
        Files.write(dir.resolve("VST").resolve("testsynth.so"), fakeElf(0x3E));
        Sources.LocalPlugin lp = new Sources.LocalPlugin(dir, Sources.readManifest(dir));
        lp.check();
        assertTrue(lp.errors.stream().anyMatch(e -> e.contains("is not a arm64 binary")), lp.errors.toString());
        // a binary that is not listed at all
        Files.writeString(dir.resolve("plugin.json"),
                "{\"name\":\"T\",\"manufacturer\":\"A\",\"uid\":\"TSth\"}", StandardCharsets.UTF_8);
        Sources.LocalPlugin lp2 = new Sources.LocalPlugin(dir, Sources.readManifest(dir));
        lp2.check();
        assertTrue(lp2.errors.stream().anyMatch(e -> e.contains("no ARM binary listed")), lp2.errors.toString());
        // a listed file that does not exist
        Files.writeString(dir.resolve("plugin.json"),
                "{\"name\":\"T\",\"manufacturer\":\"A\",\"uid\":\"TSth\",\"file64\":\"VST/missing.so\"}",
                StandardCharsets.UTF_8);
        Sources.LocalPlugin lp3 = new Sources.LocalPlugin(dir, Sources.readManifest(dir));
        lp3.check();
        assertTrue(lp3.errors.stream().anyMatch(e -> e.contains("but missing")), lp3.errors.toString());
    }

    @Test
    void checkWarnsAboutMissingVersionXmlAndFolderName() throws Exception {
        // deliberately outside the temp dir: the rename warning is suppressed for zip-unpacked
        // folders (that is what the path.startswith(tempdir) check in the original is for)
        Path parent = Path.of("target", "mpc-remote-test-folders");
        Files.createDirectories(parent);
        Path dir = pluginFolder(parent, "SomeOtherName");
        try {
            Files.delete(dir.resolve("version.xml"));
            Sources.LocalPlugin lp = new Sources.LocalPlugin(dir, Sources.readManifest(dir));
            lp.check();
            assertTrue(lp.warnings.contains("no version.xml"), lp.warnings.toString());
            assertTrue(lp.warnings.stream().anyMatch(w -> w.contains("folder will be installed as")),
                    lp.warnings.toString());
            assertEquals("Acme - VST - TestSynth", lp.folder);
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void checkSuppressesTheRenameWarningUnderTheTempDir(@TempDir Path tmp) throws Exception {
        Path dir = pluginFolder(tmp, "SomeOtherName");
        Sources.LocalPlugin lp = new Sources.LocalPlugin(dir, Sources.readManifest(dir));
        lp.check();
        assertEquals(List.of(), lp.warnings, "zip-unpacked folders are expected as-is");
    }

    @Test
    void checkRejectsMissingManifestFields(@TempDir Path tmp) throws Exception {
        Path dir = pluginFolder(tmp, "Acme - VST - TestSynth");
        Files.writeString(dir.resolve("plugin.json"),
                "{\"file64\":\"VST/testsynth.so\"}", StandardCharsets.UTF_8);
        Sources.LocalPlugin lp = new Sources.LocalPlugin(dir, Sources.readManifest(dir));
        lp.check();
        assertTrue(lp.errors.stream().anyMatch(e -> e.contains("\"name\"")), lp.errors.toString());
        assertTrue(lp.errors.stream().anyMatch(e -> e.contains("\"manufacturer\"")), lp.errors.toString());
        assertTrue(lp.errors.stream().anyMatch(e -> e.contains("\"uid\"")), lp.errors.toString());
    }

    // ---------------------------------------------------------------- loading sources

    @Test
    void loadsAFolderAndAZip(@TempDir Path tmp) throws Exception {
        Path dir = pluginFolder(tmp, "Acme - VST - TestSynth");
        List<Sources.LocalPlugin> fromFolder = Sources.loadSources(dir.toString(), tmp);
        assertEquals(1, fromFolder.size());
        assertEquals("TestSynth", fromFolder.get(0).manifest.get("name"));

        Path zip = tmp.resolve("plug.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            byte[] json = Files.readAllBytes(dir.resolve("plugin.json"));
            z.putNextEntry(new ZipEntry("Acme - VST - TestSynth/plugin.json"));
            z.write(json);
            z.closeEntry();
            z.putNextEntry(new ZipEntry("Acme - VST - TestSynth/version.xml"));
            z.write(Files.readAllBytes(dir.resolve("version.xml")));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("Acme - VST - TestSynth/VST/testsynth.so"));
            z.write(Files.readAllBytes(dir.resolve("VST").resolve("testsynth.so")));
            z.closeEntry();
        }
        List<Sources.LocalPlugin> fromZip = Sources.loadSources(zip.toString(), tmp);
        assertEquals(1, fromZip.size());
        assertTrue(fromZip.get(0).builds.containsKey("arm64"));
    }

    @Test
    void loadsNothingGivesAHelpfulError(@TempDir Path tmp) throws Exception {
        Path empty = Files.createDirectory(tmp.resolve("empty"));
        UsageError e = assertThrows(UsageError.class,
                () -> Sources.loadSources(empty.toString(), tmp));
        assertTrue(e.getMessage().contains("No plugin found"), e.getMessage());
        // an unbuilt SDK project is found but has no ARM binaries, so it comes back with errors
        Path project = Files.createDirectory(tmp.resolve("Project"));
        Files.writeString(project.resolve("plugin.json"),
                "{\"name\":\"X\",\"manufacturer\":\"Y\",\"uid\":\"Xyzz\"}");
        Files.createDirectories(project.resolve("src"));
        List<Sources.LocalPlugin> unbuilt = Sources.loadSources(project.toString(), tmp);
        assertEquals(1, unbuilt.size());
        assertTrue(unbuilt.get(0).errors.stream().anyMatch(er -> er.contains("no ARM binary listed")),
                unbuilt.get(0).errors.toString());

        UsageError e3 = assertThrows(UsageError.class,
                () -> Sources.loadSources(tmp.resolve("no-such.zip").toString(), tmp));
        assertTrue(e3.getMessage().contains("does not exist"), e3.getMessage());

        Files.writeString(tmp.resolve("plain.txt"), "not a zip");
        UsageError e4 = assertThrows(UsageError.class,
                () -> Sources.loadSources(tmp.resolve("plain.txt").toString(), tmp));
        assertTrue(e4.getMessage().contains("is not a zip file"), e4.getMessage());
    }

    @Test
    void parsesTypedPaths() throws Exception {
        Path file = tmpFile();
        List<String> one = Sources.parsePaths(file.toString());
        assertEquals(1, one.size());
        assertEquals(file.toString(), one.get(0));
        List<String> quoted = Sources.parsePaths("\"" + file + "\"");
        assertEquals(List.of(file.toString()), quoted);
        List<String> several = Sources.parsePaths("a.zip 'b c.zip'");
        assertEquals(List.of("a.zip", "b c.zip"), several);
    }

    private static Path tmpFile() throws Exception {
        return Files.createTempFile("mpc-remote-test", ".zip");
    }

    @Test
    void findsCandidatesNearTheCurrentFolder(@TempDir Path tmp) throws Exception {
        // a built plugin under plugins/<project>/build
        Path build = tmp.resolve("plugins").resolve("MySynth").resolve("build");
        Path built = pluginFolder(build, "Acme - VST - X");
        // a plugin zip in dist/
        Path dist = tmp.resolve("plugins").resolve("MySynth").resolve("dist");
        Files.createDirectories(dist);
        Path zip = dist.resolve("x-1.0-mpc.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("Acme - VST - X/plugin.json"));
            z.write("{\"name\":\"X\",\"manufacturer\":\"Y\",\"version\":\"1.0\"}".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        List<Sources.Candidate> cands = Sources.findCandidates(tmp);
        assertEquals(2, cands.size());
        // newest first: touch the zip so it sorts ahead
        zip.toFile().setLastModified(System.currentTimeMillis() + 10_000);
        cands = Sources.findCandidates(tmp);
        assertEquals("zip", cands.get(0).kind);
        assertEquals("folder", cands.get(1).kind);
        assertEquals("X 1.0", cands.get(0).label());
        assertNotNull(cands.get(0).glyph());
        assertNotNull(cands.get(0).detail(tmp));
        assertEquals(0, Sources.findCandidates(tmp.resolve("empty")).size());
        assertNotNull(built);
    }
}

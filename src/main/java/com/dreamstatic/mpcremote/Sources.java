package com.dreamstatic.mpcremote;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Local plugin sources: SDK build folders and plugin zips on this computer, plus the checks
 * that decide whether one can be installed (plugin.json fields, ELF/ARM binaries).
 */
public final class Sources {

    private Sources() {
    }

    // ---------------------------------------------------------------- manifests

    /** plugin.json in a folder, or null when it is missing/invalid/not an object. */
    public static Map<String, Object> readManifest(Path folder) {
        Path file = folder.resolve("plugin.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Json.parseObject(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Child folders of {@code directory} that are complete plugin folders (plugin.json plus a VST/ folder). */
    public static List<Path> builtFolders(Path directory) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return out;
        }
        List<String> names = listSortedNames(directory);
        for (String n : names) {
            Path child = directory.resolve(n);
            if (Files.isDirectory(child.resolve("VST")) && readManifest(child) != null) {
                out.add(child);
            }
        }
        return out;
    }

    /**
     * Find plugin folders in an unzipped tree or a project directory:
     * an SDK project keeps its deployable folder under build/, everything else is searched
     * two levels deep (manifests found at the top level of the search are used as-is).
     */
    public static List<Path> scanForPlugins(Path root) {
        Path abs = root.toAbsolutePath().normalize();
        if (readManifest(abs) != null) {
            // An SDK project also has a plugin.json at its root; the deployable folder is under build/.
            List<Path> built = Files.isDirectory(abs.resolve("VST"))
                    ? List.of()
                    : builtFolders(abs.resolve("build"));
            return built.isEmpty() ? List.of(abs) : built;
        }
        List<Path> found = new ArrayList<>();
        Path build = abs.resolve("build");
        List<Path> bases = new ArrayList<>();
        if (Files.isDirectory(build)) {
            bases.add(build);
        }
        bases.add(abs);
        for (Path base : bases) {
            scanLevel(base, base, 0, found);
            if (!found.isEmpty()) {
                break;
            }
        }
        return found;
    }

    private static void scanLevel(Path base, Path dir, int depth, List<Path> found) {
        if (!dir.equals(base) && readManifest(dir) != null) {
            found.add(dir);
            return; // do not descend into a plugin folder
        }
        if (depth >= 2) {
            return;
        }
        for (Path child : sortedChildDirs(dir)) {
            String name = child.getFileName().toString();
            if (name.startsWith(".") || name.equals("__MACOSX") || name.equals("native") || name.equals("node_modules")) {
                continue;
            }
            scanLevel(base, child, depth + 1, found);
        }
    }

    private static List<Path> sortedChildDirs(Path dir) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p) && !Files.isSymbolicLink(p)) {
                    out.add(p);
                }
            }
        } catch (IOException ignored) {
            return out;
        }
        out.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return out;
    }

    private static List<String> listSortedNames(Path dir) {
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                out.add(p.getFileName().toString());
            }
        } catch (IOException ignored) {
            return out;
        }
        Collections.sort(out);
        return out;
    }

    /** The plugin.json inside a zip (at the top or one folder down), or null if it is not a plugin zip. */
    public static Map<String, Object> pluginZipManifest(Path path) {
        try (ZipFile z = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = z.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                String[] parts = name.split("/", -1);
                if (!parts[parts.length - 1].equals("plugin.json") || parts.length > 2
                        || List.of(parts).contains("__MACOSX")) {
                    continue;
                }
                try (InputStream in = z.getInputStream(entry)) {
                    String text = new String(Util.readFully(in), java.nio.charset.StandardCharsets.UTF_8);
                    Map<String, Object> data = Json.parseObject(text);
                    return data != null && data.get("name") != null ? data : null;
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /** Sorted *.zip files directly inside a directory. */
    public static List<Path> zipsIn(Path directory) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return out;
        }
        for (String n : listSortedNames(directory)) {
            Path p = directory.resolve(n);
            if (n.toLowerCase().endsWith(".zip") && Files.isRegularFile(p)) {
                out.add(p);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- local plugin

    /** A plugin folder ready to be packaged, with the results of {@link LocalPlugin#check()}. */
    public static final class LocalPlugin {
        public final Path path;
        public final Map<String, Object> manifest;
        public final String folder;
        public final List<String> warnings = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        /** arch ("arm64"/"arm32") → relative path of the binary. */
        public final Map<String, String> builds = new LinkedHashMap<>();

        public LocalPlugin(Path path, Map<String, Object> manifest) {
            this.path = path;
            this.manifest = manifest;
            this.folder = Util.legalFilename(
                    String.valueOf(manifest.get("manufacturer")) + " - VST - " + String.valueOf(manifest.get("name")));
        }

        public void check() {
            Map<String, Object> m = manifest;
            for (String key : List.of("name", "manufacturer", "uid")) {
                if (isFalsy(m.get(key))) {
                    errors.add("plugin.json has no \"" + key + "\"");
                }
            }
            record Arch(String arch, String key, int machine) {
            }
            List<Arch> arches = List.of(new Arch("arm64", "file64", 0xB7), new Arch("arm32", "file32", 0x28));
            for (Arch a : arches) {
                Object relObj = m.get(a.key());
                if (isFalsy(relObj)) {
                    continue;
                }
                String rel = String.valueOf(relObj);
                Path full = path.resolve(rel).normalize();
                if (!full.startsWith(path.toAbsolutePath().normalize()) || !Files.isRegularFile(full)) {
                    errors.add(rel + " is listed as " + a.key() + " but missing");
                    continue;
                }
                byte[] head = readHead(full);
                if (head.length < 4 || head[0] != 0x7f || head[1] != 'E' || head[2] != 'L' || head[3] != 'F') {
                    errors.add(rel + " is not an ELF binary");
                } else if (u16le(head, 18) != a.machine()) {
                    errors.add(rel + " is not a " + a.arch() + " binary");
                } else {
                    builds.put(a.arch(), rel);
                }
            }
            if (builds.isEmpty() && errors.isEmpty()) {
                errors.add("no ARM binary listed in plugin.json (file32 / file64)");
            }
            if (!Files.isRegularFile(path.resolve("version.xml"))) {
                warnings.add("no version.xml");
            }
            String baseName = path.getFileName() == null ? "" : path.getFileName().toString();
            Path tmp = Paths.get(System.getProperty("java.io.tmpdir")).normalize();
            if (!baseName.equals(folder) && !path.startsWith(tmp)) {
                warnings.add("folder will be installed as \"" + folder + "\"");
            }
        }

        static boolean isFalsy(Object v) {
            if (v == null) {
                return true;
            }
            if (v instanceof Boolean b) {
                return !b;
            }
            if (v instanceof String s) {
                return s.isEmpty();
            }
            if (v instanceof Number n) {
                return n.doubleValue() == 0;
            }
            if (v instanceof Map<?, ?> map) {
                return map.isEmpty();
            }
            if (v instanceof List<?> list) {
                return list.isEmpty();
            }
            return false;
        }

        private static byte[] readHead(Path file) {
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[20];
                int n = 0;
                while (n < buf.length) {
                    int r = in.read(buf, n, buf.length - n);
                    if (r < 0) {
                        break;
                    }
                    n += r;
                }
                byte[] out = new byte[n];
                System.arraycopy(buf, 0, out, 0, n);
                return out;
            } catch (IOException e) {
                return new byte[0];
            }
        }

        private static int u16le(byte[] b, int off) {
            return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
        }
    }

    // ---------------------------------------------------------------- candidates

    /** Something found near the current folder that could be installed. */
    public static final class Candidate {
        public final Path path;
        public final String kind; // "folder" | "zip"
        public final Map<String, Object> manifest;
        public final long mtime; // epoch millis

        Candidate(Path path, String kind, Map<String, Object> manifest, long mtime) {
            this.path = path;
            this.kind = kind;
            this.manifest = manifest;
            this.mtime = mtime;
        }

        /** Dark blue folder-like box for a folder, amber stacked-squares for a zip. */
        public String glyph() {
            if (kind.equals("folder")) {
                return Ui.S.paint(Ui.S.unicode ? "▤" : "D", Ui.S.fg(27));
            }
            return Ui.S.paint(Ui.S.unicode ? "⧉" : "Z", Ui.S.fg(214));
        }

        public String label() {
            String name = manifest.get("name") != null ? String.valueOf(manifest.get("name"))
                    : path.getFileName().toString();
            Object version = manifest.get("version");
            return name + (version != null && !String.valueOf(version).isEmpty()
                    ? " " + version : "");
        }

        public String detail(Path base) {
            Path shownPath;
            if (kind.equals("folder")) {
                Path parent = path.getParent();
                shownPath = parent == null ? path : parent;
            } else {
                shownPath = path;
            }
            String rel;
            try {
                String r = base.toAbsolutePath().normalize().relativize(shownPath.toAbsolutePath().normalize()).toString();
                if (r.startsWith("..")) {
                    rel = shownPath.toString();
                } else {
                    rel = kind.equals("folder") ? r + java.io.File.separator : r;
                }
            } catch (IllegalArgumentException e) {
                rel = shownPath.toString(); // different roots (Windows drives)
            }
            long ageSeconds = (System.currentTimeMillis() - mtime) / 1000;
            String when;
            if (ageSeconds < 3600) {
                when = Math.max(1, ageSeconds / 60) + " min ago";
            } else if (ageSeconds < 86400) {
                when = (ageSeconds / 3600) + " h ago";
            } else {
                when = (ageSeconds / 86400) + " days ago";
            }
            return rel + "  (" + kind + ", " + when + ")";
        }
    }

    /**
     * Look for deployable plugins: built folders and plugin zips in the current folder and in the
     * build/ and dist/ folders of projects under ./plugins and ./examples (also those next to
     * this program, and under any extra roots).
     */
    public static List<Candidate> findCandidates(Path cwd, Path... extraRoots) {
        Path cwdAbs = cwd.toAbsolutePath().normalize();
        List<Path> bases = new ArrayList<>();
        bases.add(cwdAbs);
        Path jarDir = jarDir();
        if (jarDir != null && !jarDir.equals(cwdAbs)) {
            bases.add(jarDir);
        }
        for (Path extra : extraRoots) {
            Path e = extra.toAbsolutePath().normalize();
            if (!bases.contains(e)) {
                bases.add(e);
            }
        }
        List<Path> spots = new ArrayList<>();
        spots.add(cwdAbs);
        spots.add(cwdAbs.resolve("build"));
        spots.add(cwdAbs.resolve("dist"));
        for (Path base : bases) {
            for (String group : new String[]{"plugins", "examples"}) {
                Path gdir = base.resolve(group);
                if (!Files.isDirectory(gdir)) {
                    continue;
                }
                for (String proj : listSortedNames(gdir)) {
                    Path project = gdir.resolve(proj);
                    if (Files.isDirectory(project)) {
                        spots.add(project.resolve("build"));
                        spots.add(project.resolve("dist"));
                    }
                }
            }
        }
        Map<String, Candidate> found = new LinkedHashMap<>();
        for (Path spot : spots) {
            if (!Files.isDirectory(spot)) {
                continue;
            }
            for (Path f : builtFolders(spot)) {
                Map<String, Object> m = readManifest(f);
                if (m != null && m.get("name") != null && m.get("manufacturer") != null) {
                    found.putIfAbsent(realPath(f), new Candidate(f, "folder", m, mtime(f)));
                }
            }
            for (Path z : zipsIn(spot)) {
                Map<String, Object> m = pluginZipManifest(z);
                if (m != null) {
                    found.putIfAbsent(realPath(z), new Candidate(z, "zip", m, mtime(z)));
                }
            }
        }
        List<Candidate> out = new ArrayList<>(found.values());
        out.sort((a, b) -> Long.compare(b.mtime, a.mtime)); // newest first, stable
        return out.size() > 15 ? new ArrayList<>(out.subList(0, 15)) : out;
    }

    /** The directory holding the running jar (the "next to this script" spot in the original). */
    private static Path jarDir() {
        try {
            Path loc = Paths.get(Sources.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(loc)) {
                return loc.getParent().toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static String realPath(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize().toString();
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- loading

    /** Unpack a zip, refusing anything that would land outside dest (extract_zip). */
    public static void extractZip(Path zpath, Path dest) throws IOException {
        Path base = dest.toAbsolutePath().normalize();
        try (ZipFile z = new ZipFile(zpath.toFile())) {
            Enumeration<? extends ZipEntry> entries = z.entries();
            List<ZipEntry> list = new ArrayList<>();
            while (entries.hasMoreElements()) {
                list.add(entries.nextElement());
            }
            for (ZipEntry info : list) {
                String name = info.getName().replace('\\', '/');
                Path target = base.resolve(name).normalize();
                if (!target.equals(base) && !target.startsWith(base)) {
                    throw new UsageError(zpath + " contains an unsafe path (" + info.getName()
                            + "); refusing to unpack it");
                }
            }
            for (ZipEntry info : list) {
                String name = info.getName().replace('\\', '/');
                Path target = base.resolve(name).normalize();
                if (name.isEmpty() || name.equals("/")) {
                    continue;
                }
                if (info.isDirectory() || name.endsWith("/")) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    try (InputStream in = z.getInputStream(info)) {
                        Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /** Is this file a readable zip archive? */
    public static boolean isZipFile(Path path) {
        try (ZipFile z = new ZipFile(path.toFile())) {
            return z.size() >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Resolve a zip file or folder into a list of installable plugins (load_sources).
     * The returned plugins have all had {@code check()} run.
     */
    public static List<LocalPlugin> loadSources(String source, Path workdir) throws IOException {
        String expanded = Util.expandUser(source);
        Path src = Paths.get(expanded);
        if (!Files.exists(src)) {
            throw new UsageError(source + " does not exist");
        }
        List<Path> folders;
        if (Files.isRegularFile(src)) {
            if (!isZipFile(src)) {
                throw new UsageError(source + " is not a zip file");
            }
            Path dest = Files.createTempDirectory(workdir, "src-");
            extractZip(src, dest);
            folders = scanForPlugins(dest);
        } else {
            folders = scanForPlugins(src);
        }
        List<LocalPlugin> plugins = new ArrayList<>();
        for (Path f : folders) {
            Map<String, Object> m = readManifest(f);
            if (m == null || LocalPlugin.isFalsy(m.get("name")) || LocalPlugin.isFalsy(m.get("manufacturer"))) {
                continue;
            }
            LocalPlugin lp = new LocalPlugin(f, m);
            lp.check();
            plugins.add(lp);
        }
        if (plugins.isEmpty()) {
            String hint = "";
            if (Files.isDirectory(src) && Files.isDirectory(src.resolve("src"))) {
                hint = " This looks like an unbuilt SDK project: run  python tools/build.py " + source + "  first.";
            }
            throw new UsageError("No plugin found in " + source + " (looking for a folder with plugin.json)." + hint);
        }
        return plugins;
    }

    /**
     * One path (spaces allowed, quotes or backslashes optional) or several quoted ones
     * (parse_paths).
     */
    public static List<String> parsePaths(String val) {
        String v = val.strip();
        if (v.length() > 1 && (v.charAt(0) == '"' || v.charAt(0) == '\'') && v.charAt(0) == v.charAt(v.length() - 1)) {
            v = v.substring(1, v.length() - 1);
        }
        v = v.replace("\\ ", " ");
        if (Files.exists(Paths.get(Util.expandUser(v)))) {
            return List.of(v);
        }
        try {
            return Util.shlexSplit(val);
        } catch (RuntimeException e) {
            return List.of(v);
        }
    }
}

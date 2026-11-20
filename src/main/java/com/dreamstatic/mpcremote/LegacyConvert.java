package com.dreamstatic.mpcremote;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Convert a community "mpc-vst-plugins" package to the SDK's plugin folder format —
 * the Java port of legacy_convert.py. The plugin's own binaries are not changed; only the
 * packaging around them (plugin.json, version.xml) is produced.
 */
public final class LegacyConvert {

    /** The set of characters the MPC strips when it builds the folder name. */
    private static final Set<String> LEGACY_ONLY = Set.of("plugin-meta.xml", "mpc-plugin.json");
    private static final Pattern REPO_PATTERN = Pattern.compile("[\\w.-]+/[\\w.-]+",
            Pattern.UNICODE_CHARACTER_CLASS);

    private LegacyConvert() {
    }

    public static class ConvertError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConvertError(String message) {
            super(message);
        }
    }

    /** One converted plugin: where it was saved and any notes about it. */
    public static final class Result {
        public final String folder;
        public final String zip; // null when no zip was requested
        public final Map<String, Object> manifest;
        public final List<String> warnings;

        Result(String folder, String zip, Map<String, Object> manifest, List<String> warnings) {
            this.folder = folder;
            this.zip = zip;
            this.manifest = manifest;
            this.warnings = warnings;
        }
    }

    // ---------------------------------------------------------------- small helpers

    /** Lower-case and turn every non-alphanumeric character into a dash (make_slug). */
    static String makeSlug(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (char c : name.toLowerCase().toCharArray()) {
            out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ? c : '-');
        }
        return out.toString();
    }

    /** "1.2" → "1.2.0.0"; "2.1.0" → "2.1.0.0" (pad_version). */
    static String padVersion(String v) {
        List<String> nums = new ArrayList<>();
        var m = Pattern.compile("\\d+").matcher(v);
        while (m.find() && nums.size() < 4) {
            nums.add(m.group());
        }
        if (nums.isEmpty()) {
            return "1.0.0.0";
        }
        while (nums.size() < 4) {
            nums.add("0");
        }
        return String.join(".", nums);
    }

    /**
     * The old files give the VST unique id as 8 hex digits ('41634964' is the code 'AcId');
     * convert_uid prefers the four-character-code spelling when the value is printable ASCII.
     */
    static String convertUid(String text) {
        String t = text == null ? "" : text.strip();
        long value;
        if (t.matches("[0-9A-Fa-f]{8}")) {
            value = Long.parseLong(t, 16);
        } else if (t.matches("\\d+")) {
            value = Long.parseLong(t);
        } else {
            throw new ConvertError("cannot read the plugin uid " + repr(text));
        }
        byte[] raw = new byte[]{(byte) ((value >> 24) & 0xff), (byte) ((value >> 16) & 0xff),
                (byte) ((value >> 8) & 0xff), (byte) (value & 0xff)};
        boolean printable = true;
        for (byte b : raw) {
            if (b <= 0x20 || b >= 0x7f) {
                printable = false;
                break;
            }
        }
        if (printable) {
            return new String(raw, StandardCharsets.ISO_8859_1); // a four-character code
        }
        return String.format("0x%08X", value);
    }

    /** Roughly Python's %r for the strings we pass around. */
    private static String repr(Object v) {
        if (v == null) {
            return "None";
        }
        return "'" + v + "'";
    }

    /** ELF machine id → arm32/arm64 (elf_arch). */
    static String elfArch(Path path) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(path)) {
            head = new byte[20];
            int n = 0;
            while (n < head.length) {
                int r = in.read(head, n, head.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            if (n < head.length) {
                head = java.util.Arrays.copyOf(head, n);
            }
        }
        if (head.length < 4 || head[0] != 0x7f || head[1] != 'E' || head[2] != 'L' || head[3] != 'F') {
            throw new ConvertError(path.getFileName() + " is not an ELF binary");
        }
        int machine = (head[18] & 0xff) | ((head[19] & 0xff) << 8);
        if (machine == 0x28) {
            return "arm32";
        }
        if (machine == 0xB7) {
            return "arm64";
        }
        throw new ConvertError(String.format("%s is not an ARM binary (machine 0x%x)", path.getFileName(), machine));
    }

    // ---------------------------------------------------------------- zips

    /** Unpack a zip, refusing anything that would land outside dest (safe_extract). */
    private static void safeExtract(Path zpath, Path dest) throws IOException {
        Path base = dest.toAbsolutePath().normalize();
        try (ZipFile z = new ZipFile(zpath.toFile())) {
            List<ZipEntry> entries = new ArrayList<>();
            var en = z.entries();
            while (en.hasMoreElements()) {
                entries.add(en.nextElement());
            }
            for (ZipEntry info : entries) {
                String name = info.getName().replace('\\', '/');
                Path target = base.resolve(name).normalize();
                if (!target.equals(base) && !target.startsWith(base)) {
                    throw new ConvertError(zpath + " contains an unsafe path (" + info.getName()
                            + "); refusing to unpack it");
                }
            }
            for (ZipEntry info : entries) {
                String name = info.getName().replace('\\', '/');
                if (name.isEmpty()) {
                    continue;
                }
                Path target = base.resolve(name).normalize();
                if (info.isDirectory() || name.endsWith("/")) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    try (InputStream in = z.getInputStream(info)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (java.util.zip.ZipException e) {
            throw new ConvertError(zpath + " is not a valid zip file");
        }
    }

    // ---------------------------------------------------------------- legacy folders

    /** Folders holding a plugin-meta.xml, at most a few levels below root (find_legacy_folders). */
    static List<Path> findLegacyFolders(Path root) {
        List<Path> found = new ArrayList<>();
        Path abs = root.toAbsolutePath().normalize();
        walkLegacy(abs, 0, found);
        return found;
    }

    private static void walkLegacy(Path dir, int depth, List<Path> found) {
        List<Path> children = new ArrayList<>();
        boolean hasMeta = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    if (!name.equals("__MACOSX") && !name.startsWith(".")) {
                        children.add(p);
                    }
                } else if (name.equals("plugin-meta.xml")) {
                    hasMeta = true;
                }
            }
        } catch (IOException e) {
            return;
        }
        children.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        if (hasMeta) {
            found.add(dir);
            return; // do not descend into a legacy plugin folder
        }
        if (depth >= 4) {
            return;
        }
        for (Path child : children) {
            walkLegacy(child, depth + 1, found);
        }
    }

    /** Parse plugin-meta.xml (and mpc-plugin.json when there is one). */
    static Map<String, Object> readLegacy(Path folder) {
        Element p;
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            var doc = dbf.newDocumentBuilder().parse(folder.resolve("plugin-meta.xml").toFile());
            Element root = doc.getDocumentElement();
            List<Element> plugins = new ArrayList<>();
            if (etTag(root).equals("PLUGIN")) {
                plugins.add(root);
            } else {
                NodeList all = root.getElementsByTagName("*");
                for (int i = 0; i < all.getLength(); i++) {
                    Element e = (Element) all.item(i);
                    if (etTag(e).equals("PLUGIN")) {
                        plugins.add(e);
                    }
                }
            }
            if (plugins.size() != 1) {
                throw new ConvertError(folder + ": expected one plugin in plugin-meta.xml, found " + plugins.size());
            }
            p = plugins.get(0);
        } catch (ConvertError e) {
            throw e;
        } catch (Exception e) {
            throw new ConvertError(folder + ": cannot read plugin-meta.xml (" + e.getMessage() + ")");
        }

        if ("1".equals(attr(p, "isShell"))) {
            throw new ConvertError(folder + " is a shell plugin (several plugins in one binary), which is not supported");
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        Path extraFile = folder.resolve("mpc-plugin.json");
        if (Files.isRegularFile(extraFile)) {
            try {
                Map<String, Object> parsed = Json.parseObject(Files.readString(extraFile, StandardCharsets.UTF_8));
                if (parsed != null) {
                    extra = parsed;
                }
            } catch (IOException | RuntimeException ignored) {
                // no usable mpc-plugin.json
            }
        }
        String name = attr(p, "name");
        String vendor = attr(p, "manufacturer");
        if (name == null || name.isEmpty() || vendor == null || vendor.isEmpty()) {
            throw new ConvertError(folder + ": plugin-meta.xml has no name or manufacturer");
        }
        // file="%payload-path%/<folder name>/<binary path>": the binary's path inside the plugin
        // folder is what follows
        String fileAttr = (attr(p, "file") == null ? "" : attr(p, "file")).replace('\\', '/');
        String folderName = folder.getFileName().toString();
        String rel;
        if (fileAttr.contains("/" + folderName + "/")) {
            rel = fileAttr.split(java.util.regex.Pattern.quote("/" + folderName + "/"), 2)[1];
        } else {
            rel = Util.baseName(fileAttr);
        }
        Path relPath = rel.isEmpty() ? null : folder.resolve(rel.replace('\\', '/')).normalize();
        if (rel.isEmpty() || relPath == null || !Files.isRegularFile(relPath)) {
            Object so = extra.get("so");
            if (so != null && !String.valueOf(so).isEmpty()) {
                rel = String.valueOf(so);
            }
        }
        Path full = rel.isEmpty() ? null : folder.resolve(rel.replace('\\', '/')).normalize();
        if (rel.isEmpty() || full == null || !full.startsWith(folder.toAbsolutePath().normalize())
                || !Files.isRegularFile(full)) {
            throw new ConvertError(folder + ": the plugin binary " + repr(rel)
                    + " named in plugin-meta.xml is missing");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("manufacturer", vendor);
        out.put("uid", convertUid(attr(p, "uid")));
        out.put("kind", "1".equals(attr(p, "isInstrument")) ? "instrument" : "effect");
        out.put("category", attr(p, "category") == null ? "" : attr(p, "category"));
        out.put("numInputs", intAttr(p, "numInputs", 0));
        out.put("numOutputs", Math.max(intAttr(p, "numOutputs", 0), 2));
        out.put("version", str(extra.get("version")) != null && !str(extra.get("version")).isEmpty()
                ? str(extra.get("version"))
                : (attr(p, "version") != null && !attr(p, "version").isEmpty()
                ? attr(p, "version") : "1.0.0"));
        out.put("description", str(extra.get("about")) == null ? "" : str(extra.get("about")));
        out.put("license", str(extra.get("license")) == null ? "" : str(extra.get("license")));
        out.put("repo", str(extra.get("source_repo")) == null ? "" : str(extra.get("source_repo")));
        out.put("rel", rel.replace('\\', '/'));
        try {
            out.put("arch", elfArch(full));
        } catch (IOException e) {
            throw new ConvertError(full + ": " + e.getMessage());
        }
        return out;
    }

    private static String etTag(Element e) {
        String uri = e.getNamespaceURI();
        String local = e.getLocalName();
        if (local == null) {
            local = e.getTagName();
        }
        return (uri == null || uri.isEmpty() ? "" : "{" + uri + "}") + local;
    }

    private static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    private static int intAttr(Element e, String name, int dflt) {
        String v = attr(e, name);
        if (v == null || v.isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException ex) {
            throw new ConvertError("cannot read " + name + " from plugin-meta.xml (" + repr(v) + ")");
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    // ---------------------------------------------------------------- copying

    /** Copy a legacy plugin folder into dst, skipping files already there and the installer's own files. */
    private static void copyMissing(Path src, Path dst) throws IOException {
        Path abs = src.toAbsolutePath().normalize();
        List<Path> all = new ArrayList<>();
        try (var walk = Files.walk(abs)) {
            walk.forEach(all::add);
        }
        all.sort((a, b) -> {
            String ra = abs.relativize(a).toString().replace('\\', '/');
            String rb = abs.relativize(b).toString().replace('\\', '/');
            return ra.compareTo(rb);
        });
        for (Path p : all) {
            String rel = abs.relativize(p).toString().replace('\\', '/');
            if (rel.isEmpty()) {
                continue;
            }
            String base = p.getFileName().toString();
            if (Util.JUNK_NAMES.contains(base) || base.startsWith("._")) {
                continue;
            }
            if (p.getParent().equals(abs) && LEGACY_ONLY.contains(base)) {
                continue;
            }
            if (Files.isDirectory(p) && !Files.isSymbolicLink(p)) {
                Files.createDirectories(dst.resolve(rel));
            } else if (Files.isRegularFile(p)) {
                Path target = dst.resolve(rel);
                if (!Files.exists(target)) {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ---------------------------------------------------------------- conversion

    /** Convert one legacy folder into out_dir; the 32-bit and 64-bit packages merge. */
    private static Path convertFolder(Path legacy, Path outDir, Map<Path, Map<String, Object>> created,
                                      Map<String, List<String>> warns) throws IOException {
        Map<String, Object> meta = readLegacy(legacy);
        String folder = Util.legalFilename(
                String.valueOf(meta.get("manufacturer")) + " - VST - " + String.valueOf(meta.get("name")));
        Path dest = outDir.resolve(folder);
        List<String> warnings = new ArrayList<>();
        if (!legacy.getFileName().toString().equals(folder)) {
            warnings.add("the package folder was named \"" + legacy.getFileName() + "\"; the plugin reports \""
                    + folder + "\"");
        }
        String key = meta.get("arch").equals("arm64") ? "file64" : "file32";

        Map<String, Object> manifest = created.get(dest);
        boolean first = manifest == null;
        if (first) {
            Files.createDirectory(dest); // fails if it exists, like Python's os.makedirs(dest)
            manifest = new LinkedHashMap<>();
            manifest.put("name", meta.get("name"));
            manifest.put("manufacturer", meta.get("manufacturer"));
            manifest.put("version", meta.get("version"));
            manifest.put("format", "vst2");
            manifest.put("kind", meta.get("kind"));
            manifest.put("uid", meta.get("uid"));
            String category = String.valueOf(meta.get("category"));
            manifest.put("category", category.isEmpty()
                    ? ("instrument".equals(meta.get("kind")) ? "Synth" : "Effect") : category);
            manifest.put("numInputs", meta.get("numInputs"));
            manifest.put("numOutputs", meta.get("numOutputs"));
            String description = String.valueOf(meta.get("description"));
            if (!description.isEmpty()) {
                manifest.put("description", description);
            }
            String license = String.valueOf(meta.get("license"));
            if (!license.isEmpty()) {
                manifest.put("license", license);
            }
            String repo = String.valueOf(meta.get("repo"));
            if (REPO_PATTERN.matcher(repo).matches()) {
                manifest.put("url", "https://github.com/" + repo);
            }
            created.put(dest, manifest);
        } else if (!String.valueOf(manifest.get("uid")).equals(String.valueOf(meta.get("uid")))) {
            throw new ConvertError(legacy + ": uid differs from the other package for this plugin; "
                    + "they are not the same plugin");
        }
        if (manifest.containsKey(key)) {
            warnings.add("already have a " + meta.get("arch") + " build of " + meta.get("name")
                    + "; ignoring this one");
            warns.computeIfAbsent(dest.toString(), k -> new ArrayList<>()).addAll(warnings);
            return dest;
        }

        // The binary stays where the package had it: these plugins may look for their data next
        // to the .so.
        String rel = String.valueOf(meta.get("rel"));
        Path target = dest.resolve(rel);
        if (Files.exists(target)) { // the other architecture's build has the same file name
            int slash = rel.lastIndexOf('/');
            String base = slash < 0 ? rel : rel.substring(slash + 1);
            String parent = slash < 0 ? "" : rel.substring(0, slash + 1);
            int dot = base.lastIndexOf('.');
            String stem = dot < 0 ? base : base.substring(0, dot);
            String ext = dot < 0 ? "" : base.substring(dot);
            rel = parent + stem + "-" + meta.get("arch") + ext;
            target = dest.resolve(rel);
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.copy(legacy.resolve(String.valueOf(meta.get("rel"))), target,
                StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX filesystem
        }
        manifest.put(key, rel);
        copyMissing(legacy, dest);
        if (!Files.isRegularFile(dest.resolve("version.xml"))) {
            String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<plugincontent version=\"1.0\">\n"
                    + "    <version>" + padVersion(String.valueOf(meta.get("version"))) + "</version>\n"
                    + "</plugincontent>\n";
            Files.writeString(dest.resolve("version.xml"), xml, StandardCharsets.UTF_8);
        }
        warns.computeIfAbsent(dest.toString(), k -> new ArrayList<>()).addAll(warnings);
        return dest;
    }

    /** Write plugin.json (binaries in a stable key order) and optionally the zip. */
    private static String finish(Path dest, Map<String, Object> manifest, boolean makeZip, Path outDir)
            throws IOException {
        String[] order = {"name", "manufacturer", "version", "format", "kind", "uid", "category",
                "numInputs", "numOutputs", "file32", "file64", "description", "license", "url"};
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String k : order) {
            if (manifest.containsKey(k)) {
                ordered.put(k, manifest.get(k));
            }
        }
        ordered.put("convertedFrom", "community mpc-vst-plugins package");
        Files.writeString(dest.resolve("plugin.json"), Json.writeIndented(ordered) + "\n", StandardCharsets.UTF_8);
        if (!makeZip) {
            return null;
        }
        String folder = dest.getFileName().toString();
        String version = Util.legalFilename(String.valueOf(manifest.get("version"))).replace(" ", "-");
        Path zpath = outDir.resolve(makeSlug(String.valueOf(manifest.get("name"))) + "-" + version + "-mpc.zip");
        List<Path> all = new ArrayList<>();
        try (var walk = Files.walk(dest)) {
            walk.filter(Files::isRegularFile).forEach(all::add);
        }
        all.sort((a, b) -> {
            String ra = dest.relativize(a).toString().replace('\\', '/');
            String rb = dest.relativize(b).toString().replace('\\', '/');
            return ra.compareTo(rb);
        });
        Map<String, Integer> modes = new java.util.LinkedHashMap<>();
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zpath))) {
            for (Path file : all) {
                String arc = folder + "/" + dest.relativize(file).toString().replace('\\', '/');
                ZipEntry zi = new ZipEntry(arc);
                zi.setTime(Files.getLastModifiedTime(file).toMillis());
                int mode;
                try {
                    mode = Files.getPosixFilePermissions(file).contains(
                            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE) ? 0100755 : 0100644;
                } catch (UnsupportedOperationException e) {
                    mode = 0100644;
                }
                modes.put(arc, mode);
                z.putNextEntry(zi);
                z.write(Files.readAllBytes(file));
                z.closeEntry();
            }
            ZipEntry install = new ZipEntry("INSTALL.txt");
            install.setTime(System.currentTimeMillis());
            z.putNextEntry(install);
            z.write(InstallText.installText(ordered, folder).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        // keep the executable bits, like zipfile.ZipInfo.from_file does
        Util.applyUnixModes(zpath, modes);
        return zpath.toString();
    }

    /**
     * Convert packages (convert). Returns one Result per converted plugin folder, in the order
     * the plugins were first seen.
     */
    public static List<Result> convert(List<String> sources, String outDir, boolean makeZip, boolean force)
            throws IOException {
        Path out = Paths.get(Util.expandUser(outDir)).toAbsolutePath().normalize();
        Files.createDirectories(out);
        Path work = Files.createTempDirectory("legacy-");
        Map<Path, Map<String, Object>> created = new LinkedHashMap<>();
        Map<String, List<String>> warns = new LinkedHashMap<>();
        try {
            for (String srcStr : sources) {
                String src = Util.expandUser(srcStr);
                Path srcPath = Paths.get(src);
                if (!Files.exists(srcPath)) {
                    throw new ConvertError(src + " does not exist");
                }
                Path root;
                if (Files.isRegularFile(srcPath)) {
                    root = Files.createTempDirectory(work, "x-");
                    safeExtract(srcPath, root);
                } else {
                    root = srcPath;
                }
                List<Path> legacy = findLegacyFolders(root);
                if (legacy.isEmpty()) {
                    throw new ConvertError(src
                            + " has no plugin-meta.xml, so it is not a community mpc-vst-plugins package");
                }
                for (Path folder : legacy) {
                    Map<String, Object> meta = readLegacy(folder);
                    Path dest = out.resolve(Util.legalFilename(
                            String.valueOf(meta.get("manufacturer")) + " - VST - "
                                    + String.valueOf(meta.get("name"))));
                    if (!created.containsKey(dest) && Files.exists(dest)) {
                        if (!force) {
                            throw new ConvertError(dest + " already exists; use --force to replace it");
                        }
                        Util.deleteRecursively(dest);
                    }
                    convertFolder(folder, out, created, warns);
                }
            }
            List<Result> results = new ArrayList<>();
            for (Map.Entry<Path, Map<String, Object>> e : created.entrySet()) {
                Path dest = e.getKey();
                Map<String, Object> manifest = e.getValue();
                List<String> w = warns.getOrDefault(dest.toString(), new ArrayList<>());
                if (!manifest.containsKey("file32")) {
                    w.add("no 32-bit build: it will not run on MPC X / Live / Live II / One / Key / Force");
                }
                if (!manifest.containsKey("file64")) {
                    w.add("no 64-bit build: it will not run on MPC Live III / XL (add the aarch64 zip if the author has one)");
                }
                results.add(new Result(dest.toString(), finish(dest, manifest, makeZip, out), manifest, w));
            }
            return results;
        } finally {
            Util.deleteRecursively(work);
        }
    }
}

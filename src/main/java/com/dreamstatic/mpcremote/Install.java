package com.dreamstatic.mpcremote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Packaging a local plugin folder as a tar and installing it on the MPC. */
public final class Install {

    private static final Pattern WC_LINE = Pattern.compile("\\s*(\\d+)\\s+\\./(.*)");

    private Install() {
    }

    /** A built tar plus the expected size of every file inside it (for verification). */
    public record TarBuild(byte[] bytes, Map<String, Long> sizes) {
    }

    /**
     * Tar the plugin folder with normalised owners and modes (build_tar): every file becomes
     * 0755 when it is an ELF binary and 0644 otherwise, owners are root/root, and the usual
     * junk (.DS_Store, Thumbs.db, ._*, __MACOSX, symlinks) is left out.
     */
    public static TarBuild buildTar(Sources.LocalPlugin lp) throws IOException {
        List<Tar.Entry> entries = new ArrayList<>();
        Map<String, Long> sizes = new LinkedHashMap<>();
        Path root = lp.path;
        long rootMtime = Files.getLastModifiedTime(root).toMillis() / 1000;
        entries.add(new Tar.Entry(lp.folder, true, 0755, rootMtime, null));
        walk(root, lp.folder, lp.folder.length(), entries, sizes);
        return new TarBuild(Tar.create(entries), sizes);
    }

    private static void walk(Path dir, String arc, int folderLen, List<Tar.Entry> entries, Map<String, Long> sizes)
            throws IOException {
        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isSymbolicLink(child)) {
                    continue;
                }
                if (Files.isDirectory(child)) {
                    dirs.add(child);
                } else if (Files.isRegularFile(child)) {
                    files.add(child);
                }
            }
        }
        dirs.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        files.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));

        for (Path child : dirs) {
            String name = child.getFileName().toString();
            if (name.equals("__MACOSX") || name.startsWith("._")) {
                continue;
            }
            String childArc = arc + "/" + name;
            entries.add(new Tar.Entry(childArc, true, 0755,
                    Files.getLastModifiedTime(child).toMillis() / 1000, null));
        }
        for (Path child : files) {
            String name = child.getFileName().toString();
            if (Util.JUNK_NAMES.contains(name) || name.startsWith("._")) {
                continue;
            }
            byte[] data = Files.readAllBytes(child);
            boolean elf = data.length >= 4 && data[0] == 0x7f && data[1] == 'E' && data[2] == 'L' && data[3] == 'F';
            String childArc = arc + "/" + name;
            entries.add(new Tar.Entry(childArc, false, elf ? 0755 : 0644,
                    Files.getLastModifiedTime(child).toMillis() / 1000, data));
            String rel = childArc.substring(folderLen + 1);
            sizes.put(rel, (long) data.length);
        }
        for (Path child : dirs) {
            String name = child.getFileName().toString();
            if (name.equals("__MACOSX") || name.startsWith("._")) {
                continue;
            }
            walk(child, arc + "/" + name, folderLen, entries, sizes);
        }
    }

    /**
     * Copy one plugin to the MPC (install). The tar is streamed straight into a staging folder
     * on the drive and swapped into place, with a size check before and a byte count after.
     */
    public static boolean install(Context ctx, Sources.LocalPlugin lp, boolean replace) throws Exception {
        Drive d = ctx.drive;
        String vst = d.pluginsPath();
        String name = lp.folder;
        if (!Plugins.safeFolderName(name)) {
            Ui.say("  " + Ui.S.badc(Ui.S.bad) + " Unsafe plugin folder name: '" + name + "'");
            return false;
        }
        for (String w : lp.warnings) {
            Ui.say("  " + Ui.S.warnc(Ui.S.warn) + " " + w);
        }
        if (!lp.errors.isEmpty()) {
            for (String e : lp.errors) {
                Ui.say("  " + Ui.S.badc(Ui.S.bad) + " " + e);
            }
            return false;
        }
        if (ctx.arch != null && !lp.builds.containsKey(ctx.arch)) {
            String have = lp.builds.isEmpty() ? "nothing usable"
                    : String.join("/", new TreeSet<>(lp.builds.keySet()));
            Ui.say("  " + Ui.S.badc(Ui.S.bad) + String.format(
                    "This MPC needs an %s build and %s only contains %s.", ctx.arch, lp.folder, have));
            return false;
        }

        TarBuild tar = buildTar(lp);
        byte[] data = tar.bytes();
        Map<String, Long> sizes = tar.sizes();
        // While the plugin is staged any old copy still occupies its space, so the whole new
        // plugin (plus filesystem block rounding) must fit in what is free right now.
        long need = data.length + 4096L * (sizes.size() + 1) + (1 << 20);

        boolean[] enough = {true};
        Ui.step("Checking space on " + d.name, st -> {
            Long free = Drive.free(ctx.remote, d);
            if (free == null) {
                st.warn("could not tell; carrying on");
            } else if (free < need) {
                enough[0] = false;
                st.fail(Util.humanSize(free) + " free, needs " + Util.humanSize(need));
                Ui.say("  " + Ui.S.badc(Ui.S.bad) + " Not enough space on " + d.name + " to unpack " + name
                        + ". Free some space or choose another drive (--drive).");
            } else {
                st.done(Util.humanSize(free) + " free, needs about " + Util.humanSize(need));
            }
        });
        if (!enough[0]) {
            return false;
        }

        Plugins.Listing listing = Plugins.list(ctx.remote, d);
        boolean present = false;
        for (Plugins.InstalledPlugin p : listing.plugins()) {
            if (p.folder.equals(name)) {
                present = true;
                break;
            }
        }
        if (present && !replace) {
            if (!Ui.confirm(name + " is already installed on " + d.name + ". Replace it?", false, ctx.assumeYes)) {
                Ui.say("  " + Ui.S.warnc(Ui.S.warn) + " Left " + name
                        + " alone (use the update command to replace it).");
                return false;
            }
        }

        String stage = stripTrailingSlashes(d.mount) + "/.mpc-remote-stage";
        String qVst = Util.shlexQuote(vst);
        String qName = Util.shlexQuote(name);
        String qStage = Util.shlexQuote(stage);
        String script = "set -e\n"
                + "V=" + qVst + "; N=" + qName + "; T=" + qStage + "\n"
                + "rm -rf \"$T\"; mkdir -p \"$V\" \"$T\"\n"
                + "tar -xof - -C \"$T\"\n"
                + "[ -f \"$T/$N/plugin.json\" ] || { rm -rf \"$T\"; echo 'upload incomplete' >&2; exit 3; }\n"
                + "if [ -e \"$V/$N\" ]; then mv \"$V/$N\" \"$T/.old\"; fi\n"
                + "mv \"$T/$N\" \"$V/$N\"\n";

        boolean[] copied = {true};
        Ui.step("Copying " + Ui.S.bold(name) + " " + Ui.S.dim("(" + Util.humanSize(data.length) + ")"), st -> {
            Remote.Result r = ctx.remote.run(script, data, 900);
            if (r.rc() != 0) {
                copied[0] = false;
                ctx.remote.text("rm -rf " + qStage);
                String last = lastLine(r.err());
                st.fail(last != null ? last : "copy failed (exit " + r.rc() + ")");
            } else {
                st.done();
            }
        });
        if (!copied[0]) {
            return false;
        }

        boolean[] intact = {true};
        Ui.step("Verifying files", st -> {
            Remote.Result r = ctx.remote.text(
                    "cd " + Util.shlexQuote(vst + "/" + name) + " && find . -type f -exec wc -c {} \\;", 60);
            Map<String, Long> remoteSizes = new LinkedHashMap<>();
            for (String line : r.outText().split("\\R")) {
                Matcher m = WC_LINE.matcher(line);
                if (m.matches()) {
                    try {
                        remoteSizes.put(m.group(2), Long.parseLong(m.group(1)));
                    } catch (NumberFormatException ignored) {
                        // unparsable line: treated as a missing file below
                    }
                }
            }
            List<String> bad = new ArrayList<>();
            for (Map.Entry<String, Long> e : sizes.entrySet()) {
                Long got = remoteSizes.get(e.getKey());
                if (got == null || got.longValue() != e.getValue()) {
                    bad.add(e.getKey());
                }
            }
            if (!bad.isEmpty()) {
                intact[0] = false;
                ctx.remote.text("set -e; rm -rf " + qVst + "/" + qName
                        + "; if [ -e " + qStage + "/.old ]; then mv " + qStage + "/.old " + qVst + "/" + qName
                        + "; fi; rm -rf " + qStage);
                st.fail(bad.get(0) + " did not arrive intact; previous version restored");
            } else {
                ctx.remote.text("rm -rf " + qStage + "; sync");
                st.done(sizes.size() + " files match");
            }
        });
        return intact[0];
    }

    private static String stripTrailingSlashes(String s) {
        String out = s;
        while (out.length() > 1 && out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String lastLine(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String[] lines = s.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i];
            }
        }
        return null;
    }

    /** The reminders printed after a successful install. */
    public static void postInstallNotes(Context ctx) {
        List<String> notes = new ArrayList<>();
        if (ctx.drive.noexec()) {
            notes.add(Ui.S.warnc(Ui.S.warn) + " " + ctx.drive.name
                    + " is mounted noexec: the MPC may refuse to load plugins from it. "
                    + "If the plugin does not appear, try the internal drive.");
        }
        notes.add(Ui.S.dim(Ui.S.info) + " On the MPC: Preferences → Enhancements (Beta) → Enable Enhancements + External Plugin Support must be on.");
        notes.add(Ui.S.dim(Ui.S.info) + " Then open a plugin browser: it is listed under External Plugins. "
                + "If it was already loaded on a track, remove and re-add it (or restart the MPC).");
        Ui.say();
        for (String n : notes) {
            Ui.say("  " + n);
        }
        Ui.say();
    }
}

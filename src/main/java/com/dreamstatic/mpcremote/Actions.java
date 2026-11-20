package com.dreamstatic.mpcremote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** The actions behind the commands: list, add, update, remove, export, doctor, restart. */
public final class Actions {

    private static final String MPC_SERVICE = "acvs"; // the systemd unit that runs the MPC application

    private Actions() {
    }

    // ---------------------------------------------------------------- list

    public static List<Plugins.InstalledPlugin> actionList(Context ctx) throws Exception {
        AtomicBoolean exists = new AtomicBoolean(false);
        AtomicReference<List<Plugins.InstalledPlugin>> ref = new AtomicReference<>(List.of());
        Ui.step("Reading " + ctx.drive.name + "/" + Drive.PLUGINS_DIR, st -> {
            Plugins.Listing l = Plugins.list(ctx.remote, ctx.drive);
            exists.set(l.exists());
            ref.set(l.plugins());
            int n = l.plugins().size();
            st.done(n + " plugin" + (n == 1 ? "" : "s"));
        });
        Ui.say();
        Plugins.show(ref.get(), ctx.drive, exists.get());
        Ui.say();
        return ref.get();
    }

    // ---------------------------------------------------------------- add / update

    /** Offer plugins found near the current folder, or let the user type a path. */
    private static List<String> pickSource(boolean replace) {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<Sources.Candidate> cands = Sources.findCandidates(cwd);
        if (!cands.isEmpty()) {
            List<String[]> items = new ArrayList<>();
            for (Sources.Candidate c : cands) {
                items.add(new String[]{c.glyph() + " " + c.label(), c.detail(cwd)});
            }
            items.add(new String[]{"  " + (Ui.S.unicode ? "Enter a file path…" : "Enter a file path..."),
                    "a zip or an SDK project folder"});
            Integer idx = Ui.choose("Which plugin do you want to " + (replace ? "update" : "add") + "?",
                    items, true, "back");
            if (idx == null) {
                return List.of();
            }
            if (idx < cands.size()) {
                return List.of(cands.get(idx).path.toString());
            }
        } else {
            Ui.say("  " + Ui.S.dim("No built plugins or plugin zips found nearby (looked in this folder, plugins/ and examples/)."));
        }
        String val = Ui.ask("Plugin zip or folder", null, false);
        return val.isEmpty() ? List.of() : Sources.parsePaths(val);
    }

    public static boolean actionAdd(Context ctx, List<String> sources, boolean replace) throws Exception {
        if (!ctx.requireWritable()) {
            return false;
        }
        List<String> srcs = sources;
        if (srcs.isEmpty()) {
            if (!Ui.interactive()) {
                throw new UsageError("Give a zip file or an SDK plugin folder to " + (replace ? "update" : "add") + ".");
            }
            srcs = pickSource(replace);
            if (srcs.isEmpty()) {
                return false;
            }
        }
        boolean allOk = true;
        int installed = 0;
        for (String src : srcs) {
            List<Sources.LocalPlugin> plugins = Sources.loadSources(src, ctx.workdir);
            List<Sources.LocalPlugin> chosen = plugins;
            if (plugins.size() > 1 && Ui.interactive() && !ctx.assumeYes) {
                Ui.say();
                Ui.say("  " + Ui.S.bold(src) + " contains " + plugins.size() + " plugins.");
                List<String[]> items = new ArrayList<>();
                for (Sources.LocalPlugin p : plugins) {
                    items.add(new String[]{p.folder, "v" + field(p.manifest, "version", "?")});
                }
                items.add(new String[]{"All of them", ""});
                Integer idx = Ui.choose("Install which?", items, false, "");
                chosen = idx == plugins.size() ? plugins : List.of(plugins.get(idx));
            }
            for (Sources.LocalPlugin lp : chosen) {
                Map<String, Object> m = lp.manifest;
                Ui.say();
                Ui.say("  " + Ui.S.accent(Ui.S.arrow) + " "
                        + Ui.S.bold(field(m, "name", lp.folder)) + " "
                        + Ui.S.dim("by " + field(m, "manufacturer", "") + " · v" + field(m, "version", "?")
                        + " · " + field(m, "kind", "?")));
                if (Install.install(ctx, lp, replace)) {
                    installed++;
                } else {
                    allOk = false;
                }
            }
        }
        if (installed > 0) {
            Ui.say();
            Ui.say("  " + Ui.S.good(Ui.S.ok) + " "
                    + Ui.S.bold("Installed " + installed + " plugin" + (installed == 1 ? "" : "s")
                    + " on " + ctx.drive.name));
            Install.postInstallNotes(ctx);
            if (Ui.interactive() && !ctx.assumeYes) {
                actionRestart(ctx, true); // shows the unsaved-data warning and asks first
            } else { // never restart unattended: it throws away the open project
                Ui.say("  " + Ui.S.dim(Ui.S.info)
                        + " Run the restart command to make the MPC rescan its plugins (unsaved data is lost).");
                Ui.say();
            }
        }
        return allOk;
    }

    private static String field(Map<String, Object> m, String key, String dflt) {
        if (!m.containsKey(key) || m.get(key) == null) {
            return dflt;
        }
        return String.valueOf(m.get(key));
    }

    // ---------------------------------------------------------------- remove

    public static boolean actionRemove(Context ctx, List<String> names) throws Exception {
        if (!ctx.requireWritable()) {
            return false;
        }
        Plugins.Listing listing = Plugins.list(ctx.remote, ctx.drive);
        if (listing.plugins().isEmpty()) {
            Ui.say("  " + Ui.S.dim(Ui.S.info) + " Nothing to remove on " + ctx.drive.name + ".");
            return true;
        }
        List<Plugins.InstalledPlugin> targets = new ArrayList<>();
        if (!names.isEmpty()) {
            for (String n : names) {
                targets.add(Plugins.find(listing.plugins(), n));
            }
        } else if (Ui.interactive()) {
            Ui.say();
            Plugins.show(listing.plugins(), ctx.drive, listing.exists());
            Ui.say();
            List<String[]> items = new ArrayList<>();
            for (Plugins.InstalledPlugin p : listing.plugins()) {
                items.add(new String[]{p.folder, ""});
            }
            Integer idx = Ui.choose("Remove which plugin?", items, true, "back");
            if (idx == null) {
                return true;
            }
            targets.add(listing.plugins().get(idx));
        } else {
            throw new UsageError("Say which plugin to remove.");
        }
        // Several plugins selected: one confirmation for the whole list instead of one per
        // plugin — the names go to the report first so both the dialog and the log show them.
        if (targets.size() > 1) {
            Ui.say();
            Ui.say("  " + Ui.S.dim("Selected for removal:"));
            for (Plugins.InstalledPlugin p : targets) {
                Ui.say("    " + Ui.S.arrow + " " + p.folder);
            }
            if (!Ui.confirm("Delete these " + targets.size() + " plugins from " + ctx.drive.name
                    + "? This cannot be undone.", false, ctx.assumeYes)) {
                Ui.say("  " + Ui.S.warnc(Ui.S.warn) + " Kept all " + targets.size()
                        + " selected (pass --yes to skip this question).");
                return Ui.interactive(); // a scripted run that was not told --yes has not done its job
            }
        }
        boolean ok = true;
        for (Plugins.InstalledPlugin p : targets) {
            if (!Plugins.safeFolderName(p.folder)) {
                Ui.say("  " + Ui.S.badc(Ui.S.bad) + " Refusing to touch '" + p.folder + "'");
                ok = false;
                continue;
            }
            if (targets.size() == 1 && !Ui.confirm("Delete " + Ui.S.bold(p.folder) + " from " + ctx.drive.name
                    + "? This cannot be undone.", false, ctx.assumeYes)) {
                Ui.say("  " + Ui.S.warnc(Ui.S.warn) + " Kept " + p.folder
                        + " (pass --yes to skip this question).");
                ok = ok && Ui.interactive(); // a scripted run that was not told --yes has not done its job
                continue;
            }
            AtomicBoolean done = new AtomicBoolean(true);
            Ui.step("Removing " + p.folder, st -> {
                Remote.Result r = ctx.remote.text("rm -rf "
                        + Util.shlexQuote(ctx.drive.pluginsPath() + "/" + p.folder) + " && sync", 60);
                if (r.rc() != 0) {
                    done.set(false);
                    st.fail(r.err().isEmpty() ? "rm failed" : r.err());
                } else {
                    st.done();
                }
            });
            ok = ok && done.get();
        }
        return ok;
    }

    // ---------------------------------------------------------------- export

    public static boolean actionExport(Context ctx, List<String> names, boolean exportAll, String outDir)
            throws Exception {
        Plugins.Listing listing = Plugins.list(ctx.remote, ctx.drive);
        if (listing.plugins().isEmpty()) {
            Ui.say("  " + Ui.S.dim(Ui.S.info) + " Nothing to export on " + ctx.drive.name + ".");
            return true;
        }
        List<Plugins.InstalledPlugin> targets = new ArrayList<>();
        String out = outDir;
        if (exportAll) {
            targets.addAll(listing.plugins());
        } else if (!names.isEmpty()) {
            for (String n : names) {
                targets.add(Plugins.find(listing.plugins(), n));
            }
        } else if (Ui.interactive()) {
            Ui.say();
            Plugins.show(listing.plugins(), ctx.drive, listing.exists());
            Ui.say();
            List<String[]> items = new ArrayList<>();
            for (Plugins.InstalledPlugin p : listing.plugins()) {
                items.add(new String[]{p.folder, ""});
            }
            items.add(new String[]{"All of them", ""});
            Integer idx = Ui.choose("Export which plugin?", items, true, "back");
            if (idx == null) {
                return true;
            }
            targets.addAll(idx == listing.plugins().size()
                    ? listing.plugins()
                    : List.of(listing.plugins().get(idx)));
            out = Ui.ask("Save zip files to",
                    out != null ? out : Paths.get(System.getProperty("user.dir")).toString(), false);
        } else {
            throw new UsageError("Say which plugin to export, or use --all.");
        }
        Path outPath = Paths.get(Util.expandUser(out != null ? out : System.getProperty("user.dir")));
        Files.createDirectories(outPath);
        boolean ok = true;
        for (Plugins.InstalledPlugin p : targets) {
            if (!Plugins.safeFolderName(p.folder)) {
                ok = false;
                continue;
            }
            AtomicBoolean wrote = new AtomicBoolean(true);
            Ui.step("Exporting " + p.folder, st -> {
                Remote.Result r = ctx.remote.run("tar -cf - -C " + Util.shlexQuote(ctx.drive.pluginsPath())
                        + " " + Util.shlexQuote(p.folder), null, 900);
                if (r.rc() != 0 || r.out().length == 0) {
                    wrote.set(false);
                    st.fail(r.err().isEmpty() ? "tar failed" : r.err());
                    return;
                }
                String version = field(p.manifest, "version", "").trim();
                String fname = Util.legalFilename(p.folder)
                        + (version.isEmpty() ? "" : " " + version) + ".zip";
                Path dest = Util.uniquePath(outPath.resolve(fname));
                try {
                    int files = tarToZip(r.out(), dest);
                    st.done(files + " files " + Ui.S.arrow + " " + dest);
                } catch (IOException | RuntimeException e) {
                    wrote.set(false);
                    st.fail(e.getMessage() != null ? e.getMessage() : "zip failed");
                }
            });
            ok = ok && wrote.get();
        }
        return ok;
    }

    /** Turn the tar the MPC sent into a zip file (the export half of action_export). */
    static int tarToZip(byte[] tarBytes, java.nio.file.Path dest) throws IOException {
        List<Tar.Entry> entries = Tar.read(tarBytes);
        int files = 0;
        Map<String, Integer> modes = new java.util.LinkedHashMap<>();
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(dest))) {
            for (Tar.Entry m : entries) {
                String arc = m.name;
                while (arc.startsWith("./")) {
                    arc = arc.substring(2);
                }
                if (arc.isEmpty()) {
                    continue;
                }
                String[] parts = arc.split("/", -1);
                boolean bad = false;
                for (String part : parts) {
                    if (part.equals("..") || Util.JUNK_NAMES.contains(part) || part.startsWith("._")) {
                        bad = true;
                        break;
                    }
                }
                if (bad) {
                    continue;
                }
                java.util.zip.ZipEntry zi;
                if (m.dir) {
                    zi = new java.util.zip.ZipEntry(arc.endsWith("/") ? arc : arc + "/");
                    zi.setTime(System.currentTimeMillis());
                    z.putNextEntry(zi);
                    z.write(new byte[0]);
                    z.closeEntry();
                } else {
                    zi = new java.util.zip.ZipEntry(arc);
                    zi.setTime(m.mtime * 1000);
                    int perm = (m.mode & 0100) != 0 ? 0755 : 0644;
                    modes.put(arc, 0100000 | perm);
                    z.putNextEntry(zi);
                    z.write(m.data == null ? new byte[0] : m.data);
                    z.closeEntry();
                    files++;
                }
            }
        }
        // the executable bits the MPC reported are part of the archive, like Python's zipfile
        Util.applyUnixModes(dest, modes);
        return files;
    }

    // ---------------------------------------------------------------- restart

    /** Restart the MPC application so it rescans VST2 plugins. Unsaved data is lost. */
    public static boolean actionRestart(Context ctx, boolean afterInstall) throws Exception {
        Ui.say();
        List<String> rows = new ArrayList<>();
        rows.add(Ui.S.warnc(Ui.S.warn) + " " + Ui.S.bold("Any unsaved data will be lost."));
        rows.add("");
        rows.add(afterInstall
                ? "The MPC only looks for new VST2 plugins on an internal drive when its app starts."
                : "The MPC app will close and start again, which makes it rescan its VST2 plugins.");
        if (afterInstall) {
            rows.add("Restarting it now makes it rescan automatically, so your new plugin appears without you doing anything else.");
        }
        rows.add("Save your project on the MPC first. Audio stops while it restarts (about a minute).");
        Ui.panel(afterInstall ? "Restart the MPC to rescan VSTs?" : "Restart the MPC application?", rows, "warn");
        Ui.say();
        String question = afterInstall ? "Restart the MPC now so it rescans VSTs?" : "Restart the MPC now?";
        if (!Ui.confirm(question, false, ctx.assumeYes)) {
            Ui.say("  " + Ui.S.warnc(Ui.S.warn) + " Not restarted"
                    + (Ui.interactive() ? "" : " (pass --yes to confirm)") + ".");
            return Ui.interactive();
        }
        String unit = Util.shlexQuote(MPC_SERVICE);
        Remote.Result probe = ctx.remote.text("systemctl cat " + unit + " >/dev/null 2>&1", 60);
        if (probe.rc() != 0) {
            Ui.say("  " + Ui.S.badc(Ui.S.bad) + " This MPC has no '" + MPC_SERVICE
                    + "' service, so I can't restart it. Power-cycle it from the MPC instead.");
            return false;
        }
        AtomicBoolean running = new AtomicBoolean(true);
        Ui.step("Restarting the MPC application", st -> {
            Remote.Result r = ctx.remote.text("systemctl restart " + unit, 120);
            if (r.rc() != 0) {
                running.set(false);
                st.fail(lastLine(r.err()) != null ? lastLine(r.err())
                        : "systemctl failed (exit " + r.rc() + ")");
                return;
            }
            String state = "";
            for (int i = 0; i < 60; i++) {
                Remote.Result s = ctx.remote.text("systemctl is-active " + unit, 30);
                state = s.outText().strip();
                if (state.equals("active")) {
                    break;
                }
                Thread.sleep(1000);
            }
            if (!state.equals("active")) {
                running.set(false);
                st.fail("service is '" + (state.isEmpty() ? "unknown" : state) + "'; check the MPC screen");
                return;
            }
            st.done("running again");
        });
        if (!running.get()) {
            return false;
        }
        Ui.say("  " + Ui.S.dim(Ui.S.info)
                + " The MPC takes a little longer to finish loading. Your plugins appear under External Plugins.");
        Ui.say();
        return true;
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

    // ---------------------------------------------------------------- doctor

    public static boolean actionDoctor(Context ctx, List<Drive> drives) throws Exception {
        int[] problems = {0};
        List<String> rows = new ArrayList<>();
        row(rows, "ok", "SSH server reachable and login works", "");
        if ("yes".equals(ctx.info.get("devmode.file"))) {
            row(rows, "ok", "Developer mode marker present", "");
        } else {
            row(rows, "warn", "No developer-mode marker file found",
                    "Fine if SSH works; otherwise repeat the developer-mode tap sequence on the MPC.");
        }
        if (ctx.arch != null) {
            row(rows, "ok", "Processor: " + ctx.info.getOrDefault("arch", "")
                    + " (plugins need the " + ctx.arch + " build)", "");
        } else {
            row(rows, "warn", "Could not tell the processor type", "");
        }
        if (drives.isEmpty()) {
            row(rows, "bad", "No drives mounted under /media or /nvme",
                    "Insert a USB drive or SD card, or check the internal storage is mounted.");
            problems[0]++;
        }
        for (Drive d : drives) {
            Plugins.Listing listing = Plugins.list(ctx.remote, d);
            String state = "ok";
            List<String> notes = new ArrayList<>();
            if (!d.writable()) {
                state = "bad";
                problems[0]++;
                notes.add("read-only");
            }
            if (d.noexec()) {
                if (state.equals("ok")) {
                    state = "warn";
                }
                notes.add("mounted noexec: plugins may not load from here");
            }
            if (d.free != null && d.free < 20L * 1024 * 1024) {
                if (state.equals("ok")) {
                    state = "warn";
                }
                notes.add("under 20 MB free");
            }
            notes.add(Drive.PLUGINS_DIR + " folder " + (listing.exists()
                    ? "with " + listing.plugins().size() + " plugin" + (listing.plugins().size() == 1 ? "" : "s")
                    : "not created yet"));
            row(rows, state, d.name + ": " + String.join(", ", notes), "");
        }
        row(rows, "warn", "Enhancements and External Plugin Support switches cannot be read remotely",
                "On the MPC: Preferences → Enhancements (Beta) → Enable Enhancements, then External Plugin Support.");
        Ui.say();
        Ui.panel("MPC health check", rows, problems[0] > 0 ? "bad" : "good");
        Ui.say();
        return problems[0] == 0;
    }

    private static void row(List<String> rows, String state, String text, String hint) {
        String mark = switch (state) {
            case "ok" -> Ui.S.good(Ui.S.ok);
            case "warn" -> Ui.S.warnc(Ui.S.warn);
            default -> Ui.S.badc(Ui.S.bad);
        };
        rows.add(mark + " " + text);
        if (!hint.isEmpty()) {
            rows.add("    " + Ui.S.dim(hint));
        }
    }

    // ---------------------------------------------------------------- import (convert first)

    /**
     * Convert community mpc-vst-plugins packages into SDK plugin folders (action_convert).
     * Returns the folders to install, or null on failure.
     */
    public static List<String> actionConvert(List<String> sources, String outDir, boolean makeZip, boolean force)
            throws java.io.IOException {
        Ui.say();
        Step st = new Step("Converting " + sources.size() + " package" + (sources.size() == 1 ? "" : "s")).start();
        List<LegacyConvert.Result> results;
        try {
            results = LegacyConvert.convert(sources, outDir, makeZip, force);
        } catch (LegacyConvert.ConvertError e) {
            st.fail(e.getMessage());
            return null;
        }
        st.done(results.size() + " plugin" + (results.size() == 1 ? "" : "s"));
        List<String> folders = new ArrayList<>();
        for (LegacyConvert.Result r : results) {
            Map<String, Object> m = r.manifest;
            Ui.say("  " + Ui.S.good(Ui.S.ok) + " " + Ui.S.bold(String.valueOf(m.get("name"))) + " "
                    + m.get("version") + "  " + Ui.S.dim("saved to " + r.folder));
            if (r.zip != null) {
                Ui.say("    " + Ui.S.dim(Ui.S.arrow) + " " + Ui.S.dim(r.zip));
            }
            for (String w : r.warnings) {
                Ui.say("    " + Ui.S.warnc(Ui.S.warn) + " " + w);
            }
            folders.add(r.folder);
        }
        Ui.say();
        return folders;
    }
}

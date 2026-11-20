package com.dreamstatic.mpcremote;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reading and displaying the plugins installed in a drive's "VST2 Plugins" folder. */
public final class Plugins {

    /** Sentinels used by the remote listing script. */
    static final String MISSING = "@@MISSING";
    static final String PLUGIN_MARK = "@@PLUGIN ";
    static final String SIZE_MARK = "@@SIZE ";
    static final String END_MARK = "@@END";

    static final String LIST_SCRIPT = """
            cd %s 2>/dev/null || { echo '@@MISSING'; exit 0; }
            for d in */; do
              [ -d "$d" ] || continue
              d=${d%%/}
              echo "@@PLUGIN $d"
              cat "$d/plugin.json" 2>/dev/null
              echo
              echo "@@SIZE $(du -sk "$d" 2>/dev/null | cut -f1)"
            done
            echo '@@END'
            """;

    private Plugins() {
    }

    /** One folder under "VST2 Plugins" with its parsed manifest. */
    public static final class InstalledPlugin {
        public final String folder;
        public final Map<String, Object> manifest;
        public final long size; // bytes

        InstalledPlugin(String folder, Map<String, Object> manifest, long sizeKb) {
            this.folder = folder;
            this.manifest = manifest == null ? Map.of() : manifest;
            this.size = sizeKb * 1024;
        }

        public String name() {
            String n = Json.str(manifest, "name");
            return n != null && !n.isEmpty() ? n : folder;
        }

        public String label() {
            return folder;
        }
    }

    /** Result of a listing: does the folder exist at all, and what is in it. */
    public record Listing(boolean exists, List<InstalledPlugin> plugins) {
    }

    public static Listing list(Remote remote, Drive drive) {
        String script = LIST_SCRIPT.replace("%s", Util.shlexQuote(drive.pluginsPath()));
        Remote.Result r = remote.text(script, 60);
        String out = r.outText();
        if (out.contains(MISSING)) {
            return new Listing(false, new ArrayList<>());
        }
        List<InstalledPlugin> plugins = new ArrayList<>();
        String cur = null;
        List<String> body = new ArrayList<>();
        long size = 0;
        for (String line : out.split("\\R")) {
            if (line.startsWith(PLUGIN_MARK)) {
                cur = line.substring(PLUGIN_MARK.length());
                body = new ArrayList<>();
                size = 0;
            } else if (line.startsWith(SIZE_MARK) && cur != null) {
                String sizeStr = line.substring(SIZE_MARK.length()).trim();
                try {
                    size = sizeStr.isEmpty() ? 0 : Long.parseLong(sizeStr);
                } catch (NumberFormatException e) {
                    size = 0;
                }
                Map<String, Object> manifest = null;
                try {
                    manifest = Json.parseObject(String.join("\n", body));
                } catch (RuntimeException ignored) {
                    // no valid plugin.json
                }
                plugins.add(new InstalledPlugin(cur, manifest, size));
                cur = null;
            } else if (cur != null) {
                body.add(line);
            }
        }
        return new Listing(true, plugins);
    }

    /** The installed-plugins table (or the "nothing there yet" notes). */
    public static void show(List<InstalledPlugin> plugins, Drive drive, boolean exists) {
        if (!exists) {
            Ui.say("  " + Ui.S.warnc(Ui.S.warn) + " There is no \"" + Drive.PLUGINS_DIR + "\" folder on "
                    + drive.name + " yet (it is created when you add a plugin).");
            return;
        }
        if (plugins.isEmpty()) {
            Ui.say("  " + Ui.S.dim(Ui.S.info) + " No plugins installed on " + drive.name + ".");
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (InstalledPlugin p : plugins) {
            Map<String, Object> m = p.manifest;
            if (m.isEmpty()) {
                rows.add(List.of(Ui.S.warnc(p.folder), "", "", "", Ui.S.warnc("no valid plugin.json"),
                        Util.humanSize(p.size)));
                continue;
            }
            List<String> archs = new ArrayList<>();
            if (m.get("file64") != null) {
                archs.add("arm64");
            }
            if (m.get("file32") != null) {
                archs.add("arm32");
            }
            rows.add(List.of(
                    Ui.S.bold(p.folder),
                    nvl(Json.str(m, "manufacturer")),
                    nvl(Json.str(m, "version")),
                    nvl(Json.str(m, "kind")),
                    String.join("/", archs),
                    Util.humanSize(p.size)));
        }
        Ui.table(List.of("Folder", "Manufacturer", "Ver", "Kind", "Builds", "Size"), rows);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** Find an installed plugin by folder or display name (exact, then substring). */
    public static InstalledPlugin find(List<InstalledPlugin> plugins, String query) {
        String q = query.toLowerCase();
        List<InstalledPlugin> exact = new ArrayList<>();
        for (InstalledPlugin p : plugins) {
            if (q.equals(p.folder.toLowerCase()) || q.equals(p.name().toLowerCase())) {
                exact.add(p);
            }
        }
        if (exact.size() == 1) {
            return exact.get(0);
        }
        List<InstalledPlugin> part = new ArrayList<>();
        for (InstalledPlugin p : plugins) {
            if (p.folder.toLowerCase().contains(q) || p.name().toLowerCase().contains(q)) {
                part.add(p);
            }
        }
        if (part.size() == 1) {
            return part.get(0);
        }
        if (part.size() > 1) {
            throw new UsageError("'" + query + "' matches several plugins: " + joinFolders(part));
        }
        throw new UsageError("No installed plugin matches '" + query + "'.");
    }

    private static String joinFolders(List<InstalledPlugin> plugins) {
        List<String> names = new ArrayList<>();
        for (InstalledPlugin p : plugins) {
            names.add(p.folder);
        }
        return String.join(", ", names);
    }

    /** Refuse to touch anything that is not a plain, safe folder name (safe_folder_name). */
    public static boolean safeFolderName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && !name.contains("/") && name.indexOf((char) 0) < 0 && !name.startsWith("-");
    }
}

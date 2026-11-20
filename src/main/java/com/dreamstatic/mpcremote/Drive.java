package com.dreamstatic.mpcremote;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A drive on the MPC: how it is mounted, whether it is usable, and where plugins live on it. */
public class Drive {

    static final String PLUGINS_DIR = "VST2 Plugins";
    static final String NVME_MOUNT = "/nvme";
    private static final String[] INTERNAL_DRIVE_PREFIXES = {"az01-internal", "acvs-synths", "acvs-content", "nvme"};

    public final String device;
    public final String mount;
    public final String fstype;
    public final List<String> options;
    public final String name;
    public Long total;
    public Long free;

    public Drive(String device, String mount, String fstype, String options) {
        this.device = device;
        this.mount = mount;
        this.fstype = fstype;
        this.options = List.of(options.split(","));
        this.name = Util.baseName(mount.endsWith("/") && mount.length() > 1 ? mount.substring(0, mount.length() - 1) : mount);
        this.total = null;
        this.free = null;
    }

    /** The internal user-content storage: the best place for plugins (survives removing cards). */
    public boolean recommended() {
        return name.startsWith("az01-internal-sd") || mount.equals(NVME_MOUNT);
    }

    public boolean internal() {
        for (String prefix : INTERNAL_DRIVE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean writable() {
        return options.contains("rw");
    }

    public boolean noexec() {
        return options.contains("noexec");
    }

    public String pluginsPath() {
        String m = mount;
        while (m.length() > 1 && m.endsWith("/")) {
            m = m.substring(0, m.length() - 1);
        }
        return m + "/" + PLUGINS_DIR;
    }

    private String mark() {
        return recommended() ? Ui.S.good(Ui.S.unicode ? "●" : "*") : " ";
    }

    public String describe() {
        List<String> bits = new ArrayList<>();
        bits.add(internal() ? "internal" : "removable");
        bits.add(fstype);
        if (free != null && total != null) {
            bits.add(Util.humanSize(free) + " free of " + Util.humanSize(total));
        }
        if (!writable()) {
            bits.add(Ui.S.badc("read-only"));
        }
        if (noexec()) {
            bits.add(Ui.S.warnc("noexec"));
        }
        String sep = Ui.S.dim(Ui.S.unicode ? " · " : " - ");
        List<String> out = new ArrayList<>();
        for (String b : bits) {
            // pre-painted bits (read-only, noexec) keep their colour; plain ones are dimmed
            out.add(b.indexOf((char) 27) >= 0 ? b : Ui.S.dim(b));
        }
        return String.join(sep, out);
    }

    // ---------------------------------------------------------------- discovery

    /** Parse /proc/mounts and df -Pk from the MPC (discover_drives). */
    public static List<Drive> discover(Remote remote) {
        Remote.Result r = remote.text("cat /proc/mounts; echo '@@DF'; df -Pk 2>/dev/null", 60);
        String out = r.outText();
        String mounts;
        String df;
        if (out.contains("@@DF")) {
            int at = out.indexOf("@@DF");
            mounts = out.substring(0, at);
            df = out.substring(at + "@@DF".length());
        } else {
            mounts = out;
            df = "";
        }
        List<Drive> drives = new ArrayList<>();
        for (String line : mounts.split("\\R")) {
            String[] parts = line.isBlank() ? new String[0] : line.split("\\s+");
            if (parts.length < 4) {
                continue;
            }
            String mount = Util.unescapeMount(parts[1]);
            if (parts[2].equals("tmpfs") || parts[2].equals("proc") || parts[2].equals("sysfs")
                    || parts[2].equals("devtmpfs") || parts[2].equals("devpts")) {
                continue;
            }
            if (mount.equals(NVME_MOUNT) || mount.startsWith("/media/")) {
                drives.add(new Drive(parts[0], mount, parts[2], parts[3]));
            }
        }
        String[] dfLines = df.split("\\R");
        for (int i = 1; i < dfLines.length; i++) {
            String line = dfLines[i];
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 6) {
                continue;
            }
            StringBuilder mount = new StringBuilder(parts[5]);
            for (int j = 6; j < parts.length; j++) {
                mount.append(' ').append(parts[j]);
            }
            for (Drive d : drives) {
                if (d.mount.equals(mount.toString())) {
                    try {
                        d.total = Long.parseLong(parts[1]) * 1024;
                        d.free = Long.parseLong(parts[3]) * 1024;
                    } catch (NumberFormatException ignored) {
                        // leave the sizes unknown
                    }
                }
            }
        }
        drives.sort(Comparator.comparing((Drive d) -> !d.internal()).thenComparing(d -> d.mount));
        return drives;
    }

    /** The "Drives found" panel. */
    public static void show(List<Drive> drives) {
        int numw = Integer.toString(drives.size()).length();
        int namew = 0;
        for (Drive d : drives) {
            namew = Math.max(namew, Util.visibleLen(d.name));
        }
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < drives.size(); i++) {
            Drive d = drives.get(i);
            rows.add(Ui.S.accent(Util.pad(Integer.toString(i + 1), numw)) + "  " + d.mark() + " "
                    + Ui.S.bold(Util.pad(d.name, namew)) + "  " + d.describe());
        }
        boolean anyRecommended = drives.stream().anyMatch(Drive::recommended);
        if (anyRecommended) {
            rows.add(Ui.S.good(Ui.S.unicode ? "●" : "*") + " " + Ui.S.dim("recommended: internal storage"));
        }
        Ui.panel("Drives found", rows, "amber");
    }

    /**
     * Choose the drive to manage: by number, mount point or name; the single drive when there
     * is only one; an interactive choice otherwise.
     */
    public static Drive pick(List<Drive> drives, String wanted, boolean assumeYes) {
        if (drives.isEmpty()) {
            return null;
        }
        if (wanted != null && !wanted.isEmpty()) {
            String w = wanted.toLowerCase();
            for (int i = 0; i < drives.size(); i++) {
                Drive d = drives.get(i);
                if (w.equals(Integer.toString(i + 1)) || w.equals(d.mount.toLowerCase()) || w.equals(d.name.toLowerCase())) {
                    return d;
                }
            }
            List<Drive> matches = new ArrayList<>();
            for (Drive d : drives) {
                if (d.name.toLowerCase().contains(w) || d.mount.toLowerCase().contains(w)) {
                    matches.add(d);
                }
            }
            if (matches.size() == 1) {
                return matches.get(0);
            }
            throw new UsageError("No single drive matches '" + wanted + "'. Drives: " + indexedList(drives));
        }
        if (drives.size() == 1) {
            return drives.get(0);
        }
        if (!Ui.interactive()) {
            throw new UsageError("Several drives found; choose one with --drive (" + indexedList(drives) + ")");
        }
        Ui.say();
        List<String[]> items = new ArrayList<>();
        for (Drive d : drives) {
            items.add(new String[]{d.mark() + " " + d.name, d.describe()});
        }
        Integer idx = Ui.choose("Which drive do you want to manage?", items, false, "");
        return drives.get(idx);
    }

    private static String indexedList(List<Drive> drives) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < drives.size(); i++) {
            parts.add((i + 1) + "=" + drives.get(i).name);
        }
        return String.join(", ", parts);
    }

    /** Bytes free on a drive right now (the figure from discovery goes stale). Null if unknown. */
    public static Long free(Remote remote, Drive drive) {
        Remote.Result r = remote.text("df -Pk " + Util.shlexQuote(drive.mount) + " | tail -n 1", 60);
        try {
            String[] parts = r.outText().trim().split("\\s+");
            drive.free = Long.parseLong(parts[3]) * 1024;
        } catch (RuntimeException ignored) {
            // could not tell
        }
        return drive.free;
    }
}

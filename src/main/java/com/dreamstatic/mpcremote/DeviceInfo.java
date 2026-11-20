package com.dreamstatic.mpcremote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Device identity: the shell probe, its parsing, and the "Your MPC" panel rows. */
public final class DeviceInfo {

    /** ARM "CPU part" ids as shown in /proc/cpuinfo. */
    private static final Map<String, String> CPU_PARTS = Map.ofEntries(
            Map.entry("0xc07", "Cortex-A7"), Map.entry("0xc0f", "Cortex-A15"),
            Map.entry("0xc0e", "Cortex-A17"), Map.entry("0xd03", "Cortex-A53"),
            Map.entry("0xd04", "Cortex-A35"), Map.entry("0xd05", "Cortex-A55"),
            Map.entry("0xd07", "Cortex-A57"), Map.entry("0xd08", "Cortex-A72"),
            Map.entry("0xd09", "Cortex-A73"), Map.entry("0xd0a", "Cortex-A75"),
            Map.entry("0xd0b", "Cortex-A76"), Map.entry("0xd0d", "Cortex-A77"));

    static final String INFO_SCRIPT = """

            B=/sys/firmware/devicetree/base
            for f in model inmusic,product-code serial-number compatible; do
              printf 'dt.%s=' "$f"
              if [ -r "$B/$f" ]; then tr '\\000' ',' < "$B/$f"; fi
              echo
            done
            printf 'app=%s\\n' "$(head -n 1 /tmp/com.akaipro.mpc.version 2>/dev/null)"
            printf 'uname=%s\\n' "$(uname -srm 2>/dev/null)"
            printf 'arch=%s\\n' "$(uname -m 2>/dev/null)"
            printf 'hostname=%s\\n' "$(hostname 2>/dev/null)"
            printf 'uptime=%s\\n' "$(cut -d' ' -f1 /proc/uptime 2>/dev/null)"
            if [ -r /etc/os-release ]; then sed 's/^/os./' /etc/os-release; fi
            if [ -e /data/dev-mode ]; then echo devmode.file=yes; fi
            for f in /etc/az0x-release /etc/az01-release; do
              if [ -r "$f" ]; then sed -n 's/^\\(AZ0[X1]_VERSION\\)=/firmware=/p' "$f" | head -n 1; fi
            done
            printf 'cpu.count=%s\\n' "$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)"
            sed -n 's/^model name[[:space:]]*: */cpu.model=/p;s/^Hardware[[:space:]]*: */cpu.hardware=/p;s/^CPU part[[:space:]]*: */cpu.part=/p' /proc/cpuinfo 2>/dev/null | sort -u
            printf 'cpu.maxkhz=%s\\n' "$(cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq 2>/dev/null)"
            """;

    private DeviceInfo() {
    }

    /** Run the probe script and parse its key=value lines (first value wins). */
    public static Map<String, String> fetch(Remote remote) {
        Remote.Result r = remote.text(INFO_SCRIPT, 60);
        Map<String, String> info = new LinkedHashMap<>();
        for (String line : r.outText().split("\\R")) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = line.substring(0, eq).strip();
            if (info.containsKey(k)) {
                continue; // first value wins (cpu.part repeats per core type)
            }
            String v = line.substring(eq + 1);
            v = v.strip();
            v = stripSet(v, ",");
            v = v.strip();
            v = stripSet(v, "\"");
            v = stripSet(v, "'");
            info.put(k, v);
        }
        return info;
    }

    /** Python's str.strip(chars): drop any of the listed characters from both ends. */
    private static String stripSet(String s, String chars) {
        int a = 0;
        int b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    /** "Cortex-A55 ×4 @ 1.8 GHz". Falls back to whatever the kernel reports. */
    public static String cpuSummary(Map<String, String> info) {
        String part = info.get("cpu.part");
        String name = CPU_PARTS.getOrDefault(part == null ? "" : part.toLowerCase(), null);
        if (name == null) {
            name = info.getOrDefault("cpu.model", "");
        }
        if (name.isEmpty()) {
            name = info.getOrDefault("cpu.hardware", "");
        }
        List<String> parts = new ArrayList<>();
        if (!name.isEmpty()) {
            String count = info.getOrDefault("cpu.count", "0");
            if (!count.isEmpty() && !count.equals("0") && !count.equals("1")) {
                parts.add(name + (Ui.S.unicode ? " ×" + count : " x" + count));
            } else {
                parts.add(name);
            }
        }
        String maxkhz = info.get("cpu.maxkhz");
        if (maxkhz != null && !maxkhz.isEmpty()) {
            try {
                parts.add(String.format("@ %.1f GHz", Double.parseDouble(maxkhz) / 1e6));
            } catch (NumberFormatException ignored) {
                // not reported in a form we understand
            }
        }
        String arch = info.getOrDefault("arch", "");
        String text = String.join(" ", parts);
        if (text.isEmpty()) {
            text = arch;
        }
        if (!arch.isEmpty() && !text.contains(arch)) {
            text += " (" + arch + ")";
        }
        return text;
    }

    /** "arm64" / "arm32", or null when the processor type is unknown. */
    public static String deviceArch(Map<String, String> info) {
        String a = info.getOrDefault("arch", "").toLowerCase();
        if (a.equals("aarch64") || a.equals("arm64")) {
            return "arm64";
        }
        if (a.startsWith("arm")) {
            return "arm32";
        }
        return null;
    }

    /** The rows of the "Your MPC" panel. */
    public static List<String> infoRows(String host, Map<String, String> info) {
        String model = info.getOrDefault("dt.model", "");
        String code = info.getOrDefault("dt.inmusic,product-code", "");
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[]{"Address", host});
        pairs.add(new String[]{"Model", model.isEmpty() ? Ui.S.dim("(not reported)") : Ui.S.bold(model)});
        if (!code.isEmpty()) {
            pairs.add(new String[]{"Hardware code", code});
        }
        String serial = info.getOrDefault("dt.serial-number", "");
        pairs.add(new String[]{"Serial number", serial.isEmpty() ? Ui.S.dim("(not reported)") : serial});
        String hostname = info.getOrDefault("hostname", "");
        if (!hostname.isEmpty()) {
            pairs.add(new String[]{"Hostname", hostname});
        }
        String app = info.getOrDefault("app", "");
        pairs.add(new String[]{"MPC version",
                app.isEmpty() ? Ui.S.dim("(not reported; is the MPC app running?)") : Ui.S.bold(app)});
        String firmware = info.getOrDefault("firmware", "");
        pairs.add(new String[]{"Firmware version",
                firmware.isEmpty() ? Ui.S.dim("(not reported)") : Ui.S.bold(firmware)});
        String cpu = cpuSummary(info);
        if (!cpu.isEmpty()) {
            pairs.add(new String[]{"CPU", cpu});
        }
        String arch = deviceArch(info);
        if (arch != null) {
            pairs.add(new String[]{"Architecture", arch.equals("arm64") ? "64-bit" : "32-bit"});
        }
        String system = info.getOrDefault("os.PRETTY_NAME", "");
        if (system.isEmpty()) {
            system = info.getOrDefault("os.NAME", "");
        }
        String versionId = info.getOrDefault("os.VERSION_ID", "");
        if (!versionId.isEmpty() && !system.contains(versionId)) {
            system = (system + " " + versionId).strip();
        }
        if (!system.isEmpty()) {
            pairs.add(new String[]{"System", system});
        }
        String uname = info.getOrDefault("uname", "");
        if (!uname.isEmpty()) {
            pairs.add(new String[]{"Kernel", uname});
        }
        String uptime = info.getOrDefault("uptime", "");
        if (!uptime.isEmpty()) {
            try {
                long secs = (long) Double.parseDouble(uptime);
                pairs.add(new String[]{"Uptime", String.format("%dh %02dm", secs / 3600, secs % 3600 / 60)});
            } catch (NumberFormatException ignored) {
                // not a number
            }
        }
        return Ui.kvRows(pairs);
    }
}

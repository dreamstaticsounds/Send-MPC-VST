package com.dreamstatic.mpcremote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small cross-platform helpers shared across the app: shell quoting, path handling,
 * human-readable sizes, terminal width and the "illegal filename" rules of the MPC.
 */
public final class Util {

    /** The exact set of characters the MPC strips when it builds a plugin folder name. */
    public static final String ILLEGAL_FILENAME_CHARS = "\"#@,;:<>*^|?\\/";
    /** Files that must never be packaged or exported. */
    public static final Set<String> JUNK_NAMES = Set.of(".DS_Store", "Thumbs.db", "desktop.ini");

    private static final Pattern ANSI_RE = Pattern.compile("\u001b\\[[0-9;]*m");
    private static final Pattern OCTAL_ESCAPE_RE = Pattern.compile("\\\\([0-7]{3})");

    private static Integer terminalWidth;

    /** Override the detected width (the GUI sizes panels to its report area instead). */
    public static void setTerminalWidth(int width) {
        terminalWidth = width;
    }

    private Util() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ---------------------------------------------------------------- text

    public static String stripAnsi(String s) {
        return ANSI_RE.matcher(s).replaceAll("");
    }

    /** Length of a string as the terminal shows it (ANSI escapes do not count). */
    public static int visibleLen(String s) {
        return stripAnsi(s).length();
    }

    /** Pad with spaces so that the visible part is at least {@code width} columns. */
    public static String pad(String s, int width) {
        return s + " ".repeat(Math.max(0, width - visibleLen(s)));
    }

    /** e.g. "1.2 MB"; mirrors human_size() in mpc_remote.py. */
    public static String humanSize(long n) {
        double d = n;
        for (String unit : new String[]{"B", "KB", "MB", "GB", "TB"}) {
            if (d < 1024 || unit.equals("TB")) {
                if (unit.equals("B")) {
                    return String.format("%d %s", (long) d, unit);
                }
                return String.format("%.1f %s", d, unit);
            }
            d /= 1024;
        }
        return String.format("%.1f TB", d);
    }

    // ---------------------------------------------------------------- shell

    /** Single-quote a string for a POSIX shell (shlex.quote). */
    public static String shlexQuote(String s) {
        if (s == null || s.isEmpty()) {
            return "''";
        }
        if (s.matches("[A-Za-z0-9_@%+=:,./-]+")) {
            return s;
        }
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    /** Split a command line the way shlex.split() does (POSIX mode). */
    public static List<String> shlexSplit(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        boolean escaped = false;
        boolean tokenStarted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                cur.append(c);
                escaped = false;
                tokenStarted = true;
                continue;
            }
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false;
                } else if (c == '\\' && quoteChar == '"') {
                    escaped = true;
                } else {
                    cur.append(c);
                }
                tokenStarted = true;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                tokenStarted = true;
            } else if (c == '\'' || c == '"') {
                inQuotes = true;
                quoteChar = c;
                tokenStarted = true;
            } else if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (tokenStarted) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    tokenStarted = false;
                }
            } else {
                cur.append(c);
                tokenStarted = true;
            }
        }
        if (escaped) {
            cur.append('\\');
        }
        if (tokenStarted) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Expand a leading "~" to the user's home directory (os.path.expanduser). */
    public static String expandUser(String path) {
        if (path == null) {
            return null;
        }
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    /** Find an executable on PATH (like shutil.which), honouring PATHEXT on Windows. */
    public static String which(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        List<String> extensions = new ArrayList<>();
        extensions.add("");
        if (isWindows()) {
            String pathext = System.getenv("PATHEXT");
            if (pathext != null) {
                for (String ext : pathext.split(";")) {
                    if (!ext.isBlank()) {
                        extensions.add(ext);
                    }
                }
            }
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path base = Paths.get(dir);
            for (String ext : extensions) {
                Path candidate = base.resolve(name + ext);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- files

    /** os.path.basename that understands both separators (used on possibly-Windows paths). */
    public static String baseName(String path) {
        String p = path.replace('\\', '/');
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    /** Remove the characters the MPC would strip from a folder name. */
    public static String legalFilename(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (ILLEGAL_FILENAME_CHARS.indexOf(c) < 0) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Set the Unix mode of entries in a zip written by {@link java.util.zip.ZipOutputStream},
     * which has no API for the central directory's external attributes. Python's zipfile writes
     * these (that is what makes unzip restore the executable bit), so we patch them in after
     * the fact: mode is a full st_mode value such as 0100755.
     */
    public static void applyUnixModes(java.nio.file.Path zip, Map<String, Integer> modes) throws IOException {
        if (modes.isEmpty()) {
            return;
        }
        byte[] data = Files.readAllBytes(zip);
        int pos = 0;
        while (pos + 46 <= data.length) {
            int sig = (data[pos] & 0xff) | ((data[pos + 1] & 0xff) << 8)
                    | ((data[pos + 2] & 0xff) << 16) | ((data[pos + 3] & 0xff) << 24);
            if (sig != 0x02014b50) { // not a central-directory record; scan on
                pos++;
                continue;
            }
            int nameLen = (data[pos + 28] & 0xff) | ((data[pos + 29] & 0xff) << 8);
            if (pos + 46 + nameLen > data.length) {
                break;
            }
            String name = new String(data, pos + 46, nameLen, java.nio.charset.StandardCharsets.UTF_8);
            Integer mode = modes.get(name);
            if (mode != null) {
                data[pos + 5] = 3; // version made by: host system = Unix
                int attrs = mode << 16;
                data[pos + 38] = (byte) (attrs & 0xff);
                data[pos + 39] = (byte) ((attrs >> 8) & 0xff);
                data[pos + 40] = (byte) ((attrs >> 16) & 0xff);
                data[pos + 41] = (byte) ((attrs >> 24) & 0xff);
            }
            int extraLen = (data[pos + 30] & 0xff) | ((data[pos + 31] & 0xff) << 8);
            int commentLen = (data[pos + 32] & 0xff) | ((data[pos + 33] & 0xff) << 8);
            pos += 46 + nameLen + extraLen + commentLen;
        }
        Files.write(zip, data);
    }

    /** A path that does not exist yet: name.zip, name-2.zip, name-3.zip, ... */    public static Path uniquePath(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        String ext = dot < 0 ? "" : fileName.substring(dot);
        Path parent = path.getParent();
        for (int n = 2; ; n++) {
            Path candidate = (parent == null ? Paths.get(base) : parent.resolve(base + "-" + n + ext));
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    /** Recursively delete a directory, ignoring errors (shutil.rmtree ignore_errors). */
    public static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** chmod 700/755 where the platform supports it; a no-op on Windows. */
    public static void setExecutable(Path path, boolean executable) {
        try {
            Set<PosixFilePermission> perms = executable
                    ? PosixFilePermissions.fromString("rwx------")
                    : PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX filesystem (Windows)
        }
    }

    /** Read a whole stream into a byte array. */
    public static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Decode bytes as UTF-8, replacing invalid sequences (Python's "replace" error mode). */
    public static String utf8(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- terminal

    /**
     * Usable terminal width: COLUMNS, then stty on a POSIX terminal, then 100
     * (the same fallback shutil.get_terminal_size((100, 24)) uses).
     */
    public static int terminalWidth() {
        if (terminalWidth != null) {
            return terminalWidth;
        }
        int width = 100;
        String columns = System.getenv("COLUMNS");
        if (columns != null) {
            try {
                int c = Integer.parseInt(columns.trim());
                if (c > 0) {
                    width = c;
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        } else if (!isWindows() && System.console() != null) {
            try {
                Process p = new ProcessBuilder("stty", "size")
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    String out = utf8(Util.readFully(p.getInputStream())).trim();
                    String[] parts = out.split("\\s+");
                    if (parts.length == 2) {
                        int c = Integer.parseInt(parts[1]);
                        if (c > 0) {
                            width = c;
                        }
                    }
                } else {
                    p.destroyForcibly();
                }
            } catch (Exception ignored) {
                // keep the default
            }
        }
        terminalWidth = width;
        return width;
    }

    /** Is stdout a capable terminal for unicode symbols? Mirrors Style's encoding check. */
    public static boolean unicodeCapable() {
        String enc = System.getProperty("native.encoding", "");
        if (enc.isEmpty()) {
            enc = System.getProperty("file.encoding", "");
        }
        return enc.toLowerCase(Locale.ROOT).contains("utf");
    }

    /** Decode a \012-style octal escape as found in /proc/mounts. */
    public static String unescapeMount(String path) {
        var m = OCTAL_ESCAPE_RE.matcher(path);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int value = Integer.parseInt(m.group(1), 8);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}

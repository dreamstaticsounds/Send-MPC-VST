package com.dreamstatic.mpcremote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Send MPC VST - manage VST2 plugins on an Akai MPC over the network.
 *
 * <p>Java port of InMusic Software's mpc_remote.py
 * (https://github.com/inmusic-software/mpc-plugin-builder-sdk/blob/main/tools/mpc_remote.py):
 * same commands, same prompts, same remote scripts. Runs on Linux, Windows and macOS and
 * needs nothing but a JRE and the OpenSSH client.</p>
 */
public final class Main {

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAIL = 1;
    private static final int EXIT_SETUP = 2;

    private Main() {
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length > 0 && "--selftest".equals(argv[0])) {
            System.exit(Gui.selftest());
            return;
        }
        if (argv.length == 0) {
            launchGui();
            return;
        }
        Cli args;
        try {
            args = Cli.parse(argv);
        } catch (Cli.HelpRequested h) {
            System.out.println(h.text);
            return;
        } catch (Cli.CliError e) {
            System.err.println(e.prog + ": error: " + e.getMessage());
            System.err.println(Cli.usageText(e.prog));
            System.exit(EXIT_SETUP);
            return;
        }

        enableVt();
        boolean color = System.console() != null
                && !args.noColor
                && System.getenv("NO_COLOR") == null
                && !"dumb".equals(System.getenv("TERM"));
        Ui.S = new Style(color, Util.unicodeCapable());
        Ui.banner("install and manage VST2 plugins on your MPC over the network");

        String host = args.hostArg != null ? args.hostArg : args.address;
        if (host == null || host.isEmpty()) {
            if (!Ui.interactive()) {
                Ui.say("  " + Ui.S.badc(Ui.S.bad) + " Give the MPC's IP address as the first argument.");
                System.exit(EXIT_SETUP);
                return;
            }
            host = Ui.ask("MPC IP address", null, false);
            if (host.isEmpty()) {
                System.exit(EXIT_FAIL);
                return;
            }
        }

        List<String> converted = null;
        if ("import".equals(args.command)) {
            // converting needs no MPC, so do it first and keep the result even if the upload fails
            converted = Actions.actionConvert(args.sources,
                    args.out != null ? args.out : "converted", args.makeZip, args.force);
            if (converted == null) {
                System.exit(EXIT_FAIL);
                return;
            }
        }

        String password = args.password != null ? args.password : System.getenv("MPC_SSH_PASSWORD");
        Remote remote = new Remote(host, args.user, args.port, password);
        Path workdir = Files.createTempDirectory("mpcr-work-");
        int code;
        try {
            code = run(args, host, remote, workdir, converted);
        } catch (UsageError e) {
            Ui.say("  " + Ui.S.badc(Ui.S.bad) + " " + e.getMessage());
            code = EXIT_FAIL;
        } catch (SshError e) {
            e.explain(host, args.port, remote.password != null);
            code = EXIT_SETUP;
        } finally {
            remote.close();
            Util.deleteRecursively(workdir);
        }
        System.exit(code);
    }

    /** Everything after the connection is open; returns the process exit code. */
    private static int run(Cli args, String host, Remote remote, Path workdir, List<String> converted)
            throws Exception {
        try {
            Remote.connect(remote);
        } catch (SshError e) {
            e.explain(host, args.port, remote.password != null);
            return EXIT_SETUP;
        }

        Map<String, String> info;
        Step step = new Step("Reading device details").start();
        try {
            info = DeviceInfo.fetch(remote);
        } catch (RuntimeException e) {
            step.fail(e.getMessage() != null ? e.getMessage() : "");
            throw e;
        }
        step.done(info.getOrDefault("dt.model", ""));
        Ui.say();
        Ui.panel("Your MPC", DeviceInfo.infoRows(host, info), null);
        Ui.say();

        if ("restart".equals(args.command)) { // needs no drive
            Context ctx = new Context(remote, info, null, args.yes, workdir);
            return Actions.actionRestart(ctx, false) ? EXIT_OK : EXIT_FAIL;
        }

        List<Drive> drives;
        Step driveStep = new Step("Looking for drives").start();
        try {
            drives = Drive.discover(remote);
        } catch (RuntimeException e) {
            driveStep.fail(e.getMessage() != null ? e.getMessage() : "");
            throw e;
        }
        driveStep.done(drives.size() + " found");
        if (drives.isEmpty()) {
            Ui.failPanel("No drives found", List.of(
                    "The MPC has nothing mounted under /media (or /nvme).",
                    "Insert a USB drive or SD card (formatted FAT32 / exFAT / ext4) and run this again."
            ), "warn");
            return EXIT_SETUP;
        }
        Ui.say();
        Drive.show(drives);
        Ui.say();
        Drive drive = Drive.pick(drives, args.drive, args.yes);
        Ui.say("  " + Ui.S.good(Ui.S.ok) + " Using " + Ui.S.bold(drive.name) + " " + Ui.S.dim(drive.mount));
        Ui.say();

        Context ctx = new Context(remote, info, drive, args.yes, workdir);
        String cmd = args.command;
        if (cmd == null) {
            if (Ui.interactive()) {
                Actions.actionList(ctx);
                menu(ctx, drives);
                return EXIT_OK;
            }
            Actions.actionList(ctx);
            Ui.say("  " + Ui.S.dim("Pass a command (add, update, remove, export, doctor) to do more."));
            return EXIT_OK;
        }
        switch (cmd) {
            case "info", "drives":
                return EXIT_OK;
            case "list":
                Actions.actionList(ctx);
                return EXIT_OK;
            case "doctor":
                return Actions.actionDoctor(ctx, drives) ? EXIT_OK : EXIT_SETUP;
            case "import":
                return Actions.actionAdd(ctx, converted, args.replaceExisting) ? EXIT_OK : EXIT_FAIL;
            case "add", "update":
                return Actions.actionAdd(ctx, args.sources, "update".equals(cmd)) ? EXIT_OK : EXIT_FAIL;
            case "remove":
                return Actions.actionRemove(ctx, args.names) ? EXIT_OK : EXIT_FAIL;
            case "export":
                return Actions.actionExport(ctx, args.names, args.exportAll, args.out) ? EXIT_OK : EXIT_FAIL;
            default:
                return EXIT_OK;
        }
    }

    // ---------------------------------------------------------------- menu

    private static void menu(Context ctx, List<Drive> drives) throws Exception {
        String[][] entries = {
                {"List plugins", "see what is installed"},
                {"Add a plugin", "from a zip or an SDK project folder"},
                {"Update a plugin", "replace an installed plugin with a newer build"},
                {"Remove a plugin", ""},
                {"Export a plugin", "save it from the MPC as a zip"},
                {"Restart MPC to rescan VSTs", "restarts the MPC app; unsaved data is lost"},
                {"Check this MPC", "diagnostics"},
                {"Switch drive", ctx.drive.name},
        };
        while (true) {
            Ui.say();
            List<String[]> items = new ArrayList<>();
            for (int i = 0; i < entries.length; i++) {
                if (i == entries.length - 1 && drives.size() <= 1) {
                    continue; // only offer "Switch drive" when there is a choice
                }
                items.add(new String[]{entries[i][0], entries[i][1]});
            }
            Integer idx = Ui.choose("What would you like to do?", items, true, "exit");
            Ui.say();
            if (idx == null) {
                Ui.say("  " + Ui.S.dim("Bye!"));
                return;
            }
            try {
                switch (idx) {
                    case 0 -> Actions.actionList(ctx);
                    case 1 -> Actions.actionAdd(ctx, List.of(), false);
                    case 2 -> Actions.actionAdd(ctx, List.of(), true);
                    case 3 -> Actions.actionRemove(ctx, List.of());
                    case 4 -> Actions.actionExport(ctx, List.of(), false, null);
                    case 5 -> Actions.actionRestart(ctx, false);
                    case 6 -> Actions.actionDoctor(ctx, drives);
                    case 7 -> {
                        ctx.drive = Drive.pick(drives, null, false);
                        entries[7][1] = ctx.drive.name;
                    }
                    default -> {
                        // unreachable
                    }
                }
            } catch (UsageError e) {
                Ui.say("  " + Ui.S.badc(Ui.S.bad) + " " + e.getMessage());
            } catch (SshError e) {
                e.explain(ctx.remote.host, ctx.remote.port, ctx.remote.password != null);
            }
        }
    }

    /**
     * Set in the environment of a relaunched copy: a relaunch never relaunches again.
     * This is what makes an infinite chain structurally impossible, whatever the JVM
     * reports about headlessness.
     */
    private static final String RELAUNCH_MARKER = "MPC_REMOTE_RELAUNCHED";

    /**
     * No arguments: open the GUI. Some sessions (notably Wayland terminals and SSH logins)
     * run without {@code DISPLAY} even though an X server socket exists — in that case find
     * the socket and start ourselves again with {@code DISPLAY} set, so the window opens
     * anyway. Only a genuine absence of any display falls back to the CLI hint.
     */
    private static void launchGui() {
        if (java.awt.GraphicsEnvironment.isHeadless()
                && !Boolean.getBoolean("java.awt.headless")) {
            if (System.getenv(RELAUNCH_MARKER) != null) {
                // We already retried with DISPLAY set and this JVM is still headless:
                // it is a headless JRE, which cannot open windows at all.
                System.err.println("This Java cannot open windows (headless JRE — no GUI support).");
                System.err.println("Install a full JDK/JRE, e.g. Temurin: https://adoptium.net");
                System.err.println("or set JAVA_HOME to one. The command line still works: pass");
                System.err.println("the MPC's address (see --help).");
                System.exit(EXIT_SETUP);
                return;
            }
            String display = findXDisplay();
            if (display != null) {
                Integer code = relaunchWithDisplay(display);
                if (code != null) {
                    System.exit(code);
                }
            }
            System.err.println("No display available — pass the MPC's address to run the");
            System.err.println("command-line interface (see --help).");
            System.exit(EXIT_SETUP);
            return;
        }
        if (!canOpenDisplay()) {
            System.err.println("Could not open a window: the X server refused the connection.");
            System.err.println("Check DISPLAY/XAUTHORITY — or, if this is a headless JRE, install");
            System.err.println("a full JDK/JRE (https://adoptium.net). The command line still works:");
            System.err.println("pass the MPC's address (see --help).");
            System.exit(EXIT_SETUP);
            return;
        }
        Theme.install();
        Ui.S = new Style(false, true); // plain text in the report area
        javax.swing.SwingUtilities.invokeLater(() -> new Gui().show());
    }

    /** The lowest X socket under /tmp/.X11-unix (XWayland counts), or null. */
    private static String findXDisplay() {
        if (Util.isWindows()) {
            return null;
        }
        java.nio.file.Path dir = java.nio.file.Paths.get("/tmp/.X11-unix");
        if (!java.nio.file.Files.isDirectory(dir)) {
            return null;
        }
        List<Integer> numbers = new ArrayList<>();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                     java.nio.file.Files.newDirectoryStream(dir, "X*")) {
            for (java.nio.file.Path p : stream) {
                try {
                    numbers.add(Integer.parseInt(p.getFileName().toString().substring(1)));
                } catch (NumberFormatException ignored) {
                    // not an X display socket
                }
            }
        } catch (IOException e) {
            return null;
        }
        if (numbers.isEmpty()) {
            return null;
        }
        Collections.sort(numbers);
        return ":" + numbers.get(0);
    }

    /** Start ourselves again with DISPLAY set; returns the child's exit code, or null. */
    private static Integer relaunchWithDisplay(String display) {
        if (System.getenv(RELAUNCH_MARKER) != null) {
            return null; // one hop only: a relaunched copy never relaunches again
        }
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(ProcessHandle.current().info().command().orElse("java"));
            java.nio.file.Path self = java.nio.file.Paths.get(
                    Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (java.nio.file.Files.isRegularFile(self)
                    && self.getFileName().toString().endsWith(".jar")) {
                cmd.add("-jar");
                cmd.add(self.toString());
            } else {
                cmd.add("-cp");
                cmd.add(System.getProperty("java.class.path"));
                cmd.add(Main.class.getName());
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put(RELAUNCH_MARKER, "1");
            pb.environment().put("DISPLAY", display);
            if (!pb.environment().containsKey("XAUTHORITY")) {
                java.nio.file.Path cookie = java.nio.file.Paths.get(
                        System.getProperty("user.home"), ".Xauthority");
                if (java.nio.file.Files.isRegularFile(cookie)) {
                    pb.environment().put("XAUTHORITY", cookie.toString());
                }
            }
            pb.inheritIO();
            return pb.start().waitFor();
        } catch (Exception e) {
            return null; // could not re-exec: fall through to the plain message
        }
    }

    /** Probe the X connection so a refused display gives a sentence, not a stack trace. */
    private static boolean canOpenDisplay() {
        try {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Switch the Windows console into VT mode so ANSI colours work (os.system("")). */
    private static void enableVt() {
        if (Util.isWindows()) {
            try {
                Process p = new ProcessBuilder("cmd", "/c", "")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort: plain text still works without VT
            }
        }
    }
}

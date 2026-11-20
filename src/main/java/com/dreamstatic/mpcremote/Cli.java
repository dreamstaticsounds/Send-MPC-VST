package com.dreamstatic.mpcremote;

import java.util.ArrayList;
import java.util.List;

/**
 * Command-line parsing, matching mpc_remote.py's argparse setup: the MPC address may be given
 * as the first argument, global options are accepted before or after the command (except
 * --address, which argparse only knows before it), and each command takes its own options.
 */
public final class Cli {

    private static final List<String> COMMANDS = List.of("info", "drives", "list", "doctor", "restart",
            "add", "update", "import", "remove", "export");

    // parsed values
    public String hostArg;          // first positional argument (the MPC address)
    public String address;          // --address / --host (or MPC_ADDRESS / MPC_HOST)
    public String password;         // --password (null = not given; env MPC_SSH_PASSWORD is read later)
    public String user = "root";    // --user
    public int port = 22;           // --port
    public String drive;            // --drive
    public boolean yes;             // --yes / -y
    public boolean noColor;         // --no-color
    public String command;          // subcommand, or null for the interactive menu
    public final List<String> sources = new ArrayList<>(); // add / update / import
    public final List<String> names = new ArrayList<>();   // remove / export
    public String out;              // import / export --out
    public boolean makeZip;         // import --zip
    public boolean replaceExisting; // import --replace
    public boolean force;           // import --force
    public boolean exportAll;       // export --all

    private Cli() {
    }

    /** -h/--help was requested: print this text and exit 0. */
    public static final class HelpRequested extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public final String text;

        HelpRequested(String text) {
            super(text);
            this.text = text;
        }
    }

    /** Bad arguments: print the message plus the usage, exit 2. */
    public static final class CliError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** The parser to blame: "send-mpc-vst" or "send-mpc-vst <command>". */
        public final String prog;

        CliError(String prog, String message) {
            super(message);
            this.prog = prog;
        }
    }

    private static final String MAIN_PROG = "send-mpc-vst";

    /** Which parser reports an error: the main one, or the subparser once a command is seen. */
    private static String subProg(Cli c, boolean seenCommand) {
        return seenCommand ? MAIN_PROG + " " + c.command : MAIN_PROG;
    }

    /** "unrecognized arguments: ..." - always reported by the main parser, like argparse. */
    private static CliError unrecognized(String token, List<String> args, int nextIndex) {
        StringBuilder msg = new StringBuilder("unrecognized arguments: ").append(token);
        if (nextIndex < args.size() && !args.get(nextIndex).startsWith("-")) {
            msg.append(' ').append(args.get(nextIndex));
        }
        return new CliError(MAIN_PROG, msg.toString());
    }

    private static CliError invalidChoice(String choice) {
        return new CliError(MAIN_PROG, "argument command: invalid choice: '" + choice
                + "' (choose from " + String.join(", ", COMMANDS) + ")");
    }

    /** The short usage line argparse prints after an error. */
    public static String usageText(String prog) {
        if (prog.equals(MAIN_PROG)) {
            return "usage: send-mpc-vst [-h] [--address ADDRESS] [--password PW] [--user NAME] [--port N]"
                    + " [--drive D] [--yes] [--no-color]"
                    + " {info,drives,list,doctor,restart,add,update,import,remove,export} ...";
        }
        String cmd = prog.substring(MAIN_PROG.length() + 1);
        String base = "usage: " + prog
                + " [-h] [--password PW] [--user NAME] [--port N] [--drive D] [--yes] [--no-color]";
        return base + switch (cmd) {
            case "add", "update" -> " [sources ...]";
            case "import" -> " [--out DIR] [--zip] [--replace] [--force] sources [sources ...]";
            case "remove" -> " [names ...]";
            case "export" -> " [--all] [--out DIR] [names ...]";
            default -> "";
        };
    }

    // ---------------------------------------------------------------- parsing

    public static Cli parse(String[] argv) {
        Cli c = new Cli();
        c.address = firstNonEmpty(System.getenv("MPC_ADDRESS"), System.getenv("MPC_HOST"));

        List<String> args = new ArrayList<>(List.of(argv));
        // The address may only be the very first argument, and only when it is not a command
        // (mpc_remote.py ADDRESS command ...).
        if (!args.isEmpty() && !args.get(0).startsWith("-") && !isCommand(args.get(0))) {
            c.hostArg = args.remove(0);
        }
        boolean seenCommand = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-h") || a.equals("--help")) {
                throw new HelpRequested(seenCommand ? commandHelp(c.command) : mainHelp());
            }
            if (a.startsWith("-") && a.length() > 1) {
                String name = a;
                String value = null;
                int eq = a.indexOf('=');
                if (eq >= 0) {
                    name = a.substring(0, eq);
                    value = a.substring(eq + 1);
                }
                switch (name) {
                    case "--address", "--host" -> {
                        if (seenCommand) {
                            throw unrecognized(a, args, i + 1);
                        }
                        c.address = value != null ? value : nextValue(args, ++i, name, subProg(c, seenCommand));
                    }
                    case "--password" -> {
                        c.password = value != null ? value : nextValue(args, ++i, name, subProg(c, seenCommand));
                    }
                    case "--user" -> {
                        c.user = value != null ? value : nextValue(args, ++i, name, subProg(c, seenCommand));
                    }
                    case "--port" -> {
                        String v = value != null ? value : nextValue(args, ++i, name, subProg(c, seenCommand));
                        try {
                            c.port = Integer.parseInt(v);
                        } catch (NumberFormatException e) {
                            throw new CliError(subProg(c, seenCommand),
                                    "argument --port: invalid int value: '" + v + "'");
                        }
                    }
                    case "--drive" -> {
                        c.drive = value != null ? value : nextValue(args, ++i, name, subProg(c, seenCommand));
                    }
                    case "--yes", "-y" -> c.yes = true;
                    case "--no-color" -> c.noColor = true;
                    case "--out" -> {
                        if (!seenCommand) {
                            throw unrecognized(a, args, i + 1);
                        }
                        if (!"import".equals(c.command) && !"export".equals(c.command)) {
                            throw unrecognized(a, args, i + 1);
                        }
                        c.out = value != null ? value : nextValue(args, ++i, name, subProg(c, seenCommand));
                    }
                    case "--zip", "--replace", "--force" -> {
                        if (!"import".equals(c.command)) {
                            throw unrecognized(a, args, i + 1);
                        }
                        if (name.equals("--zip")) {
                            c.makeZip = true;
                        } else if (name.equals("--replace")) {
                            c.replaceExisting = true;
                        } else {
                            c.force = true;
                        }
                    }
                    case "--all" -> {
                        if (!seenCommand || !"export".equals(c.command)) {
                            throw unrecognized(a, args, i + 1);
                        }
                        c.exportAll = true;
                    }
                    default -> throw unrecognized(a, args, i + 1);
                }
                continue;
            }
            // a positional argument
            if (!seenCommand) {
                if (isCommand(a)) {
                    seenCommand = true;
                    c.command = a;
                } else {
                    throw invalidChoice(a); // the command slot only accepts a known command
                }
                continue;
            }
            switch (c.command) {
                case "add", "update", "import" -> c.sources.add(a);
                case "remove", "export" -> c.names.add(a);
                default -> {
                    StringBuilder rest = new StringBuilder(a);
                    for (int j = i + 1; j < args.size(); j++) {
                        rest.append(' ').append(args.get(j));
                    }
                    throw new CliError(MAIN_PROG, "unrecognized arguments: " + rest);
                }
            }
        }
        if ("import".equals(c.command) && c.sources.isEmpty()) {
            throw new CliError(MAIN_PROG + " import", "the following arguments are required: sources");
        }
        return c;
    }

    private static String nextValue(List<String> args, int index, String option, String prog) {
        if (index >= args.size()) {
            throw new CliError(prog, "argument " + option + ": expected one argument");
        }
        return args.get(index);
    }

    private static boolean isCommand(String s) {
        return COMMANDS.contains(s);
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return b != null && !b.isEmpty() ? b : null;
    }

    // ---------------------------------------------------------------- help text

    public static String mainHelp() {
        return String.join("\n",
                "usage: send-mpc-vst [-h] [--address ADDRESS] [--password PW] [--user NAME] [--port N]",
                "                  [--drive D] [--yes] [--no-color]",
                "                  {info,drives,list,doctor,restart,add,update,import,remove,export} ...",
                "",
                "Manage VST2 plugins on an Akai MPC over the network.",
                "",
                "The MPC's address can also be given as --address ADDRESS (or the MPC_ADDRESS environment variable).",
                "",
                "positional arguments:",
                "  {info,drives,list,doctor,restart,add,update,import,remove,export}",
                "    info                show the MPC and its drives",
                "    drives              list drives",
                "    list                list installed plugins",
                "    doctor              check the MPC is set up correctly",
                "    restart             restart the MPC app so it rescans VST2 plugins (unsaved data is lost)",
                "    add                 install plugins from zip files or SDK project folders",
                "    update              install, replacing any existing copy without asking",
                "    import              convert community mpc-vst-plugins packages to the SDK format, then install them",
                "    remove              delete installed plugins",
                "    export              save installed plugins from the MPC as zip files",
                "",
                "options:",
                "  -h, --help            show this help message and exit",
                "  --address ADDRESS, --host ADDRESS",
                "                        IP address or name of the MPC; normally given as the first argument (or set MPC_ADDRESS)",
                "  --password PW         SSH password if needed (or set MPC_SSH_PASSWORD)",
                "  --user NAME           SSH user (default root)",
                "  --port N              SSH port (default 22)",
                "  --drive D             drive number, name or mount point to manage",
                "  --yes, -y             do not ask for confirmation",
                "  --no-color            plain output",
                "",
                "Run with no arguments to open the GUI; with just an address for the",
                "interactive command-line menu.",
                "");
    }

    public static String commandHelp(String command) {
        List<String> lines = new ArrayList<>();
        String usage;
        switch (command) {
            case "add", "update" -> {
                usage = "usage: send-mpc-vst " + command + " [-h] [--password PW] [--user NAME] [--port N] [--drive D] [--yes] [--no-color] [sources ...]";
                lines.add("positional arguments:");
                lines.add("  sources               plugin zip file(s) or SDK project folder(s)");
            }
            case "import" -> {
                usage = "usage: send-mpc-vst import [-h] [--password PW] [--user NAME] [--port N] [--drive D] [--yes] [--no-color]";
                lines.add("                        [--out DIR] [--zip] [--replace] [--force] sources [sources ...]");
                lines.add("");
                lines.add("positional arguments:");
                lines.add("  sources               release zip(s) or folder(s); give the armv7 and aarch64 zips of one plugin together");
                lines.add("");
                lines.add("options:");
                lines.add("  --out DIR             folder to save the converted plugins in (default ./converted)");
                lines.add("  --zip                 also save a zip with INSTALL.txt for sharing");
                lines.add("  --replace             replace an installed copy without asking");
                lines.add("  --force               overwrite converted folders that already exist in --out");
            }
            case "remove" -> {
                usage = "usage: send-mpc-vst remove [-h] [--password PW] [--user NAME] [--port N] [--drive D] [--yes] [--no-color] [names ...]";
                lines.add("positional arguments:");
                lines.add("  names                 installed plugin folder(s) or name(s) to delete");
            }
            case "export" -> {
                usage = "usage: send-mpc-vst export [-h] [--password PW] [--user NAME] [--port N] [--drive D] [--yes] [--no-color]";
                lines.add("                        [--all] [--out DIR] [names ...]");
                lines.add("");
                lines.add("positional arguments:");
                lines.add("  names                 installed plugin folder(s) or name(s) to export");
                lines.add("");
                lines.add("options:");
                lines.add("  --all                 export every plugin");
                lines.add("  --out DIR             folder for the zip files (default: current folder)");
            }
            default -> usage = "usage: send-mpc-vst " + command + " [-h] [--password PW] [--user NAME] [--port N] [--drive D] [--yes] [--no-color]";
        }
        if (!lines.isEmpty()) {
            lines.add("");
        }
        lines.add("options:");
        lines.add("  -h, --help            show this help message and exit");
        lines.add("  --password PW         SSH password if needed (or set MPC_SSH_PASSWORD)");
        lines.add("  --user NAME           SSH user (default root)");
        lines.add("  --port N              SSH port (default 22)");
        lines.add("  --drive D             drive number, name or mount point to manage");
        lines.add("  --yes, -y             do not ask for confirmation");
        lines.add("  --no-color            plain output");
        lines.add("");
        List<String> out = new ArrayList<>();
        out.add(usage);
        out.addAll(lines);
        return String.join("\n", out);
    }
}

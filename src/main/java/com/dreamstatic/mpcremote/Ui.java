package com.dreamstatic.mpcremote;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the engine prints or asks: the banner, panels, tables, prompts, confirmations
 * and the numbered chooser. The terminal helpers of mpc_remote.py live here as the default
 * {@link Backend}; the GUI installs its own backend so the very same engine code streams
 * into the report area and asks with dialogs instead.
 */
public final class Ui {

    /** Current style; replaced in Main once the colour/unicode flags are known. */
    public static Style S = new Style(false, true);

    // ---------------------------------------------------------------- backend

    /** Where output and prompts go: a terminal (default) or the GUI's report area. */
    public interface Backend {
        /** Print one line of output. */
        void line(String text);

        /** Is a human present to answer prompts (a terminal, or the GUI window)? */
        boolean interactive();

        /** May the spinner draw live on the console (erase-line escapes, no log noise)? */
        boolean spinnerLive();

        /** Ask for one value; null/empty means "no answer" (the caller supplies defaults). */
        String ask(String prompt, String defaultValue, boolean secret);

        /** Yes/no question; only called when interactive and not assume-yes. */
        boolean confirm(String prompt, boolean defaultValue);

        /** Numbered menu; returns the chosen index, or null for back/cancel. */
        Integer choose(String title, List<String[]> items, boolean allowBack, String backLabel);
    }

    private static Backend backend = new ConsoleBackend();

    /** Install a different output target (the GUI does this before showing its window). */
    public static void setBackend(Backend b) {
        backend = b;
    }

    public static Backend backend() {
        return backend;
    }

    static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in));

    /** The stock terminal backend: stdout, stdin, and the readline-free prompt boxes. */
    static final class ConsoleBackend implements Backend {

        @Override
        public void line(String text) {
            System.out.println(text);
        }

        @Override
        public boolean interactive() {
            return System.console() != null;
        }

        @Override
        public boolean spinnerLive() {
            return System.console() != null;
        }

        /**
         * A titled input box. Falls back to a plain prompt when not on a terminal.
         * (The original also offered readline tab completion for paths; plain Java has no
         * equivalent, so paths are typed in full.)
         */
        @Override
        public String ask(String prompt, String defaultValue, boolean secret) {
            String suffix = defaultValue != null ? "  (Enter for " + defaultValue + ")" : "";
            int width = 0;
            String text;
            if (interactive()) {
                String title = prompt;
                width = Math.max(60, Util.visibleLen(title + suffix) + 6);
                width = Math.min(width, Math.max(40, Util.terminalWidth() - 4));
                say(S.accent(S.box[0] + S.box[4] + " ") + S.bold(title) + S.dim(suffix) + " "
                        + S.accent(S.box[4].repeat(Math.max(2, width - Util.visibleLen(title + suffix) - 3))));
                text = S.accent(S.box[5]) + " " + S.accent(S.arrow) + " ";
            } else {
                text = prompt + suffix + ": ";
            }
            String val;
            try {
                if (secret && System.console() != null) {
                    System.out.print(text);
                    System.out.flush();
                    char[] pw = System.console().readPassword();
                    val = pw == null ? "" : new String(pw);
                } else {
                    System.out.print(text);
                    System.out.flush();
                    val = IN.readLine();
                }
            } catch (IOException e) {
                say();
                System.exit(1);
                return "";
            }
            if (val == null) { // EOF
                say();
                System.exit(1);
                return "";
            }
            if (width > 0) {
                say(S.accent(S.box[2] + S.box[4].repeat(width - 1)));
            }
            val = val.strip();
            if (val.isEmpty() && defaultValue != null) {
                return defaultValue;
            }
            return val;
        }

        @Override
        public boolean confirm(String prompt, boolean defaultValue) {
            String val = ask(prompt + (defaultValue ? " (Y/n)" : " (y/N)"), null, false).toLowerCase();
            if (val.isEmpty()) {
                return defaultValue;
            }
            return val.startsWith("y");
        }

        /** A numbered menu in a box. Returns the chosen index, or null for back. */
        @Override
        public Integer choose(String title, List<String[]> items, boolean allowBack, String backLabel) {
            List<String> rows = new ArrayList<>();
            int numw = Integer.toString(items.size()).length();
            int labelw = 0;
            for (String[] item : items) {
                labelw = Math.max(labelw, Util.visibleLen(item[0]));
            }
            for (int i = 0; i < items.size(); i++) {
                String label = items.get(i)[0];
                String detail = items.get(i)[1];
                String num = Util.pad(Integer.toString(i + 1), numw);
                if (detail != null && !detail.isEmpty()) {
                    rows.add(S.accent(num) + "  " + Util.pad(label, labelw) + "  " + S.dim(detail));
                } else {
                    rows.add(S.accent(num) + "  " + label);
                }
            }
            if (allowBack) {
                rows.add(S.accent(Util.pad("0", numw)) + "  " + S.dim(backLabel));
            }
            panel(title, rows, null);
            while (true) {
                String val = ask("Choose a number", null, false);
                if (allowBack && (val.equals("0") || val.equals("q") || val.isEmpty())) {
                    return null;
                }
                if (val.matches("\\d+")) {
                    int n = Integer.parseInt(val);
                    if (n >= 1 && n <= items.size()) {
                        return n - 1;
                    }
                }
                say("  " + S.warnc("Enter a number from the list."));
            }
        }
    }

    // ---------------------------------------------------------------- output

    public static void say() {
        backend.line("");
    }

    public static void say(String text) {
        backend.line(text);
    }

    /** Are we attached to a terminal (or window) with a human driving us? */
    public static boolean interactive() {
        return backend.interactive();
    }

    /** May a Step draw its spinner live instead of printing plain progress lines? */
    public static boolean spinnerLive() {
        return backend.spinnerLive();
    }

    public static String ask(String prompt, String defaultValue, boolean secret) {
        return backend.ask(prompt, defaultValue, secret);
    }

    public static String ask(String prompt) {
        return ask(prompt, null, false);
    }

    public static boolean confirm(String prompt, boolean defaultValue, boolean assumeYes) {
        if (assumeYes) {
            return true;
        }
        if (!backend.interactive()) {
            return false;
        }
        return backend.confirm(prompt, defaultValue);
    }

    /**
     * Numbered menu; returns the chosen index, or null for back/exit.
     * items are {label, detail} pairs (detail may be empty).
     */
    public static Integer choose(String title, List<String[]> items, boolean allowBack, String backLabel) {
        return backend.choose(title, items, allowBack, backLabel);
    }

    // ---------------------------------------------------------------- steps

    public interface StepBody {
        void run(Step st) throws Exception;
    }

    /**
     * Run a body inside a spinner step: the step is marked done when the body returns without
     * an end, and failed when it throws (the exception is rethrown unchanged).
     */
    public static void step(String text, StepBody body) throws Exception {
        Step st = new Step(text).start();
        try {
            body.run(st);
            if (!st.finished) {
                st.done();
            }
        } catch (Throwable t) {
            if (!st.finished) {
                st.fail(t.getMessage() != null && !t.getMessage().isEmpty() ? t.getMessage() : "");
            }
            rethrow(t);
        }
    }

    static void rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            throw r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        throw new RuntimeException(t);
    }

    // ---------------------------------------------------------------- layout

    public static int visibleLen(String s) {
        return Util.visibleLen(s);
    }

    public static String pad(String s, int width) {
        return Util.pad(s, width);
    }

    public static void banner(String subtitle) {
        String[] art = {
                "┏━╸┏━╸┃╲┃┏━┓  ┏┳┓┏━┓┏━╸     ┏━╸╺┳╸",
                "┗━┓┣╸ ┃╲┃┃ ┃  ┃┃┃┣━┛┃    ╲ ╱┗━┓ ┃ ",
                "╺━┛┗━╸┃╲┃┗━┛  ╹ ╹╹  ┗━╸   ╲╱╺━┛ ╹ ",
        };
        int[] ramp = {201, 171, 141, 111, 81, 51};
        say();
        for (String row : art) {
            if (S.unicode) {
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < row.length(); i++) {
                    char ch = row.charAt(i);
                    if (ch == ' ') {
                        out.append(' ');
                    } else {
                        int idx = Math.min(ramp.length - 1, i * ramp.length / row.length());
                        out.append(S.paint(String.valueOf(ch), S.fg(ramp[idx]), "1"));
                    }
                }
                say("  " + out);
            }
        }
        if (!S.unicode) {
            say("  " + S.bold("SEND MPC VST"));
        }
        say("  " + S.dim(subtitle));
        say();
    }

    /** Rounded box with a title. rows may hold ANSI codes. */
    public static void panel(String title, List<String> rows, String tone) {
        String tl = S.box[0];
        String tr = S.box[1];
        String bl = S.box[2];
        String br = S.box[3];
        String h = S.box[4];
        String v = S.box[5];
        int width = 40;
        for (String r : rows) {
            width = Math.max(width, visibleLen(r));
        }
        width = Math.max(width, visibleLen(title) + 4);
        width = Math.min(width, Math.max(40, Util.terminalWidth() - 6));
        say(S.tone(tone, tl + h + " ") + S.bold(title) + " "
                + S.tone(tone, h.repeat(Math.max(1, width - visibleLen(title) - 1)) + tr));
        for (String r : rows) {
            say(S.tone(tone, v) + " " + pad(r, width) + " " + S.tone(tone, v));
        }
        say(S.tone(tone, bl + h.repeat(width + 2) + br));
    }

    public static void failPanel(String title, List<String> lines, String tone) {
        say();
        panel(title, lines, tone);
        say();
    }

    /** Key/value rows with dimmed, aligned keys. */
    public static List<String> kvRows(List<String[]> pairs) {
        int keyw = 0;
        for (String[] p : pairs) {
            keyw = Math.max(keyw, p[0].length());
        }
        List<String> out = new ArrayList<>();
        for (String[] p : pairs) {
            out.add(S.dim(pad(p[0], keyw)) + "  " + p[1]);
        }
        return out;
    }

    public static void table(List<String> headers, List<List<String>> rows) {
        int[] widths = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) {
            widths[i] = visibleLen(headers.get(i));
        }
        for (List<String> r : rows) {
            for (int i = 0; i < r.size(); i++) {
                widths[i] = Math.max(widths[i], visibleLen(r.get(i)));
            }
        }
        StringBuilder head = new StringBuilder("  ");
        StringBuilder rule = new StringBuilder("  ");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) {
                head.append("  ");
                rule.append("  ");
            }
            head.append(S.dim(pad(headers.get(i), widths[i])));
            rule.append(S.dim("-".repeat(widths[i])));
        }
        say(head.toString());
        say(rule.toString());
        for (List<String> r : rows) {
            StringBuilder line = new StringBuilder("  ");
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) {
                    line.append("  ");
                }
                line.append(pad(r.get(i), widths[i]));
            }
            say(line.toString());
        }
    }

    // ---------------------------------------------------------------- setup help

    public static List<String> setupSteps() {
        List<String> steps = new ArrayList<>();
        steps.add(S.bold("1. Update the MPC") + " to MPC OS 3.10.1 or later (Preferences → Info shows the version).");
        steps.add(S.bold("2. Enable the beta features:") + " Preferences → Enhancements (Beta) →");
        steps.add("     turn on " + S.cyan("Enable Enhancements") + " and then " + S.cyan("External Plugin Support") + " (read the warning).");
        steps.add(S.bold("3. Switch on developer mode:") + " Preferences → Info. Tap the version value 3 times,");
        steps.add("     then the serial number value 2 times, then the firmware version once.");
        steps.add("     Developer mode starts the MPC's SSH server.");
        steps.add(S.bold("4. Join the network:") + " the MPC and this computer must be on the same Wi-Fi / Ethernet.");
        steps.add("     Find the MPC's IP address in Preferences: the Wi-Fi tab (hold Shift to reach it) or the Ethernet tab (or your router's device list).");
        return steps;
    }
}

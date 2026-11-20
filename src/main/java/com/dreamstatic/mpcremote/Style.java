package com.dreamstatic.mpcremote;

/**
 * Colour and symbol helper. Degrades to plain ASCII when output is not a capable terminal —
 * the same symbols and 256-colour codes as mpc_remote.py.
 */
public final class Style {

    /** The ESC control character (written as a cast so the source file stays plain ASCII). */
    private static final char ESC = (char) 27;

    public final boolean color;
    public final boolean unicode;

    public final String ok;
    public final String bad;
    public final String warn;
    public final String info;
    public final String arrow;
    public final String dot;
    public final String spin;
    public final String[] box;

    public Style(boolean color, boolean unicode) {
        this.color = color;
        this.unicode = unicode;
        this.ok = unicode ? "✔" : "+";
        this.bad = unicode ? "✘" : "x";
        this.warn = unicode ? "▲" : "!";
        this.info = unicode ? "●" : "*";
        this.arrow = unicode ? "›" : ">";
        this.dot = unicode ? "·" : "-";
        this.spin = unicode ? "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏" : "|/-\\";
        this.box = unicode
                ? new String[]{"╭", "╮", "╰", "╯", "─", "│"}
                : new String[]{"+", "+", "+", "+", "-", "|"};
    }

    /** Wrap text in ANSI codes (no-op when colour is off); codes are joined with ";". */
    public String paint(String text, String... codes) {
        if (!color || codes.length == 0) {
            return text;
        }
        return ESC + "[" + String.join(";", codes) + "m" + text + ESC + "[0m";
    }

    /** 256-colour foreground code as a string (paint takes codes as strings). */
    public String fg(int n) {
        return "38;5;" + n;
    }

    // semantic colours
    public String good(String t) {
        return paint(t, fg(78));
    }

    public String badc(String t) {
        return paint(t, fg(203));
    }

    public String warnc(String t) {
        return paint(t, fg(221));
    }

    public String accent(String t) {
        return paint(t, fg(213));
    }

    public String cyan(String t) {
        return paint(t, fg(80));
    }

    public String dim(String t) {
        return paint(t, "2");
    }

    public String bold(String t) {
        return paint(t, "1");
    }

    /** Tone → painter, used by panel(): bad/warn/good/amber/violet, default cyan. */
    public String tone(String tone, String t) {
        switch (tone == null ? "" : tone) {
            case "bad":
                return badc(t);
            case "warn":
                return warnc(t);
            case "good":
                return good(t);
            case "amber":
                return paint(t, fg(214));
            case "violet":
                return paint(t, fg(141));
            default:
                return cyan(t);
        }
    }
}

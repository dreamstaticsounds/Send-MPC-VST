package com.dreamstatic.mpcremote;

/**
 * A progress line: spinner while running, then a tick or a cross — the Java version of the
 * Step context manager in mpc_remote.py. Non-interactive output gets a plain "› text ..." line.
 */
public final class Step {

    private static final char ESC = (char) 27;
    private static final char CR = '\r';

    public final String text;
    private final boolean live;
    private final Thread thread;
    private volatile boolean stop;
    /** True once done/warn/fail has printed the final line. */
    public volatile boolean finished;

    public Step(String text) {
        this.text = text;
        this.live = Ui.spinnerLive();
        this.thread = live ? new Thread(this::spin, "mpc-remote-spinner") : null;
        if (thread != null) {
            thread.setDaemon(true);
        }
    }

    /** Begin the step (prints the spinner, or a plain line when not on a terminal). */
    public Step start() {
        if (thread != null) {
            thread.start();
        } else {
            Ui.say("  " + Ui.S.arrow + " " + text + " ...");
        }
        return this;
    }

    private void spin() {
        int i = 0;
        while (!stop) {
            String frame = String.valueOf(Ui.S.spin.charAt(i % Ui.S.spin.length()));
            System.out.print("\r" + ESC + "[2K  " + Ui.S.accent(frame) + " " + text);
            System.out.flush();
            i++;
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void end(String mark, String detail) {
        finished = true;
        stop = true;
        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.out.print("\r" + ESC + "[2K");
            System.out.flush();
        }
        Ui.say("  " + mark + " " + text + (detail == null || detail.isEmpty() ? "" : "  " + Ui.S.dim(detail)));
    }

    public void done() {
        done("");
    }

    public void done(String detail) {
        end(Ui.S.good(Ui.S.ok), detail);
    }

    public void warn() {
        warn("");
    }

    public void warn(String detail) {
        end(Ui.S.warnc(Ui.S.warn), detail);
    }

    public void fail() {
        fail("");
    }

    public void fail(String detail) {
        end(Ui.S.badc(Ui.S.bad), detail);
    }

    /** Stop the spinner and erase its line without printing a result (used before re-prompting). */
    public void clearLine() {
        if (finished) {
            return;
        }
        finished = true;
        stop = true;
        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.out.print("\r" + ESC + "[2K");
            System.out.flush();
        }
    }
}

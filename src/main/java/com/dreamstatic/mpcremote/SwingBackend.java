package com.dreamstatic.mpcremote;

import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

/**
 * {@link Ui.Backend} for the GUI: report lines stream into the window's log area and the
 * engine's prompts become dialogs. Dialogs always run on the event dispatch thread, even
 * though the engine works on its own background thread.
 */
final class SwingBackend implements Ui.Backend {

    /** Receives report lines (already marshalled to the EDT by the sink). */
    private final java.util.function.Consumer<String> sink;
    /** The frame dialogs are positioned over; set once the window exists. */
    private volatile Component parent;

    SwingBackend(java.util.function.Consumer<String> sink) {
        this.sink = sink;
    }

    void setParent(Component parent) {
        this.parent = parent;
    }

    @Override
    public void line(String text) {
        sink.accept(text);
    }

    @Override
    public boolean interactive() {
        return true; // the window is always there to answer
    }

    @Override
    public boolean spinnerLive() {
        return false; // progress is reported as plain lines, never as terminal escapes
    }

    @Override
    public String ask(String prompt, String defaultValue, boolean secret) {
        return onEdt(() -> {
            String suffix = defaultValue != null ? "  (Enter for " + defaultValue + ")" : "";
            if (secret) {
                JPasswordField field = new JPasswordField(24);
                int r = JOptionPane.showConfirmDialog(parent, new Object[]{prompt + suffix + ":", field},
                        "Send MPC VST", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                if (r != JOptionPane.OK_OPTION) {
                    return "";
                }
                String v = new String(field.getPassword()).strip();
                return v.isEmpty() && defaultValue != null ? defaultValue : v;
            }
            String v = JOptionPane.showInputDialog(parent, prompt + suffix + ":",
                    defaultValue == null ? "" : defaultValue);
            if (v == null) {
                return "";
            }
            v = v.strip();
            return v.isEmpty() && defaultValue != null ? defaultValue : v;
        });
    }

    @Override
    public boolean confirm(String prompt, boolean defaultValue) {
        Integer r = onEdt(() -> {
            Object[] options = {"Yes", "No"};
            return JOptionPane.showOptionDialog(parent, prompt, "Send MPC VST",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[defaultValue ? 0 : 1]);
        });
        return r != null && r == 0;
    }

    @Override
    public Integer choose(String title, List<String[]> items, boolean allowBack, String backLabel) {
        return onEdt(() -> {
            Object[] options = new Object[items.size() + (allowBack ? 1 : 0)];
            for (int i = 0; i < items.size(); i++) {
                String label = items.get(i)[0];
                String detail = items.get(i)[1];
                options[i] = detail == null || detail.isEmpty() ? label : label + "  —  " + detail;
            }
            if (allowBack) {
                options[items.size()] = backLabel;
            }
            int r = JOptionPane.showOptionDialog(parent, title, "Send MPC VST",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (r < 0 || (allowBack && r == items.size())) {
                return null; // closed or "back"
            }
            return r < items.size() ? r : null;
        });
    }

    /** Run a dialog on the EDT even though the engine works on its own thread. */
    private static <T> T onEdt(Supplier<T> work) {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.get();
        }
        try {
            Object[] box = new Object[1];
            SwingUtilities.invokeAndWait(() -> box[0] = work.get());
            @SuppressWarnings("unchecked")
            T result = (T) box[0];
            return result;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}

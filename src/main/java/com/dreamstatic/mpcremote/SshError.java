package com.dreamstatic.mpcremote;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An SSH transport failure with a machine-readable kind, plus the friendly diagnosis panels
 * mpc_remote.py shows for each kind.
 */
public class SshError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** noclient | dns | unreachable | refused | auth | closed | timeout | other */
    public final String kind;
    public final String detail;

    public SshError(String kind) {
        this(kind, "");
    }

    public SshError(String kind, String detail) {
        super(detail == null ? "" : detail);
        this.kind = kind;
        this.detail = detail == null ? "" : detail;
    }

    /** Classify an ssh client's stderr message (classify_ssh_error). */
    public static String classify(String err) {
        String e = err.toLowerCase(Locale.ROOT);
        if (e.contains("permission denied") || e.contains("authentication failed") || e.contains("too many authentication")) {
            return "auth";
        }
        if (e.contains("refused")) {
            return "refused";
        }
        if (e.contains("could not resolve") || e.contains("name or service not known") || e.contains("nodename nor servname")) {
            return "dns";
        }
        if (e.contains("timed out") || e.contains("no route") || e.contains("unreachable") || e.contains("host is down")) {
            return "unreachable";
        }
        if (e.contains("closed by remote") || e.contains("connection reset") || e.contains("kex_exchange") || e.contains("banner")) {
            return "closed";
        }
        return "other";
    }

    /** Print the diagnosis panel for this error (explain()). */
    public void explain(String host, int port, boolean hasPassword) {
        switch (kind) {
            case "noclient":
                Ui.failPanel("No ssh command found", List.of(
                        "This tool uses your computer's OpenSSH client.",
                        "macOS / Linux: it is normally installed already. Windows 10/11: Settings → Apps → Optional features → OpenSSH Client."
                ), "bad");
                break;
            case "dns":
                Ui.failPanel("Can't find '" + host + "'", List.of(
                        "That name does not resolve. Use the MPC's IP address instead (for example 192.168.1.50).",
                        "Find it on the MPC under Preferences → Network."
                ), "bad");
                break;
            case "unreachable":
                Ui.failPanel("Can't reach " + host, List.of(
                        "Nothing answered at that address (timed out).",
                        "",
                        "• Is the MPC switched on and on the same network as this computer?",
                        "• Is the IP address right? Check Preferences on the MPC: Wi-Fi tab (hold Shift) or Ethernet tab.",
                        "• Guest Wi-Fi and some routers stop devices talking to each other (client isolation)."
                ), "bad");
                break;
            case "refused":
                List<String> refused = new ArrayList<>();
                refused.add(String.format("%s:%d refused the connection. That almost always means developer mode is not on,", host, port));
                refused.add("which is what starts the MPC's SSH server. To set the MPC up:");
                refused.add("");
                refused.addAll(Ui.setupSteps());
                Ui.failPanel("The MPC is there, but its SSH server is off", refused, "warn");
                break;
            case "auth": {
                List<String> lines = new ArrayList<>();
                lines.add("The MPC is there and SSH is on, but it did not accept the login.");
                lines.add("");
                if (hasPassword) {
                    lines.add("• The password was refused. Check it, or try an empty password:  --password \"\"");
                } else {
                    lines.add("• It wants a password. Run again with  --password YOURPASSWORD  (or set MPC_SSH_PASSWORD).");
                }
                lines.add("• To log in as another user: --user NAME");
                Ui.failPanel("Login refused", lines, "warn");
                break;
            }
            case "closed":
                Ui.failPanel("The connection was closed straight away", List.of(
                        "The SSH server may still be starting. Wait ten seconds and try again.",
                        "If this keeps happening, turn developer mode off and on again on the MPC."
                ), "warn");
                break;
            case "timeout":
                Ui.failPanel("The MPC stopped responding", List.of(
                        "The connection timed out. Check the network and try again."
                ), "bad");
                break;
            default:
                Ui.failPanel("SSH problem", List.of(detail.isEmpty() ? "unknown error" : detail), "bad");
                break;
        }
    }
}

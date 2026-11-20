package com.dreamstatic.mpcremote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The SSH transport. Like mpc_remote.py, it drives the computer's own OpenSSH client
 * (so keys, agents and ~/.ssh/config keep working) and streams shell scripts over it.
 * Passwords go through a short-lived SSH_ASKPASS helper so they never appear in `ps`.
 */
public class Remote {

    /** Result of running a script remotely: exit code, raw stdout, trimmed stderr. */
    public record Result(int rc, byte[] out, String err) {
        public String outText() {
            return Util.utf8(out);
        }
    }

    public final String host;
    public final String user;
    public final int port;
    /** May be filled in by {@link #connect(Remote)} after a password prompt. */
    public String password;

    private final int aliveCount = 4; // unanswered 15 s keepalives before ssh gives up on the link
    private final String ssh;
    private Path tmp;
    private Path askpass;
    private Path control;

    public Remote(String host, String user, int port, String password) {
        this.host = host;
        this.user = user;
        this.port = port;
        this.password = password;
        String bin = System.getenv("MPC_SSH_BIN");
        this.ssh = bin != null && !bin.isEmpty() ? bin : Util.which("ssh");
    }

    // ---------------------------------------------------------------- setup

    private Path ensureTmp() throws IOException {
        if (tmp == null) {
            Path base = !Util.isWindows() && Files.isDirectory(Paths.get("/tmp")) ? Paths.get("/tmp") : null;
            tmp = base != null ? Files.createTempDirectory(base, "mpcr-") : Files.createTempDirectory("mpcr-");
            Util.setExecutable(tmp, true); // chmod 700
        }
        return tmp;
    }

    private Path askpassScript() throws IOException {
        if (askpass == null) {
            Path d = ensureTmp();
            if (Util.isWindows()) {
                askpass = d.resolve("askpass.bat");
                Files.writeString(askpass, "@echo off\r\necho.%MPC_ASKPASS_PW%\r\n");
            } else {
                askpass = d.resolve("askpass.sh");
                Files.writeString(askpass, "#!/bin/sh\nprintf '%s\\n' \"$MPC_ASKPASS_PW\"\n");
                Util.setExecutable(askpass, true); // chmod 700
            }
        }
        return askpass;
    }

    private List<String> command(String remoteCmd) throws IOException {
        if (ssh == null) {
            throw new SshError("noclient");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(ssh);
        cmd.add("-p");
        cmd.add(String.valueOf(port));
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o");
        cmd.add("UserKnownHostsFile=" + (Util.isWindows() ? "NUL" : "/dev/null"));
        cmd.add("-o");
        cmd.add("LogLevel=ERROR");
        cmd.add("-o");
        cmd.add("ConnectTimeout=8");
        cmd.add("-o");
        cmd.add("ServerAliveInterval=15");
        cmd.add("-o");
        cmd.add("ServerAliveCountMax=" + aliveCount);
        cmd.add("-o");
        cmd.add("NumberOfPasswordPrompts=1");
        if (password == null) {
            cmd.add("-o");
            cmd.add("BatchMode=yes");
        }
        if (!Util.isWindows() && System.getenv("MPC_SSH_BIN") == null) {
            if (control == null) {
                control = ensureTmp().resolve("cm");
            }
            cmd.add("-o");
            cmd.add("ControlMaster=auto");
            cmd.add("-o");
            cmd.add("ControlPath=" + control);
            cmd.add("-o");
            cmd.add("ControlPersist=120");
        }
        cmd.add(user + "@" + host);
        cmd.add(remoteCmd);
        return cmd;
    }

    private void applyEnv(ProcessBuilder pb) throws IOException {
        if (password != null) {
            Map<String, String> env = pb.environment();
            env.put("MPC_ASKPASS_PW", password);
            env.put("SSH_ASKPASS", askpassScript().toString());
            env.put("SSH_ASKPASS_REQUIRE", "force");
            env.putIfAbsent("DISPLAY", ":0");
        }
    }

    // ---------------------------------------------------------------- running

    /** Run a shell script remotely. Returns exit code, stdout bytes and trimmed stderr. */
    public Result run(String script, byte[] stdin, int timeoutSeconds) {
        List<String> cmd;
        try {
            cmd = command("sh -c " + Util.shlexQuote(script));
        } catch (IOException e) {
            throw new SshError("other", String.valueOf(e.getMessage()));
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        try {
            applyEnv(pb);
        } catch (IOException e) {
            throw new SshError("other", String.valueOf(e.getMessage()));
        }
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new SshError(ssh == null ? "noclient" : "other", String.valueOf(e.getMessage()));
        }

        byte[] stdinBytes = stdin;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        Thread tIn = new Thread(() -> {
            try (OutputStream os = p.getOutputStream()) {
                if (stdinBytes != null) {
                    os.write(stdinBytes);
                }
            } catch (IOException ignored) {
                // the remote side closed its input (or we were killed) - nothing to do
            }
        }, "mpc-remote-stdin");
        Thread tOut = new Thread(() -> drain(p.getInputStream(), outBuf), "mpc-remote-stdout");
        Thread tErr = new Thread(() -> drain(p.getErrorStream(), errBuf), "mpc-remote-stderr");
        tIn.start();
        tOut.start();
        tErr.start();

        boolean finished;
        try {
            finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SshError("timeout");
        }
        if (!finished) {
            p.destroyForcibly();
            joinQuietly(tIn, 1000);
            joinQuietly(tOut, 2000);
            joinQuietly(tErr, 2000);
            throw new SshError("timeout");
        }
        joinQuietly(tIn, 5000);
        joinQuietly(tOut, 5000);
        joinQuietly(tErr, 5000);

        int rc = p.exitValue();
        String err = Util.utf8(errBuf.toByteArray()).strip();
        if (rc == 255) { // ssh itself failed
            throw new SshError(SshError.classify(err), err);
        }
        return new Result(rc, outBuf.toByteArray(), err);
    }

    /** Run a script and return (rc, stdout as text, stderr). */
    public Result text(String script, int timeoutSeconds) {
        return run(script, null, timeoutSeconds);
    }

    public Result text(String script) {
        return text(script, 60);
    }

    private static void drain(InputStream in, ByteArrayOutputStream sink) {
        byte[] buf = new byte[8192];
        try (InputStream stream = in) {
            int n;
            while ((n = stream.read(buf)) > 0) {
                synchronized (sink) {
                    sink.write(buf, 0, n);
                }
            }
        } catch (IOException ignored) {
            // stream closed
        }
    }

    private static void joinQuietly(Thread t, long millis) {
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /** Tear down the control socket and scratch directory. */
    public void close() {
        if (control != null && ssh != null && Files.exists(control)) {
            try {
                Process p = new ProcessBuilder(ssh, "-S", control.toString(), "-O", "exit", "x")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                p.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort
            }
        }
        if (tmp != null) {
            Util.deleteRecursively(tmp);
            tmp = null;
        }
    }

    // ---------------------------------------------------------------- probing

    /** Cheap TCP check first so we can give a precise diagnosis. Raises SshError. */
    public static void probePort(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
        } catch (UnknownHostException e) {
            throw new SshError("dns");
        } catch (ConnectException e) {
            throw new SshError("refused");
        } catch (java.net.SocketTimeoutException e) {
            throw new SshError("unreachable");
        } catch (IOException e) {
            throw new SshError("unreachable", String.valueOf(e.getMessage()));
        }
    }

    /**
     * Open the connection (with a password prompt if needed). Throws SshError on failure.
     * Mirrors connect() in mpc_remote.py.
     */
    public static void connect(Remote remote) throws Exception {
        Ui.step("Reaching " + remote.host + ":" + remote.port, st -> {
            if (System.getenv("MPC_SSH_BIN") == null) {
                probePort(remote.host, remote.port);
            }
            st.done("SSH server is up");
        });
        Step st = new Step("Logging in as " + remote.user).start();
        int attempts = 0;
        while (true) {
            try {
                Result r = remote.text("echo mpc-remote-ok", 60);
                if (r.outText().contains("mpc-remote-ok")) {
                    st.done(remote.password == null ? "ok" : "password accepted");
                    return;
                }
                throw new SshError("other", r.err().isEmpty() ? "unexpected reply from the MPC" : r.err());
            } catch (SshError e) {
                if ("auth".equals(e.kind) && remote.password == null && Ui.interactive() && attempts < 2) {
                    attempts++;
                    st.clearLine();
                    remote.password = Ui.ask(
                            "SSH password for " + remote.user + "@" + remote.host + " (Enter for none)", null, true);
                    st = new Step("Logging in as " + remote.user).start();
                    continue;
                }
                st.fail(e.kind);
                throw e;
            }
        }
    }
}

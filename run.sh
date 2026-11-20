#!/bin/sh
# Run the GUI (no arguments) or the CLI (pass flags).  Builds the jar first if it is
# missing or older than the sources.
set -e
cd "$(dirname "$0")"

# Rebuild when the jar is missing or any source file is newer than it (a stale jar
# once made this look like it "wasn't working").
if [ ! -f SendMPCVST.jar ] || [ -n "$(find src/main/java -name '*.java' -newer SendMPCVST.jar 2>/dev/null)" ]; then
    ./build.sh
fi

# Java draws with X11. Some sessions (notably Wayland terminals and SSH logins) do not
# export DISPLAY even though an X server socket exists — find it so the GUI can open.
if [ -z "${DISPLAY:-}" ]; then
    for sock in /tmp/.X11-unix/X*; do
        [ -S "$sock" ] || continue
        export DISPLAY=":${sock##*/X}"
        if [ -z "${XAUTHORITY:-}" ] && [ -f "$HOME/.Xauthority" ]; then
            export XAUTHORITY="$HOME/.Xauthority"
        fi
        break
    done
fi

# True when this JVM can open windows. Headless JREs ship without the X peer library
# (libawt_xawt.so) and can never show the GUI, however DISPLAY is set.
gui_ok() {
    [ "$(uname -s)" = "Darwin" ] && return 0    # macOS JVMs are always headful
    j="$1"
    r=$(readlink -f "$j" 2>/dev/null) && [ -n "$r" ] && j="$r"
    [ -e "$(dirname "$(dirname "$j")")/lib/libawt_xawt.so" ]
}

find_java() {
    headless=""
    for c in "$JAVA_HOME/bin/java" \
             "$(command -v java 2>/dev/null || true)" \
             "$HOME"/opt/jdk-*/bin/java \
             /usr/lib/jvm/*/bin/java \
             /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java; do
        [ -n "$c" ] && [ -x "$c" ] || continue
        if gui_ok "$c"; then
            echo "$c"
            return 0
        fi
        [ -n "$headless" ] || headless="$c"
    done
    # no window-capable JVM: fall back so the command line still works
    [ -n "$headless" ] && { echo "$headless"; return 0; }
    return 1
}

JAVA=$(find_java) || {
    echo "no Java runtime found." >&2
    echo "Install Java 17 or newer, e.g. Temurin: https://adoptium.net" >&2
    exit 1
}
if ! gui_ok "$JAVA"; then
    echo "warning: only a headless Java was found ($JAVA) — the GUI cannot open with it." >&2
    echo "         Install a full JDK/JRE (e.g. Temurin: https://adoptium.net) or set" >&2
    echo "         JAVA_HOME to one. The command line still works:" >&2
    echo "         java -jar SendMPCVST.jar <address> --help" >&2
fi
exec "$JAVA" -jar SendMPCVST.jar "$@"

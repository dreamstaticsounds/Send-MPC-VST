#!/bin/sh
# Build SendMPCVST.jar from src/main/java.  Needs a JDK (17 or newer) on PATH, in JAVA_HOME,
# or in ~/opt/jdk-* or /usr/lib/jvm/* -- no other dependencies.
set -e
cd "$(dirname "$0")"

find_tool() {
    for c in "$JAVA_HOME/bin/$1" \
             "$(command -v "$1" 2>/dev/null || true)" \
             "$HOME"/opt/jdk-*/bin/"$1" \
             /usr/lib/jvm/*/bin/"$1" \
             /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/"$1"; do
        if [ -n "$c" ] && [ -x "$c" ]; then
            echo "$c"
            return 0
        fi
    done
    return 1
}

JAVAC=$(find_tool javac) || {
    echo "no JDK found." >&2
    echo "Install Java 17 or newer, e.g. Temurin: https://adoptium.net" >&2
    exit 1
}
JAR=$(find_tool jar) || { echo "found javac but no jar tool" >&2; exit 1; }

rm -rf build/classes
mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
"$JAVAC" --release 17 -encoding UTF-8 -d build/classes @build/sources.txt
"$JAR" cfm SendMPCVST.jar MANIFEST.MF -C build/classes .
echo "built SendMPCVST.jar"

# Send MPC VST

A cross-platform (Linux / macOS / Windows) tool for **installing and managing VST2 plugins
on an Akai MPC** over the network, with a Swing GUI and an equivalent command line. No
dependencies beyond Java itself — based on InMusic Software's
[`mpc_remote.py`](https://github.com/inmusic-software/mpc-plugin-builder-sdk/blob/main/tools/mpc_remote.py)
(+ its community-package converter), with the same commands and the same behaviour.

The MPC must be on your network with **developer mode** on (that is what starts its SSH
server) and **External Plugin Support** enabled. Everything the tool writes lives inside
the `VST2 Plugins` folder of the drive you pick (plus one short-lived hidden staging folder
at the drive root); it never touches system files.

## Requirements

Java 17 or newer (a JRE is enough to run; a JDK is only needed to build).
Any OpenJDK build works — [Temurin](https://adoptium.net) is a good default. A *headless*
JRE (one without `libawt_xawt.so`, e.g. Ubuntu's `openjdk-25-jre-headless`) cannot open
windows; `run.sh` detects that and prefers a window-capable Java.

An **OpenSSH client** — the tool drives your computer's own `ssh`, so your keys, agent and
`~/.ssh/config` keep working:

* Linux / macOS: installed already.
* Windows 10/11: Settings → Apps → Optional features → **OpenSSH Client**.

## Building

```sh
# Linux / macOS
./build.sh
```

```bat
:: Windows (needs javac on PATH)
build.bat
```

This produces `SendMPCVST.jar`. The scripts look for Java in `JAVA_HOME`, `PATH`,
`~/opt/jdk-*`, `/usr/lib/jvm/*` and the macOS JDK location. (`mvn package` works too and
additionally runs the test suite — see *Testing*.)

## Running the GUI

```sh
# Linux / macOS (builds the jar first if missing or stale)
./run.sh
```

```bat
:: Windows
SendMPCVST.bat
```

Or just double-click `SendMPCVST.jar`, or:

```sh
java -jar SendMPCVST.jar
```

A ready-to-run package (`Send-MPC-VST-v1.0.0.zip` or `.tar.gz`) is attached to the
[Releases page](https://github.com/dreamstaticsounds/Send-MPC-VST/releases) — extract it
and use the `SendMPCVST.jar` inside if you only have a JRE (no JDK).

In the window:

1. **MPC** — the address (or name), SSH user, password and port, then **Connect**. The
   address, user and port are remembered for next time; the password never is (leave it
   blank to use your SSH keys). After connecting, the report shows the device panel and
   the drives the MPC reports, exactly as the command line prints them.
2. **Drive** — which drive to manage; the line on the right shows its type, free space and
   any `read-only`/`noexec` flags. Drives are listed internal-first, with the recommended
   internal storage marked ★.
3. **Installed plugins** — a table of what is on the selected drive (folder, manufacturer,
   version, kind, builds, size):
   * **Home / End** jump to the first / last plugin in the list.
   * Drag & drop a plugin zip or folder onto the table to add it.
   * **Add… / Update…** — pick plugin zip files or SDK project folders. The plugin is
     streamed to the drive, unpacked into a staging folder and swapped into place only
     after every file's byte count is verified.
   * **Remove** — deletes the selected folder(s). Several selected? One confirmation
     covers the whole selection (the names are listed in the report first) instead of a
     prompt per plugin.
   * **Export…** — saves selected plugins (or all of them, when nothing is selected) from
     the MPC as zip files into a folder you pick.
   * **Import package…** — converts a community *mpc-vst-plugins* package (plugin-meta.xml,
     giving the armv7 and aarch64 zips together), then installs the result.
   * **Doctor** — health check of the connection, developer mode and every drive.
   * **Restart MPC** — restarts the MPC app so it rescans VST2 plugins.
   * **Refresh** — reloads the table.
4. **Report** — everything an operation does streams here in the same text the command line
   prints (panels, tables, step results). **Clear** empties it; **Hide** collapses the
   report to its title bar and gives the freed height to the plugin table (**Show** puts
   it back).
5. The status bar shows the current state; a progress bar runs while an operation is in
   flight. After a successful install the tool asks whether to restart the MPC so the new
   plugin appears — same warning about unsaved data as the CLI.

## Command line

Pass any arguments instead of none to run the CLI (same behaviour as the GUI's buttons):

```sh
java -jar SendMPCVST.jar 192.168.1.50                 interactive menu
java -jar SendMPCVST.jar 192.168.1.50 info            show the device and its drives
java -jar SendMPCVST.jar 192.168.1.50 list
java -jar SendMPCVST.jar 192.168.1.50 add plugins/MySynth   an SDK project (uses its build/ output) ...
java -jar SendMPCVST.jar 192.168.1.50 add Wobble-1.0-mpc.zip              ... or a downloaded zip
java -jar SendMPCVST.jar 192.168.1.50 update plugins/MySynth              replace an installed plugin
java -jar SendMPCVST.jar 192.168.1.50 import Acid-2.1.0-mpc-armv7.zip     convert a community package, then add it
java -jar SendMPCVST.jar 192.168.1.50 remove "Acme - VST - Wobble"
java -jar SendMPCVST.jar 192.168.1.50 export --all --out ~/Backups        always written as zip files
java -jar SendMPCVST.jar 192.168.1.50 doctor          check the MPC is set up correctly
java -jar SendMPCVST.jar 192.168.1.50 restart         restart the MPC app to rescan VSTs (unsaved data is lost)
```

Options: `--address`, `--password` (or `MPC_SSH_PASSWORD`), `--user`, `--port`, `--drive`,
`--yes`, `--no-color` — see `java -jar SendMPCVST.jar --help`. Exit codes: `0` success,
`1` the operation failed, `2` a setup problem (cannot connect, no drives, bad arguments).

## How it works

The design follows [InMusic's `mpc_remote.py`](https://github.com/inmusic-software/mpc-plugin-builder-sdk/blob/main/tools/mpc_remote.py)
exactly:

* A shell script is streamed to the MPC over `ssh` and executed with `sh -c`.
* Installing a plugin builds a **tar** of the plugin folder locally (owners normalised to
  root, binaries `0755`, everything else `0644`, junk such as `.DS_Store` left out) and
  pipes it into `tar -xof -` inside a staging folder on the drive; the finished folder is
  swapped into place only after every file's byte count has been verified.
* Exporting asks the MPC for a `tar` and converts it to a zip, preserving the executable
  bits in the archive's central directory (the same values Python's `zipfile` writes).
* The `import` command converts community *mpc-vst-plugins* packages to the SDK folder
  format, merging the 32-bit and 64-bit zips of one plugin into a single folder —
  byte-for-byte the same `plugin.json` / `version.xml` / `INSTALL.txt` as the Python tools
  produce.

The engine (connection, drives, listing, install, export, conversion) is shared by the GUI
and the CLI: all of its output and prompts go through one seam, so the window streams the
report and asks with dialogs while the terminal prints boxes and reads answers.

## Differences from the Python version

* Several plugins selected for **Remove** are confirmed with a single prompt (the Python
  version asks once per plugin).
* Interactive CLI prompts use plain line input; the Python version's readline
  tab-completion for file paths is not available in a plain Java console. The GUI needs no
  typed paths at all (file choosers and drag & drop).
* The window/banner carry the name **Send MPC VST**; otherwise the CLI output, exit codes
  and the resulting files on the MPC match `mpc_remote.py`; see *Testing* below.

## Testing

```sh
mvn test
```

85 unit tests cover the JSON reader/writer, the tar reader/writer, shell quoting, the
argument parser, plugin scanning/validation, the community-package converter (including
the 32/64-bit merge), drive discovery and the remote-script parsing (with a stubbed
transport). The GUI additionally has a headless check:

```sh
java -jar SendMPCVST.jar --selftest
```

which builds the window and verifies its defaults — including the Home/End bindings and
the Hide/Show report toggle — without a display.

The port was verified by running this jar and `mpc_remote.py` side by side against a
fake-MPC `ssh` shim and diffing their complete output **and** the resulting drive state
(file names, permissions and checksums) across 34 command-line scenarios — add, update,
remove, export, import, doctor, restart, error paths and the interactive menu all match
(banner art and the program name are normalised, since the app was renamed). The GUI was
then driven end-to-end against the same fake MPC (connect, refresh, doctor, two installs
through the file chooser, Home/End, Hide/Show, a two-plugin remove behind one
confirmation) with every stage checked and screenshotted.

## Project layout

```
build.sh / build.bat      dependency-free jar build (JDK only)
run.sh / SendMPCVST.bat   launchers (build first if missing or stale)
MANIFEST.MF               Main-Class for the jar
src/main/java/com/dreamstatic/mpcremote/
  Main.java               no arguments → GUI, flags → CLI, --selftest
  Gui.java                the application window
  Theme.java              dark Nimbus colour scheme
  SwingBackend.java       Ui backend: report lines + dialogs
  Cli.java                argument parsing (argparse-compatible messages)
  Ui.java / Style.java / Step.java   output seam: terminal backend (default) or GUI
  Remote.java             OpenSSH transport (askpass, control master, error diagnosis)
  DeviceInfo.java         device probe script and the "Your MPC" panel
  Drive.java              drive discovery, description and selection
  Plugins.java            installed-plugin listing, matching, folder-name safety
  Sources.java            local plugin sources: SDK builds, zips, validation
  Tar.java                minimal GNU tar reader/writer
  Json.java               minimal JSON reader/writer
  Install.java            packaging, streaming install, verification
  Actions.java            list/add/update/remove/export/doctor/restart/import
  LegacyConvert.java      community package → SDK folder conversion
  InstallText.java        the shared INSTALL.txt text
src/test/java/...         JUnit 5 tests (mvn test)
```

## License

MIT — see [LICENSE](LICENSE).

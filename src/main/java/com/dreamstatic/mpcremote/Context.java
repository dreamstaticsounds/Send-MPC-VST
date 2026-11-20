package com.dreamstatic.mpcremote;

import java.nio.file.Path;
import java.util.Map;

/** What every action needs: the connection, the device, the chosen drive and the scratch space. */
public class Context {

    public final Remote remote;
    public final Map<String, String> info;
    public Drive drive;
    public final boolean assumeYes;
    public final Path workdir;
    /** arm64 / arm32 / null: which build this MPC needs. */
    public final String arch;

    public Context(Remote remote, Map<String, String> info, Drive drive, boolean assumeYes, Path workdir) {
        this.remote = remote;
        this.info = info;
        this.drive = drive;
        this.assumeYes = assumeYes;
        this.workdir = workdir;
        this.arch = DeviceInfo.deviceArch(info);
    }

    /** The drive must be writable; prints a panel and returns false when it is not. */
    public boolean requireWritable() {
        if (!drive.writable()) {
            Ui.failPanel(drive.name + " is read-only", java.util.List.of(
                    "The MPC has mounted this drive read-only, so nothing can be copied to it.",
                    "Choose another drive (--drive), or check the card's write-protect switch."
            ), "bad");
            return false;
        }
        return true;
    }
}

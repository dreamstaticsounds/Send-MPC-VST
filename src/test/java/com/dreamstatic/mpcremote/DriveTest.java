package com.dreamstatic.mpcremote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class DriveTest {

    private static Remote canned(String output) {
        return new Remote("127.0.0.1", "root", 22, null) {
            @Override
            public Result text(String script, int timeoutSeconds) {
                return new Result(0, output.getBytes(StandardCharsets.UTF_8), "");
            }
        };
    }

    private static final String MOUNTS = ""
            + "proc /proc proc rw,relatime 0 0\n"
            + "sysfs /sys sysfs rw 0 0\n"
            + "tmpfs /run tmpfs rw 0 0\n"
            + "devpts /dev/pts devpts rw 0 0\n"
            + "/dev/mmcblk0p1 /media/sdcard\\040name vfat rw,relatime 0 0\n"
            + "/dev/sda1 /media/usb vfat ro,noexec,relatime 0 0\n"
            + "/dev/nvme0n1p2 /nvme ext4 rw,relatime 0 0\n"
            + "/dev/sdb1 /mnt/elsewhere ext4 rw 0 0\n"
            + "@@DF\n"
            + "Filesystem 1024-blocks Used Available Capacity Mounted on\n"
            + "/dev/mmcblk0p1 7800000 1000000 6800000 13% /media/sdcard name\n"
            + "/dev/sda1 1000000 900000 100000 90% /media/usb\n"
            + "/dev/nvme0n1p2 50000000 10000000 40000000 25% /nvme\n";

    @Test
    void discoversOnlyTheDrivesTheMpcManages() {
        List<Drive> drives = Drive.discover(canned(MOUNTS));
        assertEquals(3, drives.size(), "only /media/* and /nvme count; tmpfs/proc/mnt do not");
        // internal storage sorts first
        assertEquals("/nvme", drives.get(0).mount);
        assertEquals("nvme", drives.get(0).name);
        assertTrue(drives.get(0).internal());
        assertTrue(drives.get(0).recommended());
        assertEquals("/media/sdcard name", drives.get(1).mount, "octal escapes are decoded");
        assertEquals("sdcard name", drives.get(1).name);
        // the df section fills in the sizes
        assertEquals(6800000L * 1024, drives.get(1).free.longValue());
        assertEquals(7800000L * 1024, drives.get(1).total.longValue());
        assertFalse(drives.get(1).internal());
        assertFalse(drives.get(1).recommended());
        assertTrue(drives.get(1).writable());
        // the read-only, noexec card
        Drive usb = drives.get(2);
        assertEquals("usb", usb.name);
        assertFalse(usb.writable());
        assertTrue(usb.noexec());
        assertEquals(100000L * 1024, usb.free.longValue());
    }

    @Test
    void describesAndLocatesPlugins() {
        Drive internal = new Drive("/dev/nvme", "/nvme", "ext4", "rw");
        assertEquals("/nvme/" + Drive.PLUGINS_DIR, internal.pluginsPath());
        assertTrue(internal.describe().contains("internal"));
        Drive usb = new Drive("/dev/sda1", "/media/usb/", "vfat", "ro,noexec");
        assertEquals("/media/usb/" + Drive.PLUGINS_DIR, usb.pluginsPath(), "trailing slash is normalised");
        String desc = usb.describe();
        assertTrue(desc.contains("read-only"), desc);
        assertTrue(desc.contains("noexec"), desc);
        assertFalse(desc.contains("internal"));
    }

    @Test
    void picksDrivesByNameNumberOrMount() {
        List<Drive> drives = Drive.discover(canned(MOUNTS));
        assertEquals("/nvme", Drive.pick(drives, "1", false).mount);
        assertEquals("/nvme", Drive.pick(drives, "nvme", false).mount);
        assertEquals("/media/usb", Drive.pick(drives, "/media/usb", false).mount);
        assertEquals("/media/usb", Drive.pick(drives, "USB", false).mount);
        // several drives need --drive when nobody is asked
        UsageError several = assertThrows(UsageError.class, () -> Drive.pick(drives, null, false));
        assertTrue(several.getMessage().contains("Several drives found"), several.getMessage());
        UsageError noMatch = assertThrows(UsageError.class, () -> Drive.pick(drives, "nosuch", false));
        assertTrue(noMatch.getMessage().contains("No single drive matches"), noMatch.getMessage());
        // a single drive is used without asking
        assertEquals("/nvme", Drive.pick(List.of(drives.get(0)), null, false).mount);
        assertNull(Drive.pick(List.of(), null, false));
    }

    @Test
    void readsFreeSpaceFromDf() {
        Drive d = new Drive("/dev/sda1", "/media/usb", "vfat", "rw");
        Long free = Drive.free(canned(
                "/dev/sda1 1000000 900000 100000 90% /media/usb\n"), d);
        assertEquals(100000L * 1024, free.longValue());
        assertEquals(100000L * 1024, d.free.longValue());
        // unparseable output leaves the figure unknown instead of crashing
        Drive d2 = new Drive("/dev/sdb1", "/media/x", "vfat", "rw");
        assertNull(Drive.free(canned("garbage\n"), d2));
    }
}

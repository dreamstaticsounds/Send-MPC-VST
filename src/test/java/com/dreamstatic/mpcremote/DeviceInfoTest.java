package com.dreamstatic.mpcremote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeviceInfoTest {

    private static Remote canned(String output) {
        return new Remote("127.0.0.1", "root", 22, null) {
            @Override
            public Result text(String script, int timeoutSeconds) {
                return new Result(0, output.getBytes(StandardCharsets.UTF_8), "");
            }
        };
    }

    @Test
    void parsesTheProbeOutput() {
        String output = "dt.model=MPC Live II\n"
                + "dt.serial-number=ABC123\n"
                + "dt.=\n" // empty value stays empty
                + "app=3.4.1\n"
                + "uname=Linux 6.1 armv7l\n"
                + "arch=armv7l\n"
                + "hostname=mpc\n"
                + "uptime=9061.55\n"
                + "os.PRETTY_NAME=\"Ubuntu 22.04\"\n"
                + "os.VERSION_ID=\"22.04\"\n"
                + "devmode.file=yes\n"
                + "firmware=AZ01_2.10.0\n"
                + "cpu.count=4\n"
                + "cpu.part=0xd05\n"
                + "cpu.part=0xd05\n" // repeated per core: first wins, no duplicate error
                + "cpu.maxkhz=1800000\n"
                + "junk line without equals\n";
        Map<String, String> info = DeviceInfo.fetch(canned(output));
        assertEquals("MPC Live II", info.get("dt.model"));
        assertEquals("", info.get("dt."));
        assertEquals("Ubuntu 22.04", info.get("os.PRETTY_NAME"), "quotes are stripped");
        assertEquals("yes", info.get("devmode.file"));
        assertEquals("AZ01_2.10.0", info.get("firmware"));
        assertEquals("0xd05", info.get("cpu.part"));
        assertEquals(1, info.entrySet().stream().filter(e -> e.getKey().equals("cpu.part")).count());
        assertNull(info.get("junk line without equals"));
    }

    @Test
    void summarisesTheCpu() {
        Map<String, String> info = Map.of(
                "cpu.part", "0xd05",
                "cpu.count", "4",
                "cpu.maxkhz", "1800000",
                "arch", "aarch64");
        String summary = DeviceInfo.cpuSummary(info);
        assertTrue(summary.startsWith("Cortex-A55"), summary);
        assertTrue(summary.contains("4"), summary);
        assertTrue(summary.contains("@ 1.8 GHz"), summary);
        assertTrue(summary.contains("aarch64"), summary);
        // without a known part it falls back to the kernel's model name
        String fallback = DeviceInfo.cpuSummary(Map.of("cpu.model", "Example CPU", "cpu.maxkhz", "900000"));
        assertTrue(fallback.startsWith("Example CPU"), fallback);
        assertTrue(fallback.contains("@ 0.9 GHz"), fallback);
        assertEquals("", DeviceInfo.cpuSummary(Map.of()));
    }

    @Test
    void detectsTheArchitecture() {
        assertEquals("arm64", DeviceInfo.deviceArch(Map.of("arch", "aarch64")));
        assertEquals("arm64", DeviceInfo.deviceArch(Map.of("arch", "arm64")));
        assertEquals("arm32", DeviceInfo.deviceArch(Map.of("arch", "armv7l")));
        assertNull(DeviceInfo.deviceArch(Map.of("arch", "x86_64")));
        assertNull(DeviceInfo.deviceArch(Map.of()));
    }

    @Test
    void buildsTheDevicePanel() {
        Map<String, String> info = Map.of(
                "dt.model", "MPC Live II",
                "app", "3.4.1",
                "arch", "aarch64",
                "cpu.count", "4",
                "cpu.maxkhz", "1800000",
                "cpu.part", "0xd05",
                "uptime", "9061.55",
                "os.PRETTY_NAME", "Ubuntu");
        String rows = String.join("\n", DeviceInfo.infoRows("192.168.1.50", info));
        assertTrue(rows.contains("Address"), rows);
        assertTrue(rows.contains("192.168.1.50"), rows);
        assertTrue(rows.contains("MPC Live II"), rows);
        assertTrue(rows.contains("3.4.1"), rows);
        assertTrue(rows.contains("64-bit"), rows);
        assertTrue(rows.contains("2h 31m"), rows); // 9061 s
        assertTrue(rows.contains("Ubuntu"), rows);
        // things the device does not report are shown as such
        String sparse = String.join("\n", DeviceInfo.infoRows("1.2.3.4", Map.of()));
        assertTrue(sparse.contains("(not reported)"), sparse);
        assertTrue(sparse.contains("is the MPC app running?"), sparse);
    }
}

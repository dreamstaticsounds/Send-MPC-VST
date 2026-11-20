package com.dreamstatic.mpcremote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginsTest {

    /** A remote that returns canned output instead of talking to anything. */
    private static Remote canned(String output) {
        return new Remote("127.0.0.1", "root", 22, null) {
            @Override
            public Result text(String script, int timeoutSeconds) {
                return new Result(0, output.getBytes(StandardCharsets.UTF_8), "");
            }
        };
    }

    private static Drive drive() {
        return new Drive("/dev/sda1", "/media/usb", "vfat", "rw,relatime");
    }

    @Test
    void missingPluginsFolderIsReported() {
        Plugins.Listing listing = Plugins.list(canned("@@MISSING\n"), drive());
        assertFalse(listing.exists());
        assertEquals(List.of(), listing.plugins());
    }

    @Test
    void parsesTheListing() {
        String output = "@@PLUGIN Acme - VST - Wobble\n"
                + "{\n  \"name\": \"Wobble\",\n  \"manufacturer\": \"Acme\",\n"
                + "  \"version\": \"1.0\",\n  \"kind\": \"instrument\",\n"
                + "  \"file64\": \"w.so\",\n  \"file32\": \"w32.so\"\n}\n"
                + "\n"
                + "@@SIZE 512\n"
                + "@@PLUGIN Broken - VST - X\n"
                + "not json at all\n"
                + "@@SIZE 7\n"
                + "@@END\n";
        Plugins.Listing listing = Plugins.list(canned(output), drive());
        assertTrue(listing.exists());
        assertEquals(2, listing.plugins().size());
        Plugins.InstalledPlugin first = listing.plugins().get(0);
        assertEquals("Acme - VST - Wobble", first.folder);
        assertEquals("Wobble", first.name());
        assertEquals(512 * 1024, first.size);
        assertEquals("Acme", first.manifest.get("manufacturer"));
        Plugins.InstalledPlugin second = listing.plugins().get(1);
        assertEquals("Broken - VST - X", second.folder);
        assertEquals("Broken - VST - X", second.name()); // falls back to the folder name
        assertEquals(7 * 1024, second.size);
        assertTrue(second.manifest.isEmpty(), "invalid plugin.json is kept empty");
    }

    @Test
    void findsInstalledPluginsByFolderOrName() {
        Plugins.Listing listing = Plugins.list(canned(
                "@@PLUGIN Acme - VST - Wobble\n{\"name\":\"Wobble\"}\n@@SIZE 1\n"
                        + "@@PLUGIN Acme - VST - Other\n{\"name\":\"Other\"}\n@@SIZE 1\n"
                        + "@@PLUGIN Zed - VST - Wobbly\n{\"name\":\"Wobbly\"}\n@@SIZE 1\n"), drive());
        List<Plugins.InstalledPlugin> plugins = listing.plugins();
        assertEquals("Acme - VST - Wobble",
                Plugins.find(plugins, "acme - vst - wobble").folder); // exact, case-insensitive
        assertEquals("Acme - VST - Wobble", Plugins.find(plugins, "Wobble").folder); // by display name
        assertEquals("Zed - VST - Wobbly", Plugins.find(plugins, "Zed").folder); // substring
        UsageError several = assertThrows(UsageError.class, () -> Plugins.find(plugins, "wobbl"));
        assertTrue(several.getMessage().contains("matches several plugins"), several.getMessage());
        UsageError none = assertThrows(UsageError.class, () -> Plugins.find(plugins, "Nope"));
        assertEquals("No installed plugin matches 'Nope'.", none.getMessage());
    }

    @Test
    void safeFolderNamesOnly() {
        assertTrue(Plugins.safeFolderName("Acme - VST - Wobble"));
        assertTrue(Plugins.safeFolderName("L'Cronx - VST - CSXXL"));
        assertFalse(Plugins.safeFolderName(""));
        assertFalse(Plugins.safeFolderName(null));
        assertFalse(Plugins.safeFolderName("."));
        assertFalse(Plugins.safeFolderName(".."));
        assertFalse(Plugins.safeFolderName("a/b"));
        assertFalse(Plugins.safeFolderName("-starts-with-dash"));
        assertFalse(Plugins.safeFolderName("has" + (char) 0 + "nul"));
    }

    @Test
    void showHandlesEveryShapeWithoutThrowing() {
        Plugins.show(List.of(), drive(), false);
        Plugins.show(List.of(), drive(), true);
        Plugins.InstalledPlugin broken = new Plugins.InstalledPlugin("No Manifest", null, 3);
        Plugins.show(List.of(broken), drive(), true);
        Plugins.InstalledPlugin good = new Plugins.InstalledPlugin("Acme - VST - X",
                Json.parseObject("{\"name\":\"X\",\"manufacturer\":\"Acme\",\"version\":\"1\",\"kind\":\"effect\","
                        + "\"file64\":\"x.so\"}"), 10);
        Plugins.show(List.of(good), drive(), true);
        assertEquals("X", good.name());
        assertEquals(good.folder, good.label());
    }
}

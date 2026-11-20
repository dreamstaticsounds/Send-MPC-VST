package com.dreamstatic.mpcremote;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class UtilTest {

    @Test
    void quotesForTheShell() {
        assertEquals("plain", Util.shlexQuote("plain"));
        assertEquals("''", Util.shlexQuote(""));
        assertEquals("'has space'", Util.shlexQuote("has space"));
        assertEquals("'it'\"'\"'s'", Util.shlexQuote("it's"));
        assertEquals("'/media/usb drive'", Util.shlexQuote("/media/usb drive"));
    }

    @Test
    void splitsLikeShlex() {
        assertEquals(List.of("a", "b c"), Util.shlexSplit("a 'b c'"));
        assertEquals(List.of("a b"), Util.shlexSplit("a\\ b"));
        assertEquals(List.of("say", "hi"), Util.shlexSplit("say \"hi\""));
        assertEquals(List.of(), Util.shlexSplit("   "));
    }

    @Test
    void humanSizes() {
        assertEquals("0 B", Util.humanSize(0));
        assertEquals("1023 B", Util.humanSize(1023));
        assertEquals("1.0 KB", Util.humanSize(1024));
        assertEquals("8.0 MB", Util.humanSize(8L * 1024 * 1024));
        assertEquals("1.5 GB", Util.humanSize((long) (1.5 * 1024 * 1024 * 1024)));
    }

    @Test
    void stripsIllegalFilenameChars() {
        assertEquals("Acme - VST - Wobble", Util.legalFilename("Acme - VST - Wobble"));
        assertEquals("ABC", Util.legalFilename("A\"#B,C")); // " # and , are stripped too
        assertEquals("", Util.legalFilename("<>*?|"));
    }

    @Test
    void decodesProcMountEscapes() {
        assertEquals("/media/usb drive", Util.unescapeMount("/media/usb\\040drive"));
        assertEquals("/plain", Util.unescapeMount("/plain"));
    }

    @Test
    void measuresVisibleLengthAndPads() {
        String esc = String.valueOf((char) 27);
        assertEquals(3, Util.visibleLen("abc"));
        assertEquals(1, Util.visibleLen(esc + "[31mx" + esc + "[0m"));
        assertEquals("ab  ", Util.pad("ab", 4));
        assertEquals("abc", Util.pad("abc", 2)); // never truncates
    }

    @Test
    void findsMissingSiblingPaths(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path zip = tmp.resolve("out.zip");
        assertEquals(zip, Util.uniquePath(zip));
        java.nio.file.Files.createFile(zip);
        assertEquals(tmp.resolve("out-2.zip"), Util.uniquePath(zip));
        java.nio.file.Files.createFile(tmp.resolve("out-2.zip"));
        assertEquals(tmp.resolve("out-3.zip"), Util.uniquePath(zip));
    }

    @Test
    void expandsHomeDirectory() {
        String home = System.getProperty("user.home");
        assertEquals(home, Util.expandUser("~"));
        assertEquals(home + "/x.zip", Util.expandUser("~/x.zip"));
        assertEquals("/abs/path", Util.expandUser("/abs/path"));
    }

    @Test
    void baseNameHandlesBothSeparators() {
        assertEquals("c.so", Util.baseName("/a/b/c.so"));
        assertEquals("c.so", Util.baseName("C:\\a\\c.so"));
        assertEquals("c.so", Util.baseName("c.so"));
    }
}

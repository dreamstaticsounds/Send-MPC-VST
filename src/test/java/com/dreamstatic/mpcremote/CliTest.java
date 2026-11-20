package com.dreamstatic.mpcremote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CliTest {

    private static Cli parse(String... args) {
        return Cli.parse(args);
    }

    @Test
    void addressFirstThenCommand() {
        Cli c = parse("192.168.1.50", "list", "--drive", "1");
        assertEquals("192.168.1.50", c.hostArg);
        assertEquals("list", c.command);
        assertEquals("1", c.drive);
        assertNull(c.address);
    }

    @Test
    void addressOptionBeforeCommand() {
        Cli c = parse("--address", "10.0.0.1", "--user", "admin", "--port", "2222", "info");
        assertEquals("10.0.0.1", c.address);
        assertNull(c.hostArg);
        assertEquals("admin", c.user);
        assertEquals(2222, c.port);
        assertEquals("info", c.command);
    }

    @Test
    void addressOptionWithEqualsAndHostAlias() {
        assertEquals("1.2.3.4", parse("--address=1.2.3.4", "list").address);
        assertEquals("1.2.3.4", parse("--host", "1.2.3.4", "list").address);
    }

    @Test
    void globalOptionsAfterTheCommand() {
        Cli c = parse("1.2.3.4", "remove", "Some Plugin", "--yes", "--no-color", "--password", "pw");
        assertEquals("remove", c.command);
        assertEquals(List.of("Some Plugin"), c.names);
        assertTrue(c.yes);
        assertTrue(c.noColor);
        assertEquals("pw", c.password);
    }

    @Test
    void addAndUpdateTakeSources() {
        assertEquals(List.of("a.zip", "b"), parse("1.2.3.4", "add", "a.zip", "b").sources);
        assertEquals(List.of("x.zip"), parse("1.2.3.4", "update", "x.zip").sources);
        assertEquals(List.of(), parse("1.2.3.4", "add").sources);
    }

    @Test
    void importOptions() {
        Cli c = parse("1.2.3.4", "import", "p.zip", "--out", "conv", "--zip", "--replace", "--force");
        assertTrue(c.makeZip);
        assertTrue(c.replaceExisting);
        assertTrue(c.force);
        assertEquals("conv", c.out);
        assertEquals(List.of("p.zip"), c.sources);
    }

    @Test
    void exportOptions() {
        Cli c = parse("1.2.3.4", "export", "--all", "--out", "here");
        assertTrue(c.exportAll);
        assertEquals("here", c.out);
        assertEquals(List.of(), c.names);
    }

    @Test
    void shortYesFlag() {
        assertTrue(parse("1.2.3.4", "-y", "list").yes);
    }

    @Test
    void addressAfterCommandIsRejectedLikeArgparse() {
        Cli.CliError e = assertThrows(Cli.CliError.class,
                () -> parse("192.168.1.50", "list", "--address", "1.2.3.4"));
        assertEquals("send-mpc-vst", e.prog);
        assertEquals("unrecognized arguments: --address 1.2.3.4", e.getMessage());
    }

    @Test
    void unknownCommandIsAnInvalidChoice() {
        Cli.CliError e = assertThrows(Cli.CliError.class, () -> parse("192.168.1.50", "frobnicate"));
        assertEquals("send-mpc-vst", e.prog);
        assertTrue(e.getMessage().startsWith("argument command: invalid choice: 'frobnicate' (choose from info,"),
                e.getMessage());
    }

    @Test
    void importNeedsSources() {
        Cli.CliError e = assertThrows(Cli.CliError.class, () -> parse("192.168.1.50", "import"));
        assertEquals("send-mpc-vst import", e.prog);
        assertEquals("the following arguments are required: sources", e.getMessage());
    }

    @Test
    void portValidationIsScopedToTheParserThatSawIt() {
        Cli.CliError before = assertThrows(Cli.CliError.class,
                () -> parse("--port", "abc", "list"));
        assertEquals("send-mpc-vst", before.prog);
        assertEquals("argument --port: invalid int value: 'abc'", before.getMessage());

        Cli.CliError after = assertThrows(Cli.CliError.class,
                () -> parse("1.2.3.4", "list", "--port", "abc"));
        assertEquals("send-mpc-vst list", after.prog);
        assertEquals("argument --port: invalid int value: 'abc'", after.getMessage());
    }

    @Test
    void missingOptionValue() {
        Cli.CliError e = assertThrows(Cli.CliError.class, () -> parse("1.2.3.4", "list", "--password"));
        assertEquals("send-mpc-vst list", e.prog);
        assertEquals("argument --password: expected one argument", e.getMessage());
    }

    @Test
    void commandSpecificOptionsAreRejectedElsewhere() {
        Cli.CliError e = assertThrows(Cli.CliError.class, () -> parse("1.2.3.4", "list", "--all"));
        assertEquals("unrecognized arguments: --all", e.getMessage());
        Cli.CliError e2 = assertThrows(Cli.CliError.class, () -> parse("1.2.3.4", "list", "--zip"));
        assertEquals("unrecognized arguments: --zip", e2.getMessage());
        Cli.CliError e3 = assertThrows(Cli.CliError.class, () -> parse("1.2.3.4", "list", "junk"));
        assertEquals("unrecognized arguments: junk", e3.getMessage());
    }

    @Test
    void helpIsRequestedNotAnError() {
        Cli.HelpRequested h = assertThrows(Cli.HelpRequested.class, () -> parse("--help"));
        assertTrue(h.text.contains("Manage VST2 plugins on an Akai MPC over the network."));
        Cli.HelpRequested sub = assertThrows(Cli.HelpRequested.class, () -> parse("1.2.3.4", "add", "-h"));
        assertTrue(sub.text.contains("usage: send-mpc-vst add"));
    }

    @Test
    void usageTextIsScopedLikeArgparse() {
        assertTrue(Cli.usageText("send-mpc-vst").contains("{info,drives,list,doctor,restart,"));
        assertTrue(Cli.usageText("send-mpc-vst import").contains("sources [sources ...]"));
        assertTrue(Cli.usageText("send-mpc-vst export").contains("--all"));
        assertTrue(Cli.usageText("send-mpc-vst list").endsWith("[--no-color]"));
    }
}

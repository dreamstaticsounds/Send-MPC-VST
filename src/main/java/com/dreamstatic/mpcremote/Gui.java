package com.dreamstatic.mpcremote;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.TransferHandler.TransferSupport;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * The application window: connect to an MPC over the network, then list, install, update,
 * remove, export and diagnose its VST2 plugins. All network work runs on a background
 * thread and streams into the report, so the window never freezes — the same engine the
 * command line uses, driven with buttons instead of prompts.
 */
public final class Gui {

    private static final int LOG_COLUMNS = 96;

    // ---------------------------------------------------------------- controls

    private final JTextField address = new JTextField();
    private final JTextField user = new JTextField("root");
    private final JPasswordField password = new JPasswordField();
    private final JTextField port = new JTextField("22");
    private final JButton connectBtn = new JButton("Connect");

    private final JComboBox<DriveItem> driveCombo = new JComboBox<>();
    private final JLabel driveInfo = new JLabel(" ");

    private final PluginTableModel tableModel = new PluginTableModel();
    private final JTable table = new JTable(tableModel);

    private final JButton addBtn = new JButton("Add…");
    private final JButton updateBtn = new JButton("Update…");
    private final JButton removeBtn = new JButton("Remove");
    private final JButton exportBtn = new JButton("Export…");
    private final JButton refreshBtn = new JButton("Refresh");
    private final JButton importBtn = new JButton("Import package…");
    private final JButton doctorBtn = new JButton("Doctor");
    private final JButton restartBtn = new JButton("Restart MPC");
    private final JButton hideBtn = new JButton("Hide");

    private final JTextArea log = new JTextArea(16, LOG_COLUMNS);
    private final JLabel status = new JLabel("Not connected.");
    private final JProgressBar progress = new JProgressBar();

    private final JPanel root = new JPanel();

    // report minimisation (Hide/Show) — the toggle row stays visible so it can be undone
    private JPanel reportHolder;
    private JPanel reportWrapper;
    private JPanel reportContent;
    private JScrollPane tableScroll;
    private JFrame frame;
    private boolean reportHidden;
    private static final int TABLE_BASE_HEIGHT = 150;

    private final Preferences prefs = Preferences.userNodeForPackage(Gui.class);
    private final SwingBackend backend;

    /** Everything a connected session owns; written on the EDT only. */
    private Session session;
    private boolean busy;
    /** Suppress the drive-listener refresh while the combo is filled programmatically. */
    private boolean suppressRefresh;
    private File lastDir;

    private static final class Session {
        final Remote remote;
        final String host;
        final int port;
        final Map<String, String> info;
        final List<Drive> drives;
        final Path workdir;

        Session(Remote remote, String host, int port, Map<String, String> info, List<Drive> drives, Path workdir) {
            this.remote = remote;
            this.host = host;
            this.port = port;
            this.info = info;
            this.drives = drives;
            this.workdir = workdir;
        }

        void close() {
            try {
                remote.close();
            } finally {
                Util.deleteRecursively(workdir);
            }
        }
    }

    public Gui() {
        buildPanel();
        // panels are sized to the report area, not to any terminal
        Util.setTerminalWidth(LOG_COLUMNS);
        backend = new SwingBackend(this::append);
        Ui.setBackend(backend);
        loadPrefs();
        log.setText("Send MPC VST — install and manage VST2 plugins on your MPC over the network.\n"
                + "\n"
                + "The MPC needs developer mode on (Preferences → Info) and External Plugin Support\n"
                + "enabled (Preferences → Enhancements (Beta)). Enter its address above and press\n"
                + "Connect; everything this session does is reported here.\n");
    }

    /** The whole window contents, without the frame (so it can be built headless for tests). */
    public JComponent panel() {
        return root;
    }

    private void buildPanel() {
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        root.add(stretch(connectPanel()));
        root.add(stretch(drivePanel()));
        root.add(stretch(pluginsPanel()));
        root.add(stretch(reportPanel()));
        root.add(stretch(statusPanel()));

        addBtn.addActionListener(e -> chooseSourcesAndRun("Add plugins", "Adding…", false));
        updateBtn.addActionListener(e -> chooseSourcesAndRun("Update plugins", "Updating…", true));
        removeBtn.addActionListener(e -> removeSelected());
        exportBtn.addActionListener(e -> exportPlugins());
        refreshBtn.addActionListener(e -> refreshTable("Ready."));
        importBtn.addActionListener(e -> importPackage());
        doctorBtn.addActionListener(e -> runOp("Checking the MPC…", "Check finished.", false,
                ctx -> Actions.actionDoctor(ctx, session.drives)));
        restartBtn.addActionListener(e -> runOp("Restarting the MPC application…", "Done — see the report.",
                false, ctx -> Actions.actionRestart(ctx, false)));
        connectBtn.addActionListener(e -> {
            if (session == null) {
                connect();
            } else {
                disconnect();
            }
        });
        driveCombo.addActionListener(e -> {
            updateDriveInfo();
            updateEnabled();
            if (!suppressRefresh && session != null && !busy) {
                refreshTable("Ready.");
            }
        });
        hideBtn.addActionListener(e -> toggleReport());
        bindListKeys();

        updateEnabled();
    }

    private static JComponent stretch(JComponent c) {
        c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return c;
    }

    private static JPanel titled(String title, JComponent inner) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(inner, BorderLayout.CENTER);
        return p;
    }

    // ---------------------------------------------------------------- connection

    private JPanel connectPanel() {
        user.setColumns(8);
        password.setColumns(12);
        port.setColumns(4);
        address.setColumns(18);
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;

        c.gridx = 0;
        p.add(new JLabel("Address:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(address, c);

        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel("User:"), c);
        c.gridx = 3;
        p.add(user, c);

        c.gridx = 4;
        p.add(new JLabel("Password:"), c);
        c.gridx = 5;
        p.add(password, c);

        c.gridx = 6;
        p.add(new JLabel("Port:"), c);
        c.gridx = 7;
        c.fill = GridBagConstraints.NONE;
        p.add(port, c);

        c.gridx = 8;
        c.insets = new Insets(2, 10, 2, 4);
        p.add(connectBtn, c);

        address.setToolTipText("The MPC's IP address (Preferences → Network on the device)");
        password.setToolTipText("Leave blank to use your SSH keys");
        return titled("MPC", p);
    }

    private JPanel drivePanel() {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        driveCombo.setPreferredSize(new Dimension(300, driveCombo.getPreferredSize().height));
        p.add(new JLabel("Drive:"), BorderLayout.WEST);
        p.add(driveCombo, BorderLayout.CENTER);
        p.add(driveInfo, BorderLayout.EAST);
        return titled("Drive", p);
    }

    private void updateDriveInfo() {
        DriveItem item = (DriveItem) driveCombo.getSelectedItem();
        if (item == null) {
            driveInfo.setText(" ");
            return;
        }
        Drive d = item.drive;
        driveInfo.setText(d.describe() + (d.recommended() ? "   ★ recommended" : ""));
    }

    // ---------------------------------------------------------------- plugins

    private JPanel pluginsPanel() {
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        int[] widths = {240, 130, 60, 70, 80, 80};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(JLabel.RIGHT);
        table.getColumnModel().getColumn(5).setCellRenderer(right);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(840, TABLE_BASE_HEIGHT));
        tableScroll = scroll;

        JPanel row1 = new JPanel();
        row1.setLayout(new BoxLayout(row1, BoxLayout.X_AXIS));
        for (JButton b : List.of(addBtn, updateBtn, removeBtn, exportBtn)) {
            row1.add(b);
            row1.add(Box.createHorizontalStrut(6));
        }
        row1.add(Box.createHorizontalGlue());

        JPanel row2 = new JPanel();
        row2.setLayout(new BoxLayout(row2, BoxLayout.X_AXIS));
        row2.add(importBtn);
        row2.add(Box.createHorizontalStrut(6));
        row2.add(doctorBtn);
        row2.add(Box.createHorizontalStrut(6));
        row2.add(restartBtn);
        row2.add(Box.createHorizontalStrut(6));
        row2.add(refreshBtn);
        row2.add(Box.createHorizontalGlue());

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        row1.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row2.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        buttons.add(row1);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(row2);

        JPanel inner = new JPanel(new BorderLayout(0, 6));
        inner.add(scroll, BorderLayout.CENTER);
        inner.add(buttons, BorderLayout.SOUTH);
        return titled("Installed plugins  (drag & drop a plugin zip or folder here to add it)", inner);
    }

    /** Read the form and run one engine operation on a background thread. */
    @FunctionalInterface
    private interface Operation {
        void run(Context ctx) throws Exception;
    }

    private void runOp(String busyText, String okText, boolean mutating, Operation op) {
        if (busy) {
            return;
        }
        Session s = session;
        if (s == null) {
            status.setText("Not connected.");
            return;
        }
        Drive drive = selectedDrive();
        if (drive == null) {
            status.setText("No drive selected.");
            return;
        }
        setBusy(true);
        status.setText(busyText);
        Thread worker = new Thread(() -> {
            String failure = null;
            try {
                op.run(new Context(s.remote, s.info, drive, false, s.workdir));
            } catch (UsageError e) {
                failure = e.getMessage();
                Ui.say("  " + Ui.S.badc(Ui.S.bad) + " " + e.getMessage());
            } catch (SshError e) {
                failure = e.kind;
                e.explain(s.host, s.port, s.remote.password != null);
            } catch (Exception e) {
                failure = String.valueOf(e);
                Ui.say("  " + Ui.S.badc(Ui.S.bad) + " " + e);
                e.printStackTrace();
            }
            String err = failure;
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                if (err == null) {
                    if (mutating) {
                        refreshTable(okText);
                    } else {
                        status.setText(okText);
                    }
                } else {
                    status.setText("Failed — see the report.");
                }
            });
        }, "mpc-remote-op");
        worker.setDaemon(true);
        worker.start();
    }

    private void connect() {
        String host = address.getText().trim();
        if (host.isEmpty()) {
            status.setText("Enter the MPC's address first.");
            address.requestFocusInWindow();
            return;
        }
        String userText = user.getText().trim();
        final String loginUser = userText.isEmpty() ? "root" : userText;
        final int loginPort;
        try {
            loginPort = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            status.setText("Port must be a number.");
            port.requestFocusInWindow();
            return;
        }
        String pass = new String(password.getPassword()).trim();
        final String loginPass = pass.isEmpty() ? null : pass;

        setBusy(true);
        status.setText("Connecting to " + host + "…");
        Thread worker = new Thread(() -> {
            Session old = session;
            if (old != null) {
                old.close();
            }
            Remote remote = new Remote(host, loginUser, loginPort, loginPass);
            Session made = null;
            String err = null;
            try {
                Remote.connect(remote);
                Map<String, String> info = DeviceInfo.fetch(remote);
                Ui.say();
                Ui.panel("Your MPC", DeviceInfo.infoRows(host, info), null);
                Ui.say();
                List<Drive> drives = Drive.discover(remote);
                if (drives.isEmpty()) {
                    Ui.failPanel("No drives found", List.of(
                            "The MPC has nothing mounted under /media (or /nvme).",
                            "Insert a USB drive or SD card (formatted FAT32 / exFAT / ext4) and try again."
                    ), "warn");
                    remote.close();
                    err = "No drives found on the MPC.";
                } else {
                    Ui.say();
                    Drive.show(drives);
                    Ui.say();
                    made = new Session(remote, host, loginPort, info, drives,
                            Files.createTempDirectory("mpcr-work-"));
                }
            } catch (SshError e) {
                e.explain(host, loginPort, remote.password != null);
                err = "Connection failed (" + e.kind + ") — see the report.";
                remote.close();
            } catch (Exception e) {
                err = "Connection failed: " + e;
                Ui.say("  " + Ui.S.badc(Ui.S.bad) + " " + e);
                e.printStackTrace();
                remote.close();
            }
            Session result = made;
            String failure = err;
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                session = result;
                if (result == null) {
                    status.setText(failure);
                    updateEnabled();
                    return;
                }
                prefs.put("address", host);
                prefs.put("user", loginUser);
                prefs.put("port", Integer.toString(loginPort));
                suppressRefresh = true;
                driveCombo.removeAllItems();
                for (Drive d : result.drives) {
                    driveCombo.addItem(new DriveItem(d));
                }
                suppressRefresh = false;
                String model = result.info.getOrDefault("dt.model", "");
                status.setText("Connected — " + (model.isEmpty() ? host : model + " @ " + host));
                updateEnabled();
                refreshTable("Ready.");
            });
        }, "mpc-remote-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void disconnect() {
        Session s = session;
        if (s == null) {
            return;
        }
        setBusy(true);
        status.setText("Disconnecting…");
        Thread worker = new Thread(() -> {
            s.close();
            SwingUtilities.invokeLater(() -> {
                session = null;
                suppressRefresh = true;
                driveCombo.removeAllItems();
                suppressRefresh = false;
                tableModel.setRows(List.of());
                setBusy(false);
                status.setText("Disconnected.");
                updateEnabled();
            });
        }, "mpc-remote-disconnect");
        worker.setDaemon(true);
        worker.start();
    }

    /** Fill the report when a window closes, so a stray ssh control socket is cleaned up. */
    private void closeSession() {
        Session s = session;
        session = null;
        if (s != null) {
            Thread worker = new Thread(s::close, "mpc-remote-close");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void refreshTable(String okText) {
        runOp("Reading installed plugins…", okText, false, ctx -> {
            Plugins.Listing listing = Plugins.list(ctx.remote, ctx.drive);
            SwingUtilities.invokeLater(() -> tableModel.setRows(listing.plugins()));
        });
    }

    private void chooseSourcesAndRun(String title, String busyText, boolean replace) {
        List<String> sources = chooseSources(title);
        if (sources.isEmpty()) {
            return;
        }
        runOp(busyText, "Done — see the report.", true,
                ctx -> Actions.actionAdd(ctx, sources, replace));
    }

    private void importPackage() {
        List<String> sources = chooseSources("Choose community package(s) to import");
        if (sources.isEmpty()) {
            return;
        }
        runOp("Converting and installing…", "Done — see the report.", true, ctx -> {
            Path out = ctx.workdir.resolve("converted");
            List<String> folders = Actions.actionConvert(sources, out.toString(), false, false);
            if (folders == null) {
                return; // conversion failed; already reported
            }
            Actions.actionAdd(ctx, folders, false);
        });
    }

    private void removeSelected() {
        List<String> folders = selectedFolders();
        if (folders.isEmpty()) {
            status.setText("Select a plugin to remove first.");
            return;
        }
        runOp("Removing…", "Done — see the report.", true,
                ctx -> Actions.actionRemove(ctx, folders));
    }

    private void exportPlugins() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save exported plugins to…");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (lastDir != null) {
            fc.setCurrentDirectory(lastDir);
        }
        if (fc.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        lastDir = fc.getSelectedFile();
        List<String> names = selectedFolders();
        String out = lastDir.getAbsolutePath();
        runOp("Exporting…", "Exported — see the report.", false,
                ctx -> Actions.actionExport(ctx, names, names.isEmpty(), out));
    }

    private List<String> chooseSources(String title) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(title);
        fc.setMultiSelectionEnabled(true);
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (lastDir != null) {
            fc.setCurrentDirectory(lastDir);
        }
        if (fc.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (File f : fc.getSelectedFiles()) {
            out.add(f.getAbsolutePath());
        }
        if (out.isEmpty() && fc.getSelectedFile() != null) {
            out.add(fc.getSelectedFile().getAbsolutePath());
        }
        if (!out.isEmpty()) {
            lastDir = new File(out.get(0)).getParentFile();
        }
        return out;
    }

    // ---------------------------------------------------------------- table plumbing

    private Drive selectedDrive() {
        DriveItem item = (DriveItem) driveCombo.getSelectedItem();
        return item == null ? null : item.drive;
    }

    private List<String> selectedFolders() {
        List<String> folders = new ArrayList<>();
        for (int viewRow : table.getSelectedRows()) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            folders.add(tableModel.folderAt(modelRow));
        }
        return folders;
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisible(b);
        updateEnabled();
    }

    private void updateEnabled() {
        boolean can = session != null && selectedDrive() != null && !busy;
        addBtn.setEnabled(can);
        updateBtn.setEnabled(can);
        removeBtn.setEnabled(can);
        exportBtn.setEnabled(can);
        refreshBtn.setEnabled(can);
        importBtn.setEnabled(can);
        doctorBtn.setEnabled(can);
        restartBtn.setEnabled(can);
        driveCombo.setEnabled(session != null && !busy);
        connectBtn.setEnabled(!busy);
        connectBtn.setText(session == null ? "Connect" : "Disconnect");
    }

    private void append(String line) {
        SwingUtilities.invokeLater(() -> {
            log.append(line + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    // ---------------------------------------------------------------- report + status

    private JPanel reportPanel() {
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(log);
        scroll.setPreferredSize(new Dimension(840, 260));

        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> log.setText(""));
        JPanel south = new JPanel(new BorderLayout());
        south.add(Box.createHorizontalGlue(), BorderLayout.CENTER);
        south.add(clear, BorderLayout.EAST);

        reportContent = new JPanel(new BorderLayout(0, 4));
        reportContent.add(scroll, BorderLayout.CENTER);
        reportContent.add(south, BorderLayout.SOUTH);

        // The toggle row lives outside the collapsible part, so "Show" survives "Hide".
        JPanel toggleRow = new JPanel(new BorderLayout());
        toggleRow.add(hideBtn, BorderLayout.EAST);

        reportWrapper = new JPanel(new BorderLayout(0, 4));
        reportWrapper.add(reportContent, BorderLayout.CENTER);
        reportWrapper.add(toggleRow, BorderLayout.SOUTH);

        reportHolder = titled("Report", reportWrapper);
        return reportHolder;
    }

    /** Collapse the report to its title bar and give the freed space to the plugin table. */
    private void toggleReport() {
        int extra = reportContent.getPreferredSize().height;
        reportHidden = !reportHidden;
        if (reportHidden) {
            reportWrapper.remove(reportContent);
            reportContent.setVisible(false);
            hideBtn.setText("Show");
            tableScroll.setPreferredSize(new Dimension(840, TABLE_BASE_HEIGHT + extra));
        } else {
            reportContent.setVisible(true);
            reportWrapper.add(reportContent, BorderLayout.CENTER);
            hideBtn.setText("Hide");
            tableScroll.setPreferredSize(new Dimension(840, TABLE_BASE_HEIGHT));
        }
        if (frame != null) {
            frame.pack();
        }
        root.revalidate();
        root.repaint();
    }

    /** Home selects the first plugin, End the last (translated through the row sorter). */
    private void bindListKeys() {
        for (int map : new int[]{JComponent.WHEN_FOCUSED, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT}) {
            table.getInputMap(map).put(javax.swing.KeyStroke.getKeyStroke("HOME"), "mpc.firstRow");
            table.getInputMap(map).put(javax.swing.KeyStroke.getKeyStroke("END"), "mpc.lastRow");
        }
        table.getActionMap().put("mpc.firstRow", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                selectAbsoluteRow(true);
            }
        });
        table.getActionMap().put("mpc.lastRow", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                selectAbsoluteRow(false);
            }
        });
    }

    private void selectAbsoluteRow(boolean first) {
        int rows = table.getRowCount();
        if (rows == 0) {
            return;
        }
        int view = table.convertRowIndexToView(first ? 0 : rows - 1);
        table.setRowSelectionInterval(view, view);
        table.scrollRectToVisible(table.getCellRect(view, 0, true));
    }

    private JPanel statusPanel() {
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(160, 14));
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(2, 4, 0, 4));
        p.add(status, BorderLayout.CENTER);
        p.add(progress, BorderLayout.EAST);
        return p;
    }

    // ---------------------------------------------------------------- prefs

    private void loadPrefs() {
        address.setText(prefs.get("address", ""));
        user.setText(prefs.get("user", "root"));
        port.setText(prefs.get("port", "22"));
    }

    // ---------------------------------------------------------------- drag & drop

    private void enableDragAndDrop() {
        table.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support) || busy || session == null) {
                    return false;
                }
                try {
                    Transferable t = support.getTransferable();
                    List<String> sources = new ArrayList<>();
                    for (File f : (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor)) {
                        sources.add(f.getAbsolutePath());
                    }
                    if (sources.isEmpty()) {
                        return false;
                    }
                    runOp("Adding…", "Done — see the report.", true,
                            ctx -> Actions.actionAdd(ctx, sources, false));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });
        try {
            table.setDragEnabled(true);
        } catch (java.awt.HeadlessException e) {
            // no mouse in this session; dropping still works when a display is present
        }
    }

    // ---------------------------------------------------------------- show / selftest

    /** Shows the window. */
    public void show() {
        enableDragAndDrop();
        JFrame f = new JFrame("Send MPC VST");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeSession();
            }
        });
        backend.setParent(f);
        f.add(panel());
        f.pack();
        f.setLocationByPlatform(true);
        f.setVisible(true);
        this.frame = f;
        address.requestFocusInWindow();
    }

    /**
     * Builds the panel and checks the defaults without a display, so a broken build is
     * caught even on a machine where no window can open.
     */
    public static int selftest() {
        try {
            Gui g = new Gui();
            if (g.session != null) {
                throw new IllegalStateException("should start disconnected");
            }
            if (g.addBtn.isEnabled() || g.removeBtn.isEnabled() || g.restartBtn.isEnabled()) {
                throw new IllegalStateException("plugin buttons should start disabled");
            }
            if (!"Connect".equals(g.connectBtn.getText())) {
                throw new IllegalStateException("connect button should start as Connect");
            }
            if (!"root".equals(g.user.getText()) || !"22".equals(g.port.getText())) {
                throw new IllegalStateException("user/port defaults wrong: " + g.user.getText()
                        + "/" + g.port.getText());
            }
            if (g.tableModel.getRowCount() != 0 || g.driveCombo.getItemCount() != 0) {
                throw new IllegalStateException("table and drive list should start empty");
            }
            if (!"Not connected.".equals(g.status.getText())) {
                throw new IllegalStateException("initial status should be Not connected.");
            }
            if (g.progress.isVisible()) {
                throw new IllegalStateException("progress bar should start hidden");
            }
            if (!(Ui.backend() instanceof SwingBackend)) {
                throw new IllegalStateException("the GUI should have installed its backend");
            }
            // the report's plugin rows are formatted the same way the CLI table shows them
            g.tableModel.setRows(List.of(new Plugins.InstalledPlugin("Acme - VST - X",
                    Json.parseObject("{\"name\":\"X\",\"manufacturer\":\"Acme\",\"version\":\"1.2\","
                            + "\"kind\":\"effect\",\"file64\":\"x.so\",\"file32\":\"x32.so\"}"),
                    1)));
            if (g.tableModel.getRowCount() != 1) {
                throw new IllegalStateException("table should show one row");
            }
            if (!"Acme - VST - X".equals(g.tableModel.getValueAt(0, 0))) {
                throw new IllegalStateException("folder column wrong: " + g.tableModel.getValueAt(0, 0));
            }
            if (!"arm64/arm32".equals(g.tableModel.getValueAt(0, 4))) {
                throw new IllegalStateException("builds column wrong: " + g.tableModel.getValueAt(0, 4));
            }
            if (!"1.0 KB".equals(g.tableModel.getValueAt(0, 5))) {
                throw new IllegalStateException("size column wrong: " + g.tableModel.getValueAt(0, 5));
            }
            g.tableModel.setRows(List.of());
            // Home/End jump to the first/last plugin
            javax.swing.InputMap im = g.table.getInputMap(JComponent.WHEN_FOCUSED);
            if (im.get(javax.swing.KeyStroke.getKeyStroke("HOME")) == null) {
                throw new IllegalStateException("Home should be bound on the plugin table");
            }
            if (im.get(javax.swing.KeyStroke.getKeyStroke("END")) == null) {
                throw new IllegalStateException("End should be bound on the plugin table");
            }
            // Hide collapses the report and gives its height to the table; Show puts it back
            int base = g.tableScroll.getPreferredSize().height;
            g.hideBtn.doClick();
            if (!"Show".equals(g.hideBtn.getText())) {
                throw new IllegalStateException("Hide should relabel itself Show, got " + g.hideBtn.getText());
            }
            if (g.tableScroll.getPreferredSize().height <= base) {
                throw new IllegalStateException("Hide should grow the plugin table (base " + base
                        + ", now " + g.tableScroll.getPreferredSize().height + ")");
            }
            if (g.reportContent.isVisible() || g.reportWrapper.getComponentCount() > 1) {
                throw new IllegalStateException("Hide should remove the report body from its wrapper");
            }
            g.hideBtn.doClick();
            if (!"Hide".equals(g.hideBtn.getText())
                    || g.tableScroll.getPreferredSize().height != base
                    || !g.reportContent.isVisible()) {
                throw new IllegalStateException("Show should restore the report and the table height");
            }
            System.out.println("gui selftest: panel built, defaults ok");
            return 0;
        } catch (RuntimeException e) {
            System.out.println("gui selftest FAILED: " + e);
            e.printStackTrace(System.out);
            return 1;
        }
    }

    // ---------------------------------------------------------------- small types

    /** A drive in the combo box; toString() is the line shown. */
    private static final class DriveItem {
        final Drive drive;

        DriveItem(Drive drive) {
            this.drive = drive;
        }

        @Override
        public String toString() {
            return drive.name + "  —  " + drive.mount;
        }
    }

    /** The installed-plugins table: Folder, Manufacturer, Version, Kind, Builds, Size. */
    private static final class PluginTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"Folder", "Manufacturer", "Ver", "Kind", "Builds", "Size"};
        private transient List<Plugins.InstalledPlugin> rows = List.of();

        void setRows(List<Plugins.InstalledPlugin> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        String folderAt(int row) {
            return rows.get(row).folder;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Plugins.InstalledPlugin p = rows.get(rowIndex);
            Map<String, Object> m = p.manifest;
            return switch (columnIndex) {
                case 0 -> p.folder;
                case 1 -> text(m, "manufacturer");
                case 2 -> text(m, "version");
                case 3 -> text(m, "kind");
                case 4 -> builds(m);
                default -> Util.humanSize(p.size);
            };
        }

        private static String text(Map<String, Object> m, String key) {
            Object v = m.get(key);
            return v == null ? "" : String.valueOf(v);
        }

        /** Same arch list the CLI's table prints: arm64 first, then arm32. */
        private static String builds(Map<String, Object> m) {
            List<String> archs = new ArrayList<>();
            if (m.get("file64") != null) {
                archs.add("arm64");
            }
            if (m.get("file32") != null) {
                archs.add("arm32");
            }
            return String.join("/", archs);
        }
    }
}

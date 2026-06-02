import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * TinyCraft Launcher.
 *
 * Как запустить лаунчер:
 *   javac -encoding UTF-8 Launcher.java
 *   java Launcher
 *
 * Как он работает:
 *   1. Ищет папку TinyCraft рядом с Launcher.class или Launcher.jar.
 *   2. Если выбранной версии нет, скачивает zip выбранной версии.
 *   3. Распаковывает версию в TinyCraft/<версия>.
 *   4. Если в zip уже есть TinyCraft.jar, запускает его сразу.
 *   5. Если в zip есть готовая папка out/, создает TinyCraft.jar без javac.
 *   6. Если пришли только исходники, собирает их как fallback.
 *   7. Запускает игру командой java -jar TinyCraft.jar.
 *
 * Для игроков лучше использовать prebuilt zip из release/launcher-versions:
 * там уже лежит TinyCraft.jar, поэтому JDK игроку не нужен.
 */
public class Launcher {
    private static final String GAME_FOLDER_NAME = "TinyCraft";
    private static final String GAME_JAR_NAME = "TinyCraft.jar";
    private static final String MAIN_CLASS = "TinyCraft";
    private static final String VERSIONS_DIRECTORY_NAME = "versions";
    private static final String LOGS_DIRECTORY_NAME = "logs";
    private static final String LAUNCHER_SETTINGS_FILE = "launcher.properties";
    private static final String LAUNCHER_VERSION = "v1.2";
    private static final String LAUNCHER_RELEASES_URL = "https://github.com/vakisnn-gd/TinyCraftLauncher/releases";
    private static final String LAUNCHER_UPDATE_MANIFEST_URL = "";
    private static final String LAUNCHER_UPDATE_MANIFEST_NAME = "launcher-update.txt";
    private static final String KEY_LAST_VERSION = "lastVersion";
    private static final String KEY_PLAYER_NAME = "playerName";
    private static final String KEY_KEEP_OPEN = "keepLauncherOpen";
    private static final String KEY_THEME = "theme";
    private static final Color DEEP_GRASS = new Color(34, 84, 47);
    private static final Color GRASS = new Color(76, 155, 72);
    private static final Color LIGHT_GRASS = new Color(129, 194, 97);
    private static final Color LEGACY_PANEL = new Color(72, 76, 76);
    private static final Color LEGACY_FIELD = new Color(82, 84, 84);
    private static final Color LEGACY_TEXT = new Color(220, 220, 214);

    /*
     * Позже можно вынести список версий в отдельный файл на сайте/GitHub.
     * Формат manifest-файла:
     * v0.2 Final|release|8|https://github.com/vakisnn-gd/TinyCraft/archive/refs/tags/v0.2.zip
     * v0.1|release|17|https://github.com/vakisnn-gd/TinyCraft/archive/refs/tags/v0.1.zip
     *
     * Пока строка пустая, лаунчер сначала ищет bundled manifest
     * launcher-versions/versions.txt рядом с собой, затем использует
     * LOCAL_VERSIONS ниже.
     */
    private static final String VERSION_MANIFEST_URL = "";

    private static String releaseAssetUrl(String tag, String assetName) {
        return "https://github.com/vakisnn-gd/TinyCraft/releases/download/" + tag + "/" + assetName;
    }

    /*
     * Реальные ссылки на GitHub source archives по тегам репозитория.
     * GitHub сам отдает zip для каждого тега по такому адресу.
     */
    private static final GameVersion[] LOCAL_VERSIONS = {
            new GameVersion("v0.2.1", "v0.2.1", "release", 8, MAIN_CLASS, "", releaseAssetUrl("v0.2.1", "TinyCraft-v0.2.1-windows.zip")),
            new GameVersion("v0.2-final", "v0.2 Final", "release", 8, MAIN_CLASS, "", releaseAssetUrl("v0.2", "TinyCraft-v0.2-final-windows.zip")),
            new GameVersion("v0.2-snapshot8", "v0.2 Snapshot 8", "snapshot", 8, MAIN_CLASS, "", releaseAssetUrl("v0.2-snapshot8", "TinyMinecraft-v0.2-snapshot8-windows.zip")),
            new GameVersion("v0.2-snapshot7", "v0.2 Snapshot 7", "snapshot", 17, MAIN_CLASS, "", releaseAssetUrl("v0.2-snapshot7", "TinyMinecraft-v0.2-snapshot7-windows.zip")),
            new GameVersion("v0.2-snapshot6", "v0.2 Snapshot 6", "snapshot", 17, MAIN_CLASS, "", releaseAssetUrl("v0.2-snapshot6", "TinyMinecraft-v0.2-snapshot6-windows.zip")),
            new GameVersion("v0.2-snapshot5", "v0.2 Snapshot 5", "snapshot", 17, MAIN_CLASS, "", releaseAssetUrl("v0.2-snapshot5", "TinyMinecraft-v0.2-snapshot5-windows.zip")),
            new GameVersion("v0.2-snapshot4", "v0.2 Snapshot 4", "snapshot", 17, MAIN_CLASS, "", releaseAssetUrl("v0.2-snapshot4", "TinyMinecraft-v0.2-snapshot4-windows.zip")),
            new GameVersion("v0.2-snapshot3", "v0.2 Snapshot 3", "snapshot", 17, MAIN_CLASS, "", releaseAssetUrl("snapshot-0.2-v3", "TinyMinecraft-v0.2-snapshot3-windows.zip")),
            new GameVersion("v0.2-snapshot2", "v0.2 Snapshot 2", "snapshot", 17, MAIN_CLASS, "", releaseAssetUrl("v0.2-snapshot2", "TinyMinecraft-v0.2-snapshot2-windows.zip")),
            new GameVersion("v0.2-snapshot1", "v0.2 Snapshot 1", "snapshot", 17, "TinyMinecraft", "", releaseAssetUrl("v0.2-snapshot1", "TinyMinecraft-v0.2-snapshot1-windows.zip")),
            new GameVersion("v0.1", "v0.1", "release", 17, "TinyMinecraft", "", releaseAssetUrl("v0.1", "TinyMinecraft-v0.1-windows.zip")),
            new GameVersion("v0.0.8", "v0.0.8", "classic", 17, "TinyMinecraft", "", releaseAssetUrl("v0.0.8", "TinyMinecraft-v0.0.8-windows.zip")),
            new GameVersion("v0.0.7", "v0.0.7", "classic", 17, "TinyMinecraft", "", releaseAssetUrl("v0.0.7", "TinyMinecraft-v0.0.7-windows.zip")),
            new GameVersion("v0.0.6", "v0.0.6", "classic", 17, "TinyMinecraft", "", releaseAssetUrl("v0.0.6", "TinyMinecraft-v0.0.6-windows.zip")),
            new GameVersion("v0.0.5", "v0.0.5", "classic", 17, "TinyMinecraft", "", releaseAssetUrl("v0.0.5", "TinyMinecraft-v0.0.5-windows.zip")),
            new GameVersion("v0.0.4", "v0.0.4", "classic", 17, "TinyMinecraft", "", releaseAssetUrl("v0.0.4", "TinyMinecraft-v0.0.4-windows.zip")),
            new GameVersion("v0.0.3", "v0.0.3", "classic", 17, "TinyMinecraft", "", releaseAssetUrl("v0.0.3", "TinyMinecraft-v0.0.3-windows.zip")),
            new GameVersion("v0.0.2", "v0.0.2", "classic", 17, "TinyMinecraft", "", releaseAssetUrl("v0.0.2", "TinyMinecraft-v0.0.2-windows.zip")),
            new GameVersion("v0.0.1", "v0.0.1", "classic", 17, "TinyMinecraft", "", releaseAssetUrl("v0.0.1", "TinyMinecraft-v0.0.1-windows.zip")),
            new GameVersion("v0.0.0", "v0.0.0", "classic", 17, "TinyMinecraft", "", releaseAssetUrl("v0.0.0", "TinyMinecraft-v0.0.0-windows.zip"))
    };

    private final JFrame frame = new JFrame("TinyCraft Launcher");
    private final JComboBox<GameVersion> versionBox = new JComboBox<GameVersion>();
    private final JButton playButton = new ActionButton("Играть");
    private final JButton openFolderButton = new SmallIconButton("Папка");
    private final JButton reloadButton = new SmallIconButton("Обновить");
    private final JButton settingsButton = new SmallIconButton("Настройки");
    private final JButton infoButton = new SmallIconButton("Инфо");
    private final JButton themeButton = new ThemeButton();
    private final JButton cancelButton = new SmallIconButton("Отмена");
    private final JTextField nameField = new JTextField("Player");
    private final JCheckBox forceUpdateBox = new JCheckBox("Обновить клиент");
    private final JCheckBox keepLauncherOpenBox = new JCheckBox("Не закрывать лаунчер");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel("Готово");
    private final JLabel versionInfoLabel = new JLabel(" ");
    private final JLabel launcherInfoLabel = new JLabel("Launcher " + LAUNCHER_VERSION);
    private JPanel formPanel;
    private LegacyBackgroundPanel rootPanel;
    private int themeIndex = 0;
    private boolean loadingVersions;
    private InstallAndRunWorker currentWorker;
    private String launcherUpdateUrl = LAUNCHER_RELEASES_URL;

    public static void main(String[] args) {
        configureLookAndFeel();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new Launcher().show();
            }
        });
    }

    private static void configureLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // The default Swing look is fine if the system look and feel is unavailable.
        }
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                saveLastSelectedVersion();
            }
        });
        frame.setMinimumSize(new Dimension(860, 620));
        frame.setSize(new Dimension(900, 660));
        frame.setIconImage(createBlockImage(128));

        rootPanel = new LegacyBackgroundPanel();
        rootPanel.setLayout(new GridBagLayout());

        JPanel form = new JPanel(new GridBagLayout());
        formPanel = form;
        form.setBackground(LEGACY_PANEL);
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(88, 94, 94), 6),
                BorderFactory.createEmptyBorder(30, 40, 28, 40)
        ));
        form.setPreferredSize(new Dimension(620, 510));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 8, 5, 8);
        c.fill = GridBagConstraints.HORIZONTAL;

        JPanel titleRow = new JPanel(new BorderLayout(14, 0));
        titleRow.setOpaque(false);
        styleThemeButton();
        titleRow.add(themeButton, BorderLayout.WEST);
        JLabel sectionLabel = new JLabel("TinyCraft Launcher");
        sectionLabel.setForeground(LEGACY_TEXT);
        sectionLabel.setFont(sectionLabel.getFont().deriveFont(Font.BOLD, 30f));
        titleRow.add(sectionLabel, BorderLayout.CENTER);
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(titleRow, c);

        JLabel nameLabel = legacyLabel("Ник:");
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(nameLabel, c);

        c.gridx = 1;
        c.gridy = 1;
        c.gridwidth = 1;
        c.weightx = 1;
        styleTextField(nameField);
        nameField.addActionListener(e -> saveLastSelectedVersion());
        nameField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                saveLastSelectedVersion();
            }
        });
        keepLauncherOpenBox.addActionListener(e -> saveLastSelectedVersion());
        form.add(nameField, c);

        JLabel versionLabel = legacyLabel("Версия:");
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(versionLabel, c);

        c.gridx = 1;
        c.gridy = 2;
        c.gridwidth = 1;
        c.weightx = 1;
        versionBox.setFont(versionBox.getFont().deriveFont(Font.PLAIN, 16f));
        versionBox.setPreferredSize(new Dimension(420, 42));
        styleCombo(versionBox);
        versionBox.setRenderer(new VersionRenderer());
        versionBox.addActionListener(e -> {
            updateSelectedVersionInfo();
            if (!loadingVersions) {
                saveLastSelectedVersion();
            }
        });
        form.add(versionBox, c);

        versionInfoLabel.setForeground(LEGACY_TEXT);
        versionInfoLabel.setFont(versionInfoLabel.getFont().deriveFont(13f));
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(versionInfoLabel, c);

        launcherInfoLabel.setForeground(new Color(230, 218, 180));
        launcherInfoLabel.setFont(launcherInfoLabel.getFont().deriveFont(Font.BOLD, 13f));
        launcherInfoLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        launcherInfoLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                openLauncherReleasesPage();
            }
        });
        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(launcherInfoLabel, c);

        styleCheckBox(forceUpdateBox);
        styleCheckBox(keepLauncherOpenBox);
        c.gridx = 0;
        c.gridy = 5;
        c.gridwidth = 2;
        form.add(forceUpdateBox, c);

        c.gridx = 0;
        c.gridy = 6;
        c.gridwidth = 2;
        form.add(keepLauncherOpenBox, c);

        styleButton(playButton);
        c.gridx = 0;
        c.gridy = 7;
        c.gridwidth = 2;
        c.insets = new Insets(14, 8, 4, 8);
        form.add(playButton, c);

        JPanel tools = new JPanel(new GridBagLayout());
        tools.setOpaque(false);
        styleSmallButton(openFolderButton);
        styleSmallButton(reloadButton);
        styleSmallButton(settingsButton);
        styleSmallButton(infoButton);
        styleSmallButton(cancelButton);
        cancelButton.setVisible(false);
        GridBagConstraints t = new GridBagConstraints();
        t.fill = GridBagConstraints.HORIZONTAL;
        t.weightx = 1;
        t.insets = new Insets(0, 0, 0, 2);
        tools.add(openFolderButton, t);
        t.insets = new Insets(0, 2, 0, 2);
        tools.add(reloadButton, t);
        t.insets = new Insets(0, 2, 0, 2);
        tools.add(settingsButton, t);
        t.insets = new Insets(0, 2, 0, 0);
        tools.add(infoButton, t);
        t.insets = new Insets(0, 2, 0, 0);
        tools.add(cancelButton, t);
        c.gridx = 0;
        c.gridy = 8;
        c.gridwidth = 2;
        c.insets = new Insets(4, 8, 10, 8);
        form.add(tools, c);

        statusLabel.setForeground(LEGACY_TEXT);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
        c.gridx = 0;
        c.gridy = 9;
        c.gridwidth = 2;
        c.insets = new Insets(12, 8, 2, 8);
        form.add(statusLabel, c);

        c.gridx = 0;
        c.gridy = 10;
        c.gridwidth = 2;
        c.insets = new Insets(0, 8, 0, 8);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(420, 22));
        progressBar.setForeground(GRASS);
        progressBar.setBackground(new Color(56, 58, 58));
        progressBar.setVisible(false);
        statusLabel.setVisible(false);
        form.add(progressBar, c);

        GridBagConstraints rootC = new GridBagConstraints();
        rootC.gridx = 0;
        rootC.gridy = 0;
        rootPanel.add(form, rootC);

        playButton.addActionListener(e -> playSelectedVersion());
        openFolderButton.addActionListener(e -> openGameFolder());
        reloadButton.addActionListener(e -> loadVersions());
        settingsButton.addActionListener(e -> showSettingsDialog());
        infoButton.addActionListener(e -> showGameInfoDialog());
        themeButton.addActionListener(e -> cycleTheme());
        cancelButton.addActionListener(e -> cancelCurrentInstall());

        frame.setContentPane(rootPanel);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        applySavedLauncherSettings();
        loadVersions();
        checkLauncherUpdate();
    }

    private void loadVersions() {
        reloadButton.setEnabled(false);

        new SwingWorker<List<GameVersion>, Void>() {
            @Override
            protected List<GameVersion> doInBackground() {
                File bundledManifest = findBundledManifest();
                if (bundledManifest.isFile()) {
                    try {
                        List<GameVersion> bundledVersions = downloadManifest(bundledManifest.toURI().toString());
                        if (!bundledVersions.isEmpty()) {
                            return bundledVersions;
                        }
                    } catch (IOException ignored) {
                    }
                }

                if (VERSION_MANIFEST_URL.trim().isEmpty()) {
                    return localVersions();
                }

                try {
                    List<GameVersion> remoteVersions = downloadManifest(VERSION_MANIFEST_URL);
                    return remoteVersions.isEmpty() ? localVersions() : remoteVersions;
                } catch (IOException ex) {
                    return localVersions();
                }
            }

            @Override
            protected void done() {
                try {
                    loadingVersions = true;
                    versionBox.removeAllItems();
                    for (GameVersion version : get()) {
                        versionBox.addItem(version);
                    }
                    selectSavedVersion();
                    updateSelectedVersionInfo();
                    statusLabel.setText("Выберите версию и нажмите \"Играть\"");
                } catch (Exception ex) {
                    showError("Не удалось загрузить список версий", ex);
                } finally {
                    loadingVersions = false;
                    reloadButton.setEnabled(true);
                }
            }
        }.execute();
    }

    private void checkLauncherUpdate() {
        new SwingWorker<LauncherUpdateInfo, Void>() {
            @Override
            protected LauncherUpdateInfo doInBackground() {
                try {
                    return loadLauncherUpdateInfo();
                } catch (IOException ignored) {
                    return null;
                }
            }

            @Override
            protected void done() {
                try {
                    LauncherUpdateInfo info = get();
                    if (info == null) {
                        launcherInfoLabel.setText("Launcher " + LAUNCHER_VERSION);
                        launcherInfoLabel.setToolTipText(LAUNCHER_RELEASES_URL);
                        launcherUpdateUrl = LAUNCHER_RELEASES_URL;
                        return;
                    }

                    launcherUpdateUrl = info.downloadUrl == null || info.downloadUrl.trim().isEmpty()
                            ? LAUNCHER_RELEASES_URL
                            : info.downloadUrl.trim();
                    String tooltip = info.description.isEmpty() ? launcherUpdateUrl : info.description + " | " + launcherUpdateUrl;
                    launcherInfoLabel.setToolTipText(tooltip);

                    if (info.updateAvailable) {
                        launcherInfoLabel.setText("Launcher " + LAUNCHER_VERSION + " -> " + info.version + " available");
                        launcherInfoLabel.setForeground(new Color(255, 214, 153));
                    } else {
                        launcherInfoLabel.setText("Launcher " + LAUNCHER_VERSION);
                        launcherInfoLabel.setForeground(new Color(230, 218, 180));
                    }
                } catch (Exception ignored) {
                    launcherInfoLabel.setText("Launcher " + LAUNCHER_VERSION);
                    launcherInfoLabel.setToolTipText(LAUNCHER_RELEASES_URL);
                    launcherUpdateUrl = LAUNCHER_RELEASES_URL;
                }
            }
        }.execute();
    }

    private LauncherUpdateInfo loadLauncherUpdateInfo() throws IOException {
        File bundledManifest = findBundledLauncherUpdateManifest();
        if (bundledManifest.isFile()) {
            LauncherUpdateInfo bundled = downloadLauncherUpdateInfo(bundledManifest.toURI().toString());
            if (bundled != null) {
                return bundled;
            }
        }

        if (LAUNCHER_UPDATE_MANIFEST_URL.trim().isEmpty()) {
            return null;
        }

        LauncherUpdateInfo remote = downloadLauncherUpdateInfo(LAUNCHER_UPDATE_MANIFEST_URL);
        return remote;
    }

    private static LauncherUpdateInfo downloadLauncherUpdateInfo(String manifestUrl) throws IOException {
        URI manifestUri = URI.create(manifestUrl);
        URLConnection connection = manifestUri.toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "TinyCraftLauncher/1.0");

        LauncherUpdateInfo newest = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\|", -1);
                if (parts.length < 3) {
                    continue;
                }

                LauncherUpdateInfo candidate = new LauncherUpdateInfo(
                        parts[0].trim(),
                        parts[1].trim(),
                        resolveManifestUrl(manifestUri, parts[2].trim()),
                        parts.length >= 4 ? parts[3].trim() : "");
                if (candidate.version.isEmpty()) {
                    continue;
                }

                if (newest == null || compareLauncherVersions(candidate.version, newest.version) > 0) {
                    newest = candidate;
                }
            }
        } finally {
            if (connection instanceof HttpURLConnection) {
                ((HttpURLConnection) connection).disconnect();
            }
        }

        if (newest == null) {
            return null;
        }

        newest.updateAvailable = compareLauncherVersions(newest.version, LAUNCHER_VERSION) > 0;
        return newest;
    }

    private void playSelectedVersion() {
        GameVersion version = (GameVersion) versionBox.getSelectedItem();
        if (version == null) {
            JOptionPane.showMessageDialog(frame, "Нет доступных версий.", "TinyCraft", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setBusy(true, "Проверка файлов...");
        saveLastSelectedVersion();
        currentWorker = new InstallAndRunWorker(version);
        currentWorker.execute();
    }

    private void cancelCurrentInstall() {
        InstallAndRunWorker worker = currentWorker;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
            statusLabel.setVisible(true);
            statusLabel.setText("Отмена...");
        }
    }

    private static JLabel legacyLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(LEGACY_TEXT);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 16f));
        return label;
    }

    private static void styleTextField(JTextField field) {
        field.setForeground(LEGACY_TEXT);
        field.setBackground(LEGACY_FIELD);
        field.setCaretColor(Color.WHITE);
        field.setFont(field.getFont().deriveFont(Font.PLAIN, 16f));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(118, 120, 120)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));
    }

    private static void styleCombo(JComboBox<?> combo) {
        combo.setForeground(LEGACY_TEXT);
        combo.setBackground(LEGACY_FIELD);
        combo.setFont(combo.getFont().deriveFont(Font.PLAIN, 16f));
        combo.setPreferredSize(new Dimension(260, 42));
    }

    private static void styleCheckBox(JCheckBox checkBox) {
        checkBox.setOpaque(false);
        checkBox.setForeground(LEGACY_TEXT);
        checkBox.setFont(checkBox.getFont().deriveFont(Font.PLAIN, 15f));
        checkBox.setFocusPainted(false);
    }

    private static void styleSmallButton(JButton button) {
        button.setFont(button.getFont().deriveFont(Font.BOLD, 13f));
        button.setForeground(LEGACY_TEXT);
        button.setBackground(LEGACY_FIELD);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        button.setPreferredSize(new Dimension(130, 44));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void styleThemeButton() {
        themeButton.setPreferredSize(new Dimension(32, 32));
        themeButton.setFocusable(false);
        themeButton.setBorder(BorderFactory.createLineBorder(new Color(130, 136, 136)));
        themeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        themeButton.setToolTipText("Сменить тему");
    }

    private void cycleTheme() {
        themeIndex = (themeIndex + 1) % 3;
        applyTheme();
        saveLastSelectedVersion();
    }

    private void applyTheme() {
        Color panel;
        Color field;
        Color accent;
        if (themeIndex == 1) {
            panel = new Color(48, 76, 52);
            field = new Color(63, 91, 66);
            accent = new Color(90, 162, 79);
        } else if (themeIndex == 2) {
            panel = new Color(62, 62, 64);
            field = new Color(78, 78, 80);
            accent = new Color(150, 154, 154);
        } else {
            panel = LEGACY_PANEL;
            field = LEGACY_FIELD;
            accent = new Color(92, 145, 200);
        }

        formPanel.setBackground(panel);
        nameField.setBackground(field);
        versionBox.setBackground(field);
        openFolderButton.setBackground(field);
        reloadButton.setBackground(field);
        settingsButton.setBackground(field);
        infoButton.setBackground(field);
        progressBar.setForeground(accent);
        playButton.setBackground(themeIndex == 1 ? DEEP_GRASS : field);
        rootPanel.setTheme(themeIndex);
        themeButton.repaint();
        frame.repaint();
        playButton.repaint();
    }

    private void openGameFolder() {
        File folder = getVersionsDirectory();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            showError("Не удалось создать папку игры", new IOException(folder.getAbsolutePath()));
            return;
        }

        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(folder);
            } else {
                new ProcessBuilder("explorer.exe", folder.getAbsolutePath()).start();
            }
        } catch (IOException ex) {
            showError("Не удалось открыть папку игры", ex);
        }
    }

    private void showSettingsDialog() {
        JOptionPane.showMessageDialog(frame,
                "Минимальные настройки уже на главном экране:\n" +
                        "- ник игрока;\n" +
                        "- тема лаунчера;\n" +
                        "- обновить клиент;\n" +
                        "- оставить лаунчер открытым после запуска.\n\n" +
                        "Папка версий:\n" + getVersionsDirectory().getAbsolutePath(),
                "Настройки", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showGameInfoDialog() {
        JDialog dialog = new JDialog(frame, "Информация о TinyCraft", false);
        JEditorPane content = new JEditorPane("text/html", buildGameInfoHtml());
        content.setEditable(false);
        content.setOpaque(true);
        content.setBackground(new Color(42, 44, 44));
        content.setForeground(LEGACY_TEXT);
        content.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        content.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        content.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null) {
                try {
                    Desktop.getDesktop().browse(event.getURL().toURI());
                } catch (Exception ex) {
                    showError("Не удалось открыть ссылку", new IOException(event.getURL().toString(), ex));
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        dialog.setContentPane(scrollPane);
        dialog.setSize(new Dimension(760, 560));
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void openLauncherReleasesPage() {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(launcherUpdateUrl == null || launcherUpdateUrl.trim().isEmpty()
                    ? LAUNCHER_RELEASES_URL
                    : launcherUpdateUrl.trim()));
        } catch (Exception ignored) {
        }
    }

    private static String buildGameInfoHtml() {
        return "<html><body style='font-family:sans-serif;background:#2a2c2c;color:#eeeeea;padding:18px;'>"
                + "<h1>TinyCraft</h1>"
                + "<p>Небольшая Java/LWJGL voxel-песочница с чанковым миром, биомами, горами, пещерами, шахтами, деревнями, мобами, инвентарем, крафтом, печками, сундуками, жидкостями, командами, LAN-мультиплеером и dedicated server.</p>"
                + "<h2>Launcher</h2>"
                + "<p>TinyCraft Launcher " + LAUNCHER_VERSION + "</p>"
                + "<p><a href='" + LAUNCHER_RELEASES_URL + "'>Скачать launcher releases</a></p>"
                + "<h2>Текущая версия</h2>"
                + "<p><b>v0.2.1</b> - финальная стабильная сборка поверх v0.2 Final.</p>"
                + "<h2>Ссылки</h2>"
                + "<p><a href='https://github.com/vakisnn-gd/TinyCraft'>Главная страница GitHub</a></p>"
                + "<p><a href='https://github.com/vakisnn-gd/TinyCraft/releases'>Скачать релизы</a></p>"
                + "<h2>Если игра не запускается</h2>"
                + "<p>Откройте папку логов: <code>%LOCALAPPDATA%\\TinyCraftLauncher\\logs</code> и пришлите latest-log выбранной версии.</p>"
                + "</body></html>";
    }

    private void updateSelectedVersionInfo() {
        GameVersion version = (GameVersion) versionBox.getSelectedItem();
        if (version == null) {
            versionInfoLabel.setText(" ");
            return;
        }

        File jarFile = new File(getVersionDirectory(version), GAME_JAR_NAME);
        String state = jarFile.isFile() ? "установлена" : "не установлена";
        versionInfoLabel.setText(version.name + " - " + version.typeLabel() + ", Java " + version.javaRelease + ", " + state);
    }

    private static void styleButton(JButton button) {
        button.setFont(button.getFont().deriveFont(Font.BOLD, 18f));
        button.setForeground(Color.WHITE);
        button.setBackground(DEEP_GRASS);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setPreferredSize(new Dimension(220, 60));
    }

    private static java.awt.Image createBlockImage(int size) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        paintGrassBlock(g, 8, 4, size - 16);
        g.dispose();
        return image;
    }

    private static void paintGrassBlock(Graphics2D g, int x, int y, int size) {
        int unit = Math.max(4, size / 8);
        int blockSize = unit * 8;
        int bx = x + (size - blockSize) / 2;
        int by = y + (size - blockSize) / 2;

        g.setColor(new Color(45, 35, 28));
        g.fillRect(bx, by, blockSize, blockSize);

        g.setColor(GRASS);
        g.fillRect(bx + unit, by + unit, unit * 6, unit * 2);
        g.setColor(LIGHT_GRASS);
        g.fillRect(bx + unit * 2, by + unit, unit * 3, unit);
        g.setColor(new Color(38, 120, 47));
        g.fillRect(bx + unit, by + unit * 2, unit * 6, unit);

        g.setColor(new Color(126, 83, 47));
        g.fillRect(bx + unit, by + unit * 3, unit * 6, unit * 4);
        g.setColor(new Color(93, 61, 38));
        g.fillRect(bx + unit, by + unit * 5, unit * 6, unit * 2);
        g.setColor(new Color(154, 104, 58));
        g.fillRect(bx + unit * 2, by + unit * 4, unit, unit);
        g.fillRect(bx + unit * 5, by + unit * 5, unit, unit);
        g.setColor(new Color(70, 45, 31));
        g.fillRect(bx + unit * 3, by + unit * 6, unit, unit);
    }

    private static final class ActionButton extends JButton {
        private ActionButton(String text) {
            super(text);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            ButtonModel model = getModel();
            Color base = getBackground() == null ? DEEP_GRASS : getBackground();
            Color fill = model.isPressed() ? base.darker() : base;
            if (!isEnabled()) {
                fill = new Color(120, 134, 124);
            } else if (model.isRollover()) {
                fill = base.brighter();
            }

            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g.setColor(new Color(23, 64, 34));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);

            g.setFont(getFont());
            FontMetrics metrics = g.getFontMetrics();
            String text = getText();
            int textX = (getWidth() - metrics.stringWidth(text)) / 2;
            int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g.setColor(Color.WHITE);
            g.drawString(text, textX, textY);
            g.dispose();
        }
    }

    private static final class SmallIconButton extends JButton {
        private SmallIconButton(String text) {
            super(text);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ButtonModel model = getModel();
            Color base = getBackground() == null ? LEGACY_FIELD : getBackground();
            Color fill = model.isPressed() ? base.darker() : base;
            if (!isEnabled()) {
                fill = new Color(72, 74, 74);
            } else if (model.isRollover()) {
                fill = base.brighter();
            }
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
            g.setColor(isEnabled() ? new Color(148, 154, 154) : new Color(92, 94, 94));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
            g.setFont(getFont());
            FontMetrics metrics = g.getFontMetrics();
            String text = getText();
            int textX = (getWidth() - metrics.stringWidth(text)) / 2;
            int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g.setColor(isEnabled() ? LEGACY_TEXT : new Color(150, 154, 154));
            g.drawString(text, textX, textY);
            g.dispose();
        }
    }

    private final class ThemeButton extends JButton {
        private ThemeButton() {
            super("");
            setContentAreaFilled(false);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = themeIndex == 1 ? new Color(76, 155, 72) : themeIndex == 2 ? new Color(126, 128, 130) : new Color(92, 145, 200);
            g.setColor(new Color(42, 44, 44));
            g.fillRect(3, 3, getWidth() - 6, getHeight() - 6);
            g.setColor(fill);
            g.fillRect(8, 8, getWidth() - 16, getHeight() - 16);
            g.setColor(new Color(215, 220, 218));
            g.drawRect(3, 3, getWidth() - 7, getHeight() - 7);
            g.dispose();
        }
    }

    private static final class LegacyBackgroundPanel extends JPanel {
        private int theme;

        private void setTheme(int theme) {
            this.theme = theme;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            if (theme == 1) {
                g.setColor(new Color(28, 76, 48));
            } else if (theme == 2) {
                g.setColor(new Color(48, 50, 54));
            } else {
                g.setColor(new Color(26, 64, 82));
            }
            g.fillRect(0, 0, width, height);

            if (theme == 1) {
                g.setColor(new Color(44, 104, 58, 130));
            } else if (theme == 2) {
                g.setColor(new Color(72, 76, 82, 130));
            } else {
                g.setColor(new Color(38, 91, 96, 150));
            }
            for (int y = 0; y < height; y += 34) {
                g.fillRect(0, y, width, 12);
            }

            g.setColor(theme == 2 ? new Color(32, 34, 38, 180) : new Color(20, 48, 44, 180));
            g.fillOval(-80, height - 190, width / 2, 260);
            g.fillOval(width / 2, height - 230, width / 2 + 120, 300);

            g.setColor(new Color(255, 255, 255, 45));
            for (int i = 0; i < 18; i++) {
                int x = (i * 97 + 43) % Math.max(width, 1);
                int y = (i * 53 + 29) % Math.max(height, 1);
                g.fillOval(x, y, 4, 4);
            }

            g.dispose();
        }
    }

    private static final class VersionRenderer extends JLabel implements ListCellRenderer<GameVersion> {
        private VersionRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends GameVersion> list, GameVersion value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            if (value == null) {
                setText("");
            } else {
                setText(value.name + "   " + value.typeLabel());
            }

            setFont(list.getFont().deriveFont(Font.BOLD, 14f));
            setForeground(LEGACY_TEXT);
            setBackground(isSelected ? new Color(68, 104, 78) : LEGACY_FIELD);
            return this;
        }
    }

    private final class InstallAndRunWorker extends SwingWorker<Void, String> {
        private final GameVersion version;

        private InstallAndRunWorker(GameVersion version) {
            this.version = version;
        }

        @Override
        protected Void doInBackground() throws Exception {
            File versionDir = getVersionDirectory(version);
            File jarFile = new File(versionDir, GAME_JAR_NAME);

            boolean mustRefresh = forceUpdateBox.isSelected() || !isInstalledVersionValid(versionDir);
            if (mustRefresh && versionDir.exists()) {
                deleteDirectory(versionDir);
            }

            if (mustRefresh) {
                installVersion(version, versionDir, jarFile);
            }

            checkCancelled();
            saveProfile(versionDir, nameField.getText());
            launchGame(version, jarFile);
            return null;
        }

        @Override
        protected void process(List<String> chunks) {
            if (!chunks.isEmpty()) {
                statusLabel.setVisible(true);
                statusLabel.setText(chunks.get(chunks.size() - 1));
            }
        }

        @Override
        protected void done() {
            try {
                if (isCancelled()) {
                    statusLabel.setVisible(true);
                    statusLabel.setText("Установка отменена");
                    return;
                }
                get();
                statusLabel.setText("Игра запущена");
                if (!keepLauncherOpenBox.isSelected()) {
                    frame.dispose();
                }
            } catch (Exception ex) {
                showError("Не удалось скачать, собрать или запустить игру", ex);
                statusLabel.setText("Ошибка");
            } finally {
                currentWorker = null;
                setBusy(false, statusLabel.getText());
            }
        }

        private void installVersion(GameVersion version, File versionDir, File jarFile) throws Exception {
            File versionsRoot = getVersionsDirectory();
            if (!versionsRoot.isDirectory() && !versionsRoot.mkdirs()) {
                throw new IOException("Не удалось создать папку: " + versionsRoot.getAbsolutePath());
            }

            File downloadsDir = getDownloadsDirectory();
            if (!downloadsDir.isDirectory() && !downloadsDir.mkdirs()) {
                throw new IOException("Не удалось создать папку: " + downloadsDir.getAbsolutePath());
            }

            File archiveFile = new File(downloadsDir, version.id + ".zip");
            File stagingDir = new File(versionsRoot, version.id + ".install");
            deleteDirectory(stagingDir);
            if (!stagingDir.mkdirs()) {
                throw new IOException("Не удалось создать папку: " + stagingDir.getAbsolutePath());
            }
            Files.deleteIfExists(archiveFile.toPath());

            boolean installed = false;
            try {
                progress(2, "Подготовка загрузки " + version.name + "...");
                downloadFile(version.downloadUrl, archiveFile, 2, 55);
                checkCancelled();
                if (!version.sha256.isEmpty()) {
                    verifySha256(archiveFile, version.sha256);
                }

                progress(56, "Распаковка...");
                unzipGitHubArchive(archiveFile, stagingDir, new ProgressListener() {
                    @Override
                    public void onProgress(long currentBytes, long totalBytes) throws IOException {
                        checkCancelled();
                        if (totalBytes > 0) {
                            int value = 56 + (int) Math.min(24, currentBytes * 24 / totalBytes);
                            int percent = (int) Math.min(100, currentBytes * 100 / totalBytes);
                            progress(value, "Распаковка " + percent + "% (" + formatBytes(currentBytes) + " / " + formatBytes(totalBytes) + ")");
                        } else {
                            progress(56, "Распаковка " + formatBytes(currentBytes));
                        }
                    }
                });
                checkCancelled();

                File stagingJar = new File(stagingDir, GAME_JAR_NAME);
                if (!stagingJar.isFile()) {
                    if (!new File(new File(stagingDir, "out"), version.mainClass + ".class").isFile()) {
                        progress(65, "Сборка Java-классов...");
                        compileSources(stagingDir, version.javaRelease);
                        checkCancelled();
                    }

                    progress(82, "Создание TinyCraft.jar...");
                    createRunnableJar(stagingDir, stagingJar, version.mainClass);
                }

                if (!stagingJar.isFile()) {
                    throw new IOException("В версии не найден " + GAME_JAR_NAME);
                }

                progress(94, "Установка...");
                deleteDirectory(versionDir);
                Files.move(stagingDir.toPath(), versionDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
                installed = true;
                progress(100, "Версия установлена");
            } finally {
                if (!installed) {
                    deleteDirectory(stagingDir);
                }
                if (archiveFile.exists() && !archiveFile.delete()) {
                    archiveFile.deleteOnExit();
                }
            }
        }

        private void progress(int value, String message) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    statusLabel.setVisible(true);
                    progressBar.setVisible(true);
                    progressBar.setValue(value);
                    progressBar.setString(message);
                }
            });
            publish(message);
        }

        private void downloadFile(String sourceUrl, File targetFile, int startPercent, int endPercent) throws IOException {
            File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".download");
            Files.deleteIfExists(tempFile.toPath());

            URLConnection connection = URI.create(sourceUrl).toURL().openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "TinyCraftLauncher/1.0");

            if (connection instanceof HttpURLConnection) {
                int responseCode = ((HttpURLConnection) connection).getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException("Сервер вернул HTTP " + responseCode + " для " + sourceUrl);
                }
            }

            long totalBytes = connection.getContentLengthLong();

            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                long downloaded = 0;

                while ((read = in.read(buffer)) != -1) {
                    checkCancelled();
                    out.write(buffer, 0, read);
                    downloaded += read;

                    if (totalBytes > 0) {
                        int range = endPercent - startPercent;
                        int value = startPercent + (int) Math.min(range, downloaded * range / totalBytes);
                        int percent = (int) Math.min(100, downloaded * 100 / totalBytes);
                        progress(value, "Скачивание " + percent + "% (" + formatBytes(downloaded) + " / " + formatBytes(totalBytes) + ")");
                    } else {
                        progress(startPercent, "Скачивание " + formatBytes(downloaded));
                    }
                }
            } finally {
                if (connection instanceof HttpURLConnection) {
                    ((HttpURLConnection) connection).disconnect();
                }
            }

            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        private void checkCancelled() throws IOException {
            if (isCancelled()) {
                throw new IOException("Операция отменена.");
            }
        }
    }

    private static boolean isHttpUrl(String sourceUrl) {
        String lower = sourceUrl == null ? "" : sourceUrl.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean downloadWithWindowsDownloader(String sourceUrl, File tempFile, File workingDir)
            throws IOException, InterruptedException {
        IOException curlFailure = null;
        List<String> curl = new ArrayList<String>();
        curl.add("curl.exe");
        curl.add("-L");
        curl.add("--fail");
        curl.add("--show-error");
        curl.add("--connect-timeout");
        curl.add("20");
        curl.add("--retry");
        curl.add("2");
        curl.add("--output");
        curl.add(tempFile.getAbsolutePath());
        curl.add(sourceUrl);
        try {
            runDownloaderCommand(curl, workingDir);
            return tempFile.isFile() && tempFile.length() > 0;
        } catch (IOException ex) {
            curlFailure = ex;
            Files.deleteIfExists(tempFile.toPath());
        }

        List<String> powershell = new ArrayList<String>();
        powershell.add("powershell.exe");
        powershell.add("-NoProfile");
        powershell.add("-ExecutionPolicy");
        powershell.add("Bypass");
        powershell.add("-Command");
        powershell.add("$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri $args[0] -OutFile $args[1]");
        powershell.add(sourceUrl);
        powershell.add(tempFile.getAbsolutePath());
        try {
            runDownloaderCommand(powershell, workingDir);
            return tempFile.isFile() && tempFile.length() > 0;
        } catch (IOException ex) {
            if (curlFailure != null) {
                ex.addSuppressed(curlFailure);
            }
            throw ex;
        }
    }

    private static void runDownloaderCommand(List<String> command, File workingDir) throws IOException, InterruptedException {
        File logFile = new File(workingDir, "download-command.log");
        Files.write(logFile.toPath(),
                ("Downloader: " + command.get(0) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        Process process = builder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Downloader failed with exit code " + exitCode + ". Log: " + logFile.getAbsolutePath());
        }
    }

    private interface ProgressListener {
        void onProgress(long currentBytes, long totalBytes) throws IOException;
    }

    private static void unzipGitHubArchive(File archiveFile, File targetDir, ProgressListener progressListener) throws IOException {
        String targetPath = targetDir.getCanonicalPath() + File.separator;
        long totalBytes = archiveFile.length();

        try (CountingInputStream input = new CountingInputStream(new FileInputStream(archiveFile));
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (progressListener != null) {
                    progressListener.onProgress(input.getBytesRead(), totalBytes);
                }
                String name = normalizeZipEntryName(entry.getName());
                if (name.isEmpty()) {
                    continue;
                }

                File outputFile = new File(targetDir, name);
                String outputPath = outputFile.getCanonicalPath();
                if (!outputPath.startsWith(targetPath)) {
                    throw new IOException("Небезопасный путь в архиве: " + entry.getName());
                }

                if (entry.isDirectory() || name.endsWith("/")) {
                    if (!outputFile.isDirectory() && !outputFile.mkdirs()) {
                        throw new IOException("Не удалось создать папку: " + outputFile.getAbsolutePath());
                    }
                    continue;
                }

                File parent = outputFile.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Не удалось создать папку: " + parent.getAbsolutePath());
                }

                try (FileOutputStream out = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        if (progressListener != null) {
                            progressListener.onProgress(input.getBytesRead(), totalBytes);
                        }
                    }
                }
            }
            if (progressListener != null) {
                progressListener.onProgress(totalBytes, totalBytes);
            }
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long bytesRead;

        private CountingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                bytesRead += read;
            }
            return read;
        }

        private long getBytesRead() {
            return bytesRead;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024.0) {
            return String.format(java.util.Locale.US, "%.1f KB", kib);
        }
        double mib = kib / 1024.0;
        if (mib < 1024.0) {
            return String.format(java.util.Locale.US, "%.1f MB", mib);
        }
        return String.format(java.util.Locale.US, "%.1f GB", mib / 1024.0);
    }

    private static String normalizeZipEntryName(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int slash = normalized.indexOf('/');
        if (slash > 0) {
            String firstPart = normalized.substring(0, slash).toLowerCase();
            if (firstPart.startsWith("tinycraft-") || firstPart.startsWith("tinyminecraft-")) {
                return normalized.substring(slash + 1);
            }
        }
        return normalized;
    }

    private static void compileSources(File versionDir, int javaRelease) throws IOException, InterruptedException {
        File outDir = new File(versionDir, "out");
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("Не удалось создать папку сборки: " + outDir.getAbsolutePath());
        }

        List<String> javaFiles = listTopLevelJavaFiles(versionDir);
        if (javaFiles.isEmpty()) {
            throw new IOException("В архиве версии нет Java-файлов.");
        }

        List<String> modernCommand = createCompileCommand(javaFiles, true, javaRelease);
        try {
            runCommand(modernCommand, versionDir, "compilation.log");
        } catch (IOException firstFailure) {
            if (javaRelease != 8) {
                throw new IOException("Для этой версии нужен JDK " + javaRelease + "+. Лог: "
                        + new File(versionDir, "compilation.log").getAbsolutePath(), firstFailure);
            }
            // JDK 8 does not know --release, so retry Java 8 versions with the older flags.
            List<String> java8Command = createCompileCommand(javaFiles, false, javaRelease);
            runCommand(java8Command, versionDir, "compilation.log");
        }
    }

    private static List<String> createCompileCommand(List<String> javaFiles, boolean useReleaseFlag, int javaRelease) {
        List<String> command = new ArrayList<String>();
        command.add("javac");
        command.add("-encoding");
        command.add("UTF-8");
        if (useReleaseFlag) {
            command.add("--release");
            command.add(String.valueOf(javaRelease));
        } else {
            command.add("-source");
            command.add(String.valueOf(javaRelease));
            command.add("-target");
            command.add(String.valueOf(javaRelease));
        }
        command.add("-cp");
        command.add("lib" + File.separator + "*");
        command.add("-d");
        command.add("out");
        command.addAll(javaFiles);
        return command;
    }

    private static List<String> listTopLevelJavaFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<String>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".java")) {
                result.add(file.getName());
            }
        }
        Collections.sort(result);
        return result;
    }

    private static void createRunnableJar(File versionDir, File jarFile, String mainClass) throws IOException {
        File outDir = new File(versionDir, "out");
        if (!new File(outDir, mainClass + ".class").isFile()) {
            throw new IOException("Не найден " + mainClass + ".class после сборки.");
        }

        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
        attributes.putValue("Class-Path", buildManifestClassPath(new File(versionDir, "lib")));

        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(jarFile), manifest)) {
            addDirectoryToJar(jar, outDir, outDir);
        }
    }

    private static String buildManifestClassPath(File libDir) {
        File[] files = libDir.listFiles();
        if (files == null) {
            return "";
        }

        List<String> entries = new ArrayList<String>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                entries.add("lib/" + file.getName().replace(" ", "%20"));
            }
        }
        Collections.sort(entries);

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                result.append(' ');
            }
            result.append(entries.get(i));
        }
        return result.toString();
    }

    private static void addDirectoryToJar(JarOutputStream jar, File rootDir, File currentDir) throws IOException {
        File[] files = currentDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                addDirectoryToJar(jar, rootDir, file);
                continue;
            }

            String relativePath = rootDir.toURI().relativize(file.toURI()).getPath();
            JarEntry entry = new JarEntry(relativePath);
            entry.setTime(file.lastModified());
            jar.putNextEntry(entry);

            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    jar.write(buffer, 0, read);
                }
            }

            jar.closeEntry();
        }
    }

    private static void verifySha256(File file, String expectedSha256) throws IOException {
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("SHA-256 не совпал для " + file.getName() + ": " + actual);
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                result.append(String.format("%02x", b & 0xff));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IOException("Не удалось посчитать SHA-256: " + file.getAbsolutePath(), ex);
        }
    }

    private static void deleteDirectory(File directory) throws IOException {
        if (directory == null || !directory.exists()) {
            return;
        }
        Path root = directory.toPath().toAbsolutePath().normalize();
        Path dataRoot = getLauncherDataDirectory().toPath().toAbsolutePath().normalize();
        if (!root.startsWith(dataRoot)) {
            throw new IOException("Отказ удалить папку вне данных лаунчера: " + root);
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
    }

    private static void launchGame(GameVersion version, File jarFile) throws IOException {
        ensureLauncherAssets(jarFile.getParentFile());
        File logDir = getLogsDirectory();
        if (!logDir.isDirectory() && !logDir.mkdirs()) {
            throw new IOException("Не удалось создать папку логов: " + logDir.getAbsolutePath());
        }
        File logFile = new File(logDir, version.id + "-latest.log");
        ProcessBuilder builder = new ProcessBuilder(
                findJavaCommand(version.javaRelease),
                "-Dfile.encoding=UTF-8",
                "-Dtinycraft.home=" + jarFile.getParentFile().getAbsolutePath(),
                "-jar",
                jarFile.getAbsolutePath()
        );
        builder.directory(jarFile.getParentFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        Files.write(logFile.toPath(),
                ("TinyCraft launch log for " + version.name + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Process process = builder.start();
        try {
            if (process.waitFor(10000, TimeUnit.MILLISECONDS)) {
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    throw new IOException("Игра закрылась раньше, чем появилось окно. Код завершения: "
                            + exitCode + ". Лог: " + logFile.getAbsolutePath());
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Запуск был прерван. Лог: " + logFile.getAbsolutePath(), ex);
        }
    }

    private static void ensureLauncherAssets(File versionDir) throws IOException {
        copyBundledAssetIfMissing(versionDir, "ChunkShader.vsh");
        copyBundledAssetIfMissing(versionDir, "ChunkShader.fsh");
        copyBundledAssetIfMissing(versionDir, "terrain.png");
    }

    private static void copyBundledAssetIfMissing(File versionDir, String assetName) throws IOException {
        File target = new File(versionDir, assetName);
        if (target.isFile()) {
            return;
        }

        File launcherDir = getLauncherDirectory();
        File source = new File(launcherDir, assetName);
        if (!source.isFile()) {
            File parent = launcherDir.getParentFile();
            if (parent != null) {
                source = new File(parent, assetName);
                if (!source.isFile()) {
                    File grandParent = parent.getParentFile();
                    if (grandParent != null) {
                        source = new File(grandParent, assetName);
                    }
                }
            }
        }

        if (!source.isFile()) {
            throw new IOException("Не найден ресурс " + assetName + " для запуска игры.");
        }

        File parentDir = target.getParentFile();
        if (parentDir != null && !parentDir.isDirectory() && !parentDir.mkdirs()) {
            throw new IOException("Не удалось создать папку: " + parentDir.getAbsolutePath());
        }
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String findJavaCommand(int minimumRelease) {
        File launcherDir = getLauncherDirectory();
        File parentDir = launcherDir.getParentFile();
        File[] candidates = {
                new File(new File(new File(launcherDir, "runtime"), "bin"), "java.exe"),
                new File(new File(new File(parentDir == null ? launcherDir : parentDir, "runtime"), "bin"), "java.exe"),
                new File(new File(new File(launcherDir, "runtime"), "bin"), "java"),
                new File(new File(new File(parentDir == null ? launcherDir : parentDir, "runtime"), "bin"), "java")
        };
        for (File candidate : candidates) {
            if (candidate.isFile() && javaReleaseAtLeast(candidate, minimumRelease)) {
                return candidate.getAbsolutePath();
            }
        }
        return "java";
    }

    private static boolean javaReleaseAtLeast(File javaExecutable, int minimumRelease) {
        if (minimumRelease <= 8) {
            return javaExecutable.isFile();
        }
        try {
            int release = detectJavaRelease(javaExecutable);
            return release >= minimumRelease;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int detectJavaRelease(File javaExecutable) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(javaExecutable.getAbsolutePath(), "-version");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        } finally {
            process.waitFor(5, TimeUnit.SECONDS);
        }

        String text = output.toString();
        int versionIndex = text.indexOf("version \"");
        if (versionIndex < 0) {
            return -1;
        }
        int start = versionIndex + "version \"".length();
        int end = text.indexOf('"', start);
        if (end < 0) {
            return -1;
        }
        String rawVersion = text.substring(start, end).trim();
        if (rawVersion.startsWith("1.")) {
            int dot = rawVersion.indexOf('.', 2);
            if (dot > 0) {
                return Integer.parseInt(rawVersion.substring(2, dot));
            }
        }
        int dot = rawVersion.indexOf('.');
        String major = dot > 0 ? rawVersion.substring(0, dot) : rawVersion;
        return Integer.parseInt(major);
    }

    private static void saveProfile(File versionDir, String rawName) throws IOException {
        File profileFile = new File(versionDir, "profile.properties");
        Properties properties = new Properties();
        if (profileFile.isFile()) {
            try (InputStream input = new FileInputStream(profileFile)) {
                properties.load(input);
            } catch (Exception ignored) {
                properties.clear();
            }
        }

        String uuid = properties.getProperty("uuid");
        if (uuid == null || uuid.trim().isEmpty()) {
            uuid = UUID.randomUUID().toString();
        }

        properties.setProperty("uuid", uuid);
        properties.setProperty("name", sanitizePlayerName(rawName));
        try (FileOutputStream output = new FileOutputStream(profileFile)) {
            properties.store(output, "TinyCraft local multiplayer profile");
        }
    }

    private static String sanitizePlayerName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < trimmed.length() && result.length() < 16; i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                result.append(c);
            }
        }
        return result.length() == 0 ? "Player" : result.toString();
    }

    private static void runCommand(List<String> command, File directory, String logName) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        File logFile = new File(directory, logName);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new FileWriter(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Команда завершилась с кодом " + exitCode + ". Лог: " + logFile.getAbsolutePath());
        }
    }

    private static File getLauncherDirectory() {
        try {
            File location = new File(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.isFile() ? location.getParentFile() : location;
        } catch (Exception ex) {
            return new File(".").getAbsoluteFile();
        }
    }

    private static File getLauncherDataDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.trim().isEmpty()) {
            return new File(localAppData, "TinyCraftLauncher");
        }
        return new File(System.getProperty("user.home", "."), ".tinycraftlauncher");
    }

    private static File getVersionsDirectory() {
        return new File(getLauncherDataDirectory(), VERSIONS_DIRECTORY_NAME);
    }

    private static File getDownloadsDirectory() {
        return new File(getLauncherDataDirectory(), "downloads");
    }

    private static File getLogsDirectory() {
        return new File(getLauncherDataDirectory(), LOGS_DIRECTORY_NAME);
    }

    private static File getVersionDirectory(GameVersion version) {
        String folderName = version.id == null || version.id.trim().isEmpty() ? sanitizeFileName(version.name) : sanitizeFileName(version.id);
        return new File(getVersionsDirectory(), folderName);
    }

    private static File getLauncherSettingsFile() {
        File dataDir = getLauncherDataDirectory();
        if (!dataDir.isDirectory()) {
            dataDir.mkdirs();
        }
        return new File(dataDir, LAUNCHER_SETTINGS_FILE);
    }

    private void selectSavedVersion() {
        String savedName = loadLauncherSettings().getProperty(KEY_LAST_VERSION, "").trim();
        if (savedName.isEmpty()) {
            return;
        }
        for (int i = 0; i < versionBox.getItemCount(); i++) {
            GameVersion version = versionBox.getItemAt(i);
            if (version != null && (savedName.equals(version.id) || savedName.equals(version.name))) {
                versionBox.setSelectedIndex(i);
                return;
            }
        }
    }

    private void applySavedLauncherSettings() {
        Properties properties = loadLauncherSettings();
        String savedPlayerName = properties.getProperty(KEY_PLAYER_NAME, "").trim();
        if (!savedPlayerName.isEmpty()) {
            nameField.setText(sanitizePlayerName(savedPlayerName));
        }
        keepLauncherOpenBox.setSelected(Boolean.parseBoolean(properties.getProperty(KEY_KEEP_OPEN, "true")));
        themeIndex = parseThemeIndex(properties.getProperty(KEY_THEME, "0"));
        if (formPanel != null) {
            applyTheme();
        }
    }

    private void saveLastSelectedVersion() {
        GameVersion version = (GameVersion) versionBox.getSelectedItem();
        Properties properties = loadLauncherSettings();
        if (version != null) {
            properties.setProperty(KEY_LAST_VERSION, version.id);
        }
        properties.setProperty(KEY_PLAYER_NAME, sanitizePlayerName(nameField.getText()));
        properties.setProperty(KEY_KEEP_OPEN, Boolean.toString(keepLauncherOpenBox.isSelected()));
        properties.setProperty(KEY_THEME, Integer.toString(themeIndex));
        try (FileOutputStream output = new FileOutputStream(getLauncherSettingsFile())) {
            properties.store(output, "TinyCraft launcher settings");
        } catch (IOException ignored) {
        }
    }

    private static int parseThemeIndex(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 && parsed <= 2 ? parsed : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    private static Properties loadLauncherSettings() {
        Properties properties = new Properties();
        File settingsFile = getLauncherSettingsFile();
        if (settingsFile.isFile()) {
            try (InputStream input = new FileInputStream(settingsFile)) {
                properties.load(input);
            } catch (IOException ignored) {
                properties.clear();
            }
        }
        return properties;
    }

    private static File findBundledManifest() {
        File launcherDir = getLauncherDirectory();
        File direct = new File(new File(launcherDir, "launcher-versions"), "versions.txt");
        if (direct.isFile()) {
            return direct;
        }
        File parent = launcherDir.getParentFile();
        if (parent != null) {
            File sibling = new File(new File(parent, "launcher-versions"), "versions.txt");
            if (sibling.isFile()) {
                return sibling;
            }
        }
        File cwd = new File(".").getAbsoluteFile();
        File projectLocal = new File(new File(cwd, "release" + File.separator + "launcher-versions"), "versions.txt");
        if (projectLocal.isFile()) {
            return projectLocal;
        }
        return direct;
    }

    private static File findBundledLauncherUpdateManifest() {
        File launcherDir = getLauncherDirectory();
        File direct = new File(launcherDir, LAUNCHER_UPDATE_MANIFEST_NAME);
        if (direct.isFile()) {
            return direct;
        }

        File parent = launcherDir.getParentFile();
        if (parent != null) {
            File sibling = new File(parent, LAUNCHER_UPDATE_MANIFEST_NAME);
            if (sibling.isFile()) {
                return sibling;
            }
        }

        File cwd = new File(".").getAbsoluteFile();
        File projectLocal = new File(cwd, LAUNCHER_UPDATE_MANIFEST_NAME);
        if (projectLocal.isFile()) {
            return projectLocal;
        }
        return direct;
    }

    private static List<GameVersion> localVersions() {
        List<GameVersion> result = new ArrayList<GameVersion>();
        Collections.addAll(result, LOCAL_VERSIONS);
        return result;
    }

    private static List<GameVersion> downloadManifest(String manifestUrl) throws IOException {
        List<GameVersion> result = new ArrayList<GameVersion>();
        URI manifestUri = URI.create(manifestUrl);
        URLConnection connection = manifestUri.toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "TinyCraftLauncher/1.0");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\|", -1);
                if (parts.length >= 7) {
                    result.add(new GameVersion(parts[0].trim(), parts[1].trim(), parts[2].trim(), parseJavaRelease(parts[3]),
                            parts[4].trim(), parts[5].trim(), resolveManifestUrl(manifestUri, parts[6].trim())));
                } else if (parts.length == 4) {
                    String name = parts[0].trim();
                    result.add(new GameVersion(toVersionId(name), name, parts[1].trim(), parseJavaRelease(parts[2]),
                            defaultMainClass(name), "", resolveManifestUrl(manifestUri, parts[3].trim())));
                } else if (parts.length == 3) {
                    String name = parts[0].trim();
                    result.add(new GameVersion(toVersionId(name), name, parts[1].trim(), 8,
                            defaultMainClass(name), "", resolveManifestUrl(manifestUri, parts[2].trim())));
                }
            }
        } finally {
            if (connection instanceof HttpURLConnection) {
                ((HttpURLConnection) connection).disconnect();
            }
        }

        return result;
    }

    private static String resolveManifestUrl(URI manifestUri, String value) {
        URI valueUri = URI.create(value);
        if (valueUri.isAbsolute()) {
            return valueUri.toString();
        }
        return manifestUri.resolve(valueUri).toString();
    }

    private static int parseJavaRelease(String value) {
        try {
            int release = Integer.parseInt(value.trim());
            return release <= 8 ? 8 : release;
        } catch (Exception ex) {
            return 8;
        }
    }

    private static int compareLauncherVersions(String left, String right) {
        int[] leftParts = parseVersionParts(left);
        int[] rightParts = parseVersionParts(right);
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
            int leftPart = i < leftParts.length ? leftParts[i] : 0;
            int rightPart = i < rightParts.length ? rightParts[i] : 0;
            if (leftPart != rightPart) {
                return leftPart - rightPart;
            }
        }
        return left.trim().compareToIgnoreCase(right.trim());
    }

    private static int[] parseVersionParts(String value) {
        String raw = value == null ? "" : value.trim().toLowerCase();
        if (raw.startsWith("v")) {
            raw = raw.substring(1);
        }
        String[] tokens = raw.split("[^0-9]+");
        List<Integer> parts = new ArrayList<Integer>();
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            try {
                parts.add(Integer.valueOf(token));
            } catch (NumberFormatException ignored) {
            }
        }
        if (parts.isEmpty()) {
            parts.add(Integer.valueOf(0));
        }
        int[] result = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            result[i] = parts.get(i).intValue();
        }
        return result;
    }

    private static String sanitizeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static String toVersionId(String name) {
        String id = name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        id = id.replaceAll("^-+", "").replaceAll("-+$", "");
        return id.isEmpty() ? "version" : id;
    }

    private static String defaultMainClass(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.startsWith("v0.0.") || "v0.1".equals(lower) || lower.contains("snapshot 1")) {
            return "TinyMinecraft";
        }
        return MAIN_CLASS;
    }

    private void setBusy(boolean busy, String status) {
        playButton.setEnabled(!busy);
        versionBox.setEnabled(!busy);
        nameField.setEnabled(!busy);
        themeButton.setEnabled(!busy);
        forceUpdateBox.setEnabled(!busy);
        keepLauncherOpenBox.setEnabled(!busy);
        openFolderButton.setEnabled(!busy);
        reloadButton.setEnabled(!busy);
        settingsButton.setEnabled(!busy);
        cancelButton.setVisible(busy);
        statusLabel.setText(status);
        if (!busy) {
            progressBar.setValue(0);
            progressBar.setString("");
            statusLabel.setVisible(false);
            progressBar.setVisible(false);
            updateSelectedVersionInfo();
        }
    }

    private void showError(String message, Exception ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        writeLauncherErrorLog(message, ex);
        String details = isCorruptedLauncherBuildFailure(cause)
                ? "Повреждена сборка лаунчера. Скачайте свежий TinyCraftLauncher-windows.zip"
                : cause.getMessage();
        if (details == null || details.trim().isEmpty()) {
            details = cause.getClass().getSimpleName();
        }
        JOptionPane.showMessageDialog(frame, message + ":\n" + details, "TinyCraft Launcher", JOptionPane.ERROR_MESSAGE);
    }

    private static boolean isInstalledVersionValid(File versionDir) {
        if (versionDir == null || !versionDir.isDirectory()) {
            return false;
        }

        File jarFile = new File(versionDir, GAME_JAR_NAME);
        if (!jarFile.isFile()) {
            return false;
        }

        if (!new File(versionDir, "run-game.bat").isFile()) {
            return false;
        }

        File libDir = new File(versionDir, "lib");
        File[] libs = libDir.isDirectory() ? libDir.listFiles(new java.io.FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile() && file.getName().toLowerCase().endsWith(".jar");
            }
        }) : null;
        return libs != null && libs.length > 0;
    }

    private static boolean isCorruptedLauncherBuildFailure(Throwable cause) {
        if (cause instanceof NoClassDefFoundError || cause instanceof ClassNotFoundException) {
            String text = cause.getMessage();
            return text == null || text.contains("Launcher$");
        }
        String name = cause.getClass().getName();
        return name.contains("InstallAndRunWorker") && (cause instanceof LinkageError || cause instanceof Error);
    }

    private static void writeLauncherErrorLog(String message, Throwable throwable) {
        File logDir = getLogsDirectory();
        if (!logDir.isDirectory() && !logDir.mkdirs()) {
            return;
        }

        File logFile = new File(logDir, "launcher-errors.log");
        try (FileWriter fileWriter = new FileWriter(logFile, true);
             PrintWriter writer = new PrintWriter(fileWriter)) {
            writer.println("==== " + new java.util.Date() + " :: " + message + " ====");
            StringWriter buffer = new StringWriter();
            PrintWriter stack = new PrintWriter(buffer);
            throwable.printStackTrace(stack);
            stack.flush();
            writer.print(buffer.toString());
            writer.println();
        } catch (IOException ignored) {
        }
    }

    private static final class LauncherUpdateInfo {
        private final String version;
        private final String description;
        private final String downloadUrl;
        private final String sha256;
        private boolean updateAvailable;

        private LauncherUpdateInfo(String version, String description, String downloadUrl, String sha256) {
            this.version = version == null ? "" : version.trim();
            this.description = description == null ? "" : description.trim();
            this.downloadUrl = downloadUrl == null ? "" : downloadUrl.trim();
            this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase();
        }
    }

    private static final class GameVersion {
        private final String id;
        private final String name;
        private final String type;
        private final int javaRelease;
        private final String mainClass;
        private final String sha256;
        private final String downloadUrl;

        private GameVersion(String name, String type, int javaRelease, String downloadUrl) {
            this(toVersionId(name), name, type, javaRelease, defaultMainClass(name), "", downloadUrl);
        }

        private GameVersion(String id, String name, String type, int javaRelease, String mainClass, String sha256, String downloadUrl) {
            this.id = id == null || id.trim().isEmpty() ? toVersionId(name) : id.trim();
            this.name = name;
            this.type = type;
            this.javaRelease = javaRelease;
            this.mainClass = mainClass == null || mainClass.trim().isEmpty() ? MAIN_CLASS : mainClass.trim();
            this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase();
            this.downloadUrl = downloadUrl;
        }

        private String typeLabel() {
            if ("release".equals(type)) {
                return "Релиз";
            }
            if ("snapshot".equals(type)) {
                return "Снапшот";
            }
            if ("classic".equals(type)) {
                return "Classic";
            }
            return "Старая версия";
        }

        @Override
        public String toString() {
            return name + " (" + typeLabel() + ")";
        }
    }
}

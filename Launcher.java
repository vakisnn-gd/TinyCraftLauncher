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
import java.io.BufferedOutputStream;
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
    private static final String GAME_JAR_NAME = "TinyCraft.jar";
    private static final String MAIN_CLASS = "TinyCraft";
    private static final int MIN_SUPPORTED_GAME_JAVA_RELEASE = 8;
    private static final int MAX_SUPPORTED_GAME_JAVA_RELEASE = 26;
    private static final int IO_BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_UPDATE_INTERVAL_NANOS = 150L * 1000L * 1000L;
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_FILE_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_ARCHIVE_ENTRIES = 50000;
    private static final String VERSIONS_DIRECTORY_NAME = "versions";
    private static final String LOGS_DIRECTORY_NAME = "logs";
    private static final String LAUNCHER_SETTINGS_FILE = "launcher.properties";
    private static final String LAUNCHER_VERSION = "v1.2.1";
    private static final String LAUNCHER_RELEASES_URL = "https://github.com/vakisnn-gd/TinyCraftLauncher/releases";
    private static final String LAUNCHER_UPDATE_MANIFEST_URL =
            "https://raw.githubusercontent.com/vakisnn-gd/TinyCraftLauncher/main/launcher-update.txt";
    private static final String LAUNCHER_UPDATE_MANIFEST_NAME = "launcher-update.txt";
    private static final String KEY_LAST_VERSION = "lastVersion";
    private static final String KEY_PLAYER_NAME = "playerName";
    private static final String KEY_KEEP_OPEN = "keepLauncherOpen";
    private static final String KEY_THEME = "theme";
    private static final String[] PRESERVED_VERSION_DATA = {
            "saves",
            "backups",
            "logs",
            "profile.properties",
            "options.txt",
            "server.properties",
            "ops.json",
            "whitelist.json",
            "banned-players.json"
    };
    private static final Color DEEP_GRASS = new Color(34, 84, 47);
    private static final Color GRASS = new Color(76, 155, 72);
    private static final Color LIGHT_GRASS = new Color(129, 194, 97);
    private static final Color LEGACY_PANEL = new Color(72, 76, 76);
    private static final Color LEGACY_FIELD = new Color(82, 84, 84);
    private static final Color LEGACY_TEXT = new Color(220, 220, 214);

    /*
     * Позже можно вынести список версий в отдельный файл на сайте/GitHub.
     * Формат manifest-файла:
     * id|name|type|java|mainClass|sha256|https-url
     * Для сетевой загрузки SHA-256 обязателен.
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
            new GameVersion("v0.2.1", "v0.2.1", "release", 8, MAIN_CLASS, "cce07d997cf986f2dfbbd4d2e7fc14234c9e67b909c3ee052628b7bad87f8116", releaseAssetUrl("v0.2.1", "TinyCraft-v0.2.1-windows.zip")),
            new GameVersion("v0.2-final", "v0.2 Final", "release", 8, MAIN_CLASS, "0986dd3cbbcc93368ed2014a7804ab71f7bc1eccb736347a3364670698bcf254", releaseAssetUrl("v0.2", "TinyCraft-v0.2-final-windows.zip")),
            new GameVersion("v0.2-snapshot8", "v0.2 Snapshot 8", "snapshot", 8, MAIN_CLASS, "dba81895c9c70ce43021989f8c3cdb43f75bcd05ae5e56b621b8ab344c9a3b20", releaseAssetUrl("v0.2-snapshot8", "TinyMinecraft-v0.2-snapshot8-windows.zip")),
            new GameVersion("v0.2-snapshot7", "v0.2 Snapshot 7", "snapshot", 17, MAIN_CLASS, "d98a4c1b5dea0b4b60a1297a26357968c77a3a05881669fac36c5d1221029a7a", releaseAssetUrl("v0.2-snapshot7", "TinyMinecraft-v0.2-snapshot7-windows.zip")),
            new GameVersion("v0.2-snapshot6", "v0.2 Snapshot 6", "snapshot", 17, MAIN_CLASS, "ef7d0cfec2168c31c7f99061d6cde4b6ab8e57c146eb94ca7c401cc634d9b19b", releaseAssetUrl("v0.2-snapshot6", "TinyMinecraft-v0.2-snapshot6-windows.zip")),
            new GameVersion("v0.2-snapshot5", "v0.2 Snapshot 5", "snapshot", 17, MAIN_CLASS, "1af29297a224ff0475fa962e9e4eeffdb10e63d976e184795efd2160517557c5", releaseAssetUrl("v0.2-snapshot5", "TinyMinecraft-v0.2-snapshot5-windows.zip")),
            new GameVersion("v0.2-snapshot4", "v0.2 Snapshot 4", "snapshot", 17, MAIN_CLASS, "d4f5acfad243e263d8977a724cebf224d4b075723389ca04a958e28e144c5f95", releaseAssetUrl("v0.2-snapshot4", "TinyMinecraft-v0.2-snapshot4-windows.zip")),
            new GameVersion("v0.2-snapshot3", "v0.2 Snapshot 3", "snapshot", 17, MAIN_CLASS, "677795e3acbaa40a27c9993640de90f155d0d10f35058e0aa5b15847e67ae9da", releaseAssetUrl("snapshot-0.2-v3", "TinyMinecraft-v0.2-snapshot3-windows.zip")),
            new GameVersion("v0.2-snapshot2", "v0.2 Snapshot 2", "snapshot", 17, MAIN_CLASS, "dfb4943984b5f660c7240de350f564fd3edaf1a0ed1160bde26e16f65ef21075", releaseAssetUrl("v0.2-snapshot2", "TinyMinecraft-v0.2-snapshot2-windows.zip")),
            new GameVersion("v0.2-snapshot1", "v0.2 Snapshot 1", "snapshot", 17, "TinyMinecraft", "2704823dc8022308572b3e5518962273766fb2e2a3c17b9f26bb8e489ca39130", releaseAssetUrl("v0.2-snapshot1", "TinyMinecraft-v0.2-snapshot1-windows.zip")),
            new GameVersion("v0.1", "v0.1", "release", 17, "TinyMinecraft", "b5e38bdf802f5e7e1296a37568ff18f937403dc90b9acaf0e441636c4adcd11d", releaseAssetUrl("v0.1", "TinyMinecraft-v0.1-windows.zip")),
            new GameVersion("v0.0.8", "v0.0.8", "classic", 17, "TinyMinecraft", "d8942e91a9858c4248f22a65df27c48556cd218467756842b07f514b90b583b8", releaseAssetUrl("v0.0.8", "TinyMinecraft-v0.0.8-windows.zip")),
            new GameVersion("v0.0.7", "v0.0.7", "classic", 17, "TinyMinecraft", "1b67d66366d3f32147a1bd4cc195e32712c75556d3b5052bf0ee92d1c0e1e688", releaseAssetUrl("v0.0.7", "TinyMinecraft-v0.0.7-windows.zip")),
            new GameVersion("v0.0.6", "v0.0.6", "classic", 17, "TinyMinecraft", "c337e8f49bf15b199b177383bf33d53b56e29b2bc78edb157b64dd6fc094073c", releaseAssetUrl("v0.0.6", "TinyMinecraft-v0.0.6-windows.zip")),
            new GameVersion("v0.0.5", "v0.0.5", "classic", 17, "TinyMinecraft", "7094782dc74eedfd7aeb3e90e21efd9e69724e6e5dd21ba03796f3f438e6affb", releaseAssetUrl("v0.0.5", "TinyMinecraft-v0.0.5-windows.zip")),
            new GameVersion("v0.0.4", "v0.0.4", "classic", 17, "TinyMinecraft", "02b7333688bf7c1c45e2183f3e80b361214a7f7c8840a009727bd3d53b62d1cc", releaseAssetUrl("v0.0.4", "TinyMinecraft-v0.0.4-windows.zip")),
            new GameVersion("v0.0.3", "v0.0.3", "classic", 17, "TinyMinecraft", "806e0bbaf4f190fb8ca35255e1a672aa98ef79b29ef60f97f8906565f7276557", releaseAssetUrl("v0.0.3", "TinyMinecraft-v0.0.3-windows.zip")),
            new GameVersion("v0.0.2", "v0.0.2", "classic", 17, "TinyMinecraft", "80cfe5020bcf043d708a662c250ef8a8e2f3939b02d4ed623d66dcfae6acf7ae", releaseAssetUrl("v0.0.2", "TinyMinecraft-v0.0.2-windows.zip")),
            new GameVersion("v0.0.1", "v0.0.1", "classic", 17, "TinyMinecraft", "a65d1345826a3832c85f550cd53b94eb4cbd472a93e13c46ac2f1cb9c299c854", releaseAssetUrl("v0.0.1", "TinyMinecraft-v0.0.1-windows.zip")),
            new GameVersion("v0.0.0", "v0.0.0", "classic", 17, "TinyMinecraft", "191cbce9dd2802f504b123b4fb2e42d625e8803894fe3000ea36df3df6730d1c", releaseAssetUrl("v0.0.0", "TinyMinecraft-v0.0.0-windows.zip"))
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
    private LauncherUpdateInfo availableLauncherUpdate;
    private boolean launcherUpdateInProgress;
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
                handleLauncherUpdateClick();
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
                        availableLauncherUpdate = null;
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
                        availableLauncherUpdate = info;
                        launcherInfoLabel.setText("Launcher " + LAUNCHER_VERSION + " -> " + info.version + " available");
                        launcherInfoLabel.setForeground(new Color(255, 214, 153));
                        offerLauncherUpdate(info);
                    } else {
                        availableLauncherUpdate = null;
                        launcherInfoLabel.setText("Launcher " + LAUNCHER_VERSION);
                        launcherInfoLabel.setForeground(new Color(230, 218, 180));
                    }
                } catch (Exception ignored) {
                    availableLauncherUpdate = null;
                    launcherInfoLabel.setText("Launcher " + LAUNCHER_VERSION);
                    launcherInfoLabel.setToolTipText(LAUNCHER_RELEASES_URL);
                    launcherUpdateUrl = LAUNCHER_RELEASES_URL;
                }
            }
        }.execute();
    }

    private LauncherUpdateInfo loadLauncherUpdateInfo() throws IOException {
        LauncherUpdateInfo newest = null;
        File bundledManifest = findBundledLauncherUpdateManifest();
        if (bundledManifest.isFile()) {
            try {
                newest = downloadLauncherUpdateInfo(bundledManifest.toURI().toString());
            } catch (IOException ignored) {
            }
        }

        if (!LAUNCHER_UPDATE_MANIFEST_URL.trim().isEmpty()) {
            try {
                LauncherUpdateInfo remote = downloadLauncherUpdateInfo(LAUNCHER_UPDATE_MANIFEST_URL);
                if (remote != null && (newest == null || compareLauncherVersions(remote.version, newest.version) > 0)) {
                    newest = remote;
                }
            } catch (IOException ignored) {
            }
        }
        return newest;
    }

    private static LauncherUpdateInfo downloadLauncherUpdateInfo(String manifestUrl) throws IOException {
        URI manifestUri = validateDownloadSource(manifestUrl, true);
        URLConnection connection = openSecureConnection(manifestUrl, true);
        validateHttpResponse(connection, manifestUrl);

        LauncherUpdateInfo newest = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\|", -1);
                if (parts.length < 4) {
                    throw new IOException("Некорректная запись launcher update manifest: " + line);
                }

                String downloadUrl = resolveManifestUrl(manifestUri, parts[2].trim());
                validateDownloadSource(downloadUrl, false);
                String sha256 = parts[3].trim().toLowerCase(java.util.Locale.ROOT);
                if (!isValidSha256(sha256)) {
                    throw new IOException("Некорректный SHA-256 обновления launcher " + parts[0].trim());
                }
                LauncherUpdateInfo candidate = new LauncherUpdateInfo(
                        parts[0].trim(),
                        parts[1].trim(),
                        downloadUrl,
                        sha256);
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

    private void handleLauncherUpdateClick() {
        LauncherUpdateInfo update = availableLauncherUpdate;
        if (update == null || !update.updateAvailable) {
            openLauncherReleasesPage();
            return;
        }
        if (findPackagedApplicationDirectory() == null) {
            openLauncherReleasesPage();
            return;
        }
        offerLauncherUpdate(update);
    }

    private void offerLauncherUpdate(LauncherUpdateInfo update) {
        if (update == null || launcherUpdateInProgress || findPackagedApplicationDirectory() == null) {
            return;
        }
        if (currentWorker != null && !currentWorker.isDone()) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(frame,
                "Доступен TinyCraft Launcher " + update.version + ".\n"
                        + (update.description.isEmpty() ? "" : update.description + "\n")
                        + "Скачать, проверить SHA-256 и установить обновление?",
                "Обновление лаунчера", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        File applicationDirectory = findPackagedApplicationDirectory();
        if (applicationDirectory == null) {
            openLauncherReleasesPage();
            return;
        }
        launcherUpdateInProgress = true;
        playButton.setEnabled(false);
        launcherInfoLabel.setText("Скачивание Launcher " + update.version + "...");
        new LauncherUpdateWorker(update, applicationDirectory).execute();
    }

    private final class LauncherUpdateWorker extends SwingWorker<PreparedLauncherUpdate, Void> {
        private final LauncherUpdateInfo update;
        private final File applicationDirectory;
        private int lastProgressValue = -1;

        private LauncherUpdateWorker(LauncherUpdateInfo update, File applicationDirectory) {
            this.update = update;
            this.applicationDirectory = applicationDirectory;
        }

        @Override
        protected PreparedLauncherUpdate doInBackground() throws Exception {
            File updateRoot = new File(getLauncherDataDirectory(), "launcher-update");
            if (!updateRoot.isDirectory() && !updateRoot.mkdirs()) {
                throw new IOException("Не удалось создать папку обновления: " + updateRoot.getAbsolutePath());
            }
            String safeVersion = toVersionId(update.version);
            File archiveFile = new File(updateRoot, "launcher-" + safeVersion + ".zip");
            File stagingDirectory = new File(updateRoot, safeVersion + ".install");
            Files.deleteIfExists(archiveFile.toPath());
            deleteDirectory(stagingDirectory);
            if (!stagingDirectory.mkdirs()) {
                throw new IOException("Не удалось создать папку обновления: " + stagingDirectory.getAbsolutePath());
            }

            boolean prepared = false;
            try {
                updateProgress(2, "Скачивание Launcher " + update.version + "...");
                downloadSecureFile(update.downloadUrl, update.sha256, archiveFile, new ProgressListener() {
                    @Override
                    public void onProgress(long currentBytes, long totalBytes) {
                        if (totalBytes > 0) {
                            int value = 2 + (int) Math.min(53, currentBytes * 53 / totalBytes);
                            int percent = (int) Math.min(100, currentBytes * 100 / totalBytes);
                            updateProgress(value, "Скачивание обновления " + percent + "%");
                        } else {
                            updateProgress(2, "Скачивание обновления " + formatBytes(currentBytes));
                        }
                    }
                });

                updateProgress(56, "Распаковка обновления...");
                unzipGitHubArchive(archiveFile, stagingDirectory, new ProgressListener() {
                    @Override
                    public void onProgress(long currentBytes, long totalBytes) {
                        int value = totalBytes > 0 ? 56 + (int) Math.min(34, currentBytes * 34 / totalBytes) : 56;
                        updateProgress(value, "Распаковка обновления...");
                    }
                });
                if (!isValidLauncherApplication(stagingDirectory)) {
                    throw new IOException("Архив обновления не содержит полноценный TinyCraftLauncher.");
                }

                File updaterScript = writeLauncherUpdaterScript(updateRoot);
                updateProgress(95, "Обновление готово к установке");
                prepared = true;
                return new PreparedLauncherUpdate(updaterScript, stagingDirectory, applicationDirectory);
            } finally {
                Files.deleteIfExists(archiveFile.toPath());
                if (!prepared) {
                    deleteDirectory(stagingDirectory);
                }
            }
        }

        private void updateProgress(int value, String message) {
            if (value == lastProgressValue) {
                return;
            }
            lastProgressValue = value;
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    statusLabel.setVisible(true);
                    progressBar.setVisible(true);
                    progressBar.setValue(value);
                    progressBar.setString(message);
                    statusLabel.setText(message);
                }
            });
        }

        @Override
        protected void done() {
            try {
                PreparedLauncherUpdate prepared = get();
                JOptionPane.showMessageDialog(frame,
                        "Обновление проверено и готово. Лаунчер сейчас перезапустится.",
                        "Обновление лаунчера", JOptionPane.INFORMATION_MESSAGE);
                startLauncherUpdater(prepared);
                frame.dispose();
                System.exit(0);
            } catch (Exception ex) {
                launcherUpdateInProgress = false;
                playButton.setEnabled(true);
                launcherInfoLabel.setText("Launcher " + LAUNCHER_VERSION + " -> " + update.version + " available");
                showError("Не удалось обновить лаунчер", ex);
                setBusy(false, "Ошибка обновления");
            }
        }
    }

    private static File findPackagedApplicationDirectory() {
        File launcherDirectory = getLauncherDirectory();
        File applicationDirectory = launcherDirectory.getParentFile();
        if (applicationDirectory == null) {
            return null;
        }
        File executable = new File(applicationDirectory, "TinyCraftLauncher.exe");
        File applicationJar = new File(new File(applicationDirectory, "app"), "TinyCraftLauncher.jar");
        File runtimeJava = new File(new File(new File(applicationDirectory, "runtime"), "bin"), "java.exe");
        return isNonEmptyFile(executable) && isNonEmptyFile(applicationJar) && isNonEmptyFile(runtimeJava)
                ? applicationDirectory : null;
    }

    private static boolean isValidLauncherApplication(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return false;
        }
        return isNonEmptyFile(new File(directory, "TinyCraftLauncher.exe"))
                && isNonEmptyFile(new File(new File(directory, "app"), "TinyCraftLauncher.jar"))
                && isNonEmptyFile(new File(new File(new File(directory, "runtime"), "bin"), "java.exe"));
    }

    private static File writeLauncherUpdaterScript(File updateRoot) throws IOException {
        File script = new File(updateRoot, "apply-launcher-update.ps1");
        String content = "param([Parameter(Mandatory=$true)][string]$Source, [Parameter(Mandatory=$true)][string]$Target)\r\n"
                + "$ErrorActionPreference = 'Stop'\r\n"
                + "$backup = $Target + '.previous-update'\r\n"
                + "$log = Join-Path (Split-Path -Parent $Source) 'launcher-update.log'\r\n"
                + "Start-Sleep -Seconds 2\r\n"
                + "try {\r\n"
                + "  if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Recurse -Force }\r\n"
                + "  $moved = $false\r\n"
                + "  for ($attempt = 0; $attempt -lt 60; $attempt++) {\r\n"
                + "    try { Move-Item -LiteralPath $Target -Destination $backup -ErrorAction Stop; $moved = $true; break }\r\n"
                + "    catch { if ($attempt -eq 59) { throw }; Start-Sleep -Milliseconds 500 }\r\n"
                + "  }\r\n"
                + "  if (-not $moved) { throw 'Could not unlock the old launcher.' }\r\n"
                + "  try {\r\n"
                + "    Move-Item -LiteralPath $Source -Destination $Target -ErrorAction Stop\r\n"
                + "    $exe = Join-Path $Target 'TinyCraftLauncher.exe'\r\n"
                + "    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw 'Updated launcher executable is missing.' }\r\n"
                + "  } catch {\r\n"
                + "    if (Test-Path -LiteralPath $Target) { Remove-Item -LiteralPath $Target -Recurse -Force }\r\n"
                + "    Move-Item -LiteralPath $backup -Destination $Target -ErrorAction Stop\r\n"
                + "    throw\r\n"
                + "  }\r\n"
                + "  Start-Process -FilePath $exe -WorkingDirectory $Target\r\n"
                + "  if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Recurse -Force }\r\n"
                + "} catch {\r\n"
                + "  $_ | Out-File -LiteralPath $log -Encoding UTF8\r\n"
                + "  if ((-not (Test-Path -LiteralPath $Target)) -and (Test-Path -LiteralPath $backup)) {\r\n"
                + "    Move-Item -LiteralPath $backup -Destination $Target -ErrorAction SilentlyContinue\r\n"
                + "  }\r\n"
                + "  $oldExe = Join-Path $Target 'TinyCraftLauncher.exe'\r\n"
                + "  if (Test-Path -LiteralPath $oldExe -PathType Leaf) { Start-Process -FilePath $oldExe -WorkingDirectory $Target }\r\n"
                + "  exit 1\r\n"
                + "}\r\n";
        Files.write(script.toPath(), content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return script;
    }

    private static void startLauncherUpdater(PreparedLauncherUpdate update) throws IOException {
        new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", update.script.getAbsolutePath(),
                "-Source", update.sourceDirectory.getAbsolutePath(),
                "-Target", update.targetDirectory.getAbsolutePath())
                .directory(update.script.getParentFile())
                .start();
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

    private final class InstallAndRunWorker extends SwingWorker<Void, Void> {
        private final GameVersion version;
        private long lastProgressUpdateNanos;
        private int lastProgressValue = -1;

        private InstallAndRunWorker(GameVersion version) {
            this.version = version;
        }

        @Override
        protected Void doInBackground() throws Exception {
            File versionDir = getVersionDirectory(version);
            File jarFile = new File(versionDir, GAME_JAR_NAME);
            recoverInterruptedInstall(versionDir, getPreviousVersionDirectory(version));

            boolean mustRefresh = forceUpdateBox.isSelected() || !isInstalledVersionValid(versionDir);
            if (mustRefresh) {
                installVersion(version, versionDir, jarFile);
            }

            checkCancelled();
            saveProfile(versionDir, nameField.getText());
            launchGame(version, jarFile);
            return null;
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
            validateGameDownload(version.downloadUrl, version.sha256);
            File versionsRoot = getVersionsDirectory();
            if (!versionsRoot.isDirectory() && !versionsRoot.mkdirs()) {
                throw new IOException("Не удалось создать папку: " + versionsRoot.getAbsolutePath());
            }

            File downloadsDir = getDownloadsDirectory();
            if (!downloadsDir.isDirectory() && !downloadsDir.mkdirs()) {
                throw new IOException("Не удалось создать папку: " + downloadsDir.getAbsolutePath());
            }

            File archiveFile = new File(downloadsDir, versionDir.getName() + ".zip");
            File stagingDir = new File(versionsRoot, versionDir.getName() + ".install");
            File previousDir = getPreviousVersionDirectory(version);
            deleteDirectory(stagingDir);
            if (!stagingDir.mkdirs()) {
                throw new IOException("Не удалось создать папку: " + stagingDir.getAbsolutePath());
            }
            Files.deleteIfExists(archiveFile.toPath());

            boolean installed = false;
            try {
                progress(2, "Подготовка загрузки " + version.name + "...");
                downloadFile(version.downloadUrl, version.sha256, archiveFile, 2, 55);
                checkCancelled();

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
                replaceInstalledVersion(versionDir, stagingDir, previousDir);
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
            long now = System.nanoTime();
            if (value == lastProgressValue && value < 100
                    && now - lastProgressUpdateNanos < PROGRESS_UPDATE_INTERVAL_NANOS) {
                return;
            }
            lastProgressValue = value;
            lastProgressUpdateNanos = now;
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    statusLabel.setVisible(true);
                    progressBar.setVisible(true);
                    progressBar.setValue(value);
                    progressBar.setString(message);
                }
            });
        }

        private void downloadFile(String sourceUrl, String expectedSha256, File targetFile,
                                  int startPercent, int endPercent) throws IOException {
            downloadSecureFile(sourceUrl, expectedSha256, targetFile, new ProgressListener() {
                @Override
                public void onProgress(long downloaded, long totalBytes) throws IOException {
                    checkCancelled();
                    if (totalBytes > 0) {
                        int range = endPercent - startPercent;
                        int value = startPercent + (int) Math.min(range, downloaded * range / totalBytes);
                        int percent = (int) Math.min(100, downloaded * 100 / totalBytes);
                        progress(value, "Скачивание " + percent + "% (" + formatBytes(downloaded)
                                + " / " + formatBytes(totalBytes) + ")");
                    } else {
                        progress(startPercent, "Скачивание " + formatBytes(downloaded));
                    }
                }
            });
        }

        private void checkCancelled() throws IOException {
            if (isCancelled()) {
                throw new IOException("Операция отменена.");
            }
        }
    }

    private interface ProgressListener {
        void onProgress(long currentBytes, long totalBytes) throws IOException;
    }

    private static void downloadSecureFile(String sourceUrl, String expectedSha256, File targetFile,
                                           ProgressListener progressListener) throws IOException {
        validateGameDownload(sourceUrl, expectedSha256);
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Не удалось создать папку: " + parent.getAbsolutePath());
        }
        File tempFile = new File(parent, targetFile.getName() + ".download");
        Files.deleteIfExists(tempFile.toPath());
        URLConnection connection = null;
        boolean completed = false;
        try {
            MessageDigest digest = expectedSha256 == null || expectedSha256.isEmpty()
                    ? null : createSha256Digest();
            connection = openSecureConnection(sourceUrl, true);
            validateHttpResponse(connection, sourceUrl);
            long totalBytes = connection.getContentLengthLong();
            if (totalBytes > MAX_DOWNLOAD_BYTES) {
                throw new IOException("Файл слишком большой: " + formatBytes(totalBytes));
            }

            long downloaded = 0;
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), IO_BUFFER_SIZE);
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(tempFile), IO_BUFFER_SIZE)) {
                byte[] buffer = new byte[IO_BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    downloaded += read;
                    if (downloaded > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("Загрузка превысила безопасный размер " + formatBytes(MAX_DOWNLOAD_BYTES));
                    }
                    if (digest != null) {
                        digest.update(buffer, 0, read);
                    }
                    out.write(buffer, 0, read);
                    if (progressListener != null) {
                        progressListener.onProgress(downloaded, totalBytes);
                    }
                }
            }

            if (totalBytes >= 0 && downloaded != totalBytes) {
                throw new IOException("Загрузка оборвалась: ожидалось " + formatBytes(totalBytes)
                        + ", получено " + formatBytes(downloaded));
            }
            if (digest != null) {
                String actualSha256 = toHex(digest.digest());
                if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                    throw new IOException("SHA-256 не совпал для " + targetFile.getName() + ": " + actualSha256);
                }
            }
            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            completed = true;
        } finally {
            if (connection instanceof HttpURLConnection) {
                ((HttpURLConnection) connection).disconnect();
            }
            if (!completed) {
                Files.deleteIfExists(tempFile.toPath());
            }
        }
    }

    private static void unzipGitHubArchive(File archiveFile, File targetDir, ProgressListener progressListener) throws IOException {
        String targetPath = targetDir.getCanonicalPath() + File.separator;
        long totalBytes = archiveFile.length();
        long extractedBytes = 0;
        int entryCount = 0;

        try (CountingInputStream input = new CountingInputStream(new FileInputStream(archiveFile));
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("В архиве слишком много файлов: больше " + MAX_ARCHIVE_ENTRIES);
                }
                if (entry.getSize() > MAX_EXTRACTED_FILE_BYTES) {
                    throw new IOException("Файл в архиве слишком большой: " + entry.getName());
                }
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
                    byte[] buffer = new byte[IO_BUFFER_SIZE];
                    int read;
                    long extractedFileBytes = 0;
                    while ((read = zip.read(buffer)) != -1) {
                        extractedFileBytes += read;
                        extractedBytes += read;
                        if (extractedFileBytes > MAX_EXTRACTED_FILE_BYTES || extractedBytes > MAX_EXTRACTED_BYTES) {
                            throw new IOException("Архив превышает безопасный размер распаковки.");
                        }
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
                byte[] buffer = new byte[IO_BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    jar.write(buffer, 0, read);
                }
            }

            jar.closeEntry();
        }
    }

    private static MessageDigest createSha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception ex) {
            throw new IOException("SHA-256 недоступен в текущей Java.", ex);
        }
    }

    private static String toHex(byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    private static void validateGameDownload(String sourceUrl, String expectedSha256) throws IOException {
        URI source = validateDownloadSource(sourceUrl, true);
        boolean validHash = isValidSha256(expectedSha256);
        if (expectedSha256 != null && !expectedSha256.trim().isEmpty() && !validHash) {
            throw new IOException("Некорректный SHA-256 для загрузки " + sourceUrl);
        }
        if ("https".equalsIgnoreCase(source.getScheme()) && !validHash) {
            throw new IOException("Сетевая загрузка заблокирована: для неё не указан SHA-256.");
        }
    }

    private static boolean isValidSha256(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{64}");
    }

    private static URLConnection openSecureConnection(String sourceUrl, boolean allowLocalFile) throws IOException {
        URI source = validateDownloadSource(sourceUrl, allowLocalFile);
        URLConnection connection = source.toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "TinyCraftLauncher/" + LAUNCHER_VERSION);
        return connection;
    }

    private static void validateHttpResponse(URLConnection connection, String sourceUrl) throws IOException {
        if (!(connection instanceof HttpURLConnection)) {
            return;
        }
        int responseCode = ((HttpURLConnection) connection).getResponseCode();
        validateDownloadSource(connection.getURL().toString(), false);
        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("Сервер вернул HTTP " + responseCode + " для " + sourceUrl);
        }
    }

    private static URI validateDownloadSource(String sourceUrl, boolean allowLocalFile) throws IOException {
        final URI source;
        try {
            source = URI.create(sourceUrl == null ? "" : sourceUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new IOException("Некорректный адрес загрузки: " + sourceUrl, ex);
        }

        String scheme = source.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return source;
        }
        if (allowLocalFile && "file".equalsIgnoreCase(scheme)
                && (source.getAuthority() == null || source.getAuthority().isEmpty())) {
            return source;
        }
        throw new IOException("Небезопасный адрес загрузки: разрешены только HTTPS"
                + (allowLocalFile ? " и локальные файлы" : "") + ".");
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

    private static void replaceInstalledVersion(File versionDir, File stagingDir, File previousDir) throws IOException {
        if (previousDir.exists()) {
            throw new IOException("Не завершено предыдущее обновление: " + previousDir.getAbsolutePath());
        }
        if (!isInstalledVersionValid(stagingDir)) {
            throw new IOException("Новая версия не прошла проверку комплектности: " + stagingDir.getAbsolutePath());
        }

        boolean previousMoved = false;
        try {
            if (versionDir.exists()) {
                Files.move(versionDir.toPath(), previousDir.toPath());
                previousMoved = true;
            }

            Files.move(stagingDir.toPath(), versionDir.toPath());
            if (previousMoved) {
                copyPreservedVersionData(previousDir, versionDir);
            }
        } catch (IOException | RuntimeException failure) {
            if (previousMoved && previousDir.exists()) {
                try {
                    deleteDirectory(versionDir);
                    Files.move(previousDir.toPath(), versionDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException | RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }

        if (previousMoved) {
            try {
                deleteDirectory(previousDir);
            } catch (IOException | RuntimeException cleanupFailure) {
                throw new IOException("Не удалось завершить очистку предыдущей версии: "
                        + previousDir.getAbsolutePath(), cleanupFailure);
            }
        }
    }

    private static void recoverInterruptedInstall(File versionDir, File previousDir) throws IOException {
        if (!previousDir.exists()) {
            return;
        }

        if (!isInstalledVersionValid(versionDir)) {
            deleteDirectory(versionDir);
            Files.move(previousDir.toPath(), versionDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        copyPreservedVersionData(previousDir, versionDir);
        deleteDirectory(previousDir);
    }

    private static void copyPreservedVersionData(File sourceDir, File targetDir) throws IOException {
        for (String name : PRESERVED_VERSION_DATA) {
            File source = new File(sourceDir, name);
            if (!source.exists()) {
                continue;
            }

            File target = new File(targetDir, name);
            deleteDirectory(target);
            copyRecursively(source, target);
        }
    }

    private static void copyRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IOException("Не удалось создать папку: " + target.getAbsolutePath());
            }
            File[] children = source.listFiles();
            if (children == null) {
                throw new IOException("Не удалось прочитать папку: " + source.getAbsolutePath());
            }
            for (File child : children) {
                copyRecursively(child, new File(target, child.getName()));
            }
            return;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Не удалось создать папку: " + parent.getAbsolutePath());
        }
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void launchGame(GameVersion version, File jarFile) throws IOException {
        JavaRuntime javaRuntime = findJavaRuntime(jarFile.getParentFile(), version.javaRelease);
        File logDir = getLogsDirectory();
        if (!logDir.isDirectory() && !logDir.mkdirs()) {
            throw new IOException("Не удалось создать папку логов: " + logDir.getAbsolutePath());
        }
        File logFile = new File(logDir, version.id + "-latest.log");
        List<String> command = new ArrayList<String>();
        command.add(javaRuntime.executable.getAbsolutePath());
        command.add("-Dfile.encoding=UTF-8");
        if (javaRuntime.release >= 17) {
            command.add("--enable-native-access=ALL-UNNAMED");
        }
        command.add("-Dtinycraft.home=" + jarFile.getParentFile().getAbsolutePath());
        command.add("-jar");
        command.add(jarFile.getAbsolutePath());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(jarFile.getParentFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        Files.write(logFile.toPath(),
                ("TinyCraft launch log for " + version.name + System.lineSeparator()
                        + "Java " + javaRuntime.release + ": " + javaRuntime.executable.getAbsolutePath()
                        + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
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

    private static JavaRuntime findJavaRuntime(File versionDir, int minimumRelease) throws IOException {
        if (minimumRelease > MAX_SUPPORTED_GAME_JAVA_RELEASE) {
            throw new IOException("Версия игры требует Java " + minimumRelease
                    + ", но лаунчер поддерживает запуск только до Java " + MAX_SUPPORTED_GAME_JAVA_RELEASE + ".");
        }

        List<File> candidates = new ArrayList<File>();
        addRuntimeCandidates(candidates, versionDir);
        addLauncherRuntimeCandidates(candidates);
        addJavaHomeCandidates(candidates, System.getenv("TINYCRAFT_JAVA_HOME"));
        addJavaHomeCandidates(candidates, System.getenv("JAVA_HOME"));
        addJavaHomeCandidates(candidates, System.getProperty("java.home"));
        addPathJavaCandidates(candidates);
        addKnownJavaCandidates(candidates);

        List<String> checkedPaths = new ArrayList<String>();
        List<String> foundVersions = new ArrayList<String>();
        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile()) {
                continue;
            }

            String path;
            try {
                path = candidate.getCanonicalPath();
            } catch (IOException ex) {
                path = candidate.getAbsolutePath();
            }
            if (containsPathIgnoreCase(checkedPaths, path)) {
                continue;
            }
            checkedPaths.add(path);

            try {
                int release = detectJavaRelease(candidate);
                foundVersions.add("Java " + release + " (" + path + ")");
                if (isSupportedJavaRelease(release, minimumRelease)) {
                    return new JavaRuntime(candidate.getCanonicalFile(), release);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Проверка Java была прервана: " + path, ex);
            } catch (Exception ignored) {
                foundVersions.add("не удалось проверить " + path);
            }
        }

        int requiredRelease = Math.max(minimumRelease, MIN_SUPPORTED_GAME_JAVA_RELEASE);
        String details = foundVersions.isEmpty() ? " Java не найдена." : " Найдено: " + String.join("; ", foundVersions);
        throw new IOException("Не найдена поддерживаемая Java " + requiredRelease + "-"
                + MAX_SUPPORTED_GAME_JAVA_RELEASE + ". Установите Java этого диапазона или добавьте runtime в папку версии."
                + details);
    }

    private static boolean isSupportedJavaRelease(int release, int minimumRelease) {
        return release >= Math.max(minimumRelease, MIN_SUPPORTED_GAME_JAVA_RELEASE)
                && release <= MAX_SUPPORTED_GAME_JAVA_RELEASE;
    }

    private static void addRuntimeCandidates(List<File> candidates, File versionDir) {
        if (versionDir == null) {
            return;
        }
        addJavaBinCandidates(candidates, new File(versionDir, "runtime"));
    }

    private static void addLauncherRuntimeCandidates(List<File> candidates) {
        File launcherDir = getLauncherDirectory();
        addJavaBinCandidates(candidates, new File(launcherDir, "runtime"));
        File parentDir = launcherDir.getParentFile();
        if (parentDir != null) {
            addJavaBinCandidates(candidates, new File(parentDir, "runtime"));
        }
    }

    private static void addJavaHomeCandidates(List<File> candidates, String javaHome) {
        if (javaHome == null || javaHome.trim().isEmpty()) {
            return;
        }
        addJavaBinCandidates(candidates, new File(javaHome.trim()));
    }

    private static void addJavaBinCandidates(List<File> candidates, File javaHome) {
        File bin = new File(javaHome, "bin");
        candidates.add(new File(bin, "java.exe"));
        candidates.add(new File(bin, "java"));
    }

    private static void addKnownJavaCandidates(List<File> candidates) {
        addJavaInstallations(candidates, System.getenv("LOCALAPPDATA"), "Programs" + File.separator + "Eclipse Adoptium");
        addJavaInstallations(candidates, System.getenv("ProgramFiles"), "Eclipse Adoptium");
        addJavaInstallations(candidates, System.getenv("ProgramFiles"), "Java");
        addJavaInstallations(candidates, System.getenv("ProgramFiles"), "Microsoft");
    }

    private static void addJavaInstallations(List<File> candidates, String root, String childPath) {
        if (root == null || root.trim().isEmpty()) {
            return;
        }
        File directory = new File(root, childPath);
        File[] installations = directory.listFiles(new java.io.FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isDirectory();
            }
        });
        if (installations == null) {
            return;
        }
        List<File> sorted = new ArrayList<File>();
        Collections.addAll(sorted, installations);
        Collections.sort(sorted, (left, right) -> right.getName().compareToIgnoreCase(left.getName()));
        for (File installation : sorted) {
            addJavaBinCandidates(candidates, installation);
        }
    }

    private static void addPathJavaCandidates(List<File> candidates) {
        String path = System.getenv("PATH");
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String directory = entry.trim();
            if (directory.startsWith("\"") && directory.endsWith("\"") && directory.length() > 1) {
                directory = directory.substring(1, directory.length() - 1);
            }
            if (directory.isEmpty()) {
                continue;
            }
            candidates.add(new File(directory, "java.exe"));
            candidates.add(new File(directory, "java"));
        }
    }

    private static boolean containsPathIgnoreCase(List<String> paths, String candidate) {
        for (String path : paths) {
            if (path.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
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
        if (version == null) {
            throw new IllegalArgumentException("Версия не указана.");
        }
        String folderName = requireSafeVersionId(version.id);
        Path versionsRoot = getVersionsDirectory().toPath().toAbsolutePath().normalize();
        Path versionPath = versionsRoot.resolve(folderName).normalize();
        if (!versionPath.startsWith(versionsRoot) || versionPath.equals(versionsRoot)) {
            throw new IllegalArgumentException("Небезопасный ID версии: " + version.id);
        }
        return versionPath.toFile();
    }

    private static File getPreviousVersionDirectory(GameVersion version) {
        File versionDir = getVersionDirectory(version);
        return new File(versionDir.getParentFile(), versionDir.getName() + ".previous");
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
        URI manifestUri = validateDownloadSource(manifestUrl, true);
        URLConnection connection = openSecureConnection(manifestUrl, true);
        validateHttpResponse(connection, manifestUrl);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\|", -1);
                try {
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
                } catch (IllegalArgumentException ex) {
                    throw new IOException("Некорректная запись manifest: " + line, ex);
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

    private static String requireSafeVersionId(String value) {
        String id = value == null ? "" : value.trim();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || isWindowsReservedName(id)) {
            throw new IllegalArgumentException("Небезопасный ID версии: " + value);
        }
        return id;
    }

    private static boolean isWindowsReservedName(String value) {
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        int dot = upper.indexOf('.');
        String base = dot >= 0 ? upper.substring(0, dot) : upper;
        return "CON".equals(base) || "PRN".equals(base) || "AUX".equals(base) || "NUL".equals(base)
                || base.matches("COM[1-9]") || base.matches("LPT[1-9]");
    }

    private static String toVersionId(String name) {
        String id = (name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)).replaceAll("[^a-z0-9]+", "-");
        id = id.replaceAll("^-+", "").replaceAll("-+$", "");
        if (id.length() > 64) {
            id = id.substring(0, 64).replaceAll("-+$", "");
        }
        if (id.isEmpty()) {
            id = "version";
        }
        if (isWindowsReservedName(id)) {
            id = "version-" + id;
        }
        return requireSafeVersionId(id);
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
        if (!isRunnableGameJar(jarFile)) {
            return false;
        }

        File libDir = new File(versionDir, "lib");
        File[] libs = libDir.isDirectory() ? libDir.listFiles(new java.io.FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile() && file.getName().toLowerCase().endsWith(".jar");
            }
        }) : null;
        return libs != null
                && hasCoreLwjglLibrary(libs)
                && hasLibrary(libs, "lwjgl-glfw-")
                && hasLibrary(libs, "lwjgl-opengl-")
                && hasLibrary(libs, "lwjgl-stb-")
                && hasCoreWindowsNativeLibrary(libs)
                && hasWindowsNativeLibrary(libs, "lwjgl-glfw-")
                && hasWindowsNativeLibrary(libs, "lwjgl-opengl-")
                && hasWindowsNativeLibrary(libs, "lwjgl-stb-");
    }

    private static boolean isRunnableGameJar(File jarFile) {
        if (!isNonEmptyFile(jarFile)) {
            return false;
        }
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return false;
            }
            String mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            return mainClass != null && jar.getJarEntry(mainClass.replace('.', '/') + ".class") != null;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static boolean isNonEmptyFile(File file) {
        return file.isFile() && file.length() > 0;
    }

    private static boolean hasLibrary(File[] libraries, String prefix) {
        for (File library : libraries) {
            String name = library.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.startsWith(prefix) && !name.contains("natives") && library.length() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCoreLwjglLibrary(File[] libraries) {
        for (File library : libraries) {
            String name = library.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.matches("lwjgl-[0-9].*\\.jar") && library.length() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCoreWindowsNativeLibrary(File[] libraries) {
        for (File library : libraries) {
            String name = library.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.matches("lwjgl-[0-9].*-natives-windows[^/]*\\.jar") && library.length() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWindowsNativeLibrary(File[] libraries, String prefix) {
        for (File library : libraries) {
            String name = library.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.startsWith(prefix) && name.contains("natives-windows") && library.length() > 0) {
                return true;
            }
        }
        return false;
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
            this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final class PreparedLauncherUpdate {
        private final File script;
        private final File sourceDirectory;
        private final File targetDirectory;

        private PreparedLauncherUpdate(File script, File sourceDirectory, File targetDirectory) {
            this.script = script;
            this.sourceDirectory = sourceDirectory;
            this.targetDirectory = targetDirectory;
        }
    }

    private static final class JavaRuntime {
        private final File executable;
        private final int release;

        private JavaRuntime(File executable, int release) {
            this.executable = executable;
            this.release = release;
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
            this.id = requireSafeVersionId(id == null || id.trim().isEmpty() ? toVersionId(name) : id.trim());
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

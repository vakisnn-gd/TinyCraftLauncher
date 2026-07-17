import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public final class LauncherDownloadSecurityTest {
    private static final String VALID_SHA256 =
            "ec114912f31ae573c87c2b387401748c97d2439b044b8c646d212c3ed73f4392";

    public static void main(String[] args) throws Exception {
        acceptsOnlySecureSources();
        requiresHashForNetworkArchives();
        calculatesSha256DuringStreaming();
        downloadsAndVerifiesInOnePass();
        validatesLauncherUpdateManifest();
        validatesLauncherUpdateLayout();
        rejectsUnsafeVersionIds();
        verifiesBuiltInReleaseMetadata();
        System.out.println("LauncherDownloadSecurityTest: OK");
    }

    private static void acceptsOnlySecureSources() throws Exception {
        invoke("validateDownloadSource", new Class<?>[]{String.class, Boolean.TYPE},
                "https://github.com/vakisnn-gd/TinyCraft/releases", false);
        invoke("validateDownloadSource", new Class<?>[]{String.class, Boolean.TYPE},
                new java.io.File("local.zip").toURI().toString(), true);
        expectIOException("validateDownloadSource", new Class<?>[]{String.class, Boolean.TYPE},
                "http://example.invalid/game.zip", true);
        expectIOException("validateDownloadSource", new Class<?>[]{String.class, Boolean.TYPE},
                "file://server/share/game.zip", true);
        expectIOException("validateDownloadSource", new Class<?>[]{String.class, Boolean.TYPE},
                new java.io.File("local.zip").toURI().toString(), false);
    }

    private static void requiresHashForNetworkArchives() throws Exception {
        invoke("validateGameDownload", new Class<?>[]{String.class, String.class},
                "https://example.invalid/game.zip", VALID_SHA256);
        expectIOException("validateGameDownload", new Class<?>[]{String.class, String.class},
                "https://example.invalid/game.zip", "");
        expectIOException("validateGameDownload", new Class<?>[]{String.class, String.class},
                "https://example.invalid/game.zip", "not-a-hash");
        expectIOException("validateGameDownload", new Class<?>[]{String.class, String.class},
                "http://example.invalid/game.zip", VALID_SHA256);
    }

    private static void calculatesSha256DuringStreaming() throws Exception {
        MessageDigest digest = (MessageDigest) invoke("createSha256Digest", new Class<?>[0]);
        digest.update("TinyCraft".getBytes(StandardCharsets.UTF_8));
        String actual = (String) invoke("toHex", new Class<?>[]{byte[].class}, (Object) digest.digest());
        assertTrue("8fcf63170dd23a10c81b901817c549cc015d3443413d49ee57885c03a4eba054".equals(actual),
                "streaming SHA-256 is incorrect: " + actual);

        Field bufferSizeField = Launcher.class.getDeclaredField("IO_BUFFER_SIZE");
        bufferSizeField.setAccessible(true);
        assertTrue(bufferSizeField.getInt(null) == 64 * 1024, "I/O buffer is not 64 KiB");
    }

    private static void downloadsAndVerifiesInOnePass() throws Exception {
        Path root = Files.createTempDirectory("tinycraft-secure-download-");
        try {
            File source = root.resolve("source.zip").toFile();
            File target = root.resolve("target.zip").toFile();
            Files.write(source.toPath(), "TinyCraft".getBytes(StandardCharsets.UTF_8));
            Class<?> progressType = Class.forName("Launcher$ProgressListener");
            Class<?>[] parameters = {String.class, String.class, File.class, progressType};
            String expected = "8fcf63170dd23a10c81b901817c549cc015d3443413d49ee57885c03a4eba054";
            invoke("downloadSecureFile", parameters, source.toURI().toString(), expected, target, (Object) null);
            assertTrue(target.isFile(), "verified download was not moved into place");

            File rejected = root.resolve("rejected.zip").toFile();
            expectIOException("downloadSecureFile", parameters, source.toURI().toString(), VALID_SHA256,
                    rejected, (Object) null);
            assertTrue(!rejected.exists(), "download with wrong SHA-256 was retained");
            assertTrue(!new File(root.toFile(), "rejected.zip.download").exists(), "partial download was retained");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void validatesLauncherUpdateManifest() throws Exception {
        Path root = Files.createTempDirectory("tinycraft-update-manifest-");
        try {
            File valid = root.resolve("valid.txt").toFile();
            String line = "v9.9.9|Security update|https://example.invalid/launcher.zip|" + VALID_SHA256;
            Files.write(valid.toPath(), line.getBytes(StandardCharsets.UTF_8));
            Object update = invoke("downloadLauncherUpdateInfo", new Class<?>[]{String.class}, valid.toURI().toString());
            Field version = update.getClass().getDeclaredField("version");
            Field hash = update.getClass().getDeclaredField("sha256");
            version.setAccessible(true);
            hash.setAccessible(true);
            assertTrue("v9.9.9".equals(version.get(update)), "update manifest version was not parsed");
            assertTrue(VALID_SHA256.equals(hash.get(update)), "update manifest SHA-256 was not parsed");

            File invalid = root.resolve("invalid.txt").toFile();
            Files.write(invalid.toPath(),
                    "v9.9.9|Missing hash|https://example.invalid/launcher.zip".getBytes(StandardCharsets.UTF_8));
            expectIOException("downloadLauncherUpdateInfo", new Class<?>[]{String.class}, invalid.toURI().toString());
        } finally {
            deleteRecursively(root);
        }
    }

    private static void validatesLauncherUpdateLayout() throws Exception {
        Path root = Files.createTempDirectory("tinycraft-update-layout-");
        try {
            write(root.resolve("TinyCraftLauncher.exe"), "exe");
            write(root.resolve("app/TinyCraftLauncher.jar"), "jar");
            write(root.resolve("runtime/bin/java.exe"), "java");
            Object valid = invoke("isValidLauncherApplication", new Class<?>[]{File.class}, root.toFile());
            assertTrue((Boolean) valid, "complete launcher update was rejected");
            Files.delete(root.resolve("runtime/bin/java.exe"));
            Object invalid = invoke("isValidLauncherApplication", new Class<?>[]{File.class}, root.toFile());
            assertTrue(!(Boolean) invalid, "incomplete launcher update was accepted");

            File script = (File) invoke("writeLauncherUpdaterScript", new Class<?>[]{File.class}, root.toFile());
            String scriptText = new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8);
            assertTrue(scriptText.contains(".previous-update"), "updater script has no rollback directory");
            assertTrue(scriptText.contains("Move-Item -LiteralPath $backup"), "updater script has no rollback step");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void rejectsUnsafeVersionIds() throws Exception {
        Class<?> versionClass = Class.forName("Launcher$GameVersion");
        Constructor<?> constructor = versionClass.getDeclaredConstructor(String.class, String.class, String.class,
                Integer.TYPE, String.class, String.class, String.class);
        constructor.setAccessible(true);
        expectInvalidVersionId(constructor, "..");
        expectInvalidVersionId(constructor, ".");
        expectInvalidVersionId(constructor, "../escape");
        expectInvalidVersionId(constructor, "CON");

        Object safeVersion = constructor.newInstance("v0.2.1", "v0.2.1", "release", 8,
                "TinyCraft", VALID_SHA256, "https://example.invalid/game.zip");
        Method directoryMethod = Launcher.class.getDeclaredMethod("getVersionDirectory", versionClass);
        directoryMethod.setAccessible(true);
        java.io.File directory = (java.io.File) directoryMethod.invoke(null, safeVersion);
        java.io.File versionsRoot = directory.getParentFile();
        assertTrue("versions".equals(versionsRoot.getName()), "version escaped versions directory: " + directory);
    }

    private static void expectInvalidVersionId(Constructor<?> constructor, String id) throws Exception {
        try {
            constructor.newInstance(id, "malicious", "release", 8,
                    "TinyCraft", VALID_SHA256, "https://example.invalid/game.zip");
            throw new AssertionError("unsafe version id was accepted: " + id);
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException,
                    "unexpected error for unsafe version id " + id + ": " + expected.getCause());
        }
    }

    private static void verifiesBuiltInReleaseMetadata() throws Exception {
        Field versionsField = Launcher.class.getDeclaredField("LOCAL_VERSIONS");
        versionsField.setAccessible(true);
        Object versions = versionsField.get(null);
        int count = Array.getLength(versions);
        assertTrue(count > 0, "built-in version list is empty");

        for (int index = 0; index < count; index++) {
            Object version = Array.get(versions, index);
            Field nameField = version.getClass().getDeclaredField("name");
            Field hashField = version.getClass().getDeclaredField("sha256");
            Field urlField = version.getClass().getDeclaredField("downloadUrl");
            nameField.setAccessible(true);
            hashField.setAccessible(true);
            urlField.setAccessible(true);

            String name = (String) nameField.get(version);
            String hash = (String) hashField.get(version);
            String url = (String) urlField.get(version);
            assertTrue(hash.matches("[0-9a-f]{64}"), "missing SHA-256 for " + name);
            invoke("validateGameDownload", new Class<?>[]{String.class, String.class}, url, hash);
        }
    }

    private static Object invoke(String name, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = Launcher.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ex;
        }
    }

    private static void expectIOException(String name, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        try {
            invoke(name, parameterTypes, arguments);
            throw new AssertionError(name + " accepted unsafe input");
        } catch (IOException expected) {
            // Expected security rejection.
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void write(Path path, String text) throws Exception {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            Path[] ordered = paths.sorted((left, right) -> right.compareTo(left)).toArray(Path[]::new);
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }
}

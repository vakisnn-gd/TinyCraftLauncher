import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class LauncherInstallTest {
    public static void main(String[] args) throws Exception {
        File launcherData = new File(System.getenv("LOCALAPPDATA"), "TinyCraftLauncher");
        File testRoot = new File(launcherData, "install-test-" + UUID.randomUUID());
        if (!testRoot.mkdirs()) {
            throw new AssertionError("Could not create test directory: " + testRoot);
        }

        try {
            preservesUserDataDuringReplacement(testRoot);
            restoresOldVersionWhenReplacementFails(testRoot);
            rejectsIncompleteStagingBeforeReplacement(testRoot);
            acceptsLegacyVersionWithoutModernAssets(testRoot);
            recoversInterruptedReplacement(testRoot);
            restoresPreviousVersionWhenInterruptedReplacementIsInvalid(testRoot);
            System.out.println("LauncherInstallTest: OK");
        } finally {
            deleteRecursively(testRoot.toPath());
        }
    }

    private static void preservesUserDataDuringReplacement(File root) throws Exception {
        File version = new File(root, "success");
        File staging = new File(root, "success.install");
        File previous = new File(root, "success.previous");
        createValidVersion(version, "old");
        write(new File(version, "saves/World 1/level.json"), "world-data");
        write(new File(version, "profile.properties"), "uuid=old-player");
        write(new File(version, "options.txt"), "renderDistance=8");
        write(new File(version, "old-program-file.txt"), "obsolete");

        createValidVersion(staging, "new");
        write(new File(staging, "profile.properties"), "uuid=release-default");

        invoke("replaceInstalledVersion", version, staging, previous);

        assertJarMarker(new File(version, "TinyCraft.jar"), "new");
        assertText(new File(version, "saves/World 1/level.json"), "world-data");
        assertText(new File(version, "profile.properties"), "uuid=old-player");
        assertText(new File(version, "options.txt"), "renderDistance=8");
        assertFalse(new File(version, "old-program-file.txt").exists(), "obsolete program file was retained");
        assertFalse(previous.exists(), "temporary previous version was not removed");
    }

    private static void restoresOldVersionWhenReplacementFails(File root) throws Exception {
        File version = new File(root, "rollback");
        File missingStaging = new File(root, "rollback.install");
        File previous = new File(root, "rollback.previous");
        createValidVersion(version, "old-after-failure");
        write(new File(version, "saves/World 1/level.json"), "safe-world");

        boolean failed = false;
        try {
            invoke("replaceInstalledVersion", version, missingStaging, previous);
        } catch (Exception expected) {
            failed = true;
        }

        assertTrue(failed, "replacement with missing staging directory did not fail");
        assertJarMarker(new File(version, "TinyCraft.jar"), "old-after-failure");
        assertText(new File(version, "saves/World 1/level.json"), "safe-world");
        assertFalse(previous.exists(), "old version was not restored after failure");
    }

    private static void rejectsIncompleteStagingBeforeReplacement(File root) throws Exception {
        File version = new File(root, "incomplete");
        File staging = new File(root, "incomplete.install");
        File previous = new File(root, "incomplete.previous");
        createValidVersion(version, "old-complete");
        createValidVersion(staging, "new-incomplete");
        Files.delete(new File(staging, "lib/lwjgl-opengl-3.3.3.jar").toPath());

        boolean failed = false;
        try {
            invoke("replaceInstalledVersion", version, staging, previous);
        } catch (Exception expected) {
            failed = true;
        }

        assertTrue(failed, "incomplete staging directory was installed");
        assertJarMarker(new File(version, "TinyCraft.jar"), "old-complete");
        assertFalse(previous.exists(), "complete version was moved before validation");
    }

    private static void acceptsLegacyVersionWithoutModernAssets(File root) throws Exception {
        File version = new File(root, "legacy-assets");
        createValidVersion(version, "legacy");
        Files.delete(new File(version, "run-game.bat").toPath());
        Files.delete(new File(version, "ChunkShader.vsh").toPath());
        Files.delete(new File(version, "ChunkShader.fsh").toPath());
        Files.delete(new File(version, "terrain.png").toPath());

        Method method = Launcher.class.getDeclaredMethod("isInstalledVersionValid", File.class);
        method.setAccessible(true);
        assertTrue((Boolean) method.invoke(null, version),
                "legacy version without modern external assets was rejected");
    }

    private static void recoversInterruptedReplacement(File root) throws Exception {
        File version = new File(root, "recovery");
        File previous = new File(root, "recovery.previous");
        createValidVersion(version, "new-after-crash");
        createValidVersion(previous, "old-before-crash");
        write(new File(previous, "saves/World 1/level.json"), "recovered-world");
        write(new File(previous, "profile.properties"), "uuid=recovered-player");

        invoke("recoverInterruptedInstall", version, previous);

        assertJarMarker(new File(version, "TinyCraft.jar"), "new-after-crash");
        assertText(new File(version, "saves/World 1/level.json"), "recovered-world");
        assertText(new File(version, "profile.properties"), "uuid=recovered-player");
        assertFalse(previous.exists(), "recovered previous directory was not removed");
    }

    private static void restoresPreviousVersionWhenInterruptedReplacementIsInvalid(File root) throws Exception {
        File version = new File(root, "invalid-recovery");
        File previous = new File(root, "invalid-recovery.previous");
        write(new File(version, "partial-download.txt"), "broken");
        createValidVersion(previous, "old-restored-after-crash");
        write(new File(previous, "saves/World 1/level.json"), "restored-world");

        invoke("recoverInterruptedInstall", version, previous);

        assertJarMarker(new File(version, "TinyCraft.jar"), "old-restored-after-crash");
        assertText(new File(version, "saves/World 1/level.json"), "restored-world");
        assertFalse(new File(version, "partial-download.txt").exists(), "invalid replacement was retained");
        assertFalse(previous.exists(), "previous version was not moved back into place");
    }

    private static void createValidVersion(File directory, String marker) throws Exception {
        createGameJar(new File(directory, "TinyCraft.jar"), marker);
        write(new File(directory, "run-game.bat"), "@echo off");
        write(new File(directory, "ChunkShader.vsh"), "shader");
        write(new File(directory, "ChunkShader.fsh"), "shader");
        write(new File(directory, "terrain.png"), "png");
        write(new File(directory, "lib/lwjgl-3.3.3.jar"), "library");
        write(new File(directory, "lib/lwjgl-glfw-3.3.3.jar"), "library");
        write(new File(directory, "lib/lwjgl-opengl-3.3.3.jar"), "library");
        write(new File(directory, "lib/lwjgl-stb-3.3.3.jar"), "library");
        write(new File(directory, "lib/lwjgl-3.3.3-natives-windows.jar"), "native");
        write(new File(directory, "lib/lwjgl-glfw-3.3.3-natives-windows.jar"), "native");
        write(new File(directory, "lib/lwjgl-opengl-3.3.3-natives-windows.jar"), "native");
        write(new File(directory, "lib/lwjgl-stb-3.3.3-natives-windows.jar"), "native");
    }

    private static void createGameJar(File file, String marker) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new AssertionError("Could not create directory: " + parent);
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "TinyCraft");
        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(file), manifest)) {
            jar.putNextEntry(new JarEntry("TinyCraft.class"));
            jar.write(new byte[]{0});
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("marker.txt"));
            jar.write(marker.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static void invoke(String name, File version, File other) throws Exception {
        Method method = Launcher.class.getDeclaredMethod(name, File.class, File.class);
        method.setAccessible(true);
        invokeMethod(method, version, other);
    }

    private static void invoke(String name, File version, File staging, File previous) throws Exception {
        Method method = Launcher.class.getDeclaredMethod(name, File.class, File.class, File.class);
        method.setAccessible(true);
        invokeMethod(method, version, staging, previous);
    }

    private static void invokeMethod(Method method, Object... arguments) throws Exception {
        try {
            method.invoke(null, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw exception;
        }
    }

    private static void write(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new AssertionError("Could not create directory: " + parent);
        }
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertText(File file, String expected) throws Exception {
        String actual = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected '" + expected + "' in " + file + ", got '" + actual + "'");
        }
    }

    private static void assertJarMarker(File file, String expected) throws Exception {
        try (JarFile jar = new JarFile(file)) {
            JarEntry entry = jar.getJarEntry("marker.txt");
            if (entry == null) {
                throw new AssertionError("Missing marker.txt in " + file);
            }
            try (InputStream input = jar.getInputStream(entry);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[256];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                String actual = new String(output.toByteArray(), StandardCharsets.UTF_8);
                if (!expected.equals(actual)) {
                    throw new AssertionError("Expected '" + expected + "' in " + file + ", got '" + actual + "'");
                }
            }
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
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

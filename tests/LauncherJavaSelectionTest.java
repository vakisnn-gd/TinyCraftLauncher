import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LauncherJavaSelectionTest {
    public static void main(String[] args) throws Exception {
        Path versionDirectory = Files.createTempDirectory("tinycraft-java-selection-");
        try {
            selectsSupportedJava(versionDirectory.toFile());
            supportsJavaEightThroughTwentySix();
            rejectsUnsupportedGameRequirement(versionDirectory.toFile());
            System.out.println("LauncherJavaSelectionTest: OK");
        } finally {
            Files.deleteIfExists(versionDirectory);
        }
    }

    private static void selectsSupportedJava(File versionDirectory) throws Exception {
        Object runtime = invokeFindJavaRuntime(versionDirectory, 8);
        Field executableField = runtime.getClass().getDeclaredField("executable");
        Field releaseField = runtime.getClass().getDeclaredField("release");
        executableField.setAccessible(true);
        releaseField.setAccessible(true);

        File executable = (File) executableField.get(runtime);
        int release = releaseField.getInt(runtime);
        assertTrue(executable.isFile(), "selected Java executable does not exist: " + executable);
        assertTrue(release >= 8 && release <= 26, "selected unsupported Java " + release);
        System.out.println("Selected Java " + release + ": " + executable.getCanonicalPath());
    }

    private static void supportsJavaEightThroughTwentySix() throws Exception {
        Method method = Launcher.class.getDeclaredMethod("isSupportedJavaRelease", Integer.TYPE, Integer.TYPE);
        method.setAccessible(true);
        for (int release = 8; release <= 26; release++) {
            assertTrue((Boolean) method.invoke(null, release, 8), "Java " + release + " was rejected");
        }
        assertFalse((Boolean) method.invoke(null, 7, 8), "Java 7 was accepted");
        assertFalse((Boolean) method.invoke(null, 27, 8), "Java 27 was accepted");
        assertFalse((Boolean) method.invoke(null, 16, 17), "Java 16 ignored the version requirement");
    }

    private static void rejectsUnsupportedGameRequirement(File versionDirectory) throws Exception {
        try {
            invokeFindJavaRuntime(versionDirectory, 27);
            throw new AssertionError("Java 27 requirement was not rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("27"), "error does not mention required Java 27");
        }
    }

    private static Object invokeFindJavaRuntime(File versionDirectory, int minimumRelease) throws Exception {
        Method method = Launcher.class.getDeclaredMethod("findJavaRuntime", File.class, Integer.TYPE);
        method.setAccessible(true);
        try {
            return method.invoke(null, versionDirectory, minimumRelease);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ex;
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }
}

import java.util.concurrent.Callable;

/**
 * Loads each native dependency bundled in beatoraja.jar and prints OK/FAIL.
 * Run with `mise run probe` after `mise run build` to track arm64 porting progress.
 */
public class NativeProbe {
    static void probe(String name, Callable<Object> c) {
        try {
            Object r = c.call();
            System.out.println("OK   " + name + (r != null ? " -> " + r : ""));
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) root = root.getCause();
            String msg = String.valueOf(root.getMessage()).replaceAll("\\s+", " ");
            System.out.println("FAIL " + name + " : " + root.getClass().getSimpleName() + " " + msg.substring(0, Math.min(msg.length(), 220)));
        }
    }

    public static void main(String[] a) {
        System.out.println("os.arch=" + System.getProperty("os.arch"));
        probe("gdx core natives", () -> { com.badlogic.gdx.utils.GdxNativesLoader.load(); return null; });
        probe("lwjgl2 (gdx-backend-lwjgl)", () -> { com.badlogic.gdx.backends.lwjgl.LwjglNativesLoader.load(); return org.lwjgl.Sys.getVersion(); });
        probe("gdx-freetype", () -> { new com.badlogic.gdx.utils.SharedLibraryLoader().load("gdx-freetype"); return null; });
        probe("gdx-controllers-desktop", () -> { new com.badlogic.gdx.utils.SharedLibraryLoader().load("gdx-controllers-desktop"); return null; });
        probe("sqlite-jdbc", () -> java.sql.DriverManager.getConnection("jdbc:sqlite::memory:").getMetaData().getDriverVersion());
        probe("javacv ffmpeg", () -> { org.bytedeco.javacpp.Loader.load(org.bytedeco.javacpp.avutil.class); return null; });
        probe("jportaudio", () -> { System.loadLibrary("jportaudio"); return null; });
        probe("JNA (discord-rpc bundle)", () -> com.sun.jna.Native.POINTER_SIZE);
        probe("javafx toolkit class", () -> Class.forName("javafx.application.Platform").getModule());
    }
}

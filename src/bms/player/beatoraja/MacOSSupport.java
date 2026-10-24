package bms.player.beatoraja;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration.GLEmulation;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

/**
 * macOS固有の起動設定
 *
 * macOS provides only an OpenGL 3.2 core profile context. The libGDX default shaders and
 * src/glsl/* are written for GLSL 1.10/1.20, so on macOS they are converted to GLSL 150 core
 * by prepending version and keyword macros.
 */
public final class MacOSSupport {

	private MacOSSupport() {
	}

	public static boolean isMacOS() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	public static void configure(Lwjgl3ApplicationConfiguration config) {
		if (!isMacOS()) {
			return;
		}
		config.setOpenGLEmulation(GLEmulation.GL30, 3, 2);
		ShaderProgram.prependVertexCode = "#version 150\n#define attribute in\n#define varying out\n";
		ShaderProgram.prependFragmentCode = "#version 150\n#define varying in\n#define texture2D texture\n#define gl_FragColor fragColor\nout vec4 fragColor;\n";
	}
}

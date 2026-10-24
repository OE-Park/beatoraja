# macOS arm64 Native Run Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `build/beatoraja.jar` built by Ant starts and renders on an arm64 (Apple Silicon) JVM, while still bundling Windows/Linux x86_64 natives.

**Architecture:** Keep `build.xml` + `lib/*.jar`. Swap every x86_64-only native jar for a version that ships macOS arm64 natives (sqlite-jdbc, JavaCV/FFmpeg, JNA, libGDX 1.14.2 + LWJGL 3.3.3 backend, gdx-controllers 2.x), and port the source to the LWJGL3 backend API. macOS-specific behaviour (GL 3.2 core emulation, GLFW on the JavaFX/AppKit main thread) lives in one small helper class.

**Tech Stack:** Java 17 (Liberica JDK + JavaFX, via `mise`), Apache Ant 1.10.18, libGDX 1.14.2, LWJGL 3.3.3, gdx-controllers 2.2.4 (Jamepad/SDL), sqlite-jdbc 3.53.4.0, JavaCV/JavaCPP 1.5.9 + FFmpeg 6.0, JNA 5.13.0.

**Spec:** `docs/macos-arm64-porting.md` (dependency table, pitfalls, decisions).

## Global Constraints

- Build system stays Ant. Every jar added to `lib/` must also be listed in `build.xml` `create_run_jar`, and every removed jar removed from it.
- Jars are fetched from Maven Central by `tools/macos-arm64/fetch-libs.sh` from coordinates in `tools/macos-arm64/libs.txt`, SHA-1 verified. No hand-downloaded jars.
- Bundled native platforms: `macos-arm64`, `macos-x86_64`, `windows-x86_64`, `linux-x86_64`. No 32-bit, no linux-arm.
- Toolchain: run everything through `mise exec --` / `mise run` from the repo root (`mise.toml`).
- Verification per task: `mise run build` → `BUILD SUCCESSFUL`, then `mise run probe`. The repo has no unit tests; the probe (`tools/macos-arm64/NativeProbe.java`) is the regression check.
- Non-macOS behaviour must not change except where the LWJGL3 API forces it (noted per task).
- Commits on branch `macos-arm64`, message style of the repo (`Component : summary`), ending with the `Co-Authored-By` line. Do not push.
- Files `/beatoraja_arm64_porting.iml`, `/skills/` are local-only (in `.git/info/exclude`). Never add them.

## Review Focus

1. Launch via JavaFX launcher "Start" on macOS: the game window must open (GLFW on AppKit main thread), not crash with "must be called from the main thread". → Task 5 Step 7.
2. Direct launch `java -jar build/beatoraja.jar -s` on macOS with an existing `config_sys.json`: must open the game window, not fail GLFW thread checks. → Task 5 Step 6.
3. Custom shaders in `src/glsl/*` under GL 3.2 core on macOS: must compile (log has no `ShaderProgram` compile error). → Task 5 Step 6 log grep.
4. Audio driver `PortAudio` selected: libGDX audio must be disabled, PortAudio still loads. → Task 5 Step 3 (`disableAudio(true)`), probe `jportaudio` stays OK.
5. Discord RPC enabled on arm64 (x86_64-only dylib): game must keep running with a logged warning. → Task 4 Step 3.

---

### Task 1: Reproducible lib fetcher + commit tooling

**Files:**
- Create: `tools/macos-arm64/fetch-libs.sh`, `tools/macos-arm64/libs.txt`
- Add to git (already exist, untracked): `mise.toml`, `docs/macos-arm64-porting.md`, `tools/macos-arm64/NativeProbe.java`, this plan

**Interfaces:**
- Produces: `tools/macos-arm64/fetch-libs.sh [DEST_DIR]` (default `lib`). Reads `libs.txt` lines `group:artifact:version[:classifier]` (`#` comments, blank lines ignored). Downloads `artifact-version[-classifier].jar` from `https://repo1.maven.org/maven2/`, verifies against the `.jar.sha1` file, skips files already present with matching SHA-1, exits non-zero on any failure. Later tasks append coordinates to `libs.txt` and rerun it.

- [ ] **Step 1:** Write `fetch-libs.sh` (bash, `set -euo pipefail`, `curl -fsSL`, `shasum -a 1`). Start `libs.txt` with a header comment only.
- [ ] **Step 2: Verify:** add a temporary line `org.jcraft:jorbis:0.0.17`, run `tools/macos-arm64/fetch-libs.sh /tmp/fetch-test` → `jorbis-0.0.17.jar` exists; rerun → no download; corrupt the file (`echo x >> ...`) and rerun → re-downloads or fails loudly (either is fine, silently keeping a bad file is not). Remove the temporary line.
- [ ] **Step 3: Commit** `tools/macos-arm64/*`, `mise.toml`, `docs/macos-arm64-porting.md`, `docs/superpowers/plans/2026-10-24-macos-arm64-native.md` — message `macOS arm64 : add mise toolchain, native probe and lib fetcher`.

### Task 2: sqlite-jdbc 3.53.4.0

**Files:**
- Modify: `tools/macos-arm64/libs.txt` (+ `org.xerial:sqlite-jdbc:3.53.4.0`)
- Delete: `lib/sqlite-jdbc.jar`; Add: `lib/sqlite-jdbc-3.53.4.0.jar`
- Modify: `build.xml` zipfileset `./lib/sqlite-jdbc.jar` → `./lib/sqlite-jdbc-3.53.4.0.jar`

- [ ] **Step 1:** `mise run probe` → confirm `FAIL sqlite-jdbc` (baseline).
- [ ] **Step 2:** Update `libs.txt`, run `tools/macos-arm64/fetch-libs.sh`, `git rm lib/sqlite-jdbc.jar`, update `build.xml`.
- [ ] **Step 3: Verify:** `mise run probe` → `OK   sqlite-jdbc -> 3.53.4.0` (or the driver version string it reports). Build has no new errors.
- [ ] **Step 4: Commit** `lib : update sqlite-jdbc to 3.53.4.0 (macOS arm64 native)`.

### Task 3: JavaCV / JavaCPP 1.5.9 + FFmpeg 6.0

**Files:**
- Modify: `tools/macos-arm64/libs.txt` (+ `org.bytedeco:javacv:1.5.9`, `org.bytedeco:javacpp:1.5.9`, `org.bytedeco:javacpp:1.5.9:<p>`, `org.bytedeco:ffmpeg:6.0-1.5.9`, `org.bytedeco:ffmpeg:6.0-1.5.9:<p>` for `p` in `macosx-arm64 macosx-x86_64 windows-x86_64 linux-x86_64`)
- Delete from `lib/`: `javacv.jar javacv-platform.jar javacpp.jar ffmpeg.jar ffmpeg-platform.jar ffmpeg-linux-x86.jar ffmpeg-linux-x86_64.jar ffmpeg-macosx-x86_64.jar ffmpeg-windows-x86.jar ffmpeg-windows-x86_64.jar`
- Modify: `build.xml` (replace the javacpp/ffmpeg/javacv zipfilesets with the 12 new jars)
- Modify if compile requires: `src/bms/player/beatoraja/video/FFmpegProcessor.java`, `src/bms/player/beatoraja/PixmapResourcePool.java`
- Modify: `tools/macos-arm64/NativeProbe.java` — probe `org.bytedeco.ffmpeg.global.avutil` instead of `org.bytedeco.javacpp.avutil`

- [ ] **Step 1:** Swap jars (fetch script, `git rm`), update `build.xml`, update the probe line.
- [ ] **Step 2:** `mise run build`; fix compile errors in the two source files with the JavaCV 1.5.9 equivalents (same `FFmpegFrameGrabber`/`Frame`/`Java2DFrameConverter` classes; pixel format constants move to `org.bytedeco.ffmpeg.global.avutil`).
- [ ] **Step 3: Verify:** `mise run probe` → `OK   javacv ffmpeg`. `unzip -l build/beatoraja.jar | grep -c 'org/bytedeco/ffmpeg/macosx-arm64/'` > 0.
- [ ] **Step 4: Commit** `lib : update JavaCV to 1.5.9 / FFmpeg 6.0 (macOS arm64 native)`.

### Task 4: JNA 5.13.0 + Discord RPC failure guard

**Files:**
- Modify: `tools/macos-arm64/libs.txt` (+ `net.java.dev.jna:jna:5.13.0`); Add: `lib/jna-5.13.0.jar`
- Modify: `build.xml` — add `<zipfileset excludes="META-INF/*.SF" src="./lib/jna-5.13.0.jar" />` **before** the `java-discord-rpc-2.0.1-all.jar` entry (jar task uses `duplicate="preserve"`, first entry wins, so JNA 5 classes and its `com/sun/jna/darwin-aarch64` native win over the bundled JNA 4)
- Modify: `src/bms/player/beatoraja/MainController.java:203-205` (DiscordListener creation)

- [ ] **Step 1:** Add JNA, update `build.xml`. Verify: `mise run probe` → `OK   JNA (discord-rpc bundle) -> 8`.
- [ ] **Step 2:** In `MainController`, wrap `stateListener.add(new DiscordListener())` in `try { … } catch (Throwable e)` that logs `Logger.getGlobal().warning("Discord RPC unavailable: " + e)` and continues. (`libdiscord-rpc.dylib` is x86_64 only, so `UnsatisfiedLinkError` is expected on arm64.)
- [ ] **Step 3: Verify:** build succeeds; `grep -n 'Discord RPC unavailable' src/bms/player/beatoraja/MainController.java` matches. Runtime check of this path is part of Task 5 Step 6 (run once with `"useDiscordRPC": true` in `config_sys.json`, game reaches select screen, log contains the warning).
- [ ] **Step 4: Commit** `lib : add JNA 5.13.0 for arm64, guard Discord RPC init`.

### Task 5: libGDX 1.14.2 + LWJGL3 backend (graphics, audio, launch)

Controllers stay on the old OIS jars in this task (they still compile); Task 6 replaces them.

**Files:**
- Modify: `tools/macos-arm64/libs.txt` (+ `com.badlogicgames.gdx:gdx:1.14.2`, `gdx:gdx-platform:1.14.2:natives-desktop`, `gdx-backend-lwjgl3:1.14.2`, `gdx-freetype:1.14.2`, `gdx-freetype-platform:1.14.2:natives-desktop`, `gdx-jnigen-loader:2.5.2`, `com.badlogicgames.jlayer:jlayer:1.0.1-gdx`, `org.jcraft:jorbis:0.0.17`, and `org.lwjgl:<m>:3.3.3` plus `:natives-macos`, `:natives-macos-arm64`, `:natives-windows`, `:natives-linux` for `m` in `lwjgl lwjgl-glfw lwjgl-opengl lwjgl-openal lwjgl-jemalloc lwjgl-stb`)
- Delete from `lib/`: `gdx.jar gdx-natives.jar gdx-backend-lwjgl.jar gdx-backend-lwjgl-natives.jar gdx-freetype.jar gdx-freetype-natives.jar`
- Modify: `build.xml` — explicit zipfilesets for the gdx/jlayer/jorbis jars; `<zipgroupfileset dir="lib" includes="lwjgl-*3.3.3*.jar" />` for LWJGL
- Create: `src/bms/player/beatoraja/MacOSSupport.java`
- Modify: `src/bms/player/beatoraja/MainLoader.java` (main(), play(), display-mode getters), `MainController.java` (Mouse, StringBuilder import), `PlayDataAccessor.java`, `select/BarManager.java` (StringBuilder import), `audio/PCM.java` (OggInputStream import), `input/KeyBoardInputProcessor.java`, `skin/SkinTextInput.java`, `skin/SkinTextFont.java`, `skin/SkinTextBitmap.java`, `launcher/PlayConfigurationView.java` (start)
- Modify: `tools/macos-arm64/NativeProbe.java`

**Interfaces:**
- Produces `final class MacOSSupport` (package `bms.player.beatoraja`):
  - `static boolean isMacOS()` — `os.name` contains `mac` (case-insensitive)
  - `static void configure(Lwjgl3ApplicationConfiguration config)` — no-op unless macOS; on macOS `config.setOpenGLEmulation(GLEmulation.GL30, 3, 2)` and sets `ShaderProgram.prependVertexCode = "#version 150\n#define attribute in\n#define varying out\n"` and `ShaderProgram.prependFragmentCode = "#version 150\n#define varying in\n#define texture2D texture\n#define gl_FragColor fragColor\nout vec4 fragColor;\n"` (converts libGDX default shaders and `src/glsl/*` to GLSL 150 core)
- `MainLoader.play(Path, BMSPlayerMode, boolean forceExit, Config, PlayerConfig, boolean)` keeps its signature (callers unchanged).

- [ ] **Step 1:** Update `NativeProbe`: drop the `lwjgl2` probe; add `lwjgl3 core` (`org.lwjgl.Version.getVersion()` after `org.lwjgl.system.Library.initialize()`), `glfw` (`org.lwjgl.glfw.GLFW.glfwGetVersionString()`), `openal` (`org.lwjgl.openal.ALC.create(); return null`). Change gdx-freetype/gdx core probes to `GdxNativesLoader.load()` and `new SharedLibraryLoader().load("gdx-freetype")` (unchanged names). Run `mise run probe` → new probes FAIL (classes missing) — baseline.
- [ ] **Step 2:** Swap jars (fetch, `git rm`, `build.xml`).
- [ ] **Step 3:** Port `MainLoader.play()` to `Lwjgl3Application`/`Lwjgl3ApplicationConfiguration`:
  - windowed `setWindowedMode(w, h)`; FULLSCREEN → pick the `getDisplayModes()` entry matching w×h with highest refresh/bpp (as in upstream PR #730), fall back to windowed; BORDERLESS → `setDecorated(false)` + windowed
  - `useVsync`, `setForegroundFPS` and `setIdleFPS` = `config.getMaxFramePerSecond()`, `setTitle(MainController.getVersion())`
  - `setAudioConfig(simultaneousSources, bufferSize, 9)` with the same bufferSize expression as today; `PortAudio` driver → `disableAudio(true)`
  - `MacOSSupport.configure(cfg)`
  - keep the anonymous `ApplicationListener` delegate and the `postRunnable` override unchanged (still OIS, Task 6 changes it)
  - `new Lwjgl3Application(...)` blocks until the game exits; afterwards `if (forceExit) System.exit(0);`
  - `getAvailableDisplayMode()` → `Lwjgl3ApplicationConfiguration.getDisplayModes()`; `getDesktopDisplayMode()` → `Lwjgl3ApplicationConfiguration.getDisplayMode()`
- [ ] **Step 4:** macOS threading: in `MainLoader.main()`, the direct-play branch calls `play(...)` via `javafx.application.Platform.startup(() -> play(...))` when `MacOSSupport.isMacOS()` (the JavaFX application thread is the AppKit main thread on macOS, which GLFW requires); otherwise call directly as today. In `PlayConfigurationView` start handler, hide the launcher window (`controlPanel.getScene().getWindow().hide()`) right before `MainLoader.play(...)`, since the game loop now blocks the JavaFX thread.
- [ ] **Step 5:** Fix remaining compile errors:
  - `MainController`: `Mouse.setGrabbed(cond && Mouse.isInsideWindow())` → `Gdx.input.setCursorCatched(cond)`; remove `org.lwjgl.input.Mouse`
  - `com.badlogic.gdx.utils.StringBuilder` (removed) → `java.lang.StringBuilder` in the 3 files; if a gdx-only method is used, use the `java.lang` equivalent
  - `PCM`: `com.badlogic.gdx.backends.lwjgl3.audio.OggInputStream`
  - `KeyBoardInputProcessor`: add `public boolean touchCancelled(int screenX, int screenY, int pointer, int button) { return false; }`
  - `SkinTextInput`: `getOnscreenKeyboard().show(b)` → `Gdx.input.setOnscreenKeyboardVisible(b)`
  - `SkinTextFont:285`, `SkinTextBitmap:275`: `run.color.set(color)` → `GlyphLayout.colors` is an IntArray of (glyphIndex, ABGR8888) pairs; set every odd-index entry to `color.toIntBits()`
  - `SkinTextFont:445,504`: `PixmapPacker.pack` now returns `PixmapPacker.PixmapPackerRectangle`; use that type
  Run `mise run build` until `BUILD SUCCESSFUL`.
- [ ] **Step 6: Verify (direct launch):** `mise run probe` → gdx core, gdx-freetype, lwjgl3 core, glfw, openal, sqlite, ffmpeg, jportaudio, JNA, javafx all `OK` (gdx-controllers-desktop may still FAIL). Create a default config once: `java -cp build/beatoraja.jar` + a throwaway class calling `Config.write(Config.read())` (run from repo root; `config_sys.json`, `player/`, `songdata.db` are gitignored). Run `java -Xms1g -Xmx4g -Djava.library.path=./natives -jar build/beatoraja.jar -s` in background for ~20 s, take `screencapture -x /tmp/beatoraja-smoke.png`, then quit the process. Pass = window visible in the screenshot (select screen or skin), process did not exit with a stack trace, `beatoraja_log.xml`/stdout has no `UnsatisfiedLinkError`, `GLFW`, `ShaderProgram` / `compile` errors. Repeat once with `"useDiscordRPC": true` → log contains `Discord RPC unavailable`, game still running. Record what was seen.
- [ ] **Step 7: Verify (launcher):** run `mise run run` (no args) → launcher window appears; this step's "Start" click is manual — report it as not executed unless done by a human.
- [ ] **Step 8:** Update the status table in `docs/macos-arm64-porting.md`. **Commit** `libGDX : migrate to 1.14.2 / LWJGL 3.3.3 backend (macOS arm64)`.

### Task 6: gdx-controllers 2.2.4 (Jamepad/SDL)

**Files:**
- Modify: `tools/macos-arm64/libs.txt` (+ `com.badlogicgames.gdx-controllers:gdx-controllers-core:2.2.4`, `gdx-controllers-desktop:2.2.4`, `com.badlogicgames.jamepad:jamepad:2.26.5.0`)
- Delete from `lib/`: `gdx-controllers.jar gdx-controllers-desktop.jar gdx-controllers-desktop-natives.jar`; update `build.xml`
- Modify: `src/bms/player/beatoraja/input/BMControllerInputProcessor.java:130-160`
- Modify: `src/bms/player/beatoraja/MainLoader.java` (postRunnable interception)
- Modify: `src/bms/player/beatoraja/input/BMSPlayerInputProcessor.java` (mapping file, near the `Controllers.getControllers()` loop at line 40)
- Modify: `tools/macos-arm64/NativeProbe.java`

**Interfaces:**
- Produces in `BMControllerInputProcessor`: `private boolean isHatPressed(int hat, int direction)` where `direction` = `(button - BMKeys.HAT1_LEFT) % 4` (0 left, 1 up, 2 right, 3 down, same order as the current `PovDirection` branches). Implementer writes a stub returning `false` with `// TODO(user): map via controller.getMapping() dpad buttons`. The user implements the body.

- [ ] **Step 1:** Probe: replace `gdx-controllers-desktop` probe with `jamepad` (`new com.studiohartman.jamepad.ControllerManager().initSDLGamepad()`, then `quitSDLGamepad()`). Swap jars, update `build.xml`.
- [ ] **Step 2:** `BMControllerInputProcessor`: remove `PovDirection`; HAT branch calls `isHatPressed(...)`. Keep the `|= controller.getButton(button)` fallback line as is.
- [ ] **Step 3:** `MainLoader` postRunnable override: intercept when `stackTrace[2].getClassName()` starts with `com.badlogic.gdx.controllers.desktop.support.JamepadControllerMonitor` (was `...desktop.OisControllers`), and only when `!MacOSSupport.isMacOS()` — on macOS, SDL joystick calls stay on the thread that initialised SDL (render/main thread). Update the comment to name Jamepad.
- [ ] **Step 4:** `BMSPlayerInputProcessor`: before enumerating controllers, if `Paths.get("gamecontrollerdb.txt")` exists, call `JamepadControllerManager.addMappingsFromFile(path.toString())` inside try/catch logging a warning (SDL mapping for devices like PHOENIXWAN that SDL does not know). Call it after `Controllers.getControllers()` has initialised the manager once, then re-read `Controllers.getControllers()`.
- [ ] **Step 5: Verify:** `mise run build` OK; `mise run probe` → `OK   jamepad`; rerun Task 5 Step 6 smoke (keyboard) → same pass criteria. Controller test with real hardware: not executed (user, PHOENIXWAN later).
- [ ] **Step 6:** Update `docs/macos-arm64-porting.md` (controllers row, mapping file usage). **Commit** `gdx-controllers : migrate to 2.2.4 (Jamepad/SDL)`.

### Task 7: Dockerfile per-arch OpenJFX + local IDE library

**Files:**
- Modify: `Dockerfile`
- Modify (local, gitignored): `.idea/libraries/lib2.xml`

- [ ] **Step 1:** `Dockerfile`: add `ARG TARGETARCH`; choose `OPENJFX_ARCH=x64` for `amd64`, `aarch64` for `arm64`; download `openjfx-${OPENJFX_VERSION}_linux-${OPENJFX_ARCH}_bin-sdk.zip`. Everything else unchanged.
- [ ] **Step 2: Verify:** if Docker daemon is running: `docker build -t beatoraja-build .` then `docker run --rm -v "$PWD":/usr/src/app beatoraja-build` → `BUILD SUCCESSFUL`. If the daemon is not running, record "Docker build not executed".
- [ ] **Step 3:** Replace the explicit `<root>` list in `.idea/libraries/lib2.xml` with `<jarDirectory url="file://$PROJECT_DIR$/lib" recursive="false" />` (keeps IntelliJ in sync with lib swaps; not committed).
- [ ] **Step 4: Commit** `Dockerfile : select OpenJFX SDK by TARGETARCH`.

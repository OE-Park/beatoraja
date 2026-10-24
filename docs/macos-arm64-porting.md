# macOS arm64 (Apple Silicon) porting

Status of running beatoraja natively on an arm64 JVM. Upstream: `exch-bms2/beatoraja` `master`.

## Decisions (2026-10-24)

- Keep Ant (`build.xml` + `lib/*.jar`) and swap jars, to minimise merge conflicts with upstream.
- Remote `upstream` = `exch-bms2/beatoraja`. Sync with `git fetch upstream && git merge upstream/master`.
  The personal fork is added later as `origin`.
- Verify with keyboard first. Controller check (PHOENIXWAN IIDX) is a later manual step;
  in PR #730, PHOENIXWAN 2018-2021 needed an SDL mapping (`SDL_GAMECONTROLLERCONFIG` or a
  `gamecontrollerdb.txt` file in the working directory, which `BMSPlayerInputProcessor` loads via
  `JamepadControllerManager.addMappingsFromFile`).

## Toolchain

`mise.toml` at the repo root pins the toolchain. From the repo root:

```sh
mise install        # Liberica JDK 17 + JavaFX (macos-aarch64 "full"), Ant 1.10.18
mise run build      # ant create_run_jar -> build/beatoraja.jar
mise run probe      # load each bundled native library and print OK/FAIL
mise run run        # launch from the repo root
```

The Liberica `javafx` build ships `javafx.*` modules inside the JDK image, so
`MainLoader extends javafx.application.Application` starts with plain `java -jar`
and Ant needs no `-lib` (the Dockerfile uses a separate OpenJFX SDK instead).

## Native dependency status (arm64 JVM)

Rows marked "was" show upstream 83574048. Re-check with `mise run probe`.

| Library (lib/) | macOS natives shipped | arm64 load | Notes |
|---|---|---|---|
| gdx 1.14.2 (`gdx-platform` natives-desktop) | x86_64, arm64 | OK | was gdx 1.9.9: i386, x86_64, FAIL |
| gdx-backend-lwjgl3 1.14.2 + LWJGL 3.3.3 (lwjgl, glfw, opengl, openal, jemalloc, stb) | x86_64, arm64 | OK | was LWJGL 2 (`gdx-backend-lwjgl`): x86_64 only, FAIL. macOS uses GL 3.2 core + GLSL 150 prepend (`MacOSSupport`) |
| gdx-freetype 1.14.2 (`gdx-freetype-platform`) | x86_64, arm64 | OK | was i386, x86_64, FAIL |
| gdx-controllers 2.2.4 + jamepad 2.26.5.0 | x86_64, arm64 | OK | was `gdx-controllers-desktop` (OIS): i386, x86_64, FAIL. Loads optional `gamecontrollerdb.txt` from working directory; HAT read from the SDL d-pad; see Behaviour changes |
| sqlite-jdbc 3.53.4.0 | x86_64, aarch64 | OK | was 3.18: x86_64, FAIL |
| javacv/ffmpeg (JavaCV 1.5.9, FFmpeg 6.0) | x86_64, arm64 | OK | was JavaCPP 1.3.3: x86_64, FAIL |
| JNA 5.13.0 | x86_64, aarch64 | OK | added before java-discord-rpc. discord-rpc's own native is still x86_64, so Discord RPC init is caught and logged (`Discord RPC unavailable`) |
| jportaudio (`natives/libjportaudio.dylib`) | universal (x86_64, arm64) | OK | - |
| JavaFX (launcher) | JDK-provided | OK | - |

JNA is also used directly by `audio/PortAudioAsioDiagnostics.java` (Windows ASIO path).

## Running (end users)

`beatoraja-config.command` runs bare `java`, so an arm64 JDK 17 that bundles JavaFX
(e.g. Liberica Full / "javafx" build) must be first on `PATH`.

## Behaviour changes on Windows/Linux

These apply on every OS, not only macOS (libGDX 1.14.2 / LWJGL3 / gdx-controllers 2.x).

- Controllers (gdx-controllers 2.x / Jamepad): only the 21 SDL GameController buttons and 6 axes are exposed.
  Old OIS raw indices `BUTTON_1..28` now map to SDL buttons (A/B/X/...) or nothing, so existing bindings
  (default `BUTTON_2..10`) must be re-assigned. Axes 6-7 are unreachable. Devices without an SDL mapping are
  not listed; use `gamecontrollerdb.txt`. The file is loaded after the manager has already opened devices at
  startup, so it may only take effect after replugging the device (untested). `SDL_GAMECONTROLLERCONFIG`
  is the alternative.
- HAT bindings: gdx-controllers 2.x has no POV API, so `HAT 1 LEFT/TOP/RIGHT/BOTTOM` read the SDL d-pad
  buttons from `Controller.getMapping()`. Diagonals press two directions, as before. The same d-pad also
  reports as `BUTTON 12..15` (SDL button codes 11-14), so either binding works. Only one HAT exists.
- Controller refresh: on macOS the state is refreshed once per rendered frame (SDL updates stay on the render
  thread). On other OS the poll-thread interception still runs the Jamepad monitor at the poll rate.
- Numpad keys: LWJGL3 reports KP_ENTER/ADD/SUBTRACT/MULTIPLY/DIVIDE/DECIMAL as NUMPAD_* keys instead of
  ENTER/PLUS/MINUS/STAR/SLASH/PERIOD (LWJGL2 aliasing). Numpad Enter no longer acts as Enter, and saved
  bindings relying on the aliasing need re-assigning.
- Launcher: Play hides the launcher window. The game loop runs on the JavaFX thread on every OS (GLFW
  main-thread rule) and JavaFX implicit exit is turned off at that point. The process exits via
  `System.exit` (0 on success, 1 on failure; was -1 with LWJGL2's forceExit). A Windows x64 smoke
  (direct `-s` and launcher Play) is required before releasing to Windows/Linux users.
- Mouse cursor in play: hidden and locked with `setCursorCatched` after 5 s without movement
  (no `isInsideWindow` check).
- FFmpeg 6.0 macosx-x86_64 natives need macOS 11+, so Intel Macs on 10.10-10.15 lose BGA video.

## Known pitfalls from upstream history

- PR #730 (libGDX 1.11.0 + LWJGL 3.3.1) was merged then reverted in #732:
  `build.xml` was not updated, binary jars bloated the repo, and controllers
  stopped working (gdx-controllers 2.x needs SDL mappings per device).
  The `origin/lwjgl3` branch holds an older in-progress attempt.
- macOS only offers OpenGL 3.2 core. LWJGL3 needs
  `setOpenGLEmulation(GL30, 3, 2)` and shaders must be GLSL 150 compatible.
- GLFW must run on the macOS main thread. JavaFX already owns it, so the game
  loop has to start from the JavaFX application thread (`Platform.runLater`)
  instead of relying on `-XstartOnFirstThread`.
  `MainLoader.main()` does this for direct play (`-s`, `-a`, a BMS path); the
  launcher's Play button already runs on that thread and hides the launcher
  window, because `Lwjgl3Application` blocks until the game exits.

## Reference implementation

`seraxis/lr2oraja-endlessdream` (GPL-3.0) ships macOS aarch64 builds. Its commit
`86aba175` "Support MacOS(arm based)" covers: Gradle arch selection, libGDX 1.13.0,
LWJGL 3.3.3, sqlite-jdbc 3.42.0.0, JavaCV 1.5.9, GL30 emulation, and
`SpriteBatchHelper` with guacamole `ShaderCompatibilityHelper`.
Catizard's `macos-fix` branch fixes a shader crash in the key-config screen.

## Fallback

Rosetta 2 with an x86_64 JDK 17 + JavaFX runs upstream unchanged. Upstream issue
#851 reports random audio dropouts in that setup, so it is a reference baseline,
not the target.

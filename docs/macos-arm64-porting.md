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
| gdx-controllers 2.2.4 + jamepad 2.26.5.0 | x86_64, arm64 | OK | was `gdx-controllers-desktop` (OIS): i386, x86_64, FAIL. Loads optional `gamecontrollerdb.txt` from working directory; HAT input stubbed (`isHatPressed`) |
| sqlite-jdbc 3.53.4.0 | x86_64, aarch64 | OK | was 3.18: x86_64, FAIL |
| javacv/ffmpeg (JavaCV 1.5.9, FFmpeg 6.0) | x86_64, arm64 | OK | was JavaCPP 1.3.3: x86_64, FAIL |
| JNA 5.13.0 | x86_64, aarch64 | OK | added before java-discord-rpc. discord-rpc's own native is still x86_64, so Discord RPC init is caught and logged (`Discord RPC unavailable`) |
| jportaudio (`natives/libjportaudio.dylib`) | universal (x86_64, arm64) | OK | - |
| JavaFX (launcher) | JDK-provided | OK | - |

JNA is also used directly by `audio/PortAudioAsioDiagnostics.java` (Windows ASIO path).

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

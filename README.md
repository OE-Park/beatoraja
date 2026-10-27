# beatoraja — macOS Apple Silicon (arm64) port

[English](#english) · [日本語](#日本語) · [한국어](#한국어)

This is a fork of [exch-bms2/beatoraja](https://github.com/exch-bms2/beatoraja).
The upstream README (features, options, license) is kept below unchanged.

---

## English

### Why this fork exists

Upstream beatoraja still ships native libraries built for Intel (x86_64) and 32-bit JVMs.
On an Apple Silicon Mac it therefore only runs under Rosetta with an x86_64 JDK, and several
things (video BGA, controllers, the launcher) break or lag. This fork makes beatoraja run on a
native arm64 JVM:

- libGDX 1.14.2 / LWJGL 3 / gdx-freetype / gdx-controllers 2.x (SDL via Jamepad), sqlite-jdbc,
  JavaCV/FFmpeg 6 and JNA replaced with versions that ship macOS arm64 natives. Build stays Ant
  (`build.xml` + `lib/*.jar`) to keep merges with upstream small.
- Launcher (JavaFX) and game loop fixed for the GLFW main-thread rule on macOS.
- Movie BGA first-run race in `FFmpegProcessor` fixed.
- **PHOENIXWAN USB bridge**: on macOS 26 the Gamo2 PHOENIXWAN IIDX controller is reset by the
  OS every ~0.65 s (its joystick endpoint enumerates halted and macOS never clears it), so no
  SDL-based game can read it. The fork ships a small root helper that captures the device, clears
  the halt and forwards the 1000 Hz reports to the game, which exposes it as a normal controller
  with an analog turntable.

Everything is documented with evidence in [`docs/macos-arm64-porting.md`](docs/macos-arm64-porting.md),
including behaviour changes that also apply to Windows/Linux.

### Build and run

Requirements: [mise](https://mise.jdx.dev/) (installs Liberica JDK 17 with JavaFX and Ant), macOS 12+.

```sh
mise install          # arm64 JDK 17 + JavaFX, Ant
mise run build        # -> build/beatoraja.jar
mise run run          # launcher + game from the repo root
```

PHOENIXWAN on macOS 26 (keep the first command running; it needs `sudo` because capturing a USB
device from the OS driver requires root):

```sh
mise run bridge                        # terminal 1
BEATORAJA_USB_BRIDGE=1 mise run run    # terminal 2
```

Put the controller in INFINITAS mode, pick "PHOENIXWAN (USB bridge)" in the in-game key config
(press `6` in music select, then `2` to cycle devices) and enable *Analog Scratch* for each play
mode you use (5KEYS and 7KEYS are separate settings).

### Status

Verified on an M-series Mac with macOS 26: keyboard play, BGA images and videos (mpg/wmv/mp4/avi/mpeg),
bmson/pms/bml charts, 39 GB song-database scan, PHOENIXWAN buttons and analog turntable (5K/7K,
scratch long notes). Windows/Linux builds are not yet re-verified after the library swap — see the
docs before shipping to those users.

---

## 日本語

### このフォークの目的

上流の beatoraja は Intel (x86_64) / 32bit JVM 向けのネイティブライブラリを同梱しているため、
Apple Silicon Mac では Rosetta + x86_64 JDK でしか動かず、動画 BGA・コントローラー・ランチャーに
不具合が出ます。このフォークは arm64 ネイティブ JVM で動くようにしたものです。

- libGDX 1.14.2 / LWJGL 3 / gdx-freetype / gdx-controllers 2.x (Jamepad 経由の SDL)、sqlite-jdbc、
  JavaCV/FFmpeg 6、JNA を macOS arm64 ネイティブ同梱版へ差し替え。上流とのマージ差分を小さくするため
  ビルドは Ant (`build.xml` + `lib/*.jar`) のまま。
- macOS の GLFW メインスレッド制約に合わせてランチャー (JavaFX) とゲームループを修正。
- `FFmpegProcessor` の初回起動時の動画 BGA 競合を修正。
- **PHOENIXWAN USB ブリッジ**: macOS 26 では Gamo2 PHOENIXWAN のジョイスティック用エンドポイントが
  HALT 状態で列挙され、OS がそれを解除せず約 0.65 秒ごとにデバイスをリセットするため、SDL ベースの
  ゲームからは読めません。root 権限の小さなヘルパーがデバイスを掴んで HALT を解除し、1000 Hz の
  レポートをゲームへ転送します。ゲーム側では通常のコントローラー (アナログ皿対応) として見えます。

根拠付きの詳細は [`docs/macos-arm64-porting.md`](docs/macos-arm64-porting.md) にあります
(Windows/Linux にも影響する挙動変更を含む)。

### ビルドと実行

必要なもの: [mise](https://mise.jdx.dev/) (JavaFX 入り Liberica JDK 17 と Ant を導入)、macOS 12 以降。

```sh
mise install
mise run build        # -> build/beatoraja.jar
mise run run
```

macOS 26 で PHOENIXWAN を使う場合 (1 つ目は起動したままにする。OS ドライバから USB デバイスを
奪うには root が必要なので `sudo` を求められる):

```sh
mise run bridge                        # ターミナル 1
BEATORAJA_USB_BRIDGE=1 mise run run    # ターミナル 2
```

コントローラーは INFINITAS モードにし、ゲーム内キーコンフィグ (選曲画面で `6`、`2` でデバイス切替) で
"PHOENIXWAN (USB bridge)" を選び、使うプレイモードごとに *Analog Scratch* を有効にしてください
(5KEYS と 7KEYS は別設定)。

### 状態

macOS 26 の M 系 Mac で確認済み: キーボードプレイ、画像/動画 BGA (mpg/wmv/mp4/avi/mpeg)、
bmson/pms/bml、39 GB の楽曲 DB スキャン、PHOENIXWAN のボタンとアナログ皿 (5K/7K、皿ロング)。
Windows/Linux はライブラリ差し替え後の再確認が未了です。

---

## 한국어

### 이 포크의 목적

원본 beatoraja 는 Intel(x86_64)·32비트 JVM 용 네이티브 라이브러리를 동봉하고 있어서, Apple Silicon
Mac 에서는 Rosetta + x86_64 JDK 로만 돌아가고 동영상 BGA·컨트롤러·런처에 문제가 생긴다. 이 포크는
beatoraja 를 arm64 네이티브 JVM 에서 돌아가게 만든 것이다.

- libGDX 1.14.2 / LWJGL 3 / gdx-freetype / gdx-controllers 2.x(Jamepad 경유 SDL), sqlite-jdbc,
  JavaCV/FFmpeg 6, JNA 를 macOS arm64 네이티브가 들어 있는 버전으로 교체. 원본과의 머지 충돌을 줄이기
  위해 빌드는 Ant(`build.xml` + `lib/*.jar`) 그대로.
- macOS 의 GLFW 메인 스레드 규칙에 맞게 런처(JavaFX)와 게임 루프 수정.
- `FFmpegProcessor` 의 첫 실행 동영상 BGA 경쟁 조건 수정.
- **PHOENIXWAN USB 브리지**: macOS 26 에서는 Gamo2 PHOENIXWAN 의 조이스틱 엔드포인트가 HALT 상태로
  열거되고 OS 가 이를 해제하지 않은 채 약 0.65초마다 장치를 리셋해서, SDL 기반 게임은 입력을 읽을 수
  없다. root 권한의 작은 헬퍼가 장치를 가로채 HALT 를 풀고 1000 Hz 리포트를 게임에 넘기며, 게임에서는
  아날로그 턴테이블이 있는 일반 컨트롤러로 보인다.

근거를 포함한 상세 내용은 [`docs/macos-arm64-porting.md`](docs/macos-arm64-porting.md) 에 있다
(Windows/Linux 에도 적용되는 동작 변경 포함).

### 빌드와 실행

필요한 것: [mise](https://mise.jdx.dev/)(JavaFX 포함 Liberica JDK 17 과 Ant 설치), macOS 12 이상.

```sh
mise install
mise run build        # -> build/beatoraja.jar
mise run run
```

macOS 26 에서 PHOENIXWAN 을 쓸 때(첫 명령은 켜 둔다. OS 드라이버에서 USB 장치를 가로채려면 root 가
필요해 `sudo` 비밀번호를 묻는다):

```sh
mise run bridge                        # 터미널 1
BEATORAJA_USB_BRIDGE=1 mise run run    # 터미널 2
```

컨트롤러는 INFINITAS 모드로 두고, 게임 안 키 설정(곡 선택에서 `6`, `2` 로 장치 순환)에서
"PHOENIXWAN (USB bridge)" 를 고른 뒤, 쓰는 플레이 모드마다 *Analog Scratch* 를 켠다(5KEYS 와 7KEYS 는
별도 설정).

### 상태

macOS 26 M 시리즈 Mac 에서 확인: 키보드 플레이, 이미지/동영상 BGA(mpg/wmv/mp4/avi/mpeg), bmson/pms/bml,
39 GB 곡 DB 스캔, PHOENIXWAN 버튼과 아날로그 턴테이블(5K/7K, 스크래치 롱노트). Windows/Linux 는 라이브러리
교체 후 재검증 전이다.

---

# beatoraja (upstream README)

Codename beatoraja is a Cross-platform rhythm game based on Java and libGDX.
It works on Windows, Mac OS, and Linux.

# Features
- 3 types of Long Note mode : Long Notes, Charge Notes, Hell Charge Notes, and Back Spin Scratch like IIDX
- show note timing duration (like IIDX green number), judge details (fast/slow or +-ms)
- 8 types of groove gauge (ex. assist-easy, ex-hard, ex-grade)
- 11 types of clear lamp (ex. assist, light-assist, ex-hard, perfect, and max)
- real-time play speed controller (x0.25 - x4.0. auto play mode, replay mode only)
- various assist options : legacy note, expand judge, bpm guide, and no mine
- pms judge (max 1 miss / 1 notes, combo is reset when miss)
- support bmson 0.2.1, 1.0.0
- practice mode
- import difficulty table folder, create course with various constraint (mirror/random OK, no hispeed, and so on)
- import LunaticRave2 skin (now working in progress. not supporting DirectXArchive(.dxa) and DirectDrawSurface(.dds) file)
- import LunaticRave2 scores (clear lamp, score. not including score verifier like scorehash)

# System Requirement
- Java Runtime Environment 17 64bit (32bit is not recommended)
- OpenGL 3.1- (may not work under Windows 10 + Intel HD Graphics 2000/3000. See also https://github.com/LWJGL/lwjgl/issues/119)

# How To Use

> java -jar beatoraja.jar -(a|p|r1|r2|r3|r4|s) [BMS path]

- options
  - a : autoplay
  - p : practice
  - r1-r4 : start replay data 1-4
  - s : skip configuration

beatoraja uses a large amount of heap memory. So it is recommended that you use options of extending heap memory : e.g. -Xms1g -Xmx4g.

On JRE 32bit, maximum heap memory size is limited to 1.4G-1.6G. See http://www.oracle.com/technetwork/java/hotspotfaq-138619.html#gc_heap_32bit

**Don't use this application for playing copyrighted contents.**

# License
- GNU General Public License v3

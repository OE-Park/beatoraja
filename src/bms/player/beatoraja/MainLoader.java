package bms.player.beatoraja;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.ConsoleHandler;
import java.util.logging.ErrorManager;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;

import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import bms.player.beatoraja.AudioConfig.DriverType;
import bms.player.beatoraja.ir.IRConnection;
import bms.player.beatoraja.ir.IRConnectionManager;
import bms.player.beatoraja.ir.IRResponse;
import bms.player.beatoraja.ir.IRVersionInfo;
import bms.player.beatoraja.launcher.PlayConfigurationView;
import bms.player.beatoraja.song.SQLiteSongDatabaseAccessor;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongDatabaseAccessor;
import bms.player.beatoraja.song.SongUtils;
import jdk.jfr.StackTrace;

/**
 * 起動用クラス
 *
 * @author exch
 */
public class MainLoader extends Application {

	private static final boolean ALLOWS_32BIT_JAVA = false;
	// Change to BATCHED after comparing songdata.db update performance on target environments.
	private static final SQLiteSongDatabaseAccessor.SongUpdaterType SONG_UPDATER_TYPE = SQLiteSongDatabaseAccessor.SongUpdaterType.BATCHED;

	private static final Set<String> illegalSongs = new HashSet<String>();

	private static Path bmsPath;

	private static VersionChecker version;

	private final SongDatabaseAccessorProvider songDatabaseAccessorProvider = new SongDatabaseAccessorProvider();

	public static void main(String[] args) {

		if(!ALLOWS_32BIT_JAVA && !System.getProperty( "os.arch" ).contains( "64")) {
			JOptionPane.showMessageDialog(null, "This Application needs 64bit-Java.", "Error", JOptionPane.ERROR_MESSAGE);
			System.exit(1);
		}

		configureLogger();

		BMSPlayerMode auto = null;
		for (String s : args) {
			if (s.startsWith("-")) {
				if (s.equals("-a")) {
					auto = BMSPlayerMode.AUTOPLAY;
				}
				if (s.equals("-p")) {
					auto = BMSPlayerMode.PRACTICE;
				}
				if (s.equals("-r") || s.equals("-r1")) {
					auto = BMSPlayerMode.REPLAY_1;
				}
				if (s.equals("-r2")) {
					auto = BMSPlayerMode.REPLAY_2;
				}
				if (s.equals("-r3")) {
					auto = BMSPlayerMode.REPLAY_3;
				}
				if (s.equals("-r4")) {
					auto = BMSPlayerMode.REPLAY_4;
				}
				if (s.equals("-s")) {
					auto = BMSPlayerMode.PLAY;
				}
			} else {
				bmsPath = Paths.get(s);
				if(auto == null) {
					auto = BMSPlayerMode.PLAY;
				}
			}
		}



		if (Files.exists(Config.configpath) && (bmsPath != null || auto != null)) {
			IRConnectionManager.getAllAvailableIRConnectionName();
			final BMSPlayerMode mode = auto;
			if (MacOSSupport.isMacOS()) {
				// GLFW must run on the AppKit main thread, which is the JavaFX application thread on macOS
				final Runnable game = () -> play(bmsPath, mode, true, null, null, bmsPath != null);
				try {
					javafx.application.Platform.startup(game);
				} catch (IllegalStateException e) {
					// The java launcher has already started the JavaFX toolkit (MainLoader extends Application)
					javafx.application.Platform.runLater(game);
				}
			} else {
				play(bmsPath, mode, true, null, null, bmsPath != null);
			}
		} else {
			launch(args);
		}
	}

	private static void configureLogger() {
		Logger logger = Logger.getGlobal();
		try {
			AsyncLogHandler consoleHandler = new AsyncLogHandler(new ConsoleHandler());
			AsyncLogHandler fileHandler = new AsyncLogHandler(new FileHandler("beatoraja_log.xml"));
			logger.setUseParentHandlers(false);
			logger.addHandler(consoleHandler);
			logger.addHandler(fileHandler);
			Runtime.getRuntime().addShutdownHook(new Thread(() -> closeLogHandlers(logger), "beatoraja-log-shutdown"));
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	private static void closeLogHandlers(Logger logger) {
		for (Handler handler : logger.getHandlers()) {
			handler.close();
		}
	}

	public static void play(Path f, BMSPlayerMode auto, boolean forceExit, Config config, PlayerConfig player, boolean songUpdated) {
		if(config == null) {
			config = Config.read();
		}
		if(player == null) {
			player = PlayerConfig.readPlayerConfig(config.getPlayerpath(), config.getPlayername());
		}

		SongDatabaseAccessor songdb;
		try {
			songdb = new SongDatabaseAccessorProvider().get(config, player.getId());
			detectIllegalSongs(songdb, player);
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
			Logger.getGlobal().severe("楽曲データベース初期化中の例外:" + e.getMessage());
			return;
		}
		if(illegalSongs.size() > 0) {
			JOptionPane.showMessageDialog(null, "This Application detects " + illegalSongs.size() + " illegal BMS songs. \n Remove them, update song database and restart.", "Error", JOptionPane.ERROR_MESSAGE);
			System.exit(1);
		}

		try {
			final MainController main = new MainController(f, config, player, auto, songUpdated, songdb);

			Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
			final int w = config.getResolution().width;
			final int h = config.getResolution().height;

			// fullscreen
			switch (config.getDisplaymode()) {
				case FULLSCREEN:
					Graphics.DisplayMode d = null;
					for (Graphics.DisplayMode display : Lwjgl3ApplicationConfiguration.getDisplayModes()) {
						if (display.width == w && display.height == h
								&& (d == null || (d.refreshRate <= display.refreshRate && d.bitsPerPixel <= display.bitsPerPixel))) {
							d = display;
						}
					}
					if (d != null) {
						cfg.setFullscreenMode(d);
					} else {
						cfg.setWindowedMode(w, h);
					}
					break;
				case BORDERLESS:
					cfg.setDecorated(false);
					cfg.setWindowedMode(w, h);
					break;
				case WINDOW:
					cfg.setWindowedMode(w, h);
					break;
			}
			// vSync
			cfg.useVsync(config.isVsync());
			cfg.setIdleFPS(config.getMaxFramePerSecond());
			cfg.setForegroundFPS(config.getMaxFramePerSecond());
			cfg.setTitle(MainController.getVersion());

			final int audioDeviceBufferSize;
			if(config.getAudioConfig().getDriver() == DriverType.AudioDevice) {
				audioDeviceBufferSize = Math.max(config.getAudioConfig().getDeviceBufferSize() * 2 * 2, 4096);
			} else {
				audioDeviceBufferSize = config.getAudioConfig().getDeviceBufferSize();
			}
			cfg.setAudioConfig(config.getAudioConfig().getDeviceSimultaneousSources(), audioDeviceBufferSize, 9);
			if(config.getAudioConfig().getDriver() == DriverType.PortAudio) {
				cfg.disableAudio(true);
			}
			MacOSSupport.configure(cfg);

			new Lwjgl3Application(new ApplicationListener() {
				
				public void resume() {
					main.resume();
				}
				
				public void resize(int width, int height) {
					main.resize(width, height);
				}
				
				public void render() {
					main.render();
				}
				
				public void pause() {
					main.pause();
				}
				
				public void dispose() {
					main.dispose();
				}
				
				public void create() {
					main.create();
				}
			}, cfg) {
				// This is some kind of hack. Refer to com.badlogic.gdx.controllers.desktop.support.JamepadControllerMonitor code.
				// JamepadControllerMonitor creates a runnable that polls the controller input. It polls the input every frame via
				// `Gdx.app.postRunnable` function.
				//
				// We want it to poll the input on every poll thread's interval. Be it 1000Hz or 8000Hz.
				// To achieve that, we *intercept* the runnable, store it somewhere else, and call that runnable
				// directly on the polling thread. (BMSPlayerInputProcessor.poll). Further postRunnable for that
				// runnable will be prohibited.
				// On macOS, SDL joystick calls must stay on the thread that initialised SDL (the render/main thread).
				private Runnable jamepadRunnable = null;
				@Override
				public void postRunnable(Runnable runnable) {
					if (!MacOSSupport.isMacOS()) {
						if (jamepadRunnable == null) {
							StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
							if (stackTrace.length >= 3) {
								if (stackTrace[2].getClassName().startsWith("com.badlogic.gdx.controllers.desktop.support.JamepadControllerMonitor")) {
									jamepadRunnable = runnable;
									BMSPlayerInputProcessor.controllerPollRunner = runnable;
								}
							}
						}
						else if (runnable == jamepadRunnable) return;
					}

					super.postRunnable(runnable);
				}
			};
			// Lwjgl3Application blocks until the game loop exits
			if (forceExit) {
				System.exit(0);
			}

		} catch (Throwable e) {
			e.printStackTrace();
			Logger.getGlobal().severe(e.getClass().getName() + " : " + e.getMessage());
		}
	}

	public static Graphics.DisplayMode[] getAvailableDisplayMode() {
		return Lwjgl3ApplicationConfiguration.getDisplayModes();
	}

	public static Graphics.DisplayMode getDesktopDisplayMode() {
		return Lwjgl3ApplicationConfiguration.getDisplayMode();
	}

	public static VersionChecker getVersionChecker() {
		if(version == null) {
			version = new CompositeVersionChecker();
		}
		return version;
	}

	public static void setVersionChecker(VersionChecker version) {
		if(version != null) {
			MainLoader.version = version;
		}
	}

	public static Path getBMSPath() {
		return bmsPath;
	}

	public static void putIllegalSong(String hash) {
		illegalSongs.add(hash);
	}

	public static String[] getIllegalSongs() {
		return illegalSongs.toArray(new String[illegalSongs.size()]);
	}

	public static int getIllegalSongCount() {
		return illegalSongs.size();
	}

	private static void detectIllegalSongs(SongDatabaseAccessor songdb, PlayerConfig player) {
		illegalSongs.clear();

		Set<String> hashes = new LinkedHashSet<>();
		addIllegalSongHashes(hashes, SongUtils.illegalsongs);
		for(IRConfig irconfig : player.getIrconfig()) {
			if(irconfig == null || irconfig.getIrname() == null || irconfig.getIrname().length() == 0) {
				continue;
			}
			IRConnection ir = IRConnectionManager.getIRConnection(irconfig.getIrname());
			if(ir == null) {
				continue;
			}
			try {
				IRResponse<String[]> response = ir.getIllegalSongs();
				if(response.isSucceeded()) {
					addIllegalSongHashes(hashes, response.getData());
				} else if(response.getMessage() != null && response.getMessage().length() > 0
						&& !"Not supported".equals(response.getMessage())) {
					Logger.getGlobal().warning("IR illegal song list取得失敗 - " + irconfig.getIrname() + " : "
							+ response.getMessage());
				}
			} finally {
				closeIRConnection(ir);
			}
		}

		if(hashes.isEmpty()) {
			return;
		}
		for(SongData song : songdb.getSongDatas(hashes.toArray(String[]::new))) {
			if(song != null && song.getSha256() != null && song.getSha256().length() > 0) {
				MainLoader.putIllegalSong(song.getSha256());
			}
		}
	}

	private static void addIllegalSongHashes(Set<String> hashes, String[] candidates) {
		if(candidates == null) {
			return;
		}
		for(String candidate : candidates) {
			if(candidate != null && candidate.length() == 64) {
				hashes.add(candidate);
			}
		}
	}

	private static void closeIRConnection(IRConnection ir) {
		if(ir instanceof AutoCloseable closeable) {
			try {
				closeable.close();
			} catch(Exception e) {
			}
		}
	}

	@Override
	public void start(javafx.stage.Stage primaryStage) throws Exception {
		Config config = Config.read();

		try {
//			final long t = System.currentTimeMillis();
			ResourceBundle bundle = ResourceBundle.getBundle("resources.UIResources");
			FXMLLoader loader = new FXMLLoader(
					MainLoader.class.getResource("/bms/player/beatoraja/launcher/PlayConfigurationView.fxml"), bundle);
			VBox stackPane = (VBox) loader.load();
			PlayConfigurationView bmsinfo = (PlayConfigurationView) loader.getController();
			bmsinfo.setBMSInformationLoader(this);
			bmsinfo.setSongDatabaseAccessorResolver(songDatabaseAccessorProvider::get);
			bmsinfo.update(config);
			Scene scene = new Scene(stackPane, stackPane.getPrefWidth(), stackPane.getPrefHeight());
			primaryStage.setScene(scene);
			primaryStage.setTitle(MainController.getVersion() + " configuration");
			primaryStage.setOnCloseRequest((event) -> {
				bmsinfo.exit();
			});
			primaryStage.show();
//			Logger.getGlobal().info("初期化時間(ms) : " + (System.currentTimeMillis() - t));

		} catch (IOException e) {
			Logger.getGlobal().severe(e.getMessage());
			e.printStackTrace();
		}
	}

	private static class SongDatabaseAccessorProvider {

		private SongDatabaseAccessor songdb;

		private String songpath;

		private String reviewpath;

		private boolean scanSongArchives;

		private Config.SongArchiveExtractMode songArchiveExtractMode;

		public synchronized SongDatabaseAccessor get(Config config) throws ClassNotFoundException {
			return get(config, config.getPlayername());
		}

		public synchronized SongDatabaseAccessor get(Config config, String playername) throws ClassNotFoundException {
			String nextSongpath = config.getSongpath();
			String nextReviewpath = getSongReviewPath(config, playername);
			if (songdb == null || !nextSongpath.equals(songpath) || !nextReviewpath.equals(reviewpath)
					|| config.isScanSongArchives() != scanSongArchives
					|| config.getSongArchiveExtractMode() != songArchiveExtractMode) {
				Class.forName("org.sqlite.JDBC");
				songdb = new SQLiteSongDatabaseAccessor(nextSongpath, config.getBmsroot(), nextReviewpath,
						SONG_UPDATER_TYPE, config.isScanSongArchives(), config.getSongArchiveExtractMode());
				songpath = nextSongpath;
				reviewpath = nextReviewpath;
				scanSongArchives = config.isScanSongArchives();
				songArchiveExtractMode = config.getSongArchiveExtractMode();
			}
			return songdb;
		}

		private String getSongReviewPath(Config config, String playername) {
			return config.getPlayerpath() + File.separatorChar + playername + File.separatorChar + "songreview.db";
		}
	}

	public interface VersionChecker {
		public String getMessage();
		public String getDownloadURL();
	}

	private static class CompositeVersionChecker implements VersionChecker {

		private String dlurl;
		private String message;

		public String getMessage() {
			if(message == null) {
				getInformation();
			}
			return message;
		}

		public String getDownloadURL() {
			if(message == null) {
				getInformation();
			}
			return dlurl;
		}

		private void getInformation() {
			VersionChecker github = new GithubVersionChecker();
			message = github.getMessage();
			dlurl = github.getDownloadURL();

			IRVersionInfo irVersion = getIRVersionInfo();
			if(irVersion != null) {
				message = getIRVersionMessage(irVersion);
				dlurl = irVersion.downloadURL;
			}
		}

		private IRVersionInfo getIRVersionInfo() {
			Config config = Config.read();
			PlayerConfig player = PlayerConfig.readPlayerConfig(config.getPlayerpath(), config.getPlayername());
			for(IRConfig irconfig : player.getIrconfig()) {
				if(irconfig == null || irconfig.getIrname() == null || irconfig.getIrname().length() == 0) {
					continue;
				}
				IRConnection ir = IRConnectionManager.getIRConnection(irconfig.getIrname());
				if(ir == null) {
					continue;
				}
				try {
					IRResponse<IRVersionInfo> response = ir.getVersionInfo(MainController.getVersion());
					if(response.isSucceeded() && response.getData() != null) {
						return response.getData();
					}
					if(response.getMessage() != null && response.getMessage().length() > 0
							&& !"Not supported".equals(response.getMessage())) {
						Logger.getGlobal().warning("IR version取得失敗 - " + irconfig.getIrname() + " : "
								+ response.getMessage());
					}
				} finally {
					closeIRConnection(ir);
				}
			}
			return null;
		}

		private String getIRVersionMessage(IRVersionInfo version) {
			if(version.message != null && version.message.length() > 0) {
				return version.message;
			}
			if(version.version != null && version.version.length() > 0
					&& !MainController.getVersion().contains(version.version)) {
				return String.format("最新版[%s]を利用可能です。", version.version);
			}
			return "最新版を利用中です";
		}
	}

	private static class GithubVersionChecker implements VersionChecker {
		private static final Pattern RELEASE_NAME = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"((?:\\\\\\\\.|[^\\\"\\\\\\\\])*)\\\"");

		private String dlurl;
		private String message;

		public String getMessage() {
			if(message == null) {
				getInformation();
			}
			return message;
		}

		public String getDownloadURL() {
			if(message == null) {
				getInformation();
			}
			return dlurl;
		}

		private void getInformation() {
			try {
				URL url = new URL("https://api.github.com/repos/exch-bms2/beatoraja/releases/latest");
				URLConnection connection = url.openConnection();
				connection.setConnectTimeout(5000);
				connection.setReadTimeout(5000);
				connection.setRequestProperty("Accept", "application/vnd.github+json");
				connection.setRequestProperty("User-Agent", "beatoraja-version-checker");
				final String name = getReleaseName(connection);
				if (MainController.getVersion().contains(name)) {
					message = "最新版を利用中です";
				} else {
					message = String.format("最新版[%s]を利用可能です。", name);
					dlurl = "https://mocha-repository.info/download/beatoraja" + name + ".zip";
				}
			} catch (Exception | LinkageError e) {
				Logger.getGlobal().warning("最新版URL取得時例外:" + e.getMessage());
				message = "バージョン情報を取得できませんでした";
			}
		}

		private String getReleaseName(URLConnection connection) throws IOException {
			StringBuilder response = new StringBuilder();
			try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
				char[] buffer = new char[4096];
				for (int length; (length = reader.read(buffer)) >= 0;) {
					response.append(buffer, 0, length);
				}
			}

			Matcher matcher = RELEASE_NAME.matcher(response);
			if (!matcher.find()) {
				throw new IOException("GitHub release response does not contain a name");
			}
			return matcher.group(1).replace("\\\\\"", "\"").replace("\\\\\\\\", "\\");
		}
	}

	/**
	 * Delegates log output to a background thread so caller threads do not wait for
	 * console or file I/O.
	 */
	private static final class AsyncLogHandler extends Handler {

		private static final int DEFAULT_CAPACITY = 8192;
		private static final long CLOSE_TIMEOUT_MILLIS = 5000;

		private final Handler delegate;
		private final BlockingQueue<LogRecord> queue;
		private final AtomicBoolean closed = new AtomicBoolean();
		private final AtomicLong droppedRecords = new AtomicLong();
		private final Thread worker;

		private volatile boolean running = true;

		private AsyncLogHandler(Handler delegate) {
			this(delegate, Integer.getInteger("beatoraja.log.async.queue.size", DEFAULT_CAPACITY));
		}

		private AsyncLogHandler(Handler delegate, int capacity) {
			this.delegate = delegate;
			queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
			setLevel(delegate.getLevel());
			setFilter(delegate.getFilter());
			worker = new Thread(this::processQueue, "beatoraja-async-log");
			worker.setDaemon(true);
			worker.start();
		}

		@Override
		public void publish(LogRecord record) {
			if (closed.get() || !isLoggable(record)) {
				return;
			}

			if (queue.offer(record)) {
				return;
			}

			droppedRecords.incrementAndGet();
			if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
				queue.poll();
				queue.offer(record);
			}
		}

		@Override
		public void flush() {
			delegate.flush();
		}

		@Override
		public void close() {
			if (!closed.compareAndSet(false, true)) {
				return;
			}

			running = false;
			worker.interrupt();
			try {
				worker.join(CLOSE_TIMEOUT_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			drainQueue();
			publishDroppedRecords();
			delegate.flush();
			delegate.close();
		}

		private void processQueue() {
			while (running || !queue.isEmpty()) {
				try {
					LogRecord record = queue.poll(1, TimeUnit.SECONDS);
					if (record != null) {
						publishToDelegate(record);
					}
				} catch (InterruptedException e) {
					if (!running) {
						break;
					}
				}
			}
			drainQueue();
		}

		private void drainQueue() {
			LogRecord record;
			while ((record = queue.poll()) != null) {
				publishToDelegate(record);
			}
		}

		private void publishToDelegate(LogRecord record) {
			try {
				delegate.publish(record);
			} catch (RuntimeException e) {
				reportError(e.getMessage(), e, ErrorManager.WRITE_FAILURE);
			}
		}

		private void publishDroppedRecords() {
			long dropped = droppedRecords.get();
			if (dropped == 0) {
				return;
			}

			LogRecord record = new LogRecord(Level.WARNING, "Async logger dropped " + dropped + " log records.");
			record.setLoggerName("global");
			publishToDelegate(record);
		}
	}

}

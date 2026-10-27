package bms.player.beatoraja.input;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * PHOENIXWAN USB bridge for macOS (run as root).
 *
 * macOS 26 resets the PHOENIXWAN (Gamo2 IIDX controller) every ~0.65 s because its
 * joystick interrupt endpoint enumerates in the HALT state and the macOS HID driver
 * never clears it (it retries ten times, then resets the device, which halts it again).
 * This program captures the USB device with libusb (which detaches the macOS HID driver),
 * clears the endpoint halt, reads the raw 1000 Hz HID reports from the joystick interface
 * and forwards them to {@link UsbBridgeController} over loopback UDP. Start it before the
 * game and keep it running:
 *
 * <pre>
 * mise run bridge                                   # = sudo java -cp build/beatoraja.jar ...PhoenixwanBridge
 * BEATORAJA_USB_BRIDGE=1 mise run run               # same port (27831)
 * </pre>
 *
 * Packet (12 bytes): "PW1" + seq uint32 LE + raw report (5 bytes: X = turntable 0..255
 * wrapping, Y, buttons low, buttons high, reserved). Ctrl-C hands the device back to macOS.
 *
 * libusb is loaded from natives/libusb-1.0.dylib (bundled, LGPL-2.1, see natives/LICENSE.libusb)
 * or, failing that, from the system / Homebrew library path.
 *
 * Security note: this runs as root and loads build/beatoraja.jar and natives/libusb-1.0.dylib from
 * the checkout, which the login user can write. Anything that can already run code as that user can
 * therefore gain root the next time the bridge starts. For a shared machine copy the jar and the
 * dylib to a root-owned directory and run from there; see docs/macos-arm64-porting.md.
 */
public class PhoenixwanBridge {

	/** Minimal libusb-1.0 binding: only what the bridge needs. */
	public interface LibUsb extends Library {
		int libusb_init(PointerByReference ctx);
		void libusb_exit(Pointer ctx);
		Pointer libusb_open_device_with_vid_pid(Pointer ctx, short vid, short pid);
		int libusb_detach_kernel_driver(Pointer handle, int iface);
		int libusb_attach_kernel_driver(Pointer handle, int iface);
		int libusb_claim_interface(Pointer handle, int iface);
		int libusb_release_interface(Pointer handle, int iface);
		int libusb_clear_halt(Pointer handle, byte endpoint);
		int libusb_control_transfer(Pointer handle, byte bmRequestType, byte bRequest, short wValue, short wIndex,
				byte[] data, short wLength, int timeout);
		int libusb_interrupt_transfer(Pointer handle, byte endpoint, byte[] data, int length, IntByReference transferred,
				int timeout);
		void libusb_close(Pointer handle);
		String libusb_error_name(int code);
	}

	private static final int ERR_ACCESS = -3;
	private static final int ERR_NO_DEVICE = -4;
	private static final int ERR_TIMEOUT = -7;
	private static final int ERR_PIPE = -9;

	private static final int[][] DEVICES = { { 0x1ccf, 0x8048 }, { 0x034c, 0x0368 } };
	private static final String[] DEVICE_NAMES = { "Controller INF&BMS (INFINITAS/LR2/KEYBOARD/GAMEPAD modes)",
			"Controller HID (HID-light mode)" };
	private static final int JOY_INTERFACE = 1;
	private static final byte JOY_EP_IN = (byte) 0x81;
	private static final int REPORT_LEN = 5;

	private final LibUsb usb;
	private final int port;
	private final boolean verbose;
	private final DatagramSocket socket;
	private final InetAddress loopback;
	private int seq = 0;
	private volatile Pointer handle;
	private volatile boolean stop = false;

	private PhoenixwanBridge(LibUsb usb, int port, boolean verbose) throws IOException {
		this.usb = usb;
		this.port = port;
		this.verbose = verbose;
		this.loopback = InetAddress.getLoopbackAddress();
		this.socket = new DatagramSocket(0, loopback); // never bind the wildcard address from a root process
	}

	private static void log(String msg) {
		System.out.println(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
		System.out.flush();
	}

	private String err(int r) {
		return r < 0 ? usb.libusb_error_name(r) : Integer.toString(r);
	}

	private static String hex(byte[] b, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			if (i > 0) sb.append(' ');
			sb.append(String.format("%02x", b[i] & 0xff));
		}
		return sb.toString();
	}

	/** Capture the device, clear the halted joystick endpoint, claim the joystick interface. */
	private boolean attach() {
		String desc = null;
		boolean waitingLogged = false;
		while (!stop) {
			for (int i = 0; i < DEVICES.length; i++) {
				handle = usb.libusb_open_device_with_vid_pid(null, (short) DEVICES[i][0], (short) DEVICES[i][1]);
				if (handle != null) {
					desc = String.format("%04x:%04x %s", DEVICES[i][0], DEVICES[i][1], DEVICE_NAMES[i]);
					break;
				}
			}
			if (handle != null) break;
			if (!waitingLogged) {
				log("waiting for PHOENIXWAN (1ccf:8048 or 034c:0368) to be plugged in...");
				waitingLogged = true;
			}
			sleep(250);
		}
		if (handle == null) return false;
		log("device found: " + desc);
		int r = usb.libusb_detach_kernel_driver(handle, 0); // darwin: captures the whole device
		if (r < 0) {
			log("capture failed: " + err(r) + (r == ERR_ACCESS ? " (root required: run with sudo)" : ""));
			return false;
		}
		sleep(300);
		r = usb.libusb_claim_interface(handle, JOY_INTERFACE);
		if (r < 0) {
			log("claim interface " + JOY_INTERFACE + " failed: " + err(r));
			detach(true); // the device is captured already: hand it back rather than leave it driverless
			return false;
		}
		byte[] st = new byte[2];
		usb.libusb_control_transfer(handle, (byte) 0x82, (byte) 0, (short) 0, (short) (JOY_EP_IN & 0xff), st, (short) 2, 500);
		log(String.format("endpoint 0x%02x status before clear: %02x %02x", JOY_EP_IN & 0xff, st[0], st[1]));
		r = usb.libusb_clear_halt(handle, JOY_EP_IN);
		log("clear_halt -> " + err(r) + "; forwarding reports to udp://127.0.0.1:" + port);
		return true;
	}

	private void detach(boolean releaseToMacOS) {
		Pointer h = handle;
		handle = null;
		if (h == null) return;
		usb.libusb_release_interface(h, JOY_INTERFACE);
		if (releaseToMacOS) {
			int r = usb.libusb_attach_kernel_driver(h, 0);
			if (r == 0) {
				log("released device back to macOS");
			} else {
				log("could not hand the device back to macOS (" + err(r) + "); replug the controller");
			}
		}
		usb.libusb_close(h);
	}

	/** Read reports until the device goes away or we are told to stop. */
	private void pump() {
		byte[] buf = new byte[64];
		IntByReference got = new IntByReference();
		byte[] packet = new byte[3 + 4 + REPORT_LEN];
		packet[0] = 'P'; packet[1] = 'W'; packet[2] = '1';
		byte[] last = null;
		int pipeErrors = 0;
		int otherErrors = 0;
		long shortReports = 0;
		long count = 0;
		long statAt = System.nanoTime();
		while (!stop) {
			int r = usb.libusb_interrupt_transfer(handle, JOY_EP_IN, buf, buf.length, got, 100);
			if (r == 0 && got.getValue() < REPORT_LEN) {
				// a truncated report would decode as a hard-left turntable (byte0 = 0); drop it
				shortReports++;
				if (shortReports == 1) log("short report (" + got.getValue() + " bytes) ignored");
			} else if (r == 0) {
				Arrays.fill(packet, 7, packet.length, (byte) 0);
				System.arraycopy(buf, 0, packet, 7, REPORT_LEN);
				seq++;
				ByteBuffer.wrap(packet, 3, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(seq);
				try {
					socket.send(new DatagramPacket(packet, packet.length, loopback, port));
				} catch (IOException e) {
					log("udp send failed: " + e);
				}
				count++;
				pipeErrors = 0;
				otherErrors = 0;
				if (verbose) {
					byte[] rep = Arrays.copyOfRange(packet, 7, packet.length);
					if (!Arrays.equals(rep, last)) {
						System.out.println("  " + hex(rep, rep.length));
						last = rep;
					}
				}
			} else if (r == ERR_TIMEOUT) {
				// nothing this round
			} else if (r == ERR_NO_DEVICE) {
				log("device disconnected");
				return;
			} else if (r == ERR_PIPE) {
				pipeErrors++;
				if (pipeErrors == 1) {
					log("endpoint stalled again; clearing halt");
					usb.libusb_clear_halt(handle, JOY_EP_IN);
				} else if (pipeErrors > 50) {
					log("endpoint keeps stalling; re-attaching");
					return;
				}
			} else {
				otherErrors++;
				if (otherErrors == 1) log("read error: " + err(r));
				if (otherErrors > 100) { // ~5 s of e.g. LIBUSB_ERROR_IO: give the device back and start over
					log("read keeps failing (" + err(r) + "); re-attaching");
					return;
				}
				sleep(50);
			}
			if (System.nanoTime() - statAt >= 10_000_000_000L) {
				log(count + " reports in the last 10 s");
				count = 0;
				statAt = System.nanoTime();
			}
		}
	}

	private void run() {
		final Thread main = Thread.currentThread();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			// Let the main loop finish its current (<=100 ms) transfer and release the device itself;
			// closing the handle from this thread while a transfer is in flight is a use-after-close.
			stop = true;
			try {
				main.join(3000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (handle != null) {
				detach(true);
			}
			log("bye");
		}));
		while (!stop) {
			if (!attach()) {
				detach(false);
				sleep(1000);
				continue;
			}
			pump();
			detach(stop);
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static LibUsb loadLibUsb() {
		// 1) bundled copy next to the other natives (works with -Djava.library.path=./natives or from the repo root)
		String jlp = System.getProperty("java.library.path", "");
		for (String dir : (jlp + File.pathSeparator + "natives").split(File.pathSeparator)) {
			if (!dir.isEmpty()) NativeLibrary.addSearchPath("usb-1.0", new File(dir).getAbsolutePath());
		}
		// 2) fallback: system / Homebrew search path
		NativeLibrary.addSearchPath("usb-1.0", "/opt/homebrew/lib");
		NativeLibrary.addSearchPath("usb-1.0", "/usr/local/lib");
		// Native.loadLibrary exists in both the JNA 4.x bundled by java-discord-rpc (first on the classpath) and JNA 5.13
		@SuppressWarnings("deprecation")
		LibUsb lib = (LibUsb) Native.loadLibrary("usb-1.0", LibUsb.class);
		System.out.println("libusb: " + NativeLibrary.getInstance("usb-1.0").getFile());
		return lib;
	}

	private static void usage() {
		System.out.println("usage: PhoenixwanBridge [--port N] [-v|--verbose]\n"
				+ "  Captures the PHOENIXWAN USB device (root required) and forwards its HID reports\n"
				+ "  to beatoraja over UDP 127.0.0.1:<port> (default 27831). Keep it running and start\n"
				+ "  the game with BEATORAJA_USB_BRIDGE=1. Ctrl-C hands the device back to macOS.");
	}

	public static void main(String[] args) throws Exception {
		int port = UsbBridgeController.DEFAULT_PORT;
		boolean verbose = false;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--port":
					if (i + 1 >= args.length) { usage(); System.exit(2); }
					try {
						port = Integer.parseInt(args[++i]);
					} catch (NumberFormatException e) {
						port = 0;
					}
					if (port < 1 || port > 65535) {
						System.err.println("--port must be 1..65535");
						System.exit(2);
					}
					break;
				case "-v":
				case "--verbose":
					verbose = true;
					break;
				case "-h":
				case "--help":
					usage();
					return;
				default:
					usage();
					System.exit(2);
			}
		}
		if (!"root".equals(System.getProperty("user.name"))) {
			System.err.println("root required (USB device capture): run via `mise run bridge` or `sudo java -cp build/beatoraja.jar "
					+ PhoenixwanBridge.class.getName() + "`");
			System.exit(2);
		}
		LibUsb usb = loadLibUsb();
		if (usb.libusb_init(null) != 0) {
			System.err.println("libusb_init failed");
			System.exit(1);
		}
		new PhoenixwanBridge(usb, port, verbose).run();
	}
}

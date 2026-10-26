package bms.player.beatoraja.input;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.logging.Logger;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerListener;
import com.badlogic.gdx.controllers.ControllerMapping;
import com.badlogic.gdx.controllers.ControllerPowerLevel;

/**
 * Virtual controller fed by raw HID reports over loopback UDP.
 *
 * On macOS 26 the PHOENIXWAN cannot be read through SDL because the OS keeps
 * resetting the device. A root-level daemon (tools/macos/phoenixwan_bridge.py)
 * captures the USB device with libusb and forwards the 1000 Hz reports to this
 * class, which exposes them through the gdx {@link Controller} interface so the
 * existing controller configuration and scratch logic work unchanged.
 *
 * Packet: "PW1" (3 bytes) + seq uint32 LE (4 bytes) + raw report (5 bytes).
 * Report: byte0 = X axis 0..255, byte1 = Y axis, byte2 | byte3 << 8 = 16 button
 * bits, byte4 reserved.
 */
public class UsbBridgeController implements Controller {

	public static final int DEFAULT_PORT = 27831;

	private static final int PACKET_LENGTH = 12;
	private static final int AXIS_COUNT = 2;
	private static final int BUTTON_COUNT = 16;
	/** Stream is considered lost after this many milliseconds without a packet. */
	private static final long TIMEOUT_MS = 500;

	private final int port;
	private final DatagramSocket socket;
	private final Thread thread;

	private volatile boolean running = true;
	private volatile boolean connected = false;
	private volatile int buttons = 0;
	private volatile float axis0 = 0f;
	private volatile float axis1 = 0f;
	private volatile long lastPacketNanos = 0;
	private int lastSeq = -1;
	private boolean seqValid = false;

	public UsbBridgeController(int port) throws IOException {
		this.port = port;
		socket = new DatagramSocket(port, InetAddress.getLoopbackAddress());
		socket.setSoTimeout((int) TIMEOUT_MS);
		thread = new Thread(this::receiveLoop, "UsbBridgeController-" + port);
		thread.setDaemon(true);
		thread.start();
		Logger.getGlobal().info("USB bridge controller listening on udp/127.0.0.1:" + port);
	}

	private void receiveLoop() {
		final byte[] buf = new byte[64];
		final DatagramPacket packet = new DatagramPacket(buf, buf.length);
		while (running) {
			try {
				socket.receive(packet);
			} catch (SocketTimeoutException e) {
				checkTimeout();
				continue;
			} catch (IOException e) {
				if (running) {
					Logger.getGlobal().warning("USB bridge receive failed: " + e);
				}
				break;
			}
			if (packet.getLength() != PACKET_LENGTH || buf[0] != 'P' || buf[1] != 'W' || buf[2] != '1') {
				checkTimeout();
				continue;
			}
			final int seq = (buf[3] & 0xff) | (buf[4] & 0xff) << 8 | (buf[5] & 0xff) << 16 | (buf[6] & 0xff) << 24;
			if (seqValid && seq - lastSeq <= 0) {
				// older than or equal to the last accepted packet (signed difference handles uint32 wrap)
				checkTimeout();
				continue;
			}
			lastSeq = seq;
			seqValid = true;
			axis0 = toAxis(buf[7]);
			axis1 = toAxis(buf[8]);
			buttons = (buf[9] & 0xff) | (buf[10] & 0xff) << 8;
			lastPacketNanos = System.nanoTime();
			if (!connected) {
				connected = true;
				Logger.getGlobal().info("USB bridge stream started (port " + port + ")");
			}
		}
	}

	private void checkTimeout() {
		if (connected && System.nanoTime() - lastPacketNanos > TIMEOUT_MS * 1_000_000L) {
			connected = false;
			buttons = 0;
			seqValid = false;
			Logger.getGlobal().info("USB bridge stream lost (no packet for " + TIMEOUT_MS + " ms)");
		}
	}

	private static float toAxis(byte b) {
		final float v = ((b & 0xff) - 128) / 127f;
		return v < -1f ? -1f : (v > 1f ? 1f : v);
	}

	/**
	 * Stops the receive thread and closes the socket.
	 */
	public void close() {
		running = false;
		socket.close();
		try {
			thread.join(TIMEOUT_MS * 2);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void dispose() {
		close();
	}

	@Override
	public boolean getButton(int buttonCode) {
		if (buttonCode < 0 || buttonCode >= BUTTON_COUNT) {
			return false;
		}
		return connected && (buttons & (1 << buttonCode)) != 0;
	}

	@Override
	public float getAxis(int axisCode) {
		switch (axisCode) {
			case 0:
				return axis0;
			case 1:
				return axis1;
			default:
				return 0f;
		}
	}

	@Override
	public String getName() {
		return "PHOENIXWAN (USB bridge)";
	}

	@Override
	public String getUniqueId() {
		return "usbbridge:" + port;
	}

	@Override
	public int getMinButtonIndex() {
		return 0;
	}

	@Override
	public int getMaxButtonIndex() {
		return BUTTON_COUNT - 1;
	}

	@Override
	public int getAxisCount() {
		return AXIS_COUNT;
	}

	@Override
	public boolean isConnected() {
		return connected;
	}

	@Override
	public boolean canVibrate() {
		return false;
	}

	@Override
	public boolean isVibrating() {
		return false;
	}

	@Override
	public void startVibration(int duration, float strength) {
	}

	@Override
	public void cancelVibration() {
	}

	@Override
	public boolean supportsPlayerIndex() {
		return false;
	}

	@Override
	public int getPlayerIndex() {
		return PLAYER_IDX_UNSET;
	}

	@Override
	public void setPlayerIndex(int index) {
	}

	/**
	 * The ControllerMapping constructor is protected, so no mapping is provided.
	 * BMControllerInputProcessor.isHatPressed() already treats null as "no d-pad".
	 */
	@Override
	public ControllerMapping getMapping() {
		return null;
	}

	@Override
	public ControllerPowerLevel getPowerLevel() {
		return ControllerPowerLevel.POWER_UNKNOWN;
	}

	@Override
	public void addListener(ControllerListener listener) {
	}

	@Override
	public void removeListener(ControllerListener listener) {
	}
}

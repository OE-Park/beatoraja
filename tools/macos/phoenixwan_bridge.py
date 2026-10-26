#!/usr/bin/env python3
"""PHOENIXWAN USB bridge for macOS (run as root).

macOS 26 resets the PHOENIXWAN (Gamo2 IIDX controller) every ~0.65 s because its
joystick interrupt endpoint enumerates in the HALT state and the macOS HID driver never
clears it (it retries 10x then resets the device, which halts it again).  This daemon
captures the USB device with libusb (which detaches the macOS HID driver), clears the
endpoint halt, reads the raw 1000 Hz HID reports from the joystick interface and
forwards them to beatoraja over UDP.  Start it before the game:

    sudo python3 tools/macos/phoenixwan_bridge.py            # then run beatoraja with
    BEATORAJA_USB_BRIDGE=1 java -jar build/beatoraja.jar      # (same port, 27831)

Packet (12 bytes): b"PW1" + seq uint32 LE + raw report (5 bytes:
X=turntable 0..255 wrapping, Y, buttons lo, buttons hi, reserved).
Requires Homebrew libusb (brew install libusb).  Ctrl-C releases the device back to macOS.
"""
import argparse, ctypes, os, signal, socket, struct, sys, time

LIBUSB_CANDIDATES = ["/opt/homebrew/lib/libusb-1.0.dylib", "/usr/local/lib/libusb-1.0.dylib"]
DEVICES = [(0x1CCF, 0x8048, "Controller INF&BMS (INFINITAS/LR2/KEYBOARD/GAMEPAD modes)"),
           (0x034C, 0x0368, "Controller HID (HID-light mode)")]
JOY_INTERFACE, JOY_EP_IN, REPORT_LEN = 1, 0x81, 5
ERR_TIMEOUT, ERR_NO_DEVICE, ERR_PIPE = -7, -4, -9


def load_libusb():
    for p in LIBUSB_CANDIDATES:
        if os.path.exists(p):
            L = ctypes.CDLL(p)
            break
    else:
        sys.exit("libusb not found; run: brew install libusb")
    L.libusb_init.argtypes = [ctypes.c_void_p]
    L.libusb_open_device_with_vid_pid.restype = ctypes.c_void_p
    L.libusb_open_device_with_vid_pid.argtypes = [ctypes.c_void_p, ctypes.c_uint16, ctypes.c_uint16]
    for f in ("libusb_detach_kernel_driver", "libusb_attach_kernel_driver",
              "libusb_claim_interface", "libusb_release_interface"):
        getattr(L, f).argtypes = [ctypes.c_void_p, ctypes.c_int]
    L.libusb_clear_halt.argtypes = [ctypes.c_void_p, ctypes.c_ubyte]
    L.libusb_control_transfer.argtypes = [ctypes.c_void_p, ctypes.c_uint8, ctypes.c_uint8, ctypes.c_uint16,
                                          ctypes.c_uint16, ctypes.c_void_p, ctypes.c_uint16, ctypes.c_uint]
    L.libusb_interrupt_transfer.argtypes = [ctypes.c_void_p, ctypes.c_ubyte, ctypes.c_void_p, ctypes.c_int,
                                            ctypes.POINTER(ctypes.c_int), ctypes.c_uint]
    L.libusb_close.argtypes = [ctypes.c_void_p]
    L.libusb_error_name.restype = ctypes.c_char_p
    return L


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


class Bridge:
    def __init__(self, L, port, verbose):
        self.L, self.port, self.verbose = L, port, verbose
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.addr = ("127.0.0.1", port)
        self.seq = 0
        self.handle = None
        self.stop = False

    def err(self, r):
        return self.L.libusb_error_name(r).decode() if r < 0 else str(r)

    def open_any(self):
        for vid, pid, desc in DEVICES:
            h = self.L.libusb_open_device_with_vid_pid(None, vid, pid)
            if h:
                return h, "%04x:%04x %s" % (vid, pid, desc)
        return None, None

    def attach(self):
        """Capture the device, clear the halted joystick endpoint, claim the joystick interface."""
        while not self.stop:
            h, desc = self.open_any()
            if h:
                break
            time.sleep(0.25)
        if self.stop:
            return False
        self.handle = h
        log("device found: " + desc)
        r = self.L.libusb_detach_kernel_driver(h, 0)       # darwin: captures the whole device
        if r < 0:
            log("capture failed: %s (root required; is SIP/USB capture allowed?)" % self.err(r))
            return False
        time.sleep(0.3)
        r = self.L.libusb_claim_interface(h, JOY_INTERFACE)
        if r < 0:
            log("claim interface %d failed: %s" % (JOY_INTERFACE, self.err(r)))
            return False
        st = (ctypes.c_ubyte * 2)()
        self.L.libusb_control_transfer(h, 0x82, 0, 0, JOY_EP_IN, st, 2, 500)
        log("endpoint 0x%02x status before clear: %02x %02x" % (JOY_EP_IN, st[0], st[1]))
        r = self.L.libusb_clear_halt(h, JOY_EP_IN)
        log("clear_halt -> %s; forwarding reports to udp://127.0.0.1:%d" % (self.err(r), self.port))
        return True

    def detach(self, release_to_macos=True):
        h, self.handle = self.handle, None
        if not h:
            return
        self.L.libusb_release_interface(h, JOY_INTERFACE)
        if release_to_macos:
            r = self.L.libusb_attach_kernel_driver(h, 0)
            log("released device back to macOS (%s)" % self.err(r))
        self.L.libusb_close(h)

    def pump(self):
        """Read reports until the device goes away or we are told to stop."""
        buf = (ctypes.c_ubyte * 64)()
        got = ctypes.c_int(0)
        last = None
        pipe_errors = 0
        count, t_stat = 0, time.time()
        while not self.stop:
            r = self.L.libusb_interrupt_transfer(self.handle, JOY_EP_IN, buf, 64, ctypes.byref(got), 100)
            if r == 0:
                rep = bytes(buf[:got.value])[:REPORT_LEN].ljust(REPORT_LEN, b"\0")
                self.seq = (self.seq + 1) & 0xFFFFFFFF
                self.sock.sendto(b"PW1" + struct.pack("<I", self.seq) + rep, self.addr)
                count += 1
                pipe_errors = 0
                if self.verbose and rep != last:
                    print("  %s" % rep.hex(" "), flush=True)
                    last = rep
            elif r == ERR_TIMEOUT:
                continue
            elif r == ERR_NO_DEVICE:
                log("device disconnected")
                return
            elif r == ERR_PIPE:
                pipe_errors += 1
                if pipe_errors == 1:
                    log("endpoint stalled again; clearing halt")
                    self.L.libusb_clear_halt(self.handle, JOY_EP_IN)
                elif pipe_errors > 50:
                    log("endpoint keeps stalling; re-attaching")
                    return
            else:
                log("read error: " + self.err(r))
                time.sleep(0.05)
            if time.time() - t_stat >= 10:
                log("%d reports in the last 10 s" % count)
                count, t_stat = 0, time.time()

    def run(self):
        signal.signal(signal.SIGINT, lambda *_: setattr(self, "stop", True))
        signal.signal(signal.SIGTERM, lambda *_: setattr(self, "stop", True))
        while not self.stop:
            if not self.attach():
                self.detach(release_to_macos=False)
                time.sleep(1.0)
                continue
            self.pump()
            self.detach(release_to_macos=self.stop)
        log("bye")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=27831, help="UDP port beatoraja listens on (default 27831)")
    ap.add_argument("-v", "--verbose", action="store_true", help="print each changed report")
    a = ap.parse_args()
    if os.geteuid() != 0:
        sys.exit("root required (USB device capture): sudo python3 %s" % sys.argv[0])
    L = load_libusb()
    if L.libusb_init(None) != 0:
        sys.exit("libusb_init failed")
    Bridge(L, a.port, a.verbose).run()


if __name__ == "__main__":
    main()

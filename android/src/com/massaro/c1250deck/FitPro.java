package com.massaro.c1250deck;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The motor board over USB: ICON's "FitPro 1" protocol, as iFit (com.ifit.arda,
 * module glassos_sindarin_fitpro1) speaks it since the Oct 2026 reset removed the
 * separate GlassOS service.
 *
 * The board is a HID device, vendor 0x213c (ICON), product 2. Each message is one
 * USB transfer: [device][length][command][payload…][checksum], where length counts
 * every byte and checksum is the low byte of the sum of the bytes before it. A reply
 * has the same shape with a status byte at [3] (2 = done) and its data from [4].
 *
 * Startup, as iFit does it: clear the buffer (64 × 0xFF until the board echoes it
 * twice), DEVICE_INFO (which device answers, its software version, the fields it
 * supports), SYSTEM_INFO (model and part number), VERSION_INFO, then VERIFY_SECURITY
 * when the software version is over 75: a 32-byte key worked out from those numbers.
 *
 * Data moves with READ_WRITE_DATA: a write section and a read section, each a bitmask
 * of field ids (byte count, then the bytes) and, for writes, the values in id order.
 * Speed and incline are 2-byte little-endian hundredths (kph, percent); the workout
 * mode is one byte (1 idle, 2 running, 3 pause, 4 results, 8 safety key out).
 *
 * Only one app can hold the board, so iFit has to be stopped first. USB permission
 * comes from iFit's updater (com.ifit.eru, a system app), which grants it to any
 * package named in a com.ifit.eru.USB_PERMISSION_REQUEST broadcast; that sidesteps
 * Android's permission prompt, which this console hides.
 */
final class FitPro {
    static final int VENDOR_ICON = 0x213c;
    static final int PRODUCT_FITPRO1 = 2, PRODUCT_FITPRO2 = 3, PRODUCT_FITPRO2_FTDI = 4;

    static final int DEV_MAIN = 2;
    static final int CMD_READ_WRITE = 0x02, CMD_DEVICE_INFO = 0x81, CMD_SYSTEM_INFO = 0x82,
            CMD_VERSION_INFO = 0x84, CMD_VERIFY_SECURITY = 0x90;
    static final int STATUS_DONE = 2;

    // Field ids (BitField.kt) and their sizes in bytes.
    static final int F_KPH = 0, F_GRADE = 1, F_DISTANCE = 4, F_KEY = 7, F_RUN_TIME = 11, F_MODE = 12,
            F_ACTUAL_KPH = 16, F_ACTUAL_GRADE = 17, F_MAX_GRADE = 27, F_MIN_GRADE = 28,
            F_MAX_KPH = 30, F_MIN_KPH = 31, F_CALORIES = 13, F_IDLE_LOCKOUT = 95, F_START_REQUESTED = 96,
            F_REQUIRE_START = 108;
    static final String[] MODES = {"unknown", "idle", "running", "pause", "results", "debug", "log",
            "maintenance", "safety key out", "demo", "warm-up", "cool-down", "sleep", "resume", "locked"};

    static int size(int field) {
        switch (field) {
            case F_MODE: case F_IDLE_LOCKOUT: case F_START_REQUESTED: case F_REQUIRE_START: return 1;
            case F_DISTANCE: case F_RUN_TIME: case F_CALORIES: return 4;
            case F_KEY: return 14;
            default: return 2;
        }
    }

    final StringBuilder log = new StringBuilder();
    private final Context ctx;
    private final UsbManager usb;
    UsbDevice dev;
    private UsbDeviceConnection conn;
    private UsbInterface intf;
    private UsbEndpoint in, out;
    boolean logBytes = true;
    /** Set when open() failed because another app holds the board's interface. */
    boolean claimFailed;

    // From the startup reads.
    int device = 0, swVersion, hwVersion, serialBits, model, partNumber, masterVersion;
    final TreeSet<Integer> supported = new TreeSet<>();
    boolean unlocked;

    FitPro(Context ctx) {
        this.ctx = ctx;
        this.usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    }

    // ---- finding and opening the board ----

    UsbDevice find() {
        if (usb == null) return null;
        for (UsbDevice d : usb.getDeviceList().values())
            if (d.getVendorId() == VENDOR_ICON) return d;
        return null;
    }

    /** Ask iFit's updater for permission, then Android's own prompt if that doesn't come through. */
    boolean permission(UsbDevice d) throws InterruptedException {
        if (usb.hasPermission(d)) { note("USB permission: already granted"); return true; }
        Intent i = new Intent("com.ifit.eru.USB_PERMISSION_REQUEST");
        i.setPackage("com.ifit.eru");
        i.putExtra("device", d);
        i.putExtra("packageName", ctx.getPackageName());
        ctx.sendBroadcast(i);
        note("USB permission: asked iFit's updater (com.ifit.eru)…");
        for (int t = 0; t < 30 && !usb.hasPermission(d); t++) Thread.sleep(100);
        if (usb.hasPermission(d)) { note("USB permission: granted by com.ifit.eru"); return true; }
        note("USB permission: no answer from com.ifit.eru; trying Android's prompt (it may be hidden)…");
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent("com.massaro.c1250deck.USB_PERMISSION")
                .setPackage(ctx.getPackageName()), PendingIntent.FLAG_IMMUTABLE);
        usb.requestPermission(d, pi);
        for (int t = 0; t < 100 && !usb.hasPermission(d); t++) Thread.sleep(100);
        boolean ok = usb.hasPermission(d);
        note(ok ? "USB permission: granted at the prompt" : "USB permission: not granted");
        return ok;
    }

    /** Open and claim the board. Returns null on success, or the reason it couldn't. */
    String open() throws InterruptedException {
        claimFailed = false;
        dev = find();
        if (dev == null) return "No ICON motor board (USB 213c) is attached.";
        note(String.format(Locale.US, "Board: %s  %04x:%04x  %d interface(s)", dev.getDeviceName(),
                dev.getVendorId(), dev.getProductId(), dev.getInterfaceCount()));
        if (dev.getProductId() != PRODUCT_FITPRO1)
            return "This board speaks FitPro 2 (product " + dev.getProductId() + "); only FitPro 1 is written so far.";
        if (dev.getInterfaceCount() < 1) return "The board shows no USB interface.";
        if (!permission(dev)) return "No permission to use the motor board.";
        conn = usb.openDevice(dev);
        if (conn == null) return "Android wouldn't open the motor board.";
        intf = dev.getInterface(0);
        for (int e = 0; e < intf.getEndpointCount(); e++) {
            UsbEndpoint ep = intf.getEndpoint(e);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN && in == null) in = ep;
            else if (ep.getDirection() == UsbConstants.USB_DIR_OUT && out == null) out = ep;
        }
        note(String.format(Locale.US, "Interface 0: class %d, %d endpoint(s); in %s, out %s", intf.getInterfaceClass(),
                intf.getEndpointCount(), describe(in), describe(out)));
        if (in == null || out == null) { close(); return "The board's interface is missing an endpoint."; }
        if (!conn.claimInterface(intf, true)) {
            close();
            claimFailed = true;
            return "Another app is holding the motor board — almost certainly iFit. Stop iFit first "
                    + "(Android Settings → Apps → iFit → Force stop), then run the test again.";
        }
        note("Claimed the board.");
        return null;
    }

    void close() {
        try { if (conn != null && intf != null) conn.releaseInterface(intf); } catch (Exception ignored) {}
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        conn = null; intf = null; in = null; out = null;
    }

    private static String describe(UsbEndpoint ep) {
        if (ep == null) return "none";
        String type = ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT ? "interrupt"
                : ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK ? "bulk" : "type " + ep.getType();
        return String.format(Locale.US, "0x%02x %s %dB", ep.getAddress(), type, ep.getMaxPacketSize());
    }

    // ---- the wire ----

    static byte[] packet(int device, int cmd, byte[] payload) {
        int len = payload.length + 4;
        byte[] p = new byte[len];
        p[0] = (byte) device;
        p[1] = (byte) len;
        p[2] = (byte) cmd;
        System.arraycopy(payload, 0, p, 3, payload.length);
        p[len - 1] = checksum(p, len - 1);
        return p;
    }

    static byte checksum(byte[] b, int n) {
        int s = 0;
        for (int i = 0; i < n; i++) s += b[i] & 0xff;
        return (byte) s;
    }

    /** A reply to cmd: non-zero device, sane length, matching command, good checksum. */
    static boolean valid(byte[] r, int n, int cmd) {
        if (n < 3 || r[0] == 0) return false;
        int len = r[1];
        if (len < 3 || len > 64 || len > n) return false;
        if ((r[2] & 0xff) != cmd) return false;
        return r[len - 1] == checksum(r, len - 1);
    }

    private int write(byte[] b) { return conn.bulkTransfer(out, b, b.length, 100); }

    private int read(byte[] b) { return conn.bulkTransfer(in, b, b.length, 100); }

    /** Send one message and wait for its reply (up to 10 tries, as iFit does). */
    byte[] send(int device, int cmd, byte[] payload) throws Exception {
        byte[] p = packet(device, cmd, payload);
        if (logBytes) note("→ " + hex(p, p.length));
        byte[] r = new byte[64];
        for (int attempt = 0; attempt < 10; attempt++) {
            if (write(p) < 0) { Thread.sleep(50); continue; }
            for (int k = 0; k < 5; k++) {
                int n = read(r);
                if (n > 0 && valid(r, n, cmd)) {
                    byte[] reply = new byte[r[1]];
                    System.arraycopy(r, 0, reply, 0, reply.length);
                    if (logBytes) note("← " + hex(reply, reply.length));
                    return reply;
                }
            }
            Thread.sleep(50);
        }
        throw new Exception(String.format(Locale.US, "No reply to command 0x%02x after 10 tries", cmd));
    }

    /** iFit's clearBuffer: send 64 × 0xFF until the board echoes it (byte 3 aside) twice running. */
    boolean clearBuffer() throws InterruptedException {
        byte[] ff = new byte[64];
        java.util.Arrays.fill(ff, (byte) 0xff);
        byte[] r = new byte[64];
        int good = 0;
        for (int tries = 0; tries < 10 && good < 2; tries++) {
            read(r);                      // discard whatever is waiting
            if (write(ff) < 0) { Thread.sleep(500); continue; }
            int n = read(r);
            boolean echo = n == 64;
            for (int i = 0; echo && i < 64; i++) if (i != 3 && r[i] != (byte) 0xff) echo = false;
            good = echo ? good + 1 : 0;
            if (good < 2) Thread.sleep(500);
        }
        note(good >= 2 ? "Buffer cleared." : "Buffer clear didn't echo; carrying on.");
        return good >= 2;
    }

    // ---- startup ----

    void startup() throws Exception {
        supported.clear();
        clearBuffer();

        byte[] r = send(DEV_MAIN, CMD_DEVICE_INFO, new byte[0]);
        status(r, "DEVICE_INFO");
        device = r[0];
        swVersion = r[4];
        hwVersion = r[5];
        serialBits = r[6] + (r[7] << 8) + (r[8] << 16) + (r[9] << 24);   // signed bytes, as iFit adds them
        int maskLen = r.length > 12 ? r[12] : 0;
        for (int i = 0; i < maskLen && 13 + i < r.length - 1; i++)
            for (int b = 0; b < 8; b++) if ((r[13 + i] & (1 << b)) != 0) supported.add(i * 8 + b);
        note(String.format(Locale.US, "Device %d (%s), software %d, hardware %d, %d fields supported",
                device, device == 4 ? "treadmill" : device == 5 ? "incline trainer" : "?", swVersion, hwVersion, supported.size()));

        r = send(DEV_MAIN, CMD_SYSTEM_INFO, new byte[]{0, 0});
        status(r, "SYSTEM_INFO");
        model = le32(r, 7);
        partNumber = le32(r, 11);
        if (partNumber == 370357 && model == 39915) partNumber = 374677;   // iFit's own fix-up
        note("Model " + model + ", part number " + partNumber);

        r = send(DEV_MAIN, CMD_VERSION_INFO, new byte[]{0, 0});
        status(r, "VERSION_INFO");
        masterVersion = r[4];
        note("Master library version " + masterVersion);

        if (swVersion > 75) {
            r = send(device, CMD_VERIFY_SECURITY, securityPayload());
            unlocked = r.length > 3 && r[3] == STATUS_DONE;
            note(unlocked ? "Security: unlocked." : "Security: not unlocked (status " + r[3] + ").");
        } else {
            unlocked = true;
            note("Security: not needed (software " + swVersion + ").");
        }
    }

    /** FitPro1ConsoleImpl.unlock: 32 key bytes from the board's numbers, then (version × 8) little-endian. */
    byte[] securityPayload() {
        byte[] p = new byte[36];
        for (int i = 0; i < 32; i++) {
            byte b = (byte) (i + 1);
            if (((serialBits >> i) & 1) == 1) {
                int pn = partNumber;
                if (i < 16) p[i] = (byte) (((byte) (((pn << 16) | (pn >> 16)) >> i)) ^ b);
                else p[i] = (byte) (((byte) (pn >> i)) ^ b);
            } else {
                p[i] = (byte) (((byte) ((model + i) * b)) ^ b);
            }
        }
        int v = masterVersion * 8;
        p[32] = (byte) v; p[33] = (byte) (v >> 8); p[34] = (byte) (v >> 16); p[35] = (byte) (v >> 24);
        return p;
    }

    // ---- data ----

    /** One READ_WRITE_DATA exchange. writes maps field → value bytes; reads lists fields. Returns field → bytes. */
    Map<Integer, byte[]> readWrite(Map<Integer, byte[]> writes, List<Integer> reads) throws Exception {
        TreeMap<Integer, byte[]> w = new TreeMap<>(writes);
        List<Integer> rd = new ArrayList<>(new TreeSet<>(reads));
        byte[] ws = section(new ArrayList<>(w.keySet()), w);
        byte[] rs = section(rd, null);
        byte[] payload = new byte[ws.length + rs.length];
        System.arraycopy(ws, 0, payload, 0, ws.length);
        System.arraycopy(rs, 0, payload, ws.length, rs.length);
        byte[] r = send(device, CMD_READ_WRITE, payload);
        status(r, "READ_WRITE_DATA");
        Map<Integer, byte[]> got = new TreeMap<>();
        int pos = 4;
        for (int f : rd) {
            int s = size(f);
            if (pos + s > r.length - 1) break;
            byte[] v = new byte[s];
            System.arraycopy(r, pos, v, 0, s);
            got.put(f, v);
            pos += s;
        }
        return got;
    }

    private static byte[] section(List<Integer> fields, Map<Integer, byte[]> values) {
        if (fields.isEmpty()) return new byte[]{0};
        int maskLen = Collections.max(fields) / 8 + 1;
        int valueLen = 0;
        if (values != null) for (byte[] v : values.values()) valueLen += v.length;
        byte[] s = new byte[1 + maskLen + valueLen];
        s[0] = (byte) maskLen;
        for (int f : fields) s[1 + f / 8] |= (byte) (1 << (f % 8));
        int pos = 1 + maskLen;
        if (values != null) for (int f : fields) { byte[] v = values.get(f); System.arraycopy(v, 0, s, pos, v.length); pos += v.length; }
        return s;
    }

    static double hundredths(byte[] v) { return (short) ((v[0] & 0xff) | (v[1] << 8)) / 100.0; }

    static byte[] hundredths(double x) { int v = (int) Math.round(x * 100); return new byte[]{(byte) v, (byte) (v >> 8)}; }

    static int le32(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    private void status(byte[] r, String what) throws Exception {
        if (r.length < 4 || r[3] != STATUS_DONE) {
            String[] names = {"device not supported", "command not supported", "done", "in progress", "failed",
                    "time left", "?", "unknown failure", "security block", "comm failed"};
            int s = r.length > 3 ? r[3] : -1;
            throw new Exception(what + " answered status " + s + (s >= 0 && s < names.length ? " (" + names[s] + ")" : ""));
        }
    }

    void note(String s) { log.append(s).append('\n'); }

    static String hex(byte[] b, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(String.format(Locale.US, "%02x ", b[i] & 0xff));
        return s.toString().trim();
    }

    // ---- the read-only test (1.7.0) ----

    /** Connect, run iFit's startup, read speed, incline and limits five times, then let go. Moves nothing. */
    static String readOnlyTest(Context ctx) {
        FitPro fp = new FitPro(ctx);
        fp.note("Motor link test (read only: nothing is sent that moves the belt)");
        fp.note("");
        try {
            String why = fp.open();
            if (why != null) { fp.note(""); fp.note(why); return fp.log.toString(); }
            fp.startup();
            List<Integer> want = new ArrayList<>();
            for (int f : new int[]{F_KPH, F_GRADE, F_MODE, F_ACTUAL_KPH, F_ACTUAL_GRADE, F_MAX_GRADE, F_MIN_GRADE,
                    F_MAX_KPH, F_MIN_KPH, F_DISTANCE, F_RUN_TIME})
                if (fp.supported.contains(f)) want.add(f);
            fp.note("Supported fields: " + fp.supported);
            fp.note("Reading: " + want);
            fp.note("");
            Map<Integer, byte[]> none = Collections.emptyMap();
            for (int i = 0; i < 5; i++) {
                if (i == 2) fp.logBytes = false;
                Map<Integer, byte[]> v = fp.readWrite(none, want);
                fp.note(summary(v));
                Thread.sleep(300);
            }
        } catch (Throwable t) {
            fp.note("");
            fp.note("Stopped: " + t.getMessage());
        } finally {
            fp.close();
            fp.note("");
            fp.note("Released the board. Reopen iFit to give it back.");
        }
        return fp.log.toString();
    }

    static String summary(Map<Integer, byte[]> v) {
        StringBuilder s = new StringBuilder();
        if (v.containsKey(F_MODE)) {
            int m = v.get(F_MODE)[0];
            s.append("mode ").append(m >= 0 && m < MODES.length ? MODES[m] : String.valueOf(m)).append(" · ");
        }
        if (v.containsKey(F_KPH)) s.append(String.format(Locale.US, "set %.2f kph · ", hundredths(v.get(F_KPH))));
        if (v.containsKey(F_ACTUAL_KPH)) s.append(String.format(Locale.US, "belt %.2f kph · ", hundredths(v.get(F_ACTUAL_KPH))));
        if (v.containsKey(F_GRADE)) s.append(String.format(Locale.US, "set %.1f%% · ", hundredths(v.get(F_GRADE))));
        if (v.containsKey(F_ACTUAL_GRADE)) s.append(String.format(Locale.US, "deck %.1f%% · ", hundredths(v.get(F_ACTUAL_GRADE))));
        if (v.containsKey(F_MIN_KPH) && v.containsKey(F_MAX_KPH))
            s.append(String.format(Locale.US, "speed %.1f–%.1f kph · ", hundredths(v.get(F_MIN_KPH)), hundredths(v.get(F_MAX_KPH))));
        if (v.containsKey(F_MIN_GRADE) && v.containsKey(F_MAX_GRADE))
            s.append(String.format(Locale.US, "incline %.0f to %.0f%% · ", hundredths(v.get(F_MIN_GRADE)), hundredths(v.get(F_MAX_GRADE))));
        if (v.containsKey(F_DISTANCE)) s.append("distance ").append(le32(v.get(F_DISTANCE), 0)).append(" · ");
        if (v.containsKey(F_RUN_TIME)) s.append("time ").append(le32(v.get(F_RUN_TIME), 0)).append(" s · ");
        String out = s.toString();
        return out.endsWith(" · ") ? out.substring(0, out.length() - 3) : out;
    }
}

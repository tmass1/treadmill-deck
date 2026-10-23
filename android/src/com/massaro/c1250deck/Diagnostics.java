package com.massaro.c1250deck;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds out how iFit reaches the motor on a console with no GlassOS service, so C1250 Deck can
 * do the same. Read-only: it lists hardware the app can see and searches the iFit apps' code
 * for the strings that give a transport away (serial device paths, USB, local ports, Bluetooth).
 * It can also serve the iFit apps' APK files on the local network, so they can be studied on a Mac.
 */
final class Diagnostics {
    private Diagnostics() {}

    private static final String[] TERMS = {
            "/dev/tty", "/dev/hidraw", "/dev/usb", "ttyMT", "ttyS", "ttyUSB", "ttyACM", "SerialPort", "serialport",
            "UsbManager", "UsbDeviceConnection", "bulkTransfer", "controlTransfer",
            "BluetoothGatt", "localhost:", "127.0.0.1", "grpc", "glassos", "GlassOS", "FitPro", "fitpro",
            "Brainboard", "brainboard", "motorController", "MotorController", "mcu", "MCU", "baudRate", "BaudRate"
    };
    // Concrete strings worth showing in full: device paths, local URLs, and class names about the hardware link.
    private static final Pattern SHOW = Pattern.compile(
            "/dev/[A-Za-z0-9_/.-]{2,40}"
            + "|(?:localhost|127\\.0\\.0\\.1):\\d{2,5}"
            + "|L[a-z0-9_/$]*/[A-Za-z0-9_$]*(?:Serial|Usb|FitPro|Fitpro|Brainboard|Motor|Mcu|Grpc|GlassOs|Glassos|Hardware|Uart)[A-Za-z0-9_$]*;");

    static String report(Context ctx) {
        StringBuilder r = new StringBuilder();
        r.append("C1250 Deck diagnostics\n\n");

        // ---- USB devices the app can see (vendor:product, class)
        r.append("USB devices:\n");
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            Map<String, UsbDevice> devs = um == null ? Collections.emptyMap() : um.getDeviceList();
            if (devs.isEmpty()) r.append("  none\n");
            for (UsbDevice d : devs.values()) {
                r.append(String.format("  %s  %04x:%04x  class %d  %d interface(s)", d.getDeviceName(),
                        d.getVendorId(), d.getProductId(), d.getDeviceClass(), d.getInterfaceCount()));
                for (int i = 0; i < d.getInterfaceCount(); i++) r.append(i == 0 ? "  [" : ", ").append(d.getInterface(i).getInterfaceClass());
                r.append(d.getInterfaceCount() > 0 ? "]\n" : "\n");
            }
        } catch (Exception e) { r.append("  error: ").append(e).append('\n'); }

        // ---- serial-looking device nodes
        r.append("\nDevice nodes:\n");
        try {
            String[] names = new File("/dev").list();
            List<String> hits = new ArrayList<>();
            if (names != null) {
                Arrays.sort(names);
                for (String n : names) {
                    if (!(n.startsWith("tty") && !n.matches("tty\\d*")) && !n.startsWith("hidraw") && !n.startsWith("ttyGS")) continue;
                    File f = new File("/dev", n);
                    hits.add(n + (f.canRead() ? " r" : "") + (f.canWrite() ? "w" : ""));
                }
            }
            r.append("  ").append(hits.isEmpty() ? "none visible" : String.join(", ", hits)).append('\n');
        } catch (Exception e) { r.append("  error: ").append(e).append('\n'); }

        // ---- local ports
        r.append("\nLocal ports:\n  ");
        int[] ports = {54321, 54322, 54320, 50051, 50052, 8080, 8081, 8888, 9090, 9000, 5000, 3000, 4000, 7000, 7777, 12345, 31337};
        List<String> open = new ArrayList<>();
        for (int p : ports) {
            try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", p), 250); open.add(String.valueOf(p)); }
            catch (Exception ignored) {}
        }
        r.append(open.isEmpty() ? "none of " + ports.length + " checked are open" : "open: " + String.join(", ", open)).append('\n');

        // ---- the iFit apps: version, native libraries, and what their code mentions
        PackageManager pm = ctx.getPackageManager();
        for (ApplicationInfo ai : ifitApps(ctx)) {
            String v = "?";
            try { PackageInfo pi = pm.getPackageInfo(ai.packageName, 0); v = pi.versionName + " (" + pi.getLongVersionCode() + ")"; }
            catch (Exception ignored) {}
            r.append("\n").append(ai.packageName).append(' ').append(v).append(":\n");
            List<String> apks = apks(ai);
            Map<String, Integer> counts = new LinkedHashMap<>();
            Set<String> shown = new LinkedHashSet<>();
            Set<String> libs = new LinkedHashSet<>();
            long dexBytes = 0;
            for (String apk : apks) {
                try (ZipFile zip = new ZipFile(apk)) {
                    Enumeration<? extends ZipEntry> en = zip.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        String n = e.getName();
                        if (n.startsWith("lib/") && n.endsWith(".so")) libs.add(n.substring(n.lastIndexOf('/') + 1));
                        if (!(n.endsWith(".dex") && !n.contains("/"))) continue;
                        dexBytes += e.getSize();
                        scanDex(zip, e, counts, shown);
                    }
                } catch (Exception ex) { r.append("  can't read ").append(apk).append(": ").append(ex).append('\n'); }
            }
            File nat = ai.nativeLibraryDir == null ? null : new File(ai.nativeLibraryDir);
            if (nat != null && nat.isDirectory() && nat.list() != null) libs.addAll(Arrays.asList(nat.list()));
            r.append("  code: ").append(dexBytes / 1024).append(" KB of dex in ").append(apks.size()).append(" APK(s)\n");
            r.append("  native libs: ").append(libs.isEmpty() ? "none" : String.join(", ", libs)).append('\n');
            StringBuilder c = new StringBuilder();
            for (Map.Entry<String, Integer> m : counts.entrySet()) c.append(c.length() == 0 ? "" : ", ").append(m.getKey()).append(" ×").append(m.getValue());
            r.append("  mentions: ").append(c.length() == 0 ? "none of the hardware terms" : c).append('\n');
            int i = 0;
            for (String s : shown) { if (i++ >= 40) { r.append("  …\n"); break; } r.append("  · ").append(s).append('\n'); }
        }
        return r.toString();
    }

    private static void scanDex(ZipFile zip, ZipEntry e, Map<String, Integer> counts, Set<String> shown) throws Exception {
        byte[] buf = new byte[1 << 20];
        String carry = "";
        try (InputStream in = zip.getInputStream(e)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                String chunk = carry + new String(buf, 0, n, "ISO-8859-1");
                for (String t : TERMS) {
                    int from = 0, k;
                    while ((k = chunk.indexOf(t, from)) >= 0) {
                        if (k >= carry.length() - t.length() + 1 || carry.isEmpty()) counts.merge(t, 1, Integer::sum);
                        from = k + t.length();
                    }
                }
                Matcher m = SHOW.matcher(chunk);
                while (m.find() && shown.size() < 200) {
                    String g = m.group();
                    if (g.endsWith(";")) {                         // a class: Lcom/ifit/Foo; -> com.ifit.Foo
                        if (g.startsWith("Ljava/") || g.contains("Serializable")) continue;
                        g = g.substring(1, g.length() - 1).replace('/', '.');
                    }
                    shown.add(g);
                }
                carry = chunk.length() > 96 ? chunk.substring(chunk.length() - 96) : chunk;
            }
        }
    }

    static List<ApplicationInfo> ifitApps(Context ctx) {
        List<ApplicationInfo> out = new ArrayList<>();
        try {
            for (ApplicationInfo a : ctx.getPackageManager().getInstalledApplications(0)) {
                String p = a.packageName.toLowerCase();
                if (p.contains("ifit") || p.contains("glass") || p.contains("nordictrack") || p.contains("icon")) out.add(a);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static List<String> apks(ApplicationInfo ai) {
        List<String> l = new ArrayList<>();
        if (ai.sourceDir != null) l.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) l.addAll(Arrays.asList(ai.splitSourceDirs));
        return l;
    }

    // ---- share the iFit APKs on the local network, while the Diagnostics sheet is open ----
    private static ServerSocket server;

    static synchronized String startShare(Context ctx) {
        stopShare();
        try {
            server = new ServerSocket(8765);
        } catch (Exception e) { return ""; }
        final ServerSocket ss = server;
        final Map<String, String> files = new LinkedHashMap<>();
        for (ApplicationInfo ai : ifitApps(ctx)) {
            List<String> l = apks(ai);
            for (int i = 0; i < l.size(); i++) files.put(ai.packageName + (i == 0 ? "" : "-split" + i) + ".apk", l.get(i));
        }
        // One thread per connection: browsers open spare connections that send nothing,
        // and serving them in turn left the real request waiting (a blank page in 1.6.3).
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    Thread w = new Thread(() -> {
                        try { serve(s, files); } catch (Exception ignored) {}
                        finally { try { s.close(); } catch (Exception ignored) {} }
                    }, "deck-share-conn");
                    w.setDaemon(true);
                    w.start();
                } catch (Exception ignored) {}
            }
        }, "deck-share");
        t.setDaemon(true);
        t.start();
        return "http://" + lanIp() + ":8765/";
    }

    static synchronized void stopShare() {
        if (server != null) { try { server.close(); } catch (Exception ignored) {} server = null; }
    }

    private static void serve(Socket s, Map<String, String> files) throws Exception {
        s.setSoTimeout(30000);
        InputStream in = s.getInputStream();
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') line.append((char) c);
        if (line.length() == 0) return;
        // Read the rest of the request too: closing with unread headers makes the
        // socket send a reset, and the browser can throw away the reply.
        int nl = 1;
        while ((c = in.read()) != -1) {
            if (c == '\n') { if (++nl == 2) break; }
            else if (c != '\r') nl = 0;
        }
        String[] parts = line.toString().trim().split(" ");
        String path = parts.length > 1 ? parts[1] : "/";
        String name = path.startsWith("/") ? path.substring(1) : path;
        OutputStream out = s.getOutputStream();
        String file = files.get(name);
        if (file == null) {
            StringBuilder html = new StringBuilder("<!doctype html><meta charset=utf-8><title>iFit apps</title><h1>iFit apps on this console</h1><ul>");
            for (Map.Entry<String, String> f : files.entrySet())
                html.append("<li><a href=\"").append(f.getKey()).append("\">").append(f.getKey()).append("</a> (")
                    .append(new File(f.getValue()).length() / 1048576).append(" MB)</li>");
            html.append("</ul>");
            byte[] b = html.toString().getBytes("UTF-8");
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: " + b.length + "\r\n\r\n").getBytes("UTF-8"));
            out.write(b);
            out.flush();
            s.shutdownOutput();
            return;
        }
        File f = new File(file);
        out.write(("HTTP/1.0 200 OK\r\nConnection: close\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: " + f.length()
                + "\r\nContent-Disposition: attachment; filename=\"" + name + "\"\r\n\r\n").getBytes("UTF-8"));
        try (FileInputStream fi = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = fi.read(buf)) > 0) out.write(buf, 0, r);
        }
        out.flush();
        s.shutdownOutput();
    }

    static String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) {}
        return "the-treadmill";
    }
}

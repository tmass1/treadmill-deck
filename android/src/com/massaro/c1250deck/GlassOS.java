package com.massaro.c1250deck;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Client for the treadmill's own GlassOS hardware service (gRPC over HTTP/2 with mutual TLS
 * on localhost:54321). This is the service the iFit app uses to drive the belt and incline.
 *
 * Adapted from GrpcController in nordictrack-ifit-bypass
 * (https://github.com/mattwissler-AE/nordictrack-ifit-bypass), MIT License,
 * Copyright (c) 2026 Matt Wissler. See assets/NOTICE.txt.
 *
 * The TLS certificates are not shipped in this app. The install script copies them from the
 * treadmill's own GlassOS service into this app's files folder (files/certs/); if it didn't,
 * the app copies them from the GlassOS service APK itself on first launch.
 */
public class GlassOS {
    private static final String BASE_URL = "https://localhost:54321";
    private static final String CLIENT_ID = "com.ifit.wolf";
    private static final MediaType GRPC = MediaType.parse("application/grpc");

    private static final String CONSOLE = "com.ifit.glassos.ConsoleService";
    private static final String WORKOUT = "com.ifit.glassos.WorkoutService";
    private static final String SPEED = "com.ifit.glassos.SpeedService";
    private static final String INCLINE = "com.ifit.glassos.InclineService";
    private static final String DISTANCE = "com.ifit.glassos.DistanceService";
    private static final String ELAPSED = "com.ifit.glassos.ElapsedTimeService";
    private static final String CALORIES = "com.ifit.glassos.CaloriesBurnedService";

    public static final int WK_IDLE = 1, WK_RUNNING = 3, WK_PAUSED = 4, WK_RESULTS = 5;

    /** Thrown when the certificates have not been copied onto the device yet. */
    public static class CertsMissing extends Exception {
        CertsMissing(String m) { super(m); }
    }

    private OkHttpClient client;

    public boolean ready() { return client != null; }

    public void init(Context ctx) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate ca = (X509Certificate) cf.generateCertificate(open(ctx, "glassos_ca.pem"));
        X509Certificate cert = (X509Certificate) cf.generateCertificate(open(ctx, "glassos_client.pem"));
        String pem = new String(readAll(open(ctx, "glassos_client_key.pem")), "UTF-8");
        String b64 = pem.replaceAll("-----(BEGIN|END) (RSA )?PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));

        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("glassos-ca", ca);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);

        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
        keys.load(null, null);
        keys.setKeyEntry("client", key, new char[0], new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, new char[0]);

        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        client = new OkHttpClient.Builder()
                .sslSocketFactory(ssl.getSocketFactory(), (X509TrustManager) tmf.getTrustManagers()[0])
                .hostnameVerifier((h, s) -> "localhost".equals(h))
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .build();
    }

    private static final String GLASSOS_PKG = "com.ifit.glassos_service";
    private static final String[] CERT_NAMES = { "glassos_ca.pem", "glassos_client.pem", "glassos_client_key.pem" };

    /** What the last search of the treadmill's iFit packages found, shown when certs are missing. */
    private static String scanReport = "";
    private static boolean scanned = false;

    /**
     * Certs come from files/certs/ (put there by the install script or copied by this app),
     * else from bundled assets/certs/. If none are found, they are copied from the treadmill's
     * own iFit packages (GlassOS service first), which any app can read on this Android version.
     * This lets the app be installed without ADB.
     */
    private static InputStream open(Context ctx, String name) throws Exception {
        InputStream in = find(ctx, name);
        if (in != null) return in;
        if (!scanned) { scanned = true; copyFromIfit(ctx); }
        in = find(ctx, name);
        if (in != null) return in;
        throw new CertsMissing(name + " not found. " + probe(ctx) + scanReport);
    }

    /**
     * Live checks, run every time setup fails: is anything listening on the GlassOS port, and which
     * local TCP ports are open and by which app (Android 9 still lets apps read /proc/net/tcp).
     */
    private static String probe(Context ctx) {
        StringBuilder r = new StringBuilder();
        try (Socket so = new Socket()) {
            so.connect(new InetSocketAddress("127.0.0.1", 54321), 800);
            r.append("Port 54321: open (something is listening). ");
        } catch (Exception e) {
            r.append("Port 54321: closed. ");
        }
        java.util.List<String> ports = new java.util.ArrayList<>();
        for (String f : new String[]{ "/proc/net/tcp", "/proc/net/tcp6" }) {
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String line = br.readLine();
                while ((line = br.readLine()) != null && ports.size() < 10) {
                    String[] c = line.trim().split("\\s+");
                    if (c.length < 8 || !"0A".equals(c[3])) continue;   // 0A = LISTEN
                    int port = Integer.parseInt(c[1].substring(c[1].indexOf(':') + 1), 16);
                    int uid = Integer.parseInt(c[7]);
                    String who = ctx.getPackageManager().getNameForUid(uid);
                    String e = port + " (" + (who == null ? "uid " + uid : who.replace("com.ifit.", "")) + ")";
                    if (!ports.contains(e)) ports.add(e);
                }
            } catch (Exception ignored) {}
        }
        r.append("Listening: ").append(ports.isEmpty() ? "none visible" : String.join(", ", ports)).append(". ");
        return r.toString();
    }

    private static InputStream find(Context ctx, String name) throws Exception {
        File ext = ctx.getExternalFilesDir(null);
        File[] dirs = { ext == null ? null : new File(ext, "certs"), new File(ctx.getFilesDir(), "certs") };
        for (File d : dirs) {
            if (d != null) {
                File f = new File(d, name);
                if (f.isFile() && f.length() > 0) return new FileInputStream(f);
            }
        }
        try {
            return ctx.getAssets().open("certs/" + name);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    private static void copyFromIfit(Context ctx) {
        StringBuilder rep = new StringBuilder();
        java.util.List<ApplicationInfo> pkgs = new java.util.ArrayList<>();
        try {
            pkgs.add(ctx.getPackageManager().getApplicationInfo(GLASSOS_PKG, 0));
        } catch (Exception e) {
            rep.append(GLASSOS_PKG).append(" not installed. ");
        }
        try {
            for (ApplicationInfo a : ctx.getPackageManager().getInstalledApplications(0)) {
                String p = a.packageName.toLowerCase();
                if ((p.contains("ifit") || p.contains("glass")) && !a.packageName.equals(GLASSOS_PKG)) pkgs.add(a);
            }
        } catch (Exception ignored) {}
        File out = new File(ctx.getFilesDir(), "certs");
        out.mkdirs();
        java.util.List<String> hints = new java.util.ArrayList<>();
        int scannedFiles = 0, certLike = 0;
        for (ApplicationInfo ai : pkgs) {
            java.util.List<String> apks = new java.util.ArrayList<>();
            if (ai.sourceDir != null) apks.add(ai.sourceDir);
            if (ai.splitSourceDirs != null) apks.addAll(Arrays.asList(ai.splitSourceDirs));
            int found = 0;
            for (String apk : apks) {
                try (ZipFile zip = new ZipFile(apk)) {
                    java.util.Enumeration<? extends ZipEntry> en = zip.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        String n = e.getName();
                        String base = n.substring(n.lastIndexOf('/') + 1);
                        String lb = base.toLowerCase();
                        scannedFiles++;
                        boolean certName = lb.endsWith(".pem") || lb.endsWith(".crt") || lb.endsWith(".cer") || lb.endsWith(".der")
                                || lb.endsWith(".key") || lb.endsWith(".p12") || lb.endsWith(".pfx") || lb.endsWith(".bks")
                                || lb.endsWith(".jks") || lb.endsWith(".keystore") || lb.contains("glassos") || lb.contains("cert");
                        boolean pem = false;
                        if (!certName && !e.isDirectory() && e.getSize() > 0 && e.getSize() < 131072
                                && (n.startsWith("assets/") || n.startsWith("res/raw/"))) {
                            try (InputStream src = zip.getInputStream(e)) { pem = new String(readAll(src), "ISO-8859-1").contains("-----BEGIN"); }
                            catch (Exception ignored) {}
                        }
                        if ((certName || pem) && !n.startsWith("META-INF/")) {
                            certLike++;
                            if (hints.size() < 10) hints.add(ai.packageName.replace("com.ifit.", "") + ":" + n + (pem ? " (PEM)" : ""));
                        }
                        for (String want : CERT_NAMES) {
                            File dst = new File(out, want);
                            if (!base.equals(want) || (dst.isFile() && dst.length() > 0)) continue;
                            try (InputStream src = zip.getInputStream(e); FileOutputStream fo = new FileOutputStream(dst)) {
                                byte[] buf = new byte[8192];
                                int r;
                                while ((r = src.read(buf)) > 0) fo.write(buf, 0, r);
                            }
                            found++;
                        }
                    }
                } catch (Exception ex) {
                    rep.append("can't read ").append(ai.packageName).append(" (").append(ex.getClass().getSimpleName()).append("). ");
                }
            }
            if (found > 0) rep.append(ai.packageName).append(": ").append(found).append(" copied. ");
        }
        StringBuilder names = new StringBuilder();
        java.util.List<String> svcs = new java.util.ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        for (ApplicationInfo ai : pkgs) {
            String v = "?";
            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_SERVICES);
                v = pi.versionName;
                if (pi.services != null) for (ServiceInfo si : pi.services) {
                    String c = si.name.toLowerCase();
                    if ((c.contains("glass") || c.contains("grpc") || c.contains("hardware") || c.contains("fitpro")
                            || c.contains("machine") || c.contains("console") || c.contains("motor")) && svcs.size() < 8)
                        svcs.add(ai.packageName.replace("com.ifit.", "") + ":" + si.name.substring(si.name.lastIndexOf('.') + 1));
                }
            } catch (Exception ignored) {}
            names.append(names.length() == 0 ? "" : ", ").append(ai.packageName.replace("com.ifit.", "")).append(' ').append(v);
        }
        rep.append("iFit packages: ").append(names.length() == 0 ? "none" : names).append(". ");
        rep.append("Hardware-like services: ").append(svcs.isEmpty() ? "none" : String.join(", ", svcs)).append(". ");
        rep.append("Cert-like files: ").append(certLike == 0 ? "none" : certLike + " (" + String.join(", ", hints) + ")")
           .append(" in ").append(scannedFiles).append(" files.");
        scanReport = rep.toString();
    }

    // ---- console ----
    public boolean connect() throws Exception { return varint(call(CONSOLE, "Connect", new byte[0]), 2, -1) >= 0; }
    public int consoleState() throws Exception { return varint(call(CONSOLE, "GetConsoleState", new byte[0]), 1, 0); }

    // ---- workout ----
    public int workoutState() throws Exception { return varint(call(WORKOUT, "GetWorkoutState", new byte[0]), 1, 0); }
    public String startWorkout() throws Exception { return string(call(WORKOUT, "StartNewWorkout", new byte[0]), 2); }
    public boolean pause() throws Exception { return ok(call(WORKOUT, "Pause", new byte[0])); }
    public boolean resume() throws Exception { return ok(call(WORKOUT, "Resume", new byte[0])); }
    public boolean stop() throws Exception { return ok(call(WORKOUT, "Stop", new byte[0])); }

    // ---- belt ----
    public boolean setSpeed(double kph) throws Exception { return ok(call(SPEED, "SetSpeed", dbl(1, kph))); }
    public boolean setIncline(double pct) throws Exception { return ok(call(INCLINE, "SetIncline", dbl(1, pct))); }
    public double speedKph() throws Exception { return dblField(call(SPEED, "GetSpeed", new byte[0]), 3, 0); }
    public double inclinePct() throws Exception { return dblField(call(INCLINE, "GetIncline", new byte[0]), 3, 0); }

    // ---- metrics ----
    public double distanceKm() throws Exception { return dblField(call(DISTANCE, "GetDistance", new byte[0]), 3, 0); }
    public int elapsedSec() throws Exception { return varint(call(ELAPSED, "GetElapsedTime", new byte[0]), 2, 0); }
    public double calories() throws Exception { return dblField(call(CALORIES, "GetCaloriesBurned", new byte[0]), 3, 0); }

    // ---- unary gRPC: 1-byte flag + 4-byte big-endian length + protobuf ----
    private byte[] call(String svc, String method, byte[] proto) throws Exception {
        if (client == null) throw new IllegalStateException("not initialized");
        byte[] body = new byte[5 + proto.length];
        body[1] = (byte) (proto.length >>> 24);
        body[2] = (byte) (proto.length >>> 16);
        body[3] = (byte) (proto.length >>> 8);
        body[4] = (byte) proto.length;
        System.arraycopy(proto, 0, body, 5, proto.length);
        Request req = new Request.Builder()
                .url(BASE_URL + "/" + svc + "/" + method)
                .post(RequestBody.create(GRPC, body))
                .addHeader("te", "trailers")
                .addHeader("client_id", CLIENT_ID)
                .build();
        try (Response r = client.newCall(req).execute()) {
            String status = r.header("grpc-status");
            if (status != null && !"0".equals(status)) throw new Exception("gRPC " + status + ": " + r.header("grpc-message", ""));
            byte[] b = r.body() != null ? r.body().bytes() : new byte[0];
            return b.length >= 5 ? Arrays.copyOfRange(b, 5, b.length) : new byte[0];
        }
    }

    // ---- minimal protobuf ----
    private static byte[] dbl(int field, double v) {
        byte[] b = new byte[9];
        b[0] = (byte) ((field << 3) | 1);
        long bits = Double.doubleToLongBits(v);
        for (int i = 0; i < 8; i++) b[1 + i] = (byte) (bits >>> (8 * i));
        return b;
    }

    private static boolean ok(byte[] d) { return varint(d, 2, 0) != 0; }

    private static int[] rv(byte[] d, int p) {
        int v = 0, s = 0;
        while (p < d.length) { int b = d[p++] & 0xff; v |= (b & 0x7f) << s; if ((b & 0x80) == 0) break; s += 7; }
        return new int[]{v, p};
    }

    /** Walks fields; returns position of the target field's value, or -1. out[0] = wire type. */
    private static int find(byte[] d, int target, int wantWire, int[] out) {
        int p = 0;
        while (p < d.length) {
            int[] t = rv(d, p); p = t[1];
            int field = t[0] >>> 3, wire = t[0] & 7;
            if (field == target && wire == wantWire) { out[0] = wire; return p; }
            if (wire == 0) p = rv(d, p)[1];
            else if (wire == 1) p += 8;
            else if (wire == 2) { int[] l = rv(d, p); p = l[1] + l[0]; }
            else if (wire == 5) p += 4;
            else return -1;
        }
        return -1;
    }

    private static int varint(byte[] d, int field, int def) {
        int p = find(d, field, 0, new int[1]);
        return p < 0 ? def : rv(d, p)[0];
    }

    private static double dblField(byte[] d, int field, double def) {
        int p = find(d, field, 1, new int[1]);
        if (p < 0 || p + 8 > d.length) return def;
        long bits = 0;
        for (int i = 0; i < 8; i++) bits |= ((long) (d[p + i] & 0xff)) << (8 * i);
        return Double.longBitsToDouble(bits);
    }

    private static String string(byte[] d, int field) {
        int p = find(d, field, 2, new int[1]);
        if (p < 0) return null;
        int[] l = rv(d, p);
        return l[1] + l[0] <= d.length ? new String(d, l[1], l[0]) : null;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = i.read(buf)) != -1) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }
}

package com.massaro.c1250deck;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.usb.UsbDevice;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Drives the belt over the USB motor board (FitPro.java), the way iFit's FitPro1ConsoleImpl does.
 *
 * Everything here runs on MainActivity's one hardware thread. The page's commands only record
 * what's wanted; tick() (every 250 ms) sends one READ_WRITE_DATA exchange carrying any pending
 * write and reading the belt back, so a command reaches the board within a quarter second and
 * the board never goes quiet.
 *
 * Setup after the board's startup, as iFit does it for a treadmill: REQUIRE_START_REQUESTED on
 * (field 108), so the console's own Start key asks rather than starting the belt by itself — the
 * board raises START_REQUESTED (96) and the app answers with its countdown — and IDLE_MODE_LOCKOUT
 * off (95).
 *
 * The workout mode (field 12) is the switch: 2 runs, 3 pauses, 4 ends (results), 1 is idle.
 * Speed and incline are only sent while the belt is running; a value set before that waits.
 * The console's STOP key and the safety key act on the board directly and always win: the next
 * read shows the board's new mode and the page follows it.
 */
final class Belt {
    // GlassOS's workout-state numbers, which the page already understands.
    static final int WK_IDLE = 1, WK_RUNNING = 3, WK_PAUSED = 4, WK_RESULTS = 5;

    private static final String[] IFIT = {"com.ifit.arda", "com.ifit.standalone"};

    private final Context ctx;
    private FitPro fp;
    boolean suspended;
    private long nextTry;
    String problem;
    private String startupLog = "";

    // What the board said last.
    int mode = -1;
    double setKph, kph, setGrade, grade;
    double minKph = 1.6, maxKph = 19.3, minGrade = -3, maxGrade = 12;
    private boolean startRequested;
    // ACTUAL_KPH and ACTUAL_GRADE read 0 on Tommy's board (1.8.0, 22 Oct): until one ever reads non-zero,
    // the set values stand in for them, or the page's distance never moves.
    private boolean actualKphLive, actualGradeLive;
    private int rawDistance = -1, rawTime = -1;
    private int calories = -1;

    // What's wanted.
    private final ArrayDeque<Integer> modes = new ArrayDeque<>();
    private Double wantKph, wantGrade;
    private int sentMode = -1;
    private long sentModeAt, resultsSince;
    private String cmdError;

    // The belt test (Diagnostics), run inside tick() so STOP still works during it.
    interface Done { void report(String text); }
    private Done test;
    private long testAt;
    private boolean testStopped;
    private final StringBuilder testLog = new StringBuilder();

    Belt(Context ctx) { this.ctx = ctx; }

    /** Is a FitPro 1 board plugged in? (Cheap: the USB device list.) */
    boolean attached() {
        UsbDevice d = new FitPro(ctx).find();
        return d != null && d.getProductId() == FitPro.PRODUCT_FITPRO1;
    }

    boolean holding() { return fp != null; }

    // ---- connection ----

    private boolean ensure() {
        if (fp != null) return true;
        if (suspended) return false;
        long now = System.currentTimeMillis();
        if (now < nextTry) return false;
        nextTry = now + 5000;
        FitPro f = new FitPro(ctx);
        f.logBytes = false;
        try {
            String why = f.open();
            if (why != null && f.claimFailed) {
                // iFit still has the board. Ask Android to end its background processes, then try once more.
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                for (String p : IFIT) try { am.killBackgroundProcesses(p); } catch (Exception ignored) {}
                f.note("Asked Android to stop iFit's background processes; trying again.");
                Thread.sleep(1200);
                why = f.open();
            }
            if (why != null) {
                problem = f.claimFailed
                        ? "iFit is still holding the motor. On the console: Settings → Apps → iFit → Force stop, then come back here."
                        : why;
                f.close();
                return false;
            }
            f.startup();
            if (!f.unlocked) { f.close(); problem = "The motor board didn't accept the security key."; return false; }
            // iFit's setup for a treadmill that supports REQUIRE_START_REQUESTED.
            Map<Integer, byte[]> setup = new TreeMap<>();
            if (f.supported.contains(FitPro.F_REQUIRE_START)) setup.put(FitPro.F_REQUIRE_START, new byte[]{1});
            if (f.supported.contains(FitPro.F_IDLE_LOCKOUT)) setup.put(FitPro.F_IDLE_LOCKOUT, new byte[]{0});
            List<Integer> limits = new ArrayList<>();
            for (int x : new int[]{FitPro.F_MIN_KPH, FitPro.F_MAX_KPH, FitPro.F_MIN_GRADE, FitPro.F_MAX_GRADE})
                if (f.supported.contains(x)) limits.add(x);
            Map<Integer, byte[]> v = f.readWrite(setup, limits);
            if (v.containsKey(FitPro.F_MIN_KPH)) minKph = FitPro.hundredths(v.get(FitPro.F_MIN_KPH));
            if (v.containsKey(FitPro.F_MAX_KPH)) maxKph = FitPro.hundredths(v.get(FitPro.F_MAX_KPH));
            if (v.containsKey(FitPro.F_MIN_GRADE)) minGrade = FitPro.hundredths(v.get(FitPro.F_MIN_GRADE));
            if (v.containsKey(FitPro.F_MAX_GRADE)) maxGrade = FitPro.hundredths(v.get(FitPro.F_MAX_GRADE));
            if (minKph <= 0 || maxKph <= minKph) { minKph = 1.6; maxKph = 19.3; }
            f.note(String.format(Locale.US, "Setup: start key asks first%s, idle lockout off; speed %.1f–%.1f kph, incline %.0f to %.0f%%",
                    setup.containsKey(FitPro.F_REQUIRE_START) ? "" : " (not supported here)", minKph, maxKph, minGrade, maxGrade));
            startupLog = f.log.toString();
            f.log.setLength(0);
            fp = f;
            problem = null;
            mode = -1;
            return true;
        } catch (Exception e) {
            f.close();
            problem = "The motor board stopped answering: " + e.getMessage();
            return false;
        }
    }

    private void drop(String why) {
        if (fp != null) fp.close();
        fp = null;
        problem = why;
        nextTry = System.currentTimeMillis() + 2000;
    }

    /** Leaving the screen: pause a running belt, then let go so iFit can have the board. */
    void suspend() {
        if (fp != null && running(mode)) {
            try { fp.readWrite(single(FitPro.F_MODE, new byte[]{3}), new ArrayList<>()); } catch (Exception ignored) {}
        }
        if (test != null) finishTest("Stopped: C1250 Deck left the screen.");
        if (fp != null) fp.close();
        fp = null;
        suspended = true;
    }

    void resume() { suspended = false; nextTry = 0; }

    // ---- commands (from the page) ----

    void start() {
        if (mode == 4) modes.add(1);                       // results → idle first
        modes.add(2);
    }

    void pause() { if (running(mode)) modes.add(3); }

    void stop() {
        wantKph = null; wantGrade = null;
        if (test != null && !testStopped) { testStopped = true; testLog.append("STOP pressed.\n"); }
        if (running(mode) || mode == 3) modes.add(4);
        else if (!modes.isEmpty()) modes.clear();          // a start still queued: don't send it
    }

    void speed(double k) { wantKph = Math.max(minKph, Math.min(maxKph, k)); }

    void incline(double g) { wantGrade = Math.max(minGrade, Math.min(maxGrade, g)); }

    static boolean running(int m) { return m == 2 || m == 10 || m == 11 || m == 13; }

    // ---- the loop ----

    /** One exchange with the board. Returns the state for the page, or null (see problem) if the board isn't ours. */
    JSONObject tick() {
        if (!ensure()) return null;
        long now = System.currentTimeMillis();
        Map<Integer, byte[]> w = new TreeMap<>();
        Integer m = modes.poll();
        if (m != null) {
            w.put(FitPro.F_MODE, new byte[]{(byte) (int) m});
            sentMode = m; sentModeAt = now;
            if (m == 4) resultsSince = now;
        } else if (running(mode)) {
            if (wantKph != null) { w.put(FitPro.F_KPH, FitPro.hundredths(wantKph)); wantKph = null; }
            if (wantGrade != null) { w.put(FitPro.F_GRADE, FitPro.hundredths(wantGrade)); wantGrade = null; }
        }
        List<Integer> r = new ArrayList<>();
        for (int x : new int[]{FitPro.F_KPH, FitPro.F_GRADE, FitPro.F_MODE, FitPro.F_ACTUAL_KPH, FitPro.F_ACTUAL_GRADE,
                FitPro.F_START_REQUESTED, FitPro.F_CALORIES, FitPro.F_DISTANCE, FitPro.F_RUN_TIME})
            if (fp.supported.contains(x)) r.add(x);

        fp.logBytes = test != null && !w.isEmpty();
        Map<Integer, byte[]> v;
        try {
            v = fp.readWrite(w, r);
        } catch (Exception e) {
            if (test != null) finishTest("Stopped: " + e.getMessage());
            drop("The motor board stopped answering: " + e.getMessage());
            return null;
        }
        if (test != null && fp.log.length() > 0) { testLog.append(fp.log); }
        fp.log.setLength(0);

        int was = mode;
        if (v.containsKey(FitPro.F_MODE)) mode = v.get(FitPro.F_MODE)[0];
        if (v.containsKey(FitPro.F_KPH)) setKph = FitPro.hundredths(v.get(FitPro.F_KPH));
        if (v.containsKey(FitPro.F_ACTUAL_KPH)) kph = FitPro.hundredths(v.get(FitPro.F_ACTUAL_KPH));
        if (v.containsKey(FitPro.F_GRADE)) setGrade = FitPro.hundredths(v.get(FitPro.F_GRADE));
        if (v.containsKey(FitPro.F_ACTUAL_GRADE)) grade = FitPro.hundredths(v.get(FitPro.F_ACTUAL_GRADE));
        if (v.containsKey(FitPro.F_CALORIES)) calories = FitPro.le32(v.get(FitPro.F_CALORIES), 0);
        if (v.containsKey(FitPro.F_DISTANCE)) rawDistance = FitPro.le32(v.get(FitPro.F_DISTANCE), 0);
        if (v.containsKey(FitPro.F_RUN_TIME)) rawTime = FitPro.le32(v.get(FitPro.F_RUN_TIME), 0);
        if (kph > 0.05) actualKphLive = true;
        if (Math.abs(grade) > 0.05) actualGradeLive = true;
        boolean sr = v.containsKey(FitPro.F_START_REQUESTED) && v.get(FitPro.F_START_REQUESTED)[0] == 1;
        boolean startEdge = sr && !startRequested;
        startRequested = sr;

        // A mode we sent that didn't take.
        if (sentMode > 0 && now - sentModeAt > 4000) {
            if (mode != sentMode && !(sentMode == 2 && running(mode))) {
                cmdError = "The board didn't take mode " + name(sentMode) + "; it says " + name(mode);
                if (test != null) testLog.append(cmdError).append('\n');
            }
            sentMode = -1;
        }
        // After results, back to idle (as iFit does when its results screen closes).
        if (mode == 4) {
            if (resultsSince == 0) resultsSince = now;
            if (now - resultsSince > 2500 && modes.isEmpty()) { modes.add(1); resultsSince = Long.MAX_VALUE / 2; }
        } else resultsSince = 0;

        if (test != null) testStep(now, v, was);

        JSONObject st = new JSONObject();
        try {
            st.put("ok", true);
            st.put("driver", "usb");
            st.put("console", console(mode));
            st.put("workout", workout(mode));
            st.put("kph", running(mode) ? (actualKphLive ? kph : setKph) : 0);
            st.put("incline", actualGradeLive ? grade : setGrade);
            // What the board is set to, so the page can follow the console's own speed and incline keys.
            if (running(mode)) {
                st.put("setKph", setKph); st.put("setIncline", setGrade);
                st.put("pending", wantKph != null || wantGrade != null || !modes.isEmpty());
            }
            st.put("minKph", minKph); st.put("maxKph", maxKph);
            if (rawDistance >= 0) { st.put("boardDist", rawDistance); st.put("boardTime", rawTime); }
            st.put("minIncline", minGrade); st.put("maxIncline", maxGrade);
            if (startEdge && (mode == 1 || mode == 3)) st.put("startRequested", true);
            if (cmdError != null) { st.put("cmdError", cmdError); cmdError = null; }
        } catch (Exception ignored) {}
        return st;
    }

    static String console(int m) {
        switch (m) {
            case 1: return "IDLE";
            case 2: return "WORKOUT";
            case 3: return "PAUSED";
            case 4: return "RESULTS";
            case 8: return "SAFETY_KEY";
            case 10: return "WARMUP";
            case 11: return "COOLDOWN";
            case 12: return "SLEEP";
            case 13: return "RESUME";
            case 14: return "LOCKED";
            default: return "UNKNOWN";
        }
    }

    /** In the page's (GlassOS's) numbers. A pulled safety key reads as paused, so the run isn't thrown away. */
    static int workout(int m) {
        if (running(m)) return WK_RUNNING;
        if (m == 3 || m == 8) return WK_PAUSED;
        if (m == 4) return WK_RESULTS;
        return WK_IDLE;
    }

    static String name(int m) { return m >= 0 && m < FitPro.MODES.length ? FitPro.MODES[m] : String.valueOf(m); }

    private static Map<Integer, byte[]> single(int f, byte[] b) { Map<Integer, byte[]> w = new TreeMap<>(); w.put(f, b); return w; }

    // ---- reports ----

    /** Motor link report: how the board answered at connect time, and what it says now. */
    String linkReport() {
        StringBuilder s = new StringBuilder("Motor link (C1250 Deck is driving the belt over USB)\n\n");
        if (!ensure()) return s.append(problem == null ? "The board isn't connected." : problem).append('\n').toString();
        s.append(startupLog).append('\n');
        s.append("Supported fields: ").append(fp.supported).append('\n');
        s.append(String.format(Locale.US, "Now: mode %s · set %.2f kph · belt %.2f kph · set %.1f%% · deck %.1f%% · calories raw %d · distance raw %d · time raw %d\n",
                name(mode), setKph, kph, setGrade, grade, calories, rawDistance, rawTime));
        s.append("Actual speed field ").append(actualKphLive ? "reports" : "has read 0 so far (using the set speed)")
         .append("; actual incline field ").append(actualGradeLive ? "reports" : "has read 0 so far (using the set incline)").append(".\n");
        return s.toString();
    }

    /**
     * The belt test: walk at the slowest speed for 10 seconds, then stop. Everything sent and
     * read is logged; STOP on screen or on the console ends it early.
     */
    void beltTest(Done done) {
        testLog.setLength(0);
        testLog.append("Belt test: slowest speed for 10 seconds, then stop\n\n");
        if (!ensure()) { done.report(testLog.append(problem).append('\n').toString()); return; }
        if (mode != 1 && mode != -1) {
            done.report(testLog.append("The belt isn't idle (it says ").append(name(mode)).append("). Stop it and try again.\n").toString());
            return;
        }
        testLog.append(startupLog).append('\n');
        test = done; testAt = System.currentTimeMillis(); testStopped = false;
        testLog.append("t=0.0 s  start (mode running), then ").append(String.format(Locale.US, "%.1f kph", minKph)).append('\n');
        start();
        speed(minKph);
    }

    private long lastLine;

    private void testStep(long now, Map<Integer, byte[]> v, int was) {
        double t = (now - testAt) / 1000.0;
        if (was != mode) testLog.append(String.format(Locale.US, "t=%.1f s  mode %s → %s\n", t, name(was), name(mode)));
        if (now - lastLine >= 1000) {
            lastLine = now;
            testLog.append(String.format(Locale.US, "t=%.1f s  %s\n", t, FitPro.summary(v)));
        }
        if (mode == 8 && !testStopped) { testStopped = true; testLog.append("Safety key out.\n"); }
        if (!testStopped && t >= 12) { testStopped = true; testLog.append(String.format(Locale.US, "t=%.1f s  stop (mode results)\n", t)); stop(); }
        if (testStopped && (t >= 30 || (mode == 1 && kph == 0 && modes.isEmpty()))) finishTest(String.format(Locale.US, "Done at t=%.1f s.", t));
    }

    private void finishTest(String last) {
        Done d = test;
        test = null;
        if (fp != null) fp.logBytes = false;
        testLog.append(last).append('\n');
        if (d != null) d.report(testLog.toString());
    }
}

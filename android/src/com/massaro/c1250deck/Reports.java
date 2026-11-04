package com.massaro.c1250deck;

import android.content.Context;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Uploads a Diagnostics or motor-test report to the site's report box (worker/index.js,
 * KV-backed, kept 30 days), so it reaches the Mac and Claude without photos of the screen.
 * Read them at <site>/diag/?k=KEY.
 *
 * The APK is public, so it holds no key. The site's owner types theirs into Diagnostics once and it
 * stays in the app's private prefs; on every other console nothing here uploads anything.
 */
final class Reports {
    static final String URL_REPORT = Secrets.SITE + "/diag/report";
    static final String URL_RUNS = Secrets.SITE + "/runs";

    private static volatile String key = "";

    /** Reads the saved key. Call before anything here can run: at the start of each way into the app. */
    static void load(Context c) {
        key = c.getSharedPreferences("personal", Context.MODE_PRIVATE).getString("key", "");
    }

    static boolean on() { return !key.isEmpty(); }

    /** Saves the key once the site accepts it. Null when saved, else why not, for the screen. Blocking. */
    static String setKey(Context c, String typed) {
        final String k = typed == null ? "" : typed.trim();
        if (k.isEmpty()) return "That key didn't work.";
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(URL_RUNS + "/pending").openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(8000);
            h.setRequestProperty("X-Deck-Key", k);
            if (h.getResponseCode() != 200) return "That key didn't work.";
        } catch (Exception e) {
            return "Couldn't reach the site. Check the treadmill's Wi-Fi and try again.";
        } finally {
            if (h != null) h.disconnect();
        }
        c.getSharedPreferences("personal", Context.MODE_PRIVATE).edit().putString("key", k).apply();
        key = k;
        return null;
    }

    /** Uploads one finished run (JSON) to the site's run store. Null when stored, else why not. Blocking. */
    static String sendRun(String json) {
        if (key.isEmpty()) return "no site key";
        HttpURLConnection c = null;
        try {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(URL_RUNS).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Deck-Key", key);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
            int code = c.getResponseCode();
            return code == 200 ? null : "the site answered " + code;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Returns null when it was stored, else why not. Blocking; call off the UI thread. */
    static String send(String kind, String version, String text) {
        if (key.isEmpty()) return "no site key";
        HttpURLConnection c = null;
        try {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(URL_REPORT).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.setRequestProperty("X-Deck-Key", key);
            c.setRequestProperty("X-Deck-Kind", kind);
            c.setRequestProperty("X-Deck-Version", version == null ? "" : version);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
            int code = c.getResponseCode();
            return code == 200 ? null : "the site answered " + code;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }
}

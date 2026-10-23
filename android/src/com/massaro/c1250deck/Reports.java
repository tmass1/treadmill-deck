package com.massaro.c1250deck;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Uploads a Diagnostics or motor-test report to the site's report box (worker/index.js,
 * KV-backed, kept 30 days), so it reaches the Mac and Claude without photos of the screen.
 * Read them at <site>/diag/?k=KEY. The site and key come from Secrets (build.sh, secrets.env).
 */
final class Reports {
    static final String URL_REPORT = Secrets.SITE + "/diag/report";
    static final String KEY = Secrets.DECK_KEY;

    static final String URL_RUNS = Secrets.SITE + "/runs";

    /** Uploads one finished run (JSON) to the site's run store. Null when stored, else why not. Blocking. */
    static String sendRun(String json) {
        if (KEY.isEmpty()) return "this build has no site key";
        HttpURLConnection c = null;
        try {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(URL_RUNS).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Deck-Key", KEY);
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
        if (KEY.isEmpty()) return "this build has no site key";
        HttpURLConnection c = null;
        try {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(URL_REPORT).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.setRequestProperty("X-Deck-Key", KEY);
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

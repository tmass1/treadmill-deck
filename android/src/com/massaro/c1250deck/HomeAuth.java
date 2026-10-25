package com.massaro.c1250deck;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Signing in to Home Assistant, for the cameras (webhooks need no sign-in; camera pictures and live
 * video do). It's Home Assistant's own login, the way its web app signs in: its login page in a
 * dialog over the console (the belt carries on underneath), then the code it redirects with is
 * traded for a refresh token. That stays in this app's private storage, never on the site or in the
 * repo, and turns into a 30-minute access token whenever the page asks for one.
 */
final class HomeAuth {
    interface Push { void push(JSONObject o); }

    private final MainActivity a;
    private final Push push;
    private final SharedPreferences prefs;
    private String access = null;
    private long expiresAt = 0;
    private Dialog dialog;

    HomeAuth(MainActivity a, Push push) {
        this.a = a; this.push = push;
        prefs = a.getSharedPreferences("home_assistant", Context.MODE_PRIVATE);
    }

    boolean signedIn() { return !prefs.getString("refresh", "").isEmpty(); }
    String base() { return prefs.getString("base", ""); }

    /** Home Assistant's login page, in a full-screen dialog. base is like http://192.168.4.31:80. */
    void signIn(String base) {
        // Drop a default port (http :80, https :443): WebView drops it from the URLs it reports, so the
        // redirect would never match, and Home Assistant's own app signs in without one.
        final String b = base.replaceAll("/+$", "").replaceFirst("^(http://[^/]+):80$", "$1").replaceFirst("^(https://[^/]+):443$", "$1");
        final String client = b + "/";
        final String back = b + "/?deck_auth=1";
        final String url = b + "/auth/authorize?response_type=code&state=deck"
                + "&client_id=" + enc(client) + "&redirect_uri=" + enc(back);
        a.runOnUiThread(() -> {
            if (dialog != null) dialog.dismiss();
            dialog = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setBackgroundColor(0xFF08090A);
            FrameLayout bar = new FrameLayout(a);
            bar.setPadding(24, 16, 24, 16);
            Button cancel = new Button(a);
            cancel.setText("Cancel");
            cancel.setTextSize(18);
            cancel.setOnClickListener(v -> { dialog.dismiss(); say(false, "Sign-in cancelled."); });
            bar.addView(cancel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
            col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            WebView w = new WebView(a);
            WebSettings s = w.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            CookieManager.getInstance().setAcceptCookie(true);
            w.setWebViewClient(new WebViewClient() {
                private boolean done = false;
                // The way back is any address carrying deck_auth=1 (matched on the query, not the exact
                // string: the WebView may rewrite the host or port it reports).
                private boolean catchBack(String u) {
                    if (done || u == null) return false;
                    Uri p;
                    try { p = Uri.parse(u); } catch (Exception e) { return false; }
                    if (!p.isHierarchical() || !"1".equals(p.getQueryParameter("deck_auth"))) return false;
                    done = true;
                    String code = p.getQueryParameter("code");
                    dialog.dismiss();
                    if (code == null || code.isEmpty()) { say(false, "Home Assistant didn't sign you in."); return true; }
                    new Thread(() -> exchange(b, client, code), "deck-ha-auth").start();
                    return true;
                }
                @Override public boolean shouldOverrideUrlLoading(WebView v, String u) { return catchBack(u); }
                @Override public void onPageStarted(WebView v, String u, Bitmap f) { if (catchBack(u)) v.stopLoading(); }
                @Override public void doUpdateVisitedHistory(WebView v, String u, boolean reload) { catchBack(u); }
                @Override public void onReceivedError(WebView v, int code, String desc, String u) {
                    if (u != null && u.startsWith(b + "/auth")) { dialog.dismiss(); say(false, "Couldn't open Home Assistant's sign-in page: " + desc); }
                }
            });
            col.addView(w, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            dialog.setContentView(col);
            dialog.setOnCancelListener(d -> say(false, "Sign-in cancelled."));
            dialog.show();
            w.loadUrl(url);
        });
    }

    private void exchange(String b, String client, String code) {
        try {
            JSONObject t = post(b + "/auth/token", "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(client));
            String refresh = t.optString("refresh_token", "");
            if (refresh.isEmpty()) { say(false, "Home Assistant didn't send a token."); return; }
            prefs.edit().putString("base", b).putString("client", client).putString("refresh", refresh).apply();
            keep(t);
            say(true, "");
        } catch (Exception e) {
            say(false, "Couldn't finish signing in: " + e.getMessage());
        }
    }

    /** A current access token ("" when signed out or Home Assistant can't be reached). Called from the page. */
    synchronized String token() {
        if (!signedIn()) return "";
        if (access != null && System.currentTimeMillis() < expiresAt - 60_000) return access;
        try {
            JSONObject t = post(base() + "/auth/token", "grant_type=refresh_token&refresh_token=" + enc(prefs.getString("refresh", ""))
                    + "&client_id=" + enc(prefs.getString("client", base() + "/")));
            keep(t);
            return access == null ? "" : access;
        } catch (HttpError e) {
            // 400 is Home Assistant saying the refresh token is gone (signed out there, or the user removed): sign out here too.
            if (e.code == 400) { signOut(); say(false, "Home Assistant signed this treadmill out. Sign in again in Settings."); }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    void signOut() {
        final String b = base(), refresh = prefs.getString("refresh", "");
        prefs.edit().clear().apply();
        synchronized (this) { access = null; expiresAt = 0; }
        if (!refresh.isEmpty()) new Thread(() -> { try { post(b + "/auth/token", "action=revoke&token=" + enc(refresh)); } catch (Exception ignored) {} }, "deck-ha-revoke").start();
    }

    private synchronized void keep(JSONObject t) {
        access = t.optString("access_token", null);
        expiresAt = System.currentTimeMillis() + t.optLong("expires_in", 1800) * 1000L;
    }

    private void say(boolean ok, String why) {
        try { push.push(new JSONObject().put("type", "haAuth").put("ok", ok).put("why", why)); } catch (Exception ignored) {}
    }

    static final class HttpError extends Exception {
        final int code;
        HttpError(int code) { super("Home Assistant answered " + code + "."); this.code = code; }
    }

    private static JSONObject post(String url, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000); c.setReadTimeout(6000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.getOutputStream().write(form.getBytes("UTF-8"));
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); in.close(); }
        c.disconnect();
        if (code < 200 || code >= 300) throw new HttpError(code);
        String body = out.toString("UTF-8").trim();
        return body.isEmpty() ? new JSONObject() : new JSONObject(body);
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}

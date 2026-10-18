package com.sekwon.voicepicking;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements Store.Listener {
    private WebView web;
    private Store store;
    private boolean alive = false;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_REQ = 42;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != FILE_REQ || fileCallback == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                out = new Uri[data.getClipData().getItemCount()];
                for (int k = 0; k < out.length; k++) out[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        Speaker.get(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0C1015"));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel", "text/csv", "text/comma-separated-values",
                        "application/octet-stream"});
                try {
                    startActivityForResult(Intent.createChooser(i, "피킹지 엑셀 선택"), FILE_REQ);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        alive = true;
        store.addListener(this);
    }

    @Override
    public void changed() { refreshPage(); }

    private void refreshPage() {
        if (!alive || web == null) return;
        web.post(() -> { if (alive) web.evaluateJavascript("window.onNative&&window.onNative()", null); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPage();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        store.removeListener(this);
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    private void startSession() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        Intent i = new Intent(this, PickService.class).setAction(PickService.ACTION_START);
        startForegroundService(i);
    }

    private static final String RELEASE_API = "https://api.github.com/repos/sekwon216-hash/voice-picking/releases/latest";
    static final String APK_URL = "https://github.com/sekwon216-hash/voice-picking/releases/latest/download/voice-picking.apk";

    private long currentVersion() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Asks GitHub for the newest build and hands the answer to the page. */
    private void checkUpdate() {
        new Thread(() -> {
            JSONObject r = new JSONObject();
            try {
                r.put("current", currentVersion());
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "voice-picking-app");
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("http " + code);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                }
                JSONObject rel = new JSONObject(sb.toString());
                String tag = rel.optString("tag_name", "");
                long latest = 0;
                try { latest = Long.parseLong(tag.replaceAll("\\D", "")); } catch (Exception ignored) { }
                r.put("latest", latest);
                r.put("ok", true);
            } catch (Exception e) {
                try { r.put("ok", false); } catch (Exception ignored) { }
            }
            String js = "window.onUpdate&&window.onUpdate(" + r.toString() + ")";
            if (alive && web != null) web.post(() -> { if (alive && web != null) web.evaluateJavascript(js, null); });
        }).start();
    }

    private void openDownload() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)));
        } catch (Exception ignored) { }
    }

    private void stopSession() {
        startService(new Intent(this, PickService.class).setAction(PickService.ACTION_STOP));
    }

    private void pauseSession() {
        startService(new Intent(this, PickService.class).setAction(PickService.ACTION_PAUSE));
    }

    private class Bridge {
        @JavascriptInterface public String state() { return store.stateJson(); }
        @JavascriptInterface public void next() { store.next(); }
        @JavascriptInterface public void prev() { store.prev(); }
        @JavascriptInterface public void repeat() { store.repeat(); }
        @JavascriptInterface public void jump(String id) { store.jump(id); }
        @JavascriptInterface public void setMode(String m) { store.setMode(m, store.session()); }
        @JavascriptInterface public void setQty(int d, int p, int i, int q) { store.setQty(d, p, i, q); }
        @JavascriptInterface public void moveDest(int i, int dir) { store.moveDest(i, dir); }
        @JavascriptInterface public String importData(String raw, boolean replace) { return store.importData(raw, replace); }
        @JavascriptInterface public void setSetting(String k, String v) { store.setSetting(k, v); }
        @JavascriptInterface public void resetDone() { store.resetDone(); }
        @JavascriptInterface public void clearWave() { store.clearWave(); }
        @JavascriptInterface public void test() { Speaker.get(MainActivity.this).say("소리 테스트입니다. 이 말이 들리면 음성 안내는 정상입니다."); }
        @JavascriptInterface public void start() { runOnUiThread(MainActivity.this::startSession); }
        @JavascriptInterface public void stop() { runOnUiThread(MainActivity.this::stopSession); }
        @JavascriptInterface public void pause() { runOnUiThread(MainActivity.this::pauseSession); }
        @JavascriptInterface public boolean restore() { return store.restoreBackup(); }

        @JavascriptInterface public String paste() {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData c = cm.getPrimaryClip();
            if (c == null || c.getItemCount() == 0) return "";
            CharSequence t = c.getItemAt(0).coerceToText(MainActivity.this);
            return t == null ? "" : t.toString();
        }

        @JavascriptInterface public void checkUpdate() { MainActivity.this.checkUpdate(); }
        @JavascriptInterface public void openDownload() { runOnUiThread(MainActivity.this::openDownload); }
        @JavascriptInterface public String version() { return "1.0." + currentVersion(); }

        @JavascriptInterface public void batterySettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception ignored) { }
            });
        }
    }
}

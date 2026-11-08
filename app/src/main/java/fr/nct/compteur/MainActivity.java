package fr.nct.compteur;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements Ride.Ui {
    private WebView web;
    private Ride ride;
    private boolean pageReady;
    private SharedPreferences keys;
    private boolean learning;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        keys = getSharedPreferences("keys", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(0xFF090E14);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setFocusable(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                pageReady = true;
                js("NCT.nativeReady(" + JSONObject.quote(keys.getString("map", "")) + ")");
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");

        ride = Ride.get(this);
        ride.attach(this);
        askPermissions();
    }

    private void askPermissions() {
        String[] perms = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        List<String> need = new ArrayList<>();
        for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (need.isEmpty()) ride.start();
        else requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        ride.restartSensors();
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        ride.attach(this);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onDestroy() {
        ride.detach(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------ boutons physiques

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_POWER) return super.onKeyDown(code, e);
        if (e.getRepeatCount() > 0) return true;
        if (learning) { js("NCT.onKey(" + code + ")"); return true; }
        String action = actionFor(code);
        if (action != null) { js("NCT.onButton('" + action + "')"); return true; }
        // touche inconnue : on la signale (utile pour configurer)
        if (code != KeyEvent.KEYCODE_BACK) { js("NCT.onKey(" + code + ")"); return true; }
        return super.onKeyDown(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_BACK && actionFor(code) == null && !learning)
            return super.onKeyUp(code, e);
        return true;
    }

    private String actionFor(int code) {
        String map = keys.getString("map", "");
        if (map.isEmpty()) {
            // valeurs par défaut en attendant l'apprentissage
            switch (code) {
                case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_VOLUME_DOWN: return "next";
                case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_VOLUME_UP: return "prev";
                case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "startstop";
                default: return null;
            }
        }
        try {
            JSONObject o = new JSONObject(map);
            return o.has(String.valueOf(code)) ? o.getString(String.valueOf(code)) : null;
        } catch (Exception ex) { return null; }
    }

    // ------------------------------------------------------------ pont JS

    private void js(final String code) {
        if (!pageReady) return;
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    @Override public void sample(String json) { js("NCT.onSample(" + json + ")"); }
    @Override public void event(String kind, String text) { js("NCT.onEvent(" + JSONObject.quote(kind) + "," + JSONObject.quote(text) + ")"); }

    class Bridge {
        @JavascriptInterface public void startStop() { runOnUiThread(() -> ride.startStop()); }
        @JavascriptInterface public void lap() { runOnUiThread(() -> ride.lap()); }
        @JavascriptInterface public String finish() {
            final String[] r = new String[1];
            final Object lock = new Object();
            runOnUiThread(() -> { synchronized (lock) { r[0] = ride.finish(); lock.notify(); } });
            synchronized (lock) { try { lock.wait(3000); } catch (InterruptedException ignored) {} }
            return r[0] == null ? "" : r[0];
        }
        @JavascriptInterface public void setLearning(boolean on) { learning = on; }
        @JavascriptInterface public void saveKeyMap(String json) { keys.edit().putString("map", json).apply(); }
        @JavascriptInterface public void forgetSensors() { runOnUiThread(() -> ride.ble.forget()); }
        @JavascriptInterface public void quit() { runOnUiThread(() -> { if (ride.state.equals("idle")) finishAndRemoveTask(); }); }
    }
}

package fr.nct.compteur;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity implements Ride.Ui, Dash.Host {
    private Dash dash;
    private MapHolder map;
    private Ride ride;
    private SharedPreferences keys, cfg;
    private boolean learning, errorScreen;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        keys = getSharedPreferences("keys", MODE_PRIVATE);
        cfg = getSharedPreferences("cfg", MODE_PRIVATE);

        // rapport de plantage : enregistré, puis affiché au lancement suivant
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                ex.printStackTrace(new java.io.PrintWriter(sw));
                getSharedPreferences("crash", MODE_PRIVATE).edit().putString("last", sw.toString()).commit();
            } catch (Throwable ignored) {}
            if (def != null) def.uncaughtException(t, ex);
        });
        String crash = getSharedPreferences("crash", MODE_PRIVATE).getString("last", null);
        if (crash != null) {
            getSharedPreferences("crash", MODE_PRIVATE).edit().remove("last").commit();
            showError("L'appli a planté au dernier lancement.\nPrends une photo de cet écran et envoie-la à Claude.\nAppuie sur un bouton (ou touche l'écran) pour relancer.\n\n" + crash);
            return;
        }

        applyBrightness();
        dash = new Dash(this, this);
        dash.setCfg(cfg.getInt("ftp", 250), cfg.getInt("hrMax", 185), cfg.getFloat("weight", 72f));
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF090E14);
        map = new MapHolder(this);
        if (map.view != null) root.addView(map.view);
        root.addView(dash);
        setContentView(root);

        ride = Ride.get(this);
        ride.autoPause = cfg.getBoolean("autopause", true);
        if (getIntent().getStringExtra("demo") != null) { startDemo(); return; }
        ride.attach(this);
        askPermissions();

        // premier lancement : apprentissage des boutons après le splash
        if (sim == null && keys.getString("map", "").isEmpty()) new Handler().postDelayed(() -> dash.startLearn(), 3300);
    }

    // mode démo : adb shell am start -n fr.nct.compteur/.MainActivity -e demo 1 --ei ff 1500 --ei page 0 --ei lap 900
    private Sim sim;
    private final Handler demoH = new Handler();
    private void startDemo() {
        sim = new Sim();
        int ff = getIntent().getIntExtra("ff", 0), lapAt = getIntent().getIntExtra("lap", -1);
        if (getIntent().getStringExtra("splash") == null) dash.skipSplash();
        dash.quiet = true;
        for (int i = 0; i < ff; i++) {
            Ride.Sample smp = sim.step();
            dash.onSample(smp);
            if (map != null && i > ff - 600) map.update(smp);
            if (sim.time() == lapAt) dash.newLapQuiet();
        }
        dash.quiet = getIntent().getStringExtra("toast") == null;
        dash.showPageNow(getIntent().getIntExtra("page", 0));
        int md = getIntent().getIntExtra("mode", 0);
        if (md > 0 && md < 8) dash.openMode(md);
        if (md == 9) dash.demoSummary();
        if (md == 8) { sim.state = "pause"; dash.onSample(sim.step()); }
        demoH.post(new Runnable() { @Override public void run() { sample(sim.step()); demoH.postDelayed(this, 1000); } });
    }

    private void showError(String msg) {
        errorScreen = true;
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(msg); tv.setTextColor(0xFFEEF3F7); tv.setTextSize(11); tv.setPadding(20, 30, 20, 30);
        tv.setOnClickListener(v -> recreate());
        sv.setBackgroundColor(0xFF090E14); sv.addView(tv);
        setContentView(sv);
    }

    private void askPermissions() {
        String[] perms = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        List<String> need = new ArrayList<>();
        for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (need.isEmpty()) ride.start();
        else requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (ride != null) ride.restartSensors();
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        if (ride != null) ride.attach(this);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    private void immersive() {
        // barre d'état visible (accès au volet Android), barre de navigation masquée
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onDestroy() {
        demoH.removeCallbacksAndMessages(null);
        if (map != null) map.destroy();
        if (ride != null) ride.detach(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------ boutons physiques

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_POWER) return super.onKeyDown(code, e);
        if (errorScreen) { recreate(); return true; }
        if (e.getRepeatCount() > 0) return true;
        if (learning) { dash.onKey(code); return true; }
        if (sim != null) {
            String a = actionFor(code);
            if ("startstop".equals(a)) { sim.state = sim.state.equals("rec") ? "pause" : "rec"; return true; }
            if (a != null && !"lap".equals(a)) { dash.button(a); return true; }
            if ("lap".equals(a)) { dash.newLapQuiet(); return true; }
        }
        String action = actionFor(code);
        if (action != null) { dash.button(action); return true; }
        if (code == KeyEvent.KEYCODE_BACK) return super.onKeyDown(code, e);
        dash.onKey(code); // touche inconnue : signalée à l'écran
        return true;
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_POWER || errorScreen) return super.onKeyUp(code, e);
        if (code == KeyEvent.KEYCODE_BACK && !learning && actionFor(code) == null) return super.onKeyUp(code, e);
        return true;
    }

    @Override public void onBackPressed() {
        // on ne quitte pas pendant une sortie
        if (ride != null && !ride.state.equals("idle")) { dash.toast("SORTIE EN COURS", 0xFFFFB02E); return; }
        super.onBackPressed();
    }

    private String actionFor(int code) {
        String map = keys.getString("map", "");
        if (map.isEmpty()) {
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

    // ------------------------------------------------------------ Ride.Ui

    @Override public void sample(Ride.Sample s) {
        if (dash != null) dash.onSample(s);
        if (map != null) map.update(s);
    }

    @Override public void event(String kind, String text) {
        if (dash != null) dash.sensorEvent(text, !text.startsWith("✕"));
    }

    // ------------------------------------------------------------ Dash.Host

    @Override public void startStop() { ride.startStop(); }
    @Override public void lap() { ride.lap(); }
    @Override public String finishRide() {
        if (map != null) map.clearTrack();
        return sim != null ? "2026-11-08_16-10_NCT.fit" : ride.finish();
    }
    @Override public void setLearning(boolean on) { learning = on; }
    @Override public java.util.List<Ble.Found> sensorList() {
        java.util.List<Ble.Found> l = new java.util.ArrayList<>();
        if (sim != null) {
            String[][] demo = {{"pwr", "Quarq DZero 41234"}, {"hr", "HRM-Pro:552310"}, {"pwr", "ASSIOMA48211R"}, {"csc", "Wahoo SPEED 1A2B"}};
            for (int i = 0; i < demo.length; i++) {
                Ble.Found f = new Ble.Found(); f.type = demo[i][0]; f.name = demo[i][1]; f.addr = "00:00:00:00:00:0" + i;
                f.seen = System.currentTimeMillis(); l.add(f);
            }
            return l;
        }
        l.addAll(ride.ble.found.values());
        // capteurs enregistrés d'abord, puis par type
        java.util.Collections.sort(l, (a, b) -> {
            int sa = sensorStatus(a).isEmpty() ? 1 : 0, sb = sensorStatus(b).isEmpty() ? 1 : 0;
            if (sa != sb) return sa - sb;
            return a.type.compareTo(b.type);
        });
        return l;
    }

    @Override public String sensorStatus(Ble.Found f) {
        if (sim != null) return f.addr.endsWith("0") || f.addr.endsWith("1") ? "on" : "";
        if (!f.addr.equals(ride.ble.saved(f.type))) return "";
        Ble.Link l = ride.ble.linkFor(f.type);
        return l.connected ? "on" : "saved";
    }

    @Override public void toggleSensor(Ble.Found f) {
        if (sim != null) return;
        if (sensorStatus(f).isEmpty()) ride.ble.pair(f); else ride.ble.unpair(f.type);
    }

    @Override public void setKeepScanning(boolean on) { if (sim == null) ride.ble.setKeepScanning(on); }

    @Override public void saveCfg(int ftp, int hrMax, double weight) {
        cfg.edit().putInt("ftp", ftp).putInt("hrMax", hrMax).putFloat("weight", (float) weight).apply();
    }

    private boolean simAuto = true;
    @Override public boolean autoPause() { return sim != null ? simAuto : ride.autoPause; }
    @Override public void setAutoPause(boolean on) {
        if (sim != null) { simAuto = on; return; }
        ride.autoPause = on; cfg.edit().putBoolean("autopause", on).apply();
    }

    @Override public boolean mapReady() { return map != null && map.ready; }
    @Override public String mapInfo() { return map == null ? "" : map.info; }
    @Override public void setMapVisible(boolean on) { if (map != null) map.setVisible(on); }
    @Override public void mapZoom(int d) { if (map != null) map.zoom(d); }

    @Override public void openSettings(String action) {
        try { startActivity(new android.content.Intent(action)); }
        catch (Exception e) { openAndroidSettings(); }
    }

    @Override public int brightness() { return cfg.getInt("bright", -1); }

    @Override public void setBrightness(int pct) {
        cfg.edit().putInt("bright", pct).apply();
        applyBrightness();
    }

    private void applyBrightness() {
        int b = cfg.getInt("bright", -1);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : b / 100f;
        getWindow().setAttributes(lp);
    }

    @Override public void openAndroidSettings() {
        try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS)); }
        catch (Exception e) { dash.toast("PARAMÈTRES INDISPONIBLES", 0xFFE85022); }
    }

    @Override public void quitApp() {
        if (ride != null) ride.ble.stop();
        finishAndRemoveTask();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override public void saveKeyMap(Map<Integer, String> map) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<Integer, String> en : map.entrySet()) o.put(String.valueOf(en.getKey()), en.getValue());
            keys.edit().putString("map", o.toString()).apply();
        } catch (Exception ignored) {}
    }
}

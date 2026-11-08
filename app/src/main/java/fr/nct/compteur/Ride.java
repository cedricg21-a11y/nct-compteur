package fr.nct.compteur;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;


import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Moteur de sortie : agrège capteurs BLE + GPS + baromètre, gère l'état
 * (prêt / enregistrement / pause), enregistre le .FIT et pousse un échantillon par seconde à l'interface.
 */
public class Ride implements Ble.Listener, LocationListener, SensorEventListener {
    private static final String TAG = "NCT-RIDE";
    private static Ride inst;

    public static synchronized Ride get(Context c) {
        if (inst == null) inst = new Ride(c.getApplicationContext());
        return inst;
    }

    public static class Sample {
        public int p, hr, cad, bat; public double spd, dist, alt; public String state; public long elapsed;
        public boolean sPwr, sHr, sGps, sCsc;
        public double lat = Double.NaN, lon = Double.NaN; public float bearing;
    }
    private float lastBearing = 0;

    public interface Ui {
        void sample(Sample s);
        void event(String kind, String text);
    }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    final Ble ble;
    private Ui ui;
    private boolean started;

    // état
    String state = "idle"; // idle | rec | pause | auto (pause automatique)
    public boolean autoPause = true;
    private int slowCount = 0;
    long timerS = 0, startMs = 0;
    double distM = 0;

    // GPS
    Location last, lastForDist;
    boolean gpsFix; long lastFixMs;
    // baro
    boolean hasBaro; float pressure = 0; double baroOffset = Double.NaN; double altSmooth = Double.NaN;

    // FIT
    FitWriter fit;
    int lapIdx = 0; long lapStartMs; double lapStartDist; long lapTimer; long lapPSum, lapPN, lapHrSum, lapHrN, lapCSum, lapCN;
    long pSum, pN, hrSum, hrN;

    private Ride(Context c) {
        ctx = c;
        ble = new Ble(c, this);
    }

    public void attach(Ui u) { ui = u; }
    public void detach(Ui u) { if (ui == u) ui = null; }

    public void start() {
        if (started) return;
        started = true;
        ble.start();
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        } catch (SecurityException e) { Log.e(TAG, "gps", e); }
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor p = sm != null ? sm.getDefaultSensor(Sensor.TYPE_PRESSURE) : null;
        if (p != null) { hasBaro = true; sm.registerListener(this, p, SensorManager.SENSOR_DELAY_NORMAL); }
        h.post(tick);
    }

    /** relance BLE + GPS après obtention des permissions */
    public void restartSensors() { started = false; h.removeCallbacks(tick); ble.stop(); start(); }

    // ------------------------------------------------------------ boucle 1 Hz

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            h.postAtTime(this, android.os.SystemClock.uptimeMillis() + 1000 - (now % 1000));
            step(now);
        }
    };

    private void step(long now) {
        if (now - lastFixMs > 5000) gpsFix = false;
        double spd = speedKmh();
        double alt = altitude();
        int p = ble.power, hr = ble.heartRate, cad = ble.cadence;

        // pause automatique : arrêt > 3 s (vitesse connue via GPS ou capteur de roue)
        boolean speedKnown = gpsFix || (ble.cscOk() && ble.wheelSpeedKmh >= 0);
        if (autoPause && speedKnown && state.equals("rec")) {
            slowCount = spd < 2.5 ? slowCount + 1 : 0;
            if (slowCount >= 3) {
                state = "auto"; slowCount = 0;
                try { if (fit != null) fit.event(now, 4); } catch (Exception ignored) {}
            }
        } else if (state.equals("auto") && (spd > 4 || !autoPause)) {
            state = "rec"; lastForDist = null;
            try { if (fit != null) fit.event(now, 0); } catch (Exception ignored) {}
        }

        if (state.equals("rec")) {
            timerS++; lapTimer++;
            pSum += p; pN++; if (hr > 0) { hrSum += hr; hrN++; }
            lapPSum += p; lapPN++; if (hr > 0) { lapHrSum += hr; lapHrN++; } if (cad > 0) { lapCSum += cad; lapCN++; }
            if (fit != null) {
                try {
                    fit.record(now, last != null && gpsFix ? last.getLatitude() : Double.NaN,
                            last != null && gpsFix ? last.getLongitude() : Double.NaN,
                            alt, hr, ble.pwrOk() || ble.cscOk() ? cad : -1, distM, spd / 3.6, ble.pwrOk() ? p : -1);
                } catch (Exception e) { Log.e(TAG, "fit", e); }
            }
        }

        if (ui == null) return;
        Sample s = new Sample();
        s.p = p; s.hr = hr; s.cad = cad; s.spd = spd; s.dist = distM; s.alt = alt;
        s.state = state; s.elapsed = timerS; s.bat = battery();
        s.sPwr = ble.pwrOk(); s.sHr = ble.hrOk(); s.sGps = gpsFix; s.sCsc = ble.cscOk();
        if (last != null && gpsFix) {
            s.lat = last.getLatitude(); s.lon = last.getLongitude();
            if (last.hasBearing() && last.getSpeed() > 1.5) lastBearing = last.getBearing();
        }
        s.bearing = lastBearing;
        ui.sample(s);
    }

    double speedKmh() {
        if (ble.cscOk() && ble.wheelSpeedKmh >= 0) return ble.wheelSpeedKmh;
        if (last != null && gpsFix && last.hasSpeed()) { double v = last.getSpeed() * 3.6; return v < 1.5 ? 0 : v; }
        return 0;
    }

    double altitude() {
        if (hasBaro && pressure > 0) {
            double a = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure);
            // calage du baromètre sur l'altitude GPS (lent)
            if (last != null && gpsFix && last.hasAltitude() && last.getAccuracy() < 20) {
                double off = last.getAltitude() - a;
                baroOffset = Double.isNaN(baroOffset) ? off : baroOffset + (off - baroOffset) * 0.002;
            }
            double v = a + (Double.isNaN(baroOffset) ? 0 : baroOffset);
            altSmooth = Double.isNaN(altSmooth) ? v : altSmooth + (v - altSmooth) * 0.3;
            return altSmooth;
        }
        if (last != null && gpsFix && last.hasAltitude()) {
            double v = last.getAltitude();
            altSmooth = Double.isNaN(altSmooth) ? v : altSmooth + (v - altSmooth) * 0.2;
            return altSmooth;
        }
        return Double.NaN;
    }

    int battery() {
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return -1;
        int l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return l < 0 ? -1 : l * 100 / s;
    }

    // ------------------------------------------------------------ commandes

    public void startStop() {
        long now = System.currentTimeMillis();
        if (state.equals("idle")) {
            startMs = now; timerS = 0; distM = 0; lapIdx = 0; resetLap(now);
            pSum = pN = hrSum = hrN = 0;
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "NCT");
                dir.mkdirs();
                String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.FRANCE).format(new Date(now)) + "_NCT.fit";
                fit = new FitWriter(new File(dir, name));
                fit.open(now);
            } catch (Exception e) {
                Log.e(TAG, "fit open", e); fit = null;
                if (ui != null) ui.event("toast", "ERREUR FICHIER");
            }
            state = "rec";
            ctx.startService(new Intent(ctx, RideService.class));
        } else if (state.equals("rec") || state.equals("auto")) {
            boolean wasRec = state.equals("rec");
            state = "pause";
            if (!wasRec) { step(now); return; }
            try { if (fit != null) fit.event(now, 4); } catch (Exception ignored) {}
        } else {
            state = "rec";
            lastForDist = null;
            try { if (fit != null) fit.event(now, 0); } catch (Exception ignored) {}
        }
        step(now);
    }

    public void lap() {
        if (!state.equals("rec")) return;
        long now = System.currentTimeMillis();
        writeLap(now);
        resetLap(now);
    }

    private void writeLap(long now) {
        if (fit == null) return;
        try {
            fit.lap(lapIdx, lapStartMs, now, lapTimer, distM - lapStartDist,
                    lapPN > 0 ? (int) (lapPSum / lapPN) : -1, lapHrN > 0 ? (int) (lapHrSum / lapHrN) : 0,
                    lapCN > 0 ? (int) (lapCSum / lapCN) : 0);
        } catch (Exception e) { Log.e(TAG, "lap", e); }
        lapIdx++;
    }

    private void resetLap(long now) {
        lapStartMs = now; lapStartDist = distM; lapTimer = 0;
        lapPSum = lapPN = lapHrSum = lapHrN = lapCSum = lapCN = 0;
    }

    /** Termine et enregistre la sortie. Retourne le nom du fichier ou null. */
    public String finish() {
        if (state.equals("idle")) return null;
        long now = System.currentTimeMillis();
        String name = null;
        if (fit != null) {
            try {
                writeLap(now);
                fit.close(startMs, now, timerS, distM, pN > 0 ? (int) (pSum / pN) : -1, hrN > 0 ? (int) (hrSum / hrN) : 0, lapIdx);
                name = fit.getFile().getName();
            } catch (Exception e) { Log.e(TAG, "fit close", e); }
            fit = null;
        }
        state = "idle"; timerS = 0; distM = 0;
        ctx.stopService(new Intent(ctx, RideService.class));
        step(now);
        return name;
    }

    public void discard() {
        if (fit != null) { fit.abort(); fit = null; }
        state = "idle"; timerS = 0; distM = 0;
        ctx.stopService(new Intent(ctx, RideService.class));
    }

    // ------------------------------------------------------------ callbacks

    @Override public void onSensorEvent(String type, String name, boolean connected) {
        if (ui == null) return;
        String t = type.equals("pwr") ? "PUISSANCE" : type.equals("hr") ? "CARDIO" : "VITESSE/CADENCE";
        ui.event("sensor", (connected ? "✓ " : "✕ ") + t + " · " + name);
    }

    @Override public void onLocationChanged(Location l) {
        if (l.getAccuracy() > 50) return;
        boolean firstFix = !gpsFix;
        gpsFix = true; lastFixMs = System.currentTimeMillis();
        if (state.equals("rec") && !(ble.cscOk() && ble.wheelSpeedKmh >= 0)) {
            if (lastForDist != null) {
                float d = lastForDist.distanceTo(l);
                if (l.getSpeed() > 0.6 && d < 60) distM += d;
            }
            lastForDist = l;
        } else if (state.equals("rec")) {
            // distance par capteur de roue
            distM += ble.wheelSpeedKmh / 3.6 * Math.max(0, Math.min(2, (l.getTime() - (last != null ? last.getTime() : l.getTime())) / 1000.0));
        }
        last = l;
        if (firstFix && ui != null) ui.event("sensor", "✓ GPS");
    }
    @Override public void onStatusChanged(String p, int s, Bundle b) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}

    @Override public void onSensorChanged(SensorEvent e) { pressure = e.values[0]; }
    @Override public void onAccuracyChanged(Sensor s, int a) {}
}

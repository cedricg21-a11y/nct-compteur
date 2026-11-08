package fr.nct.compteur;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Prévision de vent heure par heure (Open-Meteo, gratuit, sans clé).
 * Téléchargée dès qu'une connexion est disponible (wifi maison, partage de connexion),
 * conservée en cache : le vent reste disponible hors ligne pendant la sortie.
 */
public class Wind {
    private static final String TAG = "NCT-WIND";
    private final SharedPreferences prefs;
    private long[] times = new long[0];
    private double[] spd = new double[0], dir = new double[0], gust = new double[0], temp = new double[0];
    private long fetchedAt = 0, lastTry = 0;
    private volatile boolean busy;

    public Wind(Context c) {
        prefs = c.getSharedPreferences("wind", Context.MODE_PRIVATE);
        try {
            String js = prefs.getString("json", "{}");
            parse(new JSONObject(js));
            fetchedAt = js.contains("temperature_2m") ? prefs.getLong("at", 0) : 0; // ancien cache sans température : on recharge
        } catch (Exception ignored) {}
    }

    /** à appeler régulièrement : télécharge si la prévision a plus de 15 min (essai max toutes les 2 min). */
    public void maybeRefresh(final double lat, final double lon) {
        long now = System.currentTimeMillis();
        if (busy || now - fetchedAt < 15 * 60000L || now - lastTry < 2 * 60000L) return;
        lastTry = now; busy = true;
        new Thread(() -> {
            try {
                String u = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                        + "&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m,temperature_2m&wind_speed_unit=kmh&timeformat=unixtime&forecast_days=2", lat, lon);
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    String l; while ((l = r.readLine()) != null) sb.append(l);
                }
                JSONObject o = new JSONObject(sb.toString());
                parse(o);
                fetchedAt = System.currentTimeMillis();
                prefs.edit().putString("json", sb.toString()).putLong("at", fetchedAt).apply();
                Log.i(TAG, "prévision vent mise à jour");
            } catch (Exception e) {
                Log.w(TAG, "pas de réseau pour le vent : " + e.getMessage());
            } finally { busy = false; }
        }).start();
    }

    private synchronized void parse(JSONObject o) throws Exception {
        JSONObject h = o.optJSONObject("hourly");
        if (h == null) return;
        JSONArray t = h.getJSONArray("time"), s = h.getJSONArray("wind_speed_10m"), d = h.getJSONArray("wind_direction_10m"), g = h.getJSONArray("wind_gusts_10m");
        JSONArray tp = h.optJSONArray("temperature_2m");
        int n = t.length();
        long[] tt = new long[n]; double[] ss = new double[n], dd = new double[n], gg = new double[n], te = new double[n];
        for (int i = 0; i < n; i++) { tt[i] = t.getLong(i) * 1000; ss[i] = s.optDouble(i, 0); dd[i] = d.optDouble(i, 0); gg[i] = g.optDouble(i, 0); te[i] = tp != null ? tp.optDouble(i, Double.NaN) : Double.NaN; }
        times = tt; spd = ss; dir = dd; gust = gg; temp = te;
    }

    /** {direction d'où vient le vent (°), vitesse km/h, rafales km/h, âge de la prévision en min} ou null */
    public synchronized float[] now() {
        long t = System.currentTimeMillis();
        int n = times.length;
        if (n < 2 || t < times[0] || t > times[n - 1]) return null;
        int i = 0; while (i < n - 2 && times[i + 1] < t) i++;
        double k = (t - times[i]) / (double) (times[i + 1] - times[i]);
        // interpolation circulaire de la direction
        double a = Math.toRadians(dir[i]), b = Math.toRadians(dir[i + 1]);
        double x = (1 - k) * Math.cos(a) + k * Math.cos(b), y = (1 - k) * Math.sin(a) + k * Math.sin(b);
        double d = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
        return new float[]{(float) d, (float) (spd[i] + (spd[i + 1] - spd[i]) * k), (float) (gust[i] + (gust[i + 1] - gust[i]) * k),
                (System.currentTimeMillis() - fetchedAt) / 60000f,
                temp.length == n ? (float) (temp[i] + (temp[i + 1] - temp[i]) * k) : Float.NaN};
    }
}

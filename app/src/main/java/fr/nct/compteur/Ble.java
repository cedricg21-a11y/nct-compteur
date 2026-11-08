package fr.nct.compteur;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Capteurs Bluetooth LE : puissance (0x1818), cardio (0x180D), vitesse/cadence (0x1816).
 * Connexion automatique au premier capteur trouvé de chaque type, mémorisé ensuite.
 */
public class Ble {
    private static final String TAG = "NCT-BLE";

    static UUID u16(int x) { return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", x)); }
    static final UUID SVC_PWR = u16(0x1818), CHR_PWR = u16(0x2A63);
    static final UUID SVC_HR = u16(0x180D), CHR_HR = u16(0x2A37);
    static final UUID SVC_CSC = u16(0x1816), CHR_CSC = u16(0x2A5B);
    static final UUID CCCD = u16(0x2902);

    public interface Listener { void onSensorEvent(String type, String name, boolean connected); }

    /** Une connexion vers un capteur. */
    class Link {
        final String type; // pwr | hr | csc
        BluetoothDevice dev; BluetoothGatt gatt; String name = "";
        boolean connected; long lastData;
        Link(String t) { type = t; }
    }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Listener listener;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    final Link pwr = new Link("pwr"), hr = new Link("hr"), csc = new Link("csc");

    // dernières valeurs
    volatile int power = 0, heartRate = 0, cadence = 0;
    volatile double wheelSpeedKmh = -1;
    private long lastPwrData, lastCadData, lastHrData;
    // cadence calculée depuis les révolutions de pédalier
    private int lastCrankRevs = -1, lastCrankTime = -1;
    private int lastWheelRevs = -1, lastWheelTime = -1;
    double wheelCircM = 2.105;

    public Ble(Context c, Listener l) {
        ctx = c.getApplicationContext(); listener = l;
        prefs = ctx.getSharedPreferences("ble", Context.MODE_PRIVATE);
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
    }

    public void start() {
        if (adapter == null) return;
        if (!adapter.isEnabled()) { adapter.enable(); h.postDelayed(this::start, 2500); return; }
        // reconnexion directe aux capteurs mémorisés
        for (Link l : new Link[]{pwr, hr, csc}) {
            String addr = prefs.getString(l.type, null);
            if (addr != null && l.gatt == null) connect(l, adapter.getRemoteDevice(addr));
        }
        startScan();
        h.postDelayed(watchdog, 5000);
    }

    public void stop() {
        stopScan();
        h.removeCallbacks(watchdog);
        for (Link l : new Link[]{pwr, hr, csc}) if (l.gatt != null) { l.gatt.close(); l.gatt = null; l.connected = false; }
    }

    /** Oublie les capteurs mémorisés et relance une recherche. */
    public void forget() {
        stop(); prefs.edit().clear().apply(); start();
    }

    // ---------------------------------------------------------------- gestion des capteurs

    /** Capteur détecté pendant la recherche. */
    public static class Found {
        public String addr, name, type; public int rssi; public long seen;
    }
    public final java.util.LinkedHashMap<String, Found> found = new java.util.LinkedHashMap<>();
    private boolean keepScan;

    /** Recherche continue (écran Capteurs ouvert). */
    public void setKeepScanning(boolean on) {
        keepScan = on;
        if (on) { stopScan(); startScan(); }
    }

    public String saved(String type) { return prefs.getString(type, null); }

    public Link linkFor(String type) { return type.equals("pwr") ? pwr : type.equals("hr") ? hr : csc; }

    /** Enregistre ce capteur pour son type (remplace l'ancien) et s'y connecte. */
    public void pair(Found f) {
        Link l = linkFor(f.type);
        unpair(f.type);
        prefs.edit().putString(f.type, f.addr).apply();
        if (adapter != null) connect(l, adapter.getRemoteDevice(f.addr));
    }

    /** Oublie le capteur de ce type et le déconnecte. */
    public void unpair(String type) {
        Link l = linkFor(type);
        prefs.edit().remove(type).apply();
        l.dev = null;
        if (l.gatt != null) { try { l.gatt.disconnect(); l.gatt.close(); } catch (Exception ignored) {} l.gatt = null; }
        if (l.connected) { l.connected = false; listener.onSensorEvent(l.type, l.name, false); }
    }

    private void startScan() {
        if (scanning || adapter == null || !adapter.isEnabled()) return;
        if (!keepScan && missingSaved() == 0) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        List<ScanFilter> f = new ArrayList<>();
        f.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC_PWR)).build());
        f.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC_HR)).build());
        f.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC_CSC)).build());
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try { scanner.startScan(f, s, scanCb); scanning = true; } catch (Exception e) { Log.e(TAG, "scan", e); }
        h.postDelayed(this::stopScan, keepScan ? 15000 : 30000);
    }

    /** nombre de capteurs enregistrés mais pas connectés */
    private int missingSaved() {
        int n = 0;
        for (Link l : new Link[]{pwr, hr, csc}) if (prefs.getString(l.type, null) != null && l.gatt == null) n++;
        return n;
    }

    private void stopScan() {
        if (!scanning || scanner == null) return;
        try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
        scanning = false;
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (now - lastPwrData > 4000) power = 0;
            if (now - lastCadData > 4000) cadence = 0;
            if (now - lastHrData > 6000) heartRate = 0;
            // relance la recherche si un capteur manque
            if (keepScan || missingSaved() > 0) startScan();
            h.postDelayed(this, 5000);
        }
    };

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int cb, ScanResult r) {
            if (r.getScanRecord() == null) return;
            List<ParcelUuid> u = r.getScanRecord().getServiceUuids();
            if (u == null) return;
            BluetoothDevice d = r.getDevice();
            String type = u.contains(new ParcelUuid(SVC_PWR)) ? "pwr" : u.contains(new ParcelUuid(SVC_HR)) ? "hr"
                    : u.contains(new ParcelUuid(SVC_CSC)) ? "csc" : null;
            if (type == null) return;
            Found f = found.get(d.getAddress());
            if (f == null) { f = new Found(); f.addr = d.getAddress(); f.type = type; found.put(f.addr, f); }
            String n = r.getScanRecord().getDeviceName();
            if (n == null) n = d.getName();
            if (n != null) f.name = n; else if (f.name == null) f.name = d.getAddress();
            f.rssi = r.getRssi(); f.seen = System.currentTimeMillis();
            // connexion automatique uniquement aux capteurs enregistrés
            Link l = linkFor(type);
            if (d.getAddress().equals(prefs.getString(type, null)) && l.gatt == null) connect(l, d);
        }
    };

    private boolean isUsed(BluetoothDevice d) {
        return (pwr.dev != null && pwr.dev.getAddress().equals(d.getAddress()))
            || (hr.dev != null && hr.dev.getAddress().equals(d.getAddress()));
    }

    private void connect(final Link l, final BluetoothDevice d) {
        l.dev = d;
        l.name = d.getName() != null ? d.getName() : d.getAddress();
        Log.i(TAG, "connexion " + l.type + " " + l.name);
        l.gatt = d.connectGatt(ctx, false, new GattCb(l), BluetoothDevice.TRANSPORT_LE);
    }

    class GattCb extends BluetoothGattCallback {
        final Link l;
        GattCb(Link l) { this.l = l; }

        @Override public void onConnectionStateChange(final BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                h.post(g::discoverServices);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                final boolean was = l.connected;
                l.connected = false;
                g.close();
                if (l.gatt == g) l.gatt = null;
                h.post(() -> { if (was) listener.onSensorEvent(l.type, l.name, false); });
                // tentative de reconnexion
                h.postDelayed(() -> {
                    if (l.gatt == null && l.dev != null && l.dev.getAddress().equals(prefs.getString(l.type, null))) connect(l, l.dev);
                }, 3000);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            UUID svc = l.type.equals("pwr") ? SVC_PWR : l.type.equals("hr") ? SVC_HR : SVC_CSC;
            UUID chr = l.type.equals("pwr") ? CHR_PWR : l.type.equals("hr") ? CHR_HR : CHR_CSC;
            BluetoothGattService s = g.getService(svc);
            if (s == null) { g.disconnect(); return; }
            BluetoothGattCharacteristic c = s.getCharacteristic(chr);
            if (c == null) { g.disconnect(); return; }
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor desc = c.getDescriptor(CCCD);
            if (desc != null) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(desc);
            }
            l.connected = true;
            h.post(() -> listener.onSensorEvent(l.type, l.name, true));
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null || v.length < 2) return;
            l.lastData = System.currentTimeMillis();
            try {
                if (l.type.equals("hr")) parseHr(v);
                else if (l.type.equals("pwr")) parsePower(v);
                else parseCsc(v);
            } catch (Exception e) { Log.w(TAG, "parse " + l.type, e); }
        }
    }

    static int u8(byte[] v, int i) { return v[i] & 0xFF; }
    static int u16le(byte[] v, int i) { return (v[i] & 0xFF) | ((v[i + 1] & 0xFF) << 8); }
    static int s16le(byte[] v, int i) { return (short) u16le(v, i); }
    static long u32le(byte[] v, int i) { return (u16le(v, i) & 0xFFFFL) | ((long) u16le(v, i + 2) << 16); }

    void parseHr(byte[] v) {
        int flags = u8(v, 0);
        heartRate = (flags & 1) != 0 ? u16le(v, 1) : u8(v, 1);
        lastHrData = System.currentTimeMillis();
    }

    void parsePower(byte[] v) {
        int flags = u16le(v, 0);
        int p = s16le(v, 2);
        power = Math.max(0, p);
        lastPwrData = System.currentTimeMillis();
        int i = 4;
        if ((flags & 0x01) != 0) i += 1;       // équilibre G/D
        if ((flags & 0x04) != 0) i += 2;       // couple accumulé
        if ((flags & 0x10) != 0) i += 6;       // révolutions de roue
        if ((flags & 0x20) != 0 && v.length >= i + 4) crank(u16le(v, i), u16le(v, i + 2));
    }

    void parseCsc(byte[] v) {
        int flags = u8(v, 0); int i = 1;
        if ((flags & 1) != 0) { wheel(u32le(v, i), u16le(v, i + 4)); i += 6; }
        if ((flags & 2) != 0 && pwr.gatt == null) crank(u16le(v, i), u16le(v, i + 2));
    }

    private long lastCrankChange;
    void crank(int revs, int time) {
        long now = System.currentTimeMillis();
        if (lastCrankRevs >= 0) {
            int dr = (revs - lastCrankRevs) & 0xFFFF;
            int dt = (time - lastCrankTime) & 0xFFFF;
            if (dr > 0 && dt > 0) {
                int c = (int) Math.round(dr * 60.0 * 1024.0 / dt);
                if (c < 200) { cadence = c; lastCadData = now; lastCrankChange = now; }
            } else if (now - lastCrankChange > 3000) { cadence = 0; lastCadData = now; }
        }
        lastCrankRevs = revs; lastCrankTime = time;
    }

    private long lastWheelChange;
    void wheel(long revs, int time) {
        long now = System.currentTimeMillis();
        if (lastWheelRevs >= 0) {
            long dr = (revs - lastWheelRevs) & 0xFFFFFFFFL;
            int dt = (time - lastWheelTime) & 0xFFFF;
            if (dr > 0 && dt > 0 && dr < 100) {
                wheelSpeedKmh = dr * wheelCircM / (dt / 1024.0) * 3.6; lastWheelChange = now;
            } else if (now - lastWheelChange > 3000) wheelSpeedKmh = 0;
        }
        lastWheelRevs = (int) revs; lastWheelTime = time;
    }

    boolean pwrOk() { return pwr.connected; }
    boolean hrOk() { return hr.connected; }
    boolean cscOk() { return csc.connected; }
}

package fr.nct.compteur;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Interface du compteur NCT, entièrement dessinée en natif (aucune dépendance WebView).
 * Espace logique : 480 px de large, mis à l'échelle de l'écran.
 */
public class Dash extends View {

    public interface Host {
        void startStop();
        boolean blockSleep();
        void setBlockSleep(boolean on);
        void discardRide();
        void lap();
        String finishRide();
        void saveKeyMap(Map<Integer, String> map);
        void setLearning(boolean on);
        java.util.List<Ble.Found> sensorList();
        String sensorStatus(Ble.Found f); // "on" | "saved" | ""
        void toggleSensor(Ble.Found f);
        void setKeepScanning(boolean on);
        void saveCfg(int ftp, int hrMax, double weight);
        boolean autoPause();
        void setAutoPause(boolean on);
        void openAndroidSettings();
        boolean mapReady();
        float[] mapPos();
        float[] wind();
        void mapExplore(boolean on);
        java.util.List<org.json.JSONObject> rides();
        void saveRide(org.json.JSONObject r);
        String mapInfo();
        void setMapVisible(boolean on);
        void mapZoom(int d);
        void mapTransform(float pivotX, float pivotY, float rotation);
        void openSettings(String action);
        int brightness();          // -1 = auto (système), sinon 1..100
        void setBrightness(int pct);
        void quitApp();
    }

    // ---------------------------------------------------------------- menus
    static final int M_DASH = 0, M_MENU = 1, M_SENS = 2, M_PROF = 3, M_HOME = 4, M_HIST = 5;
    static final String[] HOME = {"ROULER", "SÉANCES", "HISTORIQUE", "MA FORME", "MES PAGES", "CAPTEURS", "RÉGLAGES"};
    private int backMode = M_DASH, homeSel = 0, histSel = 0;
    private boolean mapExplore;
    private java.util.List<org.json.JSONObject> rides = new java.util.ArrayList<>();
    static final String[] MENU = {"CAPTEURS", "PROFIL", "LUMINOSITÉ", "BOUTONS", "WI-FI", "BLUETOOTH", "PARAMÈTRES ANDROID", "QUITTER", "BOUTON MARCHE"};
    static final int[] BRIGHT = {-1, 5, 15, 30, 50, 75, 100};
    private int mode = M_HOME, sel = 0, profSel = 0;
    private boolean profEdit;
    private java.util.List<Ble.Found> sensCache = new java.util.ArrayList<>();
    private long sensRefresh;

    public void openMenu() { backMode = (mode == M_HOME) ? M_HOME : M_DASH; mode = M_MENU; sel = 0; invalidate(); }
    public void goHome() { mode = M_HOME; homeSel = 0; rides = host.rides(); invalidate(); }
    public void goDash() { mode = M_DASH; invalidate(); }
    public void openMode(int md) { mode = md; sel = 0; if (md == M_SENS) host.setKeepScanning(true); if (md == M_HIST) rides = host.rides(); invalidate(); }

    private void menuButton(String b) {
        if (mode == M_MENU) {
            if (b.equals("next")) sel = (sel + 1) % MENU.length;
            else if (b.equals("prev")) sel = (sel + MENU.length - 1) % MENU.length;
            else if (b.equals("lap")) mode = backMode;
            else if (b.equals("startstop")) menuOk(sel);
        } else if (mode == M_HOME) {
            if (b.equals("next")) homeSel = (homeSel + 1) % HOME.length;
            else if (b.equals("prev")) homeSel = (homeSel + HOME.length - 1) % HOME.length;
            else if (b.equals("lap")) mode = M_DASH;
            else if (b.equals("startstop")) homeOk(homeSel);
        } else if (mode == M_HIST) {
            int n = rides.size();
            if (b.equals("next") && n > 0) histSel = (histSel + 1) % n;
            else if (b.equals("prev") && n > 0) histSel = (histSel + n - 1) % n;
            else if (b.equals("lap") || b.equals("startstop")) mode = M_HOME;
        } else if (mode == M_SENS) {
            int n = sensCache.size();
            if (b.equals("next") && n > 0) sel = (sel + 1) % n;
            else if (b.equals("prev") && n > 0) sel = (sel + n - 1) % n;
            else if (b.equals("lap")) { host.setKeepScanning(false); mode = backMode == M_HOME && sensFromHome ? M_HOME : M_MENU; sel = 0; sensFromHome = false; }
            else if (b.equals("startstop") && sel < n) { host.toggleSensor(sensCache.get(sel)); sensRefresh = 0; }
        } else if (mode == M_PROF) {
            if (profEdit) {
                if (b.equals("next")) adjust(+1);
                else if (b.equals("prev")) adjust(-1);
                else { profEdit = false; host.saveCfg(m.ftp, m.hrMax, m.weight); }
            } else {
                if (b.equals("next")) profSel = (profSel + 1) % 4;
                else if (b.equals("prev")) profSel = (profSel + 3) % 4;
                else if (b.equals("startstop")) { if (profSel == 3) host.setAutoPause(!host.autoPause()); else profEdit = true; }
                else if (b.equals("lap")) { host.saveCfg(m.ftp, m.hrMax, m.weight); mode = M_MENU; sel = 1; }
            }
        }
        invalidate();
    }

    private boolean sensFromHome;

    private void homeOk(int i) {
        switch (i) {
            case 0: mode = M_DASH; showPageNow(0); if (state.equals("idle")) host.startStop(); break;
            case 2: mode = M_HIST; histSel = 0; rides = host.rides(); break;
            case 5: backMode = M_HOME; sensFromHome = true; openMode(M_SENS); break;
            case 6: openMenu(); break;
            default: toast("BIENTÔT DISPONIBLE ✦", AMBER);
        }
    }

    private void adjust(int d) {
        if (profSel == 0) m.ftp = Math.max(80, Math.min(600, m.ftp + 5 * d));
        else if (profSel == 1) m.hrMax = Math.max(120, Math.min(230, m.hrMax + d));
        else m.weight = Math.max(35, Math.min(150, Math.round((m.weight + .5 * d) * 2) / 2.0));
    }

    private void menuOk(int i) {
        switch (i) {
            case 0: openMode(M_SENS); break;
            case 1: mode = M_PROF; profSel = 0; profEdit = false; break;
            case 2: { // luminosité : cycle des niveaux
                int curB = host.brightness(), k = 0;
                for (int j = 0; j < BRIGHT.length; j++) if (BRIGHT[j] == curB) k = j;
                host.setBrightness(BRIGHT[(k + 1) % BRIGHT.length]);
                break;
            }
            case 3: mode = M_DASH; startLearn(); break;
            case 4: host.openSettings(android.provider.Settings.ACTION_WIFI_SETTINGS); break;
            case 5: host.openSettings(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS); break;
            case 6: host.openAndroidSettings(); break;
            case 7: if (state.equals("idle")) host.quitApp(); else toast("SORTIE EN COURS", AMBER); break;
            case 8: host.setBlockSleep(!host.blockSleep()); break;
            default: mode = backMode;
        }
    }

    // couleurs
    static final int BG = 0xFF090E14, TILE = 0xFF0E151E, LINE = 0xFF1A2532, MUTE = 0xFF6C7D90, TXT = 0xFFEEF3F7,
            CYAN = 0xFF00E5C8, ORANGE = 0xFFE85022, AMBER = 0xFFFFB02E, DIM = 0xFF3D4D5F;

    static final String[] PAGES = {"LIVE", "CARTE", "EFFORT", "GRIMPÉE", "TOUR"};
    static final int P_MAP = 1;

    private final Host host;
    final Metrics m = new Metrics();
    private Ride.Sample s = new Ride.Sample();
    private String state = "idle";

    private Typeface bc5, bc6, bc7, bc8, b6, b7;
    private Bitmap logo, logoNct;
    private final Paint pf = new Paint(Paint.ANTI_ALIAS_FLAG), pt = new Paint(Paint.ANTI_ALIAS_FLAG),
            ps = new Paint(Paint.ANTI_ALIAS_FLAG), pb = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rf = new RectF();
    private final Path path = new Path();
    private Path icBolt, icHeart, icPin;
    private LinearGradient arcGrad, brandGrad;

    private float sc = 1, H = 800;
    private long born = System.currentTimeMillis();
    public boolean quiet;
    public String version = "";

    // pages
    private int cur = 0, prev = 0, dir = 1;
    private long pageT = 0;

    // zone / glow
    private int lastZone = -1, zoneCol = CYAN;
    private long zoneFlashT = 0;

    // overlays
    private String toastTxt = null; private int toastCol = CYAN; private long toastT = 0;
    private long lapFlashT = 0; private String lapFlashN = "", lapFlashS = "";
    private long confirmUntil = 0; private String confirmKind = "", confirmTitle = "", confirmSub1 = "", confirmSub2 = "";
    private int learnStep = -1; private final Map<Integer, String> learnMap = new LinkedHashMap<>();
    private String learnErr = "";
    static final String[][] LEARN = {{"next", "PAGE SUIVANTE"}, {"prev", "PAGE PRÉCÉDENTE"}, {"startstop", "START / PAUSE"}, {"lap", "TOUR"}};

    // tweens
    private static class Tw { double from, to; long start; }
    private final HashMap<String, Tw> tw = new HashMap<>();
    private double arcShown = 0;

    public Dash(Context c, Host h) {
        super(c);
        host = h;
        bc5 = tf(c, "bc-500"); bc6 = tf(c, "bc-600"); bc7 = tf(c, "bc-700"); bc8 = tf(c, "bc-800");
        b6 = tf(c, "b-600"); b7 = tf(c, "b-700");
        logo = bmp(c, "logo.png"); logoNct = bmp(c, "logo-nct.png");
        ps.setStyle(Paint.Style.STROKE);
        arcGrad = new LinearGradient(60, 0, 420, 0, new int[]{CYAN, 0xFF9BE36A, AMBER, ORANGE}, new float[]{0, .55f, .75f, 1}, Shader.TileMode.CLAMP);
        buildIcons();
        setBackground(null);
        s.state = "idle"; s.bat = -1;
    }

    static Typeface tf(Context c, String n) {
        try { return Typeface.createFromAsset(c.getAssets(), "fonts/" + n + ".ttf"); } catch (Exception e) { return Typeface.DEFAULT_BOLD; }
    }

    static Bitmap bmp(Context c, String n) {
        try (InputStream in = c.getAssets().open(n)) { return BitmapFactory.decodeStream(in); } catch (Exception e) { return null; }
    }

    private void buildIcons() {
        icBolt = new Path(); icBolt.moveTo(13, 2); icBolt.lineTo(4, 14); icBolt.lineTo(11, 14); icBolt.lineTo(10, 22); icBolt.lineTo(19, 10); icBolt.lineTo(12, 10); icBolt.close();
        icHeart = new Path(); icHeart.moveTo(12, 21);
        icHeart.cubicTo(12, 21, 4, 15.8f, 4, 9.8f); icHeart.cubicTo(4, 6.6f, 6.4f, 4.5f, 9, 4.5f);
        icHeart.cubicTo(10.4f, 4.5f, 11.4f, 5.1f, 12, 6.1f); icHeart.cubicTo(12.6f, 5.1f, 13.6f, 4.5f, 15, 4.5f);
        icHeart.cubicTo(17.6f, 4.5f, 20, 6.6f, 20, 9.8f); icHeart.cubicTo(20, 15.8f, 12, 21, 12, 21); icHeart.close();
        icPin = new Path(); icPin.addCircle(12, 9, 7, Path.Direction.CW); icPin.moveTo(6.2f, 13); icPin.lineTo(12, 22); icPin.lineTo(17.8f, 13); icPin.close();
    }

    // ================================================================= données

    public void setCfg(int ftp, int hrMax, double weight) { m.ftp = ftp; m.hrMax = hrMax; m.weight = weight; m.wPrime = 20000; }

    public void onSample(Ride.Sample x) {
        String was = state;
        s = x; state = x.state;
        if (!state.equals(was) && !quiet) {
            if (state.equals("rec")) toast(was.equals("pause") || was.equals("auto") ? "REPRISE" : "C'EST PARTI !", CYAN);
            else if (state.equals("auto")) toast("PAUSE AUTO", AMBER);
            if (state.equals("auto")) autoSince = now();
            if (was.equals("auto") && state.equals("rec") && pauseMenu) pauseMenu = false; // reparti : on referme
            else if (state.equals("pause")) { pauseMenu = true; pauseSel = 0; delArmed = 0; }
        }
        // arrêt prolongé en pause auto (> 1 min) : le menu de pause s'ouvre tout seul
        if (state.equals("auto") && !pauseMenu && !autoMenuShown && now() - autoSince > 60000) { pauseMenu = true; pauseSel = 0; autoMenuShown = true; }
        if (!state.equals("auto")) autoMenuShown = false;
        m.push(x.p, x.hr, x.cad, x.spd, x.alt, x.dist, state.equals("rec"));
        double p3 = m.p3();
        set("pwr", p3); set("wkg", p3 / m.weight); set("pftp", 100 * p3 / m.ftp);
        set("hr", x.hr); set("cad", x.cad); set("spd", x.spd); set("dist", m.dist / 1000);
        set("np", m.np()); set("if", m.IF()); set("tss", m.tss()); set("wbal", m.wbalPct());
        set("grade", m.grade); set("vam", m.vam()); set("alt", Double.isNaN(x.alt) ? 0 : x.alt);
        set("dplus", m.dplus); set("p30", m.p30());
        Metrics.Lap L = m.lap;
        set("lapP", L.ap()); set("lapNp", L.np()); set("lapHr", L.hr()); set("lapS", L.spd()); set("lapC", L.cad()); set("lapW", L.ap() / m.weight);
        int z = m.zoneOf(p3);
        if (z != lastZone) {
            if (lastZone >= 0) zoneFlashT = now();
            lastZone = z; zoneCol = Metrics.ZCOL[z];
        }
        invalidate();
    }

    public void sensorEvent(String text, boolean ok) { toast(text, ok ? CYAN : ORANGE); }

    static long now() { return System.currentTimeMillis(); }

    private void set(String k, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) v = 0;
        Tw t = tw.get(k);
        long n = now();
        if (t == null) { t = new Tw(); t.from = v; t.to = v; t.start = n; tw.put(k, t); return; }
        t.from = cur(t, n); t.to = v; t.start = n;
    }

    private static double cur(Tw t, long n) {
        double k = Math.min(1, (n - t.start) / 450.0);
        k = 1 - Math.pow(1 - k, 3);
        return t.from + (t.to - t.from) * k;
    }

    private double v(String k) { Tw t = tw.get(k); return t == null ? 0 : cur(t, now()); }

    // ================================================================= boutons

    public boolean isLearning() { return learnStep >= 0; }

    // résumé de fin de sortie
    private boolean summary;
    private String sumTime = "", sumDist = "", sumDplus = "", sumNp = "", sumTss = "", sumKj = "", sumSpd = "", sumFile;

    public void button(String b) {
        if (learnStep >= 0) return;
        if (summary) { summary = false; goHome(); return; }
        if (pauseMenu && (state.equals("pause") || state.equals("auto"))) { pauseButton(b); return; }
        pauseMenu = false;
        if (mode != M_DASH) { menuButton(b); return; }
        switch (b) {
            case "next": showPage(cur + 1, 1); break;
            case "prev": showPage(cur - 1, -1); break;
            case "startstop": confirmUntil = 0; host.startStop(); break;
            case "lap":
                if (state.equals("rec")) {
                    Metrics.Lap p = m.newLap();
                    host.lap();
                    lapFlashN = String.valueOf(p.n + 1);
                    lapFlashS = ms(p.t) + "  ·  " + Math.round(p.ap()) + " W  ·  " + f1(p.spd()) + " km/h";
                    lapFlashT = now();
                } else if (state.equals("pause") || state.equals("auto")) {
                    pauseMenu = true; pauseSel = 0; delArmed = 0;
                } else goHome();
                break;
        }
        invalidate();
    }

    // ---------------------------------------------------------------- menu de pause
    static final String[] PAUSE = {"REPRENDRE", "TERMINER ET ENREGISTRER", "VOIR MES DONNÉES", "SUPPRIMER LA SORTIE"};
    private boolean pauseMenu, autoMenuShown; private int pauseSel; private long delArmed, autoSince;

    private void pauseButton(String b) {
        if (b.equals("next")) pauseSel = (pauseSel + 1) % PAUSE.length;
        else if (b.equals("prev")) pauseSel = (pauseSel + PAUSE.length - 1) % PAUSE.length;
        else if (b.equals("lap")) pauseMenu = false;
        else if (b.equals("startstop")) pauseOk(pauseSel);
        if (pauseSel != 3) delArmed = 0;
        invalidate();
    }

    private void pauseOk(int i) {
        switch (i) {
            case 0: pauseMenu = false; if (!state.equals("auto")) host.startStop(); break;
            case 1: pauseMenu = false; endRide(); break;
            case 2: pauseMenu = false; break;
            default:
                if (now() - delArmed < 4000) {
                    pauseMenu = false; delArmed = 0;
                    host.discardRide(); m.reset(); lastZone = -1; goHome();
                    toast("SORTIE SUPPRIMÉE", ORANGE);
                } else delArmed = now();
        }
    }

    private void drawPauseMenu(Canvas c, long n) {
        pf.setColor(0xF7090E14); c.drawRect(0, 0, 480, H, pf);
        pf.setShader(new RadialGradient(240, 140, 360, 0x33FFB02E, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 480, H, pf); pf.setShader(null);
        text(c, state.equals("auto") ? "ARRÊT" : "PAUSE", 240, 104, bc8, 56, AMBER, Paint.Align.CENTER, .1f);
        if (state.equals("auto")) text(c, "pause automatique — repars pour reprendre", 240, 160, b6, 14, DIM, Paint.Align.CENTER, 0);
        text(c, hms(s.elapsed) + "   ·   " + String.format(Locale.US, "%.1f km", m.dist / 1000) + "   ·   " + i0(m.np()) + " W NP",
                240, 140, bc6, 20, MUTE, Paint.Align.CENTER, 0);
        String[] sub = {"START", "fichier .FIT + résumé", "rester en pause", now() - delArmed < 4000 ? "appuie encore pour confirmer" : "sans enregistrer"};
        int[] col = {CYAN, TXT, MUTE, ORANGE};
        float t0 = 172, rh = 86;
        for (int i = 0; i < PAUSE.length; i++) {
            boolean on = i == pauseSel;
            float t = t0 + i * (rh + 10), b = t + rh;
            boolean armed = i == 3 && now() - delArmed < 4000;
            pf.setColor(i == 0 ? (on ? CYAN : 0xFF0F2A27) : armed ? 0xFF3A1409 : on ? 0xFF13202B : TILE);
            rf.set(12, t, 468, b); c.drawRoundRect(rf, 16, 16, pf);
            ps.setColor(on ? (i == 3 ? ORANGE : i == 0 ? 0xFFFFFFFF : CYAN) : LINE); ps.setStrokeWidth(on ? 2.5f : 1); c.drawRoundRect(rf, 16, 16, ps);
            int tc = i == 0 ? (on ? BG : CYAN) : col[i];
            text(c, PAUSE[i], 30, t + 46, bc8, i == 0 ? 34 : 26, armed ? ORANGE : tc, Paint.Align.LEFT, .04f);
            text(c, sub[i], 30, t + 70, b6, 14, i == 0 && on ? 0xBF090E14 : MUTE, Paint.Align.LEFT, 0);
            text(c, "›", 448, t + 56, bc8, 36, i == 0 && on ? BG : DIM, Paint.Align.RIGHT, 0);
        }
        footBtns(c, "‹  DONNÉES", "VALIDER  ›");
    }

    private void pauseTap(float x, float y) {
        if (y > H - 64) { pauseButton(x < 240 ? "lap" : "startstop"); return; }
        int i = (int) Math.floor((y - 172) / 96);
        if (i >= 0 && i < PAUSE.length && y >= 172) { pauseSel = i; pauseOk(i); invalidate(); }
    }

    /** termine la sortie : enregistre, affiche le résumé */
    private void endRide() {
                    {
                        // résumé de la sortie avant remise à zéro
                        sumTime = ms(s.elapsed); sumDist = String.format(Locale.US, "%.2f", m.dist / 1000);
                        sumDplus = String.valueOf(Math.round(m.dplus)); sumNp = i0(m.np()); sumTss = i0(m.tss());
                        sumKj = i0(m.kj); sumSpd = f1(m.spdAvg());
                        try {
                            org.json.JSONObject r = new org.json.JSONObject();
                            r.put("t", System.currentTimeMillis()); r.put("dur", s.elapsed); r.put("dist", m.dist);
                            r.put("dplus", m.dplus); r.put("np", m.np()); r.put("tss", m.tss()); r.put("kj", m.kj); r.put("spd", m.spdAvg());
                            if (m.dist > 100 || s.elapsed > 60) host.saveRide(r);
                        } catch (Exception ignored) {}
                        String f = host.finishRide();
                        sumFile = f != null && !f.isEmpty() ? f : null;
                        summary = true;
                        m.reset(); lastZone = -1;
                    }
    }

    private void confirm(String kind, String t, String s1, String s2) {
        confirmKind = kind; confirmTitle = t; confirmSub1 = s1; confirmSub2 = s2; confirmUntil = now() + 4000;
    }

    private boolean confirmed(String kind) {
        if (confirmKind.equals(kind) && now() < confirmUntil) { confirmUntil = 0; return true; }
        return false;
    }

    public void startLearn() {
        learnStep = 0; learnMap.clear(); learnErr = "";
        host.setLearning(true);
        invalidate();
    }

    /** touche brute (apprentissage, ou touche non assignée) */
    public void onKey(int code) {
        if (learnStep < 0) { toast("TOUCHE " + code + " NON ASSIGNÉE", AMBER); return; }
        if (learnMap.containsKey(code)) { learnErr = "Déjà utilisé — choisis un autre bouton"; invalidate(); return; }
        learnMap.put(code, LEARN[learnStep][0]);
        learnStep++; learnErr = "";
        if (learnStep >= LEARN.length) {
            learnStep = -1;
            host.saveKeyMap(new HashMap<>(learnMap));
            host.setLearning(false);
            toast("BOUTONS ENREGISTRÉS ✓", CYAN);
        }
        invalidate();
    }

    private void showPage(int i, int d) {
        i = (i + PAGES.length) % PAGES.length;
        if (i == cur) return;
        prev = cur; cur = i; dir = d; pageT = now();
    }

    public void showPageNow(int i) { cur = (i + PAGES.length) % PAGES.length; prev = cur; pageT = 0; invalidate(); }
    public void skipSplash() { born = 0; }
    public void newLapQuiet() { m.newLap(); }
    public void demoSummary() { state = "pause"; endRide(); }

    public void toast(String t, int col) { toastTxt = t; toastCol = col; toastT = now(); invalidate(); }

    private float sx0;
    private boolean ignoreUp;
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            float x = e.getX() / sc, y = e.getY() / sc;
            sx0 = e.getX(); ignoreUp = false;
            float mb = mapBottom();
            if (mapShown && mode == M_DASH && y > HDR && y < mb) {
                if (x > 414 && Math.abs(y - (mb - 150)) < 25) { host.mapZoom(1); ignoreUp = true; return true; }
                if (x > 414 && Math.abs(y - (mb - 98)) < 25) { host.mapZoom(-1); ignoreUp = true; return true; }
                if (Math.abs(y - (mb - 46)) < 25 && x > (mapExplore ? 296 : 414)) {
                    mapExplore = !mapExplore; host.mapExplore(mapExplore); ignoreUp = true; invalidate(); return true;
                }
                if (mapExplore) return false; // la carte reçoit le geste (déplacement, pincement)
            }
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_UP && ignoreUp) { ignoreUp = false; return true; }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float dx = (e.getX() - sx0) / sc, x = e.getX() / sc, y = e.getY() / sc;
            if (learnStep >= 0) {
                if (y > H - 64) { learnStep = -1; host.setLearning(false); toast("CONFIGURATION ANNULÉE", AMBER); invalidate(); }
                return true;
            }
            if (summary) { summary = false; goHome(); return true; }
            if (pauseMenu && (state.equals("pause") || state.equals("auto"))) { pauseTap(x, y); return true; }
            if (now() < confirmUntil) {
                if (y > H - 64) button(x < 240 ? "startstop" : "lap");
                return true;
            }
            if (mode != M_DASH) { menuTap(x, y); return true; }
            boolean paused = state.equals("pause") || state.equals("auto");
            if (paused && Math.abs(dx) < 30 && y > H - BAND - 58 && y < H - BAND - 8) { button(x < 240 ? "startstop" : "lap"); return true; }
            if (Math.abs(dx) > 60) button(dx < 0 ? "next" : "prev");
            else if (y < 56) openMenu();              // appui sur l'en-tête : menu
            else if (y > H - BAND && x < 240) button("startstop");
            return true;
        }
        return super.onTouchEvent(e);
    }

    // ================================================================= style validé (nov. 2026)
    static final float HDR = 46, BAND = 104;
    static final int HAIR = 0xFF1C2836, BG2 = 0xFF06090D, DARKBAND = 0xFF0C131B;

    float mapBottom() { return H - BAND - 90; }
    float pivotY() { return HDR + (mapBottom() - HDR) * 0.62f; }

    private void lab(Canvas c, String s, float x, float y, Paint.Align a) { text(c, s, x, y, bc6, 16, MUTE, a, .06f); }

    private void hair(Canvas c, float x0, float y0, float x1, float y1) {
        pf.setColor(HAIR);
        if (y0 == y1) c.drawRect(x0, y0, x1, y0 + 1, pf); else c.drawRect(x0, y0, x0 + 1, y1, pf);
    }

    private void drawHeader(Canvas c) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        text(c, String.format(Locale.US, "%d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)),
                16, 33, bc7, 26, TXT, Paint.Align.LEFT, 0);
        if (logoNct != null) {
            float h = 22, w = logoNct.getWidth() * h / logoNct.getHeight();
            rf.set(240 - w / 2, 12, 240 + w / 2, 12 + h); c.drawBitmap(logoNct, null, rf, pb);
        }
        // batterie
        float bx = 464;
        ps.setColor(TXT); ps.setStrokeWidth(2); rf.set(bx - 30, 16, bx - 2, 31); c.drawRoundRect(rf, 3, 3, ps);
        pf.setColor(TXT); rf.set(bx - 2, 20, bx + 1, 27); c.drawRect(rf, pf);
        if (s.bat >= 0) { rf.set(bx - 27, 19, bx - 27 + 22 * s.bat / 100f, 28); c.drawRect(rf, pf); }
        String bt = s.bat >= 0 ? s.bat + "%" : "--";
        pt.setTypeface(bc7); pt.setTextSize(22); pt.setLetterSpacing(0);
        float tw = pt.measureText(bt);
        text(c, bt, bx - 36, 32, bc7, 22, TXT, Paint.Align.RIGHT, 0);
        // capteurs
        float ix = bx - 40 - tw - 6;
        boolean blink = (now() / 500) % 2 == 0;
        int off = blink ? 0xFF3A4858 : 0x553A4858;
        icon(c, icPin, ix - 22, 13, s.sGps ? CYAN : off);
        icon(c, icHeart, ix - 48, 13, s.sHr ? 0xFFFF6B3D : off);
        icon(c, icBolt, ix - 74, 13, s.sPwr ? CYAN : off);
    }

    /** bandeau bas : chrono + distance (ou arrivée quand un parcours sera chargé) */
    private void drawFooter(Canvas c) {
        float y = H - BAND;
        pf.setColor(DARKBAND); c.drawRect(0, y, 480, H, pf);
        pf.setColor(CYAN); c.drawRect(0, y, 480, y + 2, pf);
        boolean paused = state.equals("pause") || state.equals("auto");
        int dc = state.equals("rec") ? ORANGE : paused ? AMBER : 0xFF3A4858;
        boolean on = !state.equals("rec") || (now() / 600) % 2 == 0;
        pf.setColor(on ? dc : alpha(dc, 70)); c.drawCircle(23, y + 25, 5, pf);
        String l = state.equals("rec") ? "CHRONO" : state.equals("auto") ? "PAUSE AUTO" : state.equals("pause") ? "PAUSE" : "PRÊT";
        text(c, l, 34, y + 31, bc6, 16, paused ? AMBER : MUTE, Paint.Align.LEFT, .06f);
        text(c, hms(s.elapsed), 18, y + 80, bc7, 54, TXT, Paint.Align.LEFT, -.01f);
        lab(c, "DISTANCE", 462, y + 31, Paint.Align.RIGHT);
        String d = String.format(Locale.US, "%.1f", m.dist / 1000);
        pt.setTypeface(bc6); pt.setTextSize(26); pt.setLetterSpacing(0);
        float kw = pt.measureText(" km");
        text(c, " km", 462, y + 80, bc6, 26, CYAN, Paint.Align.RIGHT, 0);
        text(c, d, 462 - kw, y + 80, bc7, 54, CYAN, Paint.Align.RIGHT, -.01f);
    }

    /** cellule de grille : libellé + valeur, alignement selon la colonne */
    private void cell(Canvas c, int col, float t, String label, String val, int vcol, String unit) {
        float x = col == 0 ? 16 : col == 1 ? 240 : 464;
        Paint.Align a = col == 0 ? Paint.Align.LEFT : col == 1 ? Paint.Align.CENTER : Paint.Align.RIGHT;
        text(c, label, x, t + 24, bc6, 17, MUTE, a, .05f);
        if (unit == null || unit.isEmpty()) { text(c, val, x, t + 76, bc7, 56, vcol, a, -.01f); return; }
        pt.setTypeface(bc7); pt.setTextSize(56); pt.setLetterSpacing(-.01f);
        float vw = pt.measureText(val);
        pt.setTypeface(bc6); pt.setTextSize(18); pt.setLetterSpacing(0);
        float uw = pt.measureText(" " + unit), tot = vw + uw;
        float x0 = a == Paint.Align.LEFT ? x : a == Paint.Align.CENTER ? x - tot / 2 : x - tot;
        text(c, val, x0, t + 76, bc7, 56, vcol, Paint.Align.LEFT, -.01f);
        text(c, " " + unit, x0 + vw, t + 76, bc6, 18, MUTE, Paint.Align.LEFT, 0);
    }

    private void gridLines(Canvas c, float t, int rows, float rh) {
        hair(c, 0, t, 480, t);
        for (int r = 0; r < rows; r++) {
            float y = t + r * rh;
            if (r > 0) hair(c, 0, y, 480, y);
            hair(c, 160, y + 6, 160, y + rh - 6); hair(c, 320, y + 6, 320, y + rh - 6);
        }
    }

    private String tempStr() { float[] w = host.wind(); return w != null && w.length > 4 && !Float.isNaN(w[4]) ? String.valueOf(Math.round(w[4])) : "--"; }

    /** coût du vent en watts (positif = te freine), ou NaN */
    private double windCost() {
        float[] w = host.wind();
        double v = s.spd / 3.6;
        if (w == null || v < 1.5) return Double.NaN;
        double head = w[1] / 3.6 * 0.7 * Math.cos(Math.toRadians(windRel(w[0]))); // vent à hauteur de cycliste ≈ 70 % du vent à 10 m
        double cda = 0.32, rho = 1.2, va = v + head;
        return 0.5 * rho * cda * v * (va * Math.abs(va) - v * v);
    }

    private void corner(Canvas c, boolean left, float yl, String label, String val, int col, String unit, float size) {
        float x = left ? 14 : 466;
        Paint.Align a = left ? Paint.Align.LEFT : Paint.Align.RIGHT;
        text(c, label, x, yl, bc6, 15, MUTE, a, .06f);
        pt.setTypeface(bc8); pt.setTextSize(size); pt.setLetterSpacing(0);
        float vw = pt.measureText(val);
        pt.setTypeface(bc8); pt.setTextSize(size * .43f);
        float uw = unit == null ? 0 : pt.measureText(unit);
        float x0 = left ? x : x - vw - uw;
        text(c, val, x0, yl + size * .95f, bc8, size, col, Paint.Align.LEFT, 0);
        if (unit != null) text(c, unit, x0 + vw, yl + size * .95f, bc8, size * .43f, left ? col : MUTE, Paint.Align.LEFT, 0);
    }

    // ---------------------------------------------------------------- LIVE : lunette boussole

    private void pageLive(Canvas c, float top, float bottom) {
        float gridT = bottom - 188 - 8, cx = 240, cy = top + (gridT - top) / 2 + 10;
        float R = 190;
        // lunette
        ps.setStrokeCap(Paint.Cap.BUTT);
        ps.setColor(0xFF111A24); ps.setStrokeWidth(18); c.drawCircle(cx, cy, R, ps);
        float hd = shownBearing;
        ps.setColor(0xFF2A3A4C); ps.setStrokeWidth(2);
        for (int i = 0; i < 72; i++) {
            double a = Math.toRadians(i * 5 - hd);
            float l = i % 18 == 0 ? 14 : i % 6 == 0 ? 9 : 5;
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            c.drawLine(cx + (R - l / 2) * sx, cy + (R - l / 2) * sy, cx + (R + l / 2) * sx, cy + (R + l / 2) * sy, ps);
        }
        String[] card = {"N", "E", "S", "O"};
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(i * 90 - hd);
            float x = cx + R * (float) Math.sin(a), y = cy - R * (float) Math.cos(a);
            pf.setColor(BG2); c.drawCircle(x, y, 12, pf);
            text(c, card[i], x, y + 6, bc8, 18, i == 0 ? ORANGE : 0xFF8A9BB0, Paint.Align.CENTER, 0);
        }
        // arc de puissance
        float pr = 159;
        rf.set(cx - pr, cy - pr, cx + pr, cy + pr);
        ps.setStrokeCap(Paint.Cap.ROUND);
        ps.setColor(0xFF141F2B); ps.setStrokeWidth(22); c.drawArc(rf, 139, 262, false, ps);
        double tgt = Math.max(0, Math.min(1, v("pwr") / (m.ftp * 1.6)));
        arcShown += (tgt - arcShown) * 0.25;
        float sw = (float) (262 * arcShown);
        if (sw > 0.5f) {
            android.graphics.SweepGradient sg = new android.graphics.SweepGradient(cx, cy,
                    new int[]{CYAN, CYAN, 0xFF9BE36A, AMBER, ORANGE, ORANGE}, new float[]{0, .01f, .55f * 262 / 360, .75f * 262 / 360, 262f / 360, 1});
            android.graphics.Matrix mx = new android.graphics.Matrix(); mx.setRotate(139, cx, cy); sg.setLocalMatrix(mx);
            ps.setShader(sg); c.drawArc(rf, 139, sw, false, ps); ps.setShader(null);
        }
        float[] hp = pt(cx, cy, 139 + sw, pr);
        pf.setColor(BG2); c.drawCircle(hp[0], hp[1], 15, pf);
        pf.setColor(0xFFFFFFFF); c.drawCircle(hp[0], hp[1], 11, pf);
        // arcs FC (gauche) et W'bal (droite)
        float ir = 138;
        rf.set(cx - ir, cy - ir, cx + ir, cy + ir);
        ps.setStrokeWidth(8);
        ps.setColor(0xFF141F2B); c.drawArc(rf, 158.7f, 56.8f, false, ps); c.drawArc(rf, 21.3f, -56.8f, false, ps);
        double hrR = s.hr > 0 ? Math.max(0.02, Math.min(1, (s.hr - .5 * m.hrMax) / (.5 * m.hrMax))) : 0;
        if (hrR > 0) { ps.setColor(0xFFFF6B3D); c.drawArc(rf, 158.7f, (float) (56.8 * hrR), false, ps); }
        double wb = Math.max(0.02, v("wbal") / 100);
        ps.setColor(wcol(v("wbal"))); c.drawArc(rf, 21.3f, (float) (-56.8 * wb), false, ps);
        ps.setStrokeCap(Paint.Cap.BUTT);
        // point de vent sur la lunette (direction d'où il vient, relative à ta marche)
        float[] w = host.wind();
        if (w != null) {
            double a = Math.toRadians(windRel(w[0]));
            float x = cx + R * (float) Math.sin(a), y = cy - R * (float) Math.cos(a);
            pf.setColor(ORANGE); c.drawCircle(x, y, 22, pf);
            ps.setColor(0xFFFFFFFF); ps.setStrokeWidth(3); c.drawCircle(x, y, 22, ps);
            text(c, String.valueOf(Math.round(w[1])), x, y + 8, bc8, 22, 0xFFFFFFFF, Paint.Align.CENTER, 0);
        }
        // centre
        String pw = i0(v("pwr"));
        pt.setTypeface(bc8); pt.setTextSize(64); pt.setLetterSpacing(0);
        float pwW = pt.measureText(pw);
        pt.setTypeface(bc8); pt.setTextSize(22);
        float wW = pt.measureText(" W");
        text(c, pw, cx - (pwW + wW) / 2, cy - 46, bc8, 64, AMBER, Paint.Align.LEFT, 0);
        text(c, " W", cx - (pwW + wW) / 2 + pwW, cy - 46, bc8, 22, MUTE, Paint.Align.LEFT, 0);
        text(c, f1(v("spd")), cx, cy + 52, bc8, 112, TXT, Paint.Align.CENTER, -.03f);
        text(c, String.format(Locale.US, "%.2f", m.spdAvg()), cx, cy + 92, bc7, 30, CYAN, Paint.Align.CENTER, 0);
        path.reset(); path.moveTo(cx, cy + 116); path.lineTo(cx + 11, cy + 142); path.lineTo(cx, cy + 136); path.lineTo(cx - 11, cy + 142); path.close();
        pf.setColor(TXT); c.drawPath(path, pf);
        // coins
        corner(c, true, cy - 210, "TEMP.", tempStr(), TXT, "°C", 46);
        double wc = windCost();
        String wcs = Double.isNaN(wc) ? "--" : (wc >= 0 ? "+" : "−") + Math.round(Math.abs(wc));
        corner(c, false, cy - 210, "VENT", wcs, Double.isNaN(wc) ? MUTE : wc >= 0 ? ORANGE : CYAN, " W", 46);
        text(c, s.hr > 0 ? i0(v("hr")) : "--", 14, cy + 186, bc8, 40, 0xFFFF6B3D, Paint.Align.LEFT, 0);
        text(c, "♥ BPM", 14, cy + 202, bc6, 13, MUTE, Paint.Align.LEFT, .08f);
        text(c, i0(v("wbal")) + "%", 466, cy + 186, bc8, 40, wcol(v("wbal")), Paint.Align.RIGHT, 0);
        text(c, "W'BAL", 466, cy + 202, bc6, 13, MUTE, Paint.Align.RIGHT, .08f);
        // grille
        float rh = 94;
        gridLines(c, gridT, 2, rh);
        cell(c, 0, gridT, "TOUR " + m.lap.n, ms(m.lap.t), TXT, null);
        cell(c, 1, gridT, "CADENCE", s.sPwr || s.sCsc ? i0(v("cad")) : "--", TXT, null);
        cell(c, 2, gridT, "NP", i0(v("np")), TXT, null);
        cell(c, 0, gridT + rh, "D+", i0(v("dplus")), TXT, null);
        cell(c, 1, gridT + rh, "PENTE %", f1(v("grade")), gcol(m.grade), null);
        cell(c, 2, gridT + rh, "ALTITUDE", i0(v("alt")), TXT, null);
    }

    // ---------------------------------------------------------------- CARTE

    private void pageMap(Canvas c, float top, float bottom) {
        float mb = mapBottom();
        boolean live = mapShown && cur == P_MAP;
        if (!live) {
            pf.setColor(0xFF0C1219); c.drawRect(0, top, 480, mb, pf);
            if (!host.mapReady()) {
                text(c, "AUCUNE CARTE TROUVÉE", 240, (top + mb) / 2 - 30, bc7, 26, TXT, Paint.Align.CENTER, .05f);
                text(c, "Copie un fichier .map (Mapsforge)", 240, (top + mb) / 2 + 6, b6, 16, MUTE, Paint.Align.CENTER, 0);
                text(c, "dans le dossier NCT/maps du compteur", 240, (top + mb) / 2 + 30, b6, 16, MUTE, Paint.Align.CENTER, 0);
                text(c, host.mapInfo(), 240, (top + mb) / 2 + 66, b6, 13, DIM, Paint.Align.CENTER, 0);
            }
        } else {
            pf.setShader(new LinearGradient(0, top, 0, top + 110, 0xEE06090D, 0x0006090D, Shader.TileMode.CLAMP));
            c.drawRect(0, top, 480, top + 110, pf);
            pf.setShader(new LinearGradient(0, mb - 60, 0, mb, 0x0006090D, 0xEE06090D, Shader.TileMode.CLAMP));
            c.drawRect(0, mb - 60, 480, mb, pf); pf.setShader(null);
            float px = 240, py = pivotY();
            if (mapExplore) { float[] mp = host.mapPos(); if (mp != null) { px = mp[0] / sc; py = mp[1] / sc; } }
            drawWindRing(c, px, py);
            c.save(); c.translate(px, py); c.rotate(shownBearing - mapRot);
            pf.setColor(0x5500E5C8); c.drawCircle(0, 0, 26, pf);
            path.reset(); path.moveTo(0, -18); path.lineTo(13, 13); path.lineTo(0, 6); path.lineTo(-13, 13); path.close();
            pf.setColor(0xFFFFFFFF); c.drawPath(path, pf);
            ps.setColor(BG2); ps.setStrokeWidth(2.5f); ps.setStrokeJoin(Paint.Join.ROUND); c.drawPath(path, ps);
            c.restore();
            // coins : température / coût du vent
            corner(c, true, top + 22, "TEMP.", tempStr(), TXT, "°C", 40);
            double wc = windCost();
            corner(c, false, top + 22, "VENT", Double.isNaN(wc) ? "--" : (wc >= 0 ? "+" : "−") + Math.round(Math.abs(wc)),
                    Double.isNaN(wc) ? MUTE : wc >= 0 ? ORANGE : CYAN, " W", 40);
            // boutons ronds : zoom + / − / exploration
            roundBtn(c, 444, mb - 150, "+"); roundBtn(c, 444, mb - 98, "−");
            if (mapExplore) {
                pf.setColor(CYAN); rf.set(300, mb - 70, 466, mb - 24); c.drawRoundRect(rf, 23, 23, pf);
                text(c, "◎ RECENTRER", 383, mb - 40, bc7, 18, BG, Paint.Align.CENTER, .06f);
            } else {
                roundBtn(c, 444, mb - 46, null);
                ps.setColor(TXT); ps.setStrokeWidth(2.5f); c.drawCircle(441, mb - 49, 7, ps); c.drawLine(446, mb - 44, 452, mb - 38, ps);
            }
            // petite boussole
            pf.setColor(0xCC06090D); c.drawCircle(36, mb - 46, 20, pf);
            ps.setColor(0xFF2A3A4C); ps.setStrokeWidth(1); c.drawCircle(36, mb - 46, 20, ps);
            c.save(); c.rotate(-mapRot, 36, mb - 46);
            path.reset(); path.moveTo(36, mb - 63); path.lineTo(41, mb - 52); path.lineTo(31, mb - 52); path.close();
            pf.setColor(ORANGE); c.drawPath(path, pf);
            c.restore();
            text(c, "N", 36, mb - 40, bc8, 12, TXT, Paint.Align.CENTER, 0);
        }
        // ligne de données
        pf.setColor(BG2); c.drawRect(0, mb, 480, bottom, pf);
        gridLines(c, mb, 1, 90);
        cell(c, 0, mb, "PUISSANCE", i0(v("pwr")), AMBER, null);
        cell(c, 1, mb, "VITESSE", f1(v("spd")), TXT, null);
        cell(c, 2, mb, "FC", s.hr > 0 ? i0(v("hr")) : "--", 0xFFFF6B3D, null);
    }

    private void roundBtn(Canvas c, float x, float y, String l) {
        pf.setColor(0xCC06090D); c.drawCircle(x, y, 22, pf);
        ps.setColor(0xFF2A3A4C); ps.setStrokeWidth(1); c.drawCircle(x, y, 22, ps);
        if (l != null) text(c, l, x, y + 10, bc8, 28, TXT, Paint.Align.CENTER, 0);
    }

    private void drawWindRing(Canvas c, float px, float py) {
        float[] w = host.wind();
        float R = 54;
        ps.setColor(0x9906090D); ps.setStrokeWidth(8); c.drawCircle(px, py, R, ps);
        ps.setColor(0x66FFFFFF); ps.setStrokeWidth(1.5f); c.drawCircle(px, py, R, ps);
        if (w == null) return;
        double a = Math.toRadians(w[0] - mapRot);
        float x = px + R * (float) Math.sin(a), y = py - R * (float) Math.cos(a);
        pf.setColor(ORANGE); c.drawCircle(x, y, 17, pf);
        ps.setColor(0xFFFFFFFF); ps.setStrokeWidth(2.5f); c.drawCircle(x, y, 17, ps);
        text(c, String.valueOf(Math.round(w[1])), x, y + 7, bc8, 18, 0xFFFFFFFF, Paint.Align.CENTER, 0);
    }

    // ---------------------------------------------------------------- EFFORT

    private void pageEffort(Canvas c, float top, float bottom) {
        float cx = 240, cy = top + 154, R = 118;
        double pct = v("wbal");
        int on = (int) Math.round(pct / 100 * 40), wc = wcol(pct);
        ps.setStrokeWidth(26); ps.setStrokeCap(Paint.Cap.BUTT);
        rf.set(cx - R, cy - R, cx + R, cy + R);
        for (int i = 0; i < 40; i++) {
            ps.setColor(i < on ? wc : 0xFF1A2532);
            c.drawArc(rf, -90 + i * 9 + 1.2f, 6.6f, false, ps);
        }
        String p = i0(pct);
        pt.setTypeface(bc8); pt.setTextSize(84); pt.setLetterSpacing(0);
        float pw = pt.measureText(p);
        pt.setTextSize(34); float uw = pt.measureText("%");
        text(c, p, cx - (pw + uw) / 2, cy + 18, bc8, 84, wc, Paint.Align.LEFT, 0);
        text(c, "%", cx - (pw + uw) / 2 + pw, cy + 18, bc8, 34, wc, Paint.Align.LEFT, 0);
        text(c, "RÉSERVOIR W'BAL", cx, cy + 52, bc7, 16, MUTE, Paint.Align.CENTER, .12f);
        text(c, f1(m.wbal / 1000) + " / " + i0(m.wPrime / 1000) + " kJ", cx, cy - 46, bc6, 16, MUTE, Paint.Align.CENTER, 0);
        // coins
        lab(c, "NP", 16, top + 34, Paint.Align.LEFT);           text(c, i0(v("np")), 16, top + 78, bc7, 48, TXT, Paint.Align.LEFT, 0);
        lab(c, "IF", 464, top + 34, Paint.Align.RIGHT);         text(c, String.format(Locale.US, "%.2f", v("if")), 464, top + 78, bc7, 48, TXT, Paint.Align.RIGHT, 0);
        lab(c, "TSS", 16, top + 250, Paint.Align.LEFT);         text(c, i0(v("tss")), 16, top + 292, bc7, 48, TXT, Paint.Align.LEFT, 0);
        lab(c, "TRAVAIL", 464, top + 250, Paint.Align.RIGHT);
        pt.setTypeface(bc6); pt.setTextSize(18); float kw = pt.measureText(" kJ");
        text(c, " kJ", 464, top + 292, bc6, 18, MUTE, Paint.Align.RIGHT, 0);
        text(c, i0(m.kj), 464 - kw, top + 292, bc7, 48, TXT, Paint.Align.RIGHT, 0);
        // zones
        float zt = top + 302, gridT = bottom - 90;
        hair(c, 0, zt, 480, zt);
        lab(c, "TEMPS DANS LES ZONES", 16, zt + 24, Paint.Align.LEFT);
        long tot = 0, zmax = 1; for (long x : m.zoneSec) { tot += x; zmax = Math.max(zmax, x); }
        text(c, hms(tot), 464, zt + 24, bc6, 16, TXT, Paint.Align.RIGHT, 0);
        float bx = 16, bw = 448;
        for (int i = 0; i < 7; i++) {
            float w = tot > 0 ? bw * m.zoneSec[i] / tot : (i == 0 ? bw : 0);
            pf.setColor(tot > 0 ? Metrics.ZCOL[i] : 0xFF1A2532); c.drawRect(bx, zt + 36, bx + w, zt + 52, pf); bx += w;
        }
        float rowH = Math.max(20, (gridT - (zt + 62)) / 7f);
        int cz = Math.max(0, lastZone);
        for (int i = 0; i < 7; i++) {
            float y = zt + 62 + rowH * i + rowH / 2;
            text(c, Metrics.ZN[i], 16, y + 6, bc7, 18, Metrics.ZCOL[i], Paint.Align.LEFT, 0);
            pf.setColor(0xFF141D27); rf.set(52, y - 4, 404, y + 4); c.drawRoundRect(rf, 4, 4, pf);
            float w = (float) (352.0 * m.zoneSec[i] / zmax);
            if (w > 1) { pf.setColor(Metrics.ZCOL[i]); rf.set(52, y - 4, 52 + Math.max(8, w), y + 4); c.drawRoundRect(rf, 4, 4, pf); }
            text(c, ms(m.zoneSec[i]), 464, y + 6, bc7, 18, i == cz && state.equals("rec") ? TXT : MUTE, Paint.Align.RIGHT, 0);
        }
        gridLines(c, gridT, 1, 90);
        cell(c, 0, gridT, "MOY", i0(m.avgPower()), TXT, "W");
        cell(c, 1, gridT, "MAX", i0(m.maxPower), TXT, "W");
        cell(c, 2, gridT, "KCAL", i0(m.kj * 1.0), TXT, null);
    }

    // ---------------------------------------------------------------- GRIMPÉE

    private void pageClimb(Canvas c, float top, float bottom) {
        float gridT = bottom - 188, climbT = gridT - 56;
        lab(c, "PENTE", 16, top + 22, Paint.Align.LEFT);
        String g = f1(v("grade"));
        int gc = gcol(m.grade);
        pt.setTypeface(bc7); pt.setTextSize(104); pt.setLetterSpacing(-.01f);
        float gw = pt.measureText(g);
        text(c, g, 16, top + 112, bc7, 104, gc, Paint.Align.LEFT, -.01f);
        text(c, "%", 16 + gw, top + 112, bc7, 40, gc, Paint.Align.LEFT, 0);
        lab(c, "VAM", 464, top + 22, Paint.Align.RIGHT);       text(c, i0(v("vam")), 464, top + 68, bc7, 52, TXT, Paint.Align.RIGHT, 0);
        lab(c, "ALTITUDE", 464, top + 96, Paint.Align.RIGHT);
        pt.setTypeface(bc6); pt.setTextSize(18); float mw = pt.measureText(" m");
        text(c, " m", 464, top + 142, bc6, 18, MUTE, Paint.Align.RIGHT, 0);
        text(c, i0(v("alt")), 464 - mw, top + 142, bc7, 52, TXT, Paint.Align.RIGHT, 0);
        drawProfile(c, 0, top + 150, 480, climbT);
        // montée en cours
        hair(c, 0, climbT, 480, climbT);
        if (m.inClimb) {
            lab(c, "MONTÉE EN COURS", 16, climbT + 24, Paint.Align.LEFT);
            text(c, String.format(Locale.US, "%.1f km  ·  +%d m  ·  %.1f %% moy", m.climbDist() / 1000, Math.round(m.climbGain()), m.climbAvgGrade()),
                    464, climbT + 24, bc7, 16, TXT, Paint.Align.RIGHT, 0);
            float f = (float) Math.min(1, m.climbDist() / 3000);
            pf.setColor(0xFF1A2532); rf.set(16, climbT + 36, 464, climbT + 46); c.drawRoundRect(rf, 5, 5, pf);
            pf.setShader(new LinearGradient(16, 0, 464, 0, new int[]{0xFF9BE36A, 0xFFFFD23E, 0xFFFF8A2E}, null, Shader.TileMode.CLAMP));
            rf.set(16, climbT + 36, 16 + 448 * f, climbT + 46); c.drawRoundRect(rf, 5, 5, pf); pf.setShader(null);
        } else lab(c, "PAS DE MONTÉE EN COURS", 16, climbT + 32, Paint.Align.LEFT);
        float rh = 94;
        gridLines(c, gridT, 2, rh);
        cell(c, 0, gridT, "D+", i0(v("dplus")), TXT, null);
        cell(c, 1, gridT, "PUISS. 30S", i0(v("p30")), AMBER, null);
        cell(c, 2, gridT, "W/KG", f1(v("p30") / m.weight), TXT, null);
        cell(c, 0, gridT + rh, "VITESSE", f1(v("spd")), TXT, null);
        cell(c, 1, gridT + rh, "CADENCE", s.sPwr || s.sCsc ? i0(v("cad")) : "--", TXT, null);
        cell(c, 2, gridT + rh, "FC", s.hr > 0 ? i0(v("hr")) : "--", 0xFFFF6B3D, null);
    }

    /** profil des 5 derniers km, coloré selon la pente */
    private void drawProfile(Canvas c, float l, float t, float r, float b) {
        int n = m.prof.size();
        if (n < 2) {
            text(c, "Le profil se dessine pendant la sortie", (l + r) / 2, (t + b) / 2 + 30, b6, 15, DIM, Paint.Align.CENTER, 0);
            return;
        }
        double d0 = m.prof.get(0).d, d1 = m.prof.get(n - 1).d, span = Math.max(1000, d1 - d0);
        double mn = 1e9, mx = -1e9;
        for (Metrics.Pt p : m.prof) { mn = Math.min(mn, p.a); mx = Math.max(mx, p.a); }
        if (mx - mn < 40) { double mid = (mx + mn) / 2; mn = mid - 20; mx = mid + 20; }
        float W = r - l, Hh = b - t - 16;
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            Metrics.Pt p = m.prof.get(i);
            xs[i] = (float) (r - 14 - (d1 - p.d) / span * (W - 14));
            ys[i] = (float) (b - (p.a - mn) / (mx - mn) * Hh);
        }
        for (int i = 0; i < n - 1; i++) {
            int j0 = Math.max(0, i - 3), j1 = Math.min(n - 1, i + 4);
            double dd = m.prof.get(j1).d - m.prof.get(j0).d;
            double gr = dd > 0 ? 100 * (m.prof.get(j1).a - m.prof.get(j0).a) / dd : 0;
            path.reset(); path.moveTo(xs[i], b); path.lineTo(xs[i], ys[i]); path.lineTo(xs[i + 1], ys[i + 1]); path.lineTo(xs[i + 1], b); path.close();
            pf.setColor(alpha(gcol(gr), 220)); c.drawPath(path, pf);
        }
        pf.setColor(0xFFFFFFFF); c.drawCircle(xs[n - 1], ys[n - 1], 9, pf);
        ps.setColor(BG2); ps.setStrokeWidth(4); c.drawCircle(xs[n - 1], ys[n - 1], 9, ps);
    }

    // ---------------------------------------------------------------- TOUR : vitesse moyenne

    private void pageLap(Canvas c, float top, float bottom) {
        Metrics.Lap L = m.lap, P = m.prevLap;
        text(c, "VITESSE MOYENNE · TOUR", 16, top + 24, bc6, 16, MUTE, Paint.Align.LEFT, .06f);
        pt.setTypeface(bc6); pt.setTextSize(16); pt.setLetterSpacing(.06f);
        text(c, String.valueOf(L.n), 16 + pt.measureText("VITESSE MOYENNE · TOUR "), top + 24, bc6, 16, CYAN, Paint.Align.LEFT, .06f);
        String sp = String.format(Locale.US, "%.2f", L.spd());
        pt.setTypeface(bc7); pt.setTextSize(140); pt.setLetterSpacing(-.01f);
        float sw = pt.measureText(sp);
        text(c, sp, 14, top + 152, bc7, 140, TXT, Paint.Align.LEFT, -.01f);
        text(c, " km/h", 14 + sw, top + 152, bc6, 24, MUTE, Paint.Align.LEFT, 0);
        if (P != null && P.spd() > 0) {
            double d = L.spd() - P.spd();
            text(c, (d >= 0 ? "▲ +" : "▼ ") + String.format(Locale.US, "%.2f", d) + " km/h", 16, top + 192, bc8, 26, d >= 0 ? CYAN : ORANGE, Paint.Align.LEFT, 0);
            text(c, "TOUR " + P.n + " · " + String.format(Locale.US, "%.2f", P.spd()), 464, top + 192, bc6, 16, MUTE, Paint.Align.RIGHT, .04f);
        } else text(c, "Appuie sur TOUR pour démarrer un nouveau tour", 16, top + 190, b6, 15, DIM, Paint.Align.LEFT, 0);
        // graphique : vitesse moyenne par tour
        float gridT = bottom - 188, ct = top + 210;
        hair(c, 0, ct, 480, ct);
        lab(c, "VITESSE MOYENNE PAR TOUR", 16, ct + 24, Paint.Align.LEFT);
        java.util.List<Metrics.Lap> all = new java.util.ArrayList<>(m.laps);
        all.add(L);
        int k = Math.min(6, all.size());
        java.util.List<Metrics.Lap> show = all.subList(all.size() - k, all.size());
        double mn = 1e9, mx = 0;
        for (Metrics.Lap x : show) { mn = Math.min(mn, x.spd()); mx = Math.max(mx, x.spd()); }
        double lo = Math.max(0, mn - 3), hi = Math.max(lo + 4, mx + 1);
        float base = gridT - 12, maxH = base - (ct + 60);
        float bw = Math.min(92, (448 - (k - 1) * 20) / (float) k);
        for (int i = 0; i < k; i++) {
            Metrics.Lap x = show.get(i);
            boolean curL = i == k - 1;
            float hh = (float) Math.max(18, (x.spd() - lo) / (hi - lo) * maxH);
            float bx = 16 + i * (bw + 20);
            pf.setColor(curL ? CYAN : 0xFF2A3A4C); rf.set(bx, base - hh, bx + bw, base); c.drawRoundRect(rf, 3, 3, pf);
            text(c, String.format(Locale.US, "%.2f", x.spd()), bx + bw / 2, base - hh - 8, bc7, 20, curL ? TXT : 0xFF8A9BB0, Paint.Align.CENTER, 0);
            text(c, "T" + x.n, bx + bw / 2, base - 8, bc8, 16, curL ? BG : MUTE, Paint.Align.CENTER, 0);
        }
        float rh = 94;
        gridLines(c, gridT, 2, rh);
        cell(c, 0, gridT, "TEMPS TOUR", ms(L.t), TXT, null);
        cell(c, 1, gridT, "DISTANCE", String.format(Locale.US, "%.2f", L.dist / 1000), TXT, null);
        cell(c, 2, gridT, "PUISS. MOY", i0(L.ap()), TXT, null);
        cell(c, 0, gridT + rh, "FC MOY", L.hr() > 0 ? i0(L.hr()) : "--", TXT, null);
        cell(c, 1, gridT + rh, "CADENCE", L.cad() > 0 ? i0(L.cad()) : "--", TXT, null);
        cell(c, 2, gridT + rh, "VIT. GLOBALE", String.format(Locale.US, "%.2f", m.spdAvg()), TXT, null);
    }

    // ================================================================= dessin

    @Override protected void onDraw(Canvas c) {
        long n = now();
        sc = getWidth() / 480f;
        H = getHeight() / sc;
        c.save();
        c.scale(sc, sc);

        float top = HDR, bottom = H - BAND;
        double k0 = Math.min(1, (n - pageT) / 380.0);
        boolean showMap = cur == P_MAP && k0 >= 1 && mode == M_DASH && !summary && learnStep < 0
                && n >= confirmUntil && n - born > 3200 && host.mapReady();
        if (showMap != mapShown) {
            mapShown = showMap; final boolean v = showMap;
            if (!v && mapExplore) { mapExplore = false; post(() -> host.mapExplore(false)); }
            post(() -> host.setMapVisible(v));
        }
        c.save();
        if (mapShown) c.clipRect(0, top, 480, mapBottom(), android.graphics.Region.Op.DIFFERENCE);
        c.drawColor(BG2);
        c.restore();
        drawHeader(c);
        // carte orientée dans le sens de la marche
        float db = ((s.bearing - shownBearing + 540) % 360) - 180;
        shownBearing = (shownBearing + db * 0.15f + 360) % 360;
        mapRot = mapExplore || !headingUp ? 0 : shownBearing;
        if (mapShown) host.mapTransform(240 * sc, pivotY() * sc, mapRot);

        // pages (glissement)
        c.save();
        c.clipRect(0, top, 480, bottom);
        double k = Math.min(1, (n - pageT) / 380.0);
        k = 1 - Math.pow(1 - k, 3);
        if (k < 1) {
            c.save(); c.translate((float) (-dir * 480 * k), 0); drawPage(c, prev, top, bottom); c.restore();
            c.save(); c.translate((float) (dir * 480 * (1 - k)), 0); drawPage(c, cur, top, bottom); c.restore();
        } else drawPage(c, cur, top, bottom);
        c.restore();

        drawFooter(c);
        if (mode != M_DASH) drawMenus(c, n);
        if (pauseMenu && (state.equals("pause") || state.equals("auto"))) drawPauseMenu(c, n);
        else if (pauseMenu) pauseMenu = false;
        drawOverlays(c, n);
        drawSplash(c, n);
        c.restore();

        postInvalidateDelayed(33);
    }

    private boolean mapShown;
    private float shownBearing = 0, mapRot = 0;
    public boolean headingUp = true;
    /** position du cycliste à l'écran : aux 2/3 bas de la carte pour voir la route devant */

    // ---------------------------------------------------------------- page carte


    /** angle relatif du vent (d'où il vient) par rapport à la direction de marche, -180..180 */
    private float windRel(float from) { float r = (from - s.bearing) % 360; if (r > 180) r -= 360; if (r < -180) r += 360; return r; }


    private static final String[] DIR16 = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO"};



    private void drawGlow(Canvas c, long n) {
        float a = n - zoneFlashT < 700 ? .28f : .10f;
        int col = (zoneCol & 0x00FFFFFF) | ((int) (a * 255) << 24);
        pf.setShader(new RadialGradient(240, 150, 380, col, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 480, H, pf);
        pf.setShader(null);
    }

    // ---------------------------------------------------------------- en-tête / pied


    private void icon(Canvas c, Path p, float x, float y, int col) {
        c.save(); c.translate(x, y); c.scale(20 / 24f, 20 / 24f);
        pf.setColor(col); c.drawPath(p, pf); c.restore();
    }

    private void brand(Canvas c, float l, float t, float r, float b) {
        pf.setShader(new LinearGradient(l, 0, r, 0, CYAN, ORANGE, Shader.TileMode.CLAMP));
        rf.set(l, t, r, b); c.drawRoundRect(rf, (b - t) / 2, (b - t) / 2, pf);
        pf.setShader(null);
    }


    // ---------------------------------------------------------------- pages

    private void drawPage(Canvas c, int i, float top, float bottom) {
        switch (i) {
            case 0: pageLive(c, top, bottom); break;
            case 1: pageMap(c, top, bottom); break;
            case 2: pageEffort(c, top, bottom); break;
            case 3: pageClimb(c, top, bottom); break;
            default: pageLap(c, top, bottom); break;
        }
    }






    // ---------------------------------------------------------------- overlays

    private void drawOverlays(Canvas c, long n) {
        // bandeau pause : rappel des actions
        if ((state.equals("pause") || state.equals("auto")) && mode == M_DASH && n >= confirmUntil && !summary && !pauseMenu) {
            float y = H - BAND - 58;
            pf.setColor(0xF2120E06); rf.set(12, y, 468, y + 50); c.drawRoundRect(rf, 14, 14, pf);
            ps.setColor(AMBER); ps.setStrokeWidth(2); c.drawRoundRect(rf, 14, 14, ps);
            text(c, state.equals("auto") ? "REPARS = REPRISE" : "START  reprendre", 128, y + 32, bc7, 20, AMBER, Paint.Align.CENTER, .06f);
            pf.setColor(0x66FFB02E); c.drawRect(239, y + 12, 241, y + 38, pf);
            text(c, "TOUR  menu", 354, y + 32, bc7, 20, TXT, Paint.Align.CENTER, .06f);
        }
        if (summary) { drawSummary(c); return; }
        // toast
        if (toastTxt != null) {
            long e = n - toastT;
            if (e < 2200) {
                float k = e < 350 ? e / 350f : e > 1850 ? 1 - (e - 1850) / 350f : 1;
                pt.setTypeface(bc7); pt.setTextSize(20); pt.setLetterSpacing(.1f);
                float w = pt.measureText(toastTxt) + 40, y = 64 - 30 * (1 - k);
                pf.setColor(toastCol); pf.setAlpha((int) (255 * k));
                rf.set(240 - w / 2, y, 240 + w / 2, y + 44); c.drawRoundRect(rf, 22, 22, pf); pf.setAlpha(255);
                text(c, toastTxt, 240, y + 29, bc7, 20, (BG & 0x00FFFFFF) | ((int) (255 * k) << 24), Paint.Align.CENTER, .1f);
            } else toastTxt = null;
        }
        // nouveau tour
        long le = n - lapFlashT;
        if (lapFlashT > 0 && le < 2200) {
            float k = le < 300 ? le / 300f : le > 1900 ? 1 - (le - 1900) / 300f : 1;
            pf.setColor(0xEB090E14); pf.setAlpha((int) (235 * k)); c.drawRect(0, 0, 480, H, pf); pf.setAlpha(255);
            int a = (int) (255 * k);
            pt.setTypeface(bc8); pt.setTextSize(96); pt.setLetterSpacing(.04f);
            float w1 = pt.measureText("TOUR "), w2 = pt.measureText(lapFlashN);
            text(c, "TOUR ", 240 - (w1 + w2) / 2, H / 2, bc8, 96, alpha(TXT, a), Paint.Align.LEFT, .04f);
            text(c, lapFlashN, 240 - (w1 + w2) / 2 + w1, H / 2, bc8, 96, alpha(CYAN, a), Paint.Align.LEFT, .04f);
            text(c, lapFlashS, 240, H / 2 + 44, bc6, 26, alpha(MUTE, a), Paint.Align.CENTER, 0);
        }
        // confirmation
        if (n < confirmUntil) {
            pf.setColor(0xF5090E14); c.drawRect(0, 0, 480, H, pf);
            text(c, confirmTitle, 240, H / 2 - 20, bc8, 46, TXT, Paint.Align.CENTER, 0);
            text(c, confirmSub1, 240, H / 2 + 30, b6, 20, MUTE, Paint.Align.CENTER, 0);
            text(c, confirmSub2, 240, H / 2 + 60, b6, 20, MUTE, Paint.Align.CENTER, 0);
            footBtns(c, "REPRENDRE", "TERMINER  ✓");
        }
        // apprentissage des boutons
        if (learnStep >= 0) {
            pf.setColor(0xF5090E14); c.drawRect(0, 0, 480, H, pf);
            text(c, "CONFIGURATION DES BOUTONS", 240, H / 2 - 150, b7, 15, MUTE, Paint.Align.CENTER, .2f);
            text(c, (learnStep + 1) + " / 4", 240, H / 2 - 100, bc7, 22, CYAN, Paint.Align.CENTER, 0);
            text(c, "Appuie sur le bouton", 240, H / 2 - 50, b6, 28, TXT, Paint.Align.CENTER, 0);
            text(c, LEARN[learnStep][1], 240, H / 2 + 20, bc8, 56, CYAN, Paint.Align.CENTER, .02f);
            text(c, learnErr, 240, H / 2 + 80, bc6, 20, ORANGE, Paint.Align.CENTER, 0);
            footBtns(c, "ANNULER", null);
        }
    }

    private void drawSummary(Canvas c) {
        pf.setColor(BG); c.drawRect(0, 0, 480, H, pf);
        drawGlowStatic(c);
        text(c, "SORTIE TERMINÉE", 240, 92, bc8, 44, TXT, Paint.Align.CENTER, .04f);
        brand(c, 140, 108, 340, 112);
        text(c, sumFile != null ? "Fichier .FIT enregistré ✓" : "Aucun fichier enregistré", 240, 142, b6, 16, sumFile != null ? CYAN : ORANGE, Paint.Align.CENTER, 0);
        String[][] it = {{"DURÉE", sumTime, ""}, {"DISTANCE", sumDist, "KM"}, {"VITESSE MOY", sumSpd, "KM/H"}, {"DÉNIVELÉ +", sumDplus, "M"},
                {"NP", sumNp, "W"}, {"TSS", sumTss, ""}, {"TRAVAIL", sumKj, "KJ"}};
        float t = 166, rh = Math.min(84, (H - 64 - 20 - t - 30) / 4);
        for (int i = 0; i < it.length; i++) {
            float x = i % 2 == 0 ? 12 : 246, y = t + (i / 2) * (rh + 8);
            float w = (i == it.length - 1) ? 456 : 222;
            tile(c, x, y, x + w, y + rh, it[i][0], null);
            valUnit(c, x + 14, y + rh - 14, it[i][1], 44, it[i][2]);
        }
        if (sumFile != null) text(c, "NCT / " + sumFile, 240, H - 64 - 14, b6, 13, DIM, Paint.Align.CENTER, 0);
        pf.setColor(0xE6090E14); c.drawRect(0, H - 64, 480, H, pf);
        footBtns(c, null, "CONTINUER  ›");
    }

    private void drawSplash(Canvas c, long n) {
        long e = n - born;
        if (e > 3200) return;
        float alphaAll = e < 2600 ? 1 : 1 - (e - 2600) / 600f;
        int sl = c.saveLayerAlpha(0, 0, 480, H, (int) (255 * alphaAll));
        c.drawColor(BG);
        float lw = 400, lh = logo != null ? logo.getHeight() * lw / logo.getWidth() : 180;
        float lx = 40, ly = H / 2 - lh / 2 - 40;
        float k = ease((e - 150) / 900f);
        if (logo != null && k > 0) {
            c.save(); c.clipRect(lx, ly, lx + lw * k, ly + lh);
            rf.set(lx, ly, lx + lw, ly + lh); c.drawBitmap(logo, null, rf, pb);
            c.restore();
            // reflet
            float sk = (e - 1050) / 900f;
            if (sk > 0 && sk < 1) {
                float sx = lx - 120 + (lw + 240) * sk;
                c.save(); c.clipRect(lx, ly, lx + lw, ly + lh);
                pf.setShader(new LinearGradient(sx - 60, 0, sx + 60, 0, new int[]{0x0000E5C8, 0xA600E5C8, 0x0000E5C8}, null, Shader.TileMode.CLAMP));
                c.drawRect(sx - 60, ly, sx + 60, ly + lh, pf); pf.setShader(null);
                c.restore();
            }
        }
        float k2 = ease((e - 900) / 800f);
        if (k2 > 0) brand(c, lx, ly + lh + 26, lx + lw * k2, ly + lh + 32);
        float k3 = Math.max(0, Math.min(1, (e - 1400) / 600f));
        if (k3 > 0) text(c, "Ce que la fiche technique ne dit pas", 240, ly + lh + 80, b6, 18, alpha(ORANGE, (int) (255 * k3)), Paint.Align.CENTER, 0);
        c.restoreToCount(sl);
    }

    // ---------------------------------------------------------------- menus (dessin)

    private static final float ROW0 = 96, ROWH = 68;

    private void panelHeader(Canvas c, String title, String hint) {
        c.drawColor(BG);
        drawGlowStatic(c);
        if (logoNct != null) {
            float h = 26, w = logoNct.getWidth() * h / logoNct.getHeight();
            rf.set(16, 13, 16 + w, 13 + h); c.drawBitmap(logoNct, null, rf, pb);
            brand(c, 16, 45, 88, 48);
        }
        text(c, title, 464, 34, bc7, 22, TXT, Paint.Align.RIGHT, .16f);
        pf.setColor(LINE); c.drawRect(0, 51, 480, 52, pf);
        pf.setColor(0xE6090E14); c.drawRect(0, H - 64, 480, H, pf);
        pf.setColor(LINE); c.drawRect(0, H - 64, 480, H - 63, pf);
        footBtns(c, "‹  RETOUR", hint);
    }

    /** deux boutons tactiles en bas d'écran : gauche = retour (TOUR), droite = action (START) */
    private void footBtns(Canvas c, String left, String right) {
        float t = H - 54, b = H - 10;
        if (left != null) {
            float r = right != null ? 234 : 468;
            pf.setColor(TILE); rf.set(12, t, r, b); c.drawRoundRect(rf, 12, 12, pf);
            ps.setColor(0xFF2A3746); ps.setStrokeWidth(1.5f); c.drawRoundRect(rf, 12, 12, ps);
            text(c, left, (12 + r) / 2, t + 30, bc7, 19, TXT, Paint.Align.CENTER, .08f);
        }
        if (right != null) {
            float l = left != null ? 246 : 12;
            pf.setColor(CYAN); rf.set(l, t, 468, b); c.drawRoundRect(rf, 12, 12, pf);
            text(c, right, (l + 468) / 2, t + 30, bc7, 19, BG, Paint.Align.CENTER, .08f);
        }
    }

    private void drawGlowStatic(Canvas c) {
        pf.setShader(new RadialGradient(240, 120, 380, 0x1A00E5C8, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 480, H, pf); pf.setShader(null);
    }

    private void row(Canvas c, int i, boolean on, String l1, String l2, String chip, int chipCol) {
        float t = ROW0 + i * ROWH - 30, b = t + ROWH - 10;
        pf.setColor(on ? 0xFF13202B : TILE); rf.set(12, t, 468, b); c.drawRoundRect(rf, 14, 14, pf);
        ps.setColor(on ? CYAN : LINE); ps.setStrokeWidth(on ? 2 : 1); c.drawRoundRect(rf, 14, 14, ps);
        if (on) { pf.setColor(CYAN); rf.set(12, t + 14, 17, b - 14); c.drawRoundRect(rf, 3, 3, pf); }
        float yMid = (t + b) / 2;
        if (l2 == null) text(c, l1, 32, yMid + 9, bc7, 26, on ? TXT : 0xFFB5C2CF, Paint.Align.LEFT, .06f);
        else {
            text(c, l1, 32, yMid - 4, b7, 12, on ? CYAN : MUTE, Paint.Align.LEFT, .14f);
            text(c, l2, 32, yMid + 20, bc7, 24, on ? TXT : 0xFFB5C2CF, Paint.Align.LEFT, .02f);
        }
        if (chip != null) {
            pt.setTypeface(bc7); pt.setTextSize(15); pt.setLetterSpacing(.1f);
            float w = pt.measureText(chip) + 22;
            pf.setColor(chipCol); rf.set(454 - w, yMid - 14, 454, yMid + 14); c.drawRoundRect(rf, 14, 14, pf);
            text(c, chip, 454 - w / 2, yMid + 6, bc7, 15, BG, Paint.Align.CENTER, .1f);
        }
    }

    // ---------------------------------------------------------------- accueil

    private static final String[] DAYS = {"D", "L", "M", "M", "J", "V", "S"};

    private void drawHome(Canvas c, long n) {
        c.drawColor(BG);
        drawGlowStatic(c);
        if (logoNct != null) {
            float h = 26, w = logoNct.getWidth() * h / logoNct.getHeight();
            rf.set(16, 13, 16 + w, 13 + h); c.drawBitmap(logoNct, null, rf, pb);
            brand(c, 16, 45, 88, 48);
        }
        java.util.Calendar cal = java.util.Calendar.getInstance();
        text(c, String.format(Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                + (s.bat >= 0 ? "  ·  " + s.bat + "%" : ""), 464, 34, bc6, 18, MUTE, Paint.Align.RIGHT, 0);
        pf.setColor(LINE); c.drawRect(0, 51, 480, 52, pf);

        int hr = cal.get(java.util.Calendar.HOUR_OF_DAY);
        text(c, hr < 5 || hr >= 18 ? "Bonsoir Cédric" : "Salut Cédric", 16, 100, bc8, 40, TXT, Paint.Align.LEFT, 0);
        String date = new java.text.SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(cal.getTime());
        text(c, "v" + version, 464, 100, bc6, 14, DIM, Paint.Align.RIGHT, 0);
        text(c, date.substring(0, 1).toUpperCase(Locale.FRANCE) + date.substring(1), 16, 126, b6, 16, MUTE, Paint.Align.LEFT, 0);

        // bouton ROULER
        float t = 142, b = 272;
        pf.setShader(new LinearGradient(12, t, 468, b, new int[]{CYAN, 0xFF5FE0A0, AMBER, ORANGE}, new float[]{0, .45f, .8f, 1}, Shader.TileMode.CLAMP));
        rf.set(12, t, 468, b); c.drawRoundRect(rf, 20, 20, pf); pf.setShader(null);
        if (homeSel == 0) { ps.setColor(0xFFFFFFFF); ps.setStrokeWidth(3); rf.set(10, t - 2, 470, b + 2); c.drawRoundRect(rf, 22, 22, ps); }
        boolean riding = !state.equals("idle");
        text(c, riding ? "SORTIE EN COURS" : "PRÊT À ROULER", 36, t + 38, b7, 15, 0xBF090E14, Paint.Align.LEFT, .14f);
        text(c, riding ? "REPRENDRE" : "ROULER", 34, t + 98, bc8, 64, BG, Paint.Align.LEFT, .03f);
        text(c, "START pour " + (riding ? "revenir à la sortie" : "lancer la sortie"), 36, t + 120, b7, 14, 0xBF090E14, Paint.Align.LEFT, .1f);
        text(c, "›", 440, t + 100, bc8, 56, BG, Paint.Align.CENTER, 0);

        // capteurs
        String[] cl = {"PUISSANCE", "CARDIO", "GPS"};
        boolean[] ok = {s.sPwr, s.sHr, s.sGps};
        float cw = (456 - 16) / 3f;
        boolean blink = (n / 500) % 2 == 0;
        for (int i = 0; i < 3; i++) {
            float x = 12 + i * (cw + 8);
            pf.setColor(TILE); rf.set(x, 284, x + cw, 322); c.drawRoundRect(rf, 19, 19, pf);
            ps.setColor(LINE); ps.setStrokeWidth(1); c.drawRoundRect(rf, 19, 19, ps);
            int col = ok[i] ? CYAN : (blink ? AMBER : 0x66FFB02E);
            pf.setColor(col); c.drawCircle(x + 22, 303, 5, pf);
            text(c, cl[i] + (ok[i] ? "" : "…"), x + 34, 309, bc7, 16, ok[i] ? TXT : MUTE, Paint.Align.LEFT, .05f);
        }

        // grille d'applis
        float gt = 334, gw = (456 - 20) / 3f, gh = 96;
        for (int i = 1; i < HOME.length; i++) {
            int k = i - 1;
            float x = 12 + (k % 3) * (gw + 10), y = gt + (k / 3) * (gh + 10);
            boolean on = homeSel == i;
            pf.setColor(on ? 0xFF13202B : TILE); rf.set(x, y, x + gw, y + gh); c.drawRoundRect(rf, 16, 16, pf);
            ps.setColor(on ? CYAN : LINE); ps.setStrokeWidth(on ? 2 : 1); c.drawRoundRect(rf, 16, 16, ps);
            homeIcon(c, i, x + gw / 2, y + 36, i == 3 ? ORANGE : i == 6 ? MUTE : CYAN);
            text(c, HOME[i], x + gw / 2, y + 78, bc7, 15, TXT, Paint.Align.CENTER, .1f);
            if (i == 1 || i == 3 || i == 4) text(c, "BIENTÔT", x + gw - 10, y + 18, b7, 9, AMBER, Paint.Align.RIGHT, .1f);
        }

        // semaine
        float wt = gt + 2 * (gh + 10), wb = H - 12;
        if (wb - wt > 90) {
            tile(c, 12, wt, 468, wb, "CETTE SEMAINE", null);
            long now7 = System.currentTimeMillis();
            double km = 0, sec = 0, tss = 0; double[] day = new double[7];
            java.util.Calendar d0 = java.util.Calendar.getInstance();
            d0.set(java.util.Calendar.HOUR_OF_DAY, 0); d0.set(java.util.Calendar.MINUTE, 0); d0.set(java.util.Calendar.SECOND, 0);
            long midnight = d0.getTimeInMillis();
            for (org.json.JSONObject r : rides) {
                long t0 = r.optLong("t");
                int ago = (int) Math.floor((midnight + 86400000L - t0) / 86400000.0);
                if (ago >= 0 && ago < 7) {
                    km += r.optDouble("dist") / 1000; sec += r.optDouble("dur"); tss += r.optDouble("tss");
                    day[6 - ago] += r.optDouble("dist") / 1000;
                }
            }
            text(c, String.format(Locale.US, "%.0f km · %dh%02d · TSS %.0f", km, (int) (sec / 3600), (int) (sec / 60) % 60, tss),
                    454, wt + 30, bc7, 16, TXT, Paint.Align.RIGHT, 0);
            double mx = 1; for (double d : day) mx = Math.max(mx, d);
            float bw = (428 - 6 * 8) / 7f, base = wb - 26, maxH = Math.max(10, base - (wt + 44));
            int dow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1;
            for (int i = 0; i < 7; i++) {
                float x = 26 + i * (bw + 8), hh = (float) Math.max(4, day[i] / mx * maxH);
                pf.setColor(day[i] > 0 ? CYAN : 0xFF1C2A38); rf.set(x, base - hh, x + bw, base); c.drawRoundRect(rf, 4, 4, pf);
                text(c, DAYS[(dow - 6 + i + 14) % 7], x + bw / 2, wb - 8, bc6, 13, i == 6 ? TXT : MUTE, Paint.Align.CENTER, 0);
            }
            if (rides.isEmpty()) text(c, "Ta première sortie apparaîtra ici", 240, base - maxH / 2, b6, 14, DIM, Paint.Align.CENTER, 0);
        }
    }

    private void homeIcon(Canvas c, int i, float cx, float cy, int col) {
        pf.setColor(col); ps.setColor(col); ps.setStrokeWidth(3); ps.setStrokeCap(Paint.Cap.ROUND);
        switch (i) {
            case 1: for (int k = 0; k < 4; k++) { float h = new float[]{10, 22, 15, 28}[k]; rf.set(cx - 15 + k * 8, cy + 14 - h, cx - 10 + k * 8, cy + 14); c.drawRoundRect(rf, 2, 2, pf); } break;
            case 2: c.drawCircle(cx, cy, 14, ps); c.drawLine(cx, cy, cx, cy - 8, ps); c.drawLine(cx, cy, cx + 6, cy + 4, ps); break;
            case 3: path.reset(); path.moveTo(cx - 16, cy + 10); path.lineTo(cx - 6, cy - 2); path.lineTo(cx + 2, cy + 4); path.lineTo(cx + 16, cy - 12); c.drawPath(path, ps); break;
            case 4: for (int k = 0; k < 4; k++) { float x = cx - 14 + (k % 2) * 16, y = cy - 14 + (k / 2) * 16; rf.set(x, y, x + 12, y + 12); c.drawRoundRect(rf, 2, 2, pf); } break;
            case 5: c.save(); c.translate(cx - 12, cy - 14); c.scale(1.1f, 1.1f); c.drawPath(icBolt, pf); c.restore(); break;
            default: c.drawCircle(cx, cy, 8, ps); for (int k = 0; k < 8; k++) { double a = k * Math.PI / 4; c.drawLine(cx + (float) (12 * Math.cos(a)), cy + (float) (12 * Math.sin(a)), cx + (float) (16 * Math.cos(a)), cy + (float) (16 * Math.sin(a)), ps); }
        }
        ps.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawHistory(Canvas c, long n) {
        panelHeader(c, "HISTORIQUE", null);
        if (rides.isEmpty()) {
            text(c, "Aucune sortie enregistrée pour l'instant.", 240, H / 2, b6, 17, MUTE, Paint.Align.CENTER, 0);
            return;
        }
        int max = Math.max(1, (int) ((H - 74 - (ROW0 - 30)) / ROWH));
        int start = Math.max(0, Math.min(histSel - max + 1, rides.size() - max));
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("EEE d MMM · HH:mm", Locale.FRANCE);
        for (int i = start; i < Math.min(rides.size(), start + max); i++) {
            org.json.JSONObject r = rides.get(i);
            String l1 = df.format(new java.util.Date(r.optLong("t"))).toUpperCase(Locale.FRANCE);
            String l2 = String.format(Locale.US, "%.1f km  ·  %s  ·  %.0f W NP", r.optDouble("dist") / 1000, ms(r.optLong("dur")), r.optDouble("np"));
            row(c, i - start, i == histSel, l1, l2, "TSS " + Math.round(r.optDouble("tss")), CYAN);
        }
    }

    private void drawMenus(Canvas c, long n) {
        if (mode == M_HOME) { drawHome(c, n); return; }
        if (mode == M_HIST) { drawHistory(c, n); return; }
        if (mode == M_MENU) {
            panelHeader(c, "MENU", "VALIDER  ›");
            text(c, "NCT Compteur v" + version, 240, H - 72, b6, 12, DIM, Paint.Align.CENTER, 0);
            int b = host.brightness();
            for (int i = 0; i < MENU.length; i++)
                row(c, i, i == sel, MENU[i], null, i == 2 ? (b < 0 ? "AUTO" : b + " %") : i == 8 ? (host.blockSleep() ? "VEILLE BLOQUÉE" : "VEILLE ACTIVE") : null,
                        i == 8 && !host.blockSleep() ? MUTE : CYAN);
        } else if (mode == M_SENS) {
            if (n - sensRefresh > 1000) { sensCache = host.sensorList(); sensRefresh = n; if (sel >= sensCache.size()) sel = Math.max(0, sensCache.size() - 1); }
            String act = null;
            if (sel < sensCache.size()) act = host.sensorStatus(sensCache.get(sel)).isEmpty() ? "AJOUTER  +" : "RETIRER  ✕";
            panelHeader(c, "CAPTEURS", act);
            String dots = new String[]{"", ".", "..", "..."}[(int) ((n / 400) % 4)];
            text(c, "Recherche en cours" + dots, 24, 84, b6, 15, MUTE, Paint.Align.LEFT, 0);
            if (sensCache.isEmpty()) {
                text(c, "Aucun capteur détecté pour l'instant.", 240, H / 2 - 20, b6, 18, TXT, Paint.Align.CENTER, 0);
                text(c, "Réveille-les : tour de manivelle,", 240, H / 2 + 14, b6, 16, MUTE, Paint.Align.CENTER, 0);
                text(c, "ceinture cardio portée et humidifiée.", 240, H / 2 + 38, b6, 16, MUTE, Paint.Align.CENTER, 0);
                text(c, "Ferme l'appli Xcadey si elle les utilise.", 240, H / 2 + 62, b6, 16, MUTE, Paint.Align.CENTER, 0);
            }
            int max = Math.max(1, (int) ((H - 74 - (ROW0 - 30 + ROWH)) / ROWH));
            int start = Math.max(0, Math.min(sel - max + 1, sensCache.size() - max));
            for (int i = start; i < Math.min(sensCache.size(), start + max); i++) {
                Ble.Found f = sensCache.get(i);
                String st = host.sensorStatus(f);
                String tl = f.type.equals("pwr") ? "PUISSANCE" : f.type.equals("hr") ? "CARDIO" : "VITESSE / CADENCE";
                boolean fresh = n - f.seen < 15000;
                String chip = st.equals("on") ? "CONNECTÉ" : st.equals("saved") ? "CONNEXION…" : fresh ? "AJOUTER" : "HORS DE PORTÉE";
                int col = st.equals("on") ? CYAN : st.equals("saved") ? AMBER : fresh ? 0xFF5F7A94 : 0xFF2E3B4A;
                row(c, i - start + 1, i == sel, tl + (st.isEmpty() ? "" : "  ·  ENREGISTRÉ"), f.name, chip, col);
            }
        } else if (mode == M_PROF) {
            panelHeader(c, "PROFIL", profEdit ? "VALIDER  ✓" : profSel == 3 ? "ACTIVER / DÉSACTIVER" : "MODIFIER  ›");
            String[] lab = {"FTP", "FC MAX", "POIDS", "PAUSE AUTOMATIQUE"};
            String[] val = {m.ftp + " W", m.hrMax + " bpm", String.format(Locale.US, "%.1f kg", m.weight), host.autoPause() ? "ACTIVÉE" : "DÉSACTIVÉE"};
            for (int i = 0; i < 4; i++) {
                boolean on = i == profSel;
                float t = ROW0 + i * 110 - 30, b = t + 100;
                pf.setColor(on ? 0xFF13202B : TILE); rf.set(12, t, 468, b); c.drawRoundRect(rf, 14, 14, pf);
                ps.setColor(on ? (profEdit ? ORANGE : CYAN) : LINE); ps.setStrokeWidth(on ? 2 : 1); c.drawRoundRect(rf, 14, 14, ps);
                text(c, lab[i], 30, t + 34, b7, 14, on ? CYAN : MUTE, Paint.Align.LEFT, .14f);
                text(c, val[i], 30, t + 84, bc7, 50, TXT, Paint.Align.LEFT, 0);
                if (on && profEdit) {
                    pf.setColor(ORANGE);
                    rf.set(330, t + 26, 390, t + 86); c.drawRoundRect(rf, 12, 12, pf);
                    rf.set(398, t + 26, 458, t + 86); c.drawRoundRect(rf, 12, 12, pf);
                    text(c, "−", 360, t + 70, bc8, 40, BG, Paint.Align.CENTER, 0);
                    text(c, "+", 428, t + 70, bc8, 40, BG, Paint.Align.CENTER, 0);
                }
            }
            float y = ROW0 + 4 * 110 + 4;
            text(c, "Rapport FTP / poids : " + f1(m.ftp / m.weight) + " W/kg", 240, y, b6, 18, MUTE, Paint.Align.CENTER, 0);
            text(c, "Zones de puissance et de FC recalculées automatiquement.", 240, y + 28, b6, 14, DIM, Paint.Align.CENTER, 0);
        }
    }

    private void menuTap(float x, float y) {
        if (mode == M_HOME) {
            if (y < 56) return;
            if (y >= 142 && y <= 272) { homeSel = 0; homeOk(0); }
            else if (y >= 284 && y <= 322) { homeSel = 5; homeOk(5); }
            else if (y >= 334 && y < 334 + 2 * 106) {
                float gw = (456 - 20) / 3f;
                int col = (int) Math.min(2, Math.max(0, (x - 12) / (gw + 10))), r = (int) ((y - 334) / 106);
                homeSel = 1 + r * 3 + col; homeOk(homeSel);
            }
            invalidate(); return;
        }
        if (mode == M_HIST) { if (y > H - 64 || y < 56) mode = M_HOME; invalidate(); return; }
        if (y > H - 64) { button(x < 240 ? "lap" : "startstop"); return; } // boutons du bas : retour / action
        if (mode == M_MENU || mode == M_SENS) {
            int i = (int) Math.floor((y - (ROW0 - 30)) / ROWH);
            if (mode == M_MENU && i >= 0 && i < MENU.length) { sel = i; menuOk(i); }
            else if (mode == M_SENS) {
                int max = Math.max(1, (int) ((H - 74 - (ROW0 - 30 + ROWH)) / ROWH));
                int start = Math.max(0, Math.min(sel - max + 1, sensCache.size() - max));
                int k = start + i - 1;
                if (i >= 1 && k < sensCache.size()) { sel = k; host.toggleSensor(sensCache.get(k)); sensRefresh = 0; }
            }
        } else if (mode == M_PROF) {
            int i = (int) Math.floor((y - (ROW0 - 30)) / 110);
            if (i == 3) { profSel = 3; profEdit = false; host.setAutoPause(!host.autoPause()); }
            else if (i >= 0 && i < 3) {
                if (profEdit && i == profSel && x > 326) adjust(x < 394 ? -1 : 1);
                else if (profEdit && i == profSel) { profEdit = false; host.saveCfg(m.ftp, m.hrMax, m.weight); }
                else { profSel = i; profEdit = true; }
            }
        }
        invalidate();
    }

    // ---------------------------------------------------------------- utilitaires

    private void tile(Canvas c, float l, float t, float r, float b, String label, Path ic) {
        pf.setColor(TILE); rf.set(l, t, r, b); c.drawRoundRect(rf, 14, 14, pf);
        ps.setColor(LINE); ps.setStrokeWidth(1); rf.set(l + .5f, t + .5f, r - .5f, b - .5f); c.drawRoundRect(rf, 14, 14, ps);
        pf.setShader(new LinearGradient(l, 0, r, 0, new int[]{0x0000E5C8, 0x5900E5C8, 0x0000E5C8}, null, Shader.TileMode.CLAMP));
        c.drawRect(l + 14, t, r - 14, t + 1, pf); pf.setShader(null);
        if (label != null) {
            float x = l + 14;
            if (ic != null) {
                c.save(); c.translate(x, t + 17); c.scale(15 / 24f, 15 / 24f); pf.setColor(MUTE); c.drawPath(ic, pf); c.restore();
                x += 21;
            }
            text(c, label, x, t + 30, b7, 13, MUTE, Paint.Align.LEFT, .12f);
        }
    }

    private void small(Canvas c, float l, float t, float r, float b, String label, String val, String unit) {
        pf.setColor(TILE); rf.set(l, t, r, b); c.drawRoundRect(rf, 14, 14, pf);
        ps.setColor(LINE); ps.setStrokeWidth(1); c.drawRoundRect(rf, 14, 14, ps);
        text(c, label, l + 12, t + 24, b7, 12, MUTE, Paint.Align.LEFT, .13f);
        valUnit(c, l + 12, t + 76, val, 50, unit);
    }

    private void valUnit(Canvas c, float x, float y, String val, float size, String unit) {
        text(c, val, x, y, bc7, size, TXT, Paint.Align.LEFT, -.01f);
        if (unit != null && !unit.isEmpty()) {
            pt.setTypeface(bc7); pt.setTextSize(size); pt.setLetterSpacing(-.01f);
            float w = pt.measureText(val);
            text(c, unit, x + w + 6, y, bc6, size > 70 ? 18 : 16, MUTE, Paint.Align.LEFT, .05f);
        }
    }

    private void sub(Canvas c, float x, float y, String s) { text(c, s, x, y, bc6, 18, MUTE, Paint.Align.LEFT, .03f); }

    private void text(Canvas c, String s, float x, float y, Typeface tf, float size, int col, Paint.Align a, float spacing) {
        if (s == null || s.isEmpty()) return;
        pt.setTypeface(tf); pt.setTextSize(size); pt.setColor(col); pt.setTextAlign(a); pt.setLetterSpacing(spacing);
        c.drawText(s, x, y, pt);
        pt.setTextAlign(Paint.Align.LEFT);
    }

    static float[] pt(float cx, float cy, double deg, float r) {
        double a = Math.toRadians(deg);
        return new float[]{(float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a))};
    }

    static float ease(float k) { k = Math.max(0, Math.min(1, k)); return 1 - (float) Math.pow(1 - k, 3); }
    static int alpha(int col, int a) { return (col & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24); }
    static int wcol(double p) { return p > 60 ? CYAN : p > 30 ? AMBER : ORANGE; }
    static int gcol(double g) { return g < 3 ? CYAN : g < 6 ? 0xFFFFD23E : g < 9 ? 0xFFFF8A2E : ORANGE; }
    static String wmsg(double p) {
        if (p > 90) return "Réservoir plein — tu peux attaquer.";
        if (p > 60) return "Encore de la marge pour une relance.";
        if (p > 30) return "Ça puise. Gère avant la prochaine attaque.";
        if (p > 10) return "Dans le rouge — récupère sous ta FTP.";
        return "Réservoir vide. Plus rien à sortir.";
    }
    static String i0(double v) { return String.valueOf(Math.round(v)); }
    static String f1(double v) { return String.format(Locale.US, "%.1f", v); }
    static String hms(long s) { return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60); }
    static String ms(long s) {
        long h = s / 3600, m = (s / 60) % 60, x = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, x) : String.format(Locale.US, "%d:%02d", m, x);
    }
}

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
        String mapInfo();
        void setMapVisible(boolean on);
        void mapZoom(int d);
        void openSettings(String action);
        int brightness();          // -1 = auto (système), sinon 1..100
        void setBrightness(int pct);
        void quitApp();
    }

    // ---------------------------------------------------------------- menus
    static final int M_DASH = 0, M_MENU = 1, M_SENS = 2, M_PROF = 3;
    static final String[] MENU = {"CAPTEURS", "PROFIL", "LUMINOSITÉ", "BOUTONS", "WI-FI", "BLUETOOTH", "PARAMÈTRES ANDROID", "QUITTER", "RETOUR"};
    static final int[] BRIGHT = {-1, 5, 15, 30, 50, 75, 100};
    private int mode = M_DASH, sel = 0, profSel = 0;
    private boolean profEdit;
    private java.util.List<Ble.Found> sensCache = new java.util.ArrayList<>();
    private long sensRefresh;

    public void openMenu() { mode = M_MENU; sel = 0; invalidate(); }
    public void openMode(int md) { mode = md; sel = 0; if (md == M_SENS) host.setKeepScanning(true); invalidate(); }

    private void menuButton(String b) {
        if (mode == M_MENU) {
            if (b.equals("next")) sel = (sel + 1) % MENU.length;
            else if (b.equals("prev")) sel = (sel + MENU.length - 1) % MENU.length;
            else if (b.equals("lap")) mode = M_DASH;
            else if (b.equals("startstop")) menuOk(sel);
        } else if (mode == M_SENS) {
            int n = sensCache.size();
            if (b.equals("next") && n > 0) sel = (sel + 1) % n;
            else if (b.equals("prev") && n > 0) sel = (sel + n - 1) % n;
            else if (b.equals("lap")) { host.setKeepScanning(false); mode = M_MENU; sel = 0; }
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
            default: mode = M_DASH;
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
            else if (state.equals("pause")) toast("PAUSE", AMBER);
        }
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
        if (summary) { summary = false; invalidate(); return; }
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
                    if (confirmed("finish")) {
                        // résumé de la sortie avant remise à zéro
                        sumTime = ms(s.elapsed); sumDist = String.format(Locale.US, "%.2f", m.dist / 1000);
                        sumDplus = String.valueOf(Math.round(m.dplus)); sumNp = i0(m.np()); sumTss = i0(m.tss());
                        sumKj = i0(m.kj); sumSpd = f1(m.spdAvg());
                        String f = host.finishRide();
                        sumFile = f != null && !f.isEmpty() ? f : null;
                        summary = true;
                        m.reset(); lastZone = -1;
                    } else confirm("finish", "TERMINER LA SORTIE ?", "TOUR : enregistrer le fichier .FIT", "START : reprendre");
                } else openMenu();
                break;
        }
        invalidate();
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
    public void demoSummary() { state = "pause"; confirmKind = "finish"; confirmUntil = now() + 4000; button("lap"); }

    public void toast(String t, int col) { toastTxt = t; toastCol = col; toastT = now(); invalidate(); }

    private float sx0;
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            float x = e.getX() / sc, y = e.getY() / sc;
            if (mapShown && mode == M_DASH && y > 52 && y < mapBottom()) {
                if (x > 416 && y < 52 + 68) { host.mapZoom(1); return true; }
                if (x > 416 && y > 52 + 68 && y < 52 + 124) { host.mapZoom(-1); return true; }
                return false; // la carte reçoit le geste (déplacement, pincement)
            }
            sx0 = e.getX(); return true;
        }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float dx = (e.getX() - sx0) / sc, x = e.getX() / sc, y = e.getY() / sc;
            if (learnStep >= 0) return true;
            if (summary) { summary = false; invalidate(); return true; }
            if (mode != M_DASH) { menuTap(x, y); return true; }
            if (Math.abs(dx) > 60) button(dx < 0 ? "next" : "prev");
            else if (y < 56) openMenu();              // appui sur l'en-tête : menu
            else if (y > H - 64 && x < 140) button("startstop");
            return true;
        }
        return super.onTouchEvent(e);
    }

    // ================================================================= dessin

    @Override protected void onDraw(Canvas c) {
        long n = now();
        sc = getWidth() / 480f;
        H = getHeight() / sc;
        c.save();
        c.scale(sc, sc);

        float top = 52, bottom = H - 64;
        double k0 = Math.min(1, (n - pageT) / 380.0);
        boolean showMap = cur == P_MAP && k0 >= 1 && mode == M_DASH && !summary && learnStep < 0
                && n >= confirmUntil && n - born > 3200 && host.mapReady();
        if (showMap != mapShown) { mapShown = showMap; final boolean v = showMap; post(() -> host.setMapVisible(v)); }
        c.save();
        if (mapShown) c.clipRect(0, top, 480, mapBottom(), android.graphics.Region.Op.DIFFERENCE);
        c.drawColor(BG);
        drawGlow(c, n);
        c.restore();
        drawHeader(c);

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
        drawOverlays(c, n);
        drawSplash(c, n);
        c.restore();

        postInvalidateDelayed(33);
    }

    private boolean mapShown;
    float mapBottom() { return H - 64 - 116; }

    // ---------------------------------------------------------------- page carte

    private void pageMap(Canvas c, float top, float bottom) {
        float mb = mapBottom();
        boolean live = mapShown && cur == P_MAP;
        if (!live) {
            pf.setColor(0xFF0B1118); c.drawRect(0, top, 480, mb, pf);
            if (!host.mapReady()) {
                text(c, "AUCUNE CARTE TROUVÉE", 240, (top + mb) / 2 - 30, bc7, 26, TXT, Paint.Align.CENTER, .05f);
                text(c, "Copie un fichier .map (Mapsforge)", 240, (top + mb) / 2 + 6, b6, 16, MUTE, Paint.Align.CENTER, 0);
                text(c, "dans le dossier NCT/maps du compteur", 240, (top + mb) / 2 + 30, b6, 16, MUTE, Paint.Align.CENTER, 0);
                text(c, host.mapInfo(), 240, (top + mb) / 2 + 66, b6, 13, DIM, Paint.Align.CENTER, 0);
            } else text(c, "CARTE", 240, (top + mb) / 2, bc7, 24, DIM, Paint.Align.CENTER, .2f);
        } else {
            // dégradés de lisibilité en haut et en bas de carte
            pf.setShader(new LinearGradient(0, top, 0, top + 40, 0x99090E14, 0x00090E14, Shader.TileMode.CLAMP));
            c.drawRect(0, top, 480, top + 40, pf);
            pf.setShader(new LinearGradient(0, mb - 50, 0, mb, 0x00090E14, 0xCC090E14, Shader.TileMode.CLAMP));
            c.drawRect(0, mb - 50, 480, mb, pf); pf.setShader(null);
            // position : flèche orientée selon le cap (carte nord en haut), au centre de la vue carte
            float px = 240, py = H / 2f;
            c.save(); c.translate(px, py); c.rotate(s.bearing);
            pf.setColor(0x5500E5C8); c.drawCircle(0, 0, 24, pf);
            path.reset(); path.moveTo(0, -17); path.lineTo(12, 13); path.lineTo(0, 6); path.lineTo(-12, 13); path.close();
            pf.setColor(0xFFFFFFFF); c.drawPath(path, pf);
            ps.setColor(BG); ps.setStrokeWidth(2.5f); ps.setStrokeJoin(Paint.Join.ROUND); c.drawPath(path, ps);
            c.restore();
            // boutons zoom
            zoomBtn(c, 420, top + 16, "+"); zoomBtn(c, 420, top + 72, "−");
            // nord
            pf.setColor(0xCC090E14); c.drawCircle(36, top + 40, 22, pf);
            ps.setColor(LINE); ps.setStrokeWidth(1); c.drawCircle(36, top + 40, 22, ps);
            path.reset(); path.moveTo(36, top + 24); path.lineTo(43, top + 42); path.lineTo(29, top + 42); path.close();
            pf.setColor(ORANGE); c.drawPath(path, pf);
            text(c, "N", 36, top + 56, bc7, 13, TXT, Paint.Align.CENTER, 0);
        }
        // bandeau de données
        float t = mb + 6, w = (456 - 20) / 3f;
        String[] lab = {"PUISSANCE 3S", "VITESSE", "DISTANCE"};
        String[] val = {i0(v("pwr")), f1(v("spd")), String.format(Locale.US, "%.1f", v("dist"))};
        String[] un = {"W", "KM/H", "KM"};
        for (int i = 0; i < 3; i++) {
            float l = 12 + i * (w + 10);
            tile(c, l, t, l + w, bottom - 10, lab[i], null);
            valUnit(c, l + 14, bottom - 26, val[i], 54, un[i]);
        }
        if (s.sPwr) { pf.setColor(zoneCol); rf.set(26, bottom - 18, 12 + w - 14, bottom - 14); c.drawRoundRect(rf, 2, 2, pf); }
    }

    private void zoomBtn(Canvas c, float x, float y, String l) {
        pf.setColor(0xE60E151E); rf.set(x, y, x + 48, y + 48); c.drawRoundRect(rf, 12, 12, pf);
        ps.setColor(LINE); ps.setStrokeWidth(1); c.drawRoundRect(rf, 12, 12, ps);
        text(c, l, x + 24, y + 35, bc8, 32, TXT, Paint.Align.CENTER, 0);
    }

    private void drawGlow(Canvas c, long n) {
        float a = n - zoneFlashT < 700 ? .28f : .10f;
        int col = (zoneCol & 0x00FFFFFF) | ((int) (a * 255) << 24);
        pf.setShader(new RadialGradient(240, 150, 380, col, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 480, H, pf);
        pf.setShader(null);
    }

    // ---------------------------------------------------------------- en-tête / pied

    private void drawHeader(Canvas c) {
        if (logoNct != null) {
            float h = 26, w = logoNct.getWidth() * h / logoNct.getHeight();
            rf.set(16, 13, 16 + w, 13 + h); c.drawBitmap(logoNct, null, rf, pb);
            brand(c, 16, 45, 16 + 72, 48);
        }
        text(c, PAGES[cur], 240, 31, bc7, 20, TXT, Paint.Align.CENTER, .2f);
        // points
        float x = 240 - (6 * (PAGES.length - 1) + 18 + 6 * (PAGES.length - 1)) / 2f;
        for (int i = 0; i < PAGES.length; i++) {
            float w = i == cur ? 18 : 6;
            pf.setColor(i == cur ? CYAN : 0xFF2A3746);
            rf.set(x, 38, x + w, 44); c.drawRoundRect(rf, 3, 3, pf);
            x += w + 6;
        }
        // statut
        float bx = 464;
        String bt = s.bat >= 0 ? s.bat + "%" : "--";
        pt.setTypeface(bc6); pt.setTextSize(17);
        float tw = pt.measureText(bt);
        text(c, bt, bx, 32, bc6, 17, MUTE, Paint.Align.RIGHT, 0);
        bx -= tw + 4;
        ps.setColor(MUTE); ps.setStrokeWidth(2); rf.set(bx - 8, 19, bx, 33); c.drawRoundRect(rf, 2, 2, ps);
        bx -= 8 + 9;
        boolean blink = (now() / 500) % 2 == 0;
        icon(c, icPin, bx - 20, 16, s.sGps ? CYAN : (blink ? 0xFF2E3B4A : 0x802E3B4A));
        icon(c, icHeart, bx - 47, 16, s.sHr ? ORANGE : (blink ? 0xFF2E3B4A : 0x802E3B4A));
        icon(c, icBolt, bx - 74, 16, s.sPwr ? CYAN : (blink ? 0xFF2E3B4A : 0x802E3B4A));
        pf.setColor(LINE); c.drawRect(0, 51, 480, 52, pf);
    }

    private void icon(Canvas c, Path p, float x, float y, int col) {
        c.save(); c.translate(x, y); c.scale(20 / 24f, 20 / 24f);
        pf.setColor(col); c.drawPath(p, pf); c.restore();
    }

    private void brand(Canvas c, float l, float t, float r, float b) {
        pf.setShader(new LinearGradient(l, 0, r, 0, CYAN, ORANGE, Shader.TileMode.CLAMP));
        rf.set(l, t, r, b); c.drawRoundRect(rf, (b - t) / 2, (b - t) / 2, pf);
        pf.setShader(null);
    }

    private void drawFooter(Canvas c) {
        float y = H - 64;
        pf.setColor(0xE6090E14); c.drawRect(0, y, 480, H, pf);
        pf.setColor(LINE); c.drawRect(0, y, 480, y + 1, pf);
        // pastille d'état
        boolean paused = state.equals("pause") || state.equals("auto");
        int col = state.equals("rec") ? ORANGE : paused ? AMBER : 0xFF2A3746;
        ps.setColor(col); ps.setStrokeWidth(2);
        rf.set(17, y + 16, 125, y + 48); c.drawRoundRect(rf, 16, 16, ps);
        boolean on = !state.equals("rec") || (now() / 600) % 2 == 0;
        pf.setColor(state.equals("idle") ? 0xFF3A4858 : on ? col : (col & 0x40FFFFFF));
        c.drawCircle(35, y + 32, 6, pf);
        String l = state.equals("rec") ? "REC" : state.equals("auto") ? "AUTO" : state.equals("pause") ? "PAUSE" : "PRÊT";
        text(c, l, 49, y + 39, bc7, 18, state.equals("rec") ? TXT : paused ? AMBER : MUTE, Paint.Align.LEFT, .11f);
        text(c, hms(s.elapsed), 240, y + 45, bc7, 38, TXT, Paint.Align.CENTER, .01f);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        text(c, String.format(Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)),
                464, y + 40, bc6, 22, MUTE, Paint.Align.RIGHT, 0);
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

    private void pageLive(Canvas c, float top, float bottom) {
        float cx = 240, cy = top + 204, r = 150;
        // graduations de zones
        double scale = 1.6;
        for (int i = 0; i < 6; i++) {
            double deg = 135 + 270 * Math.min(Metrics.ZMAX[i] / scale, 1);
            float[] a = pt(cx, cy, deg, r + 18), b = pt(cx, cy, deg, r + 30);
            ps.setColor(0xFF2A3644); ps.setStrokeWidth(3); ps.setStrokeCap(Paint.Cap.BUTT);
            c.drawLine(a[0], a[1], b[0], b[1], ps);
        }
        for (int i = 0; i < 7; i++) {
            double lo = i == 0 ? 0 : Metrics.ZMAX[i - 1], hi = Math.min(Metrics.ZMAX[i], scale);
            if (lo >= scale) break;
            float[] l = pt(cx, cy, 135 + 270 * ((lo + hi) / 2 / scale), r + 38);
            text(c, String.valueOf(i + 1), l[0], l[1] + 5, bc6, 14, 0xFF3E4E60, Paint.Align.CENTER, 0);
        }
        rf.set(cx - r, cy - r, cx + r, cy + r);
        ps.setStrokeCap(Paint.Cap.ROUND);
        ps.setColor(0xFF151F2B); ps.setStrokeWidth(24); c.drawArc(rf, 135, 270, false, ps);
        double target = Math.max(0, Math.min(1, v("pwr") / (m.ftp * scale)));
        arcShown += (target - arcShown) * 0.25;
        float sweep = (float) (270 * arcShown);
        if (sweep > 0.5f) {
            ps.setShader(arcGrad);
            ps.setAlpha(41); ps.setStrokeWidth(44); c.drawArc(rf, 135, sweep, false, ps);
            ps.setAlpha(255); ps.setStrokeWidth(24); c.drawArc(rf, 135, sweep, false, ps);
            ps.setShader(null);
        }
        float[] hp = pt(cx, cy, 135 + sweep, r);
        pf.setColor(BG); c.drawCircle(hp[0], hp[1], 11, pf);
        pf.setColor(0xFFFFFFFF); c.drawCircle(hp[0], hp[1], 8, pf);
        ps.setStrokeCap(Paint.Cap.BUTT);

        // centre
        text(c, i0(v("pwr")), cx, top + 218, bc7, 148, TXT, Paint.Align.CENTER, -.02f);
        pt.setTypeface(bc6); pt.setTextSize(18); pt.setLetterSpacing(.17f);
        float w1 = pt.measureText("WATTS "), w2 = pt.measureText("3S");
        text(c, "WATTS ", cx - (w1 + w2) / 2, top + 246, bc6, 18, MUTE, Paint.Align.LEFT, .17f);
        text(c, "3S", cx - (w1 + w2) / 2 + w1, top + 246, bc6, 18, CYAN, Paint.Align.LEFT, .17f);
        int z = Math.max(0, lastZone);
        String zl = Metrics.ZN[z] + " · " + Metrics.ZNAME[z];
        pt.setTypeface(bc7); pt.setTextSize(19); pt.setLetterSpacing(.1f);
        float zw = pt.measureText(zl) + 28;
        pf.setColor(zoneCol); rf.set(cx - zw / 2, top + 260, cx + zw / 2, top + 292); c.drawRoundRect(rf, 16, 16, pf);
        text(c, zl, cx, top + 283, bc7, 19, BG, Paint.Align.CENTER, .1f);
        // côtés
        text(c, f1(v("wkg")), 56, top + 298, bc7, 40, TXT, Paint.Align.CENTER, 0);
        text(c, "W/KG", 56, top + 316, bc6, 14, MUTE, Paint.Align.CENTER, .14f);
        text(c, i0(v("pftp")), 424, top + 298, bc7, 40, TXT, Paint.Align.CENTER, 0);
        text(c, "% FTP", 424, top + 316, bc6, 14, MUTE, Paint.Align.CENTER, .14f);

        // tuiles 2x2
        float t0 = top + 322, gap = 10, th = (bottom - 12 - t0 - gap) / 2;
        float[] xs = {12, 246}; float tw = 222;
        // FC
        tile(c, xs[0], t0, xs[0] + tw, t0 + th, "FRÉQ. CARDIAQUE", icHeart);
        valUnit(c, xs[0] + 14, t0 + 100, s.hr > 0 ? i0(v("hr")) : "--", 78, "BPM");
        int hz = s.hr > 0 ? Math.min(4, Math.max(0, (int) Math.floor((s.hr / (double) m.hrMax - .5) / .1))) : -1;
        float sw = (tw - 28 - 16) / 5;
        for (int i = 0; i < 5; i++) {
            pf.setColor(i <= hz ? ORANGE : LINE);
            rf.set(xs[0] + 14 + i * (sw + 4), t0 + th - 22, xs[0] + 14 + i * (sw + 4) + sw, t0 + th - 14);
            c.drawRoundRect(rf, 4, 4, pf);
        }
        // cadence
        tile(c, xs[1], t0, xs[1] + tw, t0 + th, "CADENCE", null);
        valUnit(c, xs[1] + 14, t0 + 100, s.sPwr || s.sCsc ? i0(v("cad")) : "--", 78, "RPM");
        sub(c, xs[1] + 14, t0 + th - 16, "moy " + (m.cadAvg() > 0 ? i0(m.cadAvg()) : "--"));
        // vitesse
        float t1 = t0 + th + gap;
        tile(c, xs[0], t1, xs[0] + tw, t1 + th, "VITESSE", null);
        valUnit(c, xs[0] + 14, t1 + 100, f1(v("spd")), 78, "KM/H");
        sub(c, xs[0] + 14, t1 + th - 16, "moy " + f1(m.spdAvg()));
        // distance
        tile(c, xs[1], t1, xs[1] + tw, t1 + th, "DISTANCE", null);
        valUnit(c, xs[1] + 14, t1 + 100, String.format(Locale.US, "%.2f", v("dist")), 78, "KM");
        sub(c, xs[1] + 14, t1 + th - 16, "D+ " + Math.round(m.dplus) + " m");
    }

    private void pageEffort(Canvas c, float top, float bottom) {
        float t = top + 8, w = (456 - 20) / 3f;
        String[] lab = {"NP", "IF", "TSS"}, val = {i0(v("np")), String.format(Locale.US, "%.2f", v("if")), i0(v("tss"))}, sb = {"WATTS", "INTENSITÉ", "CHARGE"};
        for (int i = 0; i < 3; i++) {
            float l = 12 + i * (w + 10), cx = l + w / 2;
            tile(c, l, t, l + w, t + 148, null, null);
            text(c, lab[i], cx, t + 30, b7, 13, MUTE, Paint.Align.CENTER, .12f);
            text(c, val[i], cx, t + 92, bc7, 62, TXT, Paint.Align.CENTER, -.01f);
            text(c, sb[i], cx, t + 130, bc6, 18, MUTE, Paint.Align.CENTER, .03f);
        }
        // réservoir W'bal
        float wt = t + 158, wb = wt + 228;
        tile(c, 12, wt, 468, wb, null, null);
        pt.setTypeface(b7); pt.setTextSize(13); pt.setLetterSpacing(.12f);
        text(c, "RÉSERVOIR", 26, wt + 30, b7, 13, MUTE, Paint.Align.LEFT, .12f);
        text(c, "W'BAL", 26 + pt.measureText("RÉSERVOIR ") + 4, wt + 30, b7, 13, DIM, Paint.Align.LEFT, .12f);
        pt.setTypeface(b7); pt.setTextSize(11); pt.setLetterSpacing(.14f);
        float tgw = pt.measureText("EXCLU NCT") + 18;
        pf.setColor(ORANGE); rf.set(454 - tgw, wt + 15, 454, wt + 35); c.drawRoundRect(rf, 10, 10, pf);
        text(c, "EXCLU NCT", 454 - tgw / 2, wt + 29, b7, 11, BG, Paint.Align.CENTER, .14f);
        double pct = v("wbal");
        int wc = wcol(pct);
        String ps_ = i0(pct);
        text(c, ps_, 24, wt + 127, bc8, 104, wc, Paint.Align.LEFT, -.02f);
        pt.setTypeface(bc8); pt.setTextSize(104); pt.setLetterSpacing(-.02f);
        text(c, "%", 24 + pt.measureText(ps_) + 4, wt + 127, bc7, 36, MUTE, Paint.Align.LEFT, 0);
        text(c, f1(m.wbal / 1000) + " / " + f1(m.wPrime / 1000) + " kJ", 454, wt + 127, bc6, 19, MUTE, Paint.Align.RIGHT, 0);
        int on = (int) Math.round(pct / 100 * 24);
        float segW = (428 - 23 * 3) / 24f;
        for (int i = 0; i < 24; i++) {
            pf.setColor(i < on ? wcol(100.0 * i / 24 + 4) : 0xFF17212C);
            float x = 26 + i * (segW + 3);
            rf.set(x, wt + 144, x + segW, wt + 170); c.drawRoundRect(rf, 3, 3, pf);
        }
        text(c, wmsg(pct), 26, wt + 200, b6, 15, MUTE, Paint.Align.LEFT, 0);

        // zones
        float zt = wb + 10, zb = bottom - 12;
        tile(c, 12, zt, 468, zb, "TEMPS DANS LES ZONES", null);
        long zmax = 1; for (long x : m.zoneSec) zmax = Math.max(zmax, x);
        float rowH = (zb - zt - 50) / 7f;
        int cz = Math.max(0, lastZone);
        for (int i = 0; i < 7; i++) {
            float y = zt + 50 + rowH * i + rowH / 2;
            text(c, Metrics.ZN[i], 26, y + 6, bc7, 17, Metrics.ZCOL[i], Paint.Align.LEFT, 0);
            pf.setColor(0xFF141D27); rf.set(58, y - 7, 380, y + 7); c.drawRoundRect(rf, 7, 7, pf);
            float bw = (float) (322.0 * m.zoneSec[i] / zmax);
            if (bw > 1) { pf.setColor(Metrics.ZCOL[i]); rf.set(58, y - 7, 58 + Math.max(14, bw), y + 7); c.drawRoundRect(rf, 7, 7, pf); }
            text(c, ms(m.zoneSec[i]), 454, y + 6, bc6, 17, i == cz && state.equals("rec") ? TXT : MUTE, Paint.Align.RIGHT, 0);
        }
    }

    private void pageClimb(Canvas c, float top, float bottom) {
        float t = top + 8;
        double g = v("grade");
        pf.setColor(gcol(m.grade)); rf.set(12, t, 274, t + 206); c.drawRoundRect(rf, 16, 16, pf);
        String gs = f1(g);
        float fs = (g < 0 || g >= 10) ? 112 : 146;
        pt.setTypeface(bc8); pt.setTextSize(fs); pt.setLetterSpacing(-.03f);
        float gw = pt.measureText(gs);
        pt.setTextSize(fs / 3.1f); float pw = pt.measureText("%");
        float gx = 143 - (gw + pw) / 2;
        text(c, gs, gx, t + 140, bc8, fs, BG, Paint.Align.LEFT, -.03f);
        text(c, "%", gx + gw + 2, t + 140, bc8, fs / 3.1f, BG, Paint.Align.LEFT, 0);
        text(c, "PENTE", 143, t + 188, b7, 14, 0xBF090E14, Paint.Align.CENTER, .28f);
        // côté
        small(c, 284, t, 468, t + 98, "VAM", i0(v("vam")), "m/h");
        small(c, 284, t + 108, 468, t + 206, "ALTITUDE", i0(v("alt")), "m");
        // profil
        float pt0 = t + 216, pb0 = bottom - 12 - 132 - 10;
        tile(c, 12, pt0, 468, pb0, "PROFIL", null);
        pt.setTypeface(b7); pt.setTextSize(13); pt.setLetterSpacing(.12f);
        text(c, "5 DERNIERS KM", 26 + pt.measureText("PROFIL ") + 4, pt0 + 30, b7, 13, DIM, Paint.Align.LEFT, .12f);
        drawProfile(c, 26, pt0 + 46, 454, pb0 - 14);
        // bas
        float bt = bottom - 12 - 132;
        tile(c, 12, bt, 234, bottom - 12, "D+ TOTAL", null);
        valUnit(c, 26, bt + 92, i0(v("dplus")), 58, "M");
        tile(c, 246, bt, 468, bottom - 12, "PUISSANCE 30S", null);
        valUnit(c, 260, bt + 92, i0(v("p30")), 58, "W");
    }

    private void drawProfile(Canvas c, float l, float t, float r, float b) {
        if (m.prof.size() < 2) {
            text(c, "En attente de données d'altitude…", (l + r) / 2, (t + b) / 2, b6, 15, DIM, Paint.Align.CENTER, 0);
            return;
        }
        double d0 = m.prof.get(0).d, d1 = m.prof.get(m.prof.size() - 1).d, span = Math.max(1000, d1 - d0);
        double mn = 1e9, mx = -1e9;
        for (Metrics.Pt p : m.prof) { mn = Math.min(mn, p.a); mx = Math.max(mx, p.a); }
        if (mx - mn < 30) { double mid = (mx + mn) / 2; mn = mid - 15; mx = mid + 15; }
        float W = r - l, Hh = b - t;
        path.reset();
        float lx = 0, ly = 0, fx = 0;
        for (int i = 0; i < m.prof.size(); i++) {
            Metrics.Pt p = m.prof.get(i);
            float x = (float) (r - (d1 - p.d) / span * W), y = (float) (b - 8 - (p.a - mn) / (mx - mn) * (Hh - 16));
            if (i == 0) { path.moveTo(x, y); fx = x; } else path.lineTo(x, y);
            lx = x; ly = y;
        }
        Path fill = new Path(path); fill.lineTo(lx, b); fill.lineTo(fx, b); fill.close();
        pf.setShader(new LinearGradient(0, t, 0, b, 0x8C00E5C8, 0x0000E5C8, Shader.TileMode.CLAMP));
        c.drawPath(fill, pf); pf.setShader(null);
        ps.setColor(CYAN); ps.setStrokeWidth(3); ps.setStrokeJoin(Paint.Join.ROUND); c.drawPath(path, ps);
        pf.setColor(0xFFFFFFFF); c.drawCircle(lx - 4, ly, 7, pf);
        ps.setColor(ORANGE); ps.setStrokeWidth(4); c.drawCircle(lx - 4, ly, 7, ps);
    }

    private void pageLap(Canvas c, float top, float bottom) {
        float t = top + 8;
        Metrics.Lap L = m.lap, P = m.prevLap;
        pf.setShader(new LinearGradient(12, t, 468, t + 150, 0x2400E5C8, 0x1AE85022, Shader.TileMode.CLAMP));
        rf.set(12, t, 468, t + 150); c.drawRoundRect(rf, 16, 16, pf); pf.setShader(null);
        ps.setColor(LINE); ps.setStrokeWidth(1); c.drawRoundRect(rf, 16, 16, ps);
        pt.setTypeface(bc7); pt.setTextSize(18); pt.setLetterSpacing(.22f);
        String n = String.valueOf(L.n);
        float w1 = pt.measureText("TOUR "), w2 = pt.measureText(n);
        text(c, "TOUR ", 240 - (w1 + w2) / 2, t + 34, bc7, 18, MUTE, Paint.Align.LEFT, .22f);
        text(c, n, 240 - (w1 + w2) / 2 + w1, t + 34, bc7, 18, CYAN, Paint.Align.LEFT, .22f);
        text(c, ms(L.t), 240, t + 112, bc8, 84, TXT, Paint.Align.CENTER, 0);
        text(c, String.format(Locale.US, "%.2f km", L.dist / 1000), 240, t + 138, bc6, 18, MUTE, Paint.Align.CENTER, 0);

        float g0 = t + 160, rh = (bottom - 12 - g0 - 20) / 3;
        String[] lab = {"PUISS. MOY", "NP TOUR", "FC MOY", "VITESSE MOY", "CADENCE MOY", "W/KG TOUR"};
        String[] un = {"W", "W", "BPM", "KM/H", "RPM", ""};
        String[] vv = {i0(v("lapP")), i0(v("lapNp")), L.hr() > 0 ? i0(v("lapHr")) : "--", f1(v("lapS")), L.cad() > 0 ? i0(v("lapC")) : "--", f1(v("lapW"))};
        double[] a = {L.ap(), L.np(), L.hr(), L.spd(), L.cad(), L.ap() / m.weight};
        double[] bb = P == null ? null : new double[]{P.ap(), P.np(), P.hr(), P.spd(), P.cad(), P.ap() / m.weight};
        int[] dec = {0, 0, 0, 1, 0, 1};
        String[] du = {" W", " W", "", "", "", ""};
        for (int i = 0; i < 6; i++) {
            float x = i % 2 == 0 ? 12 : 246, y = g0 + (i / 2) * (rh + 10);
            tile(c, x, y, x + 222, y + rh, lab[i], null);
            valUnit(c, x + 14, y + 88, vv[i], 58, un[i]);
            if (bb != null && a[i] > 0 && bb[i] > 0) {
                double d = a[i] - bb[i];
                String ds = (d >= 0 ? "▲ +" : "▼ ") + String.format(Locale.US, "%." + dec[i] + "f", d) + du[i] + " vs tour préc.";
                text(c, ds, x + 14, y + rh - 16, bc7, 16, d >= 0 ? CYAN : ORANGE, Paint.Align.LEFT, 0);
            }
        }
    }

    // ---------------------------------------------------------------- overlays

    private void drawOverlays(Canvas c, long n) {
        // bandeau pause : rappel des actions
        if ((state.equals("pause") || state.equals("auto")) && mode == M_DASH && n >= confirmUntil && !summary) {
            float y = H - 64 - 58;
            pf.setColor(0xF2120E06); rf.set(12, y, 468, y + 50); c.drawRoundRect(rf, 14, 14, pf);
            ps.setColor(AMBER); ps.setStrokeWidth(2); c.drawRoundRect(rf, 14, 14, ps);
            text(c, state.equals("auto") ? "REPARS = REPRISE" : "START  reprendre", 128, y + 32, bc7, 20, AMBER, Paint.Align.CENTER, .06f);
            pf.setColor(0x66FFB02E); c.drawRect(239, y + 12, 241, y + 38, pf);
            text(c, "TOUR  terminer", 354, y + 32, bc7, 20, TXT, Paint.Align.CENTER, .06f);
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
        }
        // apprentissage des boutons
        if (learnStep >= 0) {
            pf.setColor(0xF5090E14); c.drawRect(0, 0, 480, H, pf);
            text(c, "CONFIGURATION DES BOUTONS", 240, H / 2 - 150, b7, 15, MUTE, Paint.Align.CENTER, .2f);
            text(c, (learnStep + 1) + " / 4", 240, H / 2 - 100, bc7, 22, CYAN, Paint.Align.CENTER, 0);
            text(c, "Appuie sur le bouton", 240, H / 2 - 50, b6, 28, TXT, Paint.Align.CENTER, 0);
            text(c, LEARN[learnStep][1], 240, H / 2 + 20, bc8, 56, CYAN, Paint.Align.CENTER, .02f);
            text(c, learnErr, 240, H / 2 + 80, bc6, 20, ORANGE, Paint.Align.CENTER, 0);
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
        text(c, "Appuie sur un bouton pour continuer", 240, H - 26, b6, 15, MUTE, Paint.Align.CENTER, 0);
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
        text(c, hint, 240, H - 26, b6, 15, MUTE, Paint.Align.CENTER, 0);
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

    private void drawMenus(Canvas c, long n) {
        if (mode == M_MENU) {
            panelHeader(c, "MENU", "▲▼ naviguer  ·  START valider  ·  TOUR retour");
            int b = host.brightness();
            for (int i = 0; i < MENU.length; i++)
                row(c, i, i == sel, MENU[i], null, i == 2 ? (b < 0 ? "AUTO" : b + " %") : null, CYAN);
        } else if (mode == M_SENS) {
            if (n - sensRefresh > 1000) { sensCache = host.sensorList(); sensRefresh = n; if (sel >= sensCache.size()) sel = Math.max(0, sensCache.size() - 1); }
            panelHeader(c, "CAPTEURS", "START ajouter / retirer  ·  TOUR retour");
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
            panelHeader(c, "PROFIL", profEdit ? "▲▼ modifier  ·  START valider" : "▲▼ choisir  ·  START modifier  ·  TOUR retour");
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
        if (y > H - 64) { button("lap"); return; } // bas d'écran : retour
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

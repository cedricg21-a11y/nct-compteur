package fr.nct.compteur;

import android.app.Activity;
import android.os.Environment;
import android.util.Log;
import android.view.View;

import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.datastore.MultiMapDataStore;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.XmlRenderTheme;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Carte hors ligne Mapsforge : lit les fichiers .map déjà téléchargés par l'appli Xcadey
 * (Android/data/com.xcadey.bike_computer/files/cycling_navigation/maps) ou copiés dans NCT/maps.
 */
public class MapHolder {
    private static final String TAG = "NCT-MAP";
    public MapView view;
    public boolean ready;
    public String info = "";
    private Polyline track;
    private long userTouchT = 0;
    private boolean visible;
    private LatLong lastPos;

    public MapHolder(Activity a) {
        try {
            AndroidGraphicFactory.createInstance(a.getApplication());
            view = new MapView(a);
            view.setClickable(true);
            view.setBuiltInZoomControls(false);
            view.getMapScaleBar().setVisible(false);
            view.setVisibility(View.INVISIBLE);
            view.setOnTouchListener((v, e) -> { userTouchT = System.currentTimeMillis(); return false; });
            load(a);
        } catch (Throwable t) {
            Log.e(TAG, "init", t);
            info = "Erreur carte : " + t.getClass().getSimpleName();
            ready = false;
        }
    }

    private void load(Activity a) {
        List<File> files = new ArrayList<>();
        File sd = Environment.getExternalStorageDirectory();
        scan(new File(sd, "NCT/maps"), files, 2);
        scan(new File(sd, "Android/data/com.xcadey.bike_computer/files/cycling_navigation/maps"), files, 2);
        if (files.isEmpty()) { info = "Aucun fichier .map trouvé"; return; }

        MapDataStore store;
        if (files.size() == 1) store = new MapFile(files.get(0));
        else {
            MultiMapDataStore multi = new MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL);
            for (File f : files) multi.addMapDataStore(new MapFile(f), false, false);
            store = multi;
        }
        TileCache cache = AndroidUtil.createTileCache(a, "nctmap",
                view.getModel().displayModel.getTileSize(), 1f, view.getModel().frameBufferModel.getOverdrawFactor());
        TileRendererLayer layer = new TileRendererLayer(cache, store, view.getModel().mapViewPosition, AndroidGraphicFactory.INSTANCE);
        layer.setXmlRenderTheme(theme());
        view.getLayerManager().getLayers().add(layer);

        Paint p = AndroidGraphicFactory.INSTANCE.createPaint();
        p.setColor(0xFF00E5C8);
        p.setStrokeWidth(7);
        p.setStyle(Style.STROKE);
        track = new Polyline(p, AndroidGraphicFactory.INSTANCE);
        view.getLayerManager().getLayers().add(track);

        LatLong start = store.startPosition() != null ? store.startPosition() : new LatLong(49.096, 0.416);
        view.setCenter(new LatLong(49.096, 0.416));
        if (store.boundingBox() != null && !store.boundingBox().contains(49.096, 0.416)) view.setCenter(start);
        view.setZoomLevel((byte) 16);
        StringBuilder sb = new StringBuilder();
        for (File f : files) sb.append(sb.length() > 0 ? ", " : "").append(f.getName());
        info = sb.toString();
        ready = true;
    }

    /** Thème sombre si disponible dans cette version de Mapsforge, sinon thème par défaut. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static XmlRenderTheme theme() {
        String[] classes = {"org.mapsforge.map.rendertheme.internal.MapsforgeThemes", "org.mapsforge.map.rendertheme.InternalRenderTheme"};
        String[] names = {"MOTORIDER_DARK", "MOTORIDER", "DEFAULT"};
        for (String cn : classes) {
            try {
                Class cl = Class.forName(cn);
                for (String n : names) {
                    try { return (XmlRenderTheme) Enum.valueOf(cl, n); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void scan(File dir, List<File> out, int depth) {
        File[] l = dir.listFiles();
        if (l == null) return;
        for (File f : l) {
            if (f.isDirectory() && depth > 0) scan(f, out, depth - 1);
            else if (f.getName().toLowerCase().endsWith(".map")) out.add(f);
        }
    }

    public void setVisible(boolean on) {
        visible = on;
        if (view != null) view.setVisibility(on && ready ? View.VISIBLE : View.INVISIBLE);
        if (on && lastPos != null && ready) view.setCenter(lastPos);
    }

    public void update(Ride.Sample s) {
        if (!ready || Double.isNaN(s.lat)) return;
        LatLong p = new LatLong(s.lat, s.lon);
        lastPos = p;
        if ("rec".equals(s.state) && track != null) {
            track.getLatLongs().add(p);
            track.requestRedraw();
        }
        // suivi automatique, sauf si l'utilisateur vient de déplacer la carte
        if (visible && !explore) view.setCenter(p);
    }

    private boolean explore;

    public void setExplore(boolean on) {
        explore = on;
        if (!on && lastPos != null && ready) view.setCenter(lastPos);
    }

    /** position actuelle en pixels écran (vue carte), ou null */
    public float[] positionPx() {
        if (!ready || lastPos == null || view == null) return null;
        try {
            org.mapsforge.core.model.Point pt = new org.mapsforge.map.util.MapViewProjection(view).toPixels(lastPos);
            return pt == null ? null : new float[]{(float) pt.x, (float) pt.y};
        } catch (Throwable t) { return null; }
    }

    public void zoom(int d) {
        if (!ready) return;
        byte z = view.getModel().mapViewPosition.getZoomLevel();
        int nz = Math.max(10, Math.min(19, z + d));
        view.setZoomLevel((byte) nz);
        if (lastPos != null && !explore) view.setCenter(lastPos);
    }

    public void clearTrack() {
        if (track != null) { track.getLatLongs().clear(); track.requestRedraw(); }
    }

    public void destroy() {
        try { if (view != null) view.destroyAll(); AndroidGraphicFactory.clearResourceMemoryCache(); } catch (Throwable ignored) {}
    }
}

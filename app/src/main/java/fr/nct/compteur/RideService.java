package fr.nct.compteur;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

/** Service de premier plan : garde l'enregistrement actif même écran éteint. */
public class RideService extends Service {
    private PowerManager.WakeLock wl;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel("ride", "NCT Compteur actif", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "ride")
                .setContentTitle("NCT Compteur")
                .setContentText("Actif : capteurs et GPS connectés")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(1, n);
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nct:ride");
            wl.acquire(12 * 3600 * 1000L);
        }
        if (screenRx == null) {
            screenRx = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context ctx, Intent i) {
                    // bouton marche : on rallume aussitôt l'écran et on revient sur l'appli
                    if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                        // écran rallumé : relance capteurs + GPS (après un court délai, le temps que la ROM réactive les radios)
                        new android.os.Handler().postDelayed(() -> Ride.get(RideService.this).recover(true), 1500);
                        return;
                    }
                    if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("blockSleep", true)) return;
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    @SuppressWarnings("deprecation")
                    PowerManager.WakeLock w = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "nct:wake");
                    w.acquire(3000);
                    Intent a = new Intent(RideService.this, MainActivity.class);
                    a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    try { startActivity(a); } catch (Exception ignored) {}
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenRx, f);
        }
        return START_STICKY;
    }

    private android.content.BroadcastReceiver screenRx;

    @Override public void onDestroy() {
        if (screenRx != null) { try { unregisterReceiver(screenRx); } catch (Exception ignored) {} screenRx = null; }
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}

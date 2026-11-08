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
        NotificationChannel ch = new NotificationChannel("ride", "Sortie en cours", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "ride")
                .setContentTitle("NCT Compteur")
                .setContentText("Sortie en cours d'enregistrement")
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
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}

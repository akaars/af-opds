package io.local.tvopds;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

public final class ServerService extends Service {
    private static final String CHANNEL = "tvopds_server";
    private static final int NOTICE_ID = 3200;
    private static volatile boolean running;
    private OpdsServer server;

    static boolean isRunning() { return running; }

    static void start(Context context) {
        Intent service = new Intent(context, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }

    static void restart(Context context) {
        Context app = context.getApplicationContext();
        EventLog.add("Перезапуск сервера запрошен из веб-панели");
        app.stopService(new Intent(app, ServerService.class));
        new Handler(Looper.getMainLooper()).postDelayed(() -> start(app), 300);
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "OPDS server", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Intent activity = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, activity,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(R.drawable.ic_book)
                .setContentTitle("AF-OPDS запущен")
                .setContentText("Библиотека доступна в локальной сети на порту 8080")
                .setContentIntent(pi).setOngoing(true).build();
        startForeground(NOTICE_ID, notification);
        if (server == null) {
            try {
                server = new OpdsServer(getApplicationContext(), 8080);
                server.start();
                running = true;
                EventLog.add("Сервер запущен на порту 8080");
            } catch (Exception e) {
                EventLog.add("Ошибка запуска сервера: " + e.getMessage());
                android.util.Log.e("AF-OPDS", "Cannot start HTTP server", e);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (server != null) { server.close(); server = null; }
        running = false;
        EventLog.add("Сервер остановлен");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

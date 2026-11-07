package io.local.tvopds;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import java.io.File;

/** Shared scanning actions for the on-device and web control panels. */
final class LibraryTasks {
    private static volatile boolean scanning;
    private static volatile String state = "Ожидание";

    static boolean isScanning() { return scanning; }
    static String state() { return state; }

    static String selectedUri(Context context) {
        SharedPreferences prefs = prefs(context);
        String uri = prefs.getString("tree", null);
        if (uri == null) {
            String path = prefs.getString("path", null);
            if (path != null) uri = Uri.fromFile(new File(path)).toString();
        }
        return uri;
    }

    static String selectedFolder(Context context) {
        String uri = selectedUri(context);
        return uri == null ? null : Uri.parse(uri).getLastPathSegment();
    }

    static void setPath(Context context, String path) throws java.io.IOException {
        verifyPath(path);
        prefs(context).edit().putString("path", path).remove("tree").apply();
        EventLog.add("Выбрана папка: " + path);
    }

    static void startScan(Context context) {
        final Context app = context.getApplicationContext();
        final String uri;
        synchronized (LibraryTasks.class) {
            if (scanning) { EventLog.add("Сканирование уже выполняется"); return; }
            uri = selectedUri(app);
            if (uri == null) { state = "Сначала выберите папку"; EventLog.add(state); return; }
            scanning = true;
            state = "Сканирование выполняется";
        }
        EventLog.add("Сканирование запущено: " + uri);
        new Thread(() -> scan(app, uri), "opds-scan").start();
    }

    private static void scan(Context context, String selectedUri) {
        long started = SystemClock.elapsedRealtime();
        try {
            Uri tree = Uri.parse(selectedUri);
            verify(context, tree);
            try (LibraryDb db = new LibraryDb(context)) {
                int books = db.replace(sink -> {
                    new LibraryScanner(context, tree, (count, archive) -> {
                        long seconds = (SystemClock.elapsedRealtime() - started) / 1000;
                        state = "Импортировано " + count + " книг, " + seconds + " с";
                        EventLog.add(state + (archive == null ? "" : ": " + archive));
                    }).scanInto(sink);
                    verify(context, tree);
                }, () -> {
                    state = "Создаются индексы SQLite";
                    EventLog.add(state);
                });
                long seconds = (SystemClock.elapsedRealtime() - started) / 1000;
                state = "Индексация завершена: " + books + " книг за " + seconds + " с";
                EventLog.add(state);
            }
        } catch (Exception e) {
            state = "Ошибка сканирования: " + message(e) + ". Предыдущий индекс сохранён.";
            EventLog.add(state);
            android.util.Log.e("AF-OPDS", "Scan failed", e);
        } finally {
            scanning = false;
            EventLog.add("Сканирование остановлено");
        }
    }

    static String check(Context context) throws Exception {
        String uri = selectedUri(context);
        if (uri == null) throw new java.io.IOException("Папка не выбрана");
        String name = verify(context, Uri.parse(uri));
        String result = "USB доступен: " + name;
        EventLog.add(result);
        return result;
    }

    static String verify(Context context, Uri tree) throws Exception {
        if ("file".equals(tree.getScheme())) return verifyPath(tree.getPath());
        return UsbLibraryAccess.verify(context, tree);
    }

    static String verifyPath(String path) throws java.io.IOException {
        if (path == null || path.isEmpty()) throw new java.io.IOException("Путь не указан");
        File root = new File(path);
        if (!root.isDirectory() || !root.canRead()) throw new java.io.IOException("Нет доступа к папке");
        return root.getName();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("tvopds", Context.MODE_PRIVATE);
    }

    private static String message(Exception e) {
        String value = e.getMessage();
        return value == null || value.isEmpty() ? e.getClass().getSimpleName() : value;
    }

    private LibraryTasks() {}
}

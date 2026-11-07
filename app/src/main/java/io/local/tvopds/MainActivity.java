package io.local.tvopds;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_TREE = 101;
    private static final int REQUEST_READ_STORAGE = 102;
    private TextView status;
    private TextView logs;
    private Button scan;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("tvopds", MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(24, 20, 24, 16);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        ScrollView outer = new ScrollView(this);
        outer.addView(layout);
        screen.addView(outer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        TextView version = new TextView(this);
        version.setText("Версия " + appVersion());
        version.setTextSize(13);
        version.setGravity(android.view.Gravity.END);
        version.setPadding(24, 4, 24, 12);
        screen.addView(version);
        setContentView(screen);

        TextView heading = new TextView(this);
        heading.setText("AF-OPDS — домашняя библиотека");
        heading.setTextSize(24);
        layout.addView(heading);

        status = new TextView(this);
        status.setTextSize(17);
        status.setPadding(0, 15, 0, 15);
        status.setMovementMethod(new ScrollingMovementMethod());
        layout.addView(status);

        Button choose = button("Выбрать папку с книгами");
        choose.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            // Some Android TV firmware ships without a DocumentsUI/DocumentsProvider activity.
            // Starting this implicit intent without a handler terminates the app with
            // ActivityNotFoundException.
            if (intent.resolveActivity(getPackageManager()) == null) {
                refresh("На этой прошивке нет системного выбора папок. Установите или включите файловый менеджер, поддерживающий Storage Access Framework (SAF).");
                return;
            }
            try {
                startActivityForResult(intent, REQUEST_TREE);
            } catch (android.content.ActivityNotFoundException | SecurityException e) {
                android.util.Log.w("AF-OPDS", "Document-tree picker is unavailable", e);
                refresh("Не удалось открыть системный выбор папок: " + e.getMessage());
            }
        });
        layout.addView(choose);

        Button direct = button("Указать путь к папке USB");
        direct.setOnClickListener(v -> requestDirectPath());
        layout.addView(direct);

        Button check = button("Проверить USB-диск");
        check.setOnClickListener(v -> checkDrive());
        layout.addView(check);

        scan = button("Сканировать библиотеку");
        scan.setOnClickListener(v -> { LibraryTasks.startScan(this); refresh(null); });
        layout.addView(scan);

        Button start = button("Запустить OPDS-сервер");
        start.setOnClickListener(v -> {
            ServerService.start(this);
            refreshAfterServiceChange();
        });
        layout.addView(start);

        Button stop = button("Остановить сервер");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ServerService.class));
            refreshAfterServiceChange();
        });
        layout.addView(stop);

        CheckBox autoboot = new CheckBox(this);
        autoboot.setText("Запускать сервер после перезагрузки приставки");
        autoboot.setTextSize(16);
        autoboot.setChecked(prefs.getBoolean("start_on_boot", false));
        autoboot.setOnCheckedChangeListener((box, checked) -> prefs.edit().putBoolean("start_on_boot", checked).apply());
        layout.addView(autoboot);

        TextView logTitle = new TextView(this);
        logTitle.setText("Живой журнал (последние 200 событий)");
        logTitle.setTextSize(17);
        logTitle.setPadding(0, 18, 0, 6);
        layout.addView(logTitle);
        logs = new TextView(this);
        logs.setTextSize(12);
        logs.setTypeface(android.graphics.Typeface.MONOSPACE);
        logs.setPadding(12, 10, 12, 10);
        logs.setBackgroundColor(0xfff0f0f0);
        logs.setTextIsSelectable(true);
        layout.addView(logs);
        refresh(null);
    }

    @Override protected void onResume() {
        super.onResume();
        EventLog.setListener(() -> runOnUiThread(() -> refresh(null)));
        refresh(null);
        if (prefs != null && prefs.getString("tree", null) != null) checkDrive();
    }

    @Override protected void onPause() {
        EventLog.setListener(null);
        super.onPause();
    }

    private void checkDrive() {
        String selected = prefs.getString("tree", null);
        String path = prefs.getString("path", null);
        if (selected == null && path == null) { refresh("Сначала выберите папку."); return; }
        new Thread(() -> {
            String note;
            try {
                note = LibraryTasks.check(this);
            } catch (Exception e) {
                note = "USB недоступен: " + e.getMessage() + ". Предыдущий каталог сохранён.";
                EventLog.add(note);
            }
            String result = note;
            runOnUiThread(() -> {
                // Do not show an obsolete result if another folder was selected meanwhile.
                if ((path != null && path.equals(prefs.getString("path", null))) ||
                        (selected != null && selected.equals(prefs.getString("tree", null)))) refresh(result);
            });
        }, "opds-usb-check").start();
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        return b;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "неизвестна";
        }
    }

    private void refresh(String note) {
        String folder = LibraryTasks.selectedFolder(this);
        int count = -1;
        if (!LibraryTasks.isScanning()) {
            try (LibraryDb db = new LibraryDb(this)) { count = db.count(); }
            catch (Exception ignored) { /* The previous count is not worth crashing the control screen. */ }
        }
        StringBuilder s = new StringBuilder("Папка: ").append(folder == null ? "не выбрана" : folder);
        s.append("\nКниг: ").append(count < 0 ? "индекс обновляется" : count);
        s.append("\nСервер: ").append(ServerService.isRunning() ? "запущен" : "остановлен");
        s.append("\nСканирование: ").append(LibraryTasks.state());
        String address = address();
        s.append("\nПанель: http://").append(address).append(":8080/");
        s.append("\nOPDS: http://").append(address).append(":8080/opds");
        s.append("\nДоступ: только по доверенной локальной сети, без пароля и HTTPS.");
        if (note != null) s.append("\n\n").append(note);
        status.setText(s);
        scan.setEnabled(!LibraryTasks.isScanning());
        if (logs != null) logs.setText(EventLog.text());
    }

    private void refreshAfterServiceChange() {
        status.postDelayed(() -> refresh(null), 400);
    }

    private String address() {
        try {
            List<NetworkInterface> adapters = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface adapter : adapters) {
                if (!adapter.isUp() || adapter.isLoopback()) continue;
                for (java.net.InetAddress ip : Collections.list(adapter.getInetAddresses())) {
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) return ip.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "IP-адрес-приставки";
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
            prefs.edit().putString("tree", uri.toString()).remove("path").apply();
            EventLog.add("Выбрана папка через системный диалог: " + uri);
            checkDrive();
        } catch (Exception e) { refresh("Ошибка доступа к папке: " + e.getMessage()); }
    }

    private void requestDirectPath() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_STORAGE);
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("/storage/XXXX-XXXX/fb2.Flibusta.Net");
        input.setText(prefs.getString("path", ""));
        new AlertDialog.Builder(this).setTitle("Путь к папке книг").setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Использовать", (d, w) -> {
                    String path = input.getText().toString().trim();
                    try {
                        LibraryTasks.setPath(this, path);
                        checkDrive();
                    } catch (Exception e) { refresh("Папка недоступна: " + e.getMessage()); }
                }).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_READ_STORAGE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestDirectPath();
            else refresh("Разрешение на чтение USB отклонено.");
        }
    }

}

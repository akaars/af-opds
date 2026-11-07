package io.local.tvopds;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Small in-memory diagnostic log. It deliberately does not write user activity to storage. */
final class EventLog {
    private static final int MAX_LINES = 200;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static Listener listener;

    interface Listener { void onChanged(); }

    static void add(String message) {
        Listener notify;
        synchronized (EventLog.class) {
            if (LINES.size() == MAX_LINES) LINES.removeFirst();
            String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            LINES.addLast(time + "  " + message);
            notify = listener;
        }
        Log.i("AF-OPDS", message);
        if (notify != null) notify.onChanged();
    }

    static synchronized String text() {
        StringBuilder result = new StringBuilder();
        for (String line : LINES) {
            if (result.length() > 0) result.append('\n');
            result.append(line);
        }
        return result.toString();
    }

    static synchronized void setListener(Listener value) { listener = value; }

    private EventLog() {}
}

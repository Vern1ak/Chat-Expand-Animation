package com.vernyak.chat_expand;

import java.util.ArrayDeque;
import java.util.Calendar;

/** In-memory ring buffer log (same role as _LOGBUF/_emit in the old Python plugin). */
final class L {
    private static final int MAX = 900;
    private static final ArrayDeque<String> BUF = new ArrayDeque<String>();

    private L() {}

    static synchronized void emit(String msg) {
        try {
            Calendar c = Calendar.getInstance();
            String line = String.format("%02d:%02d:%02d.%03d %s",
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
                    c.get(Calendar.MILLISECOND), msg);
            if (BUF.size() >= MAX) BUF.pollFirst();
            BUF.addLast(line);
        } catch (Throwable ignored) {
        }
    }

    /** Log to the ring buffer and to logcat. */
    static void log(String msg) {
        emit(msg);
        try {
            android.util.Log.i("chat_expand_animation", msg);
        } catch (Throwable ignored) {
        }
    }

    static synchronized void clear() {
        BUF.clear();
    }

    static synchronized int size() {
        return BUF.size();
    }

    static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String s : BUF) {
            if (!first) sb.append('\n');
            sb.append(s);
            first = false;
        }
        return sb.toString();
    }
}

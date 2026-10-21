package com.phili.notifguard;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Small text log (last 200 lines), shown in the app and in logcat (tag NotifGuard). */
final class Journal {
    private static final String TAG = "NotifGuard";
    private static final String FILE = "journal.txt";
    private static final int MAX_LINES = 200;

    private Journal() {
    }

    static synchronized void write(Context context, String message) {
        String line = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + message;
        Log.i(TAG, message);
        List<String> lines = read(context);
        lines.add(line);
        if (lines.size() > MAX_LINES) {
            lines = new ArrayList<>(lines.subList(lines.size() - MAX_LINES, lines.size()));
        }
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE))) {
            out.write(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "cannot write the log", e);
        }
    }

    static synchronized List<String> read(Context context) {
        File f = new File(context.getFilesDir(), FILE);
        if (!f.exists()) return new ArrayList<>();
        try {
            return new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    static List<String> newestFirst(Context context) {
        List<String> lines = read(context);
        Collections.reverse(lines);
        return lines;
    }
}

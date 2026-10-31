package com.vernyak.chat_expand;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

/**
 * Entry point of the DEX. The Python plugin is a thin shell: it loads this class, calls
 * init/load/unload and asks for log text. Hooks are registered by the DEX itself (see Hooks).
 * All methods are static and use only simple argument types so they can be located
 * reflectively by name + argument count.
 */
public final class CEA {
    private CEA() {}

    /** Bump on every release; the updater compares VERSION_CODE numerically. */
    public static final String VERSION = "1.2.0";
    public static final int VERSION_CODE = 120;
    /** Python-side contract version; the shell refuses a DEX with a different API level. */
    public static final int API = 1;

    private static ClassLoader appLoader;
    private static Controller ctl;
    private static String pluginId = "chat_expand_animation";

    static ClassLoader appLoader() {
        return appLoader;
    }

    public static String version() {
        return VERSION;
    }

    public static int versionCode() {
        return VERSION_CODE;
    }

    public static int api() {
        return API;
    }

    /** Called once after the DEX is loaded. */
    public static synchronized void init(ClassLoader loader, String id) {
        appLoader = loader;
        if (id != null && id.length() > 0) pluginId = id;
        S.init(pluginId);
        L.log("DEX " + VERSION + " (" + VERSION_CODE + ") initialised");
    }

    /** Loads the controller and registers the hooks. Returns the number of hooked methods. */
    public static synchronized int load() {
        if (ctl == null) ctl = new Controller();
        ctl.onLoad();
        int n = Hooks.install(ctl);
        L.log("load: " + n + " hook(s) installed");
        return n;
    }

    public static synchronized void unload() {
        Hooks.uninstall();
        Controller c = ctl;
        if (c != null) {
            try {
                c.onUnload();
            } catch (Throwable t) {
                L.log("unload failed: " + t);
            }
        }
        ctl = null;
    }

    // ------------------------------------------------------------------- log

    public static void log(String msg) {
        L.log(msg);
    }

    public static int logSize() {
        return L.size();
    }

    public static void clearLog() {
        L.clear();
    }

    /** Full diagnostic text. {@code settings} is the exported settings blob supplied by the Python shell. */
    public static String logText(String settings, String shellInfo) {
        StringBuilder sb = new StringBuilder();
        String device = "";
        try {
            device = " | Android " + android.os.Build.VERSION.SDK_INT + " | " + android.os.Build.MODEL;
        } catch (Throwable ignored) {
        }
        sb.append("chat_expand_animation DEX ").append(VERSION).append(" (").append(VERSION_CODE).append(")").append(device).append('\n');
        if (shellInfo != null && shellInfo.length() > 0) sb.append(shellInfo).append('\n');
        if (settings != null && settings.length() > 0) sb.append("settings: ").append(settings).append('\n');
        Controller c = ctl;
        sb.append(c != null ? c.statusLine() : "controller not loaded").append('\n');
        sb.append("--- log ---\n").append(L.text());
        return sb.toString();
    }

    /** Copies the log to the clipboard; returns the number of log lines, or -1 on failure. */
    public static int copyLog(Object context, String settings, String shellInfo) {
        try {
            Context ctx = (Context) context;
            String text = logText(settings, shellInfo);
            if (text.length() > 90000) text = text.substring(text.length() - 90000);
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("chat_expand_animation log", text));
            return L.size();
        } catch (Throwable t) {
            L.log("copy log failed: " + t);
            return -1;
        }
    }
}

package com.vernyak.chat_expand;

import java.lang.reflect.Method;

/**
 * Plugin settings. Read through PluginsController (the same store Python get_setting/set_setting use),
 * never compiled in: otherwise UI changes would silently do nothing.
 */
final class S {
    private S() {}

    private static String pluginId = "chat_expand_animation";
    private static Object controller;
    private static Method mBool, mInt;
    private static boolean warned;

    static synchronized void init(String id) {
        if (id != null && id.length() > 0) pluginId = id;
        controller = null;
        mBool = mInt = null;
        warned = false;
        try {
            Class<?> pc = R.cls("com.exteragram.messenger.plugins.PluginsController");
            if (pc == null) throw new ClassNotFoundException("PluginsController");
            controller = pc.getMethod("getInstance").invoke(null);
            for (Method m : pc.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 3 || p[0] != String.class || p[1] != String.class) continue;
                if (m.getName().equals("getPluginSettingBoolean") && (p[2] == boolean.class || p[2] == Boolean.class)) {
                    m.setAccessible(true);
                    mBool = m;
                } else if (m.getName().equals("getPluginSettingInt") && (p[2] == int.class || p[2] == Integer.class)) {
                    m.setAccessible(true);
                    mInt = m;
                }
            }
            if (mBool == null || mInt == null) L.log("settings: PluginsController accessors not found (bool=" + (mBool != null) + ", int=" + (mInt != null) + ")");
        } catch (Throwable t) {
            L.log("settings: PluginsController unavailable, defaults will be used: " + t);
        }
    }

    static boolean bool(String key, boolean def) {
        try {
            if (controller != null && mBool != null) {
                Object r = mBool.invoke(controller, pluginId, key, def);
                if (r instanceof Boolean) return (Boolean) r;
            }
        } catch (Throwable t) {
            warn(key, t);
        }
        return def;
    }

    static int integer(String key, int def) {
        try {
            if (controller != null && mInt != null) {
                Object r = mInt.invoke(controller, pluginId, key, def);
                if (r instanceof Number) return ((Number) r).intValue();
            }
        } catch (Throwable t) {
            warn(key, t);
        }
        return def;
    }

    private static void warn(String key, Throwable t) {
        if (!warned) {
            warned = true;
            L.log("settings: read of '" + key + "' failed: " + t);
        }
    }

    /** Selector "stretch_mode": 0 = chat content, 1 = source card, 2 = none (with legacy-key fallback). */
    static int stretchMode() {
        int m = integer("stretch_mode", -1);
        if (m < 0 || m > 2) {
            if (bool("stretch_source_card", false)) m = 1;
            else if (bool("stretch_chat_content_v3", true)) m = 0;
            else m = 2;
        }
        return m;
    }

    static String dump() {
        return "enabled=" + bool("enabled", true)
                + ", animate_close=" + bool("animate_close", true)
                + ", parallel_close=" + bool("parallel_close", true)
                + ", header_sprite_flight_v2=" + bool("header_sprite_flight_v2", true)
                + ", depth=" + bool("depth", true)
                + ", gradient_open=" + bool("gradient_open", true)
                + ", stretch_mode=" + stretchMode()
                + ", open_mode=" + integer("open_mode", 0)
                + ", speed=" + integer("speed", 1)
                + ", corner=" + integer("corner", 1)
                + ", inset=" + integer("inset", 1);
    }
}

package com.vernyak.chat_expand;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Registers the Telegram hooks directly through XposedBridge (the same mechanism the plugin SDK uses
 * under the hood), so the Python shell does not have to forward any hook traffic.
 */
final class Hooks {
    private Hooks() {}

    static volatile boolean checkHooked;

    private static final ArrayList<XC_MethodHook.Unhook> HANDLES = new ArrayList<XC_MethodHook.Unhook>();

    /** {className, methodName, kind, exact parameter class names (comma separated, may be empty)} */
    private static final String[][] TABLE = {
            {"org.telegram.ui.ChatActivity", "onCustomTransitionAnimation", "open", "boolean,java.lang.Runnable"},
            {"org.telegram.ui.ActionBar.ActionBarLayout", "resumeDelayedFragmentAnimation", "resume", ""},
            {"org.telegram.ui.ActionBar.ActionBarLayout", "onBackPressed", "back", ""},
            {"org.telegram.ui.ActionBar.ActionBarLayout", "closeLastFragment", "back", ""},
            {"org.telegram.ui.ChatActivity", "onBackPressed", "back", ""},
            {"org.telegram.ui.ActionBar.BaseFragment", "finishFragment", "back", ""},
            {"org.telegram.ui.ActionBar.ActionBarLayout", "animateBackEndAnimation", "backend", ""},
            {"org.telegram.ui.ActionBar.ActionBarLayout", "checkTransitionAnimation", "check", ""},
    };

    private static final class Fwd extends XC_MethodHook {
        private final Controller ctl;
        private final String kind;
        private final boolean wantBefore, wantAfter;

        Fwd(Controller ctl, String kind) {
            this.ctl = ctl;
            this.kind = kind;
            // "open"/"resume" only act after the original method; "back"/"backend" need both phases.
            this.wantBefore = kind.equals("back") || kind.equals("backend") || kind.equals("check");
            this.wantAfter = !kind.equals("check");
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!wantBefore) return;
            try {
                ctl.onHook(kind, true, param);
            } catch (Throwable t) {
                L.log("hook " + kind + " before failed: " + t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!wantAfter) return;
            try {
                ctl.onHook(kind, false, param);
            } catch (Throwable t) {
                L.log("hook " + kind + " after failed: " + t);
            }
        }
    }

    private static Class<?>[] exactTypes(String spec) {
        if (spec == null || spec.length() == 0) return null;
        String[] names = spec.split(",");
        Class<?>[] out = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            String n = names[i].trim();
            if (n.equals("boolean")) out[i] = boolean.class;
            else if (n.equals("int")) out[i] = int.class;
            else if (n.equals("float")) out[i] = float.class;
            else out[i] = R.cls(n);
            if (out[i] == null) return null;
        }
        return out;
    }

    /** Returns the number of hooked methods. */
    static synchronized int install(Controller ctl) {
        uninstall();
        checkHooked = false;
        int total = 0;
        for (String[] e : TABLE) {
            String cn = e[0], mn = e[1], kind = e[2];
            Class<?> c = R.cls(cn);
            if (c == null) {
                L.log("hook skipped: class not found " + cn + "." + mn);
                continue;
            }
            Class<?>[] exact = exactTypes(e[3]);
            Fwd fwd = new Fwd(ctl, kind);
            int installed = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(mn)) continue;
                if (exact != null && !java.util.Arrays.equals(m.getParameterTypes(), exact)) continue;
                try {
                    m.setAccessible(true);
                    XC_MethodHook.Unhook h = XposedBridge.hookMethod(m, fwd);
                    if (h != null) {
                        HANDLES.add(h);
                        installed++;
                    }
                } catch (Throwable t) {
                    L.log("hook overload " + cn + "." + mn + " failed: " + t);
                }
            }
            if (installed > 0 && "check".equals(kind)) checkHooked = true;
            if (installed > 0) L.log("hook registered: " + cn + "." + mn + " (" + installed + " overload(s))");
            else L.log("hook registration failed: " + cn + "." + mn + " (no overload accepted)");
            total += installed;
        }
        return total;
    }

    static synchronized void uninstall() {
        for (int i = HANDLES.size() - 1; i >= 0; i--) {
            try {
                HANDLES.get(i).unhook();
            } catch (Throwable t) {
                L.log("unhook failed: " + t);
            }
        }
        HANDLES.clear();
    }
}

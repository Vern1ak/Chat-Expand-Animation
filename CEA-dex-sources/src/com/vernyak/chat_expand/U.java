package com.vernyak.chat_expand;

/** Small math / time helpers (ports of _c01, _smooth, _out_cubic, time.monotonic). */
final class U {
    private U() {}

    static double c01(double x) {
        return x < 0.0 ? 0.0 : (x > 1.0 ? 1.0 : x);
    }

    static double smooth(double x) {
        x = c01(x);
        return x * x * (3.0 - 2.0 * x);
    }

    static double outCubic(double x) {
        x = c01(x);
        return 1.0 - Math.pow(1.0 - x, 3.0);
    }

    static double now() {
        return System.nanoTime() / 1e9;
    }

    static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Python's `x or alt` for floats: 0.0 counts as "missing". */
    static double or(double x, double alt) {
        return x != 0.0 ? x : alt;
    }
}

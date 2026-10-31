package com.vernyak.chat_expand;

/** Cubic-bezier easing sampled into a table (port of _make_ease). */
final class Ease {
    private final double[] xs;
    private final double[] ys;
    private final int n;

    Ease(double x1, double y1, double x2, double y2, int n) {
        this.n = n;
        xs = new double[n + 1];
        ys = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double m = 1.0 - t;
            xs[i] = 3 * m * m * t * x1 + 3 * m * t * t * x2 + Math.pow(t, 3);
            ys[i] = 3 * m * m * t * y1 + 3 * m * t * t * y2 + Math.pow(t, 3);
        }
    }

    double ease(double x) {
        if (x <= 0.0) return 0.0;
        if (x >= 1.0) return 1.0;
        int lo = 0, hi = n;
        while (hi - lo > 1) {
            int mid = (lo + hi) / 2;
            if (xs[mid] <= x) lo = mid;
            else hi = mid;
        }
        double span = xs[hi] - xs[lo];
        double f = span != 0.0 ? (x - xs[lo]) / span : 0.0;
        return ys[lo] + (ys[hi] - ys[lo]) * f;
    }
}

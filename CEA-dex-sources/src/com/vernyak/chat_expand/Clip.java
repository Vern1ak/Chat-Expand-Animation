package com.vernyak.chat_expand;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;

/** Rounded clip of a view (port of _Clip). Modes: "gd", "proxy" or plain clip bounds. */
final class Clip {
    private static final int GD = 1, PROXY = 2;

    final View view;
    private int mode;
    /** GradientDrawable (gd mode) or RectOutline (proxy mode); null in bounds mode. */
    Object provider;
    private GradientDrawable gd;
    private RectOutline proxy;
    private float[] radii;
    private Drawable oldBg;
    private final ViewOutlineProvider oldProvider;
    private final boolean oldClip;
    private int[] lastBounds;
    private int[] lastRadiusBucket;

    Clip(View view) {
        this.view = view;
        this.oldProvider = view.getOutlineProvider();
        this.oldClip = view.getClipToOutline();
        try {
            oldBg = view.getBackground();
            GradientDrawable d = new GradientDrawable();
            d.setColor(0);
            radii = new float[8];
            view.setBackground(d);
            view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            view.setClipToOutline(true);
            gd = d;
            mode = GD;
        } catch (Throwable e) {
            L.log("gd clip failed: " + e);
            undoGd();
        }
        if (mode == 0) {
            try {
                proxy = new RectOutline();
                view.setOutlineProvider(proxy);
                view.setClipToOutline(true);
                mode = PROXY;
            } catch (Throwable e) {
                L.log("proxy clip failed: " + e);
                proxy = null;
                view.setOutlineProvider(oldProvider);
                view.setClipToOutline(oldClip);
            }
        }
        if (mode == GD) provider = gd;
        else if (mode == PROXY) provider = proxy;
        L.log("clip mode: " + (mode == GD ? "gd" : mode == PROXY ? "proxy" : "bounds"));
    }

    private void undoGd() {
        try {
            view.setBackground(oldBg);
            view.setOutlineProvider(oldProvider);
            view.setClipToOutline(oldClip);
        } catch (Throwable ignored) {
        }
        gd = null;
    }

    void set(double l, double t, double r, double b, double rx) {
        set(l, t, r, b, rx, rx);
    }

    void set(double l, double t, double r, double b, double rx, double ry) {
        if (mode == GD) {
            try {
                double vw = Math.max(1.0, (double) view.getWidth());
                double vh = Math.max(1.0, (double) view.getHeight());
                rx = Math.max(0.0, Math.min(rx, vw / 2.0));
                ry = Math.max(0.0, Math.min(ry, vh / 2.0));
            } catch (Throwable e) {
                rx = Math.max(0.0, rx);
                ry = Math.max(0.0, ry);
            }
            int[] bounds = new int[]{(int) l, (int) t, (int) r, (int) b};
            boolean outlineChanged = false;
            if (lastBounds == null || bounds[0] != lastBounds[0] || bounds[1] != lastBounds[1]
                    || bounds[2] != lastBounds[2] || bounds[3] != lastBounds[3]) {
                gd.setBounds(bounds[0], bounds[1], bounds[2], bounds[3]);
                lastBounds = bounds;
                outlineChanged = true;
            }
            // Uniform corners use the cheap round-rect outline; per-corner radii force a path outline.
            // Radii are bucketed to 1.5 px so the outline is not rebuilt on every frame.
            boolean uniform = Math.abs(rx - ry) < 0.75;
            int[] bucket = new int[]{(int) Math.rint(rx / 1.5), (int) Math.rint(ry / 1.5), uniform ? 1 : 0};
            if (lastRadiusBucket == null || bucket[0] != lastRadiusBucket[0] || bucket[1] != lastRadiusBucket[1]
                    || bucket[2] != lastRadiusBucket[2]) {
                if (uniform) {
                    gd.setCornerRadius((float) ((rx + ry) / 2.0));
                } else {
                    for (int i = 0; i < 8; i += 2) {
                        radii[i] = (float) rx;
                        radii[i + 1] = (float) ry;
                    }
                    gd.setCornerRadii(radii);
                }
                lastRadiusBucket = bucket;
                outlineChanged = true;
            }
            if (outlineChanged) view.invalidateOutline();
        } else if (mode == PROXY) {
            proxy.box[0] = (float) l;
            proxy.box[1] = (float) t;
            proxy.box[2] = (float) r;
            proxy.box[3] = (float) b;
            proxy.box[4] = (float) rx;
            proxy.box[5] = (float) ry;
            view.invalidateOutline();
        } else {
            view.setClipBounds(new Rect((int) l, (int) t, (int) r, (int) b));
        }
    }

    void restore() {
        if (mode == GD) {
            undoGd();
        } else if (mode == PROXY) {
            view.setOutlineProvider(oldProvider);
            view.setClipToOutline(oldClip);
        } else {
            view.setClipBounds(null);
        }
    }
}

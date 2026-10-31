package com.vernyak.chat_expand;

import android.view.View;

/**
 * Frame math. Faithful port (same float32 operation order) of the hand-written smali
 * com.vernyak.chat_expand.NativeMotion from the previous DEX. Only the paths the plugin actually
 * used are kept: apply / applyAnchoredFrame / applyAnchoredBatch / computeMorph / depth.
 */
final class NativeMotion {
    private NativeMotion() {}

    static final int BATCH_CAPACITY = 48;

    static float clamp(float x) {
        if (!(x >= 0.0f)) return 0.0f;      // also maps NaN to 0 (cmpl-float semantics)
        if (x > 1.0f) return 1.0f;
        return x;
    }

    static float smooth(float x) {
        float c = clamp(x);
        float a = c * c;
        float b = c * 2.0f;
        b = 3.0f - b;
        return a * b;
    }

    /** a = {scaleX, scaleY, translationX, translationY, alpha}. */
    static void apply(View v, float[] a) {
        float sx = a[0], sy = a[1], tx = a[2], ty = a[3], al = a[4];
        v.setScaleX(sx);
        v.setScaleY(sy);
        v.setTranslationX(tx);
        v.setTranslationY(ty);
        v.setAlpha(al);
    }

    /**
     * a = {p, alphaP, sx0, sy0, px0, py0, ax, ay, a0, sx1, sy1, px1, py1, a1}
     * (note: p and alphaP are NOT clamped here, exactly like the original).
     */
    static void applyAnchoredFrame(View v, float[] a) {
        float p = a[0], ap = a[1], sx0 = a[2], sy0 = a[3], px0 = a[4], py0 = a[5], ax = a[6], ay = a[7];
        float a0 = a[8], sx1 = a[9], sy1 = a[10], px1 = a[11], py1 = a[12], a1 = a[13];

        float s = p * p;
        float t = p * 2.0f;
        t = 3.0f - t;
        s = s * t;
        float ea = ap * ap;
        float t2 = ap * 2.0f;
        t2 = 3.0f - t2;
        ea = ea * t2;

        float x0 = sx0 * ax;
        x0 = px0 - x0;
        float y0 = sy0 * ay;
        y0 = py0 - y0;
        float x1 = sx1 * ax;
        x1 = px1 - x1;
        float y1 = sy1 * ay;
        y1 = py1 - y1;

        float osx = sx1 - sx0;
        osx = osx * s;
        osx = osx + sx0;
        float osy = sy1 - sy0;
        osy = osy * s;
        osy = osy + sy0;
        float otx = x1 - x0;
        otx = otx * s;
        otx = otx + x0;
        float oty = y1 - y0;
        oty = oty * s;
        oty = oty + y0;
        float oa = a1 - a0;
        oa = oa * ea;
        oa = oa + a0;

        v.setScaleX(osx);
        v.setScaleY(osy);
        v.setTranslationX(otx);
        v.setTranslationY(oty);
        v.setAlpha(oa);
    }

    /** Applies rows until the first null view (or the end). */
    static void applyAnchoredBatch(View[] views, float[][] data) {
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            if (v == null) return;
            applyAnchoredFrame(v, data[i]);
        }
    }

    /**
     * Stretch morph of the chat container. In: 29 packed floats (see Morph.packMorph), out: 18 floats
     * {L, T, R, B, chatAlpha, p, sx, sy, tx, ty, bb, fx0, fy0, fx1, fy1, bl, br, radius}.
     * Also applies scale/translation/alpha to the view, as the original did.
     */
    static void computeMorph(View view, float[] in, float[] out) {
        float u = in[0], l = in[1], t0 = in[2], r = in[3], b = in[4], w = in[5], h = in[6];
        float preS = in[7], prePx = in[8], prePy = in[9], density = in[10];
        float liftEnd = in[11], liftDx = in[12], liftDy = in[13], expandStart = in[14];
        float baseLeft = in[15], baseTop = in[16], off = in[17], w2 = in[18], h2 = in[19];
        float chatEarly = in[20], chatEarlyLen = in[21], fadeStart = in[22], fadeLen = in[23];
        float radius = in[24], radFull = in[25], bump = in[26], closeMul = in[27], reverse = in[28];

        float inv = 1.0f - preS;
        float fx0 = prePx * inv;
        float fy0 = prePy * inv;
        float fx1 = fx0 + preS * w;
        float fy1 = fy0 + preS * h;

        float aRaw = clamp(u / liftEnd);
        float q = 1.0f - aRaw;
        float q3 = q * q;
        q3 = q3 * q;
        float a = 1.0f - q3;
        float dx = liftDx * density;
        dx = dx * a;
        float dy = liftDy * density;
        dy = dy * a;

        float bl = Math.max(l - dx, fx0);
        float bt = Math.max(t0 - dy, fy0);
        float br = Math.min(r + dx, fx1);
        float bb = Math.min(b + dy, fy1);

        float px = u - expandStart;
        float pd = 1.0f - expandStart;
        px = px / pd;
        float p = smooth(px);

        float L = (fx0 - bl) * p + bl;
        float R = (fx1 - br) * p + br;
        float T = (fy0 - bt) * p + bt;
        float B = (fy1 - bb) * p + bb;

        float e1 = smooth(u / chatEarlyLen);
        float e2 = (u - fadeStart) / fadeLen;
        e2 = smooth(e2);
        float chatAlpha = chatEarly * e1 + (1.0f - chatEarly) * e2;

        float sx = (R - L) / w2;
        float sy = (B - T) / h2;
        float tx = L - baseLeft;
        tx = tx + off;
        float ty = T - baseTop;

        view.setScaleX(sx);
        view.setScaleY(sy);
        view.setTranslationX(tx);
        view.setTranslationY(ty);
        view.setAlpha(chatAlpha);

        float pq = 1.0f - p;
        pq = pq * p;
        float shape = 16.0f * pq;
        float den = 4.0f * pq;
        den = 5.0f - den;
        shape = shape / den;
        float rad = radFull - radius;
        rad = rad * p;
        rad = radius + rad;
        shape = shape * bump;
        rad = rad + shape;
        if (reverse != 0.0f) {
            float k = 1.0f - p;
            float m = closeMul - 1.0f;
            m = m * k;
            m = 1.0f + m;
            rad = rad * m;
        }
        float hw = (R - L) / 2.0f;
        float rr = Math.min(rad, hw);
        float hh = (B - T) / 2.0f;
        rad = Math.min(rr, hh);
        rad = Math.max(0.0f, rad);

        out[0] = L;
        out[1] = T;
        out[2] = R;
        out[3] = B;
        out[4] = chatAlpha;
        out[5] = p;
        out[6] = sx;
        out[7] = sy;
        out[8] = tx;
        out[9] = ty;
        out[10] = bb;
        out[11] = fx0;
        out[12] = fy0;
        out[13] = fx1;
        out[14] = fy1;
        out[15] = bl;
        out[16] = br;
        out[17] = rad;
    }

    /**
     * Depth effect: scrim fade + background scale. depth = {u, dim0, dimLen, bgScale, p, backTx, backTy, backAlpha}.
     * Equivalent of the scrim/back tail of the original applySpriteBatch.
     */
    static void applyDepth(View scrim, View back, float[] d) {
        if (scrim != null) {
            float t = d[0] / d[2];
            float s = smooth(t);
            float a = d[1] * s;
            scrim.setScaleX(1.0f);
            scrim.setScaleY(1.0f);
            scrim.setTranslationX(0.0f);
            scrim.setTranslationY(0.0f);
            scrim.setAlpha(a);
        }
        if (back != null) {
            float sc = d[3] * d[4];
            sc = 1.0f - sc;
            back.setScaleX(sc);
            back.setScaleY(sc);
            back.setTranslationX(d[5]);
            back.setTranslationY(d[6]);
            back.setAlpha(d[7]);
        }
    }
}

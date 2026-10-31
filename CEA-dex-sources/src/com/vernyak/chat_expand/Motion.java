package com.vernyak.chat_expand;

import android.view.View;

/**
 * Per-controller frame applier: queues view updates during one animation frame and flushes them in one
 * pass (port of the batch part of the Python _NativeMotion wrapper).
 */
final class Motion {
    private final View[] batchViews = new View[NativeMotion.BATCH_CAPACITY];
    private final float[][] batchData = new float[NativeMotion.BATCH_CAPACITY][14];
    private final float[] applyIn = new float[5];
    private int batchCount;
    private boolean batchActive;

    void beginBatch() {
        batchCount = 0;
        batchActive = true;
        batchViews[0] = null;
    }

    private void queue(View view, float sx, float sy, float tx, float ty, float alpha) {
        if (view == null) return;
        if (!batchActive) throw new IllegalStateException("DEX batch is not active");
        if (batchCount >= batchViews.length) flushBatch(true);
        int i = batchCount;
        float[] row = batchData[i];
        row[0] = 0f;      // p
        row[1] = 0f;      // alpha_p
        row[2] = sx;      // sx0
        row[3] = sy;      // sy0
        row[4] = tx;      // px0
        row[5] = ty;      // py0
        row[6] = 0f;      // ax
        row[7] = 0f;      // ay
        row[8] = alpha;   // a0
        row[9] = sx;      // sx1
        row[10] = sy;     // sy1
        row[11] = tx;     // px1
        row[12] = ty;     // py1
        row[13] = alpha;  // a1
        batchViews[i] = view;
        batchCount = i + 1;
        if (batchCount < batchViews.length) batchViews[batchCount] = null;
    }

    void flushBatch() {
        flushBatch(false);
    }

    private void flushBatch(boolean keepActive) {
        boolean wasActive = batchActive;
        batchActive = false;
        int count = batchCount;
        if (count <= 0) {
            batchCount = 0;
            batchActive = keepActive && wasActive;
            return;
        }
        if (count < batchViews.length) batchViews[count] = null;
        try {
            NativeMotion.applyAnchoredBatch(batchViews, batchData);
        } finally {
            batchCount = 0;
            batchViews[0] = null;
            batchActive = keepActive && wasActive;
        }
    }

    void apply(View view, double sx, double sy, double tx, double ty, double alpha) {
        if (view == null) return;
        if (batchActive) {
            queue(view, (float) sx, (float) sy, (float) tx, (float) ty, (float) alpha);
            return;
        }
        applyIn[0] = (float) sx;
        applyIn[1] = (float) sy;
        applyIn[2] = (float) tx;
        applyIn[3] = (float) ty;
        applyIn[4] = (float) alpha;
        NativeMotion.apply(view, applyIn);
    }
}

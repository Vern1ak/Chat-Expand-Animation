package com.vernyak.chat_expand;

import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;

/** Rounded-rect outline provider used as fallback when the GradientDrawable clip is unavailable. */
final class RectOutline extends ViewOutlineProvider {
    final float[] box = new float[]{0f, 0f, 1f, 1f, 0f, 0f};
    private final Path path = new Path();
    private final RectF rectf = new RectF();
    private final float[] radii = new float[8];

    @Override
    public void getOutline(View view, Outline outline) {
        try {
            float rx = box[4], ry = box[5];
            for (int i = 0; i < 8; i += 2) {
                radii[i] = rx;
                radii[i + 1] = ry;
            }
            rectf.set(box[0], box[1], box[2], box[3]);
            path.reset();
            path.addRoundRect(rectf, radii, Path.Direction.CW);
            try {
                outline.setPath(path);
            } catch (Throwable t) {
                outline.setConvexPath(path);
            }
        } catch (Throwable ignored) {
        }
    }
}

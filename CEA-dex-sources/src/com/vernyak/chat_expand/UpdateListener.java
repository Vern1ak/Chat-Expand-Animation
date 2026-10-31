package com.vernyak.chat_expand;

import android.animation.Animator;
import android.animation.ValueAnimator;

/** Drives a Morph from a ValueAnimator clock (port of _UpdateListener). */
final class UpdateListener implements ValueAnimator.AnimatorUpdateListener {
    final Morph target;
    final double duration;
    double acc, revAcc;
    Double last;
    Double wall0;
    int frames;
    double maxDt;
    int slow;
    int choppy;
    Double prevReal;
    double applySum, applyMax;
    int applyCount;

    UpdateListener(Morph target, int durationMs) {
        this.target = target;
        this.duration = durationMs / 1000.0;
    }

    private void stats(Morph tg) {
        try {
            double wall = wall0 != null ? (U.now() - wall0) : 0.0;
            tg.plugin.log("stats " + tg.kind() + ": frames=" + frames + " maxdt=" + (int) (maxDt * 1000)
                    + "ms slow>45ms=" + slow + " wall=" + String.format("%.2f", wall) + "s applyAvg=" + (applyCount > 0 ? String.format("%.2f", applySum / applyCount * 1000) : "0")
                    + "ms applyMax=" + String.format("%.1f", applyMax * 1000) + "ms hostChildren=" + (tg.host != null ? tg.host.getChildCount() : -1)
                    + " msgTries=" + tg.msgTries + " hdrTries=" + tg.hdrTries
                    + " native=" + Controller.nativeMb() + "MB java=" + Controller.javaMb() + "MB");
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onAnimationUpdate(ValueAnimator animation) {
        try {
            Morph tg = target;
            double now = U.now();
            if (prevReal != null && tg.begun) {
                double d = now - prevReal;
                frames++;
                if (d > maxDt) maxDt = d;
                if (d > 0.045) slow++;
                if (d > 0.034 && ++choppy == 2) tg.boostLayer();
            }
            prevReal = now;
            if (!tg.begun) {
                tg.holdHidden();
                double el = now - tg.waitStart;
                if ((tg.waiting && el < C.WAIT_MAX) || el < tg.minWait) return;
                tg.begin();
                last = null;
            }
            if (tg.ghost) {
                if (tg.ghostRelease || now - tg.waitStart > 1.2) {
                    tg.ghostRelease = true;
                    tg.releaseGhost();
                    animation.cancel();
                }
                return;
            }
            if (tg.unflipped) {
                if (last != null) revAcc += Math.min(now - last, 0.10) * tg.speed;
                last = now;
                double u = tg.ufU + revAcc / (duration / C.CLOSE_FACTOR);
                if (u >= 1.0) {
                    stats(tg);
                    tg.updateU(1.0);
                    tg.finishReopen();
                    animation.cancel();
                } else {
                    long a1 = System.nanoTime();
                    tg.updateU(u);
                    double ad2 = (System.nanoTime() - a1) / 1e9;
                    applySum += ad2;
                    applyCount++;
                    if (ad2 > applyMax) applyMax = ad2;
                }
                return;
            }
            if (wall0 == null) wall0 = now;
            boolean capped = now - wall0 > C.WALL_MAX;
            if (tg.flipped) {
                if (last != null) revAcc += Math.min(now - last, 0.10) * tg.speed;
                last = now;
                double u = tg.flipU - revAcc / (duration * C.CLOSE_FACTOR);
                if (u <= 0.0) {
                    stats(tg);
                    tg.updateU(0.0);
                    tg.finishReversed();
                    animation.cancel();
                } else {
                    long a1 = System.nanoTime();
                    tg.updateU(u);
                    double ad2 = (System.nanoTime() - a1) / 1e9;
                    applySum += ad2;
                    applyCount++;
                    if (ad2 > applyMax) applyMax = ad2;
                }
                return;
            }
            if (last != null) acc += Math.min(now - last, C.MAX_FRAME_DT) * tg.speed;
            last = now;
            double t = acc / duration;
            if (capped) {
                tg.plugin.log("animation watchdog reached; finishing stalled run");
                t = 1.0;
            }
            if (t >= 1.0) {
                stats(tg);
                tg.update(1.0);
                tg.finish();
                animation.cancel();
                return;
            }
            if (tg.predictiveClose) {
                t = 1.0 - Math.pow(1.0 - U.c01(t), C.PREDICTIVE_EASE_POWER);
            }
            long a0 = System.nanoTime();
            tg.update(t);
            double ad = (System.nanoTime() - a0) / 1e9;
            applySum += ad;
            applyCount++;
            if (ad > applyMax) applyMax = ad;
        } catch (Throwable e) {
            target.plugin.log("update failed: " + e);
        }
    }

    /** Finishes the run when the animator ends (port of _EndListener). */
    static final class End implements Animator.AnimatorListener {
        private final Morph target;

        End(Morph target) {
            this.target = target;
        }

        @Override
        public void onAnimationStart(Animator animation) {
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            target.finish();
        }

        @Override
        public void onAnimationCancel(Animator animation) {
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
        }
    }
}

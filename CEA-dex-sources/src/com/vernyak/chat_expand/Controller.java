package com.vernyak.chat_expand;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;
import android.text.Layout;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Hook handlers and animation orchestration (port of the Python ChatExpandPlugin class). */
final class Controller {
    final Motion motion = new Motion();
    final ArrayList<Morph> runs = new ArrayList<Morph>();
    private Object[] pendingBack;           // {kind, run, layout}
    private Double handoffU;
    int programmaticCloseDepth;
    private final java.util.IdentityHashMap<View, int[]> layerUse = new java.util.IdentityHashMap<View, int[]>();

    void acquireLayer(View v) {
        if (v == null) return;
        try {
            int[] e = layerUse.get(v);
            if (e == null) {
                e = new int[]{0, v.getLayerType()};
                layerUse.put(v, e);
                v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
            e[0]++;
        } catch (Throwable t) {
            log("layer acquire failed: " + t);
        }
    }

    void releaseLayer(View v) {
        if (v == null) return;
        try {
            int[] e = layerUse.get(v);
            if (e == null) return;
            if (--e[0] <= 0) {
                layerUse.remove(v);
                v.setLayerType(e[1], null);
            }
        } catch (Throwable t) {
            log("layer release failed: " + t);
        }
    }
    private ArrayList<Bitmap> snapshotPool = new ArrayList<Bitmap>();
    private boolean snapshotPoolEnabled = true;
    private final Handler ui = new Handler(Looper.getMainLooper());

    void log(String msg) {
        L.log(msg);
    }

    void postUi(Runnable r, long delayMs) {
        if (delayMs <= 0) ui.post(r);
        else ui.postDelayed(r, delayMs);
    }

    static int javaMb() {
        try {
            Runtime rt = Runtime.getRuntime();
            return (int) ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        } catch (Throwable t) {
            return -1;
        }
    }

    static int nativeMb() {
        try {
            return (int) (Debug.getNativeHeapAllocatedSize() / (1024 * 1024));
        } catch (Throwable t) {
            return -1;
        }
    }

    // --------------------------------------------------------------- lifecycle

    void onLoad() {
        for (Morph old : new ArrayList<Morph>(runs)) {
            try {
                old.abort();
            } catch (Throwable ignored) {
            }
        }
        runs.clear();
        pendingBack = null;
        handoffU = null;
        programmaticCloseDepth = 0;
        layerUse.clear();
        snapshotPool = new ArrayList<Bitmap>();
        snapshotPoolEnabled = true;
        log("settings at load: " + S.dump());
    }

    void onUnload() {
        snapshotPoolEnabled = false;
        for (Morph run : new ArrayList<Morph>(runs)) {
            try {
                run.abort();
            } catch (Throwable ignored) {
            }
        }
        runs.clear();
        clearSnapshotBitmapPool();
    }

    // ------------------------------------------------------------ snapshot pool

    Bitmap obtainSnapshotBitmap(int width, int height) {
        ArrayList<Bitmap> pool = snapshotPool;
        for (int i = pool.size() - 1; i >= 0; i--) {
            Bitmap bm = pool.remove(i);
            try {
                if (!bm.isRecycled() && bm.getWidth() == width && bm.getHeight() == height
                        && String.valueOf(bm.getConfig()).equals("ARGB_8888")) {
                    bm.eraseColor(Color.TRANSPARENT);
                    log("close snapshot bitmap reused " + width + "x" + height);
                    return bm;
                }
                if (!bm.isRecycled()) bm.recycle();
            } catch (Throwable ignored) {
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    void releaseSnapshotBitmap(Bitmap bitmap) {
        if (bitmap == null) return;
        try {
            if (bitmap.isRecycled()) return;
            long allocationBytes;
            try {
                allocationBytes = bitmap.getAllocationByteCount();
            } catch (Throwable e) {
                allocationBytes = (long) bitmap.getWidth() * bitmap.getHeight() * 4;
            }
            if (snapshotPoolEnabled && snapshotPool.size() < 1 && allocationBytes <= 16L * 1024 * 1024) {
                snapshotPool.add(bitmap);
                log("close snapshot bitmap pooled " + bitmap.getWidth() + "x" + bitmap.getHeight() + " ("
                        + (allocationBytes / (1024 * 1024)) + " MiB cap=16 MiB)");
                return;
            }
            bitmap.recycle();
        } catch (Throwable e) {
            try {
                bitmap.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    private void clearSnapshotBitmapPool() {
        ArrayList<Bitmap> pool = snapshotPool;
        snapshotPool = new ArrayList<Bitmap>();
        for (Bitmap b : pool) {
            try {
                if (b != null && !b.isRecycled()) b.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    String statusLine() {
        return "snapshot scale: " + String.format("%.2f", C.SNAPSHOT_SCALE) + " | snapshot pool: " + snapshotPool.size()
                + " | native heap: " + nativeMb() + " MB | java heap: " + javaMb() + " MB | active runs: " + runs.size()
                + " | host children: " + hostChildren();
    }

    // ------------------------------------------------------------- view helpers

    /** Offset of v inside container (null if not found within 16 levels). */
    static double[] offs(View v, View container) {
        double x = 0.0, y = 0.0;
        View cur = v;
        for (int i = 0; i < 16; i++) {
            x += (double) cur.getLeft() + (double) cur.getTranslationX();
            y += (double) cur.getTop() + (double) cur.getTranslationY();
            ViewParent par = cur.getParent();
            if (par == null) return null;
            if (!(par instanceof View)) throw new IllegalStateException("parent is not a View: " + par);
            View pv = (View) par;
            x -= (double) pv.getScrollX();
            y -= (double) pv.getScrollY();
            if (pv == container || pv.equals(container)) return new double[]{x, y};
            cur = pv;
        }
        return null;
    }

    List<Object[]> settleCells(Object fragment) {
        ArrayList<Object[]> saved = new ArrayList<Object[]>();
        try {
            Object lvo = R.get(fragment, "chatListView");
            if (!(lvo instanceof ViewGroup)) return saved;
            ViewGroup lv = (ViewGroup) lvo;
            Class<?> cell = R.cls("org.telegram.ui.Cells.ChatMessageCell");
            if (cell == null) return saved;
            for (int i = 0; i < lv.getChildCount(); i++) {
                View v = lv.getChildAt(i);
                if (!cell.isInstance(v)) continue;
                Double ao = null;
                try {
                    ao = R.num(R.call(v, "getAnimationOffsetX"), 0.0);
                } catch (Throwable ignored) {
                }
                double a = v.getAlpha(), sx = v.getScaleX(), sy = v.getScaleY();
                if (a >= 0.999 && sx == 1.0 && sy == 1.0 && (ao == null || ao == 0.0)) continue;
                saved.add(new Object[]{v, a, sx, sy, ao});
                v.setAlpha(1.0f);
                v.setScaleX(1.0f);
                v.setScaleY(1.0f);
                if (ao != null) R.tryCall(v, "setAnimationOffsetX", 0.0f);
            }
        } catch (Throwable e) {
            L.emit("settle cells failed: " + e);
        }
        if (!saved.isEmpty()) {
            L.emit("snapshot: settled " + saved.size() + " animating message cells (message_motion?)");
        }
        return saved;
    }

    void unsettleCells(List<Object[]> saved) {
        for (Object[] s : saved) {
            try {
                View v = (View) s[0];
                v.setAlpha(((Double) s[1]).floatValue());
                v.setScaleX(((Double) s[2]).floatValue());
                v.setScaleY(((Double) s[3]).floatValue());
                if (s[4] != null) R.tryCall(v, "setAnimationOffsetX", ((Double) s[4]).floatValue());
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------- message target

    static boolean isActionMessage(Object mo) {
        try {
            Object owner = R.get(mo, "messageOwner");
            Object act = owner != null ? R.get(owner, "action") : null;
            return act != null && !act.getClass().getSimpleName().contains("Empty");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Target for service messages ("X left", "pinned a message"): the ChatActionCell with the same id. */
    private Morph.Target actionTarget(ViewGroup lv, View container, List<Long> sourceIds, Long sourceId, int viewCount,
                                      double cw, double ch, double hostOffX, double hostOffY, String sourceText) {
        Class<?> actionClass = R.cls("org.telegram.ui.Cells.ChatActionCell");
        String needle = Morph.firstToken(sourceText);
        Morph.Target best = null;
        double bestBottom = Double.NEGATIVE_INFINITY;
        for (int i = 0; actionClass != null && i < viewCount; i++) {
            View v = lv.getChildAt(i);
            if (!actionClass.isInstance(v)) continue;
            try {
                if (v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0.05f || v.getWidth() <= 0 || v.getHeight() <= 0) continue;
                Object mo = R.get(v, "currentMessageObject");
                if (mo == null) mo = R.tryCall(v, "getMessageObject");
                Long id = messageId(mo);
                if (id == null || !sourceIds.contains(id)) continue;
                Object lo = R.get(v, "textLayout");
                if (!(lo instanceof Layout)) continue;
                Layout tl = (Layout) lo;
                if (tl.getLineCount() <= 0) continue;
                if (needle != null && !tl.getText().toString().contains(needle)) continue;
                Object tyo = R.get(v, "textY");
                if (!(tyo instanceof Number)) continue;
                double[] pos = offs(v, container);
                if (pos == null) continue;
                double leafTx = v.getTranslationX();
                if (U.finite(leafTx) && Math.abs(leafTx) > 0.01) pos = new double[]{pos[0] - leafTx, pos[1]};
                double textLeft = (v.getWidth() - tl.getWidth()) / 2.0;
                double x = pos[0] + textLeft + tl.getLineLeft(0);
                double y = pos[1] + ((Number) tyo).doubleValue();
                double baseline = tl.getLineBaseline(0);
                double lineH = Math.max(1.0, tl.getLineBottom(0) - tl.getLineTop(0));
                double fontSize = tl.getPaint().getTextSize();
                if (!(U.finite(x) && U.finite(y) && U.finite(baseline) && U.finite(fontSize))) continue;
                if (cw > 0.0 && (x < -cw * 0.25 || x > cw * 1.25)) continue;
                if (ch > 0.0 && (y + lineH < -ch * 0.2 || y > ch * 1.2)) continue;
                Morph.Target t = new Morph.Target();
                t.pos = new double[]{x + hostOffX, y + hostOffY};
                t.height = lineH;
                t.bottom = y + baseline + hostOffY;
                t.fontSize = Math.max(0.0, fontSize);
                t.messageId = id;
                t.sourceMessageId = sourceId;
                t.matchedPreview = true;
                t.waitingForExact = false;
                if (y + baseline > bestBottom) {
                    best = t;
                    bestBottom = y + baseline;
                }
            } catch (Throwable e) {
                // try the next cell
            }
        }
        if (best != null) {
            L.emit("service message target found: pos=" + best.pos[0] + "," + best.pos[1]);
            return best;
        }
        Morph.Target none = new Morph.Target();
        none.sourceMessageId = sourceId;
        none.matchedPreview = false;
        none.waitingForExact = !sourceIds.isEmpty();
        return none;
    }

    private static Long messageId(Object obj) {
        if (obj == null) return null;
        ArrayList<Object> owners = new ArrayList<Object>();
        owners.add(obj);
        try {
            Object owner = R.get(obj, "messageOwner");
            if (owner != null && owner != obj) owners.add(owner);
        } catch (Throwable ignored) {
        }
        for (Object candidate : owners) {
            try {
                Object value = R.tryCall(candidate, "getId");
                if (value instanceof Number && ((Number) value).longValue() != 0L) return ((Number) value).longValue();
            } catch (Throwable ignored) {
            }
            for (String fn : new String[]{"id", "messageId", "localId"}) {
                try {
                    Object value = R.get(candidate, fn);
                    if (value instanceof Number && ((Number) value).longValue() != 0L) return ((Number) value).longValue();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static void appendId(List<Long> values, Object value) {
        try {
            if (!(value instanceof Number)) return;
            long v = ((Number) value).longValue();
            if (v != 0 && !values.contains(v)) values.add(v);
        } catch (Throwable ignored) {
        }
    }

    /** Returns {Layout, xOffset(Double), yOffset(Double)}; layout is null when nothing usable was found. */
    private Object[] resolveTextLayout(View view, Object messageObject) {
        Object legacy = R.get(view, "textLayout");
        if (legacy instanceof Layout) {
            try {
                if (((Layout) legacy).getLineCount() > 0) return new Object[]{legacy, 0.0, 0.0};
            } catch (Throwable ignored) {
            }
        }
        Object blocksO = R.get(messageObject, "textLayoutBlocks");
        if (!(blocksO instanceof List)) return new Object[]{null, 0.0, 0.0};
        List<?> blocks = (List<?>) blocksO;
        int count = Math.min(128, blocks.size());
        Object transitionParams = R.get(view, "transitionParams");
        double textXOffset = R.num(R.get(messageObject, "textXOffset"), 0.0);

        for (int blockIndex = 0; blockIndex < count; blockIndex++) {
            try {
                Object block = blocks.get(blockIndex);
                Object layoutO = R.get(block, "textLayout");
                if (!(layoutO instanceof Layout) || ((Layout) layoutO).getLineCount() <= 0) continue;

                Object yo = null;
                if (transitionParams != null) yo = R.tryCall(block, "textYOffset", blocks, transitionParams);
                if (yo == null) yo = R.tryCall(block, "textYOffset", blocks);
                double yOffset;
                if (yo instanceof Number) {
                    yOffset = ((Number) yo).doubleValue();
                } else {
                    yOffset = 0.0;
                    for (int pi = 0; pi < blockIndex; pi++) {
                        Object previous = blocks.get(pi);
                        yOffset += R.num(R.get(previous, "height"), 0.0) + R.num(R.get(previous, "padTop"), 0.0)
                                + R.num(R.get(previous, "padBottom"), 0.0);
                    }
                }
                double padTop = R.num(R.get(block, "padTop"), 0.0);
                return new Object[]{layoutO, textXOffset, yOffset + padTop};
            } catch (Throwable e) {
                // try the next block
            }
        }
        return new Object[]{null, 0.0, 0.0};
    }

    Morph.Target findMsgTarget(Object fragment, View container, View sourceCell, boolean allowFallback,
                               double[] finalTranslation) {
        try {
            Object lvo = R.get(fragment, "chatListView");
            if (!(lvo instanceof ViewGroup) || container == null) {
                Morph.Target t = new Morph.Target();
                t.waitingForExact = false;
                return t;
            }
            ViewGroup lv = (ViewGroup) lvo;

            ArrayList<Long> sourceIds = new ArrayList<Long>();
            Object sourceObj = null;
            if (sourceCell != null) {
                for (String fn : new String[]{"message", "messageObject", "lastMessageObject"}) {
                    try {
                        Object candidate = R.get(sourceCell, fn);
                        if (candidate == null) continue;
                        Object candidateType = R.get(candidate, "type");
                        Long candidateId = messageId(candidate);
                        if (candidateId != null) {
                            sourceObj = candidate;
                            appendId(sourceIds, candidateId);
                            if (candidateType == null || R.intv(candidateType, -1) == 0) break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    appendId(sourceIds, R.get(sourceCell, "messageId"));
                } catch (Throwable ignored) {
                }
                try {
                    Object grouped = R.get(sourceCell, "groupMessages");
                    if (grouped instanceof List) {
                        List<?> g = (List<?>) grouped;
                        int count = Math.min(50, g.size());
                        for (int gi = 0; gi < count; gi++) {
                            try {
                                appendId(sourceIds, messageId(g.get(gi)));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            Long sourceId = sourceObj != null ? messageId(sourceObj) : null;
            if (sourceId == null && !sourceIds.isEmpty()) sourceId = sourceIds.get(0);

            Class<?> cellClass = R.cls("org.telegram.ui.Cells.ChatMessageCell");

            Morph.Target best = null, bestExact = null;
            double bestBottom = Double.NEGATIVE_INFINITY, exactBottom = Double.NEGATIVE_INFINITY;
            int viewCount;
            try {
                viewCount = Math.min(lv.getChildCount(), C.MAX_VIEWS);
            } catch (Throwable e) {
                viewCount = 0;
            }

            double cw, ch;
            try {
                cw = container.getWidth();
                ch = container.getHeight();
            } catch (Throwable e) {
                cw = ch = 0.0;
            }

            double hostOffX = 0.0, hostOffY = 0.0;
            try {
                ViewParent hostP = container.getParent();
                if (hostP instanceof View) {
                    View host = (View) hostP;
                    double transX, transY;
                    if (finalTranslation == null) {
                        transX = container.getTranslationX();
                        transY = container.getTranslationY();
                    } else {
                        transX = finalTranslation[0];
                        transY = finalTranslation[1];
                    }
                    hostOffX = (double) container.getLeft() + transX - (double) host.getScrollX();
                    hostOffY = (double) container.getTop() + transY - (double) host.getScrollY();
                }
            } catch (Throwable ignored) {
            }

            if (sourceObj != null && isActionMessage(sourceObj)) {
                Object smt = R.get(sourceObj, "messageText");
                return actionTarget(lv, container, sourceIds, sourceId, viewCount, cw, ch, hostOffX, hostOffY,
                        smt != null ? smt.toString() : null);
            }

            for (int i = 0; i < viewCount; i++) {
                View v = lv.getChildAt(i);
                if (cellClass == null || !cellClass.isInstance(v)) continue;
                try {
                    if (v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0.05f || v.getWidth() <= 0 || v.getHeight() <= 0) {
                        continue;
                    }
                } catch (Throwable e) {
                    continue;
                }
                Object mo;
                try {
                    mo = R.call(v, "getPrimaryMessageObject");
                    if (mo == null || R.intv(R.get(mo, "type"), -1) != 0) continue;
                } catch (Throwable e) {
                    continue;
                }
                Object[] resolved = resolveTextLayout(v, mo);
                Layout tl = (Layout) resolved[0];
                double blockXOffset = (Double) resolved[1];
                double blockYOffset = (Double) resolved[2];
                try {
                    if (tl == null || tl.getLineCount() <= 0) continue;
                } catch (Throwable e) {
                    continue;
                }
                Long candidateId = messageId(mo);
                boolean exact = candidateId != null && sourceIds.contains(candidateId);

                double[] pos = offs(v, container);
                if (pos == null) continue;
                try {
                    double leafTx = v.getTranslationX();
                    if (U.finite(leafTx) && Math.abs(leafTx) > 0.01) pos = new double[]{pos[0] - leafTx, pos[1]};
                } catch (Throwable ignored) {
                }
                double x, xr, y, targetBottom, lineH, fontSize, lineTop, lineBottom;
                try {
                    Object tx = R.get(v, "textX");
                    if (tx == null) tx = R.call(v, "getTextX");
                    Object ty = R.get(v, "textY");
                    if (ty == null) ty = R.call(v, "getTextY");
                    double textX = R.d(tx), textY = R.d(ty);
                    double lineLeft = tl.getLineLeft(0);
                    double lineRight = tl.getLineRight(0);
                    lineTop = tl.getLineTop(0);
                    lineBottom = tl.getLineBottom(0);
                    double baseline = tl.getLineBaseline(0);
                    fontSize = tl.getPaint().getTextSize();
                    x = pos[0] + textX + blockXOffset + lineLeft;
                    xr = pos[0] + textX + blockXOffset + lineRight;
                    y = pos[1] + textY + blockYOffset;
                    targetBottom = y + baseline;
                    lineH = Math.max(1.0, lineBottom - lineTop);
                    if (!(U.finite(x) && U.finite(xr) && U.finite(y) && U.finite(targetBottom) && U.finite(lineH)
                            && U.finite(fontSize) && U.finite(cw) && U.finite(ch))) continue;
                    if (Math.abs(x) < 0.5 && Math.abs(y) < 0.5) continue;
                    if (cw > 0.0 && (x < -cw * 0.25 || x > cw * 1.25 || xr < -cw * 0.25 || xr > cw * 1.25)) continue;
                    if (ch > 0.0 && (y + lineBottom < -ch * 0.20 || y + lineTop > ch * 1.20)) continue;
                    if (cw > 0.0 && ch > 0.0) {
                        if (Math.max(x, xr) < 0.0 || Math.min(x, xr) > cw || y + lineBottom < 0.0 || y + lineTop > ch) continue;
                    }
                } catch (Throwable e) {
                    continue;
                }

                Morph.Target candidate = new Morph.Target();
                candidate.pos = new double[]{x + hostOffX, y + hostOffY};
                candidate.height = lineH;
                candidate.bottom = targetBottom + hostOffY;
                candidate.fontSize = Math.max(0.0, fontSize);
                candidate.messageId = candidateId;
                candidate.sourceMessageId = sourceId;
                candidate.matchedPreview = exact;
                candidate.waitingForExact = !sourceIds.isEmpty() && !exact;
                double score = targetBottom;
                if (score > bestBottom) {
                    best = candidate;
                    bestBottom = score;
                }
                if (exact && score > exactBottom) {
                    bestExact = candidate;
                    exactBottom = score;
                }
            }

            if (bestExact != null) {
                bestExact.waitingForExact = false;
                return bestExact;
            }
            if (best != null) {
                best.waitingForExact = !sourceIds.isEmpty();
                return best;
            }
            Morph.Target none = new Morph.Target();
            none.sourceMessageId = sourceId;
            none.matchedPreview = false;
            none.waitingForExact = !sourceIds.isEmpty();
            return none;
        } catch (Throwable e) {
            L.emit("msg target failed: " + e);
            Morph.Target t = new Morph.Target();
            t.matchedPreview = false;
            t.waitingForExact = true;
            return t;
        }
    }

    /** First column (from startX) that contains visible pixels, or -1. */
    static double firstVisibleColumn(Bitmap bm, int startX) {
        try {
            int w = bm.getWidth(), h = bm.getHeight();
            if (w <= 0 || h <= 0 || startX >= w) return -1.0;
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            for (int x = Math.max(0, startX); x < w; x++) {
                for (int y = 0; y < h; y++) {
                    if (((px[y * w + x] >>> 24) & 255) > 40) return x;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1.0;
    }

    Morph.Header captureHeader(Object fragment, View container, boolean hide) {
        Object av, tv;
        try {
            Object ac = R.get(fragment, "avatarContainer");
            av = R.get(ac, "avatarImageView");
            tv = R.call(ac, "getTitleTextView");
        } catch (Throwable e) {
            L.emit("header views failed: " + e);
            return null;
        }
        Morph.Header out = new Morph.Header();
        ArrayList<View> views = new ArrayList<View>();
        ArrayList<Bitmap> bitmaps = new ArrayList<Bitmap>();
        try {
            if (av instanceof View && ((View) av).getWidth() > 0 && ((View) av).getHeight() > 0) {
                View a = (View) av;
                double[] pos = offs(a, container);
                if (pos != null) {
                    int w = a.getWidth(), h = a.getHeight();
                    Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bitmaps.add(bm);
                    a.draw(new Canvas(bm));
                    Morph.HeaderPart part = new Morph.HeaderPart();
                    part.bm = bm;
                    part.bw = w;
                    part.bh = h;
                    part.anc = new double[]{w / 2.0, h / 2.0};
                    part.ref = (double) w;
                    part.pos = new double[]{pos[0] + w / 2.0, pos[1] + h / 2.0};
                    out.avatar = part;
                    views.add(a);
                }
            }
            if (tv instanceof View && ((View) tv).getWidth() > 0 && ((View) tv).getHeight() > 0) {
                View t = (View) tv;
                double[] pos = offs(t, container);
                if (pos != null) {
                    int w = t.getWidth(), h = t.getHeight();
                    double th = R.num(R.tryCall(t, "getTextHeight"), 0.0);
                    if (th < 4.0) th = h * 0.7;
                    double tsx;
                    try {
                        tsx = R.d(R.call(t, "getTextStartX"));
                    } catch (Throwable e) {
                        tsx = 0.0;
                    }
                    Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bitmaps.add(bm);
                    t.draw(new Canvas(bm));
                    double density = t.getResources().getDisplayMetrics().density;
                    double vx = firstVisibleColumn(bm, (int) Math.max(0, Math.floor(tsx)));
                    log("name header: textStartX=" + tsx + " firstVisibleX=" + vx + " width=" + w);
                    if (vx >= 0.0 && vx - tsx >= 3.0 * density && vx - tsx < w * 0.5) {
                        log("header name starts with blank/invisible chars: anchor " + tsx + " -> " + vx);
                        tsx = vx;
                    }
                    Morph.HeaderPart part = new Morph.HeaderPart();
                    part.bm = bm;
                    part.bw = w;
                    part.bh = h;
                    part.anc = new double[]{tsx, h / 2.0};
                    part.ref = th;
                    part.pos = new double[]{pos[0] + tsx, pos[1] + h / 2.0};
                    out.name = part;
                    views.add(t);
                }
            }
        } catch (Throwable e) {
            L.emit("header capture failed: " + e);
            for (Bitmap bm : bitmaps) {
                try {
                    bm.recycle();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
        if (out.avatar == null && out.name == null) {
            for (Bitmap bm : bitmaps) {
                try {
                    bm.recycle();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
        for (View v : views) {
            try {
                out.alpha.add(v.getAlpha());
                if (hide) v.setAlpha(0.0f);
            } catch (Throwable e) {
                out.alpha.add(1.0f);
            }
        }
        out.views.addAll(views);
        out.bitmaps.addAll(bitmaps);
        return out;
    }

    // ------------------------------------------------------------ run registry

    private static boolean sameObject(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        try {
            return a.equals(b);
        } catch (Throwable t) {
            return false;
        }
    }

    Morph findOpeningRun(Object fragment) {
        for (int i = runs.size() - 1; i >= 0; i--) {
            Morph run = runs.get(i);
            if (run.done || !run.live || run.reverse || run.ghost) continue;
            if (sameObject(run.fragment, fragment)) return run;
        }
        return null;
    }

    boolean ownsViewOfOtherRun(Morph self, View v) {
        for (Morph run : runs) {
            if (run != self && run.ownsView(v)) return true;
        }
        return false;
    }

    int hostChildren() {
        for (int i = runs.size() - 1; i >= 0; i--) {
            try {
                ViewGroup h = runs.get(i).host;
                if (h != null) return h.getChildCount();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    void forget(Morph run) {
        runs.remove(run);
    }

    void raiseAll() {
        for (Morph run : new ArrayList<Morph>(runs)) {
            try {
                if (!run.live || run.chatIsOverlay) run.raiseOverlays(true);
            } catch (Throwable e) {
                log("raise failed: " + e);
            }
        }
    }

    void releaseGhosts(long dialogId) {
        for (Morph run : new ArrayList<Morph>(runs)) {
            if (run.ghost && run.dialogId == dialogId && !run.done) {
                run.ghostRelease = true;
                run.releaseGhost();
                try {
                    if (run.va != null) run.va.cancel();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    void touchTransition(Object fragment) {
        try {
            Object layout = R.call(fragment, "getParentLayout");
            if (layout != null) R.setLong(layout, "transitionAnimationStartTime", System.currentTimeMillis());
        } catch (Throwable e) {
            L.emit("touch transition failed: " + e);
        }
    }

    // --------------------------------------------------------------- slide emul

    /** Returns {fragment, Method, p0(Double), lite(Boolean), amp(Double)} or null. */
    Object[] slideTarget(Object prev) {
        try {
            Object frag = prev;
            Object vis = R.tryCall(prev, "getCurrentVisibleFragment");
            if (vis != null) frag = vis;
            Object p0 = R.get(frag, "slideFragmentProgress");
            if (!(p0 instanceof Number) || ((Number) p0).doubleValue() >= 0.999) return null;
            Class<?> c = frag.getClass();
            while (c != null) {
                try {
                    Method m = c.getDeclaredMethod("setSlideTransitionProgress", float.class);
                    m.setAccessible(true);
                    Object lite = R.get(frag, "slideFragmentLite");
                    Object amp = R.get(frag, "slideAmplitudeDp");
                    double a = amp instanceof Number ? ((Number) amp).doubleValue() : 0.0;
                    return new Object[]{frag, m, ((Number) p0).doubleValue(),
                            lite == null ? Boolean.TRUE : Boolean.valueOf(R.bool(lite, true)),
                            a != 0.0 ? a : 40.0};
                } catch (NoSuchMethodException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable e) {
            L.emit("slide target failed: " + e);
        }
        return null;
    }

    // ------------------------------------------------------------ back handling

    private static Object lastFragment(Object layout) throws Exception {
        Object stack = R.call(layout, "getFragmentStack");
        if (stack instanceof List) {
            List<?> l = (List<?>) stack;
            if (!l.isEmpty()) return l.get(l.size() - 1);
        }
        return null;
    }

    Morph handoff(HookParam param) {
        Object target = null;
        Object obj = param != null ? param.thisObject : null;
        if (obj != null) {
            try {
                target = lastFragment(obj);
            } catch (Throwable ignored) {
            }
            if (target == null) {
                try {
                    Object layout = R.call(obj, "getParentLayout");
                    Object cand = layout != null ? lastFragment(layout) : null;
                    target = cand != null ? cand : null;
                } catch (Throwable e) {
                    target = obj;
                }
            }
        }
        Morph run = target != null ? findOpeningRun(target) : null;
        if (run == null && target == null) {
            for (int i = runs.size() - 1; i >= 0; i--) {
                Morph c = runs.get(i);
                if (c.live && !c.reverse && !c.done && !c.ghost) {
                    run = c;
                    break;
                }
            }
        }
        if (run == null) return null;
        if (!run.flipped) run.flip();
        return run;
    }

    private Object[] lazyEnd;
    private long lazyEndAt;

    private void endNativeBackNow(String why) {
        Object[] le = lazyEnd;
        lazyEnd = null;
        if (le == null) return;
        try {
            if (R.bool(R.call(le[0], "isRunning"), false)) {
                R.call(le[0], "end");
                log("predictive back: native backAnimator ended (" + why + ")");
            }
        } catch (Throwable e) {
            log("predictive back: could not end native backAnimator: " + e);
        }
    }

    private void backEndBefore(HookParam param) throws Exception {
        pendingBack = null;
        lazyEnd = null;
        if (R.bool(param.args[0], false)) return;
        if (!S.bool("enabled", true) || !S.bool("animate_close", true)) return;
        Object layout = param.thisObject;
        Object stack = R.call(layout, "getFragmentStack");
        if (!(stack instanceof List)) return;
        List<?> st = (List<?>) stack;
        int n = st.size();
        if (n < 2) return;
        Object top = st.get(n - 1);
        Morph opening = findOpeningRun(top);
        if (opening != null) {
            if (!opening.flipped) opening.flip();
            pendingBack = new Object[]{"reverse", opening, layout};
            log("predictive back reuses opening animation at u=" + opening.curU + "; native background restoration kept");
            return;
        }
        Morph run = start(null, false, top, true);
        if (run != null) pendingBack = new Object[]{"close", run, layout};
    }

    private void backEndAfter(HookParam param) {
        Object[] pending = pendingBack;
        pendingBack = null;
        if (pending == null) return;
        String kind = (String) pending[0];
        Morph run = (Morph) pending[1];
        Object layout = pending[2];
        if ("reverse".equals(kind)) {
            raiseAll();
            return;
        }
        Object anim = R.get(layout, "backAnimator");
        if (anim != null && S.bool("parallel_close", true)) {
            if (Hooks.checkHooked) {
                run.slideEmul = false;
                run.slideArmed = false;
                run.slideM = null;
                run.slideObj = null;
                run.backC = null;
                lazyEnd = new Object[]{anim};
                lazyEndAt = System.currentTimeMillis();
                log("predictive back: native background return kept; it is ended only if another chat is opened meanwhile");
                raiseAll();
                return;
            }
            try {
                R.call(anim, "end");
                log("predictive back: native backAnimator ended so another chat can be opened during the overlay");
            } catch (Throwable e) {
                log("predictive back: could not end native backAnimator: " + e);
            }
            try {
                run.rearmSlide();
            } finally {
                raiseAll();
            }
            return;
        }
        if (anim != null) {
            run.slideEmul = false;
            run.slideArmed = false;
            run.slideM = null;
            run.slideObj = null;
            run.backC = null;
            log("predictive background: native backAnimator left running (no forced end)");
            raiseAll();
            return;
        }
        log("backAnimator unavailable after gesture; using frame-driven background return");
        try {
            run.rearmSlide();
        } finally {
            raiseAll();
        }
    }

    /** Entry point for the Python hook shim. kind: open | resume | back | backend. */
    void onHook(String kind, boolean before, Object rawParam) {
        HookParam param = new HookParam(rawParam);
        if ("open".equals(kind)) {
            if (before) return;
            if (programmaticCloseDepth > 0) return;
            if (param.getResult() != null) return;
            start(param, R.bool(param.args.length > 0 ? param.args[0] : null, false), null, false);
        } else if ("resume".equals(kind)) {
            if (before) return;
            try {
                for (Morph run : new ArrayList<Morph>(runs)) {
                    if (run.waiting) run.waiting = false;
                }
            } catch (Throwable ignored) {
            }
        } else if ("back".equals(kind)) {
            if (before) {
                try {
                    if (programmaticCloseDepth > 0) return;
                    Morph run = handoff(param);
                    if (run != null) {
                        String rt = param.returnTypeName();
                        if ("boolean".equals(rt)) param.setResult(Boolean.FALSE);
                        else param.setResult(null);
                        log("back intercepted; reversing opening at u=" + String.format("%.3f", run.flipU) + " return=" + rt);
                    }
                } catch (Throwable e) {
                    log("handoff failed: " + e);
                }
            } else {
                handoffU = null;
            }
        } else if ("check".equals(kind)) {
            if (before && lazyEnd != null && System.currentTimeMillis() - lazyEndAt > 120L) {
                endNativeBackNow("another chat is being opened during the overlay");
            }
        } else if ("backend".equals(kind)) {
            try {
                if (before) backEndBefore(param);
                else backEndAfter(param);
            } catch (Throwable e) {
                log("gesture close failed (" + (before ? "before" : "after") + "): " + e);
            }
        }
    }

    // ------------------------------------------------------------------- start

    private static long bundleId(android.os.Bundle args, String key) {
        long v = args.getLong(key, 0L);
        if (v == 0L) v = args.getInt(key, 0);
        return v;
    }

    long dialogId(Object fragment) {
        try {
            Object ao = R.call(fragment, "getArguments");
            if (!(ao instanceof android.os.Bundle)) return 0L;
            android.os.Bundle args = (android.os.Bundle) ao;
            long uid = bundleId(args, "user_id");
            if (uid != 0) return uid;
            long cid = bundleId(args, "chat_id");
            if (cid != 0) return -cid;
            int enc = args.getInt("enc_id", 0);
            if (enc != 0) {
                Class<?> dop = R.cls("org.telegram.messenger.DialogObject");
                if (dop != null) {
                    Object r = R.callStatic(dop, "makeEncryptedDialogId", (long) enc);
                    if (r instanceof Number) return ((Number) r).longValue();
                }
            }
        } catch (Throwable e) {
            log("dialog id failed: " + e);
        }
        return 0L;
    }

    View findCell(View root, long dialogId, int xMin, int xMax) {
        Class<?> dc = R.cls("org.telegram.ui.Cells.DialogCell");
        if (dc == null) {
            log("DialogCell import failed");
            return null;
        }
        int[] loc = new int[2];
        ArrayList<View> stack = new ArrayList<View>();
        stack.add(root);
        int seen = 0;
        while (!stack.isEmpty() && seen < C.MAX_VIEWS) {
            View v = stack.remove(stack.size() - 1);
            seen++;
            if (dc.isInstance(v)) {
                try {
                    Object id = R.call(v, "getDialogId");
                    if (id instanceof Number && ((Number) id).longValue() == dialogId && v.isShown()
                            && v.getWidth() > 0 && v.getHeight() > 0) {
                        v.getLocationInWindow(loc);
                        int cx = loc[0] + v.getWidth() / 2;
                        if (xMin <= cx && cx < xMax) return v;
                    }
                } catch (Throwable ignored) {
                }
                continue;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
            }
        }
        return null;
    }

    private void startReopen(HookParam param, Object fragment, View container, ViewGroup host, Runnable callback,
                             long dialogId, Morph old) throws Exception {
        int w = container.getWidth(), h = container.getHeight();
        boolean need = R.bool(R.call(fragment, "needDelayOpenAnimation"), false);
        Morph ghost = new Morph(this, true, container, host, new double[]{0.0, 0.0, 1.0, 1.0}, w, h, 0.0, 1.0, 0.0, 0.0,
                false, null, dialogId, true, callback, fragment, need, 1.0, false);
        ghost.ghost = true;
        ghost.chat = container;
        ghost.begun = false;
        container.setAlpha(0.0f);
        ValueAnimator va = ValueAnimator.ofFloat(0.0f, 1.0f);
        va.setDuration(3000);
        va.setInterpolator(new LinearInterpolator());
        va.addUpdateListener(new UpdateListener(ghost, 1000));
        ghost.va = va;
        AnimatorSet aset = new AnimatorSet();
        aset.play(va);
        aset.addListener(new UpdateListener.End(ghost));
        runs.add(ghost);
        param.setResult(aset);
        aset.start();
        old.unflip();
    }

    Morph start(HookParam param, boolean opening, Object fragment, boolean predictive) {
        if (!S.bool("enabled", true)) return null;
        if (!opening && !S.bool("animate_close", true)) return null;
        if (fragment == null && param != null) fragment = param.thisObject;
        if (fragment == null) return null;
        Runnable callback = null;
        if (param != null && param.args.length > 1 && param.args[1] instanceof Runnable) {
            callback = (Runnable) param.args[1];
        }
        try {
            Class<?> chatActivity = R.cls("org.telegram.ui.ChatActivity");
            if (chatActivity != null && !chatActivity.isInstance(fragment)) return null;

            Object layout = R.call(fragment, "getParentLayout");
            if (layout == null) return null;
            Object stackO = R.call(layout, "getFragmentStack");
            if (!(stackO instanceof List)) return null;
            List<?> stack = (List<?>) stackO;
            int n = stack.size();
            if (n < 2) return null;
            Object prev = stack.get(n - 2);

            Object chatViewO = R.call(fragment, "getFragmentView");
            Object prevViewO = R.call(prev, "getFragmentView");
            if (!(chatViewO instanceof View) || !(prevViewO instanceof View)) return null;
            View chatView = (View) chatViewO;
            View prevView = (View) prevViewO;
            ViewParent cp = chatView.getParent();
            if (!(cp instanceof View)) return null;
            View container = (View) cp;
            ViewParent hp = container.getParent();
            if (!(hp instanceof ViewGroup)) return null;
            ViewGroup host = (ViewGroup) hp;

            long dialogId = dialogId(fragment);
            if (dialogId == 0L) return null;

            if (opening) {
                raiseAll();
                Morph reopen = null;
                for (Morph old : new ArrayList<Morph>(runs)) {
                    if (old.live || old.done) continue;
                    if (old.dialogId == dialogId) {
                        reopen = old;
                    } else if (old.flipped || old.reverse) {
                        log("keeping closing overlay dlg=" + old.dialogId + " while opening dlg=" + dialogId);
                    } else {
                        log("purging stale overlay dlg=" + old.dialogId + " before opening dlg=" + dialogId);
                        try {
                            old.finish();
                        } catch (Throwable e) {
                            log("stale overlay cleanup failed: " + e);
                            try {
                                old.abort();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                if (reopen != null) {
                    startReopen(param, fragment, container, host, callback, dialogId, reopen);
                    return null;
                }
            }

            int[] loc = new int[2];
            host.getLocationInWindow(loc);
            int ox = loc[0], oy = loc[1];
            int w = container.getWidth(), h = container.getHeight();
            if (w <= 0 || h <= 0) return null;

            View cell = findCell(prevView, dialogId, ox, ox + w);
            if (cell == null) return null;

            cell.getLocationInWindow(loc);
            int cl = loc[0] - ox;
            int ct = loc[1] - oy;
            int cw = cell.getWidth(), ch = cell.getHeight();
            if (ct + ch <= 0 || ct >= h) return null;

            double density = container.getResources().getDisplayMetrics().density;
            int insetIdx = Math.max(0, Math.min(S.integer("inset", 1), C.INSETS.length - 1));
            double ix = C.INSETS[insetIdx][0] * density, iy = C.INSETS[insetIdx][1] * density;
            double pw = cw - 2 * ix, ph = ch - 2 * iy;
            if (pw < 8 || ph < 8) return null;
            double[] rect = {cl + ix, ct + iy, cl + cw - ix, ct + ch - iy};

            int mode = S.integer("corner", 1);
            double radius;
            if (mode == 0) radius = Math.min(pw, ph) / 2.0;
            else if (mode == 1) radius = 18.0 * density;
            else radius = 0.0;
            radius = Math.min(radius, Math.min(pw / 2.0, ph / 2.0));

            double u0 = 1.0;
            if (!opening && handoffU != null) u0 = U.c01(handoffU);
            handoffU = null;

            int durIdx = Math.max(0, Math.min(S.integer("speed", 1), C.DURATIONS.length - 1));
            int duration = C.DURATIONS[durIdx];
            if (!opening) duration = (int) (Math.max(0.3, u0) * duration * C.CLOSE_FACTOR
                    * (predictive ? C.PREDICTIVE_DURATION_FACTOR : 1.0));

            ViewParent bp = prevView.getParent();
            View back = bp instanceof View ? (View) bp : null;
            boolean anchor = S.bool("anchor", true);
            boolean depth = S.bool("depth", true);

            int stretchMode = S.stretchMode();
            boolean stretch = stretchMode == 0;
            boolean sourceCardStretch = stretchMode == 1;
            boolean headerFlight = S.bool("header_sprite_flight_v2", true);

            Bitmap chatBm = null;
            Morph.Header hdrPending = null;
            if (!opening && (predictive || S.bool("parallel_close", true))) {
                Morph.Header hdrInfo = null;
                try {
                    double t0s = U.now();
                    hdrInfo = headerFlight ? captureHeader(fragment, container, true) : null;
                    int sw = Math.max(1, (int) (w * C.SNAPSHOT_SCALE));
                    int sh = Math.max(1, (int) (h * C.SNAPSHOT_SCALE));
                    chatBm = obtainSnapshotBitmap(sw, sh);
                    List<Object[]> settled = settleCells(fragment);
                    try {
                        Canvas cv = new Canvas(chatBm);
                        cv.scale((float) sw / (float) w, (float) sh / (float) h);
                        container.draw(cv);
                    } finally {
                        unsettleCells(settled);
                    }
                    hdrPending = hdrInfo;
                    log("close snapshot taken in " + (int) ((U.now() - t0s) * 1000) + "ms (scale="
                            + String.format("%.2f", C.SNAPSHOT_SCALE) + ", header=" + (hdrInfo != null ? "yes" : "no") + ")");
                } catch (Throwable e) {
                    if (chatBm != null) releaseSnapshotBitmap(chatBm);
                    log("chat snapshot failed, live close: " + e);
                    chatBm = null;
                } finally {
                    if (hdrInfo != null) {
                        for (int i = 0; i < hdrInfo.views.size(); i++) {
                            try {
                                hdrInfo.views.get(i).setAlpha(hdrInfo.alpha.get(i));
                            } catch (Throwable ignored) {
                            }
                        }
                        if (chatBm == null) {
                            for (Bitmap bm : hdrInfo.bitmaps) {
                                try {
                                    bm.recycle();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }
            }
            boolean live = chatBm == null;
            if (predictive && live) return null;

            boolean wait = opening && R.bool(R.call(fragment, "needDelayOpenAnimation"), false);

            Morph run = new Morph(this, live, container, host, rect, w, h, radius, density, ix, iy, !opening, cell,
                    dialogId, anchor, live ? callback : null, fragment, wait, u0, stretch);
            run.hdrPending = hdrPending;
            run.predictiveClose = predictive && !opening;
            run.trackHeaderBitmaps(hdrPending);
            int selectedOpenMode = S.integer("open_mode", 0);
            run.snapshotOpen = selectedOpenMode == 1;
            if (opening && stretch) {
                log("open content mode: " + (selectedOpenMode == 1 ? "snapshot" : "live") + " (user-selected)");
                if (run.liveStretchOpen) {
                    log("live-stretch tuning: msgRetry=" + (int) (run.msgRetryInterval * 1000) + "ms/" + run.maxMsgRetries
                            + "; headerRetry=" + (int) (run.headerRetryInterval * 1000) + "ms/" + run.maxHeaderRetries
                            + "; gradient=" + (int) (C.LIVE_STRETCH_CHAT_FADE_LEN * 100) + "% progress");
                }
            }
            log("start " + (opening ? "open" : "close") + " dlg=" + dialogId + " cell=" + cw + "x" + ch + "@y" + ct
                    + " screen=" + w + "x" + h + " content_stretch=" + stretch + " source_card_stretch=" + sourceCardStretch
                    + " header_flight=" + headerFlight + " open_mode=" + selectedOpenMode
                    + " predictive=" + predictive + " live=" + live + " wait=" + wait + " runs=" + runs.size()
                    + " hostChildren=" + host.getChildCount() + " native=" + nativeMb() + "MB java=" + javaMb() + "MB");

            if (opening) {
                try {
                    Morph.Target initial = findMsgTarget(fragment, container, cell, false, null);
                    run.acceptMessageTarget(initial, U.now());
                    run.msgLastTry = U.now();
                    Morph.Target t = run.msgTarget;
                    if (t != null && t.pos != null) {
                        String status = t.matchedPreview ? "exact" : (t.waitingForExact ? "fallback; exact pending" : "visible fallback");
                        log("message target initial=" + status + "; message_id=" + t.messageId + " source_message_id="
                                + t.sourceMessageId + " pos=[" + t.pos[0] + ", " + t.pos[1] + "] height=" + t.height
                                + " bottom=" + t.bottom);
                    } else {
                        log("message target initial=pending; source_message_id=" + (t != null ? t.sourceMessageId : null)
                                + "; retrying valid visible cells");
                    }
                } catch (Throwable e) {
                    run.msgTarget = null;
                    run.msgLastTry = U.now();
                    log("message target initial lookup failed: " + e);
                }
            } else if (!live) {
                try {
                    run.acceptMessageTarget(findMsgTarget(fragment, container, cell, false, null), U.now());
                } catch (Throwable e) {
                    run.msgTarget = null;
                    log("close message target lookup failed: " + e);
                }
            }
            if (opening) {
                run.begun = false;
                run.minWait = C.WAIT_MIN;
            }
            if (predictive) {
                try {
                    double tx = R.num(R.get(layout, "innerTranslationX"), 0.0);
                    run.dim0 = (120.0 / 255.0) * Math.max(0.0, Math.min(0.8, (w - tx) / (double) w));
                    run.dimLen = 1.0;
                    Object[] sl = slideTarget(prev);
                    if (sl != null) {
                        run.slideP0 = (Double) sl[2];
                        run.slideLite = (Boolean) sl[3];
                        run.slideAmp = (Double) sl[4];
                        log("slide: captured native progress p0=" + String.format("%.3f", run.slideP0) + " lite=" + run.slideLite
                                + " amp=" + run.slideAmp);
                    } else {
                        double frac = U.c01(Math.abs(tx) / Math.max(1.0, 56.0 * density));
                        run.slideP0 = 1.0 - 0.15 * frac;
                        log("slide: estimated progress p0=" + String.format("%.3f", run.slideP0) + " from tx=" + tx);
                    }
                    run.slideEmul = true;
                    run.slideArmed = false;
                    run.slideObj = null;
                    run.slideM = null;
                    try {
                        run.slideBase = new double[]{prevView.getTranslationX(), prevView.getTranslationY(),
                                prevView.getScaleX(), prevView.getScaleY(), prevView.getPivotX(), prevView.getPivotY(),
                                prevView.getAlpha()};
                        run.backC = prevView;
                    } catch (Throwable e) {
                        log("slide base capture failed: " + e);
                    }
                    if (R.bool(R.get(layout, "predictiveBackInProgress"), false)
                            && R.bool(R.get(layout, "predictiveBackHasProgress"), false)) {
                        double frac = U.c01(tx / (56.0 * density));
                        double target = 0.90 + (0.85 - 0.90) * (1.0 - container.getAlpha());
                        run.preS = 1.0 + (target - 1.0) * frac;
                        boolean left = R.bool(R.get(layout, "predictiveBackLeft"), false);
                        run.prePx = left ? (w - 82.0 * density) : 82.0 * density;
                        double py = R.num(R.get(layout, "predictiveBackY"), 0.0);
                        run.prePy = py != 0.0 ? py : h / 2.0;
                        log("gesture: tx=" + tx + " s=" + String.format("%.3f", run.preS) + " dim=" + String.format("%.2f", run.dim0));
                    } else {
                        run.offX0 = container.getTranslationX();
                        log("swipe: tx=" + tx + " off=" + run.offX0);
                    }
                } catch (Throwable e) {
                    log("gesture state failed: " + e);
                }
            }
            try {
                run.setup(back, cell, depth, chatBm);
                if (predictive) run.backC = prevView;
                run.update(0.0);

                ValueAnimator va = ValueAnimator.ofFloat(0.0f, 1.0f);
                va.setDuration((long) (duration * 10 + (wait ? C.WAIT_MAX * 1000 : 0) + 1000));
                va.setInterpolator(new LinearInterpolator());
                UpdateListener ul = new UpdateListener(run, duration);
                run.updateListener = ul;
                va.addUpdateListener(ul);
                run.va = va;
                runs.add(run);

                if (live) {
                    AnimatorSet aset = new AnimatorSet();
                    aset.play(va);
                    aset.addListener(new UpdateListener.End(run));
                    run.aset = aset;
                    param.setResult(aset);
                    aset.start();
                } else {
                    va.addListener(new UpdateListener.End(run));
                    if (predictive) {
                        va.start();
                        container.setAlpha(0.0f);
                        return run;
                    }
                    param.setResult(new AnimatorSet());
                    va.start();
                    container.setAlpha(0.0f);
                    final Runnable cb = callback;
                    final View cont = container;
                    postUi(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (cb != null) cb.run();
                            } finally {
                                try {
                                    cont.setAlpha(1.0f);
                                } catch (Throwable ignored) {
                                }
                                raiseAll();
                            }
                        }
                    }, 0);
                }
            } catch (Throwable e) {
                run.abort();
                try {
                    container.setAlpha(1.0f);
                } catch (Throwable ignored) {
                }
                log("animation setup failed: " + e);
                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
            }
            return null;
        } catch (Throwable e) {
            log("start failed: " + e);
            return null;
        }
    }
}

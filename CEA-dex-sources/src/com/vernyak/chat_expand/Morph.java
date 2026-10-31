package com.vernyak.chat_expand;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** One running chat expand/collapse animation (port of the Python _Morph class). */
final class Morph {

    // ------------------------------------------------------------------ model

    /** A piece of the dialog card that flies on its own (avatar, name, last message). */
    static final class Sprite {
        String key;
        ImageView iv;
        int bw, bh;
        double[] sr;      // x0, y0, w0, h0 inside the card bitmap
        double[] sa;      // anchor inside the card
        double sref;
        double fontSize;
        ImageView div;    // matching header piece
        double[] danc;
        double dref;
        double[] dpos;
        double[] frame;   // scale, left, top of the last applied frame
    }

    static final class Region {
        String key;
        double[] box;
        double[] anc;
        double ref;
        double fontSize;
    }

    static final class HeaderPart {
        Bitmap bm;
        int bw, bh;
        double[] anc;
        double ref;
        double[] pos;
    }

    static final class Header {
        HeaderPart avatar, name;
        final ArrayList<View> views = new ArrayList<View>();
        final ArrayList<Float> alpha = new ArrayList<Float>();
        final ArrayList<Bitmap> bitmaps = new ArrayList<Bitmap>();

        HeaderPart part(String key) {
            if ("avatar".equals(key)) return avatar;
            if ("name".equals(key)) return name;
            return null;
        }
    }

    /** Where the last message text of the dialog ends up inside the chat. */
    static final class Target {
        double[] pos;                 // null when unknown
        double height, bottom, fontSize;
        Long messageId, sourceMessageId;
        boolean matchedPreview, waitingForExact;

        Target copy() {
            Target t = new Target();
            t.pos = pos == null ? null : new double[]{pos[0], pos[1]};
            t.height = height;
            t.bottom = bottom;
            t.fontSize = fontSize;
            t.messageId = messageId;
            t.sourceMessageId = sourceMessageId;
            t.matchedPreview = matchedPreview;
            t.waitingForExact = waitingForExact;
            return t;
        }
    }

    private static final class Correction {
        Target from, to;
        double start, duration;
    }

    // ----------------------------------------------------------------- state

    final Controller plugin;
    final boolean live;
    View container;
    ViewGroup host;
    double[] rect;
    final int w, h;
    final double radius, density, ix, iy;
    final boolean reverse;
    final boolean stretch;
    final boolean liveStretchOpen;
    final double msgRetryInterval;
    final int maxMsgRetries;
    final double headerRetryInterval;
    final int maxHeaderRetries;
    View cell;
    final long dialogId;
    final boolean anchor;

    final boolean sourceCardStretch;
    final boolean headerSpriteFlight;
    final boolean gradientEnabled;
    int baseLeft, baseTop;
    double origTx, origTy;
    double offX0;
    double preS = 1.0, prePx, prePy;
    double dim0 = C.BG_DIM;
    double dimLen = 0.5;
    double speed = 1.0;
    boolean ghost, ghostRelease, unflipped;
    double ufU;
    Object slideObj;
    Method slideM;
    double slideP0 = 1.0;
    private final HashSet<String> soft = new HashSet<String>();
    ArrayList<Sprite> sprites = new ArrayList<Sprite>();
    Header hdrInfo;
    ArrayList<View> hdrViews = new ArrayList<View>();
    ArrayList<Float> hdrAlpha = new ArrayList<Float>();
    int hdrTries;
    double hdrLastTry, msgLastTry;
    Header hdrPending;
    final double cyFrac;
    double curBb;
    Double lastChatAlpha;

    private float[] morphIn = new float[29];
    private float[] morphOut = new float[18];
    private boolean morphAttempted;
    ArrayList<Bitmap> bitmaps = new ArrayList<Bitmap>();
    AnimatorSet aset;
    Clip cclip;
    boolean chatIsOverlay;
    boolean snapshotOpen;
    Target msgTarget, msgTargetExact;
    private Correction msgTargetCorrection;
    int msgTries;
    Bitmap snapshotBitmap;
    View backC;
    boolean firstShownFrame;
    private View touchGuard;
    private boolean backLayered, boosted;
    boolean slideEmul;
    boolean slideArmed = true;
    double[] slideBase;
    boolean slideLite = true;
    double slideAmp = 40.0;
    double slideSign = -1.0;
    UpdateListener updateListener;
    double radFull;
    double minWait;
    boolean flipped;
    private boolean reverseClockDetached;
    double flipU;
    final double u0;
    double curU;
    ValueAnimator va;
    Runnable callback;
    Object fragment;
    boolean waiting;
    final boolean callStart;
    boolean begun;
    final double waitStart;
    private final int[] loc = new int[2];
    View chat;
    Clip clip;
    ImageView overlay;
    View scrim;
    ImageView snap;
    Clip snapClip;
    boolean snapVisible = true;
    View back;
    int oldLayer;
    int cw, ch;
    boolean cellHidden;
    boolean done;
    boolean predictiveClose;
    private boolean msgDirectLogged, avatarDirectLogged, nameDirectLogged;
    private double msgValidSince = -1.0, msgAlphaFrom = 1.0;
    private double lastRaise;
    private boolean alphaFixLogged;
    private boolean nativeDepthBackApplied;
    private final float[] depthFrame = new float[8];

    Morph(Controller plugin, boolean live, View container, ViewGroup host, double[] rect, int w, int h,
          double radius, double density, double ix, double iy, boolean reverse, View cell, long dialogId,
          boolean anchor, Runnable callback, Object fragment, boolean wait, double u0, boolean stretch) {
        this.plugin = plugin;
        this.live = live;
        this.container = container;
        this.host = host;
        this.rect = rect;
        this.w = w;
        this.h = h;
        this.radius = radius;
        this.density = density;
        this.ix = ix;
        this.iy = iy;
        this.reverse = reverse;
        this.stretch = stretch;
        int selectedOpenMode = S.integer("open_mode", 0);
        this.liveStretchOpen = live && stretch && !reverse && selectedOpenMode == 0;
        this.msgRetryInterval = liveStretchOpen ? C.LIVE_STRETCH_MSG_RETRY_INTERVAL : C.MSG_RETRY_INTERVAL;
        this.maxMsgRetries = liveStretchOpen ? C.LIVE_STRETCH_MAX_MSG_RETRIES : C.MAX_MSG_RETRIES;
        this.headerRetryInterval = liveStretchOpen ? C.LIVE_STRETCH_HEADER_RETRY_INTERVAL : C.HEADER_RETRY_INTERVAL;
        this.maxHeaderRetries = liveStretchOpen ? C.LIVE_STRETCH_MAX_HEADER_RETRIES : C.MAX_HEADER_RETRIES;
        this.cell = cell;
        this.dialogId = dialogId;
        this.anchor = anchor;
        this.sourceCardStretch = S.stretchMode() == 1;
        this.headerSpriteFlight = S.bool("header_sprite_flight_v2", true);
        this.gradientEnabled = S.bool("gradient_open", true);
        this.cyFrac = ((rect[1] + rect[3]) / 2.0) / Math.max(1.0, (double) h);
        this.curBb = rect[3];
        this.callback = callback;
        this.fragment = fragment;
        this.waiting = wait;
        this.callStart = wait;
        this.begun = !wait;
        this.waitStart = U.now();
        this.u0 = u0;
    }

    // ------------------------------------------------------------------ setup

    private Sprite sprite(String key) {
        for (Sprite sp : sprites) {
            if (key.equals(sp.key) && sp.iv != null) return sp;
        }
        return null;
    }

    void setup(View backView, View cellView, boolean depth, Bitmap chatBm) throws Exception {
        ViewGroup hostV = host;
        this.backC = backView;
        android.content.Context ctx = hostV.getContext();

        if (live) {
            chat = container;
            try {
                oldLayer = chat.getLayerType();
                int desired = (live && stretch) ? View.LAYER_TYPE_NONE : View.LAYER_TYPE_HARDWARE;
                chat.setLayerType(desired, null);
                if (live && stretch) {
                    plugin.log("live content-stretch render: no forced hardware layer (avoid stale close clip / full chat texture rerasterization)");
                }
            } catch (Throwable e) {
                plugin.log("layer failed: " + e);
            }
        }

        if (depth) {
            try {
                View sc = new View(ctx);
                sc.setBackgroundColor(Color.BLACK);
                sc.setAlpha(0.0f);
                if (live) {
                    hostV.addView(sc, hostV.indexOfChild(container), new FrameLayout.LayoutParams(-1, -1));
                } else {
                    hostV.addView(sc, new FrameLayout.LayoutParams(-1, -1));
                }
                scrim = sc;
                if (live && backView != null) {
                    double l = rect[0], t = rect[1], r = rect[2], b = rect[3];
                    backView.setPivotX((float) ((l + r) / 2.0));
                    backView.setPivotY((float) ((t + b) / 2.0));
                    back = backView;
                    plugin.acquireLayer(backView);
                    backLayered = true;
                }
            } catch (Throwable e) {
                plugin.log("depth setup failed: " + e);
            }
        }

        if (!live) {
            ImageView iv = new ImageView(ctx);
            iv.setImageBitmap(chatBm);
            snapshotBitmap = chatBm;
            bitmaps.add(chatBm);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            hostV.addView(iv, new FrameLayout.LayoutParams(w, h));
            overlay = iv;
            chat = iv;
            chatIsOverlay = true;
        } else {
            try {
                baseLeft = chat.getLeft();
                baseTop = chat.getTop();
                origTx = chat.getTranslationX();
                origTy = chat.getTranslationY();
            } catch (Throwable ignored) {
            }
        }
        if (stretch) {
            chat.setPivotX(0.0f);
            chat.setPivotY(0.0f);
        }

        clip = new Clip(chat);
        if (live) cclip = clip;

        try {
            WindowInsets ins = hostV.getRootWindowInsets();
            Object rc = ins != null ? R.tryCall(ins, "getRoundedCorner", 0) : null;
            radFull = rc != null ? R.num(R.tryCall(rc, "getRadius"), 0.0) * preS : 0.0;
        } catch (Throwable e) {
            radFull = 0.0;
        }
        if (preS != 1.0) {
            radFull = Math.max(radFull, C.GESTURE_RAD_DP * density * preS);
        }

        try {
            int cellW = cellView.getWidth(), cellH = cellView.getHeight();
            cw = cellW;
            ch = cellH;
            Bitmap bm = Bitmap.createBitmap(cellW, cellH, Bitmap.Config.ARGB_8888);
            bitmaps.add(bm);
            Canvas cv = new Canvas(bm);
            cellView.draw(cv);

            ArrayList<Object[]> pending = new ArrayList<Object[]>();
            try {
                List<Region> regions = srcRegions(cellView, bm);
                if (!headerSpriteFlight) {
                    ArrayList<Region> keep = new ArrayList<Region>();
                    for (Region rg : regions) {
                        String k = rg.key;
                        if (!("avatar".equals(k) || "name".equals(k) || "online".equals(k) || "timer".equals(k) || "msg".equals(k))) {
                            keep.add(rg);
                        }
                    }
                    regions = keep;
                }
                for (Region rg : regions) {
                    int[] box = cropBox(rg.box, cellW, cellH, 2.0);
                    if (box == null) continue;
                    int x0 = box[0], y0 = box[1], w0 = box[2], h0 = box[3];
                    Bitmap sub = Bitmap.createBitmap(bm, x0, y0, w0, h0);
                    bitmaps.add(sub);
                    cv.save();
                    cv.clipRect(x0, y0, x0 + w0, y0 + h0);
                    cv.drawColor(0, PorterDuff.Mode.CLEAR);
                    cv.restore();
                    pending.add(new Object[]{rg, sub, box});
                }
            } catch (Throwable e) {
                plugin.log("sprite regions failed: " + e);
                pending.clear();
            }

            ImageView iv = new ImageView(ctx);
            iv.setImageBitmap(bm);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setPivotX(0.0f);
            iv.setPivotY(0.0f);
            hostV.addView(iv, new FrameLayout.LayoutParams(cellW, cellH));
            snap = iv;
            Clip sc = new Clip(iv);
            sc.set(ix, iy, cellW - ix, cellH - iy, radius, radius);
            snapClip = sc;

            for (Object[] item : pending) {
                Region rg = (Region) item[0];
                Bitmap sub = (Bitmap) item[1];
                int[] box = (int[]) item[2];
                ImageView siv = new ImageView(ctx);
                siv.setImageBitmap(sub);
                siv.setScaleType(ImageView.ScaleType.FIT_XY);
                siv.setPivotX(0.0f);
                siv.setPivotY(0.0f);
                hostV.addView(siv, new FrameLayout.LayoutParams(box[2], box[3]));
                Sprite sp = new Sprite();
                sp.key = rg.key;
                sp.iv = siv;
                sp.bw = box[2];
                sp.bh = box[3];
                sp.sr = new double[]{box[0], box[1], box[2], box[3]};
                sp.sa = rg.anc;
                sp.sref = rg.ref;
                sp.fontSize = rg.fontSize;
                sprites.add(sp);
            }
            if (hdrPending != null) attachHeader(hdrPending);

            cellView.setVisibility(View.INVISIBLE);
            cellHidden = true;

            if (live && stretch && !reverse && !flipped) {
                try {
                    container.setAlpha(0.0f);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            plugin.log("required snapshot/DEX setup failed: " + e);
            if (e instanceof Exception) throw (Exception) e;
            throw new RuntimeException(e);
        }
    }

    private double[] detectNameBox(View cellView, Bitmap aBm, int cellW, int cellH) {
        Object nl = R.get(cellView, "nameLayout");
        if (nl == null) return null;
        int sw = Math.max(1, cellW / 4), sh = Math.max(1, cellH / 4);
        Bitmap bBm = null, saBm = null, sbBm = null;
        try {
            bBm = Bitmap.createBitmap(cellW, cellH, Bitmap.Config.ARGB_8888);
            R.set(cellView, "nameLayout", null);
            try {
                cellView.draw(new Canvas(bBm));
            } finally {
                R.set(cellView, "nameLayout", nl);
            }
            saBm = Bitmap.createScaledBitmap(aBm, sw, sh, true);
            sbBm = Bitmap.createScaledBitmap(bBm, sw, sh, true);
            int[] pa = new int[sw * sh];
            int[] pb = new int[sw * sh];
            saBm.getPixels(pa, 0, sw, 0, 0, sw, sh);
            sbBm.getPixels(pb, 0, sw, 0, 0, sw, sh);
            int x0 = 1000000000, y0 = 1000000000, x1 = -1, y1 = -1;
            for (int i = 0; i < sw * sh; i++) {
                int a = pa[i];
                int b = pb[i];
                if (a == b) continue;
                int d = Math.abs(((a >> 24) & 255) - ((b >> 24) & 255)) + Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                        + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) + Math.abs((a & 255) - (b & 255));
                if (d > 40) {
                    int x = i % sw;
                    int y = i / sw;
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
            if (x1 < 0) return null;
            double bx = x0 * 4.0, by = y0 * 4.0;
            double bw = (x1 - x0 + 1) * 4.0, bh = (y1 - y0 + 1) * 4.0;
            if (bh > cellH * 0.6 || bw > cellW * 0.85) return null;
            return new double[]{bx, by, bw, bh};
        } catch (Throwable e) {
            plugin.log("name detect failed: " + e);
            return null;
        } finally {
            for (Bitmap b : new Bitmap[]{bBm, saBm, sbBm}) {
                if (b != null) {
                    try {
                        b.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    static boolean invisibleChar(char c) {
        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) return true;
        if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u200E' || c == '\u200F' || c == '\u2060'
                || c == '\uFEFF' || c == '\u2800' || c == '\u3164' || c == '\u115F' || c == '\u1160' || c == '\u034F'
                || c == '\u00AD') {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.FORMAT || type == Character.CONTROL;
    }

    /** Width of leading spaces/invisible characters of the first line (LTR only), 0 when there are none. */
    static double leadingInvisibleWidth(Layout layout) {
        try {
            CharSequence text = layout.getText();
            if (text == null || text.length() == 0 || layout.getParagraphDirection(0) != Layout.DIR_LEFT_TO_RIGHT) return 0.0;
            int n = text.length(), idx = 0;
            while (idx < n && invisibleChar(text.charAt(idx))) idx++;
            if (idx <= 0 || idx >= n) return 0.0;
            if (layout.getLineForOffset(idx) != 0) return 0.0;
            return Math.max(0.0, (double) (layout.getPrimaryHorizontal(idx) - layout.getPrimaryHorizontal(0)));
        } catch (Throwable e) {
            return 0.0;
        }
    }

    /**
     * Index in the dialog preview where the message text itself starts, i.e. after the "Sender: " prefix
     * shown in group chats; 0 when there is no prefix or it cannot be determined.
     */
    static String firstToken(String message) {
        if (message == null) return null;
        int start = 0;
        while (start < message.length() && Character.isWhitespace(message.charAt(start))) start++;
        int end = start;
        while (end < message.length() && end - start < 12 && !Character.isWhitespace(message.charAt(end))) end++;
        return end > start ? message.substring(start, end) : null;
    }

    /** True when the dialog preview really shows the given message (and not e.g. "typing..."). */
    static boolean previewMatchesMessage(CharSequence preview, String message) {
        String needle = firstToken(message);
        if (needle == null || preview == null) return true;
        return preview.toString().contains(needle);
    }

    static int messagePrefixIndex(CharSequence preview, String message) {
        try {
            if (preview == null || message == null) return 0;
            String p = preview.toString();
            int start = 0;
            while (start < message.length() && Character.isWhitespace(message.charAt(start))) start++;
            int end = start;
            while (end < message.length() && end - start < 12 && !Character.isWhitespace(message.charAt(end))) end++;
            if (end <= start) return 0;
            String needle = message.substring(start, end);
            int sep = p.indexOf(": ");
            if (sep >= 0 && sep <= 80 && p.startsWith(needle, sep + 2)) return sep + 2;
            int idx = p.indexOf(needle);
            if (idx > 0 && idx <= 80) return idx;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private List<Region> srcRegions(View cellView, Bitmap aBm) {
        ArrayList<Region> out = new ArrayList<Region>();
        double d = density;
        double cwf = (double) cw;
        Object ai = R.get(cellView, "avatarImage");
        double asz = ai != null ? R.num(R.tryCall(ai, "getImageWidth"), 0.0) : 0.0;
        if (asz > 4.0) {
            double acx = R.num(R.tryCall(ai, "getCenterX"), 0.0);
            double acy = R.num(R.tryCall(ai, "getCenterY"), 0.0);
            Region rg = new Region();
            rg.key = "avatar";
            rg.box = new double[]{acx - asz / 2.0, acy - asz / 2.0, asz, asz};
            rg.anc = new double[]{acx, acy};
            rg.ref = asz;
            out.add(rg);
        }
        Object nlo = R.get(cellView, "nameLayout");
        if (nlo instanceof Layout) {
            Layout nl = (Layout) nlo;
            double nx = R.num(R.get(cellView, "nameLeft"), 0.0) + R.num(R.get(cellView, "nameLayoutTranslateX"), 0.0);
            boolean three = false;
            try {
                three = R.bool(R.getStatic("org.telegram.messenger.SharedConfig", "useThreeLinesLayout"), false);
            } catch (Throwable ignored) {
            }
            try {
                three = three || R.bool(R.get(cellView, "useForceThreeLines"), false);
            } catch (Throwable ignored) {
            }
            double ny = (three ? 10.0 : 14.0) * d;
            double nw = (double) nl.getLineWidth(0);
            double nh = (double) nl.getHeight();
            double[] det = aBm != null ? detectNameBox(cellView, aBm, (int) cwf, ch) : null;
            if (det != null) {
                nx = det[0];
                nw = det[2];
                double cy = det[1] + det[3] / 2.0;
                ny = cy - nh / 2.0;
            }
            try {
                CharSequence ntext = nl.getText();
                int invis = 0;
                while (ntext != null && invis < ntext.length() && invisibleChar(ntext.charAt(invis))) invis++;
                plugin.log("name source: detected=" + (det != null) + (det != null ? " x=" + det[0] : "") + " layoutLeft=" + nx
                        + " leadingInvisible=" + invis + " leadWidth=" + leadingInvisibleWidth(nl));
            } catch (Throwable ignored) {
            }
            if (det == null) {
                double lead = leadingInvisibleWidth(nl);
                if (lead > 0.0) {
                    nx += lead;
                    nw = Math.max(0.0, nw - lead);
                }
            }
            if (nw > 4.0 && nh > 4.0) {
                double ext = Math.min(nw + 40.0 * d, Math.max(nw, cwf - nx - 90.0 * d));
                Region rg = new Region();
                rg.key = "name";
                rg.box = new double[]{nx, ny, ext, nh};
                rg.anc = new double[]{nx, ny + nh / 2.0};
                rg.ref = nh;
                out.add(rg);
            }
        }

        try {
            Object previewObj = null;
            for (String fieldName : new String[]{"message", "messageObject", "lastMessageObject"}) {
                try {
                    Object candidate = R.get(cellView, fieldName);
                    if (candidate != null && R.hasField(candidate, "type")) {
                        previewObj = candidate;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            boolean previewIsText = previewObj != null && (R.intv(R.get(previewObj, "type"), -1) == 0
                    || Controller.isActionMessage(previewObj));
            Object mlo = R.get(cellView, "messageLayout");
            if (previewIsText && mlo instanceof Layout) {
                Layout ml = (Layout) mlo;
                Object printing = R.get(cellView, "lastPrintString");
                Object pmt = R.get(previewObj, "messageText");
                if (printing instanceof CharSequence && ((CharSequence) printing).length() > 0) {
                    plugin.log("message preview is a typing status: stays in the card and fades");
                    previewIsText = false;
                } else if (!previewMatchesMessage(ml.getText(), pmt != null ? pmt.toString() : null)) {
                    plugin.log("message preview text differs from the last message (status/draft?): stays in the card and fades");
                    previewIsText = false;
                }
            }
            if (previewIsText && mlo instanceof Layout) {
                Layout ml = (Layout) mlo;
                int lines = Math.max(1, ml.getLineCount());
                ArrayList<Double> lineLefts = new ArrayList<Double>();
                ArrayList<Double> lineRights = new ArrayList<Double>();
                for (int li = 0; li < Math.min(lines, 3); li++) {
                    double leftI = (double) ml.getLineLeft(li);
                    double widthI = (double) ml.getLineWidth(li);
                    lineLefts.add(leftI);
                    lineRights.add(leftI + widthI);
                }
                if (!lineLefts.isEmpty() && !lineRights.isEmpty()) {
                    double minLeft = 0.0;
                    for (double v : lineLefts) minLeft = Math.min(minLeft, v);
                    double maxRight = Double.NEGATIVE_INFINITY;
                    for (double v : lineRights) maxRight = Math.max(maxRight, v);
                    double boxW = maxRight - minLeft;
                    double mh = (double) ml.getHeight();
                    double lh = (double) (ml.getLineBottom(0) - ml.getLineTop(0));
                    if (boxW > 4.0 && mh > 4.0 && lh > 4.0) {
                        double mleft = R.num(R.get(cellView, "messageLeft"), 0.0);
                        double mtop = R.num(R.get(cellView, "messageTop"), 0.0);
                        double msgFontSize;
                        try {
                            msgFontSize = (double) ml.getPaint().getTextSize();
                        } catch (Throwable e) {
                            msgFontSize = 0.0;
                        }
                        double baseline = (double) ml.getLineBaseline(0);
                        double lineLeft = (double) ml.getLineLeft(0);

                        double prefixX = -1.0;
                        try {
                            Object mt = R.get(previewObj, "messageText");
                            int pi = messagePrefixIndex(ml.getText(), mt != null ? mt.toString() : null);
                            if (pi > 0 && ml.getParagraphDirection(0) == Layout.DIR_LEFT_TO_RIGHT
                                    && ml.getLineForOffset(pi) == 0) {
                                prefixX = (double) ml.getPrimaryHorizontal(pi);
                            }
                        } catch (Throwable ignored) {
                        }

                        Region rg = new Region();
                        rg.key = "msg";
                        rg.fontSize = msgFontSize;
                        rg.ref = Math.max(1.0, lh);
                        if (prefixX >= 0.0) {
                            // Group chat preview "Sender: text": only the text itself flies; the sender part
                            // stays in the card and fades together with it.
                            double line0Right = (double) ml.getLineRight(0);
                            double regionLeft = Math.max(0.0, mleft + prefixX);
                            double rightExtra = Math.min(3.0 * d, Math.max(0.0, cwf - (mleft + line0Right)));
                            double regionRight = Math.min(cwf, mleft + line0Right + rightExtra);
                            rg.box = new double[]{regionLeft, mtop + (double) ml.getLineTop(0),
                                    Math.max(2.0, regionRight - regionLeft), lh};
                            rg.anc = new double[]{mleft + prefixX, mtop + baseline};
                            plugin.log("message preview has sender prefix; text flies from x=" + (int) prefixX);
                        } else {
                            double leftExtra = Math.min(3.0 * d, Math.max(0.0, mleft + minLeft));
                            double rightExtra = Math.min(C.MSG_CAPTURE_SIDE_DP * d, Math.max(0.0, cwf - (mleft + maxRight) - 42.0 * d));
                            double regionLeft = Math.max(0.0, mleft + minLeft - leftExtra);
                            double regionRight = Math.min(cwf, mleft + maxRight + rightExtra);
                            double regionW = Math.max(boxW, regionRight - regionLeft);
                            rg.box = new double[]{regionLeft, mtop, regionW, mh};
                            rg.anc = new double[]{mleft + lineLeft, mtop + baseline};
                        }
                        out.add(rg);
                    }
                }
            }
        } catch (Throwable e) {
            plugin.log("msg region failed: " + e);
        }
        return out;
    }

    static int[] cropBox(double[] reg, int cw, int ch, double pad) {
        double x = reg[0], y = reg[1], w = reg[2], h = reg[3];
        int x0 = Math.max(0, (int) (x - pad));
        int y0 = Math.max(0, (int) (y - pad));
        int x1 = Math.min(cw, (int) (x + w + pad + 1));
        int y1 = Math.min(ch, (int) (y + h + pad + 1));
        if (x1 - x0 < 2 || y1 - y0 < 2) return null;
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    void trackHeaderBitmaps(Header info) {
        if (info == null) return;
        for (Bitmap bm : info.bitmaps) {
            if (bm == null) continue;
            boolean known = false;
            for (Bitmap e : bitmaps) {
                if (e == bm) {
                    known = true;
                    break;
                }
            }
            if (!known) bitmaps.add(bm);
        }
    }

    void attachHeader(Header info) {
        trackHeaderBitmaps(info);
        ViewGroup hostV = host;
        android.content.Context ctx = hostV.getContext();
        for (Sprite sp : sprites) {
            HeaderPart part = info.part(sp.key);
            if (part == null || sp.div != null) continue;
            ImageView iv = new ImageView(ctx);
            iv.setImageBitmap(part.bm);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setPivotX(0.0f);
            iv.setPivotY(0.0f);
            hostV.addView(iv, new FrameLayout.LayoutParams(part.bw, part.bh));
            iv.setAlpha(0.0f);
            sp.div = iv;
            sp.danc = part.anc;
            sp.dref = part.ref;
            double[] dpos = part.pos;
            if (dpos != null && ("name".equals(sp.key) || "avatar".equals(sp.key))) {
                try {
                    View cont = container;
                    double hostLeft = (double) cont.getLeft() + (live ? origTx : (double) cont.getTranslationX());
                    double hostTop = (double) cont.getTop() + (live ? origTy : (double) cont.getTranslationY());
                    hostLeft -= (double) hostV.getScrollX();
                    hostTop -= (double) hostV.getScrollY();
                    dpos = new double[]{dpos[0] + hostLeft, dpos[1] + hostTop};
                } catch (Throwable e) {
                    dpos = new double[]{dpos[0], dpos[1]};
                }
            }
            sp.dpos = dpos;
        }
    }

    // ------------------------------------------------------------ lifecycle

    void begin() {
        if (begun) return;
        begun = true;
        waiting = false;
        plugin.log("begin " + kind() + " after " + (int) ((U.now() - waitStart) * 1000) + "ms (needDelay=" + callStart + ")");
        if (live && fragment != null && !flipped) plugin.touchTransition(fragment);
        if (callStart && fragment != null) {
            try {
                R.call(fragment, "onTransitionAnimationStart", Boolean.TRUE, Boolean.FALSE);
            } catch (Throwable e) {
                plugin.log("onTransitionAnimationStart failed: " + e);
            }
        }
        switchToSnapshot();
    }

    private void switchToSnapshot() {
        if (chatIsOverlay || !live || !stretch || !snapshotOpen || reverse || flipped
                || ghost || host == null || fragment == null || container == null) {
            return;
        }
        ViewGroup hostV = host;
        View cont = container;
        Header info = hdrInfo;
        ImageView iv;
        try {
            double t0s = U.now();
            int sw = Math.max(1, (int) (w * C.OPEN_SNAPSHOT_SCALE)), sh = Math.max(1, (int) (h * C.OPEN_SNAPSHOT_SCALE));
            Bitmap bm = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            bitmaps.add(bm);
            List<Object[]> settled = plugin.settleCells(fragment);
            try {
                Canvas cv = new Canvas(bm);
                cv.scale((float) sw / (float) w, (float) sh / (float) h);
                cont.draw(cv);
            } finally {
                plugin.unsettleCells(settled);
            }

            if (headerSpriteFlight && info == null) {
                try {
                    info = plugin.captureHeader(fragment, cont, true);
                    if (info != null) {
                        trackHeaderBitmaps(info);
                        hdrInfo = info;
                        hdrViews = info.views;
                        hdrAlpha = info.alpha;
                    }
                } catch (Throwable captureError) {
                    plugin.log("snapshot header capture failed: " + captureError);
                }
            }
            iv = new ImageView(hostV.getContext());
            iv.setImageBitmap(bm);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setPivotX(0.0f);
            iv.setPivotY(0.0f);
            hostV.addView(iv, hostV.indexOfChild(cont) + 1, new FrameLayout.LayoutParams(w, h));
            plugin.log("open snapshot taken in " + (int) ((U.now() - t0s) * 1000) + "ms (scale=" + String.format("%.2f", C.OPEN_SNAPSHOT_SCALE) + ")");
        } catch (Throwable e) {
            plugin.log("open snapshot failed, animating live chat: " + e);
            try {
                cont.setAlpha(1.0f);
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            if (cclip != null) {
                cclip.restore();
                cclip = null;
            }
            cont.setScaleX(1.0f);
            cont.setScaleY(1.0f);
            cont.setTranslationX((float) origTx);
            cont.setTranslationY((float) origTy);
            try {
                cont.resetPivot();
            } catch (Throwable ignored) {
            }
            cont.setLayerType(oldLayer, null);
            cont.setAlpha(0.0f);
        } catch (Throwable e) {
            plugin.log("container reset failed: " + e);
        }
        overlay = iv;
        chat = iv;
        chatIsOverlay = true;
        baseLeft = 0;
        baseTop = 0;
        clip = new Clip(iv);
        if (info != null) {
            attachHeader(info);
            hdrInfo = info;
        }

        try {
            if (fragment != null && cell != null) {
                Target target = plugin.findMsgTarget(fragment, cont, cell, true, new double[]{origTx, origTy});
                if (target != null && target.pos != null) {
                    acceptMessageTarget(target, U.now());
                    plugin.log("snapshot message target refreshed; matched=" + target.matchedPreview
                            + " waiting_exact=" + target.waitingForExact + " pos=" + target.pos[0] + "," + target.pos[1]);
                }
            }
        } catch (Throwable targetError) {
            plugin.log("snapshot message target refresh failed: " + targetError);
        }
    }

    private void poll() {
        View c = cell;
        try {
            long id = ((Number) R.call(c, "getDialogId")).longValue();
            if (id != dialogId) {
                if (cellHidden) {
                    c.setVisibility(View.VISIBLE);
                    cellHidden = false;
                }
                cell = null;
                return;
            }
            if (c.getWidth() <= 0) return;
            View ref = host != null ? host : container;
            ref.getLocationInWindow(loc);
            int ox = loc[0], oy = loc[1];
            c.getLocationInWindow(loc);
            double cl = loc[0] - ox;
            double ct = loc[1] - oy;
            rect = new double[]{cl + ix, ct + iy, cl + c.getWidth() - ix, ct + c.getHeight() - iy};
        } catch (Throwable ignored) {
        }
    }

    /** Returns {progress for the near edge, progress for the far edge}. */
    private static double[] edgeProg(double d1, double d2, double x, double p) {
        d1 = Math.max(d1, 0.0);
        d2 = Math.max(d2, 0.0);
        double far = Math.max(d1, d2);
        if (far < 1.0) return new double[]{p, p};
        double f1 = Math.max(C.NEAR_MIN, d1 / far);
        double f2 = Math.max(C.NEAR_MIN, d2 / far);
        return new double[]{f1 >= 1.0 ? p : C.EASE.ease(U.c01(x / f1)),
                f2 >= 1.0 ? p : C.EASE.ease(U.c01(x / f2))};
    }

    void update(double t) {
        if (done) return;
        t = U.c01(t);
        double u = reverse ? u0 * (1.0 - t) : t;
        apply(u, t);
    }

    /** Keeps the live chat invisible while the opening animation is still waiting to start. */
    void holdHidden() {
        if (!live || chatIsOverlay || reverse || flipped || ghost || done || container == null) return;
        try {
            if (container.getAlpha() > 0.001f) container.setAlpha(0.0f);
        } catch (Throwable ignored) {
        }
    }

    void updateU(double u) {
        if (done) return;
        apply(U.c01(u), 1.0);
    }

    private double predictiveScaleRaw(double u) {
        double base = Math.max(0.01, Math.min(1.0, preS));
        if (base >= 0.999) return base;
        double progress = (reverse || flipped) ? (1.0 - U.c01(u)) : U.c01(u);
        progress = U.smooth(U.c01(progress / 0.55));
        return base + (1.0 - base) * progress;
    }

    private double predictiveScaleAt(double u) {
        double s = predictiveScaleRaw(u);
        if (unflipped && s < 0.999) {
            double from = predictiveScaleRaw(ufU);
            double frac = U.smooth(U.c01((u - ufU) / Math.max(0.001, 1.0 - ufU)));
            return from + (1.0 - from) * frac;
        }
        return s;
    }

    /** Returns {x, y, scale}. */
    private double[] mapPredictiveTarget(double x, double y, double u) {
        if (preS >= 0.999) return new double[]{x, y, 1.0};
        double scale = predictiveScaleAt(u);
        return new double[]{prePx * (1.0 - scale) + x * scale, prePy * (1.0 - scale) + y * scale, scale};
    }

    private double rowFadePosition() {
        return U.c01((cyFrac - 0.08) / 0.82);
    }

    private void clipMessageSpriteToBounds(Sprite sp, double L, double T, double R_, double B) {
        if (sp == null || sp.iv == null || sp.frame == null) return;
        try {
            double scale = Math.max(0.001, Math.abs(sp.frame[0]));
            double left = sp.frame[1], top = sp.frame[2];
            View view = sp.iv;
            double originX = (double) view.getLeft() + left;
            double originY = (double) view.getTop() + top;
            double width = Math.max(1.0, (double) sp.bw);
            double height = Math.max(1.0, (double) sp.bh);
            double x0 = Math.max(0.0, Math.min(width, (L - originX) / scale));
            double y0 = Math.max(0.0, Math.min(height, (T - originY) / scale));
            double x1 = Math.max(0.0, Math.min(width, (R_ - originX) / scale));
            double y1 = Math.max(0.0, Math.min(height, (B - originY) / scale));
            Rect clipRect;
            if (x1 <= x0 || y1 <= y0) clipRect = new Rect(0, 0, 0, 0);
            else clipRect = new Rect((int) Math.floor(x0), (int) Math.floor(y0), (int) Math.ceil(x1), (int) Math.ceil(y1));
            view.setClipBounds(clipRect);
        } catch (Throwable e) {
            plugin.log("message sprite boundary clip failed: " + e);
        }
    }

    boolean acceptMessageTarget(Target target, double now) {
        if (target == null || target.pos == null) return false;
        if (target.waitingForExact) {
            if (msgTargetExact == null && msgTargetCorrection == null && (msgTarget == null || msgTarget.pos == null)) {
                msgTarget = target;
            }
            return true;
        }
        if (target.matchedPreview) {
            Target previous = msgTarget;
            if (previous != null && previous.pos != null && previous.waitingForExact && msgTargetExact == null) {
                msgTargetExact = target;
                Correction c = new Correction();
                c.from = previous;
                c.to = target;
                c.start = now;
                c.duration = 0.12;
                msgTargetCorrection = c;
                return true;
            }
            msgTarget = target;
            msgTargetExact = target;
            msgTargetCorrection = null;
            return true;
        }
        if (target.sourceMessageId == null || target.sourceMessageId == 0L) {
            msgTarget = target;
            msgTargetExact = target;
            msgTargetCorrection = null;
            return true;
        }
        msgTarget = target;
        return true;
    }

    private void updateMessageTargetCorrection(double now) {
        Correction c = msgTargetCorrection;
        if (c == null) return;
        Target st = c.from, en = c.to;
        double duration = Math.max(0.001, c.duration);
        double t = U.c01((now - c.start) / duration);
        double p = t * t * (3.0 - 2.0 * t);
        try {
            Target out = en.copy();
            out.pos = new double[]{st.pos[0] + (en.pos[0] - st.pos[0]) * p, st.pos[1] + (en.pos[1] - st.pos[1]) * p};
            double av = U.or(st.height, U.or(en.height, 0.0));
            out.height = av + (U.or(en.height, av) - av) * p;
            av = U.or(st.bottom, U.or(en.bottom, 0.0));
            out.bottom = av + (U.or(en.bottom, av) - av) * p;
            av = U.or(st.fontSize, U.or(en.fontSize, 0.0));
            out.fontSize = av + (U.or(en.fontSize, av) - av) * p;
            out.waitingForExact = false;
            msgTarget = out;
            if (t >= 1.0) {
                msgTarget = en;
                msgTargetCorrection = null;
            }
        } catch (Throwable e) {
            plugin.log("message target correction failed: " + e);
            msgTarget = en;
            msgTargetCorrection = null;
        }
    }

    // ------------------------------------------------------- sprite flights

    private void applyMessageSpriteDirect(Sprite sp, double u, double k, double tx0, double ty0, double cardFade) {
        if (sp == null || sp.iv == null) return;
        View view = sp.iv;
        try {
            double x0 = sp.sr[0], y0 = sp.sr[1];
            double ax = sp.sa[0], ay = sp.sa[1];
            double sourceFont = Math.max(0.0, sp.fontSize);
            double sourceScale = Math.max(0.02, Math.min(4.0, k));
            double startX = tx0 + x0 * sourceScale;
            double startY = ty0 + y0 * sourceScale;
            Target target = msgTarget;
            double progress = U.c01(u / Math.max(0.05, C.FLY_P));
            double row = rowFadePosition();
            double verticalPower = 1.45 - 0.20 * row;
            double flight = U.smooth(Math.pow(progress, verticalPower));
            double verticalFlight = flight;
            double alpha = 1.0;
            boolean validTarget = target != null && target.pos != null && !target.waitingForExact;
            double destX = 0, destYTop = 0, destBaseline = 0, destFont = 0, predictiveScale = 1.0;
            if (validTarget) {
                try {
                    double rawX = target.pos[0], rawYTop = target.pos[1];
                    double rawBaseline = target.bottom;
                    double[] m1 = mapPredictiveTarget(rawX, rawYTop, u);
                    double[] m2 = mapPredictiveTarget(rawX, rawBaseline, u);
                    destX = m1[0];
                    destYTop = m1[1];
                    predictiveScale = m1[2];
                    destBaseline = m2[1];
                    destFont = Math.max(0.0, target.fontSize);
                    if (!(U.finite(destX) && U.finite(destYTop) && U.finite(destBaseline) && U.finite(destFont))
                            || (Math.abs(destX) < 0.5 && Math.abs(destYTop) < 0.5)) {
                        validTarget = false;
                    }
                } catch (Throwable e) {
                    validTarget = false;
                }
            }
            double scale, left, top;
            if (validTarget) {
                double destScale;
                if (sourceFont > 0.1 && destFont > 0.1) {
                    destScale = Math.max(0.25, Math.min(3.0, destFont / sourceFont));
                } else {
                    double sourceRef = Math.max(1.0, U.or(sp.sref, 1.0));
                    double destRef = Math.max(1.0, U.or(target.height, sourceRef));
                    destScale = Math.max(0.25, Math.min(3.0, destRef / sourceRef));
                }
                destScale = Math.max(0.20, Math.min(3.0, destScale * predictiveScale));
                double anchorX = ax - x0;
                double anchorY = ay - y0;
                double endX = destX - anchorX * destScale;
                double endY = destBaseline - anchorY * destScale;
                scale = sourceScale + (destScale - sourceScale) * flight;
                left = startX + (endX - startX) * flight;
                top = startY + (endY - startY) * verticalFlight;
                if (!msgDirectLogged) {
                    plugin.log("message sprite direct host-space flight enabled; source=(" + startX + "," + startY
                            + ") target=(" + destX + "," + destYTop + ") baseline=" + destBaseline + " pred=" + predictiveScale);
                    msgDirectLogged = true;
                }
            } else {
                scale = sourceScale;
                left = startX;
                top = startY;
            }
            double nowT = U.now();
            if (validTarget) {
                if (msgValidSince < 0.0) {
                    msgValidSince = nowT;
                    msgAlphaFrom = U.c01(cardFade);
                }
                alpha = msgAlphaFrom + (1.0 - msgAlphaFrom) * U.c01((nowT - msgValidSince) / 0.12);
            } else {
                msgValidSince = -1.0;
                alpha = U.c01(cardFade);
            }
            if (U.finite(scale) && U.finite(left) && U.finite(top) && U.finite(alpha)) {
                sp.frame = new double[]{scale, left, top};
                plugin.motion.apply(view, scale, scale, left, top, Math.max(0.0, Math.min(1.0, alpha)));
            }
        } catch (Throwable e) {
            softFail("message-sprite-direct", e);
        }
    }

    private void applyAvatarSpriteDirect(Sprite sp, double u, double k, double tx0, double ty0) {
        if (sp == null || sp.iv == null) return;
        Motion nat = plugin.motion;
        View view = sp.iv;
        try {
            double x0 = sp.sr[0], y0 = sp.sr[1];
            double ax = sp.sa[0], ay = sp.sa[1];
            double sourceScale = Math.max(0.02, Math.min(4.0, k));
            double startX = tx0 + x0 * sourceScale;
            double startY = ty0 + y0 * sourceScale;
            double targetFade = 0.0;
            double alpha = 1.0 - targetFade;
            double[] target = sp.dpos;
            if (target == null) {
                sp.frame = new double[]{sourceScale, startX, startY};
                nat.apply(view, sourceScale, sourceScale, startX, startY, Math.max(0.0, Math.min(1.0, alpha)));
                if (sp.div != null) sp.div.setAlpha(0.0f);
                return;
            }
            double rawX = target[0], rawY = target[1];
            double[] m = mapPredictiveTarget(rawX, rawY, u);
            double destX = m[0], destY = m[1], predictiveScale = m[2];
            double sourceRef = Math.max(1.0, U.or(sp.sref, 1.0));
            double destRef = Math.max(1.0, U.or(sp.dref, sourceRef));
            double destScale = Math.max(0.25, Math.min(3.0, destRef / sourceRef)) * predictiveScale;
            double anchorX = ax - x0;
            double anchorY = ay - y0;
            double endX = destX - anchorX * destScale;
            double endY = destY - anchorY * destScale;
            double progress = U.c01(u / Math.max(0.05, C.HEADER_FLY_P));
            double flight = U.smooth(progress);
            double scale = sourceScale + (destScale - sourceScale) * flight;
            double left = startX + (endX - startX) * flight;
            double top = startY + (endY - startY) * flight - 0.0;
            if (U.finite(destX) && U.finite(destY) && U.finite(destScale) && U.finite(scale) && U.finite(left)
                    && U.finite(top) && U.finite(alpha)) {
                sp.frame = new double[]{scale, left, top};
                nat.apply(view, scale, scale, left, top, alpha);
                if (sp.div != null) {
                    double[] danc = sp.danc != null ? sp.danc : new double[]{0.0, 0.0};
                    double divLeft = destX - danc[0] * predictiveScale;
                    double divTop = destY - danc[1] * predictiveScale;
                    nat.apply(sp.div, predictiveScale, predictiveScale, divLeft, divTop, targetFade);
                }
            }
            if (!avatarDirectLogged) {
                plugin.log("avatar direct host-space flight enabled; source=(" + startX + "," + startY + ") target_center=("
                        + destX + "," + destY + ") scale=" + sourceScale + "->" + destScale + " pred=" + predictiveScale);
                avatarDirectLogged = true;
            }
        } catch (Throwable e) {
            softFail("avatar-sprite-direct", e);
        }
    }

    private void applyNameSpriteDirect(Sprite sp, double u, double k, double tx0, double ty0) {
        if (sp == null || sp.iv == null) return;
        Motion nat = plugin.motion;
        View view = sp.iv;
        try {
            double x0 = sp.sr[0], y0 = sp.sr[1];
            double ax = sp.sa[0], ay = sp.sa[1];
            double sourceScale = Math.max(0.02, Math.min(4.0, k));
            double startX = tx0 + x0 * sourceScale;
            double startY = ty0 + y0 * sourceScale;
            double targetFade = 0.0;
            double alpha = 1.0 - targetFade;
            double[] target = sp.dpos;
            if (target != null) {
                try {
                    double rawX = target[0], rawY = target[1];
                    double[] m = mapPredictiveTarget(rawX, rawY, u);
                    double destX = m[0], destY = m[1], predictiveScale = m[2];
                    double sourceRef = Math.max(1.0, U.or(sp.sref, 1.0));
                    double destRef = Math.max(1.0, U.or(sp.dref, sourceRef));
                    double destScale = Math.max(0.25, Math.min(3.0, destRef / sourceRef)) * predictiveScale;
                    double anchorX = ax - x0;
                    double anchorY = ay - y0;
                    double flightTargetX = destX - C.NAME_TARGET_SHIFT_DP * density * predictiveScale;
                    double endX = flightTargetX - anchorX * destScale;
                    double endY = destY - anchorY * destScale + 1.0 * density * predictiveScale;
                    double progress = U.c01(u / Math.max(0.05, C.HEADER_FLY_P));
                    double flight = U.smooth(progress);
                    double scale = sourceScale + (destScale - sourceScale) * flight;
                    double left = startX + (endX - startX) * flight;
                    double top = startY + (endY - startY) * flight - 0.0;
                    if (U.finite(destX) && U.finite(destY) && U.finite(destScale) && U.finite(scale) && U.finite(left)
                            && U.finite(top) && U.finite(alpha)) {
                        sp.frame = new double[]{scale, left, top};
                        nat.apply(view, scale, scale, left, top, Math.max(0.0, Math.min(1.0, alpha)));
                        if (sp.div != null) {
                            double[] danc = sp.danc != null ? sp.danc : new double[]{0.0, 0.0};
                            double divLeft = destX - danc[0] * predictiveScale;
                            double divTop = destY - danc[1] * predictiveScale;
                            nat.apply(sp.div, predictiveScale, predictiveScale, divLeft, divTop, targetFade);
                        }
                    }
                    if (!nameDirectLogged) {
                        plugin.log("name sprite direct anchor flight enabled; source=(" + startX + "," + startY + ") target=("
                                + destX + "," + destY + ") scale=" + destScale + " shift=" + C.NAME_TARGET_SHIFT_DP
                                + "dp pred=" + predictiveScale);
                        nameDirectLogged = true;
                    }
                    return;
                } catch (Throwable e) {
                    softFail("name-sprite-target", e);
                }
            }
            sp.frame = new double[]{sourceScale, startX, startY};
            nat.apply(view, sourceScale, sourceScale, startX, startY, Math.max(0.0, Math.min(1.0, alpha)));
            if (sp.div != null) sp.div.setAlpha(0.0f);
        } catch (Throwable e) {
            softFail("name-sprite-direct", e);
        }
    }

    private boolean applySprites(double u, double p, double k, double tx0, double ty0,
                                 double L, double T, double R_, double B, double cardFade) {
        double now = U.now();
        if (headerSpriteFlight && live && !chatIsOverlay && hdrInfo == null && fragment != null
                && hdrTries < maxHeaderRetries && now - hdrLastTry >= headerRetryInterval
                && (reverse || flipped || u < 0.6)) {
            hdrTries++;
            hdrLastTry = now;
            Header info = plugin.captureHeader(fragment, container, true);
            if (info != null) {
                trackHeaderBitmaps(info);
                hdrInfo = info;
                hdrViews = info.views;
                hdrAlpha = info.alpha;
                attachHeader(info);
            }
        }
        boolean targetMissing = msgTarget == null || msgTarget.pos == null;
        boolean targetWaiting = msgTarget != null && msgTarget.waitingForExact;
        boolean needsTargetRetry = msgTargetExact == null && (targetMissing || targetWaiting);

        if (needsTargetRetry && msgTries < maxMsgRetries && fragment != null && now - msgLastTry >= msgRetryInterval
                && (reverse || flipped || u < 0.8)) {
            msgTries++;
            msgLastTry = now;
            try {
                double[] finalTranslation = null;
                if (live && stretch && !chatIsOverlay) finalTranslation = new double[]{origTx, origTy};
                Target target = plugin.findMsgTarget(fragment, container, cell, true, finalTranslation);
                if (target != null && target.pos != null) {
                    boolean hadFallback = msgTarget != null && msgTarget.waitingForExact && msgTarget.pos != null;
                    boolean foundExact = target.matchedPreview;
                    acceptMessageTarget(target, now);
                    if (foundExact) {
                        plugin.log("exact message destination found after retry " + msgTries + "; source_message_id="
                                + target.sourceMessageId + " pos=" + target.pos[0] + "," + target.pos[1]
                                + " correction=" + (hadFallback ? "120ms" : "none"));
                    } else if (!hadFallback || msgTries == 1) {
                        plugin.log("message fallback retry " + msgTries + "; waiting_exact=" + target.waitingForExact
                                + " message_id=" + target.messageId + " source_message_id=" + target.sourceMessageId
                                + " pos=" + target.pos[0] + "," + target.pos[1]);
                    }
                }
            } catch (Throwable e) {
                plugin.log("message destination retry " + msgTries + " failed: " + e);
            }
        }
        updateMessageTargetCorrection(now);

        Sprite messageSprite = sprite("msg");
        Sprite nameSprite = sprite("name");
        Sprite avatarSprite = sprite("avatar");

        float[] depth = depthFrame;
        depth[0] = (float) u;
        depth[1] = (float) dim0;
        depth[2] = (float) dimLen;
        depth[3] = (float) C.BG_SCALE;
        depth[4] = (float) p;
        depth[5] = back != null ? back.getTranslationX() : 0f;
        depth[6] = back != null ? back.getTranslationY() : 0f;
        depth[7] = back != null ? back.getAlpha() : 1f;
        View nativeBack = (back != null && !(slideM != null || (slideEmul && slideArmed))) ? back : null;
        NativeMotion.applyDepth(scrim, nativeBack, depth);

        applyAvatarSpriteDirect(avatarSprite, u, k, tx0, ty0);
        applyNameSpriteDirect(nameSprite, u, k, tx0, ty0);
        applyMessageSpriteDirect(messageSprite, u, k, tx0, ty0, cardFade);
        clipMessageSpriteToBounds(messageSprite, L, T, R_, B);
        nativeDepthBackApplied = nativeBack != null;
        return true;
    }

    // ------------------------------------------------------------- the frame

    private void packMorph(double u, double l, double t0, double r, double b, double effectivePreS, double off) {
        double chatEarly = C.CHAT_EARLY;
        double chatEarlyLen = C.CHAT_EARLY_LEN;
        double chatFadeStart = C.CHAT_FADE_START;
        double chatFadeLen = C.CHAT_FADE_LEN;
        if (liveStretchOpen) {
            chatEarlyLen = C.LIVE_STRETCH_CHAT_EARLY_LEN;
            chatFadeStart = 0.06;
            chatFadeLen = C.LIVE_STRETCH_CHAT_FADE_LEN;
        }
        if (!gradientEnabled && !reverse && !flipped) {
            chatEarly = 1.0;
            chatEarlyLen = 0.001;
            chatFadeStart = 0.0;
            chatFadeLen = 0.001;
        }
        double[] vals = {u, l, t0, r, b, w, h, effectivePreS, prePx, prePy, density, C.LIFT_END, C.LIFT_DX, C.LIFT_DY,
                C.EXPAND_START, baseLeft, baseTop, off, w, h, chatEarly, chatEarlyLen, chatFadeStart, chatFadeLen,
                radius, radFull, C.RAD_BUMP_DP * density, C.RAD_CLOSE_MUL, (reverse || flipped) ? 1.0 : 0.0};
        for (int i = 0; i < vals.length; i++) morphIn[i] = (float) vals[i];
    }

    private void apply(double u, double t) {
        Motion nat = plugin.motion;
        nat.beginBatch();
        double off = offX0 != 0.0 ? offX0 * (1.0 - U.smooth(t / 0.3)) : 0.0;
        curU = u;
        if ((reverse || flipped) && cell != null) poll();

        double l = rect[0], t0 = rect[1], r = rect[2], b = rect[3];

        View c = chat;
        double L, T, R_, B, chatAlpha, p, sx, sy, tx, ty, bb, fx0, fy0, fx1, fy1, bl, br, rad;
        if (stretch) {
            if (c == null) throw new IllegalStateException("stretch animation requires a chat view for the DEX morph");
            double effectivePreS = predictiveScaleAt(u);
            if (!morphAttempted) {
                morphAttempted = true;
                packMorph(u, l, t0, r, b, effectivePreS, off);
            }
            morphIn[0] = (float) u;
            morphIn[17] = (float) off;
            if (reverse || flipped) {
                morphIn[28] = 1.0f;
                morphIn[1] = (float) l;
                morphIn[2] = (float) t0;
                morphIn[3] = (float) r;
                morphIn[4] = (float) b;
            } else {
                morphIn[28] = 0.0f;
            }
            morphIn[7] = (float) effectivePreS;
            NativeMotion.computeMorph(c, morphIn, morphOut);
            float[] o = morphOut;
            L = o[0]; T = o[1]; R_ = o[2]; B = o[3]; chatAlpha = o[4]; p = o[5]; sx = o[6]; sy = o[7];
            tx = o[8]; ty = o[9]; bb = o[10]; fx0 = o[11]; fy0 = o[12]; fx1 = o[13]; fy1 = o[14];
            bl = o[15]; br = o[16]; rad = o[17];

            boolean forwardOpen = !(reverse || flipped);
            if (forwardOpen) {
                if (!gradientEnabled) {
                    chatAlpha = 1.0;
                } else {
                    chatAlpha = U.smooth(u / C.OPEN_FADE_LEN);
                }
            } else if (reverse && !flipped) {
                chatAlpha = 1.0;
            }
        } else {
            double s0 = predictiveScaleAt(u);
            fx0 = prePx * (1.0 - s0);
            fy0 = prePy * (1.0 - s0);
            fx1 = fx0 + s0 * w;
            fy1 = fy0 + s0 * h;
            if (sourceCardStretch) {
                double inset = C.CARD_ENDPOINT_INSET_DP * density * U.smooth(u);
                fx0 += inset;
                fy0 += inset;
                fx1 -= inset;
                fy1 -= inset;
            }
            double a = U.outCubic(U.c01(u / C.LIFT_END));
            double liftFactor = sourceCardStretch ? C.CARD_LIFT_FACTOR : 1.0;
            double dx = C.LIFT_DX * liftFactor * density * a;
            double dy = C.LIFT_DY * liftFactor * density * a;
            bl = l - dx;
            double bt = t0 - dy;
            br = r + dx;
            bb = b + dy;
            bl = Math.max(bl, fx0);
            bt = Math.max(bt, fy0);
            br = Math.min(br, fx1);
            bb = Math.min(bb, fy1);
            double x = U.c01((u - C.EXPAND_START) / (1.0 - C.EXPAND_START));
            p = C.EASE.ease(x);
            double[] pLR = edgeProg(bl - fx0, fx1 - br, x, p);
            double[] pTB = edgeProg(bt - fy0, fy1 - bb, x, p);
            L = bl + (fx0 - bl) * pLR[0];
            R_ = br + (fx1 - br) * pLR[1];
            T = bt + (fy0 - bt) * pTB[0];
            B = bb + (fy1 - bb) * pTB[1];
            boolean forwardOpen = !(reverse || flipped);
            if (forwardOpen && !gradientEnabled) {
                chatAlpha = 1.0;
            } else if (forwardOpen) {
                chatAlpha = U.smooth(u / C.OPEN_FADE_LEN);
            } else if (reverse && !flipped) {
                chatAlpha = 1.0;
            } else {
                chatAlpha = Math.min(1.0, C.CHAT_EARLY * U.smooth(u / C.CHAT_EARLY_LEN)
                        + (1.0 - C.CHAT_EARLY) * U.smooth((u - C.CHAT_FADE_START) / C.CHAT_FADE_LEN));
            }
            sx = Math.max(0.02, (R_ - L) / Math.max(1.0, (double) w));
            sy = Math.max(0.005, (B - T) / Math.max(1.0, (double) h));
            tx = L - baseLeft + off;
            ty = T - baseTop;
            rad = radius + (radFull - radius) * p;
            rad += C.RAD_BUMP_DP * density * Math.sin(Math.PI * p);
            if (reverse || flipped) rad *= 1.0 + (C.RAD_CLOSE_MUL - 1.0) * (1.0 - p);
            rad = Math.max(0.0, Math.min(rad, Math.min((R_ - L) / 2.0, (B - T) / 2.0)));
        }
        curBb = bb;
        if (stretch) {
            if (clip.provider != null) clip.set(0.0, 0.0, w, h, rad / Math.max(0.02, sx), rad / Math.max(0.005, sy));
        } else {
            if (!live && off != 0.0) {
                clip.set(0.0, 0.0, w, h, rad / Math.max(0.02, sx), rad / Math.max(0.005, sy));
                nat.apply(c, 1.0, 1.0, off, (double) c.getTranslationY(), 1.0);
            } else {
                clip.set(L, T, R_, B, rad, rad);
            }
        }

        if (live && !chatIsOverlay && !reverse && !flipped) {
            if (!begun) {
                chatAlpha = 0.0;
            } else if (!firstShownFrame) {
                firstShownFrame = true;
                chatAlpha = 0.0;
            }
        }
        double curAlpha = c.getAlpha();
        if (Math.abs(chatAlpha - curAlpha) >= 0.002 || (chatAlpha <= 0.0 && curAlpha != 0.0)
                || (chatAlpha >= 1.0 && curAlpha != 1.0)) {
            c.setAlpha((float) chatAlpha);
        }
        lastChatAlpha = chatAlpha;
        if (chatIsOverlay && live && container != null) {
            try {
                if (container.getAlpha() > 0.001f) {
                    container.setAlpha(0.0f);
                    if (!alphaFixLogged) {
                        alphaFixLogged = true;
                        plugin.log("live chat became visible behind the snapshot; forced hidden again");
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        boolean nativeAuxApplied = false;
        nativeDepthBackApplied = false;
        if (snap != null) {
            double sa = 1.0 - U.smooth((u - C.SNAP_FADE_START) / C.SNAP_FADE_LEN);
            double k = (br - bl) / Math.max(1.0, (r - l));
            double ccx = (l - ix) + cw / 2.0;
            double ccy = (t0 - iy) + ch / 2.0;
            double tx0 = ccx - k * cw / 2.0 + off;
            double ty0 = ccy - k * ch / 2.0;
            if (!sprites.isEmpty()) {
                double spriteK, spriteTx0, spriteTy0;
                if (sourceCardStretch) {
                    spriteK = k;
                    spriteTx0 = tx0;
                    spriteTy0 = ty0;
                } else {
                    spriteK = 1.0;
                    spriteTx0 = (l - ix) + off;
                    spriteTy0 = t0 - iy;
                }
                nativeAuxApplied = applySprites(u, p, spriteK, spriteTx0, spriteTy0, L, T, R_, B, sa);
            }
            if (sa > 0.0 || snapVisible) {
                double sxCard, syCard, cardTx, cardTy;
                if (sourceCardStretch) {
                    sxCard = Math.max(0.02, (R_ - L) / Math.max(1.0, r - l));
                    syCard = Math.max(0.02, (B - T) / Math.max(1.0, b - t0));
                    cardTx = L - sxCard * ix + off;
                    cardTy = T - syCard * iy;
                    if (snapClip != null) {
                        snapClip.set(ix, iy, cw - ix, ch - iy, rad / sxCard, rad / syCard);
                    }
                } else {
                    sxCard = syCard = 1.0;
                    cardTx = l - ix + off;
                    cardTy = t0 - iy;
                    if (snapClip != null) {
                        snapClip.set(ix, iy, cw - ix, ch - iy, radius, radius);
                    }
                }
                nat.apply(snap, sxCard, syCard, cardTx, cardTy, sa);
            }
            snapVisible = sa > 0.0;
        }

        if (!nativeAuxApplied && scrim != null) {
            nat.apply(scrim, 1.0, 1.0, 0.0, 0.0, dim0 * U.smooth(u / dimLen));
        }
        if ((!nativeAuxApplied || !nativeDepthBackApplied) && back != null) {
            double sc = 1.0 - C.BG_SCALE * p;
            nat.apply(back, sc, sc, (double) back.getTranslationX(), (double) back.getTranslationY(), (double) back.getAlpha());
        }
        if (slideM != null || (slideEmul && slideArmed)) {
            double pr = slideP0 + (1.0 - slideP0) * U.smooth(1.0 - u);
            if (slideM != null) {
                try {
                    slideM.invoke(slideObj, Float.valueOf((float) pr));
                } catch (Throwable e) {
                    slideFail(e);
                }
            }
            if (slideEmul && slideArmed) emulateSlide(pr);
        }
        nat.flushBatch();
        if (reverse || chatIsOverlay) raiseOverlays();
    }

    private void slideFail(Throwable e) {
        plugin.log("slide invoke failed, emulating: " + e);
        slideM = null;
        slideEmul = true;
    }

    private void emulateSlide(double pr) {
        View c = backC;
        if (c == null) return;
        double d = density;
        double[] base = slideBase;
        if (base == null) {
            try {
                base = new double[]{c.getTranslationX(), c.getTranslationY(), c.getScaleX(), c.getScaleY(),
                        c.getPivotX(), c.getPivotY(), c.getAlpha()};
                slideBase = base;
            } catch (Throwable e) {
                base = new double[]{0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 1.0};
            }
        }
        double tx = base[0], ty = base[1], sx = base[2], sy = base[3], alpha = base[6];
        double k = 1.0 - U.c01(pr);
        Motion nat = plugin.motion;
        if (slideLite) {
            nat.apply(c, sx, sy, tx + slideSign * slideAmp * d * k, ty, alpha);
        } else {
            double sc = 1.0 - 0.045 * k;
            try {
                c.setPivotX(c.getWidth() / 2.0f);
                c.setPivotY(c.getHeight() / 2.0f);
            } catch (Throwable ignored) {
            }
            nat.apply(c, sx * sc, sy * sc, tx + slideSign * 4.0 * d * k, ty, alpha);
        }
    }

    private void restoreEmulatedSlide() {
        View c = backC;
        if (c == null) return;
        double[] base = slideBase;
        if (base == null) return;
        try {
            c.setTranslationX((float) base[0]);
            c.setTranslationY((float) base[1]);
            c.setScaleX((float) base[2]);
            c.setScaleY((float) base[3]);
            c.setPivotX((float) base[4]);
            c.setPivotY((float) base[5]);
            c.setAlpha((float) base[6]);
        } catch (Throwable ignored) {
        }
    }

    private void softFail(String what, Throwable e) {
        if (soft.add(what)) plugin.log(what + " update failed (animation continues): " + e);
    }

    // ---------------------------------------------------------------- restore

    private void restore(boolean keepChatHidden) {
        if (cell != null && cellHidden) {
            try {
                cell.setVisibility(View.VISIBLE);
            } catch (Throwable ignored) {
            }
        }
        if (live && container != null) {
            try {
                if (cclip != null) cclip.restore();
            } catch (Throwable e) {
                plugin.log("clip restore failed: " + e);
            }
            View c = container;
            try {
                c.setAlpha(keepChatHidden ? 0.0f : 1.0f);
                c.setLayerType(oldLayer, null);
                if (stretch && !chatIsOverlay) {
                    c.setScaleX(1.0f);
                    c.setScaleY(1.0f);
                    c.setTranslationX((float) origTx);
                    c.setTranslationY((float) origTy);
                    try {
                        c.resetPivot();
                    } catch (Throwable e) {
                        c.setPivotX(c.getWidth() / 2.0f);
                        c.setPivotY(c.getHeight() / 2.0f);
                    }
                }
            } catch (Throwable ignored) {
            }
        } else if (container != null) {
            try {
                container.setAlpha(1.0f);
            } catch (Throwable ignored) {
            }
        }
        if (slideEmul && backC != null) restoreEmulatedSlide();
        if (slideM != null) {
            try {
                R.call(slideObj, "prepareFragmentToSlide", Boolean.FALSE, Boolean.FALSE);
            } catch (Throwable e) {
                try {
                    slideM.invoke(slideObj, Float.valueOf(1.0f));
                } catch (Throwable ignored) {
                }
            }
        }
        for (int i = 0; i < hdrViews.size() && i < hdrAlpha.size(); i++) {
            try {
                hdrViews.get(i).setAlpha(hdrAlpha.get(i));
            } catch (Throwable ignored) {
            }
        }
        ViewGroup hostV = host;
        if (hostV != null) {
            ArrayList<View> extra = new ArrayList<View>();
            extra.add(snap);
            extra.add(overlay);
            extra.add(scrim);
            for (Sprite sp : sprites) {
                extra.add(sp.iv);
                extra.add(sp.div);
            }
            for (View v : extra) {
                if (v != null) {
                    try {
                        hostV.removeView(v);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (back != null && backLayered) {
            backLayered = false;
            plugin.releaseLayer(back);
        }
        if (back != null) {
            try {
                back.setScaleX(1.0f);
                back.setScaleY(1.0f);
                try {
                    back.resetPivot();
                } catch (Throwable e) {
                    back.setPivotX(back.getWidth() / 2.0f);
                    back.setPivotY(back.getHeight() / 2.0f);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    void boostLayer() {
        if (boosted || done || !live || !stretch || chatIsOverlay || chat == null) return;
        boosted = true;
        try {
            if (chat.getLayerType() != View.LAYER_TYPE_HARDWARE) {
                chat.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                plugin.log("adaptive: slow frames detected, live chat rendered through a hardware layer");
            }
        } catch (Throwable e) {
            plugin.log("adaptive layer failed: " + e);
        }
    }

    void unflip() {
        if (unflipped || done || live) return;
        unflipped = true;
        ufU = curU;
        plugin.log("reopen at u=" + ufU);
    }

    void finishReopen() {
        if (done) return;
        done = true;
        plugin.releaseGhosts(dialogId);
        restore(false);
        plugin.forget(this);
        release();
    }

    void rearmSlide() {
        if (!slideEmul || backC == null) return;
        slideM = null;
        slideObj = null;
        slideArmed = true;
        try {
            emulateSlide(slideP0);
            plugin.log("slide fallback armed p0=" + slideP0 + " sign=" + slideSign);
        } catch (Throwable e) {
            plugin.log("slide fallback arm failed: " + e);
        }
    }

    private void detachReverseClock() {
        if (reverseClockDetached || done) return;
        ValueAnimator oldVa = va;
        AnimatorSet oldAset = aset;
        UpdateListener oldListener = updateListener;
        if (oldVa == null) return;
        int durationMs;
        try {
            durationMs = Math.max(180, (int) (oldListener.duration * 1000.0));
        } catch (Throwable e) {
            durationMs = 440;
        }
        ValueAnimator reverseVa;
        UpdateListener listener;
        try {
            reverseVa = ValueAnimator.ofFloat(0.0f, 1.0f);
            reverseVa.setDuration(600000);
            reverseVa.setInterpolator(new LinearInterpolator());
            listener = new UpdateListener(this, durationMs);
            listener.wall0 = U.now();
            listener.last = null;
            listener.prevReal = null;
            listener.revAcc = 0.0;
            reverseVa.addUpdateListener(listener);
        } catch (Throwable e) {
            plugin.log("reverse clock create failed; original animator retained: " + e);
            return;
        }

        try { oldVa.removeAllUpdateListeners(); } catch (Throwable ignored) { }
        try { oldVa.removeAllListeners(); } catch (Throwable ignored) { }
        if (oldAset != null) {
            try { oldAset.removeAllListeners(); } catch (Throwable ignored) { }
        }
        try { if (oldAset != null) oldAset.cancel(); } catch (Throwable ignored) { }
        try { oldVa.cancel(); } catch (Throwable ignored) { }

        updateListener = listener;
        va = reverseVa;
        aset = null;
        reverseClockDetached = true;
        try {
            reverseVa.start();
            plugin.log("reverse clock moved to independent ValueAnimator (" + durationMs + "ms; chat kept on stack)");
        } catch (Throwable e) {
            reverseClockDetached = false;
            plugin.log("reverse clock start failed; falling back to existing progress: " + e);
            va = oldVa;
            aset = oldAset;
            updateListener = oldListener;
            try { oldVa.addUpdateListener(oldListener); } catch (Throwable ignored) { }
            try { updateU(curU); } catch (Throwable ignored) { }
        }
    }

    void flip() {
        if (flipped || done || reverse) return;
        flipped = true;
        flipU = begun ? curU : 0.0;
        try {
            UpdateListener l = updateListener;
            if (l != null) {
                l.wall0 = U.now();
                l.revAcc = 0.0;
            }
        } catch (Throwable ignored) {
        }
        plugin.log("flip at u=" + flipU + "; preserving original navigation timeout");
        if (!begun) begin();
        detachReverseClock();
        addTouchGuard();
    }

    private void addTouchGuard() {
        if (touchGuard != null || host == null || chatIsOverlay) return;
        try {
            View g = new View(host.getContext());
            g.setClickable(true);
            g.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent ev) {
                    return true;
                }
            });
            host.addView(g, new FrameLayout.LayoutParams(-1,
                    -1));
            touchGuard = g;
            plugin.log("reverse: chat touches blocked while it collapses");
        } catch (Throwable e) {
            plugin.log("touch guard failed: " + e);
        }
    }

    private void removeTouchGuard() {
        View g = touchGuard;
        touchGuard = null;
        if (g == null) return;
        try {
            ViewGroup p = (ViewGroup) g.getParent();
            if (p != null) p.removeView(g);
        } catch (Throwable ignored) {
        }
    }

    void releaseGhost() {
        if (done) return;
        begin();
        finish();
    }

    void finishReversed() {
        if (done) return;
        done = true;
        try {
            plugin.postUi(new Runnable() {
                @Override
                public void run() {
                    try {
                        closeQuietly();
                    } catch (Throwable ignored) {
                    }
                }
            }, 0);
        } catch (Throwable e) {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        Object frag = fragment;
        Runnable cb = callback;
        View cont = container;
        Object layout = null;
        try {
            layout = frag != null ? R.call(frag, "getParentLayout") : null;
        } catch (Throwable e) {
            layout = null;
        }
        try {
            restore(true);
        } catch (Throwable e) {
            plugin.log("reverse restore failed: " + e);
        } finally {
            plugin.forget(this);
            release();
        }

        if (cb != null) {
            try {
                cb.run();
            } catch (Throwable e) {
                plugin.log("reverse callback failed: " + e);
            }
        }
        try {
            if (layout != null && frag != null) {
                List<?> stack = (List<?>) R.call(layout, "getFragmentStack");
                Object top = (stack != null && stack.size() > 0) ? stack.get(stack.size() - 1) : null;
                boolean sameTop = top == frag;
                if (!sameTop && top != null) {
                    try {
                        sameTop = top.equals(frag);
                    } catch (Throwable e) {
                        sameTop = false;
                    }
                }
                if (sameTop) {
                    plugin.programmaticCloseDepth++;
                    try {
                        R.call(layout, "closeLastFragment", Boolean.FALSE, Boolean.TRUE);
                    } finally {
                        plugin.programmaticCloseDepth = Math.max(0, plugin.programmaticCloseDepth - 1);
                    }
                } else {
                    plugin.log("reverse close skipped: top fragment changed; refusing to close a different chat");
                }
            }
        } catch (Throwable e) {
            plugin.log("quiet close failed: " + e);
        } finally {
            if (cont != null) {
                try {
                    cont.setAlpha(1.0f);
                    cont.invalidate();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    double handoff() {
        double u = curU;
        if (!begun) begin();
        try {
            finish();
        } finally {
            try {
                if (va != null) va.cancel();
            } catch (Throwable ignored) {
            }
        }
        return u;
    }

    boolean ownsView(View v) {
        if (v == null || done) return false;
        if (v == scrim || v == overlay || v == snap) return true;
        for (Sprite sp : sprites) {
            if (v == sp.iv || v == sp.div) return true;
        }
        return false;
    }

    void raiseOverlays() {
        raiseOverlays(false);
    }

    void raiseOverlays(boolean force) {
        if (done || host == null) return;
        ArrayList<View> order = new ArrayList<View>();
        order.add(scrim);
        order.add(overlay);
        order.add(snap);
        for (Sprite sp : sprites) order.add(sp.iv);
        for (Sprite sp : sprites) order.add(sp.div);
        ArrayList<View> valid = new ArrayList<View>();
        try {
            int count = host.getChildCount();
            int lowest = Integer.MAX_VALUE;
            for (View v : order) {
                if (v == null) continue;
                int idx = host.indexOfChild(v);
                if (idx >= 0) {
                    valid.add(v);
                    if (idx < lowest) lowest = idx;
                }
            }
            if (valid.isEmpty()) return;
            // In place when everything above our lowest view is ours or belongs to another running overlay:
            // re-raising against another run only causes a relayout of the whole host on every frame.
            boolean inPlace = true;
            for (int i = lowest; i < count; i++) {
                View child = host.getChildAt(i);
                if (valid.contains(child) || plugin.ownsViewOfOtherRun(this, child)) continue;
                inPlace = false;
                break;
            }
            if (inPlace) {
                // keep our own views in their relative order only when it is actually broken
                int prev = -1;
                boolean ordered = true;
                for (View v : valid) {
                    int idx = host.indexOfChild(v);
                    if (idx < prev) {
                        ordered = false;
                        break;
                    }
                    prev = idx;
                }
                if (ordered) return;
            }
            double now = U.now();
            if (!force && now - lastRaise < 0.12) return;
            lastRaise = now;
        } catch (Throwable ignored) {
        }
        for (View v : valid) {
            try {
                if (host.indexOfChild(v) < 0) host.addView(v);
                else v.bringToFront();
            } catch (Throwable e) {
                try {
                    v.bringToFront();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    String kind() {
        if (ghost) return "ghost";
        if (reverse) return !live ? "close-overlay" : "close-live";
        return chatIsOverlay ? "open-snapshot" : "open-live";
    }

    private void release() {
        removeTouchGuard();
        ArrayList<View> views = new ArrayList<View>();
        views.add(snap);
        views.add(overlay);
        for (Sprite sp : sprites) {
            views.add(sp.iv);
            views.add(sp.div);
        }
        for (View v : views) {
            if (v instanceof ImageView) {
                try {
                    ((ImageView) v).setImageBitmap(null);
                } catch (Throwable ignored) {
                }
            }
        }
        for (Animator anim : new Animator[]{va, aset}) {
            if (anim != null) {
                try {
                    anim.removeAllListeners();
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            if (va != null) va.removeAllUpdateListeners();
        } catch (Throwable ignored) {
        }
        final ArrayList<Bitmap> bms = bitmaps;
        bitmaps = new ArrayList<Bitmap>();
        final Bitmap snapshot = snapshotBitmap;
        snapshotBitmap = null;
        if (!bms.isEmpty()) {
            final Controller ctl = plugin;
            Runnable free = new Runnable() {
                @Override
                public void run() {
                    for (Bitmap b : bms) {
                        try {
                            if (snapshot != null && b == snapshot) ctl.releaseSnapshotBitmap(b);
                            else b.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            };
            try {
                plugin.postUi(free, C.BITMAP_RELEASE_DELAY_MS);
            } catch (Throwable e) {
                free.run();
            }
        }
        va = null;
        aset = null;
        sprites = new ArrayList<Sprite>();
        try {
            if (snapClip != null) snapClip.restore();
        } catch (Throwable ignored) {
        }
        snapClip = null;
        snap = null;
        overlay = null;
        scrim = null;
        hdrViews = new ArrayList<View>();
        hdrAlpha = new ArrayList<Float>();
        hdrInfo = null;
        hdrPending = null;
        cell = null;
        back = null;
        slideObj = null;
        slideM = null;
        backC = null;
        clip = null;
        cclip = null;
        chat = null;
        fragment = null;
        container = null;
        host = null;
        callback = null;
    }

    void abort() {
        if (done) return;
        done = true;
        restore(false);
        plugin.forget(this);
        release();
    }

    void finish() {
        if (done) return;
        done = true;
        Runnable cb = callback;
        restore(false);
        plugin.forget(this);
        release();
        if (cb != null) {
            try {
                cb.run();
            } catch (Throwable e) {
                plugin.log("callback failed: " + e);
            }
        }
    }
}

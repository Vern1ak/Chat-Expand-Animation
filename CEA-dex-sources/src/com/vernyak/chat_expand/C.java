package com.vernyak.chat_expand;

/** Tuning constants (verbatim from the original Python plugin). */
final class C {
    private C() {}

    static final int[] DURATIONS = {440, 580, 760};
    static final double MSG_RETRY_INTERVAL = 0.075;
    static final double CLOSE_FACTOR = 0.85;

    static final double LIFT_END = 0.18;
    static final double LIFT_DX = 10.0;
    static final double LIFT_DY = 7.0;
    static final double EXPAND_START = 0.05;
    static final double ANCHOR = 0.0;
    static final double SNAP_FADE_START = 0.20;
    static final double SNAP_FADE_LEN = 0.45;
    static final double FLY_P = 1.0;
    static final double HEADER_FLY_P = 1.0;
    static final double MSG_CAPTURE_SIDE_DP = 24.0;
    static final double NAME_TARGET_SHIFT_DP = 57.0;
    static final double RAD_BUMP_DP = 26.0;
    static final double RAD_CLOSE_MUL = 1.45;
    static final double GESTURE_RAD_DP = 28.0;
    static final double WALL_MAX = 6.0;
    static final double CHAT_EARLY = 0.60;
    static final double CHAT_EARLY_LEN = 0.10;
    static final double CHAT_FADE_START = 0.10;
    static final double CHAT_FADE_LEN = 0.50;
    static final double NEAR_MIN = 1.0;
    static final double WAIT_MIN = 0.06;
    static final double BG_SCALE = 0.035;
    static final double BG_DIM = 0.20;
    static final double WAIT_MAX = 0.2;
    static final double MAX_FRAME_DT = 0.050;
    static final double[][] INSETS = {{0.0, 0.0}, {10.0, 4.0}, {16.0, 6.0}};
    static final int MAX_VIEWS = 2000;
    static final double SNAPSHOT_SCALE = 0.48;
    static final double OPEN_SNAPSHOT_SCALE = 0.70;
    static final double OPEN_FADE_LEN = 0.10;
    static final double HEADER_RETRY_INTERVAL = 0.065;
    static final int MAX_HEADER_RETRIES = 10;
    static final int MAX_MSG_RETRIES = 28;
    static final long BITMAP_RELEASE_DELAY_MS = 160;

    static final double LIVE_STRETCH_MSG_RETRY_INTERVAL = 0.18;
    static final int LIVE_STRETCH_MAX_MSG_RETRIES = 12;
    static final double LIVE_STRETCH_HEADER_RETRY_INTERVAL = 0.14;
    static final int LIVE_STRETCH_MAX_HEADER_RETRIES = 5;
    static final double LIVE_STRETCH_CHAT_FADE_LEN = 0.34;
    static final double LIVE_STRETCH_EARLY = 0.18;
    static final double LIVE_STRETCH_FADE_START = 0.04;
    static final double LIVE_STRETCH_CHAT_EARLY_LEN = 0.10;
    static final double CARD_LIFT_FACTOR = 0.52;
    static final double CARD_ENDPOINT_INSET_DP = 7.0;

    /** Predictive back: duration relative to a normal close, and strength of the extra ease-out (1 = none). */
    static final double PREDICTIVE_DURATION_FACTOR = 1.0;
    static final double PREDICTIVE_EASE_POWER = 1.4;
    /** Header name crossfade: progress at which the header copy starts to replace the flying name. */
    static final double NAME_CROSSFADE_START = 0.70;

    static final Ease EASE = new Ease(0.3, 0.0, 0.2, 1.0, 64);
}

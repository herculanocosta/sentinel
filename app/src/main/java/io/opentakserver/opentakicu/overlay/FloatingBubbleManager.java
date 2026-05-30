package io.opentakserver.opentakicu.overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import io.opentakserver.opentakicu.R;

/**
 * Floating "Chat-Heads"-style bubble shown on top of other apps via
 * {@code WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY}. Use case:
 * while SENTINEL is in the background (user multitasking) they can still
 * see stream status at a glance and one-tap start / stop without bringing
 * the app to the foreground.
 *
 * Lifecycle is owned by {@link io.opentakserver.opentakicu.Camera2Service}:
 *   - {@link #show(BubbleCallback)} on app background (if pref enabled + overlay perm granted)
 *   - {@link #hide()} on app foreground or service destroy
 *   - {@link #updateState(int, int)} as stream state / TAK status change
 *
 * The bubble itself is a {@link R.layout#floating_bubble} — a circular dark
 * background with the SENTINEL viewfinder mark and a colored status dot.
 */
public class FloatingBubbleManager {

    private static final String TAG = "BubbleMgr";
    /** Finger movement above this counts as a drag, not a tap. ~9 dp at typical density. */
    private static final int TAP_SLOP_PX = 24;
    /** Safe margin from screen edges (px) — keeps the bubble clear of the system nav bar. */
    private static final int EDGE_MARGIN_PX = 16;

    /** Caller interface — invoked when the user taps (NOT drags) the bubble. */
    public interface BubbleCallback {
        void onBubbleTap();
        /** Long-press = "I want to change the source". Default no-op. */
        default void onBubbleLongPress() {}
    }

    private final Context ctx;
    private final WindowManager wm;
    private View bubbleView;
    private View bubbleDot;
    private View bubbleStats;
    private android.widget.TextView bubbleBitrate;
    private android.widget.TextView bubbleFps;
    private BubbleCallback callback;
    private boolean visible = false;
    private WindowManager.LayoutParams params;

    public FloatingBubbleManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    /** Returns true if the overlay permission is granted (always true pre-M). */
    public static boolean hasOverlayPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(ctx);
    }

    public boolean isVisible() { return visible; }

    /**
     * Show the bubble in the top-right area of the screen. Subsequent state changes
     * should go through {@link #updateState(int, int)}.
     */
    public void show(BubbleCallback cb) {
        if (visible || wm == null) return;
        if (!hasOverlayPermission(ctx)) {
            Log.w(TAG, "show(): overlay permission denied, not adding bubble");
            return;
        }
        this.callback = cb;
        try {
            bubbleView = LayoutInflater.from(ctx).inflate(R.layout.floating_bubble, null);
            bubbleDot = bubbleView.findViewById(R.id.bubble_state_dot);
            bubbleStats = bubbleView.findViewById(R.id.bubble_stats);
            bubbleBitrate = bubbleView.findViewById(R.id.bubble_bitrate);
            bubbleFps = bubbleView.findViewById(R.id.bubble_fps);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type, flags, PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            applyDefaultPositionForCurrentOrientation();
            bubbleView.setOnTouchListener(dragTouchListener);

            wm.addView(bubbleView, params);
            visible = true;
            // Default to dim (idle).
            tint(bubbleDot, 0xFF888888);
            Log.d(TAG, "Bubble shown at " + params.x + "," + params.y);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show bubble", e);
            cleanup();
        }
    }

    /**
     * Re-place the bubble for the current device orientation. Called on every config change
     * from {@code Camera2Service.onConfigurationChanged}. We pick a sensible default per
     * orientation (left edge in landscape, top-center in portrait) and clamp the bubble
     * inside the visible screen so it doesn't end up off-screen after rotation.
     */
    public void repositionForCurrentOrientation() {
        if (!visible || bubbleView == null || params == null || wm == null) return;
        applyDefaultPositionForCurrentOrientation();
        try { wm.updateViewLayout(bubbleView, params); } catch (Exception ignored) {}
    }

    /**
     * Decide where the bubble should sit based on the current screen orientation.
     *
     *   - Landscape: left edge, vertically centered (avoids the typical right-side nav bar)
     *   - Portrait:  top-center, just below the status bar
     */
    private void applyDefaultPositionForCurrentOrientation() {
        if (params == null) return;
        int orient = ctx.getResources().getConfiguration().orientation;
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        int bubblePx = dp(56);

        if (orient == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            // Top-center, below the typical status bar height.
            params.x = (screenW - bubblePx) / 2;
            params.y = dp(40);
        } else {
            // Landscape: left edge, vertically centered.
            params.x = EDGE_MARGIN_PX;
            params.y = (screenH - bubblePx) / 2;
        }
    }

    public void hide() {
        if (!visible) return;
        try {
            if (bubbleView != null) wm.removeView(bubbleView);
        } catch (Exception ignored) {}
        cleanup();
    }

    /**
     * Update the bubble's status dot color (e.g. green while streaming, red on error)
     * and (optional) tint the lens icon.
     *
     * @param dotColor ARGB color for the status dot
     * @param iconColor ARGB color for the lens icon ({@code 0} keeps the original white)
     */
    public void updateState(int dotColor, int iconColor) {
        if (!visible || bubbleView == null) return;
        if (bubbleDot != null) tint(bubbleDot, dotColor);
        if (iconColor != 0) {
            View icon = bubbleView.findViewById(R.id.bubble_icon);
            if (icon != null) tint(icon, iconColor);
        }
    }

    /**
     * Refresh the live stats pill next to the bubble. Called once per second from
     * {@code Camera2Service.statsTick} while a stream is active. When all numbers are zero
     * (idle), the pill is hidden so the bubble shrinks back to its round form.
     */
    public void updateStats(long bitrateKbps, long fps, long uploadKbps, boolean congested) {
        if (!visible || bubbleView == null) return;
        boolean haveAny = bitrateKbps > 0 || fps > 0 || uploadKbps > 0;
        if (!haveAny) {
            if (bubbleStats != null) bubbleStats.setVisibility(View.GONE);
            return;
        }
        if (bubbleStats != null) bubbleStats.setVisibility(View.VISIBLE);
        if (bubbleBitrate != null) {
            bubbleBitrate.setText(formatKbps(uploadKbps > 0 ? uploadKbps : bitrateKbps) + (congested ? " !" : ""));
            bubbleBitrate.setTextColor(congested ? 0xFFFFC107 : 0xFFFFFFFF);
        }
        if (bubbleFps != null) {
            bubbleFps.setText(fps + " fps");
        }
        if (bubbleDot != null) {
            tint(bubbleDot, congested ? 0xFFFFC107 : 0xFF00C853);
        }
    }

    /** Format kbps as e.g. "857 kb/s" or "1.4 Mb/s". */
    private static String formatKbps(long kbps) {
        if (kbps >= 1000) {
            return String.format(java.util.Locale.US, "%.1f Mb/s", kbps / 1000.0);
        }
        return kbps + " kb/s";
    }

    /* =========================== Drag handling =========================== */

    private final View.OnTouchListener dragTouchListener = new View.OnTouchListener() {
        private float downX, downY;
        private int initialX, initialY;
        private boolean dragging = false;
        private boolean longPressFired = false;
        private final android.os.Handler longPressHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        private final Runnable longPressRunnable = () -> {
            if (!dragging && callback != null) {
                longPressFired = true;
                callback.onBubbleLongPress();
            }
        };

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    initialX = params.x;
                    initialY = params.y;
                    dragging = false;
                    longPressFired = false;
                    longPressHandler.removeCallbacks(longPressRunnable);
                    longPressHandler.postDelayed(longPressRunnable,
                            android.view.ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (!dragging && (Math.abs(dx) > TAP_SLOP_PX || Math.abs(dy) > TAP_SLOP_PX)) {
                        dragging = true;
                        // The finger moved — cancel the pending long-press.
                        longPressHandler.removeCallbacks(longPressRunnable);
                    }
                    if (dragging) {
                        params.x = initialX + (int) dx;
                        params.y = initialY + (int) dy;
                        try { wm.updateViewLayout(v, params); } catch (Exception ignored) {}
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    longPressHandler.removeCallbacks(longPressRunnable);
                    if (!dragging && !longPressFired) {
                        // Treat as a tap.
                        if (callback != null) callback.onBubbleTap();
                    } else if (dragging) {
                        // Clamp into the visible screen so a vigorous drag can't park the bubble
                        // off-screen, but otherwise leave it where the user dropped it.
                        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
                        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
                        int w = v.getWidth();
                        int h = v.getHeight();
                        if (params.x < 0) params.x = 0;
                        if (params.x + w > screenW) params.x = screenW - w;
                        if (params.y < 0) params.y = 0;
                        if (params.y + h > screenH) params.y = screenH - h;
                        try { wm.updateViewLayout(v, params); } catch (Exception ignored) {}
                    }
                    return true;
            }
            return false;
        }
    };

    /* =========================== helpers =========================== */

    private void cleanup() {
        bubbleView = null;
        bubbleDot = null;
        callback = null;
        params = null;
        visible = false;
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    private void tint(View v, int color) {
        if (v == null || v.getBackground() == null) {
            if (v instanceof android.widget.ImageView) {
                ((android.widget.ImageView) v).setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            }
            return;
        }
        v.getBackground().mutate().setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
    }
}

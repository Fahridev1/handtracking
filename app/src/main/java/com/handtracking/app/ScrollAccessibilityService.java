package com.handtracking.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

/**
 * Menjalankan geseran layar (swipe) atas nama pengguna. Hanya dipakai untuk dispatchGesture();
 * service ini TIDAK membaca isi layar (event diabaikan).
 */
public class ScrollAccessibilityService extends AccessibilityService {
    private static volatile ScrollAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static boolean isRunning() { return instance != null; }

    /**
     * @param dir "up" | "down" | "left" | "right" = arah GERAKAN JARI di layar.
     * @return false bila Accessibility Service belum aktif.
     */
    public static boolean swipe(String dir) {
        ScrollAccessibilityService s = instance;
        if (s == null) return false;
        s.main.post(() -> s.doSwipe(dir));
        return true;
    }

    private void doSwipe(String dir) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float w = dm.widthPixels, h = dm.heightPixels;
        float x0 = w * 0.5f, y0 = h * 0.5f, x1 = x0, y1 = y0;
        long dur = 200;
        switch (dir) {
            case "up":    y0 = h * 0.75f; y1 = h * 0.25f; break;
            case "down":  y0 = h * 0.25f; y1 = h * 0.75f; break;
            case "left":  x0 = w * 0.80f; x1 = w * 0.20f; break;
            case "right": x0 = w * 0.20f; x1 = w * 0.80f; break;
            default: return;
        }
        Path p = new Path();
        p.moveTo(x0, y0);
        p.lineTo(x1, y1);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, dur)).build();
        dispatchGesture(g, null, null);
    }

    @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public boolean onUnbind(android.content.Intent intent) { instance = null; return super.onUnbind(intent); }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* sengaja kosong: tidak membaca layar */ }
    @Override public void onInterrupt() { }
}

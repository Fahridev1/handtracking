package com.handtracking.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service (tipe camera): kamera depan tetap hidup walau aplikasi ditutup/di latar belakang.
 * Frame kecil dikirim ke server Hand Tracking (/process-image?scroll=1); bila server mengembalikan "swipe",
 * geseran layar dijalankan lewat {@link ScrollAccessibilityService}.
 */
public class CameraScrollService extends LifecycleService {
    private static final String TAG = "HTScroll";
    public static final String ACTION_START = "com.handtracking.app.START";
    public static final String ACTION_STOP = "com.handtracking.app.STOP";
    private static final String CHANNEL = "scroll";
    private static final int NOTIF_ID = 42;

    /** Status untuk UI (dibaca ScrollActivity). */
    public static volatile boolean running = false;
    public static volatile String status = "Mati";

    private ExecutorService analyzerExec;
    private ProcessCameraProvider provider;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean mirror = true;
    private String session;
    private long seq = 0;
    private long lastSent = 0, lastSwipeAt = 0, lastNotifAt = 0;
    private int failStreak = 0;
    private String lastNotifText = "";
    private long fpsWindowStart = 0;
    private int fpsCount = 0;
    private int fpsShown = 0;

    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        startInForeground("Menyiapkan kamera…");
        running = true;
        status = "Menyiapkan kamera…";
        session = "app" + randomId(12);
        analyzerExec = Executors.newSingleThreadExecutor();
        startCamera();
        return START_STICKY;
    }

    @Nullable @Override public IBinder onBind(@NonNull Intent intent) { return super.onBind(intent); }

    @Override public void onDestroy() {
        running = false;
        status = "Mati";
        try { if (provider != null) provider.unbindAll(); } catch (Exception ignored) { }
        if (analyzerExec != null) analyzerExec.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------------------------------ kamera
    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
                CameraSelector sel = CameraSelector.DEFAULT_FRONT_CAMERA;
                try {
                    if (!provider.hasCamera(sel)) { sel = CameraSelector.DEFAULT_BACK_CAMERA; mirror = false; }
                } catch (Exception e) { sel = CameraSelector.DEFAULT_BACK_CAMERA; mirror = false; }
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(analyzerExec, this::onFrame);
                provider.unbindAll();
                provider.bindToLifecycle(this, sel, analysis);
                status = "Kamera aktif, menunggu tangan";
                updateNotification(status);
            } catch (Exception e) {
                Log.e(TAG, "Gagal membuka kamera", e);
                status = "Kamera gagal: " + e.getMessage();
                updateNotification(status);
            }
        }, r -> main.post(r));
    }

    // ------------------------------------------------------------------------------------------ per frame
    private void onFrame(@NonNull ImageProxy image) {
        try {
            SharedPreferences p = getSharedPreferences("ht", MODE_PRIVATE);
            int fps = Math.max(2, Math.min(15, p.getInt("fps", 8)));
            long now = SystemClock.elapsedRealtime();
            if (now - lastSent < 1000L / fps) return;     // buang frame: batasi laju kirim (hemat data & baterai)
            lastSent = now;

            Bitmap bmp = image.toBitmap();
            Matrix m = new Matrix();
            m.postRotate(image.getImageInfo().getRotationDegrees());
            if (mirror) m.postScale(-1f, 1f);               // seperti cermin: kanan pengguna = kanan di gambar
            float scale = 256f / Math.max(1, bmp.getWidth() > bmp.getHeight() ? bmp.getHeight() : bmp.getWidth());
            m.postScale(scale, scale);
            Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16 * 1024);
            out.compress(Bitmap.CompressFormat.JPEG, 60, bos);
            if (out != bmp) out.recycle();
            bmp.recycle();
            upload(bos.toByteArray(), p);
        } catch (Throwable t) {
            Log.e(TAG, "Error frame", t);
            status = "Error: " + t.getClass().getSimpleName();
        } finally {
            image.close();
        }
    }

    // ------------------------------------------------------------------------------------------ jaringan
    private void upload(byte[] jpeg, SharedPreferences p) {
        String base = p.getString("url", "").trim();
        if (base.isEmpty()) { status = "Alamat server belum diisi"; return; }
        if (!base.startsWith("http")) base = "https://" + base;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        float sens = p.getInt("sens", 50) / 100f;
        boolean horizontal = p.getBoolean("horizontal", false);
        boolean invert = p.getBoolean("invert", false);
        String q = "/process-image?scroll=1&session=" + session + "&seq=" + (seq++)
                + "&ts=" + SystemClock.elapsedRealtime() + "&sens=" + sens + "&swipe_h=" + horizontal;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + q).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestProperty("Content-Type", "image/jpeg");
            String key = p.getString("apikey", "").trim();
            if (!key.isEmpty()) c.setRequestProperty("X-API-Key", key);
            c.setFixedLengthStreamingMode(jpeg.length);
            try (OutputStream os = c.getOutputStream()) { os.write(jpeg); }
            int code = c.getResponseCode();
            if (code != 200) {
                failStreak++;
                status = code == 401 ? "API key salah (401)" : code == 429 ? "Server membatasi request (429)" : "Server error " + code;
                updateNotification(status);
                backoff();
                return;
            }
            String body = readAll(c.getInputStream());
            failStreak = 0;
            JSONObject o = new JSONObject(body);
            int hands = o.optInt("hand_count", 0);
            countFps();
            status = (hands > 0 ? "Tangan terdeteksi" : "Menunggu tangan") + " · " + fpsShown + " fps";
            String sw = o.optString("swipe", "");
            if (!sw.isEmpty()) handleSwipe(sw, invert);
            updateNotification(status);
        } catch (Exception e) {
            failStreak++;
            status = "Tidak bisa terhubung ke server";
            updateNotification(status);
            backoff();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void handleSwipe(String handDir, boolean invert) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSwipeAt < 700) return;             // pengaman tambahan di sisi aplikasi
        lastSwipeAt = now;
        String d = handDir;
        if (invert) {
            switch (handDir) {
                case "up": d = "down"; break;
                case "down": d = "up"; break;
                case "left": d = "right"; break;
                case "right": d = "left"; break;
                default: break;
            }
        }
        // Tangan naik -> jari menggeser ke atas di layar (video berikutnya di TikTok), dst.
        if (!ScrollAccessibilityService.swipe(d)) status = "Aktifkan Accessibility Service dulu";
    }

    private void backoff() {
        try { Thread.sleep(Math.min(2000, 250L * failStreak)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private void countFps() {
        long now = SystemClock.elapsedRealtime();
        if (fpsWindowStart == 0) fpsWindowStart = now;
        fpsCount++;
        if (now - fpsWindowStart >= 2000) {
            fpsShown = Math.round(fpsCount * 1000f / (now - fpsWindowStart));
            fpsCount = 0;
            fpsWindowStart = now;
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private static String randomId(int len) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------ notifikasi
    private Notification buildNotification(String text) {
        Intent open = new Intent(this, ScrollActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, CameraScrollService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Scroll Tangan", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Scroll Tangan aktif")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Matikan", stopPi)
                .build();
    }

    private void startInForeground(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification(String text) {
        long now = SystemClock.elapsedRealtime();
        if (text.equals(lastNotifText) || now - lastNotifAt < 2000) return;   // jangan membanjiri notifikasi
        lastNotifText = text;
        lastNotifAt = now;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}

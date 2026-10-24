package com.handtracking.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Pengaturan fitur Scroll Tangan: izin, Accessibility Service, server, sensitivitas, dan tombol mulai/berhenti. */
public class ScrollActivity extends Activity {
    private static final int REQ_PERM = 7;
    private SharedPreferences prefs;
    private TextView stateText, a11yText, sensLabel;
    private Button startBtn;
    private EditText urlEt, keyEt;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this, 1000); }
    };

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(8), 0, dp(2));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("ht", MODE_PRIVATE);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(24), dp(18), dp(32));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        col.addView(text("Scroll Tangan", 24, true));
        col.addView(text("Geser tangan terbuka cepat ke atas/bawah untuk menggeser layar (mis. video TikTok berikutnya), " +
                "bahkan saat aplikasi ini ada di latar belakang.", 14, false));

        stateText = text("", 16, true);
        col.addView(stateText);

        col.addView(text("1. Izin kamera & notifikasi", 16, true));
        col.addView(button("Beri izin", v -> askPermissions()));

        col.addView(text("2. Accessibility Service (untuk menggeser layar)", 16, true));
        a11yText = text("", 14, false);
        col.addView(a11yText);
        col.addView(button("Buka pengaturan Aksesibilitas", v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Cari \"Hand Tracking\" lalu aktifkan", Toast.LENGTH_LONG).show();
        }));

        col.addView(text("3. Server", 16, true));
        urlEt = new EditText(this);
        urlEt.setHint("https://domain-anda.com");
        urlEt.setSingleLine(true);
        urlEt.setText(prefs.getString("url", ""));
        col.addView(urlEt);
        keyEt = new EditText(this);
        keyEt.setHint("API key (kosongkan bila server tanpa key)");
        keyEt.setSingleLine(true);
        keyEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyEt.setText(prefs.getString("apikey", ""));
        col.addView(keyEt);

        col.addView(text("4. Kepekaan gerakan", 16, true));
        sensLabel = text("", 14, false);
        col.addView(sensLabel);
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(prefs.getInt("sens", 50));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                prefs.edit().putInt("sens", p).apply();
                sensLabel.setText("Kepekaan: " + p + "%  (tinggi = lebih mudah terpicu, tapi bisa salah picu)");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        col.addView(sb);
        sensLabel.setText("Kepekaan: " + prefs.getInt("sens", 50) + "%  (tinggi = lebih mudah terpicu, tapi bisa salah picu)");

        col.addView(switchRow("Balik arah (tangan naik = geser turun)", "invert", false));
        col.addView(switchRow("Ikut geser kiri/kanan", "horizontal", false));

        startBtn = button("", v -> toggle());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(18);
        col.addView(startBtn, lp);

        col.addView(button("Tes geser layar (3 detik lagi, pindah ke TikTok dulu)", v -> {
            if (!ScrollAccessibilityService.isRunning()) {
                Toast.makeText(this, "Aktifkan Accessibility Service dulu", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, "Geser ATAS dalam 3 detik…", Toast.LENGTH_SHORT).show();
            ui.postDelayed(() -> ScrollAccessibilityService.swipe("up"), 3000);
        }));

        col.addView(text("Tips", 16, true));
        col.addView(text("• Kamera depan dipakai; letakkan HP agar tangan terlihat jelas dan terang.\n" +
                "• Matikan optimasi baterai untuk aplikasi ini agar tidak dimatikan sistem (terutama Xiaomi/Oppo/Vivo/Samsung).\n" +
                "• Layar harus menyala; kamera bisa tertutup sistem saat layar dikunci.\n" +
                "• Data: sekitar 8 frame/detik (±60 KB/detik). Pakai Wi-Fi bila bisa.\n" +
                "• Geser tangan cepat satu kali, lalu tahan sebentar sebelum menggeser lagi.", 13, false));
    }

    private View switchRow(String label, final String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setPadding(0, dp(10), 0, dp(4));
        sw.setOnCheckedChangeListener((CompoundButton c, boolean on) -> prefs.edit().putBoolean(key, on).apply());
        return sw;
    }

    private boolean hasCamera() { return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (!hasCamera()) need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (need.isEmpty()) { Toast.makeText(this, "Izin sudah diberikan", Toast.LENGTH_SHORT).show(); return; }
        requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    private void saveServer() {
        prefs.edit().putString("url", urlEt.getText().toString().trim()).putString("apikey", keyEt.getText().toString().trim()).apply();
    }

    private void toggle() {
        saveServer();
        if (CameraScrollService.running) {
            startService(new Intent(this, CameraScrollService.class).setAction(CameraScrollService.ACTION_STOP));
            return;
        }
        if (!hasCamera()) { askPermissions(); return; }
        if (prefs.getString("url", "").isEmpty()) { Toast.makeText(this, "Isi alamat server dulu", Toast.LENGTH_LONG).show(); return; }
        if (!ScrollAccessibilityService.isRunning())
            Toast.makeText(this, "Accessibility Service belum aktif: swipe belum bisa dijalankan", Toast.LENGTH_LONG).show();
        Intent i = new Intent(this, CameraScrollService.class).setAction(CameraScrollService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { render(); }

    private void render() {
        boolean run = CameraScrollService.running;
        stateText.setText(run ? "● AKTIF — " + CameraScrollService.status : "○ Mati");
        startBtn.setText(run ? "Matikan Scroll Tangan" : "Mulai Scroll Tangan");
        a11yText.setText(ScrollAccessibilityService.isRunning() ? "✔ Aktif" : "✘ Belum aktif");
    }

    @Override protected void onResume() { super.onResume(); render(); ui.postDelayed(refresh, 1000); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresh); saveServer(); }
}

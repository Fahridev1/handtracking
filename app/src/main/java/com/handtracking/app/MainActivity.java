package com.handtracking.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

/** Membungkus halaman /client dari server hand tracking dalam WebView + izin kamera. */
public class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences prefs;
    private PermissionRequest pendingRequest;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("ht", MODE_PRIVATE);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        // Dipanggil dari halaman /client (tombol "Scroll Tangan") untuk membuka pengaturan native.
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void openScroll() { runOnUiThread(() -> startActivity(new Intent(MainActivity.this, ScrollActivity.class))); }
        }, "HTApp");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(req.getResources());
                    } else {
                        pendingRequest = req;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
                    }
                });
            }
        });
        String url = prefs.getString("url", "");
        if (url.isEmpty()) askUrl(); else load(url);
    }

    private void load(String base) {
        if (!base.startsWith("http")) base = "https://" + base;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        web.loadUrl(base + "/client");
    }

    private void askUrl() {
        final EditText et = new EditText(this);
        et.setHint("https://domain-anda.com");
        et.setText(prefs.getString("url", ""));
        new AlertDialog.Builder(this)
            .setTitle("Alamat server Hand Tracking")
            .setMessage("Masukkan URL server (HTTPS) yang menjalankan: python app.py server")
            .setView(et).setCancelable(false)
            .setNeutralButton("Scroll Tangan", (d, w) -> startActivity(new Intent(this, ScrollActivity.class)))
            .setPositiveButton("Buka", (d, w) -> {
                String u = et.getText().toString().trim();
                if (u.isEmpty()) { Toast.makeText(this, "URL kosong", Toast.LENGTH_SHORT).show(); askUrl(); return; }
                prefs.edit().putString("url", u).apply();
                load(u);
            }).show();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (pendingRequest != null) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) pendingRequest.grant(pendingRequest.getResources());
            else pendingRequest.deny();
            pendingRequest = null;
        }
    }

    /** Tombol back: mundur di web; kalau sudah mentok, buka dialog ganti server. */
    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else askUrl();
    }
}

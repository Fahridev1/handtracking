# Hand Tracking Android App
Pembungkus (WebView) untuk https://DOMAIN/client. Kamera HP -> server Python Anda.
Build: push folder ini ke repo GitHub -> tab Actions -> unduh artifact APK.
Atau buka di Android Studio -> Build > Build APK(s).

## Scroll Tangan (BARU) — geser layar/TikTok dengan gerakan tangan, juga di latar belakang
Buka: tombol back di halaman utama -> "Scroll Tangan", atau tombol "Scroll Tangan" di Pengaturan halaman web (dalam APK).

Komponen:
- `CameraScrollService` — foreground service bertipe *camera*: kamera depan tetap hidup walau aplikasi ditutup. Mengirim frame kecil
  (256 px, ±8 fps) ke `/process-image?scroll=1` di server Anda; server mengembalikan `swipe` bila gesture terdeteksi.
- `ScrollAccessibilityService` — menjalankan geseran layar (dispatchGesture). TIDAK membaca isi layar.
- `ScrollActivity` — izin, kepekaan, balik arah, geser kiri/kanan, tes swipe, mulai/berhenti.

Cara pakai:
1. Server harus versi v7 (punya parameter `scroll`). Isi alamat server (dan API key bila ada) di layar Scroll Tangan.
2. Beri izin kamera & notifikasi.
3. Pengaturan > Aksesibilitas > "Hand Tracking" > aktifkan (wajib manual oleh Android; tidak bisa diaktifkan lewat kode).
4. Tekan "Mulai Scroll Tangan", buka TikTok, lalu geser tangan terbuka cepat ke atas = video berikutnya, ke bawah = sebelumnya.

Catatan: kamera di latar belakang hanya boleh jika service dimulai saat aplikasi terlihat (sudah begitu), notifikasi tetap tampil,
dan sebagian HP (Xiaomi/Oppo/Vivo) butuh optimasi baterai dimatikan. Layar yang terkunci biasanya menutup kamera.

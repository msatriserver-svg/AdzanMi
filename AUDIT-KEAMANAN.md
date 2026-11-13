# Audit Keamanan AdzanMi (2026-11-14)

Basis: AdzanPlus v2.2.1 (commit `ce9292b`) oleh adrian463588, lisensi MIT.

## Hal yang sudah aman
- Tidak ada izin `INTERNET` dan tidak ada kode jaringan: lokasi & jadwal tidak pernah dikirim ke mana pun.
- Semua `PendingIntent` memakai `FLAG_IMMUTABLE`.
- Activity alarm, service audio, dan receiver internal (`AlarmReceiver`, `StopAdhanReceiver`, `CelestialAlarmReceiver`) tidak di-export.
- Receiver yang di-export hanya menerima broadcast sistem yang dilindungi (boot, ganti jam/zona, izin exact alarm).
- Rute dari intent/widget ke `MainActivity` dicocokkan dengan daftar putih (`VALID_ROUTES`).
- Suara adzan kustom dipilih pengguna lewat pemilih file sistem (SAF), hanya izin baca.
- `gradle-wrapper.jar` identik dengan wrapper resmi Gradle 9.1.0 (sha256 `76805e32…93f3`).
- Tidak ada rahasia/kunci yang ter-commit di riwayat git.

## Temuan & perbaikan
| # | Tingkat | Temuan | Perbaikan |
|---|---|---|---|
| 1 | Tinggi | APK release ditandatangani dengan **kunci debug Android** yang publik, sehingga siapa pun bisa membuat "update" palsu yang terpasang di atasnya. | Build release kini unsigned; ditandatangani di server dengan kunci AdzanMi sendiri. |
| 2 | Sedang | `allowBackup=true`: data aplikasi ikut cadangan cloud / transfer perangkat. | `allowBackup=false`, aturan ekstraksi data mengecualikan semua domain. |
| 3 | Rendah | Repositori `jitpack.io` terdaftar tapi tidak dipakai (risiko rantai pasok). | Dihapus. |
| 4 | Rendah | Widget receiver yang di-export menerima aksi kustom dari aplikasi lain (bisa dipicu refresh berulang). | Aksi kustom dihapus dari intent-filter (tidak dipakai lewat broadcast). |
| 5 | Info | Build CI tidak memvalidasi wrapper Gradle. | Workflow GitHub memakai `gradle/actions/wrapper-validation`. |

Lainnya (tidak diubah): log debug tidak memuat data pribadi; `play-services-location` (Google) dipakai untuk lokasi.

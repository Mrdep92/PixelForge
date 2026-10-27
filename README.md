# PixelForge

**Your AI Team in a Living Pixel Office**

PixelForge is an Android multi-agent application that runs a living 2D retro pixel office.  
AI agents work together like real office employees — they plan, report for approval, then execute tasks (coding, debugging, auditing, creative work, business planning, etc.).

## Build APK tanpa Android Studio

1. Upload project ini ke repository GitHub (tombol *Add file → Upload files*, atau `git push`).
2. Buka tab **Actions → Build APK → Run workflow** (otomatis jalan juga setiap push).
3. Setelah hijau, buka run tersebut, unduh artifact **PixelForge-debug-apk**, ekstrak, lalu install `app-debug.apk` di HP (izinkan *install from unknown sources*).

## Core Features (Phase 1)

- Living 2D pixel office with idle animations and events
- Multi-agent system with mandatory Approval Gate
- Strict Project Bible for context consistency
- OpenRouter integration with auto model switching
- Voice input
- Background tasks + notifications
- Bubble chat between agents

## Tech Stack

- Kotlin
- Jetpack Compose
- Room
- WorkManager
- Ktor / OkHttp
- Android Keystore for API keys

## How to Build

1. Open this project in **Android Studio** (Hedgehog or newer recommended)
2. Sync Gradle
3. Add your OpenRouter API key in the app settings (or via local.properties for development)
4. Run on emulator or real device (API 26+)

Setelah sync pertama, bisa juga lewat terminal (sekali jalankan `gradle wrapper` bila `gradlew` belum ada):

```bash
./gradlew assembleDebug
```

The APK will be at:
`app/build/outputs/apk/debug/app-debug.apk`

## Fitur v0.5 (hemat kuota)

- **Single-call Architect**: satu panggilan LLM menghasilkan rencana + tugas terstruktur (bukan N panggilan laporan).
- **Pipeline Coder → Auditor**: Auditor mereview hasil Coder; maksimal 1 putaran perbaikan.
- **Estimasi panggilan** ditampilkan sebelum Approve.
- Multi-provider LLM (OpenRouter + OmniRoute), fallback otomatis, progres tersimpan saat limit.
- Workspace + GitHub CI/debug loop.

## Status

Sudah jalan: Approval Gate, single-call plan, pipeline berurutan Coder→Auditor, OpenRouter fallback, Keystore, Room chat, kantor pixel.

Berikutnya: Skills system (Superpowers / grillme), Memory Bank, kurangi ketergantungan Project Bible, context condensation, voice.

## Project Structure

```
app/src/main/java/com/pixelforge/
├── agents/          # Agent roles & orchestrator
├── core/            # App, DI, utilities
├── data/            # Room, repositories
├── domain/          # Models & use cases
├── office/          # 2D office logic & animations
├── routing/         # OpenRouter / OmniRoute client
└── ui/              # Compose screens & components
```

## Analisis Perilaku Agent & Saran Perbaikan (27 Okt 2026)

Dibuat setelah membaca kode dan mencocokkannya dengan tangkapan layar Debugger yang menulis
"Root cause diperbaiki" padahal aplikasi sendiri menampilkan "⚠ Debugger tidak mengubah file apa pun".
Pembagian: **[Terverifikasi]** = dicek langsung di kode/repo/API GitHub; **[Asumsi]** = belum dibuktikan.

### A. Mengapa error itu muncul

1. **[Terverifikasi] `AgentLoop.run` menerima `DONE` walau `EDIT` di balasan yang sama gagal.**
   Semua blok dalam satu balasan dieksekusi berurutan; hasil tool dikumpulkan di `results`, tetapi bila ada
   blok `DONE` loop langsung `return` dan `results` (termasuk `ERROR: ... dilindungi`) dibuang tanpa pernah
   dilihat model. Di layar: putaran 2 = `✏️ build.yml, ✅ selesai`. Teks "Root cause diperbaiki" adalah
   *klaim model sendiri* (`Result.summary`), bukan hasil yang diverifikasi aplikasi. Tes yang ada
   (`failedEditIsReportedBackToModel`, `existingWorkflowCannotBeEdited`) menaruh EDIT dan DONE di balasan
   **terpisah**, sehingga kasus ini tidak tercakup.
2. **[Terverifikasi] Perbaikan yang paling wajar justru diblokir.** `ProtectedFiles.violation` menolak edit
   workflow yang sudah ada, sedangkan `DEBUG_SYSTEM` (MainViewModel) tidak memberi tahu Debugger batasan itu
   dan tidak ada jalur resmi untuk perbaikan jenis "izin eksekusi / langkah workflow".
3. **[Terverifikasi] `runCi` berhenti total bila `changed` kosong** (`setCi(CiState()); return`): tidak ada
   percobaan ulang, tidak ada eskalasi ke pengguna, hanya satu pesan peringatan.
4. **[Terverifikasi] `GitHubClient.commitFiles` menulis semua file dengan mode `100644`.** `gradlew` yang
   masuk lewat aplikasi tidak pernah executable. Di repo `Mrdep92/about-muslim`, `gradlew` memang bermode
   `100644` (dicek via API). Ini penyebab nyata `Permission denied` (exit 126) pada workflow yang memanggil `./gradlew`.
5. **[Terverifikasi] Janji "aplikasi menyediakan gradlew/wrapper" belum ada implementasinya.** Satu-satunya
   kemunculan di kode hanyalah teks prompt (`ToolProtocol.PROMPT`) dan `ProtectedFiles`. `Workspace` menyimpan
   `Map<String, String>` (teks), jadi `gradle-wrapper.jar` (biner) tidak bisa dibawa. Agent dilarang membuat
   wrapper, padahal tidak ada pihak lain yang membuatnya.

### B. Yang belum terbukti

- **[Asumsi]** Isi log error CI yang sebenarnya. Log disimpan di blob storage Azure yang tidak bisa diakses
  dari lingkungan analisis ini, jadi hanya status per langkah yang terbaca.
- **[Terverifikasi parsial]** Repo `about-muslim` punya dua workflow (`build.yml` dan `main.yml`) yang jalan
  bersamaan. `main.yml` sudah punya langkah `chmod +x ./gradlew` tetapi tetap gagal di `gradle assembleDebug`.
  Artinya ada penyebab kedua yang belum diketahui, dan diagnosis "root cause = izin gradlew" dari Debugger
  baru terbukti **sebagian** (untuk `build.yml` saja).
- **[Asumsi]** Seberapa sering model gratis mengirim `DONE` bersama edit yang gagal. Perlu diukur lewat log.

### C. Saran perbaikan (urut prioritas)

**P0 — kecil, deterministik, bisa diuji unit**
1. `AgentLoop`: bila di putaran yang sama ada hasil `ERROR`, **tolak `DONE`**, kirim semua hasil kembali ke
   model beserta catatan "DONE ditolak karena ada error". Tes baru: EDIT-gagal + DONE dalam satu balasan.
2. Jangan percaya ringkasan model. Tambahkan status hasil `FIXED / NO_CHANGE / BLOCKED`. Untuk Debugger,
   `NO_CHANGE` ditampilkan dengan **alasan sebenarnya dari aplikasi** (mis. "workflow dilindungi"), bukan teks klaim model.
3. `GitHubClient`: fungsi murni `modeFor(path)` → `100755` untuk `gradlew` dan `*.sh`, selain itu `100644`.
   Tes unit untuk fungsi ini. Ini memperbaiki akar masalah izin tanpa menyentuh workflow.

**P1 — butuh keputusan desain**
4. Sediakan kerangka proyek Android yang **sudah terbukti**. Opsi paling murah: workflow template memakai
   `gradle/actions/setup-gradle` dan perintah `gradle assembleDebug` (tanpa wrapper), seperti workflow
   PixelForge sendiri yang buildnya sukses. Opsi lain: bundel wrapper (termasuk jar) sebagai aset dan kirim
   dengan mode benar.
5. Ganti larangan total edit workflow dengan **usulan perubahan yang butuh persetujuan** di tab Approvals
   (tampilkan diff), atau allowlist langkah aman (`chmod`, versi JDK). Ini melonggarkan kebijakan keamanan, jadi
   pengguna harus memutuskan.
6. Debugger: sebelum memutuskan, beri ringkasan struktur (daftar file + mode) dan error pertama dari log;
   bila `BLOCKED`, berhenti dengan pesan yang jelas dan tombol tindakan, bukan diam.

**P2 — kebersihan**
7. Tambahkan pengecekan duplikasi workflow di repo target (dua workflow pada push yang sama).
8. Sinkronkan README/`docs/DESIGN.md` dengan kode sekarang (kantor 2D dan Project Bible sudah dihapus).

### D. Cara membuktikan perbaikan selesai
- P0-1 dan P0-3: tes unit hijau di CI (`gradle testDebugUnitTest`).
- P0-2: skenario layar yang sama harus menampilkan status `BLOCKED` dengan alasan, bukan "diperbaiki".
- P1-4: satu proyek uji baru harus mencapai build hijau di GitHub Actions tanpa campur tangan manual.

## License

Personal use first. Future premium release planned.

Built with the Superpowers methodology.

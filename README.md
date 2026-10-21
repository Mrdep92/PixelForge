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

## Fitur v0.2

- **Multi-provider LLM**: OpenRouter + OmniRoute (atau endpoint OpenAI-compatible lain). Kena limit → otomatis pindah provider; kalau semuanya gagal, progres disimpan dan bisa **Lanjutkan** dari tab Approvals.
- **Workspace**: agent menulis file (blok `=== FILE: path ===`) ke folder project di app; lihat di tab Files.
- **GitHub sebagai wadah kode**: file di-commit ke repo, GitHub Actions membuild, Debugger membaca log error dan memperbaiki otomatis (maks 3x).

## Status

Sudah jalan: Approval Gate asli (Architect → laporan agent → Approve/Reject → eksekusi paralel), OpenRouter dengan fallback model, API key terenkripsi, chat & Project Bible tersimpan di Room, kantor pixel dengan gerak idle.

Belum: voice input, Revise di Approval Gate, WorkManager, kompresi konteks, animasi sprite pixel-art asli.

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

## License

Personal use first. Future premium release planned.

Built with the Superpowers methodology.

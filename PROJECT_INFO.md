# NoveLA Project Overview & Architecture Guide

Inisiasi dan panduan teknis lengkap untuk pengembangan proyek **NoveLA**.

---

## 📌 General Information

- **Project Name**: NoveLA (`my.novela` / `my.noveldokusha`)
- **Type**: Android Application (Web Novel & EPUB/FB2 Reader)
- **License**: GPL-3.0
- **Version**: `1.6.1` (versionCode `39`)
- **Min Android SDK**: API 26 (Android 8.0 Oreo)
- **Target & Compile SDK**: API 37
- **Java Version**: Java 21 (`JvmTarget.JVM_21`)
- **Kotlin Version**: 2.4.20
- **Android Gradle Plugin (AGP)**: 9.4.0

---

## 🛠️ Technology Stack & Libraries

### UI & Architecture
- **UI Framework**: Jetpack Compose (Material 3) + ViewBinding / XML fragments for legacy navigation components.
- **Dependency Injection**: Hilt `2.60.1` + KSP `2.3.11`
- **Asynchronous & State**: Kotlin Coroutines `1.9.0` (StateFlow, SharedFlow)
- **Navigation**: AndroidX Navigation Component (`navigation-fragment-ktx`, `navigation-ui-ktx`)

### Data & Persistence
- **Database**: Room `2.8.4` (SQLite)
- **Serialization**: Kotlinx Serialization JSON `1.11.0`, Gson `2.14.0`, SnakeYAML `2.7`

### Networking & Web Scraping
- **HTTP Client**: OkHttp `5.5.0` (Brotli compression, Logging Interceptor)
- **HTML Extraction**: Jsoup `1.23.1`, Readability4J `1.0.8`
- **Cloudflare Bypass**: WebView JS Bridge for Turnstile challenge resolution

### Extensions & Tooling
- **Script Engine**: LuaJ (`luaj-jse` `3.0.1`) for external source plugins hosted on GitHub (`HnDK0/external-sources`)
- **Media & Audio**: Media3 ExoPlayer `1.11.1` + Android Text-to-Speech (TTS) engine with background playback and mini-player
- **Image Viewing**: SubsamplingScaleImageView for manga/image-based novel chapters
- **Image Loading**: Coil `3.6.2` (OkHttp network loader)
- **Code/Text Editing**: Sora Editor `0.24.6`
- **Background Tasks**: WorkManager `2.11.2` (integrated with Hilt)

---

## 🏗️ Project Architecture & Module Structure

Proyek ini menggunakan arsitektur **Multi-Module Gradle** modularized by feature & tooling layers:

```
NoveLA/
├── app/                        # Entry point aplikasi (MainActivity, Hilt AppEntryPoint, Routes)
├── core/                       # Core models, utilities, base classes
├── coreui/                     # Design system, Material 3 Compose theme, reusable UI components
├── data/                       # Repositories & Data sources implementation
├── scraper/                    # Built-in novel scrapers, parsing engine, Lua script module
├── networking/                 # OkHttp setup, network clients, interceptors
├── navigation/                 # Navigation graphs & routing handlers
├── strings/                    # Localization strings (20+ languages)
│
├── build-logic/                # Custom Gradle Convention Plugins
│   └── convention/             # AppConfig, Hilt, Compose, and Library configuration plugins
│
├── tooling/                    # Service & utility modules
│   ├── application_workers/    # Background WorkManager tasks (auto-backup, pre-downloads)
│   ├── backup_create/          # Backup generator
│   ├── backup_restore/         # Restore engine
│   ├── epub_importer/          # EPUB importer to local database
│   ├── epub_parser/            # EPUB parser engine
│   ├── local_database/         # Room DB schema, DAOs, and migrations
│   ├── local_source/           # Local file system novel sources
│   ├── novel_migration/        # Novel source migration engine
│   ├── text_to_speech/         # TTS service & Media3 integration
│   └── text_translator/        # Translation domain interfaces & implementations
│       ├── domain/             # Translation abstractions
│       └── translator_nop/     # FOSS Translation provider (Google Gemini, Google Translate, OpenAI)
│
└── features/                   # UI Feature Modules
    ├── catalogExplorer/        # Source discovery & source catalogs
    ├── chaptersList/           # Chapter list view & batch operations
    ├── databaseExplorer/       # SQLite database inspector tool
    ├── extensions/             # Lua extension plugin manager & repo installer
    ├── globalSourceSearch/     # Multi-source search across all plugins/sources
    ├── historyExplorer/        # Reading history screen
    ├── libraryExplorer/        # Bookshelf / Library management (categories, filters)
    ├── reader/                 # Reader screen (scroll/page, parallel translate, manga mode)
    ├── settings/               # App configuration (UI, Translation, Backup, TTS)
    ├── sourceExplorer/         # Detailed view per novel source
    └── webview/                # Cloudflare Turnstile bypass WebView container
```

---

## ⚡ Core Workflows & Capabilities

1. **Lua Extensions Manager**:
   - Plugins diparsing menggunakan engine LuaJ.
   - Sumber plugin dapat ditambah dari repository eksternal (misal: `HnDK0/external-sources`).
2. **In-Reader Translation Engine**:
   - 4 Backend: Google Translate (Enhanced), Google Translate (Simple), Google Gemini API, OpenAI-compatible APIs (OpenAI, DeepSeek, OpenRouter, Ollama).
   - Mendukung rotasi API key (round-robin) dan *Parallel Mode* (teks asli & terjemahan bersisian).
3. **Text-to-Speech (TTS)**:
   - Terintegrasi dengan Media3 ExoPlayer.
   - Background audio playback, control kecepatan/pitch, dukungan tombol headset Bluetooth, timer tidur.
4. **Offline Caching & Backup**:
   - Batch download bab roman.
   - Ekspor/Impor granular backup dengan skema Room Database.
5. **Local Library**:
   - Import file EPUB dan FB2 lokal.
   - Feature ekspor bab novel ke format EPUB.

---

## 🚀 Build & Development Commands

| Command | Usage |
|---|---|
| `./gradlew assembleDebug` | Build APK Debug (`NoveLA_v1.6.1-debug.apk`) |
| `./gradlew assembleRelease` | Build APK Release (membutuhkan signing configuration) |
| `./gradlew test` | Jalankan seluruh unit tests di semua modul |
| `./gradlew lint` | Jalankan static code analysis (Lint) |
| `./gradlew -PsplitByAbi=true assembleDebug` | Build APK terpisah per ABI (`arm64-v8a`, `armeabi-v7a`) |

---

## 🔑 Key Conventions for Developers

1. **Modul Barus / Dependencies**: Selalu gunakan Version Catalog `gradle/libs.versions.toml` untuk menambahkan dependency baru.
2. **Convention Plugins**: Manfaatkan plugin kustom di `build-logic/convention` (`noveldokusha.android.library`, `noveldokusha.android.compose`, dll.).
3. **Jetpack Compose**: Reusable UI component harus ditempatkan di `:coreui`.
4. **Multilingual Support**: Tambahkan string baru pada modul `:strings` agar dapat diterjemahkan ke 20+ bahasa yang didukung.

# Catatan Analisis Optimasi Luaj-JSE & Opsi Migrasi Native Lua (JNI)

Dokumen ini berisi analisis teknis mendalam mengenai titik kemacetan (*bottlenecks*) pada implementasi **Luaj-JSE** saat ini di **NoveLA**, rekomendasi optimasi, serta evaluasi opsi migrasi ke **Native Lua (C-Lua / LuaJIT) via JNI**.

---

## 🔍 BAGIAN 1: Area Optimasi pada Luaj-JSE Saat Ini

Berdasarkan audit kode pada modul `:scraper` ([`LuaSourceLoader.kt`](file:///data/user/0/com.termux/files/home/projects/NoveLA/scraper/src/main/java/my/noveldokusha/scraper/LuaSourceLoader.kt), [`LuaSourceAdapter.kt`](file:///data/user/0/com.termux/files/home/projects/NoveLA/scraper/src/main/java/my/noveldokusha/scraper/LuaSourceAdapter.kt)), terdapat beberapa *bottleneck* utama yang dapat dioptimalkan:

### 1. Pembentukan Alokasi Objek Binary yang Sangat Boros GC
- **Masalah**: Pada fungsi `responseTableBinary` ([`LuaSourceLoader.kt:518-531`](file:///data/user/0/com.termux/files/home/projects/NoveLA/scraper/src/main/java/my/noveldokusha/scraper/LuaSourceLoader.kt#L518-L531)), data biner diekspos ke Lua dengan cara melakukan iterasi setiap byte dan membuat `LuaTable` berindeks 1-based:
  ```kotlin
  for (i in bytes.indices) bodyTable.set(i + 1, LuaValue.valueOf(bytes[i].toInt() and 0xFF))
  ```
  Jika gambar/file berukuran **1 MB** diunduh, kode ini akan membuat **1.000.000 objek `LuaInteger` & entri tabel**, yang memicu GC Pauses parah pada Android JVM.
- **Rekomendasi Optimasi**: Gunakan `LuaValue.valueOf(bytes)` (string/byte array wrapper) atau bungkus `ByteArray` dalam `LuaUserdata` khusus agar data biner tidak perlu di-unpack menjadi entri tabel individual.

### 2. Lock Mutex Per-Source Serialization (Thread Bottleneck)
- **Masalah**: `Luaj-JSE` tidak thread-safe. Pada `LuaSourceAdapter.kt`, setiap panggilan seperti `getCatalogList`, `getChapterText`, `getBookTitle` dibungkus dalam `mutex.withLock`.
  Jika pengguna membuka beberapa bab sekaligus dari sumber yang sama (atau pre-downloading background), pemrosesan akan terantri secara sekuensial.
- **Rekomendasi Optimasi**: Implementasikan **Lua VM Pool** (Instance Pooling) atau buat `Globals` per-coroutine context untuk sumber yang sering digunakan, sehingga panggilan baca dapat berjalan secara paralel tanpa saling mengunci.

### 3. Konversi Data JSON & Struct Rekursif yang Lambat
- **Masalah**: Panggilan `json_parse` mengonversi JSON Java ke `LuaTable` secara rekursif via `convertToLua()`. Untuk respon JSON katalog yang besar (ratusan item), alokasi memori heap JVM melonjak.
- **Status Implementation**: **[SELESAI DITERAPKAN]** Menggunakan `wrapJsonElement` (lazy-proxy wrapper untuk `JsonObject` dan `JsonArray`) sehingga entri JSON diparsing secara *on-demand* saat diakses oleh Lua (`LuaSourceLoader.kt`).

### 4. Cache Prototype In-Memory Tambahan
- **Masalah**: Saat ini caching bytecode (`.lbc`) dilakukan pada level disk file. Saat adapter dieviksi dari LRU cache `LuaSourceLoader`, file `.lbc` dibaca ulang dari storage disk dan di-deserialize.
- **Status Implementation**: **[SELESAI DITERAPKAN]** Ditambahkan `inMemoryBytecodeCache` (`LruCache<String, ByteArray>(50)`) pada `LuaEngine` untuk pemuatan ulang instant (0ms).

### 5. Concurrent CSS Selector Cache & API Instance Reuse
- **Status Implementation**: **[SELESAI DITERAPKAN]** Ditambahkan `cssSelectorCache` (`ConcurrentHashMap<String, Evaluator>`) untuk memangkas regex parse CSS selector Jsoup, serta `sharedApiFunctions` untuk mendaftar 35+ fungsi API tanpa alokasi objek berulang (`LuaSourceLoader.kt`).

---

## ⚡ BAGIAN 2: Analisis & Pendapat Penggunaan Native Lua / JNI

Memindahkan engine Lua dari **Luaj-JSE (Pure Java)** ke **Native Lua (C-Lua 5.4 / LuaJIT via JNI)** merupakan keputusan arsitektural besar. Berikut adalah analisis komparatif lengkap:

### 📊 Perbandingan Arsitektur: Luaj-JSE vs Native Lua (JNI)

| Meter / Parameter | Luaj-JSE (Saat Ini) | Native Lua via JNI (C-Lua 5.4 / LuaJIT) |
|---|---|---|
| **Kecepatan Eksekusi** | Sedang (Pure Java interpreter 5.2) | 🚀 **Sangat Cepat** (Native C speed / JIT JIT compiler 5x-50x lebih cepat) |
| **Alokasi Memori JVM** | Tinggi (Banyak objek `LuaValue` pada Heap Java) | 📉 **Rendah** (Memori dikelola C Heap via `malloc`, bebas GC Pause Android) |
| **Standar Bahasa Lua** | Terbatas pada dialek Lua 5.2 | **Modern Lua 5.4** (Bitwise ops, `<const>`, `<close>`, `utf8` native) atau **LuaJIT 2.1** |
| **Ukuran & Kompleksitas APK** | Murni kode Java/Kotlin (Tanpa `.so` binaries) | Bertambah (+1-3 MB library `.so` per ABI: `arm64-v8a`, `armeabi-v7a`) |
| **Keamanan Crash** | **Sangat Aman**: Error Lua $\rightarrow$ Java Exception (Dapat di-catch) | ⚠️ **Beresiko**: Crash pada C (SIGSEGV) langsung mematikan aplikasi Android secara total |
| **Overhead JNI Boundary** | Tidak ada (Semua eksekusi di dalam JVM) | Ada overhead saat Lua sering memanggil balik fungsi Java (`http_get`, Jsoup) |
| **Multi-Threading** | Butuh Sync Java Mutex per instance | Ringan & cepat membuat multiple `lua_State*` terisolasi per thread |

---

### 💡 Pendapat & Rekomendasi Arsitektural

#### 1. Kapan Harus Bertahan pada Luaj-JSE (Dengan Optimasi)?
Jika bottleneck utama aplikasi adalah **Network I/O** (waktu tunggu respon HTTP dari server novel) dan bukan eksekusi algoritma berat di Lua, maka **Luaj-JSE sudah lebih dari cukup**. 
Dengan mengaplikasikan optimasi pada Bagian 1 (terutama perbaikan `responseTableBinary` dan instance pooling), performa Luaj-JSE dapat meningkat hingga 300% tanpa menambah kompleksitas build C/C++ NDK.

#### 2. Kapan Harus Migrasi ke Native Lua (JNI)?
Migrasi ke Native Lua (menggunakan library JNI seperti **JNLua** atau binding C++ kustom) sangat direkomendasikan jika:
- Plugin Lua banyak melakukan manipulasi string skala besar, kompresi/dekompresi kustom, enkripsi berat, atau dekode data biner.
- Ingin memanfaatkan **Lua 5.4** penuh atau **LuaJIT**.
- Ingin menghemat konsumsi RAM pada perangkat kelas bawah (*low-end devices*).

#### 🛠️ Strategi Transisi (Jika Memilih JNI):
Jika kelak diputuskan untuk menggunakan Native Lua:
1. Pakai **C-Lua 5.4** (bukan LuaJIT) agar kompatibel dengan seluruh ABI Android (termasuk 64-bit ARM64) secara stabil.
2. Porting fungsi parsing HTML (`html_select`) langsung ke library C seperti **Gumbo HTML parser** di layer C++ untuk menghindari *JNI ping-pong overhead* antara C-Lua dan Jsoup Java.
3. Bungkus panggilan native JNI dengan *signal handler* dan validasi ketat agar error C tidak memicu Segmentation Fault yang mematikan aplikasi.

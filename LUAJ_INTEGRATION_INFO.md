# Panduan Detil Integrasi & Penggunaan Luaj-JSE pada NoveLA

Dokumen ini berisi informasi teknis mendalam mengenai penggunaan library **Luaj-JSE** (`org.luaj:luaj-jse:3.0.1`) pada aplikasi **NoveLA**.

---

## 📌 1. Informasi General & Dependensi

- **Library**: `org.luaj:luaj-jse:3.0.1`
- **Konfigurasi Version Catalog**: `gradle/libs.versions.toml` (`luajvm = { module = "org.luaj:luaj-jse", version.ref = "luajvm" }`)
- **Modul Pengguna**: `:scraper` (`scraper/build.gradle.kts`)
- **Proguard Rule**: `-keep class org.luaj.vm2.** { *; }` (`app/proguard-rules.pro`)
- **Tujuan Utama**: Mengeksekusi script plugin sumber novel eksternal (web scrapers, pencarian catalog, parser bab, manga mode, stream video) yang ditulis dalam bahasa Lua secara dinamis tanpa perlu melakukan kompilasi ulang kode aplikasi Android.

---

## 🛡️ 2. Keamanan & Sandboxing (`createLuaSandboxGlobals`)

Untuk mencegah plugin Lua mengeksekusi kode berbahaya (Remote Code Execution / RCE) atau mengakses sistem berkas perangkat, engine Lua berjalan di dalam песочница (sandbox) yang sangat ketat:

### Standard Globals yang Dihapus (Set to `NIL`)
- `luajava` — Menutup akses refleksi langsung ke JVM Java class/object.
- `io` — Menutup akses pembacaan/penulisan berkas lokal.
- `load`, `loadfile`, `loadstring`, `dofile`, `require`, `package` — Verifikasi agar script tidak dapat memuat file luar atau mel Melakukan dynamic code loading di luar kendali app.
- `debug` — Menghilangkan fungsionalitas debug internal Lua.

### Pengamanan OS Library
Pustaka `os` dipangkas sehingga hanya menyisakan fungsi waktu yang aman (`os.time()`). Fungsi berisiko berikut dihapus:
- `os.execute`, `os.exit`, `os.getenv`, `os.rename`, `os.remove`, `os.tmpname`.

### Perlindungan Network & Path Traversal
1. **SSRF Guard (`isSsrfSafe`)**: Setiap permintaan HTTP dari Lua diperiksa. Alamat `loopback` (`127.0.0.1`, `10.0.2.2`), `site-local` (LAN private IP `192.168.x.x`, `10.x.x.x`), `link-local`, dan `any-local` diblokir secara otomatis.
2. **Extension ID Sanitization (`isValidExtensionId`)**: Mencegah serangan path traversal (`..`, `/`, `\`) pada nama berkas plugin.

---

## ⚡ 3. Optimasi Performa & Bytecode Caching (`loadScriptChunk`)

Untuk menghindari penundaan *cold start* saat memuat puluhan plugin Lua (yang biasanya memakan waktu 1.5–2 detik untuk kompilasi teks), NoveLA mengimplementasikan sistem kcache bytecode:

1. **Serialisasi Bytecode (`compileLuaBytecode`)**: Script Lua mentah dikompilasi menjadi bytecode biner (`.lbc`) menggunakan `DumpState.dump()`.
2. **Penyimpanan Berkas Cache**: Disimpan pada direktori `context.filesDir/lua_bytecode`.
3. **Format Nama File**: `SHA256(luaCode)-3.0.1.lbc`. Tag versi LuaJ (`3.0.1`) dimasukkan dalam nama file untuk secara otomatis meng-invalidasi cache jika engine Lua di-upgrade.
4. **Pembersihan Cache Otomatis (`pruneBytecodeCache`)**: Setiap kali siklus pemuatan sumber selesai (`beginBytecodeCacheCycle()`), file `.lbc` yatim (script yang telah dihapus atau diperbarui) akan dibersihkan dari disk.

---

## 🧵 4. Concurrency & Thread Safety (`LuaSourceAdapter`)

> [!WARNING]
> Class `Globals` dan eksekusi VM pada `Luaj-JSE` **TIDAK thread-safe**. Pemanggilan bersamaan (*concurrent calls*) dari beberapa coroutine ke instance Lua state yang sama dapat merusak memory VM dan menyebabkan crash JVM.

### Mekanisme Serialisasi
- **Coroutine Mutex**: Setiap instance `LuaSourceAdapter` membungkus eksekusi fungsi Lua menggunakan `kotlinx.coroutines.sync.Mutex` (`mutex.withLock`).
- **ThreadLocal State**:
  - `luaEngine.currentSourceId`: Mengikat ID sumber yang sedang berjalan ke thread eksekusi saat ini untuk keperluan tagging OkHttp request.
  - `luaEngine.forceNetworkFlag`: Mengontrol bypass cache memory TTL saat melakukan fetch catalog.

---

## 🔌 5. API Bridge: Fungsi Java yang Disediakan untuk Lua Script

Engine menginjeksikan berbagai fungsi pembantu ke dalam environment global Lua script (`registerApi`):

### 🌐 Networking
- `http_get(url, config)`: Mengirim HTTP GET/HEAD. Mendukung memory TTL cache (2s default), penanganan charset, mode binary, header kustom, dan timeout.
- `http_post(url, body, config)`: Mengirim HTTP POST. Otomatis mendeteksi `Content-Type` (`application/json` atau `form-urlencoded`).
- `http_get_batch(items, config)`: Menjalankan HTTP GET dalam jumlah banyak secara paralel via `Dispatchers.IO`, mengembalikan array sesuai urutan permintaan.

### 🍪 Cookie & Local Preferences
- `get_cookies(url)` / `set_cookies(url, cookiesTable)`: Sinkronisasi dengan OkHttp `CookieJar`.
- `get_preference(key)` / `set_preference(key, val)`: Akses ke Android `SharedPreferences`.
- `get_localStorage(url, key)`: JSON Storage berbasis domain.

### 📄 HTML Parsing & DOM Wrapper (Jsoup Integration)
- `html_parse(html)`: Mengurai HTML menjadi struktur objek.
- `html_select(html_or_element, css_selector)`: Query CSS Selector.
- `html_select_first(html_or_element, css_selector)`: Mengambil elemen pertama hasil selector.
- `html_attr(html_or_element, selector, attr_name)`: Mengambil atribut elemen.
- `html_text(html_or_element)`: Mengambil teks bersih dari elemen.
- `html_remove(html, selector...)`: Menghapus elemen penumpuk/iklan.

> [!TIP]
> **Optimasi Memori Jsoup**: Elemen DOM di-wrap langsung sebagai `LuaUserdata` (`__element`) dengan metatable `__index` lazy getter. Ini menghindari pembentukan string HTML berulang saat melakukan query berantai.

### 🔐 Cryptography & Encoding
- `aes_decrypt(cipherText, key, iv)`: Enkripsi AES/CBC/PKCS5Padding.
- `base64_encode(str)` / `base64_decode(str)`
- `url_encode(str)` / `url_encode_charset(str, charset)` (Mendukung pengodean GBK).
- `url_resolve(baseUrl, relativeUrl)`

### 🛠️ String & JSON Utilities
- `json_parse(jsonStr)` / `json_stringify(table)`
- `regex_match(str, regex)` / `regex_replace(str, regex, replacement)`
- `string_clean(str)`: Normalisasi Unicode NFKC + manipulasi spasi.
- `string_trim`, `string_split`, `string_starts_with`, `string_ends_with`, `unescape_unicode`.
- `sleep(ms)`: Penundaan eksekusi untuk menghindari rate-limit.
- `show_error(title, message, authUrl)`: Menampilkan dialog error kustom pada UI aplikasi.
- `log_info(msg)` / `log_error(msg)`: Logging via Timber.

---

## 📜 6. Kontrak Fungsi yang Wajib Disediakan oleh Script Lua

Plugin sumber novel Lua diwajibkan menyediakan fungsi-fungsi berikut agar dapat diproses oleh `LuaSourceAdapter`:

| Nama Fungsi Lua | Deskripsi / Return Value |
|---|---|
| `getCatalogList(page)` | Mengembalikan daftar novel populer/terbaru (`PagedList<BookResult>`). |
| `getCatalogSearch(page, query)` | Pencarian novel berdasarkan kata kunci. |
| `getBookTitle(bookUrl)` | Mengambil judul novel dari halaman detail. |
| `getBookCoverImageUrl(bookUrl)` | Mengambil URL gambar sampul novel. |
| `getBookDescription(bookUrl)` | Mengambil deskripsi/sinopsis novel. |
| `getBookGenres(bookUrl)` | Mengembalikan array genre novel. |
| `getBookRating(bookUrl)` | Mengambil nilai rating novel. |
| `getBookStatus(bookUrl)` | Mengambil status novel (Ongoing/Completed). |
| `getBookLastUpdate(bookUrl)` | Mengambil tanggal update terakhir. |
| `getChapterList(bookUrl)` / `parsePage(bookUrl, page)` | Mengambil daftar bab novel. |
| `getChapterText(chapterUrl)` / `getVideoList(chapterUrl)` | Mengambil teks isi bab novel (atau daftar stream video). |
| `getFilterList()` / `getCatalogFiltered(page, filters)` | *(Opsional)* Dukungan filter katalog tingkat lanjut. |
| `getSettingsSchema()` | *(Opsional)* Skema pengaturan kustom plugin. |
| `getUserAgentPreset()` / `cf_options` | *(Opsional)* Konfigurasi User-Agent dan bypass Cloudflare. |

---

## 🧪 7. Pengujian (Unit Tests)

Seluruh komponen `luaj-jse` diuji secara komprehensif pada paket `:scraper` (`src/test/java/my/noveldokusha/scraper/`):

- `LuaBytecodeCacheTest.kt`: Menguji pembuatan, loading, dan keabsahan file `.lbc`.
- `LuaEngineHttpBatchTest.kt`: Menguji eksekusi `http_get_batch` secara paralel.
- `LuaEngineHttpGetCacheTest.kt`: Menguji TTL cache HTTP dan pencabutan saat Cloudflare clearance.
- `LuaSourceLoaderElementColonTest.kt`: Menguji wrapper `Jsoup` DOM element.
- `ExtensionIdValidationTest.kt`: Menguji sanitasi ID plugin dari serangan Path Traversal.

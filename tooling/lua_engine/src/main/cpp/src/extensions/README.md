# Lua Android Native Extensions

Direktori ini berisi pustaka dan modul native pihak ketiga yang diintegrasikan langsung ke dalam runtime Lua untuk keperluan aplikasi Android (seperti NoveLA/Shosetsu):

## 1. JSON (`src/extensions/json/`)
- **Pustaka Sumber**: [DaveGamble/cJSON](https://github.com/DaveGamble/cJSON)
- **Lisensi**: MIT License
- **Fungsi Global**: `json.encode(table)`, `json.decode(string)`

## 2. Regular Expressions (`src/extensions/pcre2/`)
- **Pustaka Sumber**: [PCRE2 (Perl Compatible Regular Expressions 2)](https://github.com/PCRE2Project/pcre2) 8-bit native C dengan UTF-8 & UCP (Unicode Properties) support.
- **Fungsi Global (tanpa `require`)**:
  - `regex_match(str, pat)`: Menghasilkan tabel string hasil match (kompatibel penuh dengan Kotlin/Java `Regex.findAll`).
  - `regex_replace(str, pat, repl)`: Mengganti substring sesuai pola regex (kompatibel penuh dengan `Regex.replace` termasuk `(?i)`, non-greedy quantifiers `.*?`, dan `$1` group backreferences).
- **Modul `regex`**:
  - `regex.test(str, pat)`
  - `regex.match(str, pat)` / `regex.find_all(str, pat)`
  - `regex.replace(str, pat, repl)`
  - `regex.find(str, pat, [init])`
  - `regex.split(str, pat, [limit])`

## 3. Cryptography & Encoding Utilities (`src/extensions/util/`)
- **AES-128-CBC**: [kokke/tiny-AES-c](https://github.com/kokke/tiny-AES-c) (Public Domain / The Unlicense)
- **Base64**: [jwerle/b64.c](https://github.com/jwerle/b64.c) (MIT License)
- **Fungsi Global (tanpa `require`)**:
  - `base64_encode(string)`
  - `base64_decode(string)`
  - `url_encode(string)`
  - `url_encode_charset(string, charset)`
  - `aes_decrypt(ciphertext_base64, key_16b, iv_16b)` dengan verifikasi otomatis unpadding PKCS#7 / PKCS#5.

# Native HTML Parser Extension (Lexbor)

Ekstensi parser HTML5 native berkinerja tinggi untuk NoveLA menggunakan engine Lexbor C99 (v3.0.1).

## Fitur & Kompatibilitas
- **Full DOM Tree & CSS Selectors**: Query elemen cepat menggunakan sintaks CSS selector standar (`doc:select("div.content > p")`, `doc:selectFirst(...)`).
- **Antarmuka NoveLA & Shosetsu Parity**:
  - `html_parse(html_str)`: Mengembalikan objek Document.
  - Objek Document:
    - `doc:select(css)`: Mengembalikan daftar elemen (`Elements`).
    - `doc:selectFirst(css)` / `doc:select_first(css)`: Mengembalikan elemen pertama atau `nil`.
    - `doc:title()` atau `doc.title`: Judul dokumen.
    - `doc:body()`, `doc:head()`, `doc:root()`.
    - `doc:html()` atau `doc.html`, `doc:text()`.
  - Objek Element:
    - `el:select(css)`, `el:selectFirst(css)`.
    - `el:attr("name")` atau `el["name"]` atau properti langsung seperti `el.href`, `el.src`, `el.id`, `el.className`.
    - `el:hasAttr("name")`, `el:hasClass("name")`.
    - `el:text()` atau `el.text`: Konten teks rekursif.
    - `el:ownText()` atau `el.own_text`: Teks langsung milik elemen (tanpa anak).
    - `el:html()` atau `el.html`: Inner HTML.
    - `el:outerHtml()` atau `el.outerHtml`: Outer HTML.
    - `el:tagName()` atau `el.tagName`: Nama tag (lowercase).
    - `el:parent()`, `el:children()`, `el:child(i)`, `el:childrenSize()`.
    - `el:nextElementSibling()`, `el:previousElementSibling()`.
    - `el:remove()`.
  - Objek Elements (Daftar Elemen):
    - `list:first()`, `list:last()`.
    - `list:size()`, `list:length()`, `#list`.
    - `list:isEmpty()`, `list:is_empty()`.
    - `list:get(i)` atau `list[i]`.
    - `list:attr("name")`: Atribut dari elemen pertama.
    - `list:text()`: Gabungan teks seluruh elemen.
    - `list:html()`, `list:outerHtml()`.
    - `list:select(css)`: Sub-query CSS pada setiap elemen.
    - `list:forEach(fn)` / `list:each(fn)`.
- **Zero Memory Leaks & Safe GC**: Pointer dokumen diikat ke Lua uservalue dari setiap elemen anak sehingga dokumen tidak akan di-free sebelum semua referensi elemen anak dilepas oleh Lua GC.

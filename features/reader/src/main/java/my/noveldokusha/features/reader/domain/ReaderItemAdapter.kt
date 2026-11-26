package my.noveldokusha.features.reader.domain

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.Paint
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import timber.log.Timber
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import coil3.load
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import my.noveldokusha.core.AppFileResolver
import my.noveldokusha.core.utils.inflater
import my.noveldokusha.core.utils.refererFor
import my.noveldokusha.reader.R
import my.noveldokusha.reader.databinding.ActivityReaderListItemBodyBinding
import my.noveldokusha.reader.databinding.ActivityReaderListItemDividerBinding
import my.noveldokusha.reader.databinding.ActivityReaderListItemErrorBinding
import my.noveldokusha.reader.databinding.ActivityReaderListItemGoogleTranslateAttributionBinding
import my.noveldokusha.reader.databinding.ActivityReaderListItemImageBinding
import my.noveldokusha.reader.databinding.ActivityReaderListItemPaddingBinding
import my.noveldokusha.reader.databinding.ActivityReaderListItemProgressBarBinding
import my.noveldokusha.reader.databinding.ActivityReaderListItemSpecialTitleBinding
import my.noveldokusha.reader.databinding.ActivityReaderListItemTitleBinding
import my.noveldokusha.reader.databinding.ActivityReaderListItemTranslateAttributionBinding
import my.noveldokusha.reader.databinding.ActivityReaderListItemTranslatingBinding

internal class ReaderItemAdapter(
    private val ctx: Context,
    list: List<ReaderItem>,
    private val bookUrl: String,
    private val currentTextSelectability: () -> Boolean,
    private val currentFontSize: () -> Float,
    private val currentLineHeight: () -> Float,
    private val currentParagraphSpacing: () -> Float,
    private val currentLetterSpacing: () -> Float,
    private val currentParagraphIndent: () -> Boolean = { false },
    private val currentParagraphFirstLetterBold: () -> Boolean = { false },
    private val currentTypeface: () -> Typeface,
    private val currentTypefaceBold: () -> Typeface,
    private val currentTextJustify: () -> Boolean,
    private val currentTextHyphenation: () -> Boolean,
    private val currentTextBold: () -> Boolean,
    private val currentTextItalic: () -> Boolean,
    private val currentTextUnderline: () -> Boolean,
    private val currentTextShadow: () -> Boolean,
    private val currentTextSmooth: () -> Boolean,
    private val currentMarginLeft: () -> Float,
    private val currentMarginRight: () -> Float,
    private val currentMarginTop: () -> Float,
    private val currentMarginBottom: () -> Float,
    private val currentParallelEnabled: () -> Boolean,
    private val currentParallelOrder: () -> String,
    private val onChapterStartVisible: (chapterUrl: String) -> Unit,
    private val onChapterEndVisible: (chapterUrl: String) -> Unit,
    private val onReloadReader: () -> Unit,
    private val onRetryChapter: (chapterIndex: Int) -> Unit,
    private val onOpenChapterInBrowser: (url: String) -> Unit,
    private val onClick: () -> Unit,
    private val currentTextColor: () -> String = { "" },
    private val currentManualHighlight: () -> HighlightPosition? = { null },
) : ArrayAdapter<ReaderItem>(ctx, 0, list) {
    private val appFileResolver = AppFileResolver(ctx)
    override fun getCount() = super.getCount() + 2
    override fun getItem(position: Int): ReaderItem = when (position) {
        0 -> topPadding
        count - 1 -> bottomPadding
        else -> super.getItem(position - 1)!!
    }

    fun getFirstVisibleItemIndexGivenPosition(firstVisiblePosition: Int): Int =
        when (firstVisiblePosition) {
            in 1 until (count - 1) -> firstVisiblePosition - 1
            0 -> 0
            count - 1 -> count - 1
            else -> -1
        }

    fun fromPositionToIndex(position: Int): Int = when (position) {
        in 1 until (count - 1) -> position - 1
        else -> -1
    }

    fun fromIndexToPosition(index: ItemIndex): Int = when (index) {
        in 0 until super.getCount() -> index + 1
        else -> -1
    }

    private val topPadding = ReaderItem.Padding(chapterIndex = Int.MIN_VALUE)
    private val bottomPadding = ReaderItem.Padding(chapterIndex = Int.MAX_VALUE)

    override fun hasStableIds(): Boolean = true

    override fun getItemId(position: Int): Long {
        if (position == 0) return Long.MIN_VALUE
        if (position == count - 1) return Long.MAX_VALUE
        val item = super.getItem(position - 1)!!
        return when (item) {
            is ReaderItem.Position -> (item.chapterIndex.toLong() shl 32) or (item.chapterItemPosition.toLong() and 0xFFFFFFFFL)
            is ReaderItem.BookStart -> (item.chapterIndex.toLong() shl 32) or 1L
            is ReaderItem.BookEnd -> (item.chapterIndex.toLong() shl 32) or 2L
            is ReaderItem.Divider -> (item.chapterIndex.toLong() shl 32) or 3L
            is ReaderItem.Progressbar -> (item.chapterIndex.toLong() shl 32) or 4L
            is ReaderItem.Translating -> (item.chapterIndex.toLong() shl 32) or 5L
            is ReaderItem.Error -> (item.chapterIndex.toLong() shl 32) or 6L
            is ReaderItem.GoogleTranslateAttribution -> (item.chapterIndex.toLong() shl 32) or 7L
            is ReaderItem.TranslateAttribution -> (item.chapterIndex.toLong() shl 32) or 8L
            else -> item.chapterIndex.toLong()
        }
    }

    override fun getViewTypeCount(): Int = 12
    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ReaderItem.Body -> 0
        is ReaderItem.Image -> 1
        is ReaderItem.BookEnd -> 2
        is ReaderItem.BookStart -> 3
        is ReaderItem.Divider -> 4
        is ReaderItem.Error -> 5
        is ReaderItem.Padding -> 6
        is ReaderItem.Progressbar -> 7
        is ReaderItem.Title -> 8
        is ReaderItem.Translating -> 9
        is ReaderItem.GoogleTranslateAttribution -> 10
        is ReaderItem.TranslateAttribution -> 11
    }

    private fun viewTranslateAttribution(convertView: View?, parent: ViewGroup): View {
        val bind = when (convertView) {
            null -> ActivityReaderListItemGoogleTranslateAttributionBinding.inflate(parent.inflater, parent, false).also { it.root.tag = it }
            else -> ActivityReaderListItemGoogleTranslateAttributionBinding.bind(convertView)
        }
        return bind.root
    }

    private fun viewTranslateAttributionNew(item: ReaderItem.TranslateAttribution, convertView: View?, parent: ViewGroup): View {
        val bind = when (convertView) {
            null -> ActivityReaderListItemTranslateAttributionBinding.inflate(parent.inflater, parent, false).also { it.root.tag = it }
            else -> ActivityReaderListItemTranslateAttributionBinding.bind(convertView)
        }
        bind.attributionText.text = when (item.provider) {
            "gemini" -> "Powered by Gemini"
            else -> "Powered by Google Translate"
        }
        return bind.root
    }

    private fun viewBody(item: ReaderItem.Body, convertView: View?, parent: ViewGroup): View {
        val bind = when (convertView) {
            null -> ActivityReaderListItemBodyBinding.inflate(parent.inflater, parent, false).also { it.root.tag = it }
            else -> ActivityReaderListItemBodyBinding.bind(convertView)
        }

        val parallelEnabled = currentParallelEnabled() && item.textTranslated != null

        // ponytail: helper — apply 6 text-formatting features to a TextView
        fun applyBodyFormatting(textView: TextView) {
            val base = currentTypeface()
            val style = when {
                currentTextBold() && currentTextItalic() -> Typeface.BOLD_ITALIC
                currentTextBold() -> Typeface.BOLD
                currentTextItalic() -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            textView.typeface = if (style == Typeface.NORMAL) base else Typeface.create(base, style)
            textView.paintFlags = if (currentTextUnderline()) textView.paintFlags or Paint.UNDERLINE_TEXT_FLAG else textView.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            if (currentTextShadow()) textView.setShadowLayer(1f, 1f, 1f, 0x66000000.toInt()) else textView.setShadowLayer(0f, 0f, 0f, 0)
            textView.paint.isAntiAlias = currentTextSmooth()
            textView.justificationMode = if (currentTextJustify()) 1 else 0 // ponytail: TextView.TEXT_JUSTIFICATION_MODE_INTER_WORD/NONE unresolved on compileSdk37; literals are API26-safe
            textView.hyphenationFrequency = if (currentTextHyphenation()) Layout.HYPHENATION_FREQUENCY_FULL else Layout.HYPHENATION_FREQUENCY_NONE
            // ponytail: 0.5em first-line indent (Foliate default; setTextIndent doesn't exist in this SDK; LeadingMarginSpan.Standard = indent of first line only)
            if (currentParagraphIndent()) {
                val span = LeadingMarginSpan.Standard((textView.textSize * 0.5f).toInt(), 0)
                val text = textView.text
                if (text is Spannable) {
                    text.setSpan(span, 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    textView.text = SpannableStringBuilder(text).apply {
                        setSpan(span, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            if (currentParagraphFirstLetterBold()) {
                val text = textView.text
                // ponytail: isLetter() = Unicode letter (CJK/arabic/cyrillic OK); skips quotes/dashes/digits/emoji
                val firstLetterIndex = text.indexOfFirst { it.isLetter() }
                if (firstLetterIndex >= 0) {
                    if (text is Spannable) {
                        text.setSpan(StyleSpan(Typeface.BOLD), firstLetterIndex, firstLetterIndex + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        text.setSpan(RelativeSizeSpan(1.15f), firstLetterIndex, firstLetterIndex + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        textView.text = SpannableStringBuilder(text).apply {
                            setSpan(StyleSpan(Typeface.BOLD), firstLetterIndex, firstLetterIndex + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(RelativeSizeSpan(1.15f), firstLetterIndex, firstLetterIndex + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }
        }

        if (parallelEnabled) {
            val orderTranslationFirst = currentParallelOrder() == "TRANSLATION_FIRST"

            val primaryText = if (orderTranslationFirst) item.textTranslated ?: item.text else item.text
            val secondaryText = if (orderTranslationFirst) item.text else item.textTranslated ?: item.text

            bind.bodyTranslated.text = primaryText
            bind.bodyTranslated.textSize = currentFontSize()
            applyBodyFormatting(bind.bodyTranslated)
            bind.bodyTranslated.updateTextSelectability()
            bind.bodyTranslated.setLineSpacing(0f, currentLineHeight())
            bind.bodyTranslated.letterSpacing = currentLetterSpacing()
            applyTextColor(bind.bodyTranslated, currentTextColor())

            bind.bodyOriginal.text = secondaryText
            bind.bodyOriginal.textSize = currentFontSize() * 0.85f
            applyBodyFormatting(bind.bodyOriginal)
            bind.bodyOriginal.updateTextSelectability()
            bind.bodyOriginal.setLineSpacing(0f, currentLineHeight())
            bind.bodyOriginal.letterSpacing = currentLetterSpacing()
            applyTextColor(bind.bodyOriginal, currentTextColor())
            bind.bodyOriginal.visibility = View.VISIBLE
        } else {
            bind.bodyTranslated.text = item.textToDisplay
            bind.bodyTranslated.textSize = currentFontSize()
            applyBodyFormatting(bind.bodyTranslated)
            bind.bodyTranslated.updateTextSelectability()
            bind.bodyTranslated.setLineSpacing(0f, currentLineHeight())
            bind.bodyTranslated.letterSpacing = currentLetterSpacing()
            applyTextColor(bind.bodyTranslated, currentTextColor())

            applyBodyFormatting(bind.bodyOriginal)
            bind.bodyOriginal.visibility = View.GONE
        }

        bind.root.background = getItemReadingStateBackground(item)
        val paddingVertical = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            currentParagraphSpacing(),
            ctx.resources.displayMetrics
        ).toInt()
        val marginLeft = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, currentMarginLeft(), ctx.resources.displayMetrics).toInt()
        val marginRight = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, currentMarginRight(), ctx.resources.displayMetrics).toInt()
        val marginTop = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, currentMarginTop(), ctx.resources.displayMetrics).toInt()
        val marginBottom = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, currentMarginBottom(), ctx.resources.displayMetrics).toInt()
        bind.bodyTranslated.setPadding(marginLeft, marginTop + paddingVertical, marginRight, marginBottom + paddingVertical)
        bind.bodyOriginal.setPadding(marginLeft, marginTop + paddingVertical, marginRight, marginBottom + paddingVertical)

        when (item.location) {
            ReaderItem.Location.FIRST -> onChapterStartVisible(item.chapterUrl)
            ReaderItem.Location.LAST -> onChapterEndVisible(item.chapterUrl)
            else -> run {}
        }
        return bind.root
    }

    private fun viewImage(item: ReaderItem.Image, convertView: View?, parent: ViewGroup): View {
        val bind = when (convertView) {
            null -> ActivityReaderListItemImageBinding.inflate(parent.inflater, parent, false).also { it.root.tag = it }
            else -> ActivityReaderListItemImageBinding.bind(convertView)
        }

        bind.image.updateLayoutParams<ConstraintLayout.LayoutParams> {
            dimensionRatio = "1:${item.image.yrel}"
        }

        bind.image.tag = null

        val imageModel = appFileResolver.resolvedBookImagePath(bookUrl = bookUrl, imagePath = item.image.path, isCover = false)
        Timber.d("viewImage called imageModel=%s path=%s", imageModel, item.image.path)

        // ponytail: Referer для прямых URL — чтобы серверы-источники не блокировали загрузку
        val referer = (imageModel as? String)
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let(::refererFor)

        bind.image.load(imageModel) {
            scale(coil3.size.Scale.FIT)
            size(1024)
            if (!referer.isNullOrEmpty()) httpHeaders(NetworkHeaders.Builder().set("Referer", referer).build())
            listener(onError = { _,_ ->
                Timber.d("viewImage: load error for path=%s", item.image.path)
                if (bind.image.tag == null && item.image.path.startsWith("http")) {
                    bind.image.tag = true
                    val proxyUrl = "https://images.weserv.nl/?url=${android.net.Uri.encode(item.image.path)}"
                    Timber.d("viewImage: trying proxy url=%s", proxyUrl)
                    bind.image.load(proxyUrl) {
                        scale(coil3.size.Scale.FIT)
                        size(1024)
                        error(R.drawable.ic_baseline_error_outline_24)
                    }
                }
            })
        }

        when (item.location) {
            ReaderItem.Location.FIRST -> onChapterStartVisible(item.chapterUrl)
            ReaderItem.Location.LAST -> onChapterEndVisible(item.chapterUrl)
            else -> run {}
        }
        return bind.root
    }

    private fun viewBookEnd(convertView: View?, parent: ViewGroup): View {
        val bind = when (convertView) {
            null -> ActivityReaderListItemSpecialTitleBinding.inflate(parent.inflater, parent, false).also { it.root.tag = it }
            else -> ActivityReaderListItemSpecialTitleBinding.bind(convertView)
        }
        bind.specialTitle.updateTextSelectability()
        bind.specialTitle.text = ctx.getString(R.string.reader_no_more_chapters)
        bind.specialTitle.typeface = currentTypefaceBold()
        applyTextColor(bind.specialTitle, currentTextColor())
        return bind.root
    }

    private fun viewBookStart(convertView: View?, parent: ViewGroup): View {
        val bind = when (convertView) {
            null -> ActivityReaderListItemSpecialTitleBinding.inflate(parent.inflater, parent, false).also { it.root.tag = it }
            else -> ActivityReaderListItemSpecialTitleBinding.bind(convertView)
        }
        bind.specialTitle.updateTextSelectability()
        bind.specialTitle.text = ctx.getString(R.string.reader_first_chapter)
        bind.specialTitle.typeface = currentTypefaceBold()
        applyTextColor(bind.specialTitle, currentTextColor())
        return bind.root
    }

    private fun viewProgressbar(convertView: View?, parent: ViewGroup): View {
        val bind = when (convertView) {
            null -> ActivityReaderListItemProgressBarBinding.inflate(parent.inflater, parent, false).also { it.root.tag = it }
            else -> ActivityReaderListItemProgressBarBinding.bind(convertView)
        }
        return bind.root
    }

    private fun viewTranslating(item: ReaderItem.Translating, convertView: View?, parent: ViewGroup): View {
        val bind = when (convertView) {
            null -> ActivityReaderListItemTranslatingBinding.inflate(parent.inflater, parent, false).also { it.root.tag = it }
            else -> ActivityReaderListItemTranslatingBinding.bind(convertView)
        }
        bind.text.text = context.getString(R.string.translating_from_lang_a_to_lang_b, item.sourceLang, item.targetLang)
        return bind.root
    }

    private fun viewDivider(convertView: View?, parent: ViewGroup): View {
        val bind = when (convertView) {
            null -> ActivityReaderListItemDividerBinding.inflate(parent.inflater, parent, false).also { it.root.tag = it }
            else -> ActivityReaderListItemDividerBinding.bind(convertView)
        }
        return bind.root
    }

    private fun viewError(item: ReaderItem.Error, convertView: View?, parent: ViewGroup): View {
        val bind = when (convertView) {
            null -> ActivityReaderListItemErrorBinding.inflate(parent.inflater, parent, false).also { it.root.tag = it }
            else -> ActivityReaderListItemErrorBinding.bind(convertView)
        }
        bind.error.updateTextSelectability()
        bind.error.text = item.text
        applyTextColor(bind.error, currentTextColor())
        bind.reloadButton.setOnClickListener { onRetryChapter(item.chapterIndex) }
        if (item.chapterUrl.isNotBlank() || item.authUrl != null) {
            bind.openInBrowserButton.visibility = View.VISIBLE
            bind.openInBrowserButton.setOnClickListener { onOpenChapterInBrowser(item.authUrl ?: item.chapterUrl) }
        } else {
            bind.openInBrowserButton.visibility = View.GONE
        }
        return bind.root
    }

    private fun viewPadding(convertView: View?, parent: ViewGroup): View {
        val bind = when (convertView) {
            null -> ActivityReaderListItemPaddingBinding.inflate(parent.inflater, parent, false).also { it.root.tag = it }
            else -> ActivityReaderListItemPaddingBinding.bind(convertView)
        }
        return bind.root
    }

    private fun viewTitle(item: ReaderItem.Title, convertView: View?, parent: ViewGroup): View {
        val bind = when (convertView) {
            null -> ActivityReaderListItemTitleBinding.inflate(parent.inflater, parent, false).also { it.root.tag = it }
            else -> ActivityReaderListItemTitleBinding.bind(convertView)
        }
        bind.titleTranslated.updateTextSelectability()
        bind.root.background = getItemReadingStateBackground(item)
        bind.titleTranslated.text = item.textToDisplay
        bind.titleTranslated.typeface = currentTypefaceBold()
        applyTextColor(bind.titleTranslated, currentTextColor())
        bind.titleOriginal.visibility = View.GONE
        return bind.root
    }

    private val currentReadingAloudDrawable by lazy {
        AppCompatResources.getDrawable(context, R.drawable.translucent_current_reading_text_background)
    }

    private fun TextView.updateTextSelectability() {
        val selectableText = currentTextSelectability()
        setTextIsSelectable(selectableText)
        if (selectableText) {
            // Добавляем пункт "Поиск в браузере" в меню выделения текста
            if (customSelectionActionModeCallback == null) {
                customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
                    override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                        menu.add(0, MENU_ID_SEARCH_WEB, 0, ctx.getString(R.string.search_web))
                            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
                        return true
                    }
                    override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
                    override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                        if (item.itemId == MENU_ID_SEARCH_WEB) {
                            val start = selectionStart.coerceAtLeast(0)
                            val end = selectionEnd.coerceAtLeast(0)
                            val selected = text.substring(minOf(start, end), maxOf(start, end))
                            if (selected.isNotBlank()) {
                                val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                                    putExtra(android.app.SearchManager.QUERY, selected)
                                }
                                ctx.startActivity(intent)
                            }
                            mode.finish()
                            return true
                        }
                        return false
                    }
                    override fun onDestroyActionMode(mode: android.view.ActionMode) {}
                }
            }
            setTextSelectionAwareClick { onClick() }
        } else {
            customSelectionActionModeCallback = null
            setOnClickListener { onClick() }
            setOnTouchListener(null)
        }
    }

    private fun parseTextColor(hex: String): Int? =
        runCatching { android.graphics.Color.parseColor("#$hex") }.getOrNull()

    private fun applyTextColor(tv: TextView, colorHex: String) {
        if (tv.getTag(R.id.reader_orig_color) == null) {
            tv.setTag(R.id.reader_orig_color, tv.textColors)
        }
        val custom = if (colorHex.isNotEmpty()) parseTextColor(colorHex) else null
        if (custom != null) {
            tv.setTextColor(custom)
        } else {
            (tv.getTag(R.id.reader_orig_color) as? ColorStateList)?.let { tv.setTextColor(it) }
        }
    }

    private fun getItemReadingStateBackground(item: ReaderItem): Drawable? {
        val manualHighlight = currentManualHighlight()
        if (manualHighlight != null &&
            item is ReaderItem.Position &&
            item.chapterIndex == manualHighlight.chapterIndex &&
            item.chapterItemPosition == manualHighlight.chapterItemPosition
        ) {
            return currentReadingAloudDrawable
        }

        return null
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val item = getItem(position)) {
            is ReaderItem.GoogleTranslateAttribution -> viewTranslateAttribution(convertView, parent)
            is ReaderItem.TranslateAttribution -> viewTranslateAttributionNew(item, convertView, parent)
            is ReaderItem.Body -> viewBody(item, convertView, parent)
            is ReaderItem.Image -> viewImage(item, convertView, parent)
            is ReaderItem.BookEnd -> viewBookEnd(convertView, parent)
            is ReaderItem.BookStart -> viewBookStart(convertView, parent)
            is ReaderItem.Divider -> viewDivider(convertView, parent)
            is ReaderItem.Error -> viewError(item, convertView, parent)
            is ReaderItem.Padding -> viewPadding(convertView, parent)
            is ReaderItem.Progressbar -> viewProgressbar(convertView, parent)
            is ReaderItem.Translating -> viewTranslating(item, convertView, parent)
            is ReaderItem.Title -> viewTitle(item, convertView, parent)
        }

    private fun View.setTextSelectionAwareClick(action: () -> Unit) {
        setOnClickListener { action() }
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && !this.isFocused) {
                performClick()
            }
            false
        }
    }

    companion object {
        private const val MENU_ID_SEARCH_WEB = 9999
    }
}
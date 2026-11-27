package moe.n4tsu.agyremote.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.util.LruCache
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.spring
import kotlinx.coroutines.delay
import moe.n4tsu.agyremote.R
import moe.n4tsu.agyremote.state.Haptics
import moe.n4tsu.agyremote.ui.components.plainClickable
import moe.n4tsu.agyremote.ui.theme.AppColors
import moe.n4tsu.agyremote.ui.theme.LocalAppColors

// ---------------------------------------------------------------- model

sealed class MdKind {
    abstract val tag: String

    data class Paragraph(val text: String) : MdKind() { override val tag get() = "p" }
    data class Heading(val level: Int, val text: String) : MdKind() { override val tag get() = "h$level" }
    data class ListBlock(val items: List<MdItem>) : MdKind() { override val tag get() = "l" }
    data class Quote(val text: String) : MdKind() { override val tag get() = "q" }
    data class Code(val lang: String, val code: String) : MdKind() { override val tag get() = "c-$lang" }
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdKind() { override val tag get() = "t" }
    data object Rule : MdKind() { override val tag get() = "r" }
}

data class MdItem(val indent: Int, val number: Int?, val text: String)

data class MdBlock(val id: String, val kind: MdKind)

// ---------------------------------------------------------------- parser

object MarkdownParser {
    private val cache = LruCache<String, List<MdKind>>(300)

    fun blocks(text: String): List<MdBlock> {
        val src = text.replace("\r\n", "\n")
        val kinds = cachedKinds(src)
        val seen = HashMap<String, Int>()
        return kinds.map { k ->
            val n = seen[k.tag] ?: 0
            seen[k.tag] = n + 1
            MdBlock("${k.tag}#$n", k)
        }
    }

    private fun cachedKinds(src: String): List<MdKind> {
        cache.get(src)?.let { return it }
        val cut = splitPoint(src)
        val out = if (cut > 0) {
            val head = src.substring(0, cut)
            val headKinds = cache.get(head) ?: parse(head).also { cache.put(head, it) }
            headKinds + parse(src.substring(cut))
        } else parse(src)
        cache.put(src, out)
        return out
    }

    /** Offset of the last blank line outside a fence that follows a non-blank line; 0 if none. */
    private fun splitPoint(src: String): Int {
        var fenced = false
        var lastBlank = false
        var best = 0
        var start = 0
        while (start <= src.length) {
            val end = src.indexOf('\n', start).let { if (it < 0) src.length else it }
            val t = src.substring(start, end).trim(' ', '\t')
            if (t.startsWith("```")) fenced = !fenced
            val blank = t.isEmpty()
            if (blank && !fenced && !lastBlank && start > 0) best = start
            lastBlank = blank
            start = end + 1
        }
        return best
    }

    fun tableCells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.dropLast(1)
        return s.split("|").map { it.trim() }
    }

    fun isTableSeparator(line: String): Boolean {
        val cells = tableCells(line)
        if (cells.isEmpty()) return false
        return cells.all { cell -> cell.contains('-') && cell.all { it == '-' || it == ':' } }
    }

    private fun columns(line: String): Int {
        var n = 0
        for (ch in line) {
            when (ch) {
                ' ' -> n += 1
                '\t' -> n += 4
                else -> return n
            }
        }
        return n
    }

    /** Returns (number or null for bullets, body) when the trimmed line is a list item. */
    private fun listItem(t: String): Pair<Int?, String>? {
        if (t.length >= 2 && (t[0] == '-' || t[0] == '*' || t[0] == '+') && (t[1] == ' ' || t[1] == '\t')) {
            return null to t.substring(2).trim(' ', '\t')
        }
        var i = 0
        while (i < t.length && i < 3 && t[i] in '0'..'9') i++
        if (i == 0 || i + 1 >= t.length) return null
        if ((t[i] == '.' || t[i] == ')') && (t[i + 1] == ' ' || t[i + 1] == '\t')) {
            return t.substring(0, i).toInt() to t.substring(i + 2).trim(' ', '\t')
        }
        return null
    }

    fun parse(src: String): List<MdKind> {
        val out = ArrayList<MdKind>()
        val para = ArrayList<String>()
        val items = ArrayList<MdItem>()
        val quote = ArrayList<String>()
        val pipes = ArrayList<String>()
        var fenceLang: String? = null
        val fence = ArrayList<String>()

        fun endPara() {
            val s = para.joinToString("\n").trim()
            if (s.isNotEmpty()) out += MdKind.Paragraph(s)
            para.clear()
        }
        fun endList() { if (items.isNotEmpty()) out += MdKind.ListBlock(items.toList()); items.clear() }
        fun endQuote() { if (quote.isNotEmpty()) out += MdKind.Quote(quote.joinToString("\n")); quote.clear() }
        fun endPipes() {
            if (pipes.isEmpty()) return
            if (pipes.size >= 2 && isTableSeparator(pipes[1])) {
                endPara()
                out += MdKind.Table(tableCells(pipes[0]), pipes.drop(2).map { tableCells(it) })
            } else {
                para.addAll(pipes)
            }
            pipes.clear()
        }

        for (raw in src.replace("\r\n", "\n").split("\n")) {
            val t = raw.trim(' ', '\t')
            if (fenceLang != null) {
                if (t.startsWith("```")) {
                    out += MdKind.Code(fenceLang, fence.joinToString("\n"))
                    fence.clear(); fenceLang = null
                } else fence += raw
                continue
            }
            if (t.startsWith("```")) {
                endPipes(); endPara(); endList(); endQuote()
                fenceLang = t.substring(3).trim().lowercase()
                continue
            }
            if (t.isEmpty()) { endPipes(); endPara(); endList(); endQuote(); continue }
            if (t.startsWith("|")) {
                if (pipes.isEmpty()) { endList(); endQuote() }
                pipes += t
                continue
            }
            endPipes()
            val hashes = t.takeWhile { it == '#' }.length
            if (hashes in 1..6 && t.length > hashes && t[hashes] == ' ') {
                endPara(); endList(); endQuote()
                out += MdKind.Heading(hashes, t.substring(hashes + 1).trim())
                continue
            }
            if (t == "---" || t == "***" || t == "___") {
                endPara(); endList(); endQuote(); out += MdKind.Rule; continue
            }
            val li = listItem(t)
            if (li != null) {
                endPara(); endQuote()
                items += MdItem(columns(raw) / 2, li.first, li.second)
                continue
            }
            if (t.startsWith(">")) {
                endPara(); endList()
                quote += t.substring(1).trim()
                continue
            }
            if (items.isNotEmpty() && para.isEmpty() && quote.isEmpty() && columns(raw) >= 2) {
                val last = items.removeAt(items.size - 1)
                items += last.copy(text = last.text + " " + t)
                continue
            }
            endList(); endQuote()
            para += raw
        }
        if (fenceLang != null && fence.any { it.isNotBlank() }) out += MdKind.Code(fenceLang, fence.joinToString("\n"))
        endPipes(); endPara(); endList(); endQuote()
        return out
    }
}

// ---------------------------------------------------------------- inline

fun inlineMarkdown(text: String, c: AppColors): AnnotatedString = buildAnnotatedString {
    InlineScanner(c, this).run(text)
}

private class InlineScanner(val c: AppColors, val b: AnnotatedString.Builder) {
    fun run(s: String) {
        var i = 0
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { b.append(plain.toString()); plain.clear() } }
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length && !s[i + 1].isLetterOrDigit()) {
                plain.append(s[i + 1]); i += 2; continue
            }
            if (ch == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i + 1) {
                    flush()
                    b.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.5.sp, background = c.surfaceContainerHigh))
                    b.append(s.substring(i + 1, close)); b.pop()
                    i = close + 1; continue
                }
            }
            if ((s.startsWith("**", i) || s.startsWith("__", i))) {
                val mark = s.substring(i, i + 2)
                val close = s.indexOf(mark, i + 2)
                if (close > i + 2) {
                    flush()
                    b.pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); run(s.substring(i + 2, close)); b.pop()
                    i = close + 2; continue
                }
            }
            if (s.startsWith("~~", i)) {
                val close = s.indexOf("~~", i + 2)
                if (close > i + 2) {
                    flush()
                    b.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)); run(s.substring(i + 2, close)); b.pop()
                    i = close + 2; continue
                }
            }
            if ((ch == '*' || ch == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() &&
                (ch == '*' || i == 0 || !s[i - 1].isLetterOrDigit())) {
                val close = s.indexOf(ch, i + 1)
                if (close > i + 1) {
                    flush()
                    b.pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); run(s.substring(i + 1, close)); b.pop()
                    i = close + 1; continue
                }
            }
            if (ch == '[') {
                val mid = s.indexOf("](", i + 1)
                val end = if (mid > 0) s.indexOf(')', mid + 2) else -1
                if (mid > i && end > mid) {
                    flush()
                    link(s.substring(mid + 2, end)) { run(s.substring(i + 1, mid)) }
                    i = end + 1; continue
                }
            }
            if (s.startsWith("http://", i) || s.startsWith("https://", i)) {
                var j = i
                while (j < s.length && !s[j].isWhitespace() && s[j] != ')' && s[j] != '>') j++
                while (j > i && s[j - 1] in ".,;:!?") j--
                flush()
                val url = s.substring(i, j)
                link(url) { b.append(url) }
                i = j; continue
            }
            plain.append(ch); i++
        }
        flush()
    }

    private fun link(url: String, body: () -> Unit) {
        // A click handler, not LinkAnnotation.Url: the app decides what a link may do (ChatLinks).
        b.pushLink(LinkAnnotation.Clickable(url, TextLinkStyles(SpanStyle(color = c.primary))) { ChatLinks.onLink?.invoke(url) })
        body()
        b.pop()
    }
}

// ---------------------------------------------------------------- views

@Composable
fun MarkdownView(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MarkdownParser.blocks(text) }
    SelectionContainer(modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (block in blocks) {
                androidx.compose.runtime.key(block.id) { MdBlockView(block.kind) }
            }
        }
    }
}

@Composable
private fun MdBlockView(kind: MdKind) {
    val c = LocalAppColors.current
    val prose = TextStyle(fontSize = 16.5.sp, lineHeight = 24.sp, color = c.onSurface)
    when (kind) {
        is MdKind.Paragraph -> Text(remember(kind, c) { inlineMarkdown(kind.text, c) }, style = prose)
        is MdKind.Heading -> {
            val size = when { kind.level <= 1 -> 20f; kind.level == 2 -> 17.5f; else -> 16f }
            Text(
                remember(kind, c) { inlineMarkdown(kind.text, c) },
                Modifier.padding(top = if (kind.level <= 1) 8.dp else 4.dp),
                style = TextStyle(
                    fontSize = size.sp, lineHeight = (size + 7).sp, fontWeight = FontWeight.SemiBold,
                    color = if (kind.level > 3) c.onSurfaceVariant else c.onSurface,
                ),
            )
        }
        is MdKind.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in kind.items) {
                val body = remember(item, c) { inlineMarkdown(item.text, c) }
                if (item.number != null) {
                    Row(Modifier.padding(start = (item.indent * 18).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${item.number}.", Modifier.widthIn(min = 18.dp), style = prose.copy(color = c.onSurfaceMuted, textAlign = TextAlign.End, fontFeatureSettings = "tnum"))
                        Text(body, style = prose)
                    }
                } else {
                    Row(Modifier.padding(start = (4 + item.indent * 18).dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("•", style = prose.copy(color = c.onSurfaceMuted))
                        Text(body, style = prose)
                    }
                }
            }
        }
        is MdKind.Quote -> Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(c.outlineVariant, RoundedCornerShape(1.dp)))
            Text(remember(kind, c) { inlineMarkdown(kind.text, c) }, style = prose.copy(color = c.onSurfaceVariant))
        }
        is MdKind.Code -> CodeBlock(kind.lang, kind.code)
        is MdKind.Table -> MarkdownTable(kind.header, kind.rows)
        MdKind.Rule -> Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(1.dp).background(c.outlineVariant.copy(alpha = 0.6f)))
    }
}

@Composable
fun MarkdownTable(header: List<String>, rows: List<List<String>>) {
    val c = LocalAppColors.current
    val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return
    val cellStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = c.onSurface)
    fun cell(r: List<String>, i: Int) = r.getOrElse(i) { "" }
    Box(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
        Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Each column gets its own vertical stack so widths line up.
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (col in 0 until cols) {
                    Column(Modifier.widthIn(max = 280.dp).width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(inlineMarkdown(cell(header, col), c), style = cellStyle.copy(fontWeight = FontWeight.SemiBold))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant.copy(alpha = 0.6f)))
                        for (r in rows) Text(inlineMarkdown(cell(r, col), c), style = cellStyle)
                    }
                }
            }
        }
    }
}

@Composable
fun CodeBlock(lang: String, code: String) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    val lines = remember(code) { code.split("\n") }
    val foldable = lines.size > 20
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val shown = if (foldable && !expanded) lines.take(16) else lines
    val shownText = shown.joinToString("\n")
    val highlighted = remember(shownText, c.isDark) { SyntaxHighlighter.cached(shownText, c.isDark) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.5.sp)
    val shape = RoundedCornerShape(12.dp)

    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier.fillMaxWidth()
                .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 385f))
                .background(c.surfaceContainerLow, shape)
                .border(1.dp, c.outlineVariant.copy(alpha = 0.6f), shape),
        ) {
            Row(Modifier.fillMaxWidth().height(34.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(lang.lowercase().ifEmpty { "code" }, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.onSurfaceMuted))
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                val desc = stringResource(R.string.copy)
                Box(
                    Modifier.size(32.dp, 28.dp).semantics { contentDescription = desc }.plainClickable {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("code", code))
                        Haptics.success(context); copied = true
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy, null,
                        Modifier.size(14.dp), tint = if (copied) c.primary else c.onSurfaceMuted,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant.copy(alpha = 0.5f)))
            Box {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (lines.size > 1) {
                        Text(
                            (1..shown.size).joinToString("\n"),
                            style = mono.copy(color = c.onSurfaceMuted.copy(alpha = 0.7f), textAlign = TextAlign.End, fontFeatureSettings = "tnum"),
                        )
                    }
                    Text(highlighted, style = mono, softWrap = false)
                }
                if (foldable && !expanded) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, c.surfaceContainerLow))),
                    )
                }
            }
            if (foldable) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant.copy(alpha = 0.5f)))
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 36.dp).plainClickable { expanded = !expanded },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (expanded) stringResource(R.string.show_less) else pluralStringResource(R.plurals.show_all_x_lines, lines.size, lines.size),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.onSurfaceVariant),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- highlighter

object SyntaxHighlighter {
    private class Palette(val plain: Color, val keyword: Color, val string: Color, val comment: Color, val number: Color)

    private val light = Palette(Color(0xFF1A1F20), Color(0xFF6D3FD9), Color(0xFF0F766E), Color(0xFF7B8587), Color(0xFF1D6FB8))
    private val dark = Palette(Color(0xFFE4E9E8), Color(0xFFB9A4FF), Color(0xFF6EE0C8), Color(0xFF6F7A7D), Color(0xFF8EC5F5))
    private val memo = LruCache<String, AnnotatedString>(200)

    private val keywords: Set<String> = (
        "func fn def class struct enum protocol interface trait impl extension typealias type " +
            "let var const val fun mut static public private internal fileprivate protected open " +
            "export import from package module use mod pub extern final override abstract sealed " +
            "data object companion init deinit new delete namespace using include where lazy " +
            "if else elif elseif then fi for while do done loop repeat in of until switch match " +
            "when case default break continue return yield goto fallthrough async await go chan " +
            "select try catch except finally throw throws raise defer guard with as is not and or " +
            "pass lambda esac " +
            "true false nil null None True False undefined self Self this super NULL " +
            "String Int Int64 Int32 Double Float Bool Void Character UInt " +
            "int float double char bool void long short unsigned str bytes " +
            "i8 i16 i32 i64 u8 u16 u32 u64 usize isize"
        ).split(' ').filter { it.isNotEmpty() }.toSet()

    fun cached(source: String, isDark: Boolean): AnnotatedString {
        val key = (if (isDark) "d" else "l") + source
        memo.get(key)?.let { return it }
        val out = colour(source, if (isDark) dark else light)
        memo.put(key, out)
        return out
    }

    private enum class Role { Plain, Keyword, Str, Comment, Num }

    private fun colour(s: String, p: Palette): AnnotatedString {
        val runs = ArrayList<Triple<Int, Int, Role>>()
        fun add(a: Int, z: Int, r: Role) {
            if (z <= a) return
            val last = runs.lastOrNull()
            if (last != null && last.third == r && last.second == a) runs[runs.size - 1] = Triple(last.first, z, r)
            else runs += Triple(a, z, r)
        }
        fun lineEnd(from: Int): Int = s.indexOf('\n', from).let { if (it < 0) s.length else it }

        var i = 0
        var lineStart = true
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '\n' -> { add(i, i + 1, Role.Plain); i++; lineStart = true }
                ch == ' ' || ch == '\t' -> { add(i, i + 1, Role.Plain); i++ }
                s.startsWith("//", i) -> { val e = lineEnd(i); add(i, e, Role.Comment); i = e }
                s.startsWith("/*", i) -> {
                    val e = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                    add(i, e, Role.Comment); i = e; lineStart = false
                }
                ch == '#' && lineStart && !(i + 1 < s.length && s[i + 1] == '!') -> {
                    val e = lineEnd(i); add(i, e, Role.Comment); i = e
                }
                ch == '"' || ch == '\'' || ch == '`' -> {
                    var j = i + 1
                    var closed = false
                    while (j < s.length && s[j] != '\n') {
                        if (s[j] == '\\') { j += 2; continue }
                        if (s[j] == ch) { closed = true; j++; break }
                        j++
                    }
                    j = minOf(j, s.length)
                    if (!closed && ch == '\'') { add(i, i + 1, Role.Plain); i++ }
                    else { add(i, j, Role.Str); i = j }
                    lineStart = false
                }
                ch.isDigit() -> {
                    var j = i + 1
                    if (ch == '0' && j < s.length && (s[j] == 'x' || s[j] == 'X') && j + 1 < s.length && s[j + 1].isHex()) {
                        j += 1
                        while (j < s.length && s[j].isHex()) j++
                    } else {
                        while (j < s.length && s[j].isDigit()) j++
                        if (j + 1 < s.length && s[j] == '.' && s[j + 1].isDigit()) {
                            j += 1
                            while (j < s.length && s[j].isDigit()) j++
                        }
                    }
                    add(i, j, Role.Num); i = j; lineStart = false
                }
                ch.isLetter() || ch == '_' -> {
                    var j = i + 1
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    add(i, j, if (s.substring(i, j) in keywords) Role.Keyword else Role.Plain)
                    i = j; lineStart = false
                }
                else -> { add(i, i + 1, Role.Plain); i++; lineStart = false }
            }
        }
        return buildAnnotatedString {
            for ((a, z, r) in runs) {
                val col = when (r) {
                    Role.Plain -> p.plain; Role.Keyword -> p.keyword; Role.Str -> p.string
                    Role.Comment -> p.comment; Role.Num -> p.number
                }
                pushStyle(SpanStyle(color = col)); append(s.substring(a, z)); pop()
            }
        }
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

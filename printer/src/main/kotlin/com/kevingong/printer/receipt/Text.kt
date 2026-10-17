package com.kevingong.printer.receipt

import com.kevingong.printer.Command
import com.kevingong.printer.PrinterProfile
import com.kevingong.printer.ReceiptItem

/**
 * Encodes the string for the printer. Characters that cannot be represented in
 * `profile.encoding` are replaced (usually with `?`) instead of dropping the line.
 *
 * On GB2312 / GBK / GB18030 profiles the yen/yuan sign `¥` (U+00A5) is sent as the
 * full-width `￥` (U+FFE5, `A3 A4`): the Java encoder would otherwise emit the 4-byte
 * GB18030 sequence `81 30 84 36`, which GBK-only printers such as the Gprinter GP-58
 * render as an unrelated ideograph.
 */
fun String.printableBytes(profile: PrinterProfile): ByteArray =
    normalizedFor(profile.encoding).toByteArray(profile.encoding)

/** Characters that have a native two-byte form on GB-family printers. */
private val gbSubstitutions = mapOf('\u00A5' to '\uFFE5')

internal fun String.normalizedFor(charset: java.nio.charset.Charset): String {
    if (!isGbFamily(charset)) return this
    var result: StringBuilder? = null
    for ((index, ch) in withIndex()) {
        val replacement = gbSubstitutions[ch]
        if (replacement != null && result == null) result = StringBuilder(this).also { it.setLength(index) }
        if (result != null) result.append(replacement ?: ch)
    }
    return result?.toString() ?: this
}

internal fun isGbFamily(charset: java.nio.charset.Charset): Boolean {
    val name = charset.name().uppercase()
    return name.startsWith("GB") || name == "X-MSWIN-936" || name == "MS936" || name == "EUC-CN"
}

/**
 * Number of character cells the string occupies on a fixed-pitch printer
 * (full-width CJK characters count as two). Encoding-agnostic; prefer
 * [printableWidth] with a profile when laying out a line.
 */
val String.printableWidth: Int
    get() = printableWidth(null)

/**
 * Number of character cells the string occupies when printed with [profile]'s encoding.
 *
 * On printers driven by a legacy multi-byte code page (GBK / GB18030, Big5, Shift_JIS,
 * EUC-KR …) every character that encodes to more than one byte is drawn in a
 * double-width cell, not only the CJK ideographs: `¥` (U+00A5) becomes a 4-byte GB18030
 * sequence and prints two cells wide on a Gprinter GP-58, which pushed the last digit of a
 * right-aligned price onto the next line when it was counted as one cell.
 */
fun String.printableWidth(profile: PrinterProfile): Int =
    normalizedFor(profile.encoding).printableWidth(profile.encoding)

internal fun String.printableWidth(charset: java.nio.charset.Charset?): Int {
    val multiByteCodePage = charset != null && isLegacyMultiByte(charset)
    var width = 0
    var i = 0
    while (i < length) {
        val codePoint = codePointAt(i)
        val count = Character.charCount(codePoint)
        width += when {
            codePoint < 0x80 -> 1
            isFullWidth(codePoint) -> 2
            multiByteCodePage && substring(i, i + count).toByteArray(charset!!).size > 1 -> 2
            else -> 1
        }
        i += count
    }
    return width
}

/** `true` for DBCS-style code pages where "more than one byte" means "two cells". */
internal fun isLegacyMultiByte(charset: java.nio.charset.Charset): Boolean {
    val name = charset.name().uppercase()
    if (name.startsWith("UTF") || name.startsWith("X-UTF")) return false
    return charset.newEncoder().maxBytesPerChar() > 1f
}

internal fun isFullWidth(codePoint: Int): Boolean = when (codePoint) {
    in 0x1100..0x115F, in 0x2E80..0x303E, in 0x3041..0x33FF, in 0x3400..0x4DBF,
    in 0x4E00..0x9FFF, in 0xA000..0xA4CF, in 0xAC00..0xD7A3, in 0xF900..0xFAFF,
    in 0xFE30..0xFE4F, in 0xFF00..0xFF60, in 0xFFE0..0xFFE6, in 0x20000..0x3FFFD,
    -> true
    else -> false
}

/** A line of text followed by a line feed. Equivalent of appending a `String` in Swift. */
class Line(val string: String) : ReceiptItem {
    override fun assemblePrintableData(profile: PrinterProfile): ByteArray =
        string.printableBytes(profile) + Command.Cursor.LineFeed.value

    override fun toString(): String = "Line(\"$string\")"
}

/** Renders the string followed by a line feed, same as [Line]. */
fun String.asLine(): Line = Line(this)

/** A line of text that is not followed by a line feed, so several items can share one line. */
class Text(val string: String) : ReceiptItem {
    override fun assemblePrintableData(profile: PrinterProfile): ByteArray = string.printableBytes(profile)

    override fun toString(): String = "Text(\"$string\")"
}

/** `key` left aligned and `value` right aligned on one line. */
class KVItem(val k: String, val v: String) : ReceiptItem {
    override fun assemblePrintableData(profile: PrinterProfile): ByteArray {
        val spaces = maxOf(1, profile.charactersPerLine - k.printableWidth(profile) - v.printableWidth(profile))
        return Line(k + " ".repeat(spaces) + v).assemblePrintableData(profile)
    }

    override fun toString(): String = "KVItem(\"$k\", \"$v\")"
}

/** Chooses the character printed at position `current` of `total` in a [Dividing] line. */
fun interface DividingProvider {
    fun character(current: Int, total: Int): Char
}

/** A full-width line made of a repeated character, e.g. `--------`. */
class Dividing(private val provider: DividingProvider) : ReceiptItem {
    override fun assemblePrintableData(profile: PrinterProfile): ByteArray {
        val total = profile.charactersPerLine
        val content = buildString(total) {
            for (i in 0 until total) append(provider.character(i, total))
        }
        return Line(content).assemblePrintableData(profile)
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun default(character: Char = '-'): Dividing = Dividing { _, _ -> character }
    }
}

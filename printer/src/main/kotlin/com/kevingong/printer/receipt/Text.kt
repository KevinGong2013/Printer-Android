package com.kevingong.printer.receipt

import com.kevingong.printer.Command
import com.kevingong.printer.PrinterProfile
import com.kevingong.printer.ReceiptItem

/**
 * Encodes the string for the printer. Characters that cannot be represented in
 * `profile.encoding` are replaced (usually with `?`) instead of dropping the line.
 */
fun String.printableBytes(profile: PrinterProfile): ByteArray = toByteArray(profile.encoding)

/**
 * Number of character cells the string occupies on a fixed-pitch printer
 * (full-width CJK characters count as two).
 */
val String.printableWidth: Int
    get() {
        var width = 0
        var i = 0
        while (i < length) {
            val codePoint = codePointAt(i)
            width += if (isFullWidth(codePoint)) 2 else 1
            i += Character.charCount(codePoint)
        }
        return width
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
        val spaces = maxOf(1, profile.charactersPerLine - k.printableWidth - v.printableWidth)
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

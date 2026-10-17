package com.kevingong.printer

import java.nio.charset.Charset

/**
 * Describes the paper width and font of the target printer.
 *
 * Mirrors `PrinterProfile` of the Swift library: the same receipt rendered with the
 * same profile produces the same bytes on both platforms.
 *
 * @param maxWidthDensity printable width in dots (58 mm paper: 384, 80 mm paper: 576).
 * @param fontDensity width of one character cell in dots (font A: 12, font B: 9).
 * @param encoding charset used for text. Must match the code page selected on the printer.
 */
class PrinterProfile(
    val maxWidthDensity: Int,
    fontDensity: Int = 12,
    val encoding: Charset = Charsets.US_ASCII,
) {
    val fontDensity: Int = maxOf(1, fontDensity)

    /** Characters per line, used by [KVItem] and [Dividing]. */
    val charactersPerLine: Int
        get() = maxWidthDensity / this.fontDensity

    fun copy(
        maxWidthDensity: Int = this.maxWidthDensity,
        fontDensity: Int = this.fontDensity,
        encoding: Charset = this.encoding,
    ): PrinterProfile = PrinterProfile(maxWidthDensity, fontDensity, encoding)

    override fun equals(other: Any?): Boolean =
        other is PrinterProfile &&
            other.maxWidthDensity == maxWidthDensity &&
            other.fontDensity == fontDensity &&
            other.encoding == encoding

    override fun hashCode(): Int = (maxWidthDensity * 31 + fontDensity) * 31 + encoding.hashCode()

    override fun toString(): String =
        "PrinterProfile(maxWidthDensity=$maxWidthDensity, fontDensity=$fontDensity, encoding=${encoding.name()})"

    companion object {
        /** 58 mm paper, 384 dots, font A. Same as `.🖨️58(encoding)` in Swift. */
        @JvmStatic
        @JvmOverloads
        fun paper58(encoding: Charset = Charsets.US_ASCII): PrinterProfile = PrinterProfile(384, 12, encoding)

        /** 80 mm paper, 576 dots, font A. Same as `.🖨️80(encoding)` in Swift. */
        @JvmStatic
        @JvmOverloads
        fun paper80(encoding: Charset = Charsets.US_ASCII): PrinterProfile = PrinterProfile(576, 12, encoding)
    }
}

/**
 * Charsets that thermal printers commonly expect. Pair each with the matching
 * [Command.CharacterCodePage] (see the README table).
 */
object Encodings {
    /** Most Chinese-market printers (Gprinter, Xprinter, …) expect GB18030 / GBK bytes. */
    @JvmField
    val GB_18030_2000: Charset = Charset.forName("GB18030")

    @JvmField
    val GBK: Charset = Charset.forName("GBK")

    /** Windows-1252, Western Europe. Pair with [Command.CharacterCodePage.WPC1252]. */
    @JvmField
    val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    /** Windows-1256, Arabic. Pair with [Command.CharacterCodePage.WPC1256]. */
    @JvmField
    val WINDOWS_ARABIC: Charset = Charset.forName("windows-1256")

    /** Windows-1251, Cyrillic. Pair with [Command.CharacterCodePage.WPC1251]. */
    @JvmField
    val WINDOWS_1251: Charset = Charset.forName("windows-1251")

    /** Windows-1253, Greek. Pair with [Command.CharacterCodePage.WPC1253]. */
    @JvmField
    val WINDOWS_1253: Charset = Charset.forName("windows-1253")
}

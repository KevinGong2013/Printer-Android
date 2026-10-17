package com.kevingong.printer

import com.kevingong.printer.receipt.Barcode
import com.kevingong.printer.receipt.Dividing
import com.kevingong.printer.receipt.GrayscaleSource
import com.kevingong.printer.receipt.ImageItem
import com.kevingong.printer.receipt.KVItem
import com.kevingong.printer.receipt.Line
import com.kevingong.printer.receipt.QRCode
import com.kevingong.printer.receipt.Text
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** Anything that can be rendered to ESC/POS bytes against a [PrinterProfile]. */
fun interface ReceiptItem {
    fun assemblePrintableData(profile: PrinterProfile): ByteArray
}

/** Two items rendered back to back, the Kotlin equivalent of Swift's `item <<< item`. */
operator fun ReceiptItem.plus(other: ReceiptItem): ReceiptItem =
    ReceiptItem { profile -> assemblePrintableData(profile) + other.assemblePrintableData(profile) }

/**
 * An ordered list of [ReceiptItem]s that is rendered to ESC/POS bytes on demand.
 *
 * ```kotlin
 * val receipt = receipt(PrinterProfile.paper58(Charsets.UTF_8)) {
 *     initialize()
 *     align(Command.Alignment.CENTER)
 *     line("My Shop")
 *     kv("Total", "3.50")
 *     qrCode("https://example.com")
 *     feedAndCut(partial = true)
 * }
 * printer.write(receipt) { error -> … }
 * ```
 *
 * Items can also be appended with `receipt += item` or `receipt.append(item)`.
 */
class Receipt(val profile: PrinterProfile) {
    private val items = mutableListOf<ReceiptItem>()

    /** Number of items appended so far. */
    val size: Int get() = items.size

    fun append(item: ReceiptItem): Receipt {
        items.add(item)
        return this
    }

    operator fun plusAssign(item: ReceiptItem) {
        append(item)
    }

    /** Appends a line of text followed by a line feed, like `receipt <<< "text"` in Swift. */
    operator fun plusAssign(string: String) {
        append(Line(string))
    }

    /** The receipt rendered to ESC/POS bytes. Rendered on every access. */
    val data: ByteArray
        get() {
            val out = ByteArrayOutputStream()
            for (item in items) {
                out.write(item.assemblePrintableData(profile))
            }
            return out.toByteArray()
        }

    // MARK: - DSL

    /** Appends a raw [Command] (Swift: `<<~ .style(...)`). */
    fun command(command: Command): Receipt = append(command)

    /** A line of text followed by a line feed (Swift: `<<< "text"`). */
    fun line(string: String): Receipt = append(Line(string))

    /** Text without a trailing line feed (Swift: `Text`). */
    fun text(string: String): Receipt = append(Text(string))

    /** `key` left aligned and `value` right aligned on one line (Swift: `KVItem`). */
    fun kv(key: String, value: String): Receipt = append(KVItem(key, value))

    /** A full-width line of a repeated character (Swift: `Dividing.default()`). */
    fun dividing(character: Char = '-'): Receipt = append(Dividing.default(character))

    fun image(
        source: GrayscaleSource,
        binarization: ImageItem.Binarization = ImageItem.Binarization.Threshold(128),
        mode: ImageItem.Mode = ImageItem.Mode.NORMAL,
        scaling: ImageItem.Scaling = ImageItem.Scaling.FIT_WIDTH,
        rowsPerBand: Int = 64,
    ): Receipt = append(ImageItem(source, binarization, mode, scaling, rowsPerBand))

    fun image(item: ImageItem): Receipt = append(item)

    fun qrCode(
        content: String,
        moduleSize: Int = 6,
        recovery: QRCode.RecoveryLevel = QRCode.RecoveryLevel.M,
        model: QRCode.Model = QRCode.Model.M_2,
        encoding: Charset? = null,
    ): Receipt = append(QRCode(content, moduleSize, recovery, model, encoding))

    fun barcode(
        content: String,
        symbology: Barcode.Symbology = Barcode.Symbology.CODE128,
        height: Int = 80,
        moduleWidth: Int = 3,
        text: Barcode.TextPosition = Barcode.TextPosition.BELOW,
        font: Command.CharacterFont = Command.CharacterFont.A,
    ): Receipt = append(Barcode(content, symbology, height, moduleWidth, text, font))

    /** Vendor specific bytes (Swift: `.raw([...])`). */
    fun raw(vararg bytes: Int): Receipt = append(Command.Raw(*bytes))

    fun raw(bytes: ByteArray): Receipt = append(Command.Raw(bytes))

    // Frequently used commands

    /** `ESC @` */
    fun initialize(): Receipt = command(Command.Style.Initialize)

    /** `ESC a n` */
    fun align(alignment: Command.Alignment): Receipt = command(Command.Layout.Justification(alignment))

    /** `GS ! n` – width and height magnification, each 1…8. */
    fun characterSize(width: Int, height: Int = width): Receipt =
        command(Command.Style.CharacterSize(width, height))

    /** `ESC E n` */
    fun emphasis(enable: Boolean = true): Receipt = command(Command.Style.Emphasis(enable))

    /** `ESC - n` */
    fun underline(mode: Command.UnderlineMode = Command.UnderlineMode.ENABLE_1DOT): Receipt =
        command(Command.Style.UnderlineMode(mode))

    fun underlineOff(): Receipt = command(Command.Style.UnderlineMode(Command.UnderlineMode.ENABLE))

    /** `ESC t n` */
    fun codePage(page: Command.CharacterCodePage): Receipt = command(Command.Style.SelectCharacterCodePage(page))

    /** `ESC d n` – print and feed `lines` lines. */
    fun feed(lines: Int = 1): Receipt = command(Command.Page.PrintAndFeed(lines))

    /** `GS V 0` / `GS V 1` */
    fun cut(partial: Boolean = false): Receipt =
        command(if (partial) Command.Page.PartialCut else Command.Page.FullCut)

    /** `GS V 65/66 n` – feed then cut. */
    fun feedAndCut(partial: Boolean = true, feed: Int = 0): Receipt = command(Command.Page.FeedAndCut(partial, feed))

    /** `ESC p m t1 t2` */
    fun cashDrawer(pin: Int = 0, onTime: Int = 50, offTime: Int = 250): Receipt =
        command(Command.Peripheral.CashDrawer(pin, onTime, offTime))
}

/** Builds a [Receipt] with the DSL. */
inline fun receipt(profile: PrinterProfile, block: Receipt.() -> Unit): Receipt = Receipt(profile).apply(block)

/** Helper used throughout the library to spell byte sequences with Int literals. */
internal fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

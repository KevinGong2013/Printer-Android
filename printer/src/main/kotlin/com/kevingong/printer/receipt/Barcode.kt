package com.kevingong.printer.receipt

import com.kevingong.printer.Command
import com.kevingong.printer.PrinterProfile
import com.kevingong.printer.ReceiptItem
import com.kevingong.printer.bytesOf

/**
 * Prints a 1D barcode with the printer's built-in generator (`GS k`, function B).
 *
 * @param height bar height in dots, 1…255.
 * @param moduleWidth module width, 2…6.
 */
class Barcode(
    val content: String,
    val symbology: Symbology = Symbology.CODE128,
    height: Int = 80,
    moduleWidth: Int = 3,
    val text: TextPosition = TextPosition.BELOW,
    val font: Command.CharacterFont = Command.CharacterFont.A,
) : ReceiptItem {
    enum class Symbology(val rawValue: Int) {
        UPC_A(65), UPC_E(66), EAN13(67), EAN8(68), CODE39(69), ITF(70), CODABAR(71), CODE93(72), CODE128(73),
    }

    /** Where the human readable text is printed. */
    enum class TextPosition(val rawValue: Int) { NONE(0), ABOVE(1), BELOW(2), BOTH(3) }

    val height: Int = height.coerceIn(1, 255)
    val moduleWidth: Int = moduleWidth.coerceIn(2, 6)

    override fun assemblePrintableData(profile: PrinterProfile): ByteArray {
        if (content.isEmpty() || content.any { it.code > 127 }) {
            return ByteArray(0)
        }
        var payload = content.toByteArray(Charsets.US_ASCII)
        if (symbology == Symbology.CODE128 && payload.first() != '{'.code.toByte()) {
            // Default to CODE B (printable ASCII) unless the caller already chose a code set.
            payload = bytesOf(123, 66) + payload
        }
        if (payload.size > 255) {
            return ByteArray(0)
        }
        return bytesOf(29, 104, height) + // GS h – height
            bytesOf(29, 119, moduleWidth) + // GS w – module width
            bytesOf(29, 72, text.rawValue) + // GS H – HRI position
            bytesOf(29, 102, if (font == Command.CharacterFont.B) 1 else 0) + // GS f – HRI font
            bytesOf(29, 107, symbology.rawValue, payload.size) + payload
    }

    override fun toString(): String = "Barcode(\"$content\", $symbology, height=$height, moduleWidth=$moduleWidth)"
}

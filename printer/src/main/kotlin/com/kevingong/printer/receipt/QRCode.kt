package com.kevingong.printer.receipt

import com.kevingong.printer.PrinterProfile
import com.kevingong.printer.ReceiptItem
import com.kevingong.printer.bytesOf
import java.nio.charset.Charset

/**
 * Prints a QR Code with the printer's built-in symbol generator (`GS ( k`, cn = 49).
 *
 * Command sequence per the Epson ESC/POS reference:
 * 1. fn 65 – select model
 * 2. fn 67 – set module size
 * 3. fn 69 – set error correction level
 * 4. fn 80 – store data in the symbol storage area
 * 5. fn 81 – print the stored symbol
 *
 * @param moduleSize size of one module (dot) in printer dots, 1…16.
 * @param encoding encoding used for `content`; `null` uses the receipt profile's encoding.
 */
class QRCode(
    val content: String,
    moduleSize: Int = 6,
    val recovery: RecoveryLevel = RecoveryLevel.M,
    val model: Model = Model.M_2,
    val encoding: Charset? = null,
) : ReceiptItem {
    enum class Model(val rawValue: Int) { M_1(49), M_2(50), MICRO(51) }

    enum class RecoveryLevel(val rawValue: Int) {
        L(48), // 7%
        M(49), // 15%
        Q(50), // 25%
        H(51), // 30%
    }

    val moduleSize: Int = moduleSize.coerceIn(1, 16)

    override fun assemblePrintableData(profile: PrinterProfile): ByteArray {
        val charset = encoding ?: profile.encoding
        val contentData = content.toByteArray(charset)
        if (contentData.isEmpty() || contentData.size > 7089) {
            return ByteArray(0)
        }
        val total = contentData.size + 3
        return bytesOf(29, 40, 107, 4, 0, 49, 65, model.rawValue, 0) + // select the model
            bytesOf(29, 40, 107, 3, 0, 49, 67, moduleSize) + // module size
            bytesOf(29, 40, 107, 3, 0, 49, 69, recovery.rawValue) + // error correction level
            bytesOf(29, 40, 107, total % 256, total / 256, 49, 80, 48) + contentData + // store
            bytesOf(29, 40, 107, 3, 0, 49, 81, 48) // print
    }

    override fun toString(): String = "QRCode(\"$content\", moduleSize=$moduleSize, recovery=$recovery, model=$model)"
}

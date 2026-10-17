package com.kevingong.printer.receipt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kevingong.printer.PrinterProfile
import com.kevingong.printer.ReceiptItem
import com.kevingong.printer.bytesOf
import java.io.ByteArrayOutputStream
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt

/**
 * Prints a bitmap with the `GS v 0` raster command.
 *
 * The image is re-rendered into an 8-bit grayscale buffer first, so any
 * [GrayscaleSource] (an Android `Bitmap`, decoded PNG/JPEG bytes, a generated
 * [GrayImage], …) is accepted. Transparent pixels are treated as white.
 *
 * Images wider than the printer's dot width are scaled down proportionally
 * unless `scaling` is [Scaling.NONE].
 *
 * Long images are split into bands of `rowsPerBand` rows, each sent as its
 * own raster command. This keeps every command small enough for printers with
 * tiny receive buffers and lets Bluetooth printers start printing before the
 * whole picture has arrived.
 */
class ImageItem(
    val source: GrayscaleSource,
    val binarization: Binarization = Binarization.Threshold(128),
    val mode: Mode = Mode.NORMAL,
    val scaling: Scaling = Scaling.FIT_WIDTH,
    rowsPerBand: Int = 64,
) : ReceiptItem {
    enum class Mode(val rawValue: Int) { NORMAL(0), DOUBLE_WIDTH(1), DOUBLE_HEIGHT(2), DOUBLE_WH(3) }

    /** How gray pixels are turned into black and white dots. */
    sealed class Binarization {
        /** Pixels darker than `threshold` (0…255) are printed black. */
        data class Threshold(val threshold: Int) : Binarization()

        /** Floyd–Steinberg error diffusion. Best for photos and anti-aliased text. */
        object Dither : Binarization() {
            override fun toString(): String = "Dither"
        }
    }

    enum class Scaling {
        /** Send the image at its native pixel size (pixels beyond the paper are dropped by the printer). */
        NONE,

        /** Scale down proportionally so that the image fits [PrinterProfile.maxWidthDensity]. */
        FIT_WIDTH,
    }

    /** Rows per `GS v 0` command. `0` sends the whole image in one command. */
    val rowsPerBand: Int = maxOf(0, rowsPerBand)

    val width: Int get() = source.width
    val height: Int get() = source.height

    /** Threshold binarization, the Swift `ImageItem(_:grayThreshold:)` initializer. */
    constructor(
        source: GrayscaleSource,
        grayThreshold: Int,
        mode: Mode = Mode.NORMAL,
        scaling: Scaling = Scaling.FIT_WIDTH,
        rowsPerBand: Int = 64,
    ) : this(source, Binarization.Threshold(grayThreshold), mode, scaling, rowsPerBand)

    @JvmOverloads
    constructor(
        bitmap: Bitmap,
        binarization: Binarization = Binarization.Threshold(128),
        mode: Mode = Mode.NORMAL,
        scaling: Scaling = Scaling.FIT_WIDTH,
        rowsPerBand: Int = 64,
    ) : this(BitmapSource(bitmap), binarization, mode, scaling, rowsPerBand)

    constructor(
        bitmap: Bitmap,
        grayThreshold: Int,
        mode: Mode = Mode.NORMAL,
        scaling: Scaling = Scaling.FIT_WIDTH,
        rowsPerBand: Int = 64,
    ) : this(BitmapSource(bitmap), Binarization.Threshold(grayThreshold), mode, scaling, rowsPerBand)

    // MARK: - ReceiptItem

    override fun assemblePrintableData(profile: PrinterProfile): ByteArray {
        val bitmap = MonoBitmap.render(source, targetWidth(profile), binarization) ?: return ByteArray(0)
        return rasterCommands(bitmap)
    }

    /** The pixel width the image is rendered at before being sent. */
    fun targetWidth(profile: PrinterProfile): Int {
        if (scaling != Scaling.FIT_WIDTH || profile.maxWidthDensity <= 0) {
            return source.width
        }
        val doubles = mode == Mode.DOUBLE_WIDTH || mode == Mode.DOUBLE_WH
        val maxWidth = if (doubles) profile.maxWidthDensity / 2 else profile.maxWidthDensity
        return minOf(source.width, maxOf(8, maxWidth))
    }

    private fun rasterCommands(bitmap: MonoBitmap): ByteArray {
        val widthBytes = bitmap.widthBytes
        val band = if (rowsPerBand == 0) bitmap.height else rowsPerBand
        val out = ByteArrayOutputStream(bitmap.rows.size + (bitmap.height / maxOf(band, 1) + 1) * 8)
        var y = 0
        while (y < bitmap.height) {
            val rows = minOf(band, bitmap.height - y)
            out.write(bytesOf(29, 118, 48, mode.rawValue))
            out.write(bytesOf(widthBytes % 256, widthBytes / 256, rows % 256, rows / 256))
            out.write(bitmap.rows, y * widthBytes, rows * widthBytes)
            y += rows
        }
        return out.toByteArray()
    }

    override fun toString(): String =
        "ImageItem(${source.width}x${source.height}, $binarization, mode=$mode, scaling=$scaling, rowsPerBand=$rowsPerBand)"

    companion object {
        /**
         * Decodes PNG / JPEG / WebP / … bytes with `BitmapFactory` (e.g. a downloaded or
         * base64-decoded picture). Returns `null` when the bytes are not a picture.
         */
        @JvmStatic
        @JvmOverloads
        fun fromBytes(
            data: ByteArray,
            binarization: Binarization = Binarization.Threshold(128),
            mode: Mode = Mode.NORMAL,
            scaling: Scaling = Scaling.FIT_WIDTH,
            rowsPerBand: Int = 64,
        ): ImageItem? {
            val bitmap = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull() ?: return null
            return ImageItem(BitmapSource(bitmap), binarization, mode, scaling, rowsPerBand)
        }

        /** Decodes a base64 string, with or without a `data:image/...;base64,` prefix. */
        @JvmStatic
        @JvmOverloads
        fun fromBase64(
            base64: String,
            binarization: Binarization = Binarization.Threshold(128),
            mode: Mode = Mode.NORMAL,
            scaling: Scaling = Scaling.FIT_WIDTH,
            rowsPerBand: Int = 64,
        ): ImageItem? {
            val data = decodeBase64Payload(base64) ?: return null
            return fromBytes(data, binarization, mode, scaling, rowsPerBand)
        }

        /**
         * Strips an optional `data:…;base64,` prefix and decodes the payload, ignoring
         * whitespace and other characters outside the base64 alphabet (like Foundation's
         * `ignoreUnknownCharacters`). Returns `null` when nothing decodable is left.
         */
        @JvmStatic
        fun decodeBase64Payload(base64: String): ByteArray? {
            var payload = base64.trim()
            val comma = payload.indexOf(',')
            if (comma >= 0 && payload.startsWith("data:", ignoreCase = true)) {
                payload = payload.substring(comma + 1)
            }
            val cleaned = payload.filter { it.isLetterOrDigit() && it.code < 128 || it == '+' || it == '/' || it == '-' || it == '_' || it == '=' }
                .replace('-', '+').replace('_', '/').trimEnd('=')
            if (cleaned.isEmpty() || cleaned.length % 4 == 1) return null
            return runCatching { Base64.Default.withPadding(Base64.PaddingOption.ABSENT).decode(cleaned) }.getOrNull()
        }
    }
}

/** 1-bit-per-pixel, MSB first, `1` = black. Rows are padded to whole bytes. */
internal class MonoBitmap private constructor(
    val width: Int,
    val height: Int,
    val widthBytes: Int,
    val rows: ByteArray,
) {
    companion object {
        fun render(source: GrayscaleSource, targetWidth: Int, binarization: ImageItem.Binarization): MonoBitmap? {
            if (source.width <= 0 || source.height <= 0 || targetWidth <= 0) {
                return null
            }
            val width = targetWidth
            val height = maxOf(1, (source.height.toDouble() * width / source.width).roundToInt())
            val gray = source.render(width, height)
            if (gray.size != width * height) return null

            val widthBytes = (width + 7) / 8
            val rows = ByteArray(widthBytes * height)

            fun setBlack(x: Int, y: Int) {
                val i = y * widthBytes + x / 8
                rows[i] = (rows[i].toInt() or (0x80 ushr (x % 8))).toByte()
            }

            when (binarization) {
                is ImageItem.Binarization.Threshold -> {
                    val threshold = binarization.threshold
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            if (gray[y * width + x] < threshold) setBlack(x, y)
                        }
                    }
                }

                ImageItem.Binarization.Dither -> {
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val index = y * width + x
                            val old = gray[index]
                            val new = if (old < 128) 0 else 255
                            if (new == 0) setBlack(x, y)
                            val error = old - new
                            if (x + 1 < width) gray[index + 1] += error * 7 / 16
                            if (y + 1 < height) {
                                if (x > 0) gray[index + width - 1] += error * 3 / 16
                                gray[index + width] += error * 5 / 16
                                if (x + 1 < width) gray[index + width + 1] += error * 1 / 16
                            }
                        }
                    }
                }
            }
            return MonoBitmap(width, height, widthBytes, rows)
        }
    }
}

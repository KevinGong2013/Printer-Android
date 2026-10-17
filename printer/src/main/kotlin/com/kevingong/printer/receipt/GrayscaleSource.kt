package com.kevingong.printer.receipt

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * A picture that can be rendered into an 8-bit grayscale buffer of any size.
 *
 * [ImageItem] works on this abstraction so the raster code is plain Kotlin and
 * can be unit tested on the JVM; [BitmapSource] adapts `android.graphics.Bitmap`.
 */
interface GrayscaleSource {
    val width: Int
    val height: Int

    /**
     * Renders the picture at `width`×`height`, row-major, one value per pixel in
     * 0 (black) … 255 (white). Transparent pixels must be composited over white.
     */
    fun render(width: Int, height: Int): IntArray
}

/** Converts a packed ARGB pixel to gray, compositing alpha over white. */
fun argbToGray(argb: Int): Int {
    val a = argb ushr 24 and 0xFF
    val r = argb ushr 16 and 0xFF
    val g = argb ushr 8 and 0xFF
    val b = argb and 0xFF
    val luma = (299 * r + 587 * g + 114 * b) / 1000
    return if (a == 255) luma else (luma * a + 255 * (255 - a)) / 255
}

/** Nearest-neighbour upscaling and box-filter downscaling of an 8-bit gray buffer. */
internal fun resampleGray(src: IntArray, srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): IntArray {
    if (srcWidth == dstWidth && srcHeight == dstHeight) return src.copyOf()
    val out = IntArray(dstWidth * dstHeight)
    for (y in 0 until dstHeight) {
        val sy0 = y * srcHeight / dstHeight
        val sy1 = max(sy0 + 1, (y + 1) * srcHeight / dstHeight)
        for (x in 0 until dstWidth) {
            val sx0 = x * srcWidth / dstWidth
            val sx1 = max(sx0 + 1, (x + 1) * srcWidth / dstWidth)
            var sum = 0
            var count = 0
            for (sy in sy0 until min(sy1, srcHeight)) {
                val row = sy * srcWidth
                for (sx in sx0 until min(sx1, srcWidth)) {
                    sum += src[row + sx]
                    count++
                }
            }
            out[y * dstWidth + x] = if (count == 0) 255 else sum / count
        }
    }
    return out
}

/** An 8-bit grayscale picture held in memory. Handy in tests and for generated graphics. */
class GrayImage(override val width: Int, override val height: Int, private val pixels: IntArray) : GrayscaleSource {
    init {
        require(pixels.size == width * height) { "pixels.size must be width * height" }
    }

    override fun render(width: Int, height: Int): IntArray = resampleGray(pixels, this.width, this.height, width, height)

    companion object {
        /** A picture filled with one gray level. */
        @JvmStatic
        fun filled(width: Int, height: Int, gray: Int): GrayImage = GrayImage(width, height, IntArray(width * height) { gray })
    }
}

/** A packed-ARGB picture held in memory (same layout as `Bitmap.getPixels`). */
class ArgbImage(override val width: Int, override val height: Int, private val argb: IntArray) : GrayscaleSource {
    init {
        require(argb.size == width * height) { "argb.size must be width * height" }
    }

    override fun render(width: Int, height: Int): IntArray {
        val gray = IntArray(argb.size) { argbToGray(argb[it]) }
        return resampleGray(gray, this.width, this.height, width, height)
    }
}

/**
 * Adapts an `android.graphics.Bitmap`. Any config is accepted (hardware bitmaps are
 * copied first); scaling uses the platform's filtered `createScaledBitmap`.
 */
class BitmapSource(private val bitmap: Bitmap) : GrayscaleSource {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun render(width: Int, height: Int): IntArray {
        var source = bitmap
        if (source.config == Bitmap.Config.HARDWARE || source.config != Bitmap.Config.ARGB_8888) {
            source = source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        }
        val scaled = if (source.width == width && source.height == height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        val argb = IntArray(width * height)
        scaled.getPixels(argb, 0, width, 0, 0, width, height)
        return IntArray(argb.size) { argbToGray(argb[it]) }
    }
}

package com.kevingong.printer

import com.kevingong.printer.receipt.ArgbImage
import com.kevingong.printer.receipt.GrayImage
import com.kevingong.printer.receipt.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTests {
    private val profile = PrinterProfile.paper58()

    /** Builds an ARGB image of `width`×`height`, white, with the left half black. */
    private fun makeImage(width: Int, height: Int, alpha: Boolean = true, leftHalfBlack: Boolean = true): ArgbImage {
        val white = if (alpha) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt()
        val black = if (alpha) 0xFF000000.toInt() else 0xFF000000.toInt()
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            if (leftHalfBlack && x < width / 2) black else white
        }
        return ArgbImage(width, height, pixels)
    }

    @Test
    fun headerAndBlackIsOne() {
        val bytes = ImageItem(makeImage(16, 2)).assemblePrintableData(profile)
        assertBytes(b(29, 118, 48, 0, 2, 0, 2, 0), bytes.copyOf(8))
        assertBytes(b(0xFF, 0x00, 0xFF, 0x00), bytes.copyOfRange(8, bytes.size))
    }

    @Test
    fun whiteImageIsAllZero() {
        val bytes = ImageItem(makeImage(8, 1, leftHalfBlack = false)).assemblePrintableData(profile)
        assertBytes(b(29, 118, 48, 0, 1, 0, 1, 0, 0), bytes)
    }

    @Test
    fun nonMultipleOf8WidthIsPadded() {
        val bytes = ImageItem(makeImage(10, 1)).assemblePrintableData(profile)
        assertBytes(b(2, 0, 1, 0), bytes.copyOfRange(4, 8))
        assertBytes(b(0xF8, 0x00), bytes.copyOfRange(bytes.size - 2, bytes.size))
    }

    @Test
    fun rgbWithoutAlphaDoesNotCrash() {
        val bytes = ImageItem(makeImage(8, 1, alpha = false)).assemblePrintableData(profile)
        assertEquals(0xF0, bytes.u(bytes.size - 1))
    }

    @Test
    fun grayscaleSourceDoesNotCrash() {
        val bytes = ImageItem(GrayImage.filled(8, 1, 0)).assemblePrintableData(profile)
        assertEquals(0xFF, bytes.u(bytes.size - 1))
    }

    @Test
    fun transparentPixelsPrintWhite() {
        val transparentBlack = ArgbImage(8, 1, IntArray(8) { 0x00000000 })
        val bytes = ImageItem(transparentBlack).assemblePrintableData(profile)
        assertEquals(0x00, bytes.u(bytes.size - 1))
    }

    @Test
    fun wideImageIsScaledToPaper() {
        val bytes = ImageItem(makeImage(1000, 100), rowsPerBand = 0).assemblePrintableData(profile)
        assertBytes(b(48, 0, 38, 0), bytes.copyOfRange(4, 8)) // 384 px = 48 bytes, 100 * 384/1000 ≈ 38 rows
        assertEquals(8 + 48 * 38, bytes.size)
    }

    @Test
    fun doubleWidthHalvesTargetWidth() {
        assertEquals(192, ImageItem(makeImage(1000, 10), mode = ImageItem.Mode.DOUBLE_WH).targetWidth(profile))
    }

    @Test
    fun scalingNoneKeepsNativeSize() {
        val bytes = ImageItem(makeImage(1000, 1), scaling = ImageItem.Scaling.NONE, rowsPerBand = 0).assemblePrintableData(profile)
        assertBytes(b(125, 0), bytes.copyOfRange(4, 6))
    }

    @Test
    fun longImageIsSplitIntoBands() {
        val bytes = ImageItem(makeImage(8, 150), rowsPerBand = 64).assemblePrintableData(profile)
        // 64 + 64 + 22 rows, each band has an 8-byte header and 1 byte per row.
        assertEquals(3 * 8 + 150, bytes.size)
        assertBytes(b(29, 118, 48, 0, 1, 0, 64, 0), bytes.copyOf(8))
        val third = 8 + 64 + 8 + 64
        assertBytes(b(29, 118, 48, 0, 1, 0, 22, 0), bytes.copyOfRange(third, third + 8))
    }

    @Test
    fun ditherOfMidGrayIsRoughlyHalfBlack() {
        val gray = GrayImage.filled(64, 64, 128)
        val bytes = ImageItem(gray, binarization = ImageItem.Binarization.Dither, rowsPerBand = 0).assemblePrintableData(profile)
        val black = bytes.drop(8).sumOf { Integer.bitCount(it.toInt() and 0xFF) }
        assertEquals(0.5, black.toDouble() / 4096, 0.05)
        // A plain threshold would make this either all black or all white.
        val thresholded = ImageItem(gray, rowsPerBand = 0).assemblePrintableData(profile)
        assertEquals(1, thresholded.drop(8).toSet().size)
    }

    @Test
    fun thresholdIsConfigurable() {
        val gray = GrayImage.filled(8, 1, 150)
        assertEquals(0x00, ImageItem(gray, grayThreshold = 128).assemblePrintableData(profile).last().toInt() and 0xFF)
        assertEquals(0xFF, ImageItem(gray, grayThreshold = 160).assemblePrintableData(profile).last().toInt() and 0xFF)
    }

    @Test
    fun base64PayloadDecoding() {
        // 1×1 white PNG
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="
        val signature = b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val withPrefix = ImageItem.decodeBase64Payload("data:image/png;base64,$png")
        assertNotNull(withPrefix)
        assertBytes(signature, withPrefix!!.copyOf(8))
        val bare = ImageItem.decodeBase64Payload(png)
        assertNotNull(bare)
        assertBytes(signature, bare!!.copyOf(8))
        assertNull(ImageItem.decodeBase64Payload("not base64!!"))
        assertNull(ImageItem.fromBase64("not base64!!"))
    }

    @Test
    fun emptySourceRendersNothing() {
        assertTrue(ImageItem(GrayImage(0, 0, IntArray(0))).assemblePrintableData(profile).isEmpty())
    }
}

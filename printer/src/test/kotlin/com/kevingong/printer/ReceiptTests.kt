package com.kevingong.printer

import com.kevingong.printer.receipt.Dividing
import com.kevingong.printer.receipt.KVItem
import com.kevingong.printer.receipt.Line
import com.kevingong.printer.receipt.Text
import com.kevingong.printer.receipt.printableWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Translated from `ReceiptTests` in the Swift package; the expected bytes are the shared golden vectors. */
class ReceiptTests {
    private val profile = PrinterProfile.paper58(Charsets.UTF_8)

    @Test
    fun textAppendsLineFeed() {
        assertBytes(b(0x48, 0x69, 10), Line("Hi").assemblePrintableData(profile))
    }

    @Test
    fun textWithoutLineFeed() {
        assertBytes(b(0x48, 0x69), Text("Hi").assemblePrintableData(profile))
    }

    @Test
    fun unencodableTextIsNotDroppedSilently() {
        val ascii = PrinterProfile.paper58(Charsets.US_ASCII)
        val bytes = Line("€5").assemblePrintableData(ascii)
        assertFalse(bytes.isEmpty())
        assertEquals(10, bytes.last().toInt())
        assertTrue(bytes.contains(0x35)) // the "5" survives
    }

    @Test
    fun gbEncodingForChinese() {
        val gb = PrinterProfile.paper58(Encodings.GB_18030_2000)
        assertBytes(b(0xD6, 0xD0, 10), Line("中").assemblePrintableData(gb))
    }

    @Test
    fun kvItemFillsExactlyOneLine() {
        val bytes = KVItem("Total", "¥12.00").assemblePrintableData(profile)
        val line = bytes.copyOf(bytes.size - 1).toString(Charsets.UTF_8)
        assertEquals(profile.charactersPerLine, line.length)
        assertTrue(line.startsWith("Total"))
        assertTrue(line.endsWith("¥12.00"))
    }

    @Test
    fun kvItemCountsCJKAsDoubleWidth() {
        val bytes = KVItem("合计", "12").assemblePrintableData(profile)
        val line = bytes.copyOf(bytes.size - 1).toString(Charsets.UTF_8)
        assertEquals(profile.charactersPerLine, line.printableWidth)
    }

    @Test
    fun dividingIsFullWidth() {
        val bytes = Dividing.default().assemblePrintableData(profile)
        assertEquals(profile.charactersPerLine + 1, bytes.size)
        assertEquals(setOf('-'.code.toByte()), bytes.copyOf(bytes.size - 1).toSet())
    }

    @Test
    fun dslConcatenatesInOrder() {
        val receipt = receipt(profile) {
            command(Command.Style.Initialize)
            line("A")
            command(Command.Page.PrintAndFeed(lines = 2))
        }
        assertBytes(b(27, 64, 0x41, 10, 27, 100, 2), receipt.data)
    }

    @Test
    fun plusAssignAppendsItemsAndStrings() {
        val receipt = Receipt(profile)
        receipt += Command.Style.Initialize
        receipt += "A"
        receipt += Command.Page.PrintAndFeed(lines = 2)
        assertBytes(b(27, 64, 0x41, 10, 27, 100, 2), receipt.data)
        assertEquals(3, receipt.size)
    }

    @Test
    fun itemPlusCombinesTwoItems() {
        val combined = Text("A") + Command.Cursor.LineFeed
        assertBytes(b(0x41, 10), combined.assemblePrintableData(profile))
    }

    @Test
    fun shortcutsEmitTheSameBytesAsCommands() {
        val receipt = receipt(profile) {
            initialize()
            align(Command.Alignment.CENTER)
            characterSize(2, 2)
            emphasis(true)
            feed(3)
            feedAndCut(partial = true, feed = 0)
            cashDrawer()
        }
        assertBytes(
            b(27, 64, 27, 97, 1, 29, 33, 0x11, 27, 69, 1, 27, 100, 3, 29, 86, 66, 0, 27, 112, 0, 50, 250),
            receipt.data,
        )
    }

    @Test
    fun profilesMatchSwiftDefaults() {
        assertEquals(384, PrinterProfile.paper58().maxWidthDensity)
        assertEquals(576, PrinterProfile.paper80().maxWidthDensity)
        assertEquals(32, PrinterProfile.paper58().charactersPerLine)
        assertEquals(48, PrinterProfile.paper80().charactersPerLine)
        assertEquals(Charsets.US_ASCII, PrinterProfile.paper58().encoding)
        assertEquals(1, PrinterProfile(384, 0).fontDensity)
    }
}

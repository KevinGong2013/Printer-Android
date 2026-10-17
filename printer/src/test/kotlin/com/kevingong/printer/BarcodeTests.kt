package com.kevingong.printer

import com.kevingong.printer.receipt.Barcode
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeTests {
    @Test
    fun code128AddsCodeSetB() {
        val bytes = Barcode("AB", height = 60, moduleWidth = 2, text = Barcode.TextPosition.NONE)
            .assemblePrintableData(PrinterProfile.paper58())
        assertBytes(b(29, 104, 60, 29, 119, 2, 29, 72, 0, 29, 102, 0, 29, 107, 73, 4, 123, 66, 0x41, 0x42), bytes)
    }

    @Test
    fun ean13() {
        val bytes = Barcode("4901234567894", symbology = Barcode.Symbology.EAN13).assemblePrintableData(PrinterProfile.paper58())
        assertBytes(b(29, 107, 67, 13) + "4901234567894".toByteArray(Charsets.US_ASCII), bytes.copyOfRange(bytes.size - 17, bytes.size))
    }

    @Test
    fun nonASCIIIsRejected() {
        assertTrue(Barcode("中").assemblePrintableData(PrinterProfile.paper58()).isEmpty())
    }

    @Test
    fun explicitCodeSetIsKept() {
        val bytes = Barcode("{A12").assemblePrintableData(PrinterProfile.paper58())
        assertBytes(b(29, 107, 73, 4, 123, 65, 0x31, 0x32), bytes.copyOfRange(bytes.size - 8, bytes.size))
    }

    @Test
    fun defaultsMatchSwift() {
        val bytes = Barcode("X").assemblePrintableData(PrinterProfile.paper58())
        // height 80, module width 3, HRI below, font A, CODE128 + {B
        assertBytes(b(29, 104, 80, 29, 119, 3, 29, 72, 2, 29, 102, 0, 29, 107, 73, 3, 123, 66, 0x58), bytes)
    }
}

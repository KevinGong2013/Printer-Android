package com.kevingong.printer

import com.kevingong.printer.receipt.QRCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QRCodeTests {
    @Test
    fun commandSequence() {
        val bytes = QRCode("ab", moduleSize = 4, recovery = QRCode.RecoveryLevel.H)
            .assemblePrintableData(PrinterProfile.paper58(Charsets.UTF_8))
        val expected = b(
            29, 40, 107, 4, 0, 49, 65, 50, 0, // model 2
            29, 40, 107, 3, 0, 49, 67, 4, // module size 4
            29, 40, 107, 3, 0, 49, 69, 51, // level H
            29, 40, 107, 5, 0, 49, 80, 48, 0x61, 0x62, // store "ab" (pL = 2 + 3)
            29, 40, 107, 3, 0, 49, 81, 48, // print
        )
        assertBytes(expected, bytes)
    }

    @Test
    fun moduleSizeIsClamped() {
        assertEquals(1, QRCode("x", moduleSize = 0).moduleSize)
        assertEquals(16, QRCode("x", moduleSize = 99).moduleSize)
    }

    @Test
    fun lengthHighByte() {
        val content = "a".repeat(300)
        val bytes = QRCode(content).assemblePrintableData(PrinterProfile.paper58(Charsets.UTF_8))
        val store = bytes.indexOfSequence(b(49, 80, 48))
        assertTrue("store command missing", store >= 0)
        val pL = bytes.u(store - 2)
        val pH = bytes.u(store - 1)
        assertEquals(303, pL + pH * 256)
    }

    @Test
    fun encodingOverride() {
        val bytes = QRCode("中", encoding = Charsets.UTF_8).assemblePrintableData(PrinterProfile.paper58(Charsets.US_ASCII))
        assertTrue(bytes.indexOfSequence(b(0xE4, 0xB8, 0xAD)) >= 0)
    }

    @Test
    fun defaultsMatchSwift() {
        val qr = QRCode("x")
        assertEquals(6, qr.moduleSize)
        assertEquals(QRCode.RecoveryLevel.M, qr.recovery)
        assertEquals(QRCode.Model.M_2, qr.model)
    }
}

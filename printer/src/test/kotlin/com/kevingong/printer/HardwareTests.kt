package com.kevingong.printer

import android.bluetooth.BluetoothDevice
import com.kevingong.printer.hardware.BluetoothClassicScanner
import com.kevingong.printer.hardware.DummyPrinter
import com.kevingong.printer.hardware.NetworkPrinter
import com.kevingong.printer.hardware.PError
import com.kevingong.printer.hardware.VirtualPrinter
import com.kevingong.printer.hardware.chunked
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DummyPrinterTests {
    @Test
    fun recordsHistory() {
        val printer = DummyPrinter()
        printer.logsToConsole = false
        val seen = mutableListOf<ByteArray>()
        printer.onWrite = { seen.add(it) }
        var result: PError? = PError.DeviceNotReady
        printer.write(receipt(PrinterProfile.paper58()) { line("x") }) { result = it }
        assertEquals(1, printer.history.size)
        assertBytes(b(0x78, 10), printer.history[0])
        assertEquals(1, seen.size)
        assertBytes(printer.history[0], seen[0])
        assertNull(result)
    }
}

class ClassicScannerTests {
    @Test
    fun leOnlyDevicesCannotDoSpp() {
        assertTrue(BluetoothClassicScanner.supportsRfcomm(BluetoothDevice.DEVICE_TYPE_CLASSIC))
        assertTrue(BluetoothClassicScanner.supportsRfcomm(BluetoothDevice.DEVICE_TYPE_DUAL))
        assertTrue(BluetoothClassicScanner.supportsRfcomm(BluetoothDevice.DEVICE_TYPE_UNKNOWN))
        assertFalse(BluetoothClassicScanner.supportsRfcomm(BluetoothDevice.DEVICE_TYPE_LE))
    }
}

class ChunkingTests {
    @Test
    fun splitsIntoPackets() {
        val data = ByteArray(45) { it.toByte() }
        val packets = chunked(data, 20)
        assertEquals(listOf(20, 20, 5), packets.map { it.size })
        assertBytes(data, packets.reduce { a, c -> a + c })
        assertEquals(1, chunked(data, 0).size)
        assertEquals(1, chunked(data, 100).size)
    }
}

class NetworkPrinterTests {
    @Test
    fun sendsBytesOverTCP() {
        val server = ServerSocket(0)
        val collected = AtomicReference(ByteArray(0))
        val received = CountDownLatch(1)
        Thread {
            server.accept().use { socket ->
                collected.set(socket.getInputStream().readBytes())
            }
            received.countDown()
        }.start()

        val printer = NetworkPrinter("127.0.0.1", server.localPort)
        val done = CountDownLatch(1)
        val result = AtomicReference<PError?>(PError.DeviceNotReady)
        printer.write(b(27, 64, 0x41, 0x42, 10)) {
            result.set(it)
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(received.await(5, TimeUnit.SECONDS))
        server.close()

        assertNull(result.get())
        assertBytes(b(27, 64, 0x41, 0x42, 10), collected.get())
    }

    @Test
    fun connectionRefusedReportsError() {
        val closed = ServerSocket(0)
        val port = closed.localPort
        closed.close()
        val printer = NetworkPrinter("127.0.0.1", port)
        printer.connectTimeoutMillis = 3000
        val done = CountDownLatch(1)
        val result = AtomicReference<PError?>(null)
        printer.write(b(1)) {
            result.set(it)
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNotNull(result.get())
    }
}

class VirtualPrinterTests {
    private class Captured(val method: String, val path: String, val contentType: String?, val printerName: String?, val body: ByteArray)

    private fun serve(status: Int, response: String, block: (VirtualPrinter, () -> Captured?) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val captured = AtomicReference<Captured?>(null)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            captured.set(
                Captured(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestHeaders.getFirst("X-Printer-Name"),
                    body,
                ),
            )
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val printer = VirtualPrinter("ab12cd", "http://127.0.0.1:${server.address.port}/")
            block(printer) { captured.get() }
        } finally {
            server.stop(0)
        }
    }

    private fun VirtualPrinter.writeAndWait(bytes: ByteArray): PError? {
        val done = CountDownLatch(1)
        val result = AtomicReference<PError?>(PError.NotSupported)
        write(bytes) {
            result.set(it)
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    @Test
    fun postsRawBytesAndAccepts202() = serve(202, """{"id":"job_1","bytes":5}""") { printer, captured ->
        printer.printerName = "Sample"
        val jobs = mutableListOf<VirtualPrinter.Job>()
        printer.onAccepted = { jobs.add(it) }
        val error = printer.writeAndWait(b(27, 64, 0x41, 0x42, 10))
        assertNull(error)
        val request = captured()!!
        assertEquals("POST", request.method)
        assertEquals("/api/v1/jobs/AB12CD", request.path) // token is normalised to upper case
        assertEquals("application/octet-stream", request.contentType)
        assertEquals("Sample", request.printerName)
        assertBytes(b(27, 64, 0x41, 0x42, 10), request.body)
        assertEquals(listOf(VirtualPrinter.Job("job_1", 5)), jobs)
    }

    @Test
    fun notFoundMapsToDeviceNotReady() = serve(404, """{"error":"unknown token"}""") { printer, _ ->
        assertEquals(PError.DeviceNotReady, printer.writeAndWait(b(1)))
    }

    @Test
    fun otherErrorsMapToWriteFailed() = serve(500, "boom") { printer, _ ->
        val error = printer.writeAndWait(b(1))
        assertTrue(error is PError.WriteFailed)
        val cause = (error as PError.WriteFailed).cause as VirtualPrinter.HttpException
        assertEquals(500, cause.code)
        assertEquals("boom", cause.body)
    }

    @Test
    fun invalidTokenFailsFast() {
        val printer = VirtualPrinter("abc", "http://127.0.0.1:9")
        assertEquals(PError.DeviceNotReady, printer.writeAndWait(b(1)))
        assertTrue(VirtualPrinter.isValidToken("A1B2C3"))
        assertTrue(VirtualPrinter.isValidToken(" a1b2c3 "))
        assertEquals(false, VirtualPrinter.isValidToken("A1B2C"))
        assertEquals(false, VirtualPrinter.isValidToken("A1B2C3D"))
    }

    @Test
    fun endpointAndDefaults() {
        val printer = VirtualPrinter("x1y2z3")
        assertEquals("https://printer.baici.tech/api/v1/jobs/X1Y2Z3", printer.endpoint.toString())
        assertEquals(VirtualPrinter.Job("", 7), VirtualPrinter.parseJob("{}", 7))
    }
}

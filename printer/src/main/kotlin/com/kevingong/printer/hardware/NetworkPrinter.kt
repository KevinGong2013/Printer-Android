package com.kevingong.printer.hardware

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

/**
 * Sends ESC/POS bytes to a printer over TCP. Nearly every Ethernet / Wi-Fi receipt
 * printer listens on port 9100 ("raw" / JetDirect) and prints whatever arrives.
 *
 * ```kotlin
 * val printer = NetworkPrinter("192.168.1.50")
 * printer.write(receipt) { error -> … }
 * ```
 *
 * A connection is opened per [write] call and closed once the bytes were sent,
 * which is what most printers expect; keep the object around to reuse the settings.
 */
class NetworkPrinter(val host: String, val port: Int = 9100) : Printer {
    /** Give up if the printer does not accept the connection within this time. */
    var connectTimeoutMillis: Int = 5_000

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "printer-network").apply { isDaemon = true } }

    override fun write(bytes: ByteArray, callback: ((PError?) -> Unit)?) {
        executor.execute {
            val error: PError? = try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
                    socket.getOutputStream().apply {
                        write(bytes)
                        flush()
                    }
                }
                null
            } catch (e: SocketTimeoutException) {
                PError.ConnectFailed
            } catch (e: ConnectException) {
                PError.ConnectFailed
            } catch (e: NoRouteToHostException) {
                PError.ConnectFailed
            } catch (e: UnknownHostException) {
                PError.ConnectFailed
            } catch (e: IOException) {
                PError.WriteFailed(e)
            } catch (e: IllegalArgumentException) {
                PError.DeviceNotReady
            }
            MainThread.post { callback?.invoke(error) }
        }
    }
}

package com.kevingong.printer.hardware

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sends receipts to the web-based virtual printer at https://printer.baici.tech instead
 * of real hardware. Open the page, copy the 6-character token it shows, and every
 * [write] is rendered there in real time.
 *
 * Protocol (shared with the Swift library):
 * - `POST {baseUrl}/api/v1/jobs/{token}` with `Content-Type: application/octet-stream`,
 *   body = raw ESC/POS bytes, optional header `X-Printer-Name`.
 * - `202` → accepted, JSON `{"id":"…","bytes":N}` ([onAccepted]).
 * - `404` → token unknown or the page is offline → [PError.DeviceNotReady].
 * - any other non-2xx → [PError.WriteFailed] wrapping an [HttpException].
 */
class VirtualPrinter(token: String, baseUrl: String = DEFAULT_BASE_URL) : Printer {
    /** The token shown by the web page, 6 upper-case letters / digits. */
    val token: String = token.trim().uppercase()
    val baseUrl: String = baseUrl.trimEnd('/')

    /** Sent as `X-Printer-Name` so the page can label the source. */
    var printerName: String? = null
    var connectTimeoutMillis: Int = 5_000
    var readTimeoutMillis: Int = 15_000

    /** Called on the main thread with the server's job id and byte count after each accepted write. */
    var onAccepted: ((Job) -> Unit)? = null

    /** Response of an accepted job. */
    data class Job(val id: String, val bytes: Int)

    /** A non-2xx response other than 404. */
    class HttpException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(200)}")

    /** The URL the next write is posted to. */
    val endpoint: URL get() = URL("$baseUrl/api/v1/jobs/$token")

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "printer-virtual").apply { isDaemon = true } }

    override fun write(bytes: ByteArray, callback: ((PError?) -> Unit)?) {
        if (!isValidToken(token)) {
            MainThread.post { callback?.invoke(PError.DeviceNotReady) }
            return
        }
        executor.execute {
            var accepted: Job? = null
            val error: PError? = try {
                val connection = endpoint.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = connectTimeoutMillis
                    connection.readTimeout = readTimeoutMillis
                    connection.doOutput = true
                    connection.useCaches = false
                    connection.setRequestProperty("Content-Type", "application/octet-stream")
                    connection.setRequestProperty("Accept", "application/json")
                    printerName?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("X-Printer-Name", it) }
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                    val code = connection.responseCode
                    when {
                        code in 200..299 -> {
                            val body = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                            accepted = parseJob(body, bytes.size)
                            null
                        }
                        code == 404 -> PError.DeviceNotReady
                        else -> {
                            val body = (connection.errorStream ?: connection.inputStream)?.use { it.readBytes() }
                                ?.toString(Charsets.UTF_8) ?: ""
                            PError.WriteFailed(HttpException(code, body))
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: java.net.SocketTimeoutException) {
                PError.ConnectFailed
            } catch (e: java.net.ConnectException) {
                PError.ConnectFailed
            } catch (e: java.net.UnknownHostException) {
                PError.ConnectFailed
            } catch (e: IOException) {
                PError.WriteFailed(e)
            }
            MainThread.post {
                accepted?.let { job -> onAccepted?.invoke(job) }
                callback?.invoke(error)
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://printer.baici.tech"

        private val TOKEN = Regex("^[A-Z0-9]{6}$")
        private val ID = Regex("\"id\"\\s*:\\s*\"([^\"]*)\"")
        private val BYTES = Regex("\"bytes\"\\s*:\\s*(\\d+)")

        /** `true` for 6 upper-case letters / digits, the format the web page hands out. */
        @JvmStatic
        fun isValidToken(token: String): Boolean = TOKEN.matches(token.trim().uppercase())

        internal fun parseJob(body: String, sent: Int): Job = Job(
            id = ID.find(body)?.groupValues?.get(1) ?: "",
            bytes = BYTES.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: sent,
        )
    }
}

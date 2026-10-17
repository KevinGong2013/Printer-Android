package com.kevingong.printer.hardware

import android.os.Handler
import android.os.Looper
import com.kevingong.printer.Receipt

/**
 * Something that accepts raw ESC/POS bytes: [BluetoothLePrinter], [BluetoothSppPrinter],
 * [UsbPrinter], [NetworkPrinter], [VirtualPrinter] and [DummyPrinter].
 *
 * `callback` is invoked on the main thread with `null` on success or a [PError].
 */
interface Printer {
    fun write(bytes: ByteArray, callback: ((PError?) -> Unit)? = null)

    fun write(receipt: Receipt, callback: ((PError?) -> Unit)? = null) = write(receipt.data, callback)
}

/**
 * Errors reported by the hardware layer. Same cases as `PError` in the Swift library.
 */
sealed class PError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Bluetooth is off / not permitted, no printer is connected, or (virtual printer) the token is unknown. */
    object DeviceNotReady : PError("device not ready")

    object ConnectFailed : PError("connect failed")

    /** The printer disconnected while data was still being sent. */
    object Disconnected : PError("disconnected")

    /** The printer exposes no writable characteristic / endpoint. */
    object NotSupported : PError("not supported")

    /** The transport reported an error while writing. */
    class WriteFailed(cause: Throwable) : PError("write failed: ${cause.message ?: cause.javaClass.simpleName}", cause)

    override fun toString(): String = when (this) {
        is WriteFailed -> "WriteFailed(${cause?.message ?: cause})"
        else -> javaClass.simpleName
    }
}

/** Runs callbacks on the Android main thread, or inline when there is no main looper (JVM tests). */
internal object MainThread {
    private val handler: Handler? by lazy {
        val looper = runCatching { Looper.getMainLooper() }.getOrNull()
        looper?.let { runCatching { Handler(it) }.getOrNull() }
    }

    fun post(block: () -> Unit) {
        val h = handler
        if (h == null) {
            block()
        } else {
            h.post(block)
        }
    }
}

/** Splits `bytes` into packets of at most `size` bytes; `size <= 0` returns the whole payload. */
fun chunked(bytes: ByteArray, size: Int): List<ByteArray> {
    if (size <= 0 || bytes.size <= size) return listOf(bytes)
    val out = ArrayList<ByteArray>((bytes.size + size - 1) / size)
    var offset = 0
    while (offset < bytes.size) {
        val end = minOf(offset + size, bytes.size)
        out.add(bytes.copyOfRange(offset, end))
        offset = end
    }
    return out
}

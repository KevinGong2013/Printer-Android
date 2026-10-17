package com.kevingong.printer.hardware

import kotlin.io.encoding.Base64

/**
 * A printer that only records what it would have printed. Useful in unit tests
 * and for debugging receipts without hardware.
 */
class DummyPrinter : Printer {
    /** Called with every payload passed to [write]. */
    var onWrite: ((ByteArray) -> Unit)? = null

    private val _history = mutableListOf<ByteArray>()

    /** Every payload written so far. */
    val history: List<ByteArray> get() = _history.toList()

    /** When `true` (the default) each payload is also logged as base64 via `println`. */
    var logsToConsole = true

    override fun write(bytes: ByteArray, callback: ((PError?) -> Unit)?) {
        _history.add(bytes)
        onWrite?.invoke(bytes)
        if (logsToConsole) {
            println("DummyPrinter: ${Base64.Default.encode(bytes)}")
        }
        callback?.invoke(null)
    }

    fun clear() = _history.clear()
}

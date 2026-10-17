package com.kevingong.printer

import org.junit.Assert.assertArrayEquals

internal fun b(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

internal fun assertBytes(expected: ByteArray, actual: ByteArray) {
    assertArrayEquals(
        "expected ${expected.hex()} but was ${actual.hex()}",
        expected,
        actual,
    )
}

internal fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

internal fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.indexOfSequence(needle: ByteArray): Int {
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

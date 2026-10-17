package com.kevingong.printer.hardware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Classic Bluetooth (RFCOMM / Serial Port Profile) printer. Most 58 mm and 80 mm
 * Bluetooth printers offer SPP in addition to – or instead of – BLE; iOS cannot
 * use it, Android can.
 *
 * ```kotlin
 * val device = BluetoothSppPrinter.bondedDevices(context).first { it.name == "Gprinter_3073" }
 * val printer = BluetoothSppPrinter(device)
 * printer.write(receipt) { error -> … }
 * ```
 *
 * The socket is opened on the first [write] and kept open while [keepConnection] is `true`.
 * Pair the printer in the system Bluetooth settings first, or use [BluetoothClassicScanner].
 */
@SuppressLint("MissingPermission")
class BluetoothSppPrinter(val device: BluetoothDevice) : Printer {
    var connectTimeoutMillis: Long = 10_000

    /** Leave the socket open between writes (faster; the printer shows "connected"). */
    var keepConnection: Boolean = true

    /** Bytes per `OutputStream.write`; `0` writes the whole payload at once. */
    var writeChunkSize: Int = 0
    var interChunkDelayMillis: Long = 0

    /** Verbose trace, delivered on the main thread. */
    var diagnostics: ((String) -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "printer-spp").apply { isDaemon = true } }
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "printer-spp-watchdog").apply { isDaemon = true } }

    @Volatile
    private var socket: BluetoothSocket? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    /** Opens the RFCOMM socket now instead of on the first write. */
    fun connect(callback: ((PError?) -> Unit)? = null) {
        executor.execute {
            val error = ensureConnected()
            MainThread.post { callback?.invoke(error) }
        }
    }

    fun disconnect() {
        executor.execute { closeSocket() }
    }

    override fun write(bytes: ByteArray, callback: ((PError?) -> Unit)?) {
        executor.execute {
            var error = ensureConnected()
            if (error == null) {
                try {
                    val out = socket!!.outputStream
                    val packets = chunked(bytes, writeChunkSize)
                    diag("write ${bytes.size} bytes in ${packets.size} packet(s)")
                    for ((index, packet) in packets.withIndex()) {
                        out.write(packet)
                        if (interChunkDelayMillis > 0 && index < packets.lastIndex) Thread.sleep(interChunkDelayMillis)
                    }
                    out.flush()
                } catch (e: IOException) {
                    diag("write failed: ${e.message}")
                    error = if (socket?.isConnected == true) PError.WriteFailed(e) else PError.Disconnected
                    closeSocket()
                }
            }
            if (!keepConnection) closeSocket()
            MainThread.post { callback?.invoke(error) }
        }
    }

    /** Runs on the executor. */
    private fun ensureConnected(): PError? {
        if (socket?.isConnected == true) return null
        closeSocket()
        runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
        diag("connecting to ${runCatching { device.name }.getOrNull() ?: device.address}")
        val candidates = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                // Last resort for modules with a broken SDP record.
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(device, 1) as BluetoothSocket
            },
        )
        var lastError: Exception? = null
        for ((index, open) in candidates.withIndex()) {
            val s = try {
                open()
            } catch (e: Exception) {
                lastError = e
                continue
            }
            val guard = watchdog.schedule({ if (!s.isConnected) runCatching { s.close() } }, connectTimeoutMillis, TimeUnit.MILLISECONDS)
            try {
                s.connect()
                guard.cancel(false)
                socket = s
                diag("connected (attempt ${index + 1})")
                return null
            } catch (e: IOException) {
                guard.cancel(false)
                runCatching { s.close() }
                lastError = e
                diag("attempt ${index + 1} failed: ${e.message}")
            } catch (e: SecurityException) {
                guard.cancel(false)
                runCatching { s.close() }
                return PError.DeviceNotReady
            }
        }
        diag("connect failed: ${lastError?.message}")
        return PError.ConnectFailed
    }

    private fun closeSocket() {
        socket?.let { s ->
            runCatching { s.close() }
            diag("socket closed")
        }
        socket = null
    }

    private fun diag(message: String) {
        val callback = diagnostics ?: return
        MainThread.post { callback(message) }
    }

    companion object {
        /** Serial Port Profile. */
        @JvmField
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Devices paired in the system settings, printers first. */
        @JvmStatic
        fun bondedDevices(context: Context): List<BluetoothDevice> {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return emptyList()
            val bonded = runCatching { adapter.bondedDevices }.getOrNull() ?: return emptyList()
            return bonded.sortedByDescending { isLikelyPrinter(it) }
        }

        /** `true` when the device class says "imaging / printer" (not every printer does). */
        @JvmStatic
        fun isLikelyPrinter(device: BluetoothDevice): Boolean {
            val clazz = runCatching { device.bluetoothClass }.getOrNull() ?: return false
            return clazz.majorDeviceClass == BluetoothClass.Device.Major.IMAGING
        }
    }
}

/**
 * Discovers classic Bluetooth devices (`BluetoothAdapter.startDiscovery`), the SPP
 * counterpart of BLE scanning. Discovery takes ~12 s and finds unpaired printers too;
 * pairing happens when the socket is first opened.
 */
@SuppressLint("MissingPermission")
class BluetoothClassicScanner(context: Context) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var receiver: BroadcastReceiver? = null

    var onDeviceFound: ((BluetoothDevice) -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    val isDiscovering: Boolean get() = adapter?.isDiscovering == true

    fun start(): PError? {
        val adapter = adapter ?: return PError.DeviceNotReady
        if (!adapter.isEnabled) return PError.DeviceNotReady
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            @Suppress("DEPRECATION")
                            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            device?.let { d -> onDeviceFound?.invoke(d) }
                        }

                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onFinished?.invoke()
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(r, filter)
            }
            receiver = r
        }
        return try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            if (adapter.startDiscovery()) null else PError.DeviceNotReady
        } catch (e: SecurityException) {
            PError.DeviceNotReady
        }
    }

    fun stop() {
        runCatching { adapter?.cancelDiscovery() }
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
    }
}

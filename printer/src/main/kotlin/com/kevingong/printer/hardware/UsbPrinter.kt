package com.kevingong.printer.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.IOException
import java.util.concurrent.Executors

/**
 * USB printer connected through USB host / OTG. Receipt printers enumerate as a
 * *USB Printer Class* device (interface class 7) with a bulk OUT endpoint; the
 * bytes are sent with `bulkTransfer`.
 *
 * ```kotlin
 * val device = UsbPrinter.findPrinters(context).first()
 * val printer = UsbPrinter(context, device)
 * printer.write(receipt) { error -> … }   // asks for permission on the first use
 * ```
 *
 * Add `<uses-feature android:name="android.hardware.usb.host" />` to the app manifest,
 * and optionally an `USB_DEVICE_ATTACHED` intent filter to get permission at plug-in time.
 */
class UsbPrinter(context: Context, val device: UsbDevice) : Printer {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    var bulkTimeoutMillis: Int = 5_000

    /** Bytes per `bulkTransfer`. */
    var writeChunkSize: Int = 16 * 1024

    /** Keep the device open between writes. */
    var keepConnection: Boolean = false

    /** Verbose trace, delivered on the main thread. */
    var diagnostics: ((String) -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "printer-usb").apply { isDaemon = true } }
    private var connection: UsbDeviceConnection? = null
    private var claimed: UsbInterface? = null

    val hasPermission: Boolean get() = manager.hasPermission(device)

    /**
     * Shows the system permission dialog when needed. `callback` runs on the main thread
     * with the result; it is called immediately when permission was already granted.
     */
    fun requestPermission(callback: (Boolean) -> Unit) {
        if (hasPermission) {
            MainThread.post { callback(true) }
            return
        }
        val action = "$ACTION_PERMISSION.${System.identityHashCode(this)}.${System.nanoTime()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != action) return
                runCatching { appContext.unregisterReceiver(this) }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                diag(if (granted) "permission granted" else "permission denied")
                MainThread.post { callback(granted) }
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(action).setPackage(appContext.packageName)
        val pendingIntent = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        diag("requesting permission for ${describe(device)}")
        manager.requestPermission(device, pendingIntent)
    }

    override fun write(bytes: ByteArray, callback: ((PError?) -> Unit)?) {
        if (!hasPermission) {
            requestPermission { granted ->
                if (granted) {
                    write(bytes, callback)
                } else {
                    callback?.invoke(PError.DeviceNotReady)
                }
            }
            return
        }
        executor.execute {
            val error = writeBlocking(bytes)
            MainThread.post { callback?.invoke(error) }
        }
    }

    fun close() {
        executor.execute { release() }
    }

    /** Runs on the executor. */
    private fun writeBlocking(bytes: ByteArray): PError? {
        val target = findBulkOut(device) ?: run {
            diag("no bulk OUT endpoint on ${describe(device)}")
            return PError.NotSupported
        }
        val (iface, endpoint) = target
        var conn = connection
        if (conn == null || claimed !== iface) {
            release()
            conn = try {
                manager.openDevice(device)
            } catch (e: SecurityException) {
                null
            }
            if (conn == null) {
                diag("openDevice failed")
                return PError.ConnectFailed
            }
            if (!conn.claimInterface(iface, true)) {
                diag("claimInterface failed")
                conn.close()
                return PError.ConnectFailed
            }
            connection = conn
            claimed = iface
            diag("opened ${describe(device)}, interface ${iface.id} class ${iface.interfaceClass}, endpoint ${endpoint.address}")
        }
        try {
            val packets = chunked(bytes, writeChunkSize)
            diag("write ${bytes.size} bytes in ${packets.size} transfer(s)")
            for (packet in packets) {
                var offset = 0
                while (offset < packet.size) {
                    val sent = conn.bulkTransfer(endpoint, packet, offset, packet.size - offset, bulkTimeoutMillis)
                    if (sent < 0) {
                        diag("bulkTransfer returned $sent")
                        release()
                        return PError.WriteFailed(IOException("bulkTransfer returned $sent"))
                    }
                    offset += sent
                }
            }
            return null
        } finally {
            if (!keepConnection) release()
        }
    }

    private fun release() {
        connection?.let { c ->
            claimed?.let { runCatching { c.releaseInterface(it) } }
            runCatching { c.close() }
        }
        connection = null
        claimed = null
    }

    private fun diag(message: String) {
        val callback = diagnostics ?: return
        MainThread.post { callback(message) }
    }

    companion object {
        private const val ACTION_PERMISSION = "com.kevingong.printer.USB_PERMISSION"

        /** Attached devices that expose a USB Printer Class interface (class 7). */
        @JvmStatic
        fun findPrinters(context: Context): List<UsbDevice> {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
            return manager.deviceList.values.filter { isPrinter(it) }
        }

        /** Every attached device, printers first. Use when a printer hides behind a vendor-specific class. */
        @JvmStatic
        fun allDevices(context: Context): List<UsbDevice> {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
            return manager.deviceList.values.sortedByDescending { isPrinter(it) }
        }

        @JvmStatic
        fun isPrinter(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER }

        /** The interface and bulk OUT endpoint to print through, preferring the printer-class interface. */
        @JvmStatic
        fun findBulkOut(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
            val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
                .sortedByDescending { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
            for (iface in interfaces) {
                for (i in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(i)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        return iface to endpoint
                    }
                }
            }
            return null
        }

        @JvmStatic
        fun describe(device: UsbDevice): String {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifBlank { null }
            } else {
                null
            }
            return "${name ?: device.deviceName} (${"%04X".format(device.vendorId)}:${"%04X".format(device.productId)})"
        }
    }
}

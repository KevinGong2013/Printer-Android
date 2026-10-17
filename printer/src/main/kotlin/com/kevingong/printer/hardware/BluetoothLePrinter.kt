package com.kevingong.printer.hardware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Low Energy printer, the Android counterpart of `BluetoothPrinterManager`.
 *
 * ```kotlin
 * val printer = BluetoothLePrinter(context)
 * printer.onNearbyPrintersChanged = { list -> … }
 * printer.startScan()                       // needs BLUETOOTH_SCAN (31+) or location (≤30)
 * printer.connect(list[0].device)
 * printer.onStateChanged = { state, _ -> if (state == BluetoothLePrinter.State.CONNECTED) printer.write(receipt) }
 * ```
 *
 * Writes are split into [writeChunkSize] packets (20 bytes by default: many printer
 * modules negotiate a large MTU but silently truncate every packet after byte 20, which
 * shows up as garbage – the Gprinter GP-58 does exactly this). Each packet waits for the
 * stack's `onCharacteristicWrite` before the next one is sent, so receipts of any length
 * (images included) arrive intact. Set [writeChunkSize] to `0` to use the negotiated MTU
 * once you know the printer handles it.
 *
 * All callbacks ([diagnostics], [errorReport], [onNearbyPrintersChanged], [onStateChanged]
 * and write completions) run on the main thread.
 */
@SuppressLint("MissingPermission")
class BluetoothLePrinter(context: Context) : Printer {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val thread = HandlerThread("printer-ble").apply { start() }
    private val handler = Handler(thread.looper)

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    enum class WriteType {
        /** `WRITE_TYPE_NO_RESPONSE` when the characteristic supports it, else `WRITE_TYPE_DEFAULT`. */
        AUTOMATIC,

        /** Always ask for an ATT acknowledgement. Slower, but some modules drop unacknowledged packets. */
        WITH_RESPONSE,
        WITHOUT_RESPONSE,
    }

    /** A printer seen while scanning. */
    data class Device(val name: String?, val address: String, val rssi: Int, val device: BluetoothDevice) {
        val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: address

        /**
         * `true` when the stack knows the printer as a dual-mode (BR/EDR + LE) device. Such
         * printers also speak SPP, and on Android that is the path to prefer: see
         * [BluetoothSppPrinter] and the README's GP-58 notes.
         */
        val isDualMode: Boolean
            get() = runCatching { device.type == BluetoothDevice.DEVICE_TYPE_DUAL }.getOrDefault(false)
    }

    /** Maximum bytes per BLE write; `0` uses the negotiated MTU. Keep 20 unless the printer is known to cope. */
    var writeChunkSize: Int = 20

    /** Pause between packets. A few milliseconds remove white lines in images on slow printers. */
    var interChunkDelayMillis: Long = 0

    /** How packets are written. Try [WriteType.WITH_RESPONSE] if a printer connects but stays silent. */
    var writeType: WriteType = WriteType.AUTOMATIC

    /** MTU requested after connecting (`0` skips the request). The stack negotiates down as needed. */
    var requestMtu: Int = 512

    /**
     * `BluetoothGatt.CONNECTION_PRIORITY_*` requested right after connecting, or `-1` to leave
     * the interval to the stack.
     *
     * Defaults to `CONNECTION_PRIORITY_BALANCED` (30–50 ms), the kind of link cheap printer
     * modules are comfortable with once printing starts. It is applied after the stack's own
     * service discovery, so it does **not** prevent the 7.5 ms interval Android forces during
     * that discovery, which kills the BLE side of dual-mode printers such as the Gprinter
     * GP-58 (see the README: use SPP for those on Android).
     */
    var connectionPriority: Int = BluetoothGatt.CONNECTION_PRIORITY_BALANCED

    var connectTimeoutMillis: Long = 15_000

    /** Fail a write when the stack does not acknowledge a packet within this time. */
    var writeTimeoutMillis: Long = 5_000

    /** Verbose trace: scan results, services, chosen characteristic, MTU, packet counts. */
    var diagnostics: ((String) -> Unit)? = null

    var errorReport: ((PError) -> Unit)? = null

    var onNearbyPrintersChanged: ((List<Device>) -> Unit)? = null

    var onStateChanged: ((State, Device?) -> Unit)? = null

    // ---- state below is only touched on `handler`; the @Volatile copies are read-only snapshots ----

    private val discovered = LinkedHashMap<String, Device>()

    @Volatile
    var nearbyPrinters: List<Device> = emptyList()
        private set

    @Volatile
    var state: State = State.DISCONNECTED
        private set

    @Volatile
    var connectedDevice: Device? = null
        private set

    @Volatile
    private var candidates: List<BluetoothGattCharacteristic> = emptyList()

    @Volatile
    private var writable: BluetoothGattCharacteristic? = null

    @Volatile
    var mtu: Int = 23
        private set

    private var gatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private var discovering = false
    private var discoveryAttempts = 0
    private var inFlight = false
    private var rejectedWrites = 0
    private val pending = ArrayDeque<WriteJob>()
    private var connectTimeout: Runnable? = null
    private var mtuFallback: Runnable? = null
    private var writeTimeout: Runnable? = null

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    val isScanning: Boolean get() = scanCallback != null

    /** `true` once connected and a writable characteristic was found. */
    val canPrint: Boolean get() = state == State.CONNECTED && writable != null

    /** Every writable characteristic of the connected printer as `(service, characteristic)`. */
    val writableCharacteristics: List<Pair<UUID, UUID>>
        get() = candidates.map { (it.service?.uuid ?: UUID(0, 0)) to it.uuid }

    /** The characteristic writes currently go to. */
    val currentCharacteristic: Pair<UUID, UUID>?
        get() = writable?.let { (it.service?.uuid ?: UUID(0, 0)) to it.uuid }

    /** Redirect writes to another of [writableCharacteristics]. */
    fun useCharacteristic(characteristic: UUID, service: UUID? = null): Boolean {
        val match = candidates.firstOrNull { it.uuid == characteristic && (service == null || it.service?.uuid == service) }
            ?: return false
        handler.post {
            writable = match
            diag("using characteristic ${match.uuid} (service ${match.service?.uuid})")
        }
        return true
    }

    // MARK: - Scanning

    /**
     * Starts scanning for printers that advertise one of [specifiedServices] (every device
     * when the list is empty). Returns [PError.DeviceNotReady] when Bluetooth is off or the
     * permission is missing.
     */
    fun startScan(): PError? {
        val adapter = adapter ?: return PError.DeviceNotReady
        if (!adapter.isEnabled) return PError.DeviceNotReady
        val scanner = adapter.bluetoothLeScanner ?: return PError.DeviceNotReady
        if (scanCallback != null) return null

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post { addScanResult(result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                handler.post { results.forEach(::addScanResult) }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    diag("scan failed, error $errorCode")
                    scanCallback = null
                    report(PError.DeviceNotReady)
                }
            }
        }
        val filters = specifiedServices.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(filters.ifEmpty { null }, settings, callback)
            scanCallback = callback
            diag("scan started (${if (filters.isEmpty()) "no filter" else "${filters.size} service filters"})")
            null
        } catch (e: SecurityException) {
            diag("scan refused: ${e.message}")
            PError.DeviceNotReady
        } catch (e: IllegalStateException) {
            PError.DeviceNotReady
        }
    }

    fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        diag("scan stopped")
    }

    /** Forgets every device seen so far. */
    fun clearNearbyPrinters() {
        handler.post {
            discovered.clear()
            publishNearby()
        }
    }

    private fun addScanResult(result: ScanResult) {
        val device = result.device ?: return
        val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
        val existing = discovered[device.address]
        val entry = Device(name ?: existing?.name, device.address, result.rssi, device)
        if (existing == null) diag("found ${entry.displayName} rssi ${result.rssi}")
        if (existing == null || existing.name != entry.name || Math.abs(existing.rssi - entry.rssi) >= 5) {
            discovered[device.address] = entry
            publishNearby()
        }
    }

    private fun publishNearby() {
        val list = discovered.values.toList()
        nearbyPrinters = list
        MainThread.post { onNearbyPrintersChanged?.invoke(list) }
    }

    // MARK: - Connection

    fun connect(device: Device) = connect(device.device)

    /** Connects by MAC address, e.g. one remembered from an earlier session. */
    fun connect(address: String) {
        val adapter = adapter ?: return report(PError.DeviceNotReady)
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return report(PError.ConnectFailed)
        connect(device)
    }

    fun connect(device: BluetoothDevice) {
        handler.post {
            if (adapter?.isEnabled != true) {
                report(PError.DeviceNotReady)
                return@post
            }
            if (state != State.DISCONNECTED) {
                if (connectedDevice?.address == device.address) return@post
                closeGatt()
            }
            stopScan()
            val entry = discovered[device.address]
                ?: Device(runCatching { device.name }.getOrNull(), device.address, 0, device)
            connectedDevice = entry
            setState(State.CONNECTING)
            diag("connect ${entry.displayName}")

            val g = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(appContext, false, gattCallback)
                }
            } catch (e: SecurityException) {
                null
            }
            if (g == null) {
                connectedDevice = null
                setState(State.DISCONNECTED)
                report(PError.ConnectFailed)
                return@post
            }
            gatt = g
            val timeout = Runnable {
                if (state == State.CONNECTING) {
                    diag("connect timeout after ${connectTimeoutMillis} ms")
                    closeGatt()
                    setState(State.DISCONNECTED)
                    report(PError.ConnectFailed)
                }
            }
            connectTimeout = timeout
            handler.postDelayed(timeout, connectTimeoutMillis)
        }
    }

    fun disconnect() {
        handler.post {
            val g = gatt
            if (g == null || state == State.DISCONNECTED) return@post
            if (state == State.CONNECTED) {
                setState(State.DISCONNECTING)
                failPending(PError.Disconnected)
                runCatching { g.disconnect() }
                // onConnectionStateChange closes; make sure we do not hang if it never arrives.
                handler.postDelayed({
                    if (state == State.DISCONNECTING) {
                        closeGatt()
                        setState(State.DISCONNECTED)
                    }
                }, 3_000)
            } else {
                closeGatt()
                setState(State.DISCONNECTED)
            }
        }
    }

    /** Disconnects and stops the worker thread. The instance cannot be used afterwards. */
    fun close() {
        stopScan()
        handler.post {
            closeGatt()
            setState(State.DISCONNECTED)
            thread.quitSafely()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) {
                    runCatching { g.close() }
                    return@post
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            diag("connected with status $status, closing")
                            closeGatt()
                            setState(State.DISCONNECTED)
                            report(PError.ConnectFailed)
                            return@post
                        }
                        if (connectionPriority >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            val ok = runCatching { g.requestConnectionPriority(connectionPriority) }.getOrDefault(false)
                            diag("connected, requestConnectionPriority($connectionPriority) → $ok")
                        }
                        diag("connected, requesting MTU $requestMtu")
                        if (requestMtu > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && g.requestMtu(requestMtu)) {
                            val fallback = Runnable {
                                diag("no MTU response, discovering services with MTU $mtu")
                                discoverServices(g)
                            }
                            mtuFallback = fallback
                            handler.postDelayed(fallback, 2_000)
                        } else {
                            discoverServices(g)
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val wasConnecting = state == State.CONNECTING
                        diag("disconnected, status $status")
                        closeGatt()
                        setState(State.DISCONNECTED)
                        if (wasConnecting) report(PError.ConnectFailed)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                mtuFallback?.let { handler.removeCallbacks(it) }
                mtuFallback = null
                this@BluetoothLePrinter.mtu = if (status == BluetoothGatt.GATT_SUCCESS && mtu > 0) mtu else 23
                diag("mtu ${this@BluetoothLePrinter.mtu} (status $status)")
                discoverServices(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    diag("service discovery failed, status $status")
                    closeGatt()
                    setState(State.DISCONNECTED)
                    report(PError.ConnectFailed)
                    return@post
                }
                if (g.services.isNullOrEmpty() && discoveryAttempts < MAX_DISCOVERY_ATTEMPTS) {
                    // The stack runs its own discovery right after connecting; a discoverServices()
                    // that overlaps it completes "successfully" with an empty list. Ask again.
                    diag("service list empty (attempt $discoveryAttempts), retrying in $DISCOVERY_RETRY_MILLIS ms")
                    discovering = false
                    handler.postDelayed({ if (g === gatt && state == State.CONNECTING) discoverServices(g) }, DISCOVERY_RETRY_MILLIS)
                    return@post
                }
                selectCharacteristic(g)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                writeTimeout?.let { handler.removeCallbacks(it) }
                writeTimeout = null
                inFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    diag("write failed, status $status")
                    failPending(PError.WriteFailed(GattException(status)))
                } else if (interChunkDelayMillis > 0) {
                    handler.postDelayed({ pump() }, interChunkDelayMillis)
                } else {
                    pump()
                }
            }
        }
    }

    private fun discoverServices(g: BluetoothGatt) {
        if (discovering) return
        discovering = true
        discoveryAttempts++
        if (!g.discoverServices()) {
            diag("discoverServices() refused")
            closeGatt()
            setState(State.DISCONNECTED)
            report(PError.ConnectFailed)
        }
    }

    private fun selectCharacteristic(g: BluetoothGatt) {
        val all = g.services ?: emptyList()
        val wanted = specifiedServices.toSet()
        val services = all.filter { wanted.isEmpty() || it.uuid in wanted }.ifEmpty { all }
        for (service in all) {
            diag("service ${service.uuid}: " + service.characteristics.joinToString { "${it.uuid}[${propertyNames(it.properties)}]" })
        }
        val found = services.flatMap { s ->
            s.characteristics.filter { c ->
                c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
        }
        candidates = found
        val chosen = characteristicPriority.firstNotNullOfOrNull { p -> found.firstOrNull { it.uuid == p } }
            ?: found.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 }
            ?: found.firstOrNull()
        writable = chosen
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
        setState(State.CONNECTED)
        if (chosen == null) {
            diag("no writable characteristic")
            report(PError.NotSupported)
        } else {
            diag("writing to ${chosen.uuid} (service ${chosen.service?.uuid}), ${propertyNames(chosen.properties)}")
            pump()
        }
    }

    private fun propertyNames(properties: Int): String = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeNoResponse")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.joinToString(",")

    private fun closeGatt() {
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
        mtuFallback?.let { handler.removeCallbacks(it) }
        mtuFallback = null
        writeTimeout?.let { handler.removeCallbacks(it) }
        writeTimeout = null
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
        writable = null
        candidates = emptyList()
        discovering = false
        discoveryAttempts = 0
        inFlight = false
        rejectedWrites = 0
        mtu = 23
        failPending(PError.Disconnected)
    }

    private fun setState(newState: State) {
        if (state == newState) return
        state = newState
        if (newState == State.DISCONNECTED) connectedDevice = null
        val device = connectedDevice
        MainThread.post { onStateChanged?.invoke(newState, device) }
    }

    private fun report(error: PError) {
        MainThread.post { errorReport?.invoke(error) }
    }

    private fun diag(message: String) {
        val callback = diagnostics ?: return
        MainThread.post { callback(message) }
    }

    // MARK: - Writing

    private class WriteJob(val data: ByteArray, val callback: ((PError?) -> Unit)?) {
        var offset = 0
    }

    override fun write(bytes: ByteArray, callback: ((PError?) -> Unit)?) {
        handler.post {
            val c = writable
            if (gatt == null || c == null || state != State.CONNECTED) {
                MainThread.post { callback?.invoke(PError.DeviceNotReady) }
                return@post
            }
            val size = packetSize()
            val type = if (effectiveWriteType(c) == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) "withoutResponse" else "withResponse"
            diag("write ${bytes.size} bytes to ${c.uuid} as $type, mtu $mtu, chunk $size → ${(bytes.size + size - 1) / size} packets")
            pending.addLast(WriteJob(bytes, callback))
            pump()
        }
    }

    private fun packetSize(): Int {
        val payloadMax = maxOf(1, mtu - 3)
        return if (writeChunkSize > 0) minOf(writeChunkSize, payloadMax) else payloadMax
    }

    private fun effectiveWriteType(characteristic: BluetoothGattCharacteristic): Int {
        val canNoResponse = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val canResponse = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        return when (writeType) {
            WriteType.WITH_RESPONSE -> if (canResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            WriteType.WITHOUT_RESPONSE -> if (canNoResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            WriteType.AUTOMATIC -> if (canNoResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
    }

    /** Sends the next packet; resumed by `onCharacteristicWrite`. Runs on `handler`. */
    private fun pump() {
        while (true) {
            if (inFlight) return
            val job = pending.firstOrNull() ?: return
            val g = gatt
            val c = writable
            if (g == null || c == null || state != State.CONNECTED) {
                failPending(PError.Disconnected)
                return
            }
            if (job.offset >= job.data.size) {
                pending.removeFirst()
                MainThread.post { job.callback?.invoke(null) }
                continue
            }
            val end = minOf(job.offset + packetSize(), job.data.size)
            val chunk = job.data.copyOfRange(job.offset, end)
            val type = effectiveWriteType(c)
            val accepted = writeCharacteristic(g, c, chunk, type)
            if (!accepted) {
                rejectedWrites++
                if (rejectedWrites > 40) {
                    diag("writeCharacteristic kept refusing, giving up")
                    failPending(PError.WriteFailed(IOException("writeCharacteristic refused")))
                } else {
                    handler.postDelayed({ pump() }, 25)
                }
                return
            }
            rejectedWrites = 0
            job.offset = end
            inFlight = true
            val timeout = Runnable {
                if (inFlight) {
                    diag("no write acknowledgement within $writeTimeoutMillis ms")
                    inFlight = false
                    failPending(PError.WriteFailed(IOException("write timeout")))
                }
            }
            writeTimeout = timeout
            handler.postDelayed(timeout, writeTimeoutMillis)
            return
        }
    }

    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = type
                    c.value = value
                    g.writeCharacteristic(c)
                }
            }
        } catch (e: SecurityException) {
            false
        }

    private fun failPending(error: PError) {
        inFlight = false
        writeTimeout?.let { handler.removeCallbacks(it) }
        writeTimeout = null
        if (pending.isEmpty()) return
        val jobs = pending.toList()
        pending.clear()
        MainThread.post { jobs.forEach { it.callback?.invoke(error) } }
    }

    /** A GATT status code reported by the stack while writing. */
    class GattException(val status: Int) : IOException("GATT status $status")

    companion object {
        private const val MAX_DISCOVERY_ATTEMPTS = 6
        private const val DISCOVERY_RETRY_MILLIS = 400L

        /** Expands a 16-bit Bluetooth SIG UUID like `"FFE1"` to its 128-bit form. */
        @JvmStatic
        fun uuid16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805F9B34FB")

        /**
         * GATT services that identify a printer. Scanning filters on these; an empty list
         * scans for every device. Change it **before** calling [startScan].
         *
         * - `E7810A71-…` Gprinter / Xprinter / Munbyn (ESC/POS BLE module)
         * - `49535343-…` Microchip ISSC (many 58 mm "mini" printers, GoojPRT, PT-210)
         * - `18F0` Epson / generic "printer" service
         * - `FFE0`, `FF00` HM-10 / Telink style UART bridges
         */
        @JvmStatic
        var specifiedServices: List<UUID> = listOf(
            UUID.fromString("E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"),
            UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455"),
            uuid16("18F0"),
            uuid16("FFE0"),
            uuid16("FF00"),
        )

        /**
         * Write characteristics in order of preference. The first match wins, otherwise the
         * first characteristic with write-without-response, otherwise any writable one.
         */
        @JvmStatic
        var characteristicPriority: List<UUID> = listOf(
            UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"), // Gprinter / Xprinter
            UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3"), // Microchip ISSC
            uuid16("FFE1"),
            uuid16("FF02"),
            uuid16("2AF1"), // 18F0 "printer" service; on some modules this is a dead end
        )
    }
}

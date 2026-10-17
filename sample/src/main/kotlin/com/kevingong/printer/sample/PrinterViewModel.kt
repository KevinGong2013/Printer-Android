package com.kevingong.printer.sample

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.hardware.usb.UsbDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.kevingong.printer.hardware.BluetoothClassicScanner
import com.kevingong.printer.hardware.BluetoothLePrinter
import com.kevingong.printer.hardware.BluetoothSppPrinter
import com.kevingong.printer.hardware.NetworkPrinter
import com.kevingong.printer.hardware.PError
import com.kevingong.printer.hardware.Printer
import com.kevingong.printer.hardware.UsbPrinter
import com.kevingong.printer.hardware.VirtualPrinter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Transport(val title: String) { BLE("BLE"), SPP("SPP"), USB("USB"), NETWORK("网络"), VIRTUAL("虚拟") }

class PrinterViewModel(app: Application) : AndroidViewModel(app) {
    var transport by mutableStateOf(Transport.BLE)
    var paper80 by mutableStateOf(false)
    var withImage by mutableStateOf(true)
    var busy by mutableStateOf(false)
    val logs = mutableStateListOf<String>()

    val receiptBytes: ByteArray get() = SampleReceipt.build(paper80, withImage).data

    // BLE
    val ble = BluetoothLePrinter(app)
    var bleDevices by mutableStateOf<List<BluetoothLePrinter.Device>>(emptyList())
    var bleState by mutableStateOf(BluetoothLePrinter.State.DISCONNECTED)
    var bleConnected by mutableStateOf<BluetoothLePrinter.Device?>(null)
    var bleScanning by mutableStateOf(false)
    var bleUseMtu by mutableStateOf(false)
    var bleWithResponse by mutableStateOf(false)

    // SPP
    private val classicScanner = BluetoothClassicScanner(app)
    var sppDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    var sppSelected by mutableStateOf<BluetoothDevice?>(null)
    var sppScanning by mutableStateOf(false)
    private var sppPrinter: BluetoothSppPrinter? = null

    // USB
    var usbDevices by mutableStateOf<List<UsbDevice>>(emptyList())
    var usbSelected by mutableStateOf<UsbDevice?>(null)

    // Network
    var host by mutableStateOf("192.168.1.50")
    var port by mutableStateOf("9100")

    // Virtual
    var token by mutableStateOf("")
    var virtualBaseUrl by mutableStateOf(VirtualPrinter.DEFAULT_BASE_URL)

    init {
        ble.diagnostics = { log("BLE  $it") }
        ble.errorReport = { log("BLE  错误: $it") }
        ble.onNearbyPrintersChanged = { bleDevices = it }
        ble.onStateChanged = { state, device ->
            bleState = state
            bleConnected = if (state == BluetoothLePrinter.State.CONNECTED) device else null
            if (state == BluetoothLePrinter.State.CONNECTED && device != null) log("BLE  已连接 ${device.displayName}")
        }
        classicScanner.onDeviceFound = { device ->
            if (sppDevices.none { it.address == device.address }) sppDevices = sppDevices + device
        }
        classicScanner.onFinished = {
            sppScanning = false
            log("SPP  搜索结束，共 ${sppDevices.size} 台")
        }
    }

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        logs.add(0, "$time  $message")
        android.util.Log.i("PrinterSample", message)
        if (logs.size > 300) logs.removeAt(logs.lastIndex)
    }

    fun clearLogs() = logs.clear()

    // MARK: BLE

    fun toggleBleScan() {
        if (ble.isScanning) {
            ble.stopScan()
            bleScanning = false
        } else {
            ble.clearNearbyPrinters()
            val error = ble.startScan()
            bleScanning = error == null
            if (error != null) log("BLE  无法扫描: $error（蓝牙是否已开启？权限是否已授予？）")
        }
    }

    fun connectBle(device: BluetoothLePrinter.Device) {
        bleScanning = false
        ble.connect(device)
    }

    fun disconnectBle() = ble.disconnect()

    // MARK: SPP

    fun loadBonded() {
        sppDevices = BluetoothSppPrinter.bondedDevices(getApplication())
        log("SPP  已配对设备 ${sppDevices.size} 台")
    }

    fun toggleSppScan() {
        if (sppScanning) {
            classicScanner.stop()
            sppScanning = false
        } else {
            loadBonded()
            val error = classicScanner.start()
            sppScanning = error == null
            if (error != null) log("SPP  无法搜索: $error")
        }
    }

    fun selectSpp(device: BluetoothDevice) {
        if (sppSelected?.address != device.address) {
            sppPrinter?.disconnect()
            sppPrinter = null
        }
        sppSelected = device
    }

    // MARK: USB

    fun refreshUsb() {
        usbDevices = UsbPrinter.allDevices(getApplication())
        usbSelected = usbDevices.firstOrNull { UsbPrinter.isPrinter(it) } ?: usbDevices.firstOrNull()
        log("USB  设备 ${usbDevices.size} 台，其中打印机类 ${usbDevices.count { UsbPrinter.isPrinter(it) }} 台")
    }

    // MARK: Printing

    private fun currentPrinter(): Printer? = when (transport) {
        Transport.BLE -> {
            if (!ble.canPrint) {
                log("BLE  尚未连接打印机")
                null
            } else {
                ble.writeChunkSize = if (bleUseMtu) 0 else 20
                ble.writeType = if (bleWithResponse) BluetoothLePrinter.WriteType.WITH_RESPONSE else BluetoothLePrinter.WriteType.AUTOMATIC
                ble
            }
        }

        Transport.SPP -> {
            val device = sppSelected
            if (device == null) {
                log("SPP  请先选择设备")
                null
            } else {
                sppPrinter?.takeIf { it.device.address == device.address } ?: BluetoothSppPrinter(device).also {
                    it.diagnostics = { m -> log("SPP  $m") }
                    sppPrinter = it
                }
            }
        }

        Transport.USB -> {
            val device = usbSelected
            if (device == null) {
                log("USB  请先选择设备")
                null
            } else {
                UsbPrinter(getApplication(), device).also { it.diagnostics = { m -> log("USB  $m") } }
            }
        }

        Transport.NETWORK -> {
            val p = port.toIntOrNull()
            if (host.isBlank() || p == null) {
                log("网络 请填写 IP 和端口")
                null
            } else {
                NetworkPrinter(host.trim(), p)
            }
        }

        Transport.VIRTUAL -> {
            if (!VirtualPrinter.isValidToken(token)) {
                log("虚拟 token 须为 6 位大写字母/数字，由网页显示")
                null
            } else {
                VirtualPrinter(token, virtualBaseUrl.ifBlank { VirtualPrinter.DEFAULT_BASE_URL }).also {
                    it.printerName = "Android Sample"
                    it.onAccepted = { job -> log("虚拟 网页已收到 job ${job.id}, ${job.bytes} 字节") }
                }
            }
        }
    }

    fun print() {
        val printer = currentPrinter() ?: return
        val bytes = receiptBytes
        busy = true
        val started = System.currentTimeMillis()
        log("${transport.title} 发送 ${bytes.size} 字节…")
        printer.write(bytes) { error: PError? ->
            busy = false
            val ms = System.currentTimeMillis() - started
            log(if (error == null) "${transport.title} 发送完成，${ms} ms" else "${transport.title} 失败: $error")
        }
    }

    override fun onCleared() {
        ble.close()
        sppPrinter?.disconnect()
        classicScanner.stop()
    }
}

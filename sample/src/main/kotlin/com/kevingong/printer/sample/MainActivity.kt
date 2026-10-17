package com.kevingong.printer.sample

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevingong.printer.hardware.BluetoothLePrinter
import com.kevingong.printer.hardware.UsbPrinter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PrinterSampleApp()
                }
            }
        }
    }
}

private fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterSampleApp(model: PrinterViewModel = viewModel()) {
    var pendingAction: (() -> Unit)? by remember { mutableStateOf(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            pendingAction?.invoke()
        } else {
            model.log("蓝牙权限被拒绝，无法扫描/连接")
        }
        pendingAction = null
    }
    val withBluetoothPermission: (() -> Unit) -> Unit = { action ->
        pendingAction = action
        permissionLauncher.launch(bluetoothPermissions())
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Printer Sample") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TabRow(selectedTabIndex = model.transport.ordinal) {
                Transport.entries.forEach { t ->
                    Tab(selected = model.transport == t, onClick = { model.transport = t }, text = { Text(t.title) })
                }
            }

            when (model.transport) {
                Transport.BLE -> BleSection(model, withBluetoothPermission)
                Transport.SPP -> SppSection(model, withBluetoothPermission)
                Transport.USB -> UsbSection(model)
                Transport.NETWORK -> NetworkSection(model)
                Transport.VIRTUAL -> VirtualSection(model)
            }

            ReceiptSection(model)
            LogSection(model)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DeviceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Text("✓")
    }
}

@Composable
private fun BleSection(model: PrinterViewModel, withPermission: (() -> Unit) -> Unit) {
    SectionCard("蓝牙 BLE（GATT，默认 20 字节分片）") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { withPermission { model.toggleBleScan() } }) {
                Text(if (model.bleScanning) "停止扫描" else "扫描")
            }
            if (model.bleScanning) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.weight(1f))
            val connected = model.bleConnected
            Text(
                when (model.bleState) {
                    BluetoothLePrinter.State.CONNECTED -> "已连接 ${connected?.displayName ?: ""}"
                    BluetoothLePrinter.State.CONNECTING -> "连接中…"
                    BluetoothLePrinter.State.DISCONNECTING -> "断开中…"
                    BluetoothLePrinter.State.DISCONNECTED -> "未连接"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (model.bleState != BluetoothLePrinter.State.DISCONNECTED) {
                TextButton(onClick = { model.disconnectBle() }) { Text("断开") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !model.bleUseMtu, onClick = { model.bleUseMtu = false }, label = { Text("20 字节/包") })
            FilterChip(selected = model.bleUseMtu, onClick = { model.bleUseMtu = true }, label = { Text("按 MTU (${model.ble.mtu})") })
            FilterChip(selected = model.bleWithResponse, onClick = { model.bleWithResponse = !model.bleWithResponse }, label = { Text("带响应写") })
        }
        if (model.bleDevices.isEmpty()) {
            Text("点击「扫描」查找广播打印机服务的设备（如 Gprinter_3073）。", style = MaterialTheme.typography.bodySmall)
        }
        model.bleDevices.forEach { device ->
            DeviceRow(
                title = device.displayName,
                subtitle = "${device.address}  RSSI ${device.rssi}",
                selected = model.bleConnected?.address == device.address,
                onClick = { withPermission { model.connectBle(device) } },
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun SppSection(model: PrinterViewModel, withPermission: (() -> Unit) -> Unit) {
    SectionCard("经典蓝牙 SPP（RFCOMM 00001101）") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { withPermission { model.loadBonded() } }) { Text("已配对设备") }
            OutlinedButton(onClick = { withPermission { model.toggleSppScan() } }) {
                Text(if (model.sppScanning) "停止搜索" else "搜索附近")
            }
            model.sppSelected?.takeIf { it.bondState != BluetoothDevice.BOND_BONDED }?.let { device ->
                TextButton(onClick = { withPermission { model.log("SPP  createBond → " + runCatching { device.createBond() }.getOrDefault(false)) } }) { Text("配对") }
            }
            if (model.sppScanning) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
        }
        if (model.sppDevices.isEmpty()) {
            Text("先在系统设置里配对打印机，或点「搜索附近」。", style = MaterialTheme.typography.bodySmall)
        }
        model.sppDevices.forEach { device: BluetoothDevice ->
            val name = runCatching { device.name }.getOrNull() ?: "(未命名)"
            DeviceRow(
                title = name,
                subtitle = device.address + if (device.bondState == BluetoothDevice.BOND_BONDED) "  已配对" else "",
                selected = model.sppSelected?.address == device.address,
                onClick = { model.selectSpp(device) },
            )
        }
    }
}

@Composable
private fun UsbSection(model: PrinterViewModel) {
    LaunchedEffect(Unit) { model.refreshUsb() }
    SectionCard("USB（打印机类 interface 7，bulk OUT）") {
        Button(onClick = { model.refreshUsb() }) { Text("刷新设备") }
        if (model.usbDevices.isEmpty()) {
            Text("通过 OTG 连接打印机后点「刷新设备」。首次打印会弹出系统授权。", style = MaterialTheme.typography.bodySmall)
        }
        model.usbDevices.forEach { device ->
            DeviceRow(
                title = UsbPrinter.describe(device),
                subtitle = if (UsbPrinter.isPrinter(device)) "USB Printer Class" else "非打印机类（仍可尝试 bulk OUT）",
                selected = model.usbSelected?.deviceId == device.deviceId,
                onClick = { model.usbSelected = device },
            )
        }
    }
}

@Composable
private fun NetworkSection(model: PrinterViewModel) {
    SectionCard("网络（TCP raw / JetDirect）") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = model.host, onValueChange = { model.host = it }, label = { Text("IP / 主机名") }, modifier = Modifier.weight(2f), singleLine = true)
            OutlinedTextField(value = model.port, onValueChange = { model.port = it.filter(Char::isDigit) }, label = { Text("端口") }, modifier = Modifier.weight(1f), singleLine = true)
        }
    }
}

@Composable
private fun VirtualSection(model: PrinterViewModel) {
    SectionCard("虚拟打印机（网页实时渲染）") {
        Text("打开 ${model.virtualBaseUrl.ifBlank { "https://printer.baici.tech" }}，把页面显示的 6 位 token 填到这里。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = model.token,
            onValueChange = { model.token = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
            label = { Text("Token（6 位）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model.virtualBaseUrl,
            onValueChange = { model.virtualBaseUrl = it },
            label = { Text("服务地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReceiptSection(model: PrinterViewModel) {
    val bytes = remember(model.paper80, model.withImage) { model.receiptBytes }
    SectionCard("小票（${bytes.size} 字节）") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !model.paper80, onClick = { model.paper80 = false }, label = { Text("58mm / 384") })
            FilterChip(selected = model.paper80, onClick = { model.paper80 = true }, label = { Text("80mm / 576") })
            Spacer(Modifier.weight(1f))
            Text("图片", style = MaterialTheme.typography.bodySmall)
            Switch(checked = model.withImage, onCheckedChange = { model.withImage = it })
        }
        Text(
            SampleReceipt.hexDump(bytes, 512),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState()),
        )
        Button(onClick = { model.print() }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
            if (model.busy) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("通过 ${model.transport.title} 打印")
        }
    }
}

@Composable
private fun LogSection(model: PrinterViewModel) {
    SectionCard("日志") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${model.logs.size} 条", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { model.clearLogs() }) { Text("清空") }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
            items(model.logs) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

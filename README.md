# Printer-Android

[![CI](https://github.com/KevinGong2013/Printer-Android/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/KevinGong2013/Printer-Android/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/KevinGong2013/Printer-Android.svg)](https://jitpack.io/#KevinGong2013/Printer-Android)
[![Platform](https://img.shields.io/badge/Android-API%2021%2B-blue.svg)](printer/build.gradle.kts)
[![License](https://img.shields.io/github/license/KevinGong2013/Printer-Android)](LICENSE)

Kotlin library for ESC/POS thermal receipt printers on Android over **Bluetooth LE**,
**classic Bluetooth (SPP)**, **USB** and **TCP/IP**, plus a **virtual printer** that renders
receipts in a web page.

It is the Android twin of [KevinGong2013/Printer](https://github.com/KevinGong2013/Printer)
(Swift). Both share the same receipt model, the same item names and defaults, and the same
test vectors: a receipt built with the same items produces byte-for-byte identical ESC/POS
output on iOS and Android, so documentation and printer quirks carry over.

```kotlin
val receipt = receipt(PrinterProfile.paper58(Encodings.GB_18030_2000)) {
    initialize()
    align(Command.Alignment.CENTER)
    characterSize(2, 2)
    line("My Shop")
    characterSize(1, 1)
    dividing()
    align(Command.Alignment.LEFT)
    kv("Coffee", "3.50")
    kv("Total", "3.50")
    image(BitmapSource(logo), binarization = ImageItem.Binarization.Dither)
    qrCode("https://example.com")
    barcode("ORDER-42")
    feed(3)
    feedAndCut(partial = true)
}

printer.write(receipt) { error -> /* null on success, else a PError */ }
```

## Requirements

- Library: Android 5.0+ (API 21), no dependencies besides the Kotlin stdlib.
- Sample app: API 23+ (Jetpack Compose).
- Built with AGP 9 / Kotlin 2.3; consumers need AGP 8.x or newer.

## Installation

Via [JitPack](https://jitpack.io/#KevinGong2013/Printer-Android):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.KevinGong2013:Printer-Android:1.0.1")
}
```

If your build resolves modules individually, the artifact is also available as
`com.github.KevinGong2013.Printer-Android:printer:1.0.1`.

The library manifest declares `INTERNET`, `BLUETOOTH` / `BLUETOOTH_ADMIN` (≤ API 30),
`BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` (`neverForLocation`). Request the runtime
permissions yourself: `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` on Android 12+, otherwise
`ACCESS_FINE_LOCATION` for scanning. USB needs no runtime permission; the system dialog
is shown by `UsbPrinter` on the first write.

## Building a receipt

`Receipt` is a list of `ReceiptItem`s rendered against a `PrinterProfile`
(paper width, character width, text charset). `receipt.data` is the ESC/POS byte array.

| Profile | Dots | Use for |
|---|---|---|
| `PrinterProfile.paper58(charset)` | 384 | 58 mm paper |
| `PrinterProfile.paper80(charset)` | 576 | 80 mm paper |
| `PrinterProfile(maxWidthDensity, fontDensity, encoding)` | any | custom |

Items are appended with the DSL (`receipt(profile) { … }`), with `receipt += item`,
or with `receipt.append(item)`. `receipt += "text"` appends a line.

### Text

| DSL | Item | Output |
|---|---|---|
| `line("Hello")` | `Line` | the line, followed by a line feed |
| `text("Hello")` | `Text` | the text without a line feed (combine several on one line) |
| `kv("Total", "5.50")` | `KVItem` | key left-aligned, value right-aligned, full width (CJK counts as 2 cells) |
| `dividing()` | `Dividing.default()` | a line of `-` across the paper |
| `command(…)` | `Command` | any raw command (see below) |

Styles are plain commands: `emphasis(true)`, `characterSize(2, 2)`, `underline()`,
`align(Command.Alignment.CENTER)`, `command(Command.Layout.LineSpace(Command.LineSpace.Raw(40)))` …
The full `Command` hierarchy mirrors the Swift enum: `Command.Style`, `Command.Layout`,
`Command.Cursor`, `Command.Page`, `Command.Info`, `Command.Peripheral` and `Command.Raw`.

### Images

```kotlin
image(BitmapSource(bitmap))                                            // threshold 128
ImageItem(bitmap, grayThreshold = 160)                                 // lighter images print more black
ImageItem(bitmap, binarization = ImageItem.Binarization.Dither)        // Floyd–Steinberg, best for photos / logos
ImageItem(bitmap, mode = ImageItem.Mode.DOUBLE_WH)                     // printer-side 2× magnification
ImageItem.fromBytes(pngBytes)                                          // PNG / JPEG / WebP bytes (BitmapFactory)
ImageItem.fromBase64("data:image/png;base64,....")                     // base64 string
ImageItem(GrayImage(w, h, grayPixels)) / ImageItem(ArgbImage(w, h, argb))  // pure Kotlin sources
```

- Any `Bitmap` config works (hardware bitmaps are copied). Transparent pixels print white.
- Images wider than the paper are scaled down automatically. Pass
  `scaling = ImageItem.Scaling.NONE` to keep the native size.
- Long images are sent in bands of 64 rows (`rowsPerBand`) so the printer starts
  immediately and never overflows its buffer. If you still see white gaps across
  images, set `interChunkDelayMillis = 10` on the Bluetooth printer.

### QR code and barcodes

```kotlin
qrCode("https://example.com", moduleSize = 6, recovery = QRCode.RecoveryLevel.M)   // moduleSize 1…16 dots
qrCode("中文", encoding = Charsets.UTF_8)                                          // override the profile charset
barcode("4901234567894", symbology = Barcode.Symbology.EAN13, height = 80, text = Barcode.TextPosition.BELOW)
barcode("ORDER-42")                                                                 // CODE128, code set B
```

Both use the printer's built-in symbol generator (`GS ( k` and `GS k` function B).

### Cutting, feeding, cash drawer

```kotlin
feed(3)                                   // ESC d 3
cut()                                     // GS V 0
feedAndCut(partial = true, feed = 30)     // GS V 66 30
command(Command.Page.LegacyCut(false))    // ESC i, for printers without GS V
cashDrawer()                              // ESC p 0 50 250
```

### Encodings, umlauts, €, Arabic, Chinese

Thermal printers have no Unicode. You pick a **code page** on the printer and
encode your strings in the matching **Charset**; both must agree.

| Language | Profile charset | Command |
|---|---|---|
| English only | `Charsets.US_ASCII` | – |
| Western Europe (ä ö ü é €) | `Encodings.WINDOWS_1252` | `codePage(Command.CharacterCodePage.WPC1252)` |
| Chinese (Gprinter / Xprinter …) | `Encodings.GB_18030_2000` | usually none needed |
| Arabic | `Encodings.WINDOWS_ARABIC` | `codePage(Command.CharacterCodePage.WPC1256)` |
| Cyrillic | `Encodings.WINDOWS_1251` | `codePage(Command.CharacterCodePage.WPC1251)` |
| Greek | `Encodings.WINDOWS_1253` | `codePage(Command.CharacterCodePage.WPC1253)` |

```kotlin
val receipt = receipt(PrinterProfile.paper58(Encodings.WINDOWS_1252)) {
    initialize()
    codePage(Command.CharacterCodePage.WPC1252)
    line("Größe: 5 €")
}
```

Characters the chosen charset cannot represent are replaced with `?` instead of
dropping the whole line. `Command.CharacterCodePage` follows the Epson `ESC t` table;
some printers ship with a different table — check the self-test page that prints when
you hold the feed button while powering on.

## Connecting to a printer

Every transport implements `Printer`:

```kotlin
interface Printer {
    fun write(bytes: ByteArray, callback: ((PError?) -> Unit)? = null)
    fun write(receipt: Receipt, callback: ((PError?) -> Unit)? = null)
}
```

Callbacks run on the main thread. `PError` has the same cases as the Swift library:
`DeviceNotReady`, `ConnectFailed`, `Disconnected`, `NotSupported`, `WriteFailed(cause)`.

### Bluetooth LE

```kotlin
val ble = BluetoothLePrinter(context)
ble.diagnostics = { Log.d("BLE", it) }                // services, chosen characteristic, MTU, packets
ble.onNearbyPrintersChanged = { devices -> … }
ble.onStateChanged = { state, device -> if (state == BluetoothLePrinter.State.CONNECTED) ble.write(receipt) }
ble.startScan()                                       // returns PError.DeviceNotReady if Bluetooth is off
ble.connect(devices[0])                               // or ble.connect("AA:BB:CC:DD:EE:FF")
```

`write` splits the data into 20-byte packets (`writeChunkSize`) and waits for the
stack's acknowledgement before sending the next one, so receipts of any length
(images included) arrive intact. 20 bytes is what every module handles — the Gprinter
GP-58, for example, negotiates a large MTU and then silently truncates every packet after
byte 20, which prints as garbage. Printers that accept full-MTU packets print images
several times faster with `writeChunkSize = 0`, so try that once the basics work.
`writeType = WITH_RESPONSE` helps modules that drop unacknowledged packets;
`interChunkDelayMillis` removes white lines in images on slow printers.

Right after connecting the library asks for `CONNECTION_PRIORITY_BALANCED`
(`connectionPriority`, set to `-1` to leave the link alone) and retries service discovery
when the stack answers with an empty list, which happens when the app's discovery overlaps
the one the Android stack starts on its own.

> **Dual-mode printers (GP-58 and friends) on Android: use SPP.** During its own service
> discovery the Android stack forces the connection interval down to 7.5 ms. The BLE module
> in the Gprinter GP-58 cannot keep up, stops answering and the link dies with a
> supervision timeout about three seconds after connecting; afterwards the printer's
> Bluetooth LED stays lit and it has to be power-cycled (seen on Android 12, 7 of 8
> attempts, confirmed with an HCI trace). This happens before the app gets a say, so no
> library setting avoids it; iOS never goes below 15 ms and is unaffected. Such printers are
> dual-mode (`Device.isDualMode`) and print perfectly over [SPP](#classic-bluetooth-spp),
> which is also what the vendor's own Android SDK uses. BLE-only modules are not affected.

Scanning filters on the GATT services used by common BLE printer modules and the write
characteristic is picked by priority:

| Service | Write characteristic | Typical printers |
|---|---|---|
| `E7810A71-73AE-499D-8C15-FAA9AEF0C3F2` | `BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F` | Gprinter, Xprinter, Munbyn |
| `49535343-FE7D-4AE5-8FA9-9FAFD205E455` | `49535343-8841-43F4-A8D4-ECBE34729BB3` | Microchip ISSC: GoojPRT PT-210, many 58 mm minis |
| `18F0` | `2AF1` | Epson, generic |
| `FFE0` / `FF00` | `FFE1` / `FF02` | HM-10 / Telink UART bridges |

Unknown characteristic UUIDs are fine (the first writable one in a matched service is used).
For other service UUIDs set `BluetoothLePrinter.specifiedServices` **before** scanning; an
empty list scans every device. `writableCharacteristics` / `useCharacteristic()` let you
switch when a printer exposes several.

### Classic Bluetooth (SPP)

```kotlin
val device = BluetoothSppPrinter.bondedDevices(context).first { it.name == "Gprinter_3073" }
val spp = BluetoothSppPrinter(device)                 // RFCOMM, UUID 00001101-0000-1000-8000-00805F9B34FB
spp.write(receipt) { error -> … }                    // connects on first use, keeps the socket open
```

`BluetoothClassicScanner` wraps `startDiscovery()` to find unpaired printers; BLE-only
devices (beacons, earbuds, phones) are filtered out because they cannot open an RFCOMM
socket (`includeLeOnlyDevices = true` keeps them). Pairing happens when the socket is first
opened: the system shows the PIN dialog (`0000` on Gprinter) and the connection guard
waits up to `pairingTimeoutMillis` while the bond is in progress. Calling
`device.createBond()` beforehand makes the dialog appear at a moment of your choosing.
This is the transport iOS cannot use.

### USB

```kotlin
val device = UsbPrinter.findPrinters(context).first()  // USB Printer Class (interface class 7)
val usb = UsbPrinter(context, device)
usb.write(receipt) { error -> … }                     // shows the permission dialog on first use
```

Bytes go through the interface's bulk OUT endpoint with `bulkTransfer`. `UsbPrinter.allDevices`
lists vendor-class devices too; the first bulk OUT endpoint is used for those.

### Network

```kotlin
val lan = NetworkPrinter("192.168.1.50")             // TCP 9100, 5 s connect timeout
lan.write(receipt) { error -> … }
```

### Virtual printer (no hardware)

Open <https://printer.baici.tech>, which shows a 6-character token, and send receipts to
it; the page renders them live.

```kotlin
val virtual = VirtualPrinter("AB12CD")               // VirtualPrinter(token, baseUrl = "https://printer.baici.tech")
virtual.printerName = "My App"                       // optional, shown on the page
virtual.onAccepted = { job -> println("${job.id} ${job.bytes} bytes") }
virtual.write(receipt) { error -> … }                // DeviceNotReady when the token is unknown / page closed
```

Protocol: `POST {baseUrl}/api/v1/jobs/{token}`, `Content-Type: application/octet-stream`,
body = raw ESC/POS bytes, optional `X-Printer-Name` header. `202` with
`{"id":"…","bytes":N}` means the page received it; `404` maps to `PError.DeviceNotReady`
(token unknown or page offline); other non-2xx map to `PError.WriteFailed(HttpException)`.
The Swift library implements the same client.

`DummyPrinter` records every payload (`history`, `onWrite`) for unit tests.

## Sample app

[`sample/`](sample) is a Compose app that scans / connects BLE and SPP printers, lists USB
devices, takes an IP or a virtual-printer token, builds a receipt with text styles,
key/value lines, a dithered logo, QR code, barcode and a cut, shows the hex dump and sends
it. Install with `./gradlew :sample:installDebug`.

## Relationship to the Swift library

| Swift (`KevinGong2013/Printer`) | Kotlin (this library) |
|---|---|
| `PrinterProfile.🖨️58(.utf8)` | `PrinterProfile.paper58(Charsets.UTF_8)` |
| `String.GBEncoding.GB_18030_2000` | `Encodings.GB_18030_2000` |
| `receipt <<< "text"` | `line("text")` / `receipt += "text"` |
| `receipt <<~ .style(.initialize)` | `command(Command.Style.Initialize)` / `initialize()` |
| `Text`, `KVItem`, `Dividing.default()` | same names |
| `ImageItem(cgImage, binarization: .dither)` | `ImageItem(bitmap, binarization = ImageItem.Binarization.Dither)` |
| `QRCode(content:moduleSize:recovery:model:encoding:)` | `QRCode(content, moduleSize, recovery, model, encoding)` |
| `Barcode(_:symbology:height:moduleWidth:text:font:)` | `Barcode(content, symbology, height, moduleWidth, text, font)` |
| `Command.style(.characterSize(width: 2, height: 2))` | `Command.Style.CharacterSize(2, 2)` |
| `Command.page(.feedAndCut(partial: true, feed: 0))` | `Command.Page.FeedAndCut(true, 0)` |
| `BluetoothPrinterManager` | `BluetoothLePrinter` |
| – (iOS has no SPP / USB) | `BluetoothSppPrinter`, `UsbPrinter` |
| `NetworkPrinter` | `NetworkPrinter` |
| `SerialPrinter` (macOS) | – |
| `VirtualPrinter` | `VirtualPrinter` |
| `PError` | `PError` |

The unit tests in [`printer/src/test`](printer/src/test) are the Swift tests translated
one by one; their expected bytes are the shared golden vectors.

## Troubleshooting

- **Connected, but nothing prints, or only garbage** – packets are too large for the
  module. Keep `writeChunkSize` at 20 (the default). Power-cycle the printer after your
  app was killed mid-connection.
- **`NotSupported` after connecting** – the printer has no writable characteristic in the
  matched service. Look at `diagnostics` output; add the service UUID to
  `specifiedServices` or pick one with `useCharacteristic`.
- **Scan finds nothing** – check the runtime permission (`BLUETOOTH_SCAN` on 12+, location
  on ≤ 11) and that the printer advertises one of the known services; set
  `specifiedServices = emptyList()` to see every device.
- **White horizontal lines in images** – the printer is fed faster than it prints. Set
  `interChunkDelayMillis = 10`.
- **Garbage instead of accented letters** – profile charset and `codePage` do not match.
- **Last character of a `kv` value wraps to the next line** – a symbol such as `¥` (U+00A5)
  is drawn two cells wide by GBK / GB18030 printers. `printableWidth(profile)` accounts for
  that; make sure the profile carries the printer's real encoding.
- **BLE connection drops ~3 s after connecting, printer LED stays on** – dual-mode printer
  on Android, see [Bluetooth LE](#bluetooth-le). Power-cycle the printer and use SPP.
- **Cut command does nothing** – the printer has no cutter.

## License

Apache License 2.0. See [LICENSE](LICENSE).

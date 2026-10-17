package com.kevingong.printer.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import com.kevingong.printer.Command
import com.kevingong.printer.Encodings
import com.kevingong.printer.PrinterProfile
import com.kevingong.printer.Receipt
import com.kevingong.printer.receipt.BitmapSource
import com.kevingong.printer.receipt.ImageItem
import com.kevingong.printer.receipt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the demo receipt: text styles, key/value lines, a dithered logo, QR, barcode and a cut. */
object SampleReceipt {
    fun profile(paper80: Boolean): PrinterProfile =
        if (paper80) PrinterProfile.paper80(Encodings.GB_18030_2000) else PrinterProfile.paper58(Encodings.GB_18030_2000)

    fun build(paper80: Boolean, withImage: Boolean = true, cut: Boolean = true): Receipt = receipt(profile(paper80)) {
        initialize()
        align(Command.Alignment.CENTER)
        characterSize(2, 2)
        line("Printer Sample")
        characterSize(1, 1)
        line("ESC/POS 示例小票 · Android")
        if (withImage) {
            image(BitmapSource(logo()), binarization = ImageItem.Binarization.Dither)
        }
        feed(1)

        align(Command.Alignment.LEFT)
        dividing()
        kv("咖啡 Coffee x1", "¥12.00")
        kv("芝士蛋糕 Cheesecake x1", "¥18.50")
        kv("矿泉水 Water x2", "¥6.00")
        dividing()
        emphasis(true)
        kv("合计 Total", "¥36.50")
        emphasis(false)
        underline()
        line("已支付 微信支付 WeChat Pay")
        underlineOff()
        command(Command.Style.SelectPrintMode(Command.PrintMode.SELECT_FONT_B))
        line("单号 ORDER-42  " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
        command(Command.Style.SelectPrintMode(Command.PrintMode.RESET))
        dividing('=')

        align(Command.Alignment.CENTER)
        line("扫码查看项目主页")
        qrCode("https://github.com/KevinGong2013/Printer-Android", moduleSize = 6)
        feed(1)
        barcode("ORDER-42", height = 60)
        feed(1)
        line("谢谢惠顾 Thank you!")
        feed(3)
        if (cut) feedAndCut(partial = true, feed = 0)
    }

    /** A 320×96 logo with a gradient bar so the dithering is visible. */
    fun logo(): Bitmap {
        val width = 320
        val height = 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), 24f, paint)
        paint.shader = null
        paint.color = Color.BLACK
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 44f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Printer", width / 2f, 70f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawRoundRect(4f, 30f, width - 4f, height - 4f, 12f, 12f, paint)
        return bitmap
    }

    fun hexDump(bytes: ByteArray, maxBytes: Int = 1024): String {
        val sb = StringBuilder()
        val shown = minOf(bytes.size, maxBytes)
        var i = 0
        while (i < shown) {
            sb.append("%04X  ".format(i))
            val end = minOf(i + 16, shown)
            for (j in i until end) sb.append("%02X ".format(bytes[j].toInt() and 0xFF))
            sb.append('\n')
            i = end
        }
        if (bytes.size > shown) sb.append("… 共 ${bytes.size} 字节\n")
        return sb.toString()
    }
}

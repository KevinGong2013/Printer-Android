package com.kevingong.printer

/**
 * Raw ESC/POS commands. Reference: https://reference.epson-biz.com/modules/ref_escpos/index.php
 *
 * The hierarchy mirrors the Swift `Command` enum: `Command.style(.characterSize(width: 2, height: 2))`
 * becomes `Command.Style.CharacterSize(width = 2, height = 2)`.
 */
sealed class Command : ReceiptItem {
    /** The command bytes. */
    abstract val value: ByteArray

    override fun assemblePrintableData(profile: PrinterProfile): ByteArray = value

    enum class PrintMode(val rawValue: Int) {
        RESET(0),
        /** Default is font A. */
        SELECT_FONT_B(1),
        EMPHASIS(8),
        DOUBLE_HEIGHT(16),
        DOUBLE_WIDTH(32),
        ITALIC(64),
        UNDERLINE(128),
    }

    enum class UnderlineMode(val rawValue: Int) {
        /** Underline off. */
        ENABLE(0),
        ENABLE_1DOT(1),
        ENABLE_2DOT(2),
    }

    enum class CharacterFont(val rawValue: Int) { A(0), B(1), C(2), D(3) }

    /**
     * Code pages for `ESC t n`. Values follow the Epson ESC/POS table, which most
     * clones (Gprinter, Xprinter, Munbyn, Rongta, …) use as well.
     *
     * The receipt's [PrinterProfile.encoding] must match the page you select, e.g.
     * [Encodings.WINDOWS_1252] with [WPC1252], or [Encodings.WINDOWS_ARABIC] with [WPC1256].
     */
    enum class CharacterCodePage(val rawValue: Int) {
        PC437(0), // USA, standard Europe
        KATAKANA(1),
        PC850(2), // Multilingual
        PC860(3), // Portuguese
        PC863(4), // Canadian-French
        PC865(5), // Nordic
        PC851(11), // Greek
        PC853(12), // Turkish
        PC857(13), // Turkish
        PC737(14), // Greek
        ISO8859_7(15), // Greek
        WPC1252(16), // Western Europe (ä ö ü € …)
        PC866(17), // Cyrillic #2
        PC852(18), // Latin 2
        PC858(19), // Euro
        THAI42(20),
        THAI11(21),
        THAI13(22),
        THAI14(23),
        THAI16(24),
        THAI17(25),
        THAI18(26),
        TCVN3_1(30), // Vietnamese
        TCVN3_2(31), // Vietnamese
        PC720(32), // Arabic
        WPC775(33), // Baltic Rim
        PC855(34), // Cyrillic
        PC861(35), // Icelandic
        PC862(36), // Hebrew
        PC864(37), // Arabic
        PC869(38), // Greek
        ISO8859_2(39), // Latin 2
        ISO8859_15(40), // Latin 9
        PC1098(41), // Farsi
        PC1118(42), // Lithuanian
        PC1119(43), // Lithuanian
        PC1125(44), // Ukrainian
        WPC1250(45), // Latin 2
        WPC1251(46), // Cyrillic
        WPC1253(47), // Greek
        WPC1254(48), // Turkish
        WPC1255(49), // Hebrew
        WPC1256(50), // Arabic
        WPC1257(51), // Baltic Rim
        WPC1258(52), // Vietnamese
        KZ1048(53), // Kazakh
        USER_DEFINED(255),
    }

    /** International character set for `ESC R n`. */
    enum class InternationalCharacterSet(val rawValue: Int) {
        USA(0), FRANCE(1), GERMANY(2), UK(3), DENMARK_1(4), SWEDEN(5), ITALY(6), SPAIN_1(7), JAPAN(8), NORWAY(9),
        DENMARK_2(10), SPAIN_2(11), LATIN_AMERICA(12), KOREA(13), SLOVENIA_CROATIA(14), CHINA(15), VIETNAM(16), ARABIA(17),
    }

    enum class Alignment(val rawValue: Int) { LEFT(0), CENTER(1), RIGHT(2) }

    sealed class LineSpace {
        /** `ESC 2` – default 1/6 inch. */
        object L1_6 : LineSpace()
        /** `ESC 0` – 1/8 inch. */
        object L1_8 : LineSpace()
        /** `ESC 3 n` – n vertical motion units. */
        data class Raw(val n: Int) : LineSpace()
    }

    enum class PrinterID(val rawValue: Int) { MODEL_ID(1), TYPE_ID(2), FIRMWARE_REVISION(3) }

    // MARK: - Style (Swift: Command.style(FontControlling))

    sealed class Style : Command() {
        /** `ESC @` */
        object Initialize : Style() {
            override val value get() = bytesOf(27, 64)
        }

        /** Same as [Initialize]. */
        object Clear : Style() {
            override val value get() = bytesOf(27, 64)
        }

        /** `ESC ! n` */
        data class SelectPrintMode(val mode: PrintMode) : Style() {
            override val value get() = bytesOf(27, 33, mode.rawValue)
        }

        /** `ESC - n` */
        data class UnderlineMode(val mode: Command.UnderlineMode) : Style() {
            override val value get() = bytesOf(27, 45, mode.rawValue)
        }

        /** `ESC 4 n` */
        data class ItalicsMode(val enable: Boolean) : Style() {
            override val value get() = bytesOf(27, 52, if (enable) 1 else 0)
        }

        /** `ESC E n` */
        data class Emphasis(val enable: Boolean) : Style() {
            override val value get() = bytesOf(27, 69, if (enable) 1 else 0)
        }

        /** `ESC M n` (fonts A/B), `ESC T` / `ESC U` for C/D on some printers. */
        data class SelectCharacterFont(val font: CharacterFont) : Style() {
            override val value
                get() = when (font) {
                    CharacterFont.A, CharacterFont.B -> bytesOf(27, 77, font.rawValue)
                    CharacterFont.C -> bytesOf(27, 84)
                    CharacterFont.D -> bytesOf(27, 85)
                }
        }

        /** `ESC V n` */
        data class Rotation90(val enable: Boolean) : Style() {
            override val value get() = bytesOf(27, 86, if (enable) 1 else 0)
        }

        /** `ESC t n` */
        data class SelectCharacterCodePage(val page: CharacterCodePage) : Style() {
            override val value get() = bytesOf(27, 116, page.rawValue)
        }

        /** `ESC R n` */
        data class SelectInternationalCharacterSet(val set: InternationalCharacterSet) : Style() {
            override val value get() = bytesOf(27, 82, set.rawValue)
        }

        /** `GS ! n` – width and height magnification, each 1…8. */
        data class CharacterSize(val width: Int, val height: Int) : Style() {
            override val value: ByteArray
                get() {
                    val w = width.coerceIn(1, 8) - 1
                    val h = height.coerceIn(1, 8) - 1
                    return bytesOf(29, 33, (w shl 4) or h)
                }
        }

        /** `ESC { n` */
        data class UpsideDownMode(val enable: Boolean) : Style() {
            override val value get() = bytesOf(27, 123, if (enable) 1 else 0)
        }

        /** `ESC C1 n` – https://escpos.readthedocs.io/en/latest/font_cmds.html#set-cpi-mode-1b-c1-rel */
        data class SetCPIMode(val n: Int) : Style() {
            override val value get() = bytesOf(27, 193, n)
        }

        /** `GS B n` – white on black. */
        data class ReverseMode(val enable: Boolean) : Style() {
            override val value get() = bytesOf(29, 66, if (enable) 1 else 0)
        }

        /** `ESC G n` */
        data class DoubleStrikeMode(val n: Int) : Style() {
            override val value get() = bytesOf(27, 71, n)
        }
    }

    // MARK: - Info (Swift: Command.info(PrinterInformation))

    sealed class Info : Command() {
        /** `GS I n` */
        data class Printer(val id: PrinterID) : Info() {
            override val value get() = bytesOf(29, 73, id.rawValue)
        }

        /** `GS r 1` – paper sensor status. */
        object TransmitStatus : Info() {
            override val value get() = bytesOf(29, 114, 1)
        }

        /** `ESC v` */
        object TransmitPaperSensorStatus : Info() {
            override val value get() = bytesOf(27, 118)
        }

        /** `DLE EOT n` – real-time status (1 printer, 2 offline cause, 3 error cause, 4 paper). */
        data class RealTimeStatus(val n: Int) : Info() {
            override val value get() = bytesOf(16, 4, n.coerceIn(1, 4))
        }
    }

    // MARK: - Cursor (Swift: Command.cursor(CursorPosition))

    sealed class Cursor : Command() {
        object HorizontalTab : Cursor() {
            override val value get() = bytesOf(9)
        }

        object LineFeed : Cursor() {
            override val value get() = bytesOf(10)
        }

        object FormFeed : Cursor() {
            override val value get() = bytesOf(12)
        }

        object CarriageReturn : Cursor() {
            override val value get() = bytesOf(13)
        }

        object CancelCurrentLine : Cursor() {
            override val value get() = bytesOf(24)
        }

        /** `ESC $ nL nH` */
        data class AbsolutePrintPosition(val nL: Int, val nH: Int) : Cursor() {
            override val value get() = bytesOf(27, 36, nL, nH)
        }

        /** `ESC \ nL nH` */
        data class RelativePrintPosition(val nL: Int, val nH: Int) : Cursor() {
            override val value get() = bytesOf(27, 92, nL, nH)
        }
    }

    // MARK: - Page (Swift: Command.page(PageMovement))

    sealed class Page : Command() {
        /** `GS V 1` – partial cut (one point left uncut). */
        object PartialCut : Page() {
            override val value get() = bytesOf(29, 86, 1)
        }

        /** `GS V 0` – full cut. */
        object FullCut : Page() {
            override val value get() = bytesOf(29, 86, 0)
        }

        /** `GS V 65/66 n` – feed `feed` dots, then cut. */
        data class FeedAndCut(val partial: Boolean, val feed: Int) : Page() {
            override val value get() = bytesOf(29, 86, if (partial) 66 else 65, feed)
        }

        /** Legacy `ESC i` / `ESC m` cuts for old printers that do not support `GS V`. */
        data class LegacyCut(val partial: Boolean) : Page() {
            override val value get() = if (partial) bytesOf(27, 109) else bytesOf(27, 105)
        }

        /** No-op kept for parity with the Swift enum. */
        object Ejector : Page() {
            override val value get() = ByteArray(0)
        }

        /** `ESC J 0` – flush the line buffer. */
        object Print : Page() {
            override val value get() = bytesOf(27, 74, 0)
        }

        /** `ESC d n` – print and feed `lines` lines. */
        data class PrintAndFeed(val lines: Int) : Page() {
            override val value get() = bytesOf(27, 100, lines)
        }

        /** `ESC J n` – print and feed `dots` dots (vertical motion units). */
        data class PrintAndFeedPaper(val dots: Int) : Page() {
            override val value get() = bytesOf(27, 74, dots)
        }
    }

    // MARK: - Layout (Swift: Command.layout(Layout))

    sealed class Layout : Command() {
        /** `ESC SP n` */
        data class RightSpacing(val n: Int) : Layout() {
            override val value get() = bytesOf(27, 32, n)
        }

        data class LineSpace(val space: Command.LineSpace) : Layout() {
            override val value
                get() = when (space) {
                    Command.LineSpace.L1_6 -> bytesOf(27, 50)
                    Command.LineSpace.L1_8 -> bytesOf(27, 48)
                    is Command.LineSpace.Raw -> bytesOf(27, 51, space.n)
                }
        }

        /** `ESC a n` */
        data class Justification(val alignment: Alignment) : Layout() {
            override val value get() = bytesOf(27, 97, alignment.rawValue)
        }

        /** `GS L nL nH` – left margin in horizontal motion units. */
        data class LeftMargin(val n: Int) : Layout() {
            override val value get() = bytesOf(29, 76, n % 256, n / 256)
        }

        /** `GS W nL nH` – print area width in horizontal motion units. */
        data class PrintAreaWidth(val n: Int) : Layout() {
            override val value get() = bytesOf(29, 87, n % 256, n / 256)
        }
    }

    // MARK: - Peripheral (Swift: Command.peripheral(Peripheral))

    sealed class Peripheral : Command() {
        /**
         * `ESC p m t1 t2` – pulse the cash drawer kick-out connector.
         * `pin` 0 or 1, times in units of 2 ms (default 100 ms on / 500 ms off).
         */
        data class CashDrawer(val pin: Int = 0, val onTime: Int = 50, val offTime: Int = 250) : Peripheral() {
            override val value get() = bytesOf(27, 112, pin.coerceIn(0, 1), onTime, offTime)
        }
    }

    /** Escape hatch for vendor-specific commands. */
    class Raw(override val value: ByteArray) : Command() {
        constructor(vararg values: Int) : this(bytesOf(*values))

        override fun equals(other: Any?): Boolean = other is Raw && other.value.contentEquals(value)
        override fun hashCode(): Int = value.contentHashCode()
        override fun toString(): String = "Raw(${value.joinToString(",") { (it.toInt() and 0xFF).toString() }})"
    }
}

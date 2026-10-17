package com.kevingong.printer

import org.junit.Test

class CommandTests {
    @Test
    fun codePageUsesESCt() {
        assertBytes(b(27, 116, 16), Command.Style.SelectCharacterCodePage(Command.CharacterCodePage.WPC1252).value)
        assertBytes(b(27, 116, 0), Command.Style.SelectCharacterCodePage(Command.CharacterCodePage.PC437).value)
        assertBytes(b(27, 116, 50), Command.Style.SelectCharacterCodePage(Command.CharacterCodePage.WPC1256).value)
    }

    @Test
    fun cutsUseGSV() {
        assertBytes(b(29, 86, 0), Command.Page.FullCut.value)
        assertBytes(b(29, 86, 1), Command.Page.PartialCut.value)
        assertBytes(b(29, 86, 66, 30), Command.Page.FeedAndCut(partial = true, feed = 30).value)
        assertBytes(b(27, 105), Command.Page.LegacyCut(partial = false).value)
    }

    @Test
    fun cashDrawer() {
        assertBytes(b(27, 112, 0, 50, 250), Command.Peripheral.CashDrawer().value)
        assertBytes(b(27, 112, 1, 25, 250), Command.Peripheral.CashDrawer(pin = 1, onTime = 25, offTime = 250).value)
    }

    @Test
    fun characterSizeClampsAndPacks() {
        assertBytes(b(29, 33, 0x12), Command.Style.CharacterSize(width = 2, height = 3).value)
        assertBytes(b(29, 33, 0x07), Command.Style.CharacterSize(width = 0, height = 99).value)
    }

    @Test
    fun marginsAreLittleEndian() {
        assertBytes(b(29, 76, 44, 1), Command.Layout.LeftMargin(300).value)
        assertBytes(b(29, 87, 64, 2), Command.Layout.PrintAreaWidth(576).value)
    }

    @Test
    fun rawPassesThrough() {
        assertBytes(b(1, 2, 3), Command.Raw(1, 2, 3).value)
        assertBytes(b(1, 2, 3), Command.Raw(b(1, 2, 3)).value)
    }

    @Test
    fun styleCommands() {
        assertBytes(b(27, 64), Command.Style.Initialize.value)
        assertBytes(b(27, 33, 8), Command.Style.SelectPrintMode(Command.PrintMode.EMPHASIS).value)
        assertBytes(b(27, 45, 1), Command.Style.UnderlineMode(Command.UnderlineMode.ENABLE_1DOT).value)
        assertBytes(b(27, 69, 1), Command.Style.Emphasis(true).value)
        assertBytes(b(27, 77, 1), Command.Style.SelectCharacterFont(Command.CharacterFont.B).value)
        assertBytes(b(27, 84), Command.Style.SelectCharacterFont(Command.CharacterFont.C).value)
        assertBytes(b(27, 82, 2), Command.Style.SelectInternationalCharacterSet(Command.InternationalCharacterSet.GERMANY).value)
        assertBytes(b(29, 66, 1), Command.Style.ReverseMode(true).value)
        assertBytes(b(27, 123, 1), Command.Style.UpsideDownMode(true).value)
    }

    @Test
    fun layoutCursorInfoCommands() {
        assertBytes(b(27, 97, 1), Command.Layout.Justification(Command.Alignment.CENTER).value)
        assertBytes(b(27, 50), Command.Layout.LineSpace(Command.LineSpace.L1_6).value)
        assertBytes(b(27, 51, 40), Command.Layout.LineSpace(Command.LineSpace.Raw(40)).value)
        assertBytes(b(27, 32, 2), Command.Layout.RightSpacing(2).value)
        assertBytes(b(10), Command.Cursor.LineFeed.value)
        assertBytes(b(27, 36, 1, 2), Command.Cursor.AbsolutePrintPosition(1, 2).value)
        assertBytes(b(27, 74, 0), Command.Page.Print.value)
        assertBytes(b(27, 74, 24), Command.Page.PrintAndFeedPaper(24).value)
        assertBytes(b(29, 73, 1), Command.Info.Printer(Command.PrinterID.MODEL_ID).value)
        assertBytes(b(16, 4, 4), Command.Info.RealTimeStatus(9).value)
    }
}

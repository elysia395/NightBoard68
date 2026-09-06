package com.nightboard.keyboard68

/**
 * 一个键帽。
 *
 * @param label      主标签（Fn 层之外显示的字符）
 * @param code       USB HID 键码；修饰键为 0，Fn 层切换键为 -1
 * @param width      相对键宽（1 = 标准键），所有行加起来都是 16u
 * @param isModifier 是否为修饰键（Win/Ctrl/Alt/Shift）
 * @param modBit     修饰键在报告里的位掩码
 * @param fnLabel    Fn 层副标签，null 表示该键没有 Fn 层
 * @param fnCode     Fn 层按下的键码
 */
class Key(
    val label: String,
    val code: Int,
    val width: Float = 1f,
    val isModifier: Boolean = false,
    val modBit: Int = 0,
    val fnLabel: String? = null,
    val fnCode: Int = code,
) {
    /** 修饰键文本标签（Win 用 ⌘ 风格会误导，直接写 Win） */
    val displayLabel: String get() = label
}

/**
 * 68 键 65% 配列（5 行，每行总宽 16u，共 68 键）：
 *  R1: Esc 1-0 - = Backspace Del                    (15)
 *  R2: Tab Q-P [ ] \ PgUp                           (15)
 *  R3: Caps A-L ; ' Enter PgDn                      (14)
 *  R4: LShift Z-/ RShift ↑ End                      (14)
 *  R5: LCtrl Win LAlt Space RAlt Fn RCtrl ← ↓ →     (10)
 *
 * Fn 层：数字排变 F1-F12，Esc→`，Del→PrtSc，PgUp/PgDn→Home/End，End→Home
 */
object Layout68 {

    private fun letter(label: String, code: Int) = Key(label, code)

    val rows: List<List<Key>> = listOf(
        listOf(
            Key("Esc", Hid.ESC, fnLabel = "~", fnCode = Hid.GRAVE),
            Key("1", Hid.NUM_1, fnLabel = "F1", fnCode = Hid.F1),
            Key("2", Hid.NUM_2, fnLabel = "F2", fnCode = Hid.F2),
            Key("3", Hid.NUM_3, fnLabel = "F3", fnCode = Hid.F3),
            Key("4", Hid.NUM_4, fnLabel = "F4", fnCode = Hid.F4),
            Key("5", Hid.NUM_5, fnLabel = "F5", fnCode = Hid.F5),
            Key("6", Hid.NUM_6, fnLabel = "F6", fnCode = Hid.F6),
            Key("7", Hid.NUM_7, fnLabel = "F7", fnCode = Hid.F7),
            Key("8", Hid.NUM_8, fnLabel = "F8", fnCode = Hid.F8),
            Key("9", Hid.NUM_9, fnLabel = "F9", fnCode = Hid.F9),
            Key("0", Hid.NUM_0, fnLabel = "F10", fnCode = Hid.F10),
            Key("-", Hid.MINUS, fnLabel = "F11", fnCode = Hid.F11),
            Key("=", Hid.EQUAL, fnLabel = "F12", fnCode = Hid.F12),
            Key("⌫", Hid.BKSP, 2f),
            Key("Del", Hid.DELETE, fnLabel = "PrtSc", fnCode = Hid.PRTSC),
        ),
        listOf(
            Key("Tab", Hid.TAB, 1.5f),
            letter("Q", Hid.Q), letter("W", Hid.W), letter("E", Hid.E), letter("R", Hid.R),
            letter("T", Hid.T), letter("Y", Hid.Y), letter("U", Hid.U), letter("I", Hid.I),
            letter("O", Hid.O), letter("P", Hid.P),
            Key("[", Hid.LBRACKET), Key("]", Hid.RBRACKET),
            Key("\\", Hid.BACKSLASH, 1.5f),
            Key("PgUp", Hid.PGUP, fnLabel = "Home", fnCode = Hid.HOME),
        ),
        listOf(
            Key("Caps", Hid.CAPSLOCK, 1.75f),
            letter("A", Hid.A), letter("S", Hid.S), letter("D", Hid.D), letter("F", Hid.F),
            letter("G", Hid.G), letter("H", Hid.H), letter("J", Hid.J), letter("K", Hid.K),
            letter("L", Hid.L),
            Key(";", Hid.SEMICOLON), Key("'", Hid.APOSTROPHE),
            Key("Enter", Hid.ENTER, 2.25f),
            Key("PgDn", Hid.PGDN, fnLabel = "End", fnCode = Hid.END),
        ),
        listOf(
            Key("Shift", 0, 2.25f, isModifier = true, modBit = Mods.LSHIFT),
            letter("Z", Hid.Z), letter("X", Hid.X), letter("C", Hid.C), letter("V", Hid.V),
            letter("B", Hid.B), letter("N", Hid.N), letter("M", Hid.M),
            Key(",", Hid.COMMA), Key(".", Hid.PERIOD), Key("/", Hid.SLASH),
            Key("Shift", 0, 1.75f, isModifier = true, modBit = Mods.RSHIFT),
            Key("↑", Hid.UP),
            Key("End", Hid.END, fnLabel = "Home", fnCode = Hid.HOME),
        ),
        listOf(
            Key("Ctrl", 0, 1.25f, isModifier = true, modBit = Mods.LCTRL),
            Key("Win", 0, 1.25f, isModifier = true, modBit = Mods.LGUI),
            Key("Alt", 0, 1.25f, isModifier = true, modBit = Mods.LALT),
            Key("", Hid.SPACE, 6.25f),
            Key("Alt", 0, 1f, isModifier = true, modBit = Mods.RALT),
            Key("Fn", -1, 1f),
            Key("Ctrl", 0, 1f, isModifier = true, modBit = Mods.RCTRL),
            Key("←", Hid.LEFT),
            Key("↓", Hid.DOWN),
            Key("→", Hid.RIGHT),
        ),
    )
}

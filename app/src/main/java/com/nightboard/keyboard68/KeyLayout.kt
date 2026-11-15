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
 * @param autoMods   发送该键时自动携带的修饰键（如 ( = Shift+9），0 = 无
 */
class Key(
    val label: String,
    val code: Int,
    val width: Float = 1f,
    val isModifier: Boolean = false,
    val modBit: Int = 0,
    val fnLabel: String? = null,
    val fnCode: Int = code,
    val autoMods: Int = 0,
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
 * Fn 层：数字排变 F1-F12，Esc→`，Del→PrtSc，PgUp→Ins，End→Home（PgDn 无 Fn 层）
 */
object Layout68 {

    private fun letter(label: String, code: Int) = Key(label, code)

    val rows: List<List<Key>> = listOf(
        listOf(
            Key("Esc", Hid.ESC, fnLabel = "`", fnCode = Hid.GRAVE),
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
            Key("PgUp", Hid.PGUP, fnLabel = "Ins", fnCode = Hid.INSERT),
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

/**
 * 82 键 75% 配列（6 行，每行总宽 16u）：独立 F 键排 + 右侧编辑键列 + 方向键。
 *  R1: Esc F1-F12 Del                               (14)
 *  R2: ` 1-0 - = Backspace Home                     (15)
 *  R3: Tab Q-P [ ] \ PgUp                           (15)
 *  R4: Caps A-L ; ' Enter PgDn                      (14)
 *  R5: LShift Z-/ RShift ↑ End                      (14)
 *  R6: LCtrl Win LAlt Space RAlt Fn RCtrl ← ↓ →     (10)
 *
 * Fn 层：Del→PrtSc，方向键→Home/End/PgUp/PgDn（手不离方向区翻页）
 */
object Layout75 {

    private fun letter(label: String, code: Int) = Key(label, code)

    /** F 排 14 键均分 16u（比数字键略宽，渲染器按行归一化） */
    private const val FW = 16f / 14f

    val rows: List<List<Key>> = listOf(
        buildList {
            add(Key("Esc", Hid.ESC, FW))
            for (i in 1..12) add(Key("F$i", Hid.F1 + i - 1, FW))
            add(Key("Del", Hid.DELETE, FW, fnLabel = "PrtSc", fnCode = Hid.PRTSC))
        },
        listOf(
            Key("`", Hid.GRAVE),
            Key("1", Hid.NUM_1), Key("2", Hid.NUM_2), Key("3", Hid.NUM_3),
            Key("4", Hid.NUM_4), Key("5", Hid.NUM_5), Key("6", Hid.NUM_6),
            Key("7", Hid.NUM_7), Key("8", Hid.NUM_8), Key("9", Hid.NUM_9),
            Key("0", Hid.NUM_0), Key("-", Hid.MINUS), Key("=", Hid.EQUAL),
            Key("⌫", Hid.BKSP, 2f),
            Key("Home", Hid.HOME),
        ),
        listOf(
            Key("Tab", Hid.TAB, 1.5f),
            letter("Q", Hid.Q), letter("W", Hid.W), letter("E", Hid.E), letter("R", Hid.R),
            letter("T", Hid.T), letter("Y", Hid.Y), letter("U", Hid.U), letter("I", Hid.I),
            letter("O", Hid.O), letter("P", Hid.P),
            Key("[", Hid.LBRACKET), Key("]", Hid.RBRACKET),
            Key("\\", Hid.BACKSLASH, 1.5f),
            Key("PgUp", Hid.PGUP),
        ),
        listOf(
            Key("Caps", Hid.CAPSLOCK, 1.75f),
            letter("A", Hid.A), letter("S", Hid.S), letter("D", Hid.D), letter("F", Hid.F),
            letter("G", Hid.G), letter("H", Hid.H), letter("J", Hid.J), letter("K", Hid.K),
            letter("L", Hid.L),
            Key(";", Hid.SEMICOLON), Key("'", Hid.APOSTROPHE),
            Key("Enter", Hid.ENTER, 2.25f),
            Key("PgDn", Hid.PGDN),
        ),
        listOf(
            Key("Shift", 0, 2.25f, isModifier = true, modBit = Mods.LSHIFT),
            letter("Z", Hid.Z), letter("X", Hid.X), letter("C", Hid.C), letter("V", Hid.V),
            letter("B", Hid.B), letter("N", Hid.N), letter("M", Hid.M),
            Key(",", Hid.COMMA), Key(".", Hid.PERIOD), Key("/", Hid.SLASH),
            Key("Shift", 0, 1.75f, isModifier = true, modBit = Mods.RSHIFT),
            Key("↑", Hid.UP, fnLabel = "PgUp", fnCode = Hid.PGUP),
            Key("End", Hid.END),
        ),
        listOf(
            Key("Ctrl", 0, 1.25f, isModifier = true, modBit = Mods.LCTRL),
            Key("Win", 0, 1.25f, isModifier = true, modBit = Mods.LGUI),
            Key("Alt", 0, 1.25f, isModifier = true, modBit = Mods.LALT),
            Key("", Hid.SPACE, 6.25f),
            Key("Alt", 0, 1f, isModifier = true, modBit = Mods.RALT),
            Key("Fn", -1, 1f),
            Key("Ctrl", 0, 1f, isModifier = true, modBit = Mods.RCTRL),
            Key("←", Hid.LEFT, fnLabel = "Home", fnCode = Hid.HOME),
            Key("↓", Hid.DOWN, fnLabel = "PgDn", fnCode = Hid.PGDN),
            Key("→", Hid.RIGHT, fnLabel = "End", fnCode = Hid.END),
        ),
    )
}

/**
 * 61 键 60% 配列（5 行，每行总宽 15u；渲染器按行拉伸铺满，键比 65% 更大）。
 *  R1: ` 1-0 - = Backspace                          (14)
 *  R2: Tab Q-P [ ] \                                (14)
 *  R3: Caps A-L ; ' Enter                           (13)
 *  R4: LShift Z-/ RShift                            (12)
 *  R5: LCtrl Win LAlt Space RAlt Fn Menu RCtrl      (8)
 *
 * Fn 层（60% 没有独立 F 排/方向键/Esc，全靠 Fn）：
 *  `→Esc，数字排→F1-F12，⌫→Del；I/J/K/L→↑/←/↓/→，U/O→Home/End，H/N→PgUp/PgDn，P→PrtSc
 */
object Layout60 {

    private fun letter(label: String, code: Int, fnLabel: String? = null, fnCode: Int = code) =
        Key(label, code, fnLabel = fnLabel, fnCode = fnCode)

    val rows: List<List<Key>> = listOf(
        listOf(
            Key("`", Hid.GRAVE, fnLabel = "Esc", fnCode = Hid.ESC),
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
            Key("⌫", Hid.BKSP, 2f, fnLabel = "Del", fnCode = Hid.DELETE),
        ),
        listOf(
            Key("Tab", Hid.TAB, 1.5f),
            letter("Q", Hid.Q), letter("W", Hid.W), letter("E", Hid.E), letter("R", Hid.R),
            letter("T", Hid.T), letter("Y", Hid.Y),
            letter("U", Hid.U, "Home", Hid.HOME),
            letter("I", Hid.I, "↑", Hid.UP),
            letter("O", Hid.O, "End", Hid.END),
            letter("P", Hid.P, "PrtSc", Hid.PRTSC),
            Key("[", Hid.LBRACKET), Key("]", Hid.RBRACKET),
            Key("\\", Hid.BACKSLASH, 1.5f),
        ),
        listOf(
            Key("Caps", Hid.CAPSLOCK, 1.75f),
            letter("A", Hid.A), letter("S", Hid.S), letter("D", Hid.D), letter("F", Hid.F),
            letter("G", Hid.G),
            letter("H", Hid.H, "PgUp", Hid.PGUP),
            letter("J", Hid.J, "←", Hid.LEFT),
            letter("K", Hid.K, "↓", Hid.DOWN),
            letter("L", Hid.L, "→", Hid.RIGHT),
            Key(";", Hid.SEMICOLON), Key("'", Hid.APOSTROPHE),
            Key("Enter", Hid.ENTER, 2.25f),
        ),
        listOf(
            Key("Shift", 0, 2.25f, isModifier = true, modBit = Mods.LSHIFT),
            letter("Z", Hid.Z), letter("X", Hid.X), letter("C", Hid.C), letter("V", Hid.V),
            letter("B", Hid.B),
            letter("N", Hid.N, "PgDn", Hid.PGDN),
            letter("M", Hid.M),
            Key(",", Hid.COMMA), Key(".", Hid.PERIOD), Key("/", Hid.SLASH),
            Key("Shift", 0, 2.75f, isModifier = true, modBit = Mods.RSHIFT),
        ),
        listOf(
            Key("Ctrl", 0, 1.25f, isModifier = true, modBit = Mods.LCTRL),
            Key("Win", 0, 1.25f, isModifier = true, modBit = Mods.LGUI),
            Key("Alt", 0, 1.25f, isModifier = true, modBit = Mods.LALT),
            Key("", Hid.SPACE, 6.25f),
            Key("Alt", 0, 1.25f, isModifier = true, modBit = Mods.RALT),
            Key("Fn", -1, 1.25f),
            Key("Menu", Hid.MENU, 1.25f),
            Key("Ctrl", 0, 1.25f, isModifier = true, modBit = Mods.RCTRL),
        ),
    )
}

/** 一个可选配列：id（持久化用）/ 显示名 / 键帽行 */
class KbLayout(val id: String, val label: String, val rows: List<List<Key>>)

/**
 * 配列清单。键数刻意限制在 61~82（issue #20）：
 * 更多键（87 TKL / 104 全尺寸）在手机上单键过窄、极易误触；更少（40%）功能残缺。
 * 65% 为默认；75% 给需要独立 F 排的用户；60% 给追求大键位的用户。
 */
object KbLayouts {
    const val DEFAULT_ID = "68"

    val all: List<KbLayout> = listOf(
        KbLayout("68", "65% · 68键", Layout68.rows),
        KbLayout("75", "75% · 82键", Layout75.rows),
        KbLayout("60", "60% · 61键", Layout60.rows),
    )

    fun byId(id: String?): KbLayout = all.firstOrNull { it.id == id } ?: all.first()
}

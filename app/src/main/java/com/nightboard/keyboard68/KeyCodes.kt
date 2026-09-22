package com.nightboard.keyboard68

/**
 * USB HID Usage 键码（Bluetooth HID 键盘报告用的就是这套码表）
 * 参考：USB HID Usage Tables 1.12, Keyboard/Keypad Page (0x07)
 */
object Hid {
    const val NONE = 0x00

    // 字母 A-Z
    const val A = 0x04; const val B = 0x05; const val C = 0x06; const val D = 0x07
    const val E = 0x08; const val F = 0x09; const val G = 0x0A; const val H = 0x0B
    const val I = 0x0C; const val J = 0x0D; const val K = 0x0E; const val L = 0x0F
    const val M = 0x10; const val N = 0x11; const val O = 0x12; const val P = 0x13
    const val Q = 0x14; const val R = 0x15; const val S = 0x16; const val T = 0x17
    const val U = 0x18; const val V = 0x19; const val W = 0x1A; const val X = 0x1B
    const val Y = 0x1C; const val Z = 0x1D

    // 数字 1-0
    const val NUM_1 = 0x1E; const val NUM_2 = 0x1F; const val NUM_3 = 0x20
    const val NUM_4 = 0x21; const val NUM_5 = 0x22; const val NUM_6 = 0x23
    const val NUM_7 = 0x24; const val NUM_8 = 0x25; const val NUM_9 = 0x26
    const val NUM_0 = 0x27

    const val ENTER = 0x28; const val ESC = 0x29; const val BKSP = 0x2A
    const val TAB = 0x2B; const val SPACE = 0x2C

    const val MINUS = 0x2D; const val EQUAL = 0x2E
    const val LBRACKET = 0x2F; const val RBRACKET = 0x30; const val BACKSLASH = 0x31
    const val SEMICOLON = 0x33; const val APOSTROPHE = 0x34; const val GRAVE = 0x35
    const val COMMA = 0x36; const val PERIOD = 0x37; const val SLASH = 0x38
    const val CAPSLOCK = 0x39

    const val F1 = 0x3A; const val F2 = 0x3B; const val F3 = 0x3C; const val F4 = 0x3D
    const val F5 = 0x3E; const val F6 = 0x3F; const val F7 = 0x40; const val F8 = 0x41
    const val F9 = 0x42; const val F10 = 0x43; const val F11 = 0x44; const val F12 = 0x45

    const val PRTSC = 0x46; const val SCROLLLOCK = 0x47; const val PAUSE = 0x48
    const val INSERT = 0x49; const val HOME = 0x4A; const val PGUP = 0x4B
    const val DELETE = 0x4C; const val END = 0x4D; const val PGDN = 0x4E
    const val RIGHT = 0x4F; const val LEFT = 0x50; const val DOWN = 0x51; const val UP = 0x52

    // 数字小键盘（Keypad 页）：电脑端识别为小键盘键位
    const val NUM_LOCK = 0x53   // NumLock
    const val DIVIDE = 0x54     // 小键盘 /
    const val MULTIPLY = 0x55   // 小键盘 *
    const val SUBTRACT = 0x56   // 小键盘 -
    const val ADD = 0x57        // 小键盘 +
    const val KEYPAD_ENTER = 0x58   // 小键盘 Enter
    const val NUMPAD_1 = 0x59; const val NUMPAD_2 = 0x5A; const val NUMPAD_3 = 0x5B
    const val NUMPAD_4 = 0x5C; const val NUMPAD_5 = 0x5D; const val NUMPAD_6 = 0x5E
    const val NUMPAD_7 = 0x5F; const val NUMPAD_8 = 0x60; const val NUMPAD_9 = 0x61
    const val NUMPAD_0 = 0x62; const val NUMPAD_DOT = 0x63
}

/**
 * 报告第 1 字节的修饰键位掩码（bit0-7）
 */
object Mods {
    const val LCTRL = 0x01
    const val LSHIFT = 0x02
    const val LALT = 0x04
    const val LGUI = 0x08   // Win / Cmd
    const val RCTRL = 0x10
    const val RSHIFT = 0x20
    const val RALT = 0x40   // AltGr
    const val RGUI = 0x80
}

/**
 * 字符 → (HID 键码, 修饰键位) 映射（美式键盘布局）。
 * 软键盘实时输入用：逐字发送到电脑。无法通过 HID 表示的字符（中文等）返回 null。
 */
fun charToHid(c: Char): Pair<Int, Int>? {
    val shift = Mods.LSHIFT
    return when (c) {
        in 'a'..'z' -> (Hid.A + (c - 'a')) to 0
        in 'A'..'Z' -> (Hid.A + (c - 'A')) to shift
        '0' -> Hid.NUM_0 to 0
        in '1'..'9' -> (Hid.NUM_1 + (c - '1')) to 0
        ' ' -> Hid.SPACE to 0
        '\n' -> Hid.ENTER to 0
        '\t' -> Hid.TAB to 0
        '-' -> Hid.MINUS to 0
        '_' -> Hid.MINUS to shift
        '=' -> Hid.EQUAL to 0
        '+' -> Hid.EQUAL to shift
        '[' -> Hid.LBRACKET to 0
        '{' -> Hid.LBRACKET to shift
        ']' -> Hid.RBRACKET to 0
        '}' -> Hid.RBRACKET to shift
        '\\' -> Hid.BACKSLASH to 0
        '|' -> Hid.BACKSLASH to shift
        ';' -> Hid.SEMICOLON to 0
        ':' -> Hid.SEMICOLON to shift
        '\'' -> Hid.APOSTROPHE to 0
        '"' -> Hid.APOSTROPHE to shift
        '`' -> Hid.GRAVE to 0
        '~' -> Hid.GRAVE to shift
        ',' -> Hid.COMMA to 0
        '<' -> Hid.COMMA to shift
        '.' -> Hid.PERIOD to 0
        '>' -> Hid.PERIOD to shift
        '/' -> Hid.SLASH to 0
        '?' -> Hid.SLASH to shift
        '!' -> Hid.NUM_1 to shift
        '@' -> Hid.NUM_2 to shift
        '#' -> Hid.NUM_3 to shift
        '$' -> Hid.NUM_4 to shift
        '%' -> Hid.NUM_5 to shift
        '^' -> Hid.NUM_6 to shift
        '&' -> Hid.NUM_7 to shift
        '*' -> Hid.NUM_8 to shift
        '(' -> Hid.NUM_9 to shift
        ')' -> Hid.NUM_0 to shift
        else -> null
    }
}

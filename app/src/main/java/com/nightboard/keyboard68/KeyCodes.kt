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

package com.nightboard.keyboard68

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单手模式的自定义快捷键存取（SharedPreferences 内 JSON 数组）。
 *
 * 一个快捷键 = 修饰键位掩码 + USB HID 键码，例如 Ctrl+Shift+T。
 * 槽位固定 6 个，长按快捷键弹出编辑器修改。
 */
class ShortcutStore(context: Context) {

    class Shortcut(val mods: Int, val code: Int)

    private val prefs = context.applicationContext
        .getSharedPreferences("nightboard", Context.MODE_PRIVATE)

    fun load(): List<Shortcut?> {
        val raw = prefs.getString("custom_shortcuts", null) ?: return defaultList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Shortcut?>(SLOTS)
            for (i in 0 until SLOTS) {
                if (i < arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val m = o.optInt("m", 0)
                    val c = o.optInt("c", 0)
                    out.add(if (c > 0) Shortcut(m, c) else null)
                } else {
                    out.add(null)
                }
            }
            out
        } catch (_: Exception) {
            defaultList()
        }
    }

    fun save(list: List<Shortcut?>) {
        val arr = JSONArray()
        for (i in 0 until SLOTS) {
            val s = list.getOrNull(i)
            val o = JSONObject()
            if (s != null) {
                o.put("m", s.mods)
                o.put("c", s.code)
            } else {
                o.put("m", 0)
                o.put("c", 0)
            }
            arr.put(o)
        }
        prefs.edit().putString("custom_shortcuts", arr.toString()).apply()
    }

    private fun defaultList(): List<Shortcut?> = listOf(
        Shortcut(Mods.LCTRL, Hid.C),
        Shortcut(Mods.LCTRL, Hid.V),
        Shortcut(Mods.LCTRL, Hid.Z),
        Shortcut(Mods.LCTRL, Hid.S),
        Shortcut(Mods.LALT, Hid.TAB),
        Shortcut(Mods.LGUI, Hid.D),
    )

    companion object {
        const val SLOTS = 6

        private val MOD_ORDER = listOf(
            Mods.LCTRL to "Ctrl",
            Mods.LALT to "Alt",
            Mods.LSHIFT to "Shift",
            Mods.LGUI to "Win",
        )

        /** 编辑器里可选的按键：标签 → HID 键码 */
        val CHOOSABLE_KEYS: List<Pair<String, Int>> = buildList {
            for (c in 'A'..'Z') add(c.toString() to (Hid.A + (c - 'A')))
            for (i in 1..9) add(i.toString() to (Hid.NUM_1 + i - 1))
            add("0" to Hid.NUM_0)
            for (i in 1..12) add("F$i" to (Hid.F1 + i - 1))
            add("回车" to Hid.ENTER)
            add("Tab" to Hid.TAB)
            add("空格" to Hid.SPACE)
            add("退格" to Hid.BKSP)
            add("Del" to Hid.DELETE)
            add("Ins" to Hid.INSERT)
            add("Home" to Hid.HOME)
            add("End" to Hid.END)
            add("PgUp" to Hid.PGUP)
            add("PgDn" to Hid.PGDN)
            add("↑" to Hid.UP)
            add("↓" to Hid.DOWN)
            add("←" to Hid.LEFT)
            add("→" to Hid.RIGHT)
            add("-" to Hid.MINUS)
            add("=" to Hid.EQUAL)
            add("[" to Hid.LBRACKET)
            add("]" to Hid.RBRACKET)
            add("\\" to Hid.BACKSLASH)
            add(";" to Hid.SEMICOLON)
            add("'" to Hid.APOSTROPHE)
            add("`" to Hid.GRAVE)
            add("," to Hid.COMMA)
            add("." to Hid.PERIOD)
            add("/" to Hid.SLASH)
            add("Esc" to Hid.ESC)
            add("Caps" to Hid.CAPSLOCK)
            add("PrtSc" to Hid.PRTSC)
        }

        /** 生成显示标签，如 "Ctrl+Shift+T"、"Win+D" */
        fun labelFor(mods: Int, code: Int): String {
            val sb = StringBuilder()
            for ((bit, name) in MOD_ORDER) {
                if (mods and bit != 0) {
                    if (sb.isNotEmpty()) sb.append('+')
                    sb.append(name)
                }
            }
            val keyLabel = CHOOSABLE_KEYS.firstOrNull { it.second == code }?.first
                ?: code.toString()
            if (sb.isNotEmpty()) sb.append('+')
            sb.append(keyLabel)
            return sb.toString()
        }
    }
}

# NightBoard68

<p>
  <a href="README.md">简体中文</a> · English
</p>

Turn your Android phone sideways and it becomes a **68-key (65% layout)
Bluetooth keyboard + touchpad**. Your computer sees it as a real Bluetooth
HID device — **zero software to install on the PC**.

<table>
  <tr>
    <td width="42%"><img src="assets/cover.png" alt="NightBoard68 cover: turn your Android phone into a silent keyboard" /></td>
    <td width="58%"><img src="assets/intro.png" alt="NightBoard68 intro: keyboard + touchpad for dorm nights, libraries and living-room TVs" /></td>
  </tr>
</table>

> **Current version: v1.3.0** (based on upstream v1.2.1, version numbering aligned
> with this repository). What's new in v1.3.0:
>
> - **LAN mode** — connect over Wi-Fi for 2–10 ms latency (vs 15–40 ms over
>   Bluetooth). Ships with `NightBoardAgent`, a portable 13 KB Windows exe
>   (no install). Bluetooth and LAN are two independent modes, switchable
>   anywhere; Bluetooth behavior is unchanged from v1.2.1.
> - **Portrait mode** — a one-hand vertical layout: touchpad (with edge scroll
>   strip), customizable shortcut slots, programming symbols row, F-row,
>   number row and a phone-style QWERTY.
> - **Interaction** — double-tap Shift = caps-like lock, long-press touchpad =
>   right click, tunable scroll sensitivity; fixes tap-to-latch modifiers
>   dropping on finger lift (v1.2.1 bug).
>
> See the [Chinese README](README.md#v130-改动点相对上游-v121) for the full changelog.

## Why I built this

I like to study and code at night in my dorm, but a mechanical keyboard is
loud enough to wake my roommates. Headphones can't fix noise that comes from
your own hands.

Then it hit me: **typing on a touchscreen is completely silent**, my phone is
always within reach, and held sideways its size is close to a 60% mechanical
keyboard. If the phone could pose as a *real* Bluetooth keyboard, it would be
the perfect midnight keyboard — nothing to install on the computer, the
charging port stays free, and no network needed.

Existing open-source projects (e.g.
[android-bt-remote](https://github.com/jqssun/android-bt-remote),
[Atharok/BtRemote](https://gitlab.com/Atharok/BtRemote)) already turn a phone
into a Bluetooth keyboard, but their on-screen layouts are generic remotes
for casual input — **nobody built a proper 65% layout, and nobody optimized
the landscape experience for people who actually type code on it**.

So NightBoard68 was born:

- **68-key 65% layout** — number row, bottom-right arrows, Fn layer for the
  F-row and editing keys; key positions match mainstream 65% boards, so your
  muscle memory carries over
- **Touch-first interaction** — tap-to-latch modifiers (tap Ctrl, then C =
  Ctrl+C with a single finger), something a physical keyboard can't do
- **Zero noise** — at night, the only thing audible is your thinking
- **Zero install, zero dependencies** — nothing on the computer; the app
  itself is 2 MB with no third-party libraries

## Use cases

- 🌙 **Dorm at night** — roommates asleep, you still want to code (the scene
  it was born in)
- 📚 **Library / study room** — when there's no keyboard or you don't want
  to carry one
- 🛋️ **On the couch / in bed** — control a living-room PC or HTPC; keyboard
  and touchpad in one
- 🖥️ **Demo rescue** — your keyboard dies, the phone steps in
- 📺 **Android TV / projector** — painless text entry on the big screen

## Features

### Keyboard
- 68-key 65% layout; multi-touch (hold Ctrl with one finger, tap C with another)
- **Tap-to-latch modifiers**: tap Ctrl (orange outline) → tap C → auto-release;
  tap Ctrl again to cancel
- **Chorded modifiers**: with Ctrl latched, tap Shift to send Ctrl+Shift
  (switch input method); tap a latched Shift again to send a lone Shift
  (toggle Chinese/English in IMEs)
- **Fn layer**: number row → F1–F12, Esc → `` ` ``, Del → PrtSc,
  PgUp/PgDn ↔ Home/End
- Hold-to-auto-repeat; Caps Lock state fed back from the PC lights up the
  keycap in real time
- Hit-slop between keys: fast taps that land in the gaps never drop

### Touchpad
- One tap on the top bar to switch: the whole screen becomes a touchpad
- One-finger move / tap = left click / two-finger tap = right click /
  two-finger slide = scroll

### Connection & keep-alive
- Foreground service: connection survives leaving the keyboard screen or
  locking the phone; the notification gets you back in one tap
- Auto-reconnects to the last computer; manual reconnect from the main screen

### Settings
- Haptic feedback amplitude, 0–255 with live preview
- Per-app screen brightness (down to 5% for night use; restored on exit)
- Modifier mode: tap-to-latch ↔ hold (physical-keyboard style)
- Auto-reconnect toggle

## Layout

```
Esc  1  2  3  4  5  6  7  8  9  0  -  =  Backspace   Del
Tab  Q  W  E  R  T  Y  U  I  O  P  [  ]  \           PgUp
Caps A  S  D  F  G  H  J  K  L  ;  '  Enter          PgDn
Shift   Z  X  C  V  B  N  M  ,  .  /  Shift   ↑      End
Ctrl Win Alt        Space        Alt Fn Ctrl  ←  ↓  →
```

## Requirements

- Phone: Android 9.0+ (uses the system `BluetoothHidDevice` profile)
- Computer: anything that accepts Bluetooth keyboards/mice
  (Windows / macOS / Linux / Android TV)

## Usage

1. Grab the APK from [Releases](../../releases), install it, grant the
   "nearby devices" permission, and wait for "keyboard ready" on the main
   screen
2. Tap **"Make this phone discoverable"**, then on the computer:
   Settings → Bluetooth & devices → Add device → Bluetooth
3. Pick **your phone's Bluetooth name** — the keyboard is a service
   registered on the phone, so no device literally named "NightBoard68"
   will appear; confirm the pairing prompt on the phone
4. Tap "Start typing", focus any text field on the computer, and go

> **Tip**: if the computer has paired with this phone before (as a phone),
> remove that old pairing first, otherwise the keyboard service won't be
> picked up. Upgrading the APK later does **not** require re-pairing
> (unless a release note says the HID descriptor changed).

## Build it yourself

```bash
gradle :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

No third-party dependencies — just the Android SDK (compileSdk 34) and
JDK 17+. To change the key mapping, edit
[`app/src/main/java/com/nightboard/keyboard68/KeyLayout.kt`](app/src/main/java/com/nightboard/keyboard68/KeyLayout.kt).

## How it works (one paragraph)

`BluetoothHidDevice` (the Android 9+ HID Device profile) registers a
keyboard + mouse combo device (Report ID 1 = 8-byte keyboard report,
Report ID 2 = 4-byte mouse report), so the computer treats the phone as a
native Bluetooth HID device. The UI is a single custom View (`onDraw` for
keycaps + `onTouchEvent` for multi-pointer dispatch) with hit-slop fallback
so gaps between keys never drop a tap.

## Known limitations

- Chorded modifiers (e.g. Ctrl+Shift to switch IME) fire after a 300 ms
  delay — the trade-off that disambiguates "chord" from "modifier + letter"
- Some heavily customized ROMs (older MIUI/ColorOS) restrict the HID device
  profile; if it's stuck on "registering keyboard…", please file an issue
  with your phone model
- If the touchpad scroll direction feels inverted, yell in an issue — it's a
  one-character fix

## Acknowledgments

- [Arian04/android-hid-client](https://github.com/Arian04/android-hid-client),
  [jqssun/android-bt-remote](https://github.com/jqssun/android-bt-remote) and
  [Atharok/BtRemote](https://gitlab.com/Atharok/BtRemote) — proved the
  `BluetoothHidDevice` route works
- [USB HID Usage Tables](https://usb.org/document-library/hid-usage-tables-and-digitizers-table-reference-guides) /
  [Understanding HID report descriptors](http://who-t.blogspot.com/2018/12/understanding-hid-report-descriptors.html)

## License

[MIT](LICENSE)

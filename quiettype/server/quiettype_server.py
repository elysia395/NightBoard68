#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QuietType 主机端 (Windows)
==========================
把 iPhone / 手机浏览器变成 Windows 的"远程键盘 + 触控板"。

原理:
  - 手机(网页或 iOS App)把文字 / 按键 / 鼠标增量通过 HTTP POST 发到本机;
  - 本程序用 ctypes 直接调 user32.SendInput 把内容注入系统(软件模拟 HID),
    效果等同于你亲手敲键盘 / 动鼠标。
  - 纯 Python 标准库,零第三方依赖。仅支持 Windows。

用法:
  python quiettype_server.py            # 默认端口 8567
  python quiettype_server.py --port 9000
  python quiettype_server.py --dryrun   # 不真正注入,只打印(测试用)

协议见 PROTOCOL.md
"""

import ctypes
import json
import os
import socket
import sys
import time
import uuid
from ctypes import wintypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
DEFAULT_PORT = 8567

# 源码运行 / PyInstaller 打包运行 的路径自适应
if getattr(sys, "frozen", False):
    BASE_DIR = os.path.dirname(sys.executable)   # exe 所在目录(日志写这里)
    WEB_DIR = os.path.join(sys._MEIPASS, "web")  # 打包内嵌的页面资源
else:
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))
    WEB_DIR = os.path.join(BASE_DIR, "web")

# 非中文 Windows 控制台打印中文不报错
for _s in (sys.stdout, sys.stderr):
    try:
        if _s and hasattr(_s, "reconfigure"):
            _s.reconfigure(errors="replace")
    except Exception:
        pass

TOKEN = uuid.uuid4().hex[:6]      # 每次启动生成,手机端通过 /api/info 自动获取
DRY_RUN = False                   # 测试模式: 不真正注入按键
VERBOSE = False
ENABLE_LOG = True                 # 是否把输入内容写入 access.log(--no-log 关闭)
VERSION = 1
APP_NAME = "QuietType"
ACCESS_LOG = os.path.join(BASE_DIR, "access.log")
RUN_PORT = DEFAULT_PORT           # main() 里按 --port 覆盖


# ---------------------------------------------------------------------------
# QR 支持: 内嵌 segno(纯 Python,MIT),不需要联网也不依赖第三方安装
# ---------------------------------------------------------------------------
_vendor = os.path.join(sys._MEIPASS, "vendor") if getattr(sys, "frozen", False) else os.path.join(BASE_DIR, "vendor")
sys.path.insert(0, _vendor)
QR_ERR = ""
try:
    import segno  # noqa
    QR_OK = True
except Exception as _e:
    segno = None
    QR_OK = False
    QR_ERR = str(_e) or type(_e).__name__


def lan_urls():
    """本机可用的手机访问网址列表。设了 QT_PUBLIC_URL 时优先用它(公网隧道版)。"""
    pub = os.environ.get("QT_PUBLIC_URL")
    if pub:
        return [pub]
    return ["http://%s:%d/" % (ip, RUN_PORT) for ip in lan_ips()]


def qr_svg_bytes():
    """主网址的 SVG 二维码(带缓存)。没有 segno 时返回 None。"""
    if not QR_OK:
        return None
    if not hasattr(qr_svg_bytes, "_cache"):
        urls = lan_urls()
        if not urls:
            return None
        _svg = segno.make(urls[0], error="m").svg_inline(
            scale=6, border=4, dark="#0f1115", light="#ffffff")
        # segno 的 inline 输出不带 xmlns,作为 <img> 独立图片加载时必须补上,
        # 否则浏览器拒渲(HTTP 200 但显示破图)
        if "<svg " in _svg and "xmlns" not in _svg:
            _svg = _svg.replace("<svg ",
                                '<svg xmlns="http://www.w3.org/2000/svg" ', 1)
        qr_svg_bytes._cache = _svg.encode("utf-8")
    return qr_svg_bytes._cache


def qr_ascii_lines(text):
    """把一段文本画成控制台 ASCII 二维码(用 # 与空格,兼容中文 codepage)。
    返回字符串行列表;每模块 2x2,四周 4 模块静区。"""
    if not QR_OK:
        return []
    qr = segno.make(text, error="m")
    m = qr.matrix
    n = len(m)
    b = 4
    blank = [0] * (n + 2 * b)
    full = [list(blank) for _ in range(b)]
    for row in m:
        full.append([0] * b + list(row) + [0] * b)
    full += [list(blank) for _ in range(b)]
    lines = []
    for row in full:
        s = "".join("##" if c else "  " for c in row)
        lines.append(s)
        lines.append(s)   # 每行画两次,保持方块比例,便于扫码
    return lines


def _access_log(line):
    """把收到的消息追加到 access.log,便于排查(--no-log 可关闭)。"""
    if not ENABLE_LOG:
        return
    try:
        with open(ACCESS_LOG, "a", encoding="utf-8") as f:
            f.write("%s  %s\n" % (time.strftime("%m-%d %H:%M:%S"), line))
    except OSError:
        pass

# ---------------------------------------------------------------------------
# ctypes 定义 (user32 SendInput)
# ---------------------------------------------------------------------------
user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

# 必须声明 64 位参数/返回类型,否则句柄被截断成 32 位导致访问违例
kernel32.GlobalAlloc.restype = ctypes.c_void_p
kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
kernel32.GlobalLock.restype = ctypes.c_void_p
kernel32.GlobalLock.argtypes = [ctypes.c_void_p]
kernel32.GlobalUnlock.argtypes = [ctypes.c_void_p]
kernel32.GlobalFree.argtypes = [ctypes.c_void_p]
user32.OpenClipboard.argtypes = [ctypes.c_void_p]
user32.OpenClipboard.restype = wintypes.BOOL
user32.EmptyClipboard.restype = wintypes.BOOL
user32.SetClipboardData.argtypes = [wintypes.UINT, ctypes.c_void_p]
user32.SetClipboardData.restype = ctypes.c_void_p
user32.CloseClipboard.restype = wintypes.BOOL

ULONG_PTR = ctypes.c_size_t  # 64 位兼容


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]


class _INPUT_UNION(ctypes.Union):
    _fields_ = [
        ("mi", MOUSEINPUT),
        ("ki", KEYBDINPUT),
        ("hi", HARDWAREINPUT),
    ]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("u", _INPUT_UNION)]


user32.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
user32.SendInput.restype = wintypes.UINT


INPUT_MOUSE = 0
INPUT_KEYBOARD = 1

KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
KEYEVENTF_SCANCODE = 0x0008

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800

# 特殊键 -> 虚拟键码(覆盖完整 PC 键盘)
VK = {
    "backspace": 0x08, "tab": 0x09, "enter": 0x0D, "shift": 0x10,
    "ctrl": 0x11, "alt": 0x12, "esc": 0x1B, "space": 0x20,
    "pgup": 0x21, "pgdn": 0x22, "end": 0x23, "home": 0x24,
    "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
    "delete": 0x2E, "win": 0x5B,
    # 锁定 / 编辑 / 扩展键
    "capital": 0x14, "capslock": 0x14, "pause": 0x13, "printscreen": 0x2C,
    "insert": 0x2D, "numlock": 0x90, "scroll": 0x91, "apps": 0x5D,
    # 数字小键盘
    "numpad0": 0x60, "numpad1": 0x61, "numpad2": 0x62, "numpad3": 0x63,
    "numpad4": 0x64, "numpad5": 0x65, "numpad6": 0x66, "numpad7": 0x67,
    "numpad8": 0x68, "numpad9": 0x69, "multiply": 0x6A, "add": 0x6B,
    "separator": 0x6C, "subtract": 0x6D, "decimal": 0x6E, "divide": 0x6F,
    # 符号键(物理位置键码;配合 Shift 即为上层符号)
    "oem_3": 0xC0,     # `
    "oem_minus": 0xBD, # -
    "oem_plus": 0xBB,  # =
    "oem_4": 0xDB,     # [
    "oem_6": 0xDD,     # ]
    "oem_5": 0xDC,     # \
    "oem_1": 0xBA,     # ;
    "oem_7": 0xDE,     # '
    "oem_comma": 0xBC, # ,
    "oem_period": 0xBE,# .
    "oem_2": 0xBF,     # /
    "oem_102": 0xE2,   # <
    # 多媒体
    "volume_mute": 0xAD, "volume_down": 0xAE, "volume_up": 0xAF,
    "media_next": 0xB0, "media_prev": 0xB1, "media_stop": 0xB2,
    "media_play": 0xB3,
}
for _i in range(1, 25):
    VK["f%d" % _i] = 0x6F + _i  # F1..F24
for _c in "abcdefghijklmnopqrstuvwxyz":
    VK[_c] = ord(_c.upper())
for _d in "0123456789":
    VK[_d] = ord(_d)             # 主键盘数字 0-9

# 修饰键 名称 -> VK
MOD_VK = {"ctrl": 0x11, "alt": 0x12, "shift": 0x10, "win": 0x5B}


def _log(*a):
    print(*a, flush=True)


def _inject_raw(inputs):
    """inputs: list of INPUT"""
    if DRY_RUN:
        return
    if not inputs:
        return
    n = len(inputs)
    arr = (INPUT * n)(*inputs)
    user32.SendInput(n, arr, ctypes.sizeof(INPUT))


def _key_input(vk, down, unicode_char=None):
    ki = KEYBDINPUT()
    if unicode_char is not None:
        ki.wVk = 0
        ki.wScan = ord(unicode_char)
        ki.dwFlags = KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if not down else 0)
    else:
        ki.wVk = vk
        ki.dwFlags = KEYEVENTF_KEYUP if not down else 0
    inp = INPUT()
    inp.type = INPUT_KEYBOARD
    inp.u.ki = ki
    return inp


def _tap_vk(vk):
    if DRY_RUN:
        _log("[dry] key vk=0x%02X" % vk)
        return
    _inject_raw([_key_input(vk, True), _key_input(vk, False)])


def _tap_unicode(ch):
    if DRY_RUN:
        _log("[dry] unicode %r" % ch)
        return
    _inject_raw([_key_input(0, True, ch), _key_input(0, False, ch)])


def press_key(name, mods=()):
    """按一个命名键,可带 ctrl/alt/shift/win 修饰。"""
    vk = VK.get(name)
    if vk is None:
        return False
    if DRY_RUN:
        _log("[dry] key %s mods=%s" % (name, ",".join(mods) or "-"))
        return True
    downs = [_key_input(MOD_VK[m], True) for m in mods if m in MOD_VK]
    ups = [_key_input(MOD_VK[m], False) for m in reversed(mods) if m in MOD_VK]
    _inject_raw(downs + [_key_input(vk, True), _key_input(vk, False)] + ups)
    time.sleep(0.008)
    return True


def press_backspaces(n):
    for _ in range(max(0, int(n))):
        _tap_vk(VK["backspace"])
        time.sleep(0.012)


def type_text(text):
    """把一段文字“打”出来。
    ASCII 用 KEYEVENTF_UNICODE 逐字符注入(和真实键盘输入一致);
    含非 ASCII(中文等)的连续段改用剪贴板粘贴,保证任意字符可靠。
    \n -> 回车键; \t -> Tab 键。
    """
    i = 0
    s = text.replace("\r", "")
    n = len(s)
    while i < n:
        ch = s[i]
        if ch == "\n":
            _tap_vk(VK["enter"])
            time.sleep(0.01)
            i += 1
            continue
        if ch == "\t":
            _tap_vk(VK["tab"])
            time.sleep(0.01)
            i += 1
            continue
        if ord(ch) < 0x80:
            _tap_unicode(ch)
            time.sleep(0.006)
            i += 1
            continue
        # 收集一段非 ASCII
        j = i
        while j < n and ord(s[j]) >= 0x80 and s[j] not in "\n\t":
            j += 1
        if j > i:
            _paste_text(s[i:j])
            time.sleep(0.02)
            i = j


def _clipboard_set(text):
    """把文本写入系统剪贴板(CF_UNICODETEXT)。成功返回 True。"""
    wide = (text + "\0").encode("utf-16-le")
    size = len(wide)
    GMEM_MOVEABLE = 0x0002
    GMEM_ZEROINIT = 0x0040
    h = kernel32.GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, size)
    if not h:
        return False
    ptr = kernel32.GlobalLock(h)
    if not ptr:
        kernel32.GlobalFree(h)
        return False
    ctypes.memmove(ptr, wide, size)
    kernel32.GlobalUnlock(h)
    opened = False
    for _ in range(20):
        if user32.OpenClipboard(None):
            opened = True
            break
        time.sleep(0.01)
    if not opened:
        kernel32.GlobalFree(h)
        return False
    try:
        user32.EmptyClipboard()
        user32.SetClipboardData(13, h)   # CF_UNICODETEXT;成功后系统接管句柄
        h = None
    finally:
        user32.CloseClipboard()
    if h:
        kernel32.GlobalFree(h)
    return True


def _paste_text(text):
    """用剪贴板粘贴文本(任意字符都可靠)。"""
    if DRY_RUN:
        _log("[dry] paste %r" % text)
        return
    if not _clipboard_set(text):
        return
    # ---- Ctrl+V ----
    _inject_raw([_key_input(0x11, True),
                 _key_input(0x56, True), _key_input(0x56, False),
                 _key_input(0x11, False)])


# ---------------------------------------------------------------------------
# 鼠标
# ---------------------------------------------------------------------------
def _mouse_input(flags, data=0, dx=0, dy=0):
    mi = MOUSEINPUT()
    mi.dx, mi.dy = dx, dy
    mi.mouseData = data
    mi.dwFlags = flags
    inp = INPUT()
    inp.type = INPUT_MOUSE
    inp.u.mi = mi
    return inp


def mouse_move(dx, dy):
    if DRY_RUN:
        _log("[dry] mouse move %d,%d" % (dx, dy))
        return
    if dx or dy:
        _inject_raw([_mouse_input(MOUSEEVENTF_MOVE, dx=dx, dy=dy)])


def mouse_button(button, down):
    flag = {
        ("left", True): MOUSEEVENTF_LEFTDOWN, ("left", False): MOUSEEVENTF_LEFTUP,
        ("right", True): MOUSEEVENTF_RIGHTDOWN, ("right", False): MOUSEEVENTF_RIGHTUP,
        ("middle", True): MOUSEEVENTF_MIDDLEDOWN, ("middle", False): MOUSEEVENTF_MIDDLEUP,
    }.get((button, down))
    if flag is None:
        return
    if DRY_RUN:
        _log("[dry] %s %s" % (button, "down" if down else "up"))
        return
    _inject_raw([_mouse_input(flag)])
    time.sleep(0.01)


def mouse_wheel(steps):
    """steps>0 向上滚动,steps<0 向下;一步 = 一格(WHEEL_DELTA)。"""
    if DRY_RUN:
        _log("[dry] wheel %d" % steps)
        return
    steps = max(-200, min(200, int(steps)))
    if steps:
        _inject_raw([_mouse_input(MOUSEEVENTF_WHEEL, data=120 * steps)])


# ---------------------------------------------------------------------------
# 消息分发 (协议见 PROTOCOL.md)
# ---------------------------------------------------------------------------
def dispatch(msg):
    """处理一条来自客户端的消息,返回 (ok, text)。"""
    t = msg.get("t")
    if t == "txt":            # {"t":"txt","s":"插入的文本"} 逐字注入
        type_text(msg.get("s") or "")
        return True, "typed"
    if t == "paste":          # {"t":"paste","s":"文本"} 整段剪贴板粘贴(网页/富文本编辑器专用)
        _paste_text(msg.get("s") or "")
        return True, "pasted"
    if t == "bs":             # {"t":"bs","n":3}
        press_backspaces(int(msg.get("n") or 0))
        return True, "backspace"
    if t == "key":            # {"t":"key","k":"enter","mods":["ctrl"]}
        ok = press_key(msg.get("k") or "", msg.get("mods") or [])
        return ok, "key" if ok else "unknown key"
    if t == "mv":             # {"t":"mv","x":12,"y":-5}
        dx = max(-800, min(800, int(msg.get("x") or 0)))
        dy = max(-800, min(800, int(msg.get("y") or 0)))
        mouse_move(dx, dy)
        return True, "move"
    if t == "mb":             # {"t":"mb","b":"left","d":true}
        mouse_button(msg.get("b") or "left", bool(msg.get("d")))
        return True, "button"
    if t == "wl":             # {"t":"wl","v":3} / {"t":"wl","v":-1}
        mouse_wheel(int(msg.get("v") or 0))
        return True, "wheel"
    if t == "ping":
        return True, "pong"
    return False, "unknown message type"


# ---------------------------------------------------------------------------
# HTTP 服务
# ---------------------------------------------------------------------------
CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".ico": "image/x-icon",
    ".svg": "image/svg+xml",
    ".png": "image/png",
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        if VERBOSE:
            _log("[http] " + fmt % args)

    # ---------- GET ----------
    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/api/info":
            urls = lan_urls()
            body = json.dumps({"name": APP_NAME, "token": TOKEN,
                               "version": VERSION,
                               "url": urls[0] if urls else ""}).encode("utf-8")
            return self._respond(200, "application/json; charset=utf-8", body)
        if path == "/qr.svg":
            data = qr_svg_bytes()
            if data is None:
                return self._respond(404, "text/plain",
                                     ("QR unavailable: " + QR_ERR).encode("utf-8"))
            return self._respond(200, "image/svg+xml", data)
        if path in ("/", "/index.html"):
            path = "/index.html"
        name = os.path.normpath(path.lstrip("/"))
        full = os.path.normpath(os.path.join(WEB_DIR, name))
        if not full.startswith(WEB_DIR):          # 防目录穿越
            return self._respond(403, "text/plain", b"forbidden")
        if not os.path.isfile(full):
            return self._respond(404, "text/plain", b"not found")
        ext = os.path.splitext(full)[1].lower()
        with open(full, "rb") as f:
            return self._respond(200, CONTENT_TYPES.get(ext, "application/octet-stream"),
                                 f.read())

    # ---------- POST ----------
    def do_POST(self):
        if self.path.split("?", 1)[0] != "/api/input":
            return self._respond(404, "application/json", b'{"ok":false}')
        try:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            msg = json.loads(raw.decode("utf-8")) if raw else {}
        except Exception as e:
            return self._respond(400, "application/json",
                                 json.dumps({"ok": False, "error": "bad json: %s" % e}).encode())
        if not isinstance(msg, dict) or msg.get("token") != TOKEN:
            return self._respond(403, "application/json",
                                 json.dumps({"ok": False, "error": "bad token"}).encode())
        try:
            ok, info = dispatch(msg)
        except Exception as e:
            return self._respond(500, "application/json",
                                 json.dumps({"ok": False, "error": "%s" % e}).encode())
        t = msg.get("t")
        s = msg.get("s")
        if t == "txt":
            _access_log("txt s=%r -> %s" % (s, info))
        else:
            _access_log("%s -> %s" % (json.dumps({k: v for k, v in msg.items() if k != "token"}, ensure_ascii=False), info))
        return self._respond(200, "application/json",
                             json.dumps({"ok": ok, "info": info}).encode())

    def _respond(self, code, ctype, body):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False   # Windows 上关掉,避免两个实例静默同绑 8567

    def handle_error(self, request, client_address):
        # 客户端提前断开 keep-alive 是常见且无害的,不刷 traceback
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionResetError, BrokenPipeError)):
            return
        super().handle_error(request, client_address)


# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------
def lan_ips():
    """尽量找本机局域网 IPv4。"""
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    return ips or ["127.0.0.1"]


def main(argv=None):
    global DRY_RUN, VERBOSE, RUN_PORT, ENABLE_LOG
    port = DEFAULT_PORT
    args = list(sys.argv[1:] if argv is None else argv)
    while args:
        a = args.pop(0)
        if a == "--port":
            port = int(args.pop(0))
        elif a == "--dryrun":
            DRY_RUN = True
        elif a == "--verbose":
            VERBOSE = True
        elif a == "--no-log":
            ENABLE_LOG = False          # 不把输入内容写进 access.log(隐私)
        elif a == "--help":
            print(__doc__)
            return

    # 端口被占用时自动顺延,不再直接启动失败
    server = None
    for p in range(port, port + 20):
        try:
            server = Server(("0.0.0.0", p), Handler)
            if p != port:
                print("[提示] 端口 %d 已被占用,已自动改用 %d" % (port, p))
            port = p
            break
        except OSError:
            continue
    if server is None:
        print("启动失败: 端口 %d~%d 全被占用。请关掉其它 QuietType 窗口后重试。" % (port, port + 19))
        print("(按任意键退出…)")
        try:
            import msvcrt
            msvcrt.getch()
        except Exception:
            pass
        return
    RUN_PORT = port

    urls = lan_urls()
    print("=" * 66)
    print("  %s host (token: %s)" % (APP_NAME, TOKEN))
    print("  dry-run 测试模式: %s" % ("ON(不真正注入)" if DRY_RUN else "OFF"))
    print("-" * 66)
    show_qr = QR_OK and not DRY_RUN and not os.environ.get("QT_NO_QR")
    if show_qr and urls:
        for i, u in enumerate(urls):
            if i == 0:
                print("  >>> 手机相机【扫下面的码】直达连接页,或手动打开:")
            print("  手机打开:  %s" % u)
            for ln in qr_ascii_lines(u):
                print(ln)
    else:
        for u in urls:
            print("  手机打开:  %s" % u)
    print("-" * 66)
    print("  端口: %d   按 Ctrl+C 退出" % port)
    if not DRY_RUN:
        print("  首次使用若连不上,请以管理员运行 firewall_allow.bat 放行端口,")
        print("  并确认手机和电脑在同一个 Wi-Fi/局域网。")
    print("=" * 66)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()

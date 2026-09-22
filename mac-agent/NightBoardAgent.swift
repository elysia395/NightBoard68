//
//  NightBoardAgent.swift
//  NightBoard68 macOS 局域网接收端（菜单栏常驻）
//
//  职责（与 Windows 版 NightBoardAgent.exe 逐条对应）：
//    1. UDP :6869 应答手机广播（自动发现）
//    2. TCP :6868 接收手机发来的按键/鼠标事件（换行分隔 JSON）
//    3. 通过 CoreGraphics CGEvent 注入成真实键鼠输入（等同于硬件键盘）
//
//  协议（手机→电脑）：{"t":"hello","n":"机型"} {"t":"kd","c":HID键码}
//    {"t":"ku","c":HID键码} {"t":"m","dx":..,"dy":..,"w":..,"b":..} {"t":"ra"} {"t":"p","i":id}
//  协议（电脑→手机）：{"t":"po","i":id} {"t":"led","c":键盘灯位掩码} {"t":"disc","n":计算机名,"p":6868}
//
//  权限：合成键鼠事件需要「辅助功能」权限（系统设置 → 隐私与安全性 → 辅助功能）。
//  未授权时注入会被系统静默丢弃，菜单栏图标显示 ⚠️，点击菜单提示可一键跳转授权页。
//

import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

// MARK: - 协议常量

let kDiscoveryPort: UInt16 = 6869
let kTransportPort: UInt16 = 6868
let kDiscoverReq = "NB68_DISCOVER_V1"

// 局域网注入的按键没有硬件自动重复（那是物理键盘固件干的），长按连发由代理模拟：
// 按下 450ms 后按 ~30 次/秒 注入"抬起+按下"，直到收到 ku。与 Windows 版参数一致。
let kRepeatDelayMs: Int64 = 450
let kRepeatIntervalMs: Int64 = 33

// MARK: - HID 键码 → macOS 虚拟键码

private func buildKeyMap() -> [Int: CGKeyCode] {
    var m = [Int: CGKeyCode]()
    // 字母 A-Z（HID 0x04..0x1D）→ kVK_ANSI_A..Z（QWERTY 物理序，不是字母序）
    let ansiLetters: [CGKeyCode] = [
        0x00, 0x0B, 0x08, 0x02, 0x0E, 0x03, 0x05, 0x04, 0x22, 0x26,
        0x28, 0x25, 0x2E, 0x2D, 0x1F, 0x23, 0x0C, 0x0F, 0x01, 0x11,
        0x20, 0x09, 0x0D, 0x07, 0x10, 0x06,
    ]
    for i in 0..<26 { m[0x04 + i] = ansiLetters[i] }
    // 数字 1-0（HID 0x1E..0x27）→ kVK_ANSI_1..0（物理序，注意 5/6 与 7/8/9 的错位）
    let ansiDigits: [CGKeyCode] = [0x12, 0x13, 0x14, 0x15, 0x17, 0x16, 0x1A, 0x1C, 0x19, 0x1D]
    for i in 0..<10 { m[0x1E + i] = ansiDigits[i] }
    m[0x28] = 0x24  // Enter → Return
    m[0x29] = 0x35  // Esc
    m[0x2A] = 0x33  // Backspace → Delete（退格键）
    m[0x2B] = 0x30  // Tab
    m[0x2C] = 0x31  // Space
    m[0x2D] = 0x1B  // -
    m[0x2E] = 0x18  // =
    m[0x2F] = 0x21  // [
    m[0x30] = 0x1E  // ]
    m[0x31] = 0x2A  // \
    m[0x33] = 0x29  // ;
    m[0x34] = 0x27  // '
    m[0x35] = 0x32  // `
    m[0x36] = 0x2B  // ,
    m[0x37] = 0x2F  // .
    m[0x38] = 0x2C  // /
    m[0x39] = 0x39  // CapsLock
    // F1-F12：macOS 的 F 区键码不连续，逐项映射
    let fKeys: [CGKeyCode] = [0x7A, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64, 0x65, 0x6D, 0x67, 0x6F]
    for i in 0..<12 { m[0x3A + i] = fKeys[i] }
    m[0x46] = 0x69  // PrintScreen → F13
    m[0x47] = 0x6B  // ScrollLock → F14
    m[0x48] = 0x71  // Pause → F15
    m[0x49] = 0x72  // Insert → Help
    m[0x4A] = 0x73  // Home
    m[0x4B] = 0x74  // PgUp
    m[0x4C] = 0x75  // Delete → ForwardDelete
    m[0x4D] = 0x77  // End
    m[0x4E] = 0x79  // PgDn
    m[0x4F] = 0x7C  // →
    m[0x50] = 0x7B  // ←
    m[0x51] = 0x7D  // ↓
    m[0x52] = 0x7E  // ↑
    // 数字小键盘（App v1.4.0 起支持，电脑端识别为小键盘键位）
    m[0x53] = 0x47  // NumLock → KeypadClear（多数 Mac 键盘无 NumLock 键）
    m[0x54] = 0x4B  // 小键盘 /
    m[0x55] = 0x43  // 小键盘 *
    m[0x56] = 0x4E  // 小键盘 -
    m[0x57] = 0x45  // 小键盘 +
    m[0x58] = 0x4C  // 小键盘 Enter
    m[0x59] = 0x53; m[0x5A] = 0x54; m[0x5B] = 0x55  // 小键盘 1 2 3
    m[0x5C] = 0x56; m[0x5D] = 0x57; m[0x5E] = 0x58  // 小键盘 4 5 6
    m[0x5F] = 0x59; m[0x60] = 0x5B; m[0x61] = 0x5C  // 小键盘 7 8 9
    m[0x62] = 0x52  // 小键盘 0
    m[0x63] = 0x41  // 小键盘 .
    // 修饰键（HID 0xE0..0xE7）
    m[0xE0] = 0x3B  // LCtrl → Control
    m[0xE1] = 0x38  // LShift → Shift
    m[0xE2] = 0x3A  // LAlt → Option
    m[0xE3] = 0x37  // LGUI → Command
    m[0xE4] = 0x3E  // RCtrl → RightControl
    m[0xE5] = 0x3C  // RShift → RightShift
    m[0xE6] = 0x3D  // RAlt → RightOption（AltGr）
    m[0xE7] = 0x37  // RGUI → Command（macOS 无独立右 Cmd 键码）
    return m
}

// MARK: - 日志

private let logQueue = DispatchQueue(label: "com.nightboard.agent.log")
private var logHandle: FileHandle?
private let timeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss"
    return f
}()

func logFileURL() -> URL {
    let base = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
    return base.appendingPathComponent("Logs/NightBoardAgent.log")
}

private func openLog() {
    let url = logFileURL()
    try? FileManager.default.createDirectory(
        at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    if !FileManager.default.fileExists(atPath: url.path) {
        FileManager.default.createFile(atPath: url.path, contents: nil)
    }
    logHandle = FileHandle(forWritingAtPath: url.path)
    try? logHandle?.seekToEnd()
}

func log(_ msg: String) {
    let line = "[\(timeFormatter.string(from: Date()))] \(msg)\n"
    if let h = logHandle, let d = line.data(using: .utf8) {
        logQueue.async {
            h.seekToEndOfFile()
            h.write(d)
        }
    }
    FileHandle.standardError.write(line.data(using: .utf8) ?? Data())
}

// MARK: - 小工具

private func jsonEscape(_ s: String) -> String {
    var out = ""
    out.reserveCapacity(s.count + 8)
    for c in s {
        if c == "\"" { out += "\\\"" }
        else if c == "\\" { out += "\\\\" }
        else if let a = c.asciiValue, a < 32 { out += " " }
        else { out.append(c) }
    }
    return out
}

private func computerName() -> String {
    var name = ProcessInfo.processInfo.hostName
    if let r = name.range(of: ".local") { name = String(name[..<r.lowerBound]) }
    if name.isEmpty { name = "Mac" }
    return name
}

private func ipString(_ addr: sockaddr_in) -> String {
    // sin_addr 是网络字节序；转成主机序后按大端取四段，任何字节序主机上都正确
    let a = UInt32(bigEndian: addr.sin_addr.s_addr)
    return "\((a >> 24) & 0xFF).\((a >> 16) & 0xFF).\((a >> 8) & 0xFF).\(a & 0xFF)"
}

private func fatalStartup(_ msg: String) {
    log(msg)
    DispatchQueue.main.async {
        let a = NSAlert()
        a.messageText = "NightBoardAgent 无法启动"
        a.informativeText = msg + "\n\n请关闭其他 NightBoardAgent 实例后重试。"
        a.alertStyle = .critical
        a.addButton(withTitle: "退出")
        a.runModal()
        NSApp.terminate(nil)
    }
    Thread.sleep(forTimeInterval: 3)
    exit(1)
}

// MARK: - 单条手机连接

final class ClientConn {
    let fd: Int32
    let remote: String
    private let writeLock = NSLock()
    private var closed = false

    init?(fd: Int32) {
        self.fd = fd
        var addr = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let r = withUnsafeMutablePointer(to: &addr) { p -> Int32 in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                getpeername(fd, sa, &len)
            }
        }
        remote = (r == 0) ? ipString(addr) : "unknown"
    }

    func sendLine(_ text: String) {
        writeLock.lock()
        defer { writeLock.unlock() }
        guard !closed else { return }
        var data = Array(text.utf8)
        data.append(0x0A)  // 协议按换行分隔
        var offset = 0
        while offset < data.count {
            let n = data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Int in
                guard let base = raw.baseAddress else { return -1 }
                return send(fd, base.advanced(by: offset), raw.count - offset, Int32(MSG_NOSIGNAL))
            }
            if n <= 0 { closeLocked(); return }
            offset += n
        }
    }

    func closeConn() {
        writeLock.lock()
        closeLocked()
        writeLock.unlock()
    }

    private func closeLocked() {
        guard !closed else { return }
        closed = true
        shutdown(fd, Int32(SHUT_RDWR))
        close(fd)
    }
}

// MARK: - 接收代理核心

final class Agent {
    static let shared = Agent()

    let keyMap = buildKeyMap()
    var onStateChange: (() -> Void)?

    private let lock = NSLock()
    private var pressed = Set<Int>()
    private var prevButtons = 0
    private var repeatTimers = [Int: DispatchSourceTimer]()
    private var activeConn: ClientConn?
    private var deviceName = ""

    private let repeatQueue = DispatchQueue(label: "com.nightboard.agent.repeat")
    private let acceptQueue = DispatchQueue(label: "com.nightboard.agent.accept")
    private let discoveryQueue = DispatchQueue(label: "com.nightboard.agent.discovery")

    var connectedDevice: String? {
        lock.lock(); defer { lock.unlock() }
        return deviceName.isEmpty ? nil : deviceName
    }

    func start() {
        discoveryQueue.async { self.udpLoop() }
        acceptQueue.async { self.tcpAcceptLoop() }
    }

    // MARK: UDP 自动发现

    private func udpLoop() {
        while true {
            let fd = socket(AF_INET, SOCK_DGRAM, 0)
            guard fd >= 0 else {
                log("UDP socket 创建失败（5 秒后重试）")
                Thread.sleep(forTimeInterval: 5)
                continue
            }
            defer { close(fd) }

            var addr = sockaddr_in()
            addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = kDiscoveryPort.bigEndian
            addr.sin_addr.s_addr = INADDR_ANY

            let bound = withUnsafePointer(to: &addr) { p -> Int32 in
                p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    bind(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
            guard bound == 0 else {
                log("UDP bind 失败（端口 \(kDiscoveryPort) 被占用？5 秒后重试）")
                Thread.sleep(forTimeInterval: 5)
                continue
            }
            log("UDP 发现服务就绪（端口 \(kDiscoveryPort)）")

            var buf = [UInt8](repeating: 0, count: 512)
            recvLoop: while true {
                var from = sockaddr_in()
                var fromLen = socklen_t(MemoryLayout<sockaddr_in>.size)
                let n = withUnsafeMutablePointer(to: &from) { p -> Int in
                    p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        recvfrom(fd, &buf, buf.count, 0, sa, &fromLen)
                    }
                }
                if n < 0 {
                    if errno == EINTR { continue recvLoop }
                    break recvLoop
                }
                let text = String(decoding: buf[0..<n], as: UTF8.self)
                guard text.hasPrefix(kDiscoverReq) else { continue }
                let reply = "{\"t\":\"disc\",\"n\":\"\(jsonEscape(computerName()))\",\"p\":\(kTransportPort),\"v\":1}"
                let bytes = Array(reply.utf8)
                let sent = withUnsafeMutablePointer(to: &from) { p -> Int in
                    p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        bytes.withUnsafeBufferPointer { bp -> Int in
                            guard let base = bp.baseAddress else { return -1 }
                            return sendto(fd, base, bytes.count, 0, sa, fromLen)
                        }
                    }
                }
                if sent > 0 {
                    log("收到来自 \(ipString(from)) 的发现请求，已应答")
                }
            }
            log("UDP 发现服务退出（5 秒后重启）")
            Thread.sleep(forTimeInterval: 5)
        }
    }

    // MARK: TCP 输入通道

    private func tcpAcceptLoop() {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else {
            fatalStartup("TCP socket 创建失败")
            return
        }
        defer { close(fd) }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = kTransportPort.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY

        let bound = withUnsafePointer(to: &addr) { p -> Int32 in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                bind(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, listen(fd, 8) == 0 else {
            fatalStartup("无法监听 TCP \(kTransportPort)：端口可能被其他 NightBoardAgent 占用")
            return
        }
        log("TCP 监听已就绪（端口 \(kTransportPort)），等待手机连接…")

        while true {
            var ca = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let cfd = withUnsafeMutablePointer(to: &ca) { p -> Int32 in
                p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    accept(fd, sa, &len)
                }
            }
            if cfd < 0 {
                if errno == EINTR { continue }
                break
            }
            var nodelay: Int32 = 1
            setsockopt(cfd, IPPROTO_TCP, TCP_NODAY, &nodelay, socklen_t(MemoryLayout<Int32>.size))
            guard let conn = ClientConn(fd: cfd) else {
                close(cfd)
                continue
            }
            DispatchQueue(label: "nb68-client").async { self.clientLoop(conn) }
        }
        log("TCP 监听退出")
    }

    private func clientLoop(_ conn: ClientConn) {
        lock.lock()
        let old = activeConn
        activeConn = conn
        prevButtons = 0
        deviceName = ""
        lock.unlock()
        // 只保留最新一台手机，旧连接直接替换
        if let old = old { old.closeConn() }

        log("手机已连接: \(conn.remote)")
        // 新连接的边沿检测基线归零：上一台手机异常断线时若按钮为按下态，
        // 不复位会导致新连接的第一条 b:1 被判"无变化"，表现为重连后轻点失灵
        conn.sendLine("{\"t\":\"led\",\"c\":\(ledMask())}")
        notify()

        var buf = [UInt8](repeating: 0, count: 4096)
        var pending = [UInt8]()
        readLoop: while true {
            let n = buf.withUnsafeMutableBufferPointer { bp -> Int in
                guard let base = bp.baseAddress else { return 0 }
                return recv(conn.fd, base, bp.count, 0)
            }
            if n <= 0 { break readLoop }
            pending.append(contentsOf: buf[0..<n])
            while let nl = pending.firstIndex(of: 0x0A) {
                let line = String(decoding: pending[0..<nl], as: UTF8.self)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                pending.removeFirst(nl + 1)
                if !line.isEmpty { handleLine(line, conn: conn) }
            }
        }

        log("手机断开: \(conn.remote)")
        releaseAllKeys()
        lock.lock()
        if activeConn === conn {
            activeConn = nil
            deviceName = ""
        }
        lock.unlock()
        conn.closeConn()
        notify()
    }

    // MARK: 消息分发

    private func handleLine(_ line: String, conn: ClientConn) {
        guard let data = line.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let t = obj["t"] as? String else { return }

        switch t {
        case "hello":
            let name = (obj["n"] as? String) ?? "手机"
            lock.lock(); deviceName = name; lock.unlock()
            log("打字设备: \(name)")
            notify()
        case "kd":
            keyDown(jsonInt(obj["c"]))
        case "ku":
            keyUp(jsonInt(obj["c"]))
        case "m":
            handleMouse(dx: jsonInt(obj["dx"]), dy: jsonInt(obj["dy"]),
                        wheel: jsonInt(obj["w"]), buttons: jsonInt(obj["b"]))
        case "ra":
            releaseAllKeys()
        case "p":
            conn.sendLine("{\"t\":\"po\",\"i\":\(jsonInt(obj["i"]))}")
        default:
            break
        }
    }

    private func jsonInt(_ v: Any?) -> Int {
        switch v {
        case let n as Int: return n
        case let n as Double: return Int(n)
        case let n as NSNumber: return n.intValue
        case let s as String: return Int(s) ?? 0
        default: return 0
        }
    }

    // MARK: 键盘注入

    func keyDown(_ hid: Int) {
        guard let vk = keyMap[hid] else { return }
        lock.lock()
        pressed.insert(hid)
        postKeyLocked(vk, down: true)
        lock.unlock()
        startRepeat(hid)
        if hid == 0x39 { postLed() }   // CapsLock 状态可能翻转
    }

    func keyUp(_ hid: Int) {
        guard let vk = keyMap[hid] else { return }
        lock.lock()
        pressed.remove(hid)
        postKeyLocked(vk, down: false)
        lock.unlock()
        stopRepeat(hid)
        if hid == 0x39 { postLed() }
    }

    /// 调用方必须已持有 lock：抬起事件必须与 pressed 移除、连发取消同锁，
    /// 否则连发表例程可能在键已抬起后又补一次"按下"，造成卡键。
    private func postKeyLocked(_ vk: CGKeyCode, down: Bool) {
        guard let e = CGEvent(keyboardEventSource: nil, virtualKey: vk, keyDown: down) else { return }
        e.post(tap: .cghidEventTap)
    }

    func releaseAllKeys() {
        lock.lock()
        let toRelease = Array(pressed)
        pressed.removeAll()
        let timers = repeatTimers
        repeatTimers.removeAll()
        for hid in toRelease {
            if let vk = keyMap[hid] { postKeyLocked(vk, down: false) }
        }
        // 补发鼠标按键抬起：拖动/点击进行中手机断线时，防止电脑端左/右键卡住
        for e in mouseButtonEventsLocked(buttons: 0, at: currentMouseLocation()) {
            e.post(tap: .cghidEventTap)
        }
        lock.unlock()
        for t in timers.values { t.cancel() }
    }

    // MARK: 长按连发

    private func startRepeat(_ hid: Int) {
        if hid >= 0xE0 && hid <= 0xE7 { return }   // 修饰键不需要连发
        lock.lock()
        guard repeatTimers[hid] == nil else { lock.unlock(); return }
        let t = DispatchSource.makeTimerSource(queue: repeatQueue)
        t.schedule(deadline: .now() + .milliseconds(kRepeatDelayMs),
                   repeating: .milliseconds(kRepeatIntervalMs))
        t.setEventHandler { [weak self] in
            guard let self = self else { return }
            self.lock.lock()
            let still = self.pressed.contains(hid)
            let vk = still ? self.keyMap[hid] : nil
            if let vk = vk {
                // 先抬再按：键在逻辑上保持按住，与 Windows 版 RepeatTap 相同
                self.postKeyLocked(vk, down: false)
                self.postKeyLocked(vk, down: true)
            }
            self.lock.unlock()
        }
        repeatTimers[hid] = t
        lock.unlock()
        t.resume()
    }

    private func stopRepeat(_ hid: Int) {
        lock.lock()
        let t = repeatTimers.removeValue(forKey: hid)
        lock.unlock()
        t?.cancel()
    }

    // MARK: 鼠标注入

    func handleMouse(dx: Int, dy: Int, wheel: Int, buttons: Int) {
        let cur = currentMouseLocation()
        var events = [CGEvent]()
        if dx != 0 || dy != 0 {
            // 左键按住时发 dragged 事件，否则 AppKit 里拖选/拖动不生效
            lock.lock()
            let dragging = (prevButtons & 1) != 0
            lock.unlock()
            let type: CGEventType = dragging ? .leftMouseDragged : .mouseMoved
            if let e = CGEvent(mouseEventSource: nil, mouseType: type,
                               mouseCursorPosition: CGPoint(x: cur.x + CGFloat(dx), y: cur.y + CGFloat(dy)),
                               mouseButton: .left) {
                events.append(e)
            }
        }
        if wheel != 0 {
            // 手机端 1 格 ≈ 物理滚轮 1 格；用像素单位发送，不支持平滑滚动的 App
            // 会由系统折算成行，与 Windows 版 WHEEL_DELTA(120) 手感一致
            if let e = CGEvent(scrollWheelEventSource: nil, units: .unitPixel,
                               wheel1: Int32(wheel * 120), wheel2: 0) {
                events.append(e)
            }
        }
        lock.lock()
        events.append(contentsOf: mouseButtonEventsLocked(buttons: buttons, at: cur))
        lock.unlock()
        for e in events { e.post(tap: .cghidEventTap) }
    }

    /// 鼠标按键边沿检测，调用方必须已持有 lock；返回需要注入的事件
    private func mouseButtonEventsLocked(buttons: Int, at pos: CGPoint) -> [CGEvent] {
        var out = [CGEvent]()
        if (buttons & 1) != (prevButtons & 1) {
            let type: CGEventType = (buttons & 1) != 0 ? .leftMouseDown : .leftMouseUp
            if let e = CGEvent(mouseEventSource: nil, mouseType: type,
                               mouseCursorPosition: pos, mouseButton: .left) {
                out.append(e)
            }
        }
        if (buttons & 2) != (prevButtons & 2) {
            let type: CGEventType = (buttons & 2) != 0 ? .rightMouseDown : .rightMouseUp
            if let e = CGEvent(mouseEventSource: nil, mouseType: type,
                               mouseCursorPosition: pos, mouseButton: .right) {
                out.append(e)
            }
        }
        prevButtons = buttons
        return out
    }

    private func currentMouseLocation() -> CGPoint {
        // NSEvent.mouseLocation 是 AppKit 屏幕坐标（主屏左下原点、y 向上），
        // CG 全局显示坐标是主屏左上原点、y 向下，按主屏高度翻转
        let ns = NSEvent.mouseLocation
        let h = CGDisplayBounds(CGMainDisplayID()).height
        return CGPoint(x: ns.x, y: h - ns.y)
    }

    // MARK: 键盘灯回传

    private func ledMask() -> Int {
        var mask = 0
        // NumLock / ScrollLock 在 Mac 键盘上不存在，恒为灭；CapsLock 可读
        if NSEvent.modifierFlags.contains(.capsLock) { mask |= 0x02 }
        return mask
    }

    private func postLed() {
        lock.lock()
        let conn = activeConn
        lock.unlock()
        conn?.sendLine("{\"t\":\"led\",\"c\":\(ledMask())}")
    }

    // MARK: 状态通知

    private func notify() {
        onStateChange?()
    }
}

// MARK: - 菜单栏 App

@main
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem!
    private var statusMenuItem: NSMenuItem!
    private var permissionMenuItem: NSMenuItem!
    private var deviceMenuItem: NSMenuItem!

    private let agent = Agent.shared

    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.setActivationPolicy(.accessory)   // 菜单栏常驻，无 Dock 图标
        app.run()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        openLog()
        log("==============================================")
        log("  NightBoardAgent (macOS) - NightBoard68 局域网接收端")
        log("==============================================")
        log("本机名: \(computerName())")
        log("端口:   UDP \(kDiscoveryPort) (发现) / TCP \(kTransportPort) (输入)")
        if AXIsProcessTrusted() {
            log("辅助功能权限: 已授予")
        } else {
            log("辅助功能权限: 未授予！合成键鼠事件会被系统丢弃，请到")
            log("系统设置 → 隐私与安全性 → 辅助功能 勾选本 App（菜单栏图标有快捷入口）")
        }

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        buildMenu()

        agent.onStateChange = { [weak self] in
            DispatchQueue.main.async { self?.refreshUI() }
        }
        agent.start()
        refreshUI()

        // 权限可能稍后在系统设置里授予，定时刷新图标与提示
        Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            self?.refreshUI()
        }
    }

    private func buildMenu() {
        let menu = NSMenu()

        statusMenuItem = NSMenuItem(title: "状态：启动中…", action: nil, keyEquivalent: "")
        statusMenuItem.isEnabled = false
        menu.addItem(statusMenuItem)

        deviceMenuItem = NSMenuItem(title: "", action: nil, keyEquivalent: "")
        deviceMenuItem.isEnabled = false
        deviceMenuItem.isHidden = true
        menu.addItem(deviceMenuItem)

        menu.addItem(.separator())

        permissionMenuItem = NSMenuItem(
            title: "⚠️ 辅助功能权限未授予（注入无效，点击前往设置）",
            action: #selector(openAccessibilitySettings),
            keyEquivalent: "")
        permissionMenuItem.target = self
        menu.addItem(permissionMenuItem)

        menu.addItem(.separator())

        let logItem = NSMenuItem(title: "打开日志", action: #selector(openLogFile), keyEquivalent: "")
        logItem.target = self
        menu.addItem(logItem)

        let aboutItem = NSMenuItem(title: "关于 NightBoardAgent", action: #selector(showAbout), keyEquivalent: "")
        aboutItem.target = self
        menu.addItem(aboutItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem(title: "退出", action: #selector(quit), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)

        statusItem.menu = menu
    }

    private func refreshUI() {
        let trusted = AXIsProcessTrusted()
        permissionMenuItem.isHidden = trusted

        if !trusted {
            statusItem.button?.title = "NB68 ⚠️"
            statusMenuItem.title = "状态：缺少辅助功能权限（点击上方提示授权）"
            deviceMenuItem.isHidden = true
            return
        }
        if let d = agent.connectedDevice {
            statusItem.button?.title = "NB68 ✓"
            statusMenuItem.title = "状态：已连接（TCP \(kTransportPort)）"
            deviceMenuItem.title = "打字设备：\(d)"
            deviceMenuItem.isHidden = false
        } else {
            statusItem.button?.title = "NB68"
            statusMenuItem.title = "状态：等待手机连接…（TCP \(kTransportPort) / UDP \(kDiscoveryPort)）"
            deviceMenuItem.isHidden = true
        }
    }

    @objc private func openAccessibilitySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    @objc private func openLogFile() {
        NSWorkspace.shared.open(logFileURL())
    }

    @objc private func showAbout() {
        let a = NSAlert()
        a.messageText = "NightBoardAgent (macOS)"
        a.informativeText =
            "NightBoard68 局域网模式电脑端接收代理。\n\n"
            + "UDP \(kDiscoveryPort) 自动发现 · TCP \(kTransportPort) 输入传输\n"
            + "协议与 Windows 版 NightBoardAgent.exe 完全一致。\n\n"
            + "注入合成键鼠事件需要「辅助功能」权限。\n"
            + "MIT License · github.com/elysia395/NightBoard68"
        a.alertStyle = .informational
        a.addButton(withTitle: "好")
        a.runModal()
    }

    @objc private func quit() {
        agent.releaseAllKeys()
        NSApp.terminate(nil)
    }
}

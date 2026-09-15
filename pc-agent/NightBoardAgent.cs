// NightBoardAgent - 配合 NightBoard68 安卓 App 的电脑端接收代理
// 免安装：单文件 exe，双击即跑，不需要管理员权限，不需要装驱动。
//
// 职责：
//   1. UDP :6869 应答手机广播（自动发现）
//   2. TCP :6868 接收手机发来的按键/鼠标事件（换行分隔 JSON）
//   3. 通过 Win32 SendInput 注入成真实键鼠输入（等同于硬件键盘）
//
// 协议（手机→电脑）：{"t":"hello","n":"机型"} {"t":"kd","c":HID键码}
//   {"t":"ku","c":HID键码} {"t":"m","dx":..,"dy":..,"w":..,"b":..} {"t":"ra"}
//   {"t":"txt","s":"任意Unicode文本(中文等)"} {"t":"p","i":id}
// 协议（电脑→手机）：{"t":"po","i":id} {"t":"led","c":键盘灯位掩码} {"t":"disc","n":计算机名,"p":6868}
//
// 首次运行如 Windows 防火墙弹出提示，请勾选"专用网络"并允许，否则手机搜不到。
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;
using System.Threading;

class NightBoardAgent
{
    const int DISCOVERY_PORT = 6869;
    const int TRANSPORT_PORT = 6868;
    const string DISCOVER_REQ = "NB68_DISCOVER_V1";

    static long eventCount = 0;
    static volatile TcpClient activeClient;

    static void Main(string[] args)
    {
        bool verbose = args.Length > 0 && (args[0] == "-v" || args[0] == "--verbose");
        try { Console.OutputEncoding = Encoding.UTF8; } catch { }

        Console.WriteLine("==============================================");
        Console.WriteLine("  NightBoardAgent - NightBoard68 局域网接收端");
        Console.WriteLine("==============================================");
        Console.WriteLine("本机名: " + Environment.MachineName);
        Console.WriteLine("端口:   UDP " + DISCOVERY_PORT + " (发现) / TCP " + TRANSPORT_PORT + " (输入)");
        Console.WriteLine("退出:   关闭本窗口或按 Ctrl+C");
        Console.WriteLine("----------------------------------------------");
        bool isAdmin = IsAdministrator();
        Console.WriteLine("权限:   " + (isAdmin ? "管理员（可向所有窗口注入输入）" : "普通（若目标程序以管理员权限运行将无法输入）"));
        if (!isAdmin)
        {
            Console.WriteLine("提示:   某些程序（如以管理员运行的 IDE）普通权限无法注入输入，");
            Console.WriteLine("        如需修复：右键本 exe → [以管理员身份运行]，");
            Console.WriteLine("        或在文件属性 → 兼容性 → 勾选[以管理员身份运行此程序]。");
        }
        Console.WriteLine("----------------------------------------------");
        Console.WriteLine("首次使用如弹出 Windows 防火墙提示，请点【允许】");
        Console.WriteLine("(勾选\"专用网络\")，否则手机搜不到本机。");
        Console.WriteLine("----------------------------------------------");

        var udpThread = new Thread(UdpDiscoveryLoop) { IsBackground = true };
        udpThread.Start();

        var tcpThread = new Thread(delegate() { TcpAcceptLoop(verbose); });
        tcpThread.IsBackground = false;
        tcpThread.Start();
        tcpThread.Join();
    }

    // ---------- UDP 自动发现 ----------

    static void UdpDiscoveryLoop()
    {
        while (true)
        {
            try
            {
                using (var udp = new UdpClient(DISCOVERY_PORT))
                {
                    var any = new IPEndPoint(IPAddress.Any, 0);
                    while (true)
                    {
                        var data = udp.Receive(ref any);
                        var text = Encoding.ASCII.GetString(data);
                        if (text.StartsWith(DISCOVER_REQ, StringComparison.Ordinal))
                        {
                            string reply = "{\"t\":\"disc\",\"n\":\"" + JsonEscape(Environment.MachineName) +
                                           "\",\"p\":" + TRANSPORT_PORT + ",\"v\":1}";
                            var bytes = Encoding.UTF8.GetBytes(reply);
                            udp.Send(bytes, bytes.Length, any);
                            Log("收到来自 " + any.Address + " 的发现请求，已应答");
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Log("UDP 发现服务异常: " + e.Message + "（5 秒后重试）");
                Log("若反复出现，多半是防火墙拦截了 UDP " + DISCOVERY_PORT);
                Thread.Sleep(5000);
            }
        }
    }

    // ---------- TCP 输入通道 ----------

    static void TcpAcceptLoop(bool verbose)
    {
        var listener = new TcpListener(IPAddress.Any, TRANSPORT_PORT);
        try
        {
            listener.Start();
            Log("TCP 监听已就绪，等待手机连接…");
        }
        catch (Exception e)
        {
            Log("无法监听 TCP " + TRANSPORT_PORT + ": " + e.Message);
            Log("可能端口被占用，请关闭其他 NightBoardAgent 后重试。");
            return;
        }

        while (true)
        {
            TcpClient client;
            try { client = listener.AcceptTcpClient(); }
            catch { break; }

            // 只保留最新一台手机；旧连接直接替换
            var old = activeClient;
            if (old != null) { try { old.Close(); } catch { } }

            activeClient = client;
            var t = new Thread(delegate() { ClientLoop(client, verbose); });
            t.IsBackground = true;
            t.Start();
        }
    }

    static void ClientLoop(TcpClient client, bool verbose)
    {
        var remote = "";
        try { remote = ((IPEndPoint)client.Client.RemoteEndPoint).Address.ToString(); } catch { }
        Log("手机已连接: " + remote);
        // 新连接的边沿检测基线归零：上一台手机异常断线时若按钮为按下态，
        // 不复位会导致新连接的第一条 b:1 被判"无变化"，表现为重连后轻点失灵
        prevButtons = 0;
        SendToPhone("{\"t\":\"led\",\"c\":" + LedMask() + "}");

        var buffer = new StringBuilder();
        var chunk = new char[2048];
        try
        {
            client.NoDelay = true;
            using (var reader = new System.IO.StreamReader(client.GetStream(), new UTF8Encoding(false)))
            {
                while (true)
                {
                    int n = reader.Read(chunk, 0, chunk.Length);
                    if (n <= 0) break;
                    buffer.Append(chunk, 0, n);
                    int idx;
                    while ((idx = buffer.ToString().IndexOf('\n')) >= 0)
                    {
                        string line = buffer.ToString(0, idx).Trim();
                        buffer.Remove(0, idx + 1);
                        if (line.Length > 0) HandleMessage(line, verbose);
                    }
                }
            }
        }
        catch { }
        Log("手机断开: " + remote);
        ReleaseAllKeys();
        try { client.Close(); } catch { }
        if (activeClient == client) activeClient = null;
    }

    static void SendToPhone(string json)
    {
        var c = activeClient;
        if (c == null) return;
        try
        {
            var bytes = Encoding.UTF8.GetBytes(json + "\n");
            c.GetStream().Write(bytes, 0, bytes.Length);
            c.GetStream().Flush();
        }
        catch { }
    }

    static void HandleMessage(string json, bool verbose)
    {
        string type = GetStr(json, "t");
        if (type == null) return;
        System.Threading.Interlocked.Increment(ref eventCount);

        switch (type)
        {
            case "hello":
                Log("打字设备: " + GetStr(json, "n"));
                break;
            case "kd":
                KeyDown(GetInt(json, "c"), verbose);
                break;
            case "ku":
                KeyUp(GetInt(json, "c"), verbose);
                break;
            case "m":
                HandleMouse(GetInt(json, "dx"), GetInt(json, "dy"), GetInt(json, "w"), GetInt(json, "b"));
                break;
            case "ra":
                ReleaseAllKeys();
                break;
            case "txt":
                TypeText(UnescapeJson(GetStr(json, "s") ?? ""), verbose);
                break;
            case "p":
                SendToPhone("{\"t\":\"po\",\"i\":" + GetInt(json, "i") + "}");
                break;
        }
    }

    // ---------- 文本注入（手机软键盘逐字发送，支持中文等任意 Unicode） ----------

    /// 通过 SendInput KEYEVENTF_UNICODE 逐字输入。UTF-16 代理对（如 emoji）
    /// 按两个码元连发，Windows 会自行拼合。
    static void TypeText(string s, bool verbose)
    {
        if (string.IsNullOrEmpty(s)) return;
        var list = new List<INPUT>(s.Length * 2);
        foreach (char c in s)
        {
            var down = new INPUT();
            down.type = 1; // INPUT_KEYBOARD
            down.U.ki.wScan = c;
            down.U.ki.dwFlags = KEYEVENTF_UNICODE;
            list.Add(down);
            var up = new INPUT();
            up.type = 1;
            up.U.ki.wScan = c;
            up.U.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;
            list.Add(up);
        }
        SendInputs(list.ToArray());
        if (verbose) Log("txt " + s);
    }

    /// 手机端 JSON 字符串是转义过的（\" \\ \n 等），按标准规则反转义
    static string UnescapeJson(string s)
    {
        var sb = new StringBuilder(s.Length);
        for (int i = 0; i < s.Length; i++)
        {
            char c = s[i];
            if (c == '\\' && i + 1 < s.Length)
            {
                char n = s[++i];
                switch (n)
                {
                    case '"': sb.Append('"'); break;
                    case '\\': sb.Append('\\'); break;
                    case '/': sb.Append('/'); break;
                    case 'n': sb.Append('\n'); break;
                    case 'r': sb.Append('\r'); break;
                    case 't': sb.Append('\t'); break;
                    case 'u':
                        if (i + 4 < s.Length)
                        {
                            string hex = s.Substring(i + 1, 4);
                            int code;
                            if (int.TryParse(hex, System.Globalization.NumberStyles.HexNumber,
                                System.Globalization.CultureInfo.InvariantCulture, out code))
                            {
                                sb.Append((char)code);
                                i += 4;
                                break;
                            }
                        }
                        sb.Append('u');
                        break;
                    default: sb.Append(n); break;
                }
            }
            else sb.Append(c);
        }
        return sb.ToString();
    }

    // ---------- 键盘注入 ----------

    class KeyDef
    {
        public ushort scan;   // Set 1 扫描码
        public bool ext;      // 是否扩展键
        public int vk;        // 非 0 = 按 VK 注入（PrintScreen / Pause 等特殊键）
        public KeyDef(ushort s, bool e, int v) { scan = s; ext = e; vk = v; }
    }

    static readonly Dictionary<int, KeyDef> KeyMap = BuildKeyMap();
    static readonly HashSet<int> pressed = new HashSet<int>();
    static object keyLock = new object();

    // 局域网注入的按键没有硬件自动重复（那是物理键盘固件干的），
    // 长按连发由代理自己模拟：按下 450ms 后按 ~30 次/秒 注入"按下+抬起"直到收到抬起
    const int REPEAT_DELAY_MS = 450;
    const int REPEAT_INTERVAL_MS = 33;
    static readonly Dictionary<int, System.Threading.Timer> repeatTimers = new Dictionary<int, System.Threading.Timer>();
    static object repeatLock = new object();

    static void StartRepeat(int hid)
    {
        if (hid >= 0xE0 && hid <= 0xE7) return;   // 修饰键不需要连发
        lock (repeatLock)
        {
            if (!repeatTimers.ContainsKey(hid))
            {
                var t = new System.Threading.Timer(_ => RepeatTap(hid), null, REPEAT_DELAY_MS, REPEAT_INTERVAL_MS);
                repeatTimers[hid] = t;
            }
        }
    }

    static void StopRepeat(int hid)
    {
        lock (repeatLock)
        {
            System.Threading.Timer t;
            if (repeatTimers.TryGetValue(hid, out t))
            {
                repeatTimers.Remove(hid);
                t.Dispose();
            }
        }
    }

    static void RepeatTap(int hid)
    {
        lock (keyLock)
        {
            if (!pressed.Contains(hid)) return;   // 键已释放（竞态保护）
        }
        KeyDef d;
        if (KeyMap.TryGetValue(hid, out d))
        {
            SendKey(d, false);
            SendKey(d, true);
        }
    }

    static Dictionary<int, KeyDef> BuildKeyMap()
    {
        var m = new Dictionary<int, KeyDef>();
        // A-Z (HID 0x04..0x1D)
        byte[] letters = { 0x1E,0x30,0x2E,0x20,0x12,0x21,0x22,0x23,0x17,0x24,0x25,0x26,0x32,0x31,0x18,0x19,0x10,0x13,0x1F,0x14,0x16,0x2F,0x11,0x2D,0x15,0x2C };
        for (int i = 0; i < 26; i++) m[0x04 + i] = new KeyDef(letters[i], false, 0);
        // 1-0 (HID 0x1E..0x27)
        for (int i = 0; i < 9; i++) m[0x1E + i] = new KeyDef((byte)(0x02 + i), false, 0);
        m[0x27] = new KeyDef(0x0B, false, 0);
        // 常规键
        m[0x28] = new KeyDef(0x1C, false, 0);   // Enter
        m[0x29] = new KeyDef(0x01, false, 0);   // Esc
        m[0x2A] = new KeyDef(0x0E, false, 0);   // Backspace
        m[0x2B] = new KeyDef(0x0F, false, 0);   // Tab
        m[0x2C] = new KeyDef(0x39, false, 0);   // Space
        m[0x2D] = new KeyDef(0x0C, false, 0);   // -
        m[0x2E] = new KeyDef(0x0D, false, 0);   // =
        m[0x2F] = new KeyDef(0x1A, false, 0);   // [
        m[0x30] = new KeyDef(0x1B, false, 0);   // ]
        m[0x31] = new KeyDef(0x2B, false, 0);   // \
        m[0x33] = new KeyDef(0x27, false, 0);   // ;
        m[0x34] = new KeyDef(0x28, false, 0);   // '
        m[0x35] = new KeyDef(0x29, false, 0);   // `
        m[0x36] = new KeyDef(0x33, false, 0);   // ,
        m[0x37] = new KeyDef(0x34, false, 0);   // .
        m[0x38] = new KeyDef(0x35, false, 0);   // /
        m[0x39] = new KeyDef(0x3A, false, 0);   // CapsLock
        // F1~F10 = HID+1，F11/F12 特例
        for (int i = 0; i < 10; i++) m[0x3A + i] = new KeyDef((byte)(0x3B + i), false, 0);
        m[0x44] = new KeyDef(0x57, false, 0);   // F11
        m[0x45] = new KeyDef(0x58, false, 0);   // F12
        m[0x46] = new KeyDef(0, true, 0x2C);    // PrintScreen (VK_SNAPSHOT)
        m[0x47] = new KeyDef(0x46, false, 0);   // ScrollLock
        m[0x48] = new KeyDef(0, false, 0x13);   // Pause (VK_PAUSE)
        // 编辑键区（Insert~Up）：一律走 VK 注入而非扫描码。
        // 扫描码方案靠 E0(EXTENDEDKEY) 前缀区分方向键与小键盘 8/2/4/6，
        // 但某些环境下 E0 前缀不生效，方向键会被当成小键盘 → NumLock 开时输出数字。
        // VK 与 NumLock 无关、跨键盘布局稳定。
        m[0x49] = new KeyDef(0, false, 0x2D);   // Insert (VK_INSERT)
        m[0x4A] = new KeyDef(0, false, 0x24);   // Home (VK_HOME)
        m[0x4B] = new KeyDef(0, false, 0x21);   // PgUp (VK_PRIOR)
        m[0x4C] = new KeyDef(0, false, 0x2E);   // Delete (VK_DELETE)
        m[0x4D] = new KeyDef(0, false, 0x23);   // End (VK_END)
        m[0x4E] = new KeyDef(0, false, 0x22);   // PgDn (VK_NEXT)
        m[0x4F] = new KeyDef(0, false, 0x27);   // Right (VK_RIGHT)
        m[0x50] = new KeyDef(0, false, 0x25);   // Left (VK_LEFT)
        m[0x51] = new KeyDef(0, false, 0x28);   // Down (VK_DOWN)
        m[0x52] = new KeyDef(0, false, 0x26);   // Up (VK_UP)
        // 数字小键盘 (HID 0x53..0x63)：Set1 扫描码与主键盘数字键区共用，
        // 实际输出方向/数字由电脑端 NumLock 决定（蓝牙 HID 同样如此）
        m[0x53] = new KeyDef(0x45, false, 0);   // NumLk
        m[0x54] = new KeyDef(0x35, false, 0);   // 小键盘 /
        m[0x55] = new KeyDef(0x37, false, 0);   // 小键盘 *
        m[0x56] = new KeyDef(0x4A, false, 0);   // 小键盘 -
        m[0x57] = new KeyDef(0x4E, false, 0);   // 小键盘 +
        m[0x58] = new KeyDef(0x1C, true, 0);    // 小键盘 Enter
        m[0x59] = new KeyDef(0x47, false, 0);   // 7
        m[0x5A] = new KeyDef(0x48, false, 0);   // 8
        m[0x5B] = new KeyDef(0x49, false, 0);   // 9
        m[0x5C] = new KeyDef(0x4B, false, 0);   // 4
        m[0x5D] = new KeyDef(0x4C, false, 0);   // 5
        m[0x5E] = new KeyDef(0x4D, false, 0);   // 6
        m[0x5F] = new KeyDef(0x4F, false, 0);   // 1
        m[0x60] = new KeyDef(0x50, false, 0);   // 2
        m[0x61] = new KeyDef(0x51, false, 0);   // 3
        m[0x62] = new KeyDef(0x52, false, 0);   // 0
        m[0x63] = new KeyDef(0x53, false, 0);   // .
        // 修饰键 (HID 0xE0..0xE7)
        m[0xE0] = new KeyDef(0x1D, false, 0);   // LCtrl
        m[0xE1] = new KeyDef(0x2A, false, 0);   // LShift
        m[0xE2] = new KeyDef(0x38, false, 0);   // LAlt
        m[0xE3] = new KeyDef(0x5B, true, 0);    // LWin
        m[0xE4] = new KeyDef(0x1D, true, 0);    // RCtrl
        m[0xE5] = new KeyDef(0x36, false, 0);   // RShift
        m[0xE6] = new KeyDef(0x38, true, 0);    // RAlt (AltGr)
        m[0xE7] = new KeyDef(0x5C, true, 0);    // RWin
        return m;
    }

    static void KeyDown(int hid, bool verbose)
    {
        KeyDef d;
        if (!KeyMap.TryGetValue(hid, out d)) return;
        lock (keyLock) pressed.Add(hid);
        SendKey(d, false);
        StartRepeat(hid);
        if (IsLedToggle(hid)) PostLed();   // CapsLock/NumLock/ScrollLock 状态可能翻转
        if (verbose) Log("kd " + hid.ToString("X2"));
    }

    static void KeyUp(int hid, bool verbose)
    {
        KeyDef d;
        if (!KeyMap.TryGetValue(hid, out d)) return;
        StopRepeat(hid);
        lock (keyLock) pressed.Remove(hid);
        SendKey(d, true);
        if (IsLedToggle(hid)) PostLed();
        if (verbose) Log("ku " + hid.ToString("X2"));
    }

    /// 切换后会影响键盘灯（LED）状态的键：CapsLock 0x39 / ScrollLock 0x47 / NumLock 0x53。
    /// 只有 CapsLock 回传 LED 会导致手机端 Num/Scroll 状态在切换后不更新。
    static bool IsLedToggle(int hid)
    {
        return hid == 0x39 || hid == 0x47 || hid == 0x53;
    }

    static void SendKey(KeyDef d, bool up)
    {
        var input = new INPUT[1];
        input[0].type = 1; // INPUT_KEYBOARD
        if (d.vk != 0)
        {
            input[0].U.ki.wVk = (ushort)d.vk;
            input[0].U.ki.wScan = d.scan;
            input[0].U.ki.dwFlags = (uint)((d.ext ? KEYEVENTF_EXTENDEDKEY : 0) | (up ? KEYEVENTF_KEYUP : 0));
        }
        else
        {
            input[0].U.ki.wScan = d.scan;
            input[0].U.ki.dwFlags = (uint)(KEYEVENTF_SCANCODE | (d.ext ? KEYEVENTF_EXTENDEDKEY : 0) | (up ? KEYEVENTF_KEYUP : 0));
        }
        SendInputs(input);
    }

    static void ReleaseAllKeys()
    {
        lock (repeatLock)
        {
            foreach (var t in repeatTimers.Values) t.Dispose();
            repeatTimers.Clear();
        }
        int[] toRelease;
        lock (keyLock)
        {
            toRelease = new int[pressed.Count];
            pressed.CopyTo(toRelease);
            pressed.Clear();
        }
        foreach (int hid in toRelease)
        {
            KeyDef d;
            if (KeyMap.TryGetValue(hid, out d)) SendKey(d, true);
        }
        // 补发鼠标按键抬起：拖动/点击进行中手机断线时，防止电脑端左/右键卡住
        // （HandleMouse 按边沿检测注入抬起并把 prevButtons 归零）
        HandleMouse(0, 0, 0, 0);
    }

    // ---------- 鼠标注入 ----------

    static int prevButtons = 0;

    static void HandleMouse(int dx, int dy, int wheel, int buttons)
    {
        var list = new List<INPUT>(3);

        if (dx != 0 || dy != 0)
        {
            var i = new INPUT();
            i.type = 0; // INPUT_MOUSE（注意：0，不是 2——2 是 INPUT_HARDWARE）
            i.U.mi.dx = dx; i.U.mi.dy = dy;
            i.U.mi.dwFlags = MOUSEEVENTF_MOVE;
            list.Add(i);
        }
        if (wheel != 0)
        {
            var i = new INPUT(); i.type = 0;
            i.U.mi.mouseData = (uint)(wheel * 120);   // 1 格 = WHEEL_DELTA(120)
            i.U.mi.dwFlags = MOUSEEVENTF_WHEEL;
            list.Add(i);
        }
        if ((buttons & 1) != (prevButtons & 1))
        {
            var i = new INPUT(); i.type = 0;
            i.U.mi.dwFlags = ((buttons & 1) != 0) ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
            list.Add(i);
        }
        if ((buttons & 2) != (prevButtons & 2))
        {
            var i = new INPUT(); i.type = 0;
            i.U.mi.dwFlags = ((buttons & 2) != 0) ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
            list.Add(i);
        }
        // 中键：手机端鼠标键列"中"= bit2(4)，此前只处理 bit0/1 导致 LAN 中键完全无效
        if ((buttons & 4) != (prevButtons & 4))
        {
            var i = new INPUT(); i.type = 0;
            i.U.mi.dwFlags = ((buttons & 4) != 0) ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
            list.Add(i);
        }
        prevButtons = buttons;

        if (list.Count > 0)
        {
            SendInputs(list.ToArray());
        }
    }

    /// 发送并校验：SendInput 返回 0 说明被系统拦截（UIPI/安全软件），立刻写日志
    static void SendInputs(INPUT[] arr)
    {
        uint n = SendInput((uint)arr.Length, arr, Marshal.SizeOf(typeof(INPUT)));
        if (n == 0)
        {
            int err = Marshal.GetLastWin32Error();
            string hint = "";
            if (err == 5)   // ERROR_ACCESS_DENIED：UIPI——前台窗口以管理员权限运行，本进程普通权限无法注入
                hint = "（前台程序可能以管理员权限运行，请右键本 exe → 以管理员身份运行 后重试）";
            Log("SendInput 被系统拦截（返回0，Win32错误码 " + err + "）" + hint);
        }
    }

    // ---------- 键盘灯回传 ----------

    static int LedMask()
    {
        int mask = 0;
        if ((GetKeyState(0x90) & 1) != 0) mask |= 1;   // NumLock
        if ((GetKeyState(0x14) & 1) != 0) mask |= 2;   // CapsLock
        if ((GetKeyState(0x91) & 1) != 0) mask |= 4;   // ScrollLock
        return mask;
    }

    static void PostLed()
    {
        SendToPhone("{\"t\":\"led\",\"c\":" + LedMask() + "}");
    }

    // ---------- 极简 JSON 取值（协议字段都是扁平的，够用） ----------

    static int GetInt(string json, string key)
    {
        int i = json.IndexOf("\"" + key + "\"", StringComparison.Ordinal);
        if (i < 0) return 0;
        i = json.IndexOf(':', i);
        if (i < 0) return 0;
        i++;
        int sign = 1;
        while (i < json.Length && (json[i] == ' ')) i++;
        if (i < json.Length && json[i] == '-') { sign = -1; i++; }
        int v = 0;
        while (i < json.Length && json[i] >= '0' && json[i] <= '9')
        {
            v = v * 10 + (json[i] - '0');
            i++;
        }
        return sign * v;
    }

    static string GetStr(string json, string key)
    {
        int i = json.IndexOf("\"" + key + "\"", StringComparison.Ordinal);
        if (i < 0) return null;
        i = json.IndexOf(':', i);
        if (i < 0) return null;
        i = json.IndexOf('"', i);
        if (i < 0) return null;
        i++;
        int end = json.IndexOf('"', i);
        if (end < 0) return null;
        return json.Substring(i, end - i);
    }

    static string JsonEscape(string s)
    {
        var sb = new StringBuilder(s.Length + 8);
        foreach (char c in s)
        {
            if (c == '"' || c == '\\') { sb.Append('\\'); sb.Append(c); }
            else if (c < 32) sb.Append(' ');
            else sb.Append(c);
        }
        return sb.ToString();
    }

    static void Log(string msg)
    {
        Console.WriteLine("[" + DateTime.Now.ToString("HH:mm:ss") + "] " + msg);
    }

    /// 当前进程是否拥有管理员权限（决定能否向管理员权限运行的窗口注入输入）
    static bool IsAdministrator()
    {
        try
        {
            using (var id = WindowsIdentity.GetCurrent())
            {
                var p = new WindowsPrincipal(id);
                return p.IsInRole(WindowsBuiltInRole.Administrator);
            }
        }
        catch { return false; }
    }

    // ---------- Win32 ----------

    const uint KEYEVENTF_EXTENDEDKEY = 0x1000;
    const uint KEYEVENTF_KEYUP = 0x0002;
    const uint KEYEVENTF_SCANCODE = 0x0008;
    const uint KEYEVENTF_UNICODE = 0x0004;
    const uint MOUSEEVENTF_MOVE = 0x0001;
    const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    const uint MOUSEEVENTF_LEFTUP = 0x0004;
    const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    const uint MOUSEEVENTF_WHEEL = 0x0800;

    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct INPUT
    {
        public uint type;
        public InputUnion U;
    }

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    static extern short GetKeyState(int nVirtKey);
}

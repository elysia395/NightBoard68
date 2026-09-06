package com.nightboard.keyboard68

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 局域网传输通道：手机通过 WiFi 直连电脑上的 NightBoardAgent（免安装单文件 exe）。
 *
 * 与蓝牙 HID 互补：
 *  - 蓝牙 HID：电脑零安装，但延迟较高（连接间隔 + 2.4G 共存干扰）
 *  - 局域网 TCP：同网段延迟通常 2~10ms（热点直连更低），触控板吞吐不受 HID 报告率限制
 *
 * 协议（TCP 换行分隔 JSON，UTF-8；UDP 6869 发现，TCP 6868 传输）：
 *  手机→电脑：hello / kd / ku / m / ra / p（心跳）
 *  电脑→手机：po（回pong，测RTT）/ led（大写锁定等状态回传）
 *
 * 线路健康：每 1s 发一次 ping，3 次未回视为断线，自动重连；
 * 发现方式：UDP 广播自动发现，或在设置里手动填电脑 IP。
 */
class LanKeyboard(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** 连接状态 / 延迟 / 大写锁定 变化 */
        fun onLanState()
    }

    enum class State { OFF, SEARCHING, CONNECTING, CONNECTED }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("nightboard", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var state = State.OFF
        private set
    @Volatile var agentName: String? = null
        private set
    /** 最近一次心跳往返延迟（毫秒），未测得为 -1 */
    @Volatile var rttMs = -1
        private set
    /** 电脑端回传的大写锁定状态 */
    @Volatile var capsOn = false
        private set

    @Volatile private var running = false
    private var loopThread: Thread? = null
    private var heartThread: Thread? = null

    private val outLock = Any()
    private var out: OutputStream? = null
    private var socket: Socket? = null

    /**
     * 发送队列：键盘/触摸事件在主线程产生，Android 禁止主线程做网络 I/O
     * （NetworkOnMainThreadException），所以 socket 写全部交给专用 writer
     * 线程；单队列 FIFO 保证「修饰键先落、主键后落」的顺序不乱。
     */
    private val sendQueue = LinkedBlockingQueue<String>(512)

    /** 心跳状态：pingId 自增，pingAt = 发出时刻，由读取线程回填 RTT */
    @Volatile private var pingId = 0
    @Volatile private var pingAt = 0L
    @Volatile private var pongSeen = true

    private fun notifyUi() = main.post { listener.onLanState() }

    val isConnected: Boolean get() = state == State.CONNECTED

    /** 手动指定的电脑 IP（空 = 自动广播发现），格式 "192.168.1.8[:端口]" */
    private fun manualHost(): String? = prefs.getString("lan_host", null)?.trim()?.takeIf { it.isNotEmpty() }

    fun start() {
        if (running) return
        running = true
        state = State.SEARCHING
        loopThread = Thread({ loop() }, "nb68-lan").apply {
            isDaemon = true
            start()
        }
        notifyUi()
    }

    fun stop() {
        running = false
        closeSocket()
        state = State.OFF
        agentName = null
        rttMs = -1
        notifyUi()
    }

    /** 「检查连接」：立刻打断退避等待，重新发现 + 连接 */
    fun reconnectNow() {
        closeSocket()   // 连接中会抛异常回到重连点；等待中靠 interrupt 打断 sleep
        loopThread?.interrupt()
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    // ---------- 主循环：发现 → 连接 → 收包，失败退避重试 ----------

    private fun loop() {
        while (running) {
            var agent: DiscoveredAgent? = null
            try {
                val manual = manualHost()
                agent = if (manual != null) probeManual(manual) else discover()
                if (agent == null) {
                    setState(State.SEARCHING)
                    throw IOException("未发现电脑端 NightBoardAgent")
                }
                setState(State.CONNECTING)
                connect(agent)
            } catch (e: InterruptedException) {
                // reconnectNow / stop 打断
            } catch (e: Exception) {
                if (agent != null && state == State.CONNECTING) {
                    Log("连接 ${agent.ip} 失败: ${e.message}")
                }
            }
            closeSocket()
            if (running && state != State.OFF) setState(State.SEARCHING)
            try {
                Thread.sleep(if (agent == null) 4000L else 1500L)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun setState(s: State) {
        state = s
        notifyUi()
    }

    private class DiscoveredAgent(val ip: String, val port: Int, val name: String)

    /** UDP 广播发现：局域网内所有运行 Agent 的电脑会回包 */
    private fun discover(): DiscoveredAgent? {
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 1600
            val req = DISCOVER_REQ.toByteArray(Charsets.US_ASCII)
            s.send(DatagramPacket(req, req.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
            // 部分路由器隔离广播，再对本网段常见网关方向补发一次定向广播
            try {
                val ip = localIp()
                if (ip != null) {
                    val prefix = ip.substringBeforeLast('.') + ".255"
                    s.send(DatagramPacket(req, req.size, InetAddress.getByName(prefix), DISCOVERY_PORT))
                }
            } catch (_: Exception) {
            }
            val buf = ByteArray(512)
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                val remain = (deadline - System.currentTimeMillis()).toInt()
                if (remain <= 0) break
                s.soTimeout = remain
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val text = String(p.data, 0, p.length, Charsets.UTF_8)
                if (!text.contains("\"t\":\"disc\"")) continue
                val o = JSONObject(text)
                val port = o.optInt("p", TRANSPORT_PORT)
                val name = o.optString("n", "电脑")
                return DiscoveredAgent(p.address.hostAddress ?: continue, port, name)
            }
        }
        return null
    }

    /** 手动指定 IP：直接对该地址定向探测（不依赖广播） */
    private fun probeManual(manual: String): DiscoveredAgent? {
        val hostPart = manual.substringBefore(':')
        val port = manual.substringAfter(':', "").toIntOrNull() ?: TRANSPORT_PORT
        DatagramSocket().use { s ->
            s.soTimeout = 1200
            val req = DISCOVER_REQ.toByteArray(Charsets.US_ASCII)
            s.send(DatagramPacket(req, req.size, InetAddress.getByName(hostPart), DISCOVERY_PORT))
            val buf = ByteArray(512)
            val p = DatagramPacket(buf, buf.size)
            return try {
                s.receive(p)
                val o = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                DiscoveredAgent(hostPart, o.optInt("p", port), o.optString("n", "电脑"))
            } catch (_: Exception) {
                // 探测没回包也照样尝试直连（Agent 可能被防火墙挡了 UDP 但放行了 TCP）
                DiscoveredAgent(hostPart, port, "电脑")
            }
        }
    }

    private fun localIp(): String? {
        return try {
            val en = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            while (en.hasMoreElements()) {
                val n = en.nextElement()
                val addresses = n.inetAddresses
                while (addresses.hasMoreElements()) {
                    val a = addresses.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) return a.hostAddress
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // ---------- TCP 连接与收发 ----------

    private fun connect(agent: DiscoveredAgent) {
        Socket().use { sock ->
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(agent.ip, agent.port), 3000)
            sock.soTimeout = 5000
            synchronized(outLock) { out = sock.getOutputStream() }
            socket = sock
            // 先置 CONNECTED 再入队 hello：sendLine 只在 CONNECTED 状态放行
            agentName = agent.name
            pongSeen = true
            rttMs = -1
            setState(State.CONNECTED)
            sendLine(JSONObject().put("t", "hello").put("v", 1).put("n", deviceName()).toString())
            startWriter()
            startHeartbeat()
            readLoop(sock)
        }
    }

    /**
     * 专用发送线程：消费 sendQueue 并写 socket。
     * 只认自己连接那次的 OutputStream（localOut），断线/换线后自动退出，
     * 避免老 writer 把新连接的写入顺序搅乱。
     */
    private fun startWriter() {
        Thread({
            val localOut = synchronized(outLock) { out } ?: return@Thread
            while (running && state == State.CONNECTED) {
                val line = try {
                    sendQueue.poll(1000, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                if (line == null) continue
                val o = synchronized(outLock) { out }
                if (o == null || o !== localOut) break
                try {
                    o.write((line + "\n").toByteArray(Charsets.UTF_8))
                    o.flush()
                } catch (_: Exception) {
                    Log("发送失败，断开重连")
                    closeSocket()
                    break
                }
            }
        }, "nb68-lan-writer").apply { isDaemon = true; start() }
    }

    private fun deviceName(): String =
        Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    /** 阻塞读电脑回包（pong / led）；断开时抛异常走重连 */
    private fun readLoop(sock: Socket) {
        val buf = StringBuilder()
        val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
        val chars = CharArray(512)
        while (running) {
            val n = reader.read(chars)
            if (n < 0) throw IOException("电脑端断开")
            buf.append(chars, 0, n)
            while (true) {
                val nl = buf.indexOf('\n')
                if (nl < 0) break
                val line = buf.substring(0, nl).trim()
                buf.delete(0, nl + 1)
                if (line.isNotEmpty()) handleLine(line)
            }
        }
    }

    private fun handleLine(line: String) {
        try {
            val o = JSONObject(line)
            when (o.optString("t")) {
                "po" -> {
                    rttMs = (System.currentTimeMillis() - pingAt).toInt().coerceAtLeast(0)
                    pongSeen = true
                    notifyUi()
                }
                "led" -> {
                    val mask = o.optInt("c", 0)
                    val caps = (mask and 0x02) != 0
                    if (caps != capsOn) {
                        capsOn = caps
                        notifyUi()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun startHeartbeat() {
        heartThread = Thread({
            while (running && state == State.CONNECTED) {
                try {
                    if (!pongSeen) {
                        // 上一个 ping 没回：连续丢 3 次判定断线
                        misses++
                        if (misses >= 3) {
                            Log("心跳超时，断开重连")
                            closeSocket()
                            return@Thread
                        }
                    } else {
                        misses = 0
                    }
                    pingId++
                    pongSeen = false
                    pingAt = System.currentTimeMillis()
                    sendLine(JSONObject().put("t", "p").put("i", pingId).toString())
                } catch (_: Exception) {
                    closeSocket()
                    return@Thread
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "nb68-lan-heart").apply { isDaemon = true; start() }
    }

    private var misses = 0

    /**
     * 入队即返回（真正的 socket 写在 writer 线程）。
     * 未连接或队列满返回 false（供上层感知），绝不阻塞主线程。
     */
    private fun sendLine(json: String): Boolean {
        if (state != State.CONNECTED) return false
        return sendQueue.offer(json)
    }

    private fun closeSocket() {
        sendQueue.clear()   // 丢弃未发出的旧事件，防止重连后灌入过期按键
        synchronized(outLock) { out = null }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    // ---------- 输入事件发送（键码 = USB HID Usage，与蓝牙报告同一套码表） ----------

    fun keyDown(code: Int): Boolean = isConnected && sendLine(JSONObject().put("t", "kd").put("c", code).toString())

    fun keyUp(code: Int): Boolean = isConnected && sendLine(JSONObject().put("t", "ku").put("c", code).toString())

    fun releaseAll(): Boolean = isConnected && sendLine(JSONObject().put("t", "ra").toString())

    /** buttons: bit0 左键 bit1 右键；dx/dy/wheel 相对量 */
    fun sendMouse(dx: Int, dy: Int, wheel: Int, buttons: Int): Boolean =
        isConnected && sendLine(
            JSONObject().put("t", "m").put("dx", dx).put("dy", dy).put("w", wheel).put("b", buttons).toString()
        )

    private fun Log(msg: String) = android.util.Log.w("LanKeyboard", msg)

    companion object {
        const val DISCOVERY_PORT = 6869
        const val TRANSPORT_PORT = 6868
        const val DISCOVER_REQ = "NB68_DISCOVER_V1"
    }
}

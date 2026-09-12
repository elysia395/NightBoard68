/* QuietType 网页客户端 —— 手机浏览器里的"远程键盘 + 触控板" */
"use strict";

// ---------------- 状态 ----------------
let TOKEN = "";
let connected = false;
let mode = "key";                 // key | mouse
let prevText = "";                // 镜像文本框上一次已同步内容(用于增量 diff)
let composing = false;
let pasteMode = false;            // true=剪贴板粘贴注入(网页/富文本编辑器); false=逐字注入(原生窗口)
let relayMode = false;            // true=公网中继模式(选择电脑 + 口令)

const $ = (id) => document.getElementById(id);
const mirror = $("mirror");
const pad = $("pad");

// 中继模式: 下拉框选电脑、输入口令
async function loadAgents(keepSelection) {
  try {
    const r = await fetch("/api/agents");
    if (!r.ok) return;
    const data = await r.json();
    const sel = $("relay-agent");
    const prev = keepSelection && sel.value ? sel.value : "";
    sel.innerHTML = "";
    (data.agents || []).forEach((a) => {
      const o = document.createElement("option");
      o.value = a.name; o.textContent = a.name;
      sel.appendChild(o);
    });
    if (prev && [...sel.options].some((o) => o.value === prev)) sel.value = prev;
    $("relaybar").classList.toggle("hidden", (data.agents || []).length === 0);
  } catch (_) { /* 中继暂不可达 */ }
}
$("relay-refresh").addEventListener("click", () => loadAgents(true));
$("relay-agent").addEventListener("change", () => loadAgents(true));

// 粘贴模式开关(网页编辑器如聊天框/Notion 等只认粘贴或真实按键)
$("btn-paste").addEventListener("click", () => {
  pasteMode = !pasteMode;
  $("btn-paste").textContent = pasteMode ? "文本:粘贴" : "文本:逐字";
  flash(pasteMode ? "已切换到粘贴模式(适合网页/编辑器)" : "已切换到逐字模式(适合记事本等原生窗口)");
});

// ---------------- 网络 ----------------
async function send(msg) {
  if (relayMode) {
    msg.name = $("relay-agent").value || "";
    msg.pin = $("relay-pin").value || "";
  } else {
    msg.token = TOKEN;
  }
  try {
    const r = await fetch("/api/input", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(msg),
    });
    if (r.status === 403) { setStatus(false); }
    return r.ok;
  } catch (_) {
    setStatus(false);
    return false;
  }
}

async function fetchInfo() {
  try {
    const r = await fetch("/api/info");
    if (!r.ok) return false;
    const info = await r.json();
    TOKEN = info.token;
    if (info.url) $("qr-url").textContent = info.url.replace(/^http:\/\//, "");
    relayMode = (info.name || "").toLowerCase().indexOf("relay") >= 0;
    if (relayMode) {
      $("relaybar").classList.remove("hidden");
      $("qrpane").style.display = "none";     // 中继页没有本机二维码
      loadAgents(false);
    } else {
      $("relaybar").classList.add("hidden");
    }
    setStatus(true);
    return true;
  } catch (_) {
    setStatus(false);
    return false;
  }
}

function setStatus(ok) {
  connected = ok;
  $("dot").className = "dot " + (ok ? "ok" : "off");
  $("status-text").textContent = ok ? "已连接 " + location.host : "未连接";
}

// 断线后每 3 秒自动重连
setInterval(() => { if (!connected) fetchInfo(); }, 3000);
fetchInfo();

// ---------------- 镜像文本: 增量 diff 发送 ----------------
// 计算 old -> new 的差异: 需要删掉几个字符 + 插入了什么文本
function diff(oldStr, newStr) {
  const o = oldStr, n = newStr;
  let p = 0;
  const maxP = Math.min(o.length, n.length);
  while (p < maxP && o.charCodeAt(p) === n.charCodeAt(p)) p++;
  let q = 0;
  const oTail = o.length - p, nTail = n.length - p;
  const maxQ = Math.min(oTail, nTail);
  while (q < maxQ && o.charCodeAt(o.length - 1 - q) === n.charCodeAt(n.length - 1 - q)) q++;
  const removed = oTail - q;
  const inserted = n.slice(p, n.length - q);
  return { removed, inserted };
}

function onMirrorChange() {
  if (composing) return;                       // 输入法组合中,不发送
  const cur = mirror.value;
  if (cur === prevText) return;
  const { removed, inserted } = diff(prevText, cur);
  prevText = cur;
  if (removed > 0) send({ t: "bs", n: removed });
  if (inserted.length > 0) {
    send(pasteMode ? { t: "paste", s: inserted } : { t: "txt", s: inserted });
  }
}

mirror.addEventListener("input", onMirrorChange);
mirror.addEventListener("compositionstart", () => { composing = true; });
mirror.addEventListener("compositionend", () => {
  composing = false;
  onMirrorChange();                            // 组合结束,把最终中文一次发出
});

// 镜像同步: 电脑端内容变了/切了窗口之后,先点这个再继续输入
$("btn-sync").addEventListener("click", () => {
  mirror.value = "";
  prevText = "";
  flash("已同步,从当前位置继续输入");
});

// 扫码面板(电脑上看时显示,手机上自动隐藏)
$("qr-close").addEventListener("click", () => { $("qrpane").style.display = "none"; });

// ---------------- 直发键 / 快捷键 ----------------
document.querySelectorAll(".keyrow .k[data-key]").forEach((b) => {
  b.addEventListener("click", () => {
    send({ t: "key", k: b.dataset.key, mods: [] });
  });
});
document.querySelectorAll(".keyrow .k[data-combo]").forEach((b) => {
  b.addEventListener("click", () => {
    send({ t: "key", k: b.dataset.combo, mods: ["ctrl"] });
  });
});
document.querySelectorAll(".keyrow .k[data-wheel]").forEach((b) => {
  b.addEventListener("click", () => {
    send({ t: "wl", v: parseInt(b.dataset.wheel, 10) * 3 });
  });
});
$("btn-left").addEventListener("click", () => clickButton("left"));
$("btn-right").addEventListener("click", () => clickButton("right"));

function clickButton(b) {
  send({ t: "mb", b, d: true });
  setTimeout(() => send({ t: "mb", b, d: false }), 40);
}

// ---------------- 触控板手势 ----------------
let sens = 2.5;
$("sens").addEventListener("input", (e) => {
  sens = parseFloat(e.target.value);
  $("sens-val").textContent = sens;
});

const touches = new Map();       // identifier -> {x,y,t0,moved}
let twoCentroid = null;
let wheelAcc = 0;
let pendingMove = { x: 0, y: 0 };
let mouseInFlight = 0;
const MOUSE_PIPE = 8;            // 允许 8 条移动消息在途(流式);超出的合并为最新位置

function flushMove() {
  if (mouseInFlight >= MOUSE_PIPE) return;   // 管道满:等下一轮,消息自动合并
  const m = pendingMove;
  if (!m.x && !m.y) return;
  pendingMove = { x: 0, y: 0 };
  mouseInFlight++;
  send({ t: "mv", x: m.x, y: m.y }).finally(() => {
    mouseInFlight--;
    if (pendingMove.x || pendingMove.y) flushMove();
  });
}
function queueMove(dx, dy) {
  pendingMove.x += dx;
  pendingMove.y += dy;
  flushMove();                   // 不等回复,保持流式;满管道时合并,不滚雪球
}

function centroidOf() {
  let x = 0, y = 0;
  touches.forEach((t) => { x += t.x; y += t.y; });
  const n = touches.size;
  return { x: x / n, y: y / n };
}

pad.addEventListener("touchstart", (e) => {
  e.preventDefault();
  for (const t of e.changedTouches) {
    touches.set(t.identifier, { x: t.clientX, y: t.clientY, t0: performance.now(), moved: 0 });
  }
  if (touches.size === 2) twoCentroid = centroidOf();
  else twoCentroid = null;
}, { passive: false });

let lastSingle = null;   // 单指模式下上一次的坐标(用于增量)
pad.addEventListener("touchmove", (e) => {
  e.preventDefault();
  // 记录每根手指新位置与移动量
  for (const t of e.changedTouches) {
    const s = touches.get(t.identifier);
    if (s) {
      const dx = t.clientX - s.x, dy = t.clientY - s.y;
      s.x = t.clientX; s.y = t.clientY;
      s.moved += Math.abs(dx) + Math.abs(dy);
    }
  }
  if (touches.size === 1) {
    // 单指 -> 鼠标相对移动
    const s = touches.values().next().value;
    if (!lastSingle) lastSingle = { x: s.x, y: s.y };
    const dx = (s.x - lastSingle.x) * sens;
    const dy = (s.y - lastSingle.y) * sens;
    lastSingle = { x: s.x, y: s.y };
    queueMove(Math.round(dx), Math.round(dy));
  } else if (touches.size === 2 && twoCentroid) {
    // 双指纵向滑动 -> 滚轮
    const c = centroidOf();
    const dy = c.y - twoCentroid.y;
    twoCentroid = c;
    wheelAcc += dy;
    const step = Math.trunc(wheelAcc / 40);    // 每 40px 一档滚轮
    if (step !== 0) {
      wheelAcc -= step * 40;
      send({ t: "wl", v: step });
    }
  }
}, { passive: false });

pad.addEventListener("touchend", (e) => {
  e.preventDefault();
  const was = touches.size;
  const ended = [];
  for (const t of e.changedTouches) {
    const s = touches.get(t.identifier);
    if (s) {
      ended.push({ dt: performance.now() - s.t0, moved: s.moved });
      touches.delete(t.identifier);
    }
  }
  lastSingle = null;
  if (was === 1 && ended.length === 1) {
    const s = ended[0];
    if (s.dt < 250 && s.moved < 12) clickButton("left");   // 轻点=左键
  } else if (was === 2 && ended.length === 2) {
    const both = ended.every((s) => s.dt < 300 && s.moved < 12);
    if (both) clickButton("right");                         // 双指轻点=右键
  }
  if (touches.size === 2) twoCentroid = centroidOf();
  else if (touches.size < 2) { twoCentroid = null; wheelAcc = 0; }
}, { passive: false });

pad.addEventListener("touchcancel", (e) => {
  for (const t of e.changedTouches) touches.delete(t.identifier);
  lastSingle = null;
  twoCentroid = null;
});

// ---------------- 面板切换 ----------------
$("tab-key").addEventListener("click", () => switchMode("key"));
$("tab-mouse").addEventListener("click", () => switchMode("mouse"));

function switchMode(m) {
  mode = m;
  $("tab-key").className = "tab" + (m === "key" ? " active" : "");
  $("tab-mouse").className = "tab" + (m === "mouse" ? " active" : "");
  $("panel-key").classList.toggle("hidden", m !== "key");
  $("panel-mouse").classList.toggle("hidden", m !== "mouse");
  if (m === "key") setTimeout(() => mirror.focus({ preventScroll: true }), 50);
}

// ---------------- 小工具 ----------------
function flash(msg) {
  const el = $("status-text");
  const old = el.textContent;
  el.textContent = msg;
  setTimeout(() => { if (el.textContent === msg) el.textContent = old; }, 1500);
}

// 进入页面自动聚焦键盘
switchMode("key");

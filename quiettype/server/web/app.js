/* QuietType 手机端 v7 —— 竖屏紧凑 / 横屏 68 键 / 外观可换 / 一键两用配套 */
"use strict";

const $ = (id) => document.getElementById(id);

// ===================== 状态 =====================
let TOKEN = "";
let connected = false;
let relayMode = false;
let pasteMode = false;
let lang = "en";                     // en | zh(竖屏 26 键 / 手机输入法)
let lockMode = "hold";               // hold | once
let orientMode = "auto";             // auto | portrait | landscape
let mirrorValue = "";                // 中文输入框上次已发送内容(镜像)
let composing = false;

const S = {                          // 可调参数(设置页)
  scroll: 2.5, move: 2.5, hold: 600, vib: 10,
};
const LSKEY = "qtUI_v7";
const PRESETS = [
  { name: "默认深色", bg: "#0f1115", card: "#1f2430" },
  { name: "纯黑", bg: "#000000", card: "#1b1b1b" },
  { name: "深蓝", bg: "#0b1220", card: "#182338" },
  { name: "暗绿", bg: "#0b1a12", card: "#16281c" },
  { name: "紫色", bg: "#150f22", card: "#241a35" },
  { name: "咖啡", bg: "#1a1310", card: "#2a201a" },
  { name: "灰蓝", bg: "#101820", card: "#1c2733" },
  { name: "米白(浅色)", bg: "#f2efe9", card: "#ffffff" },
];
let UI = { bgType: "color", bg: "#0f1115", card: "#1f2430", photo: "", blur: 0, dim: 0 };

// ===================== 键名表 =====================
const FKEYS = ["f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12"];
const SYMS = ["{", "}", "(", ")", "[", "]", "<", ">", "=", "+", "-", "*", "/", "\\", "|",
  ";", ":", "'", '"', ",", ".", "_", "~", "`", "!", "@", "#", "$", "%", "^", "&", "?"];
const QWERTY = [
  ["q", "w", "e", "r", "t", "y", "u", "i", "o", "p"],
  ["a", "s", "d", "f", "g", "h", "j", "k", "l"],
];
const CN = { ctrl: "Ctrl", alt: "Alt", shift: "Shift", win: "Win", tab: "Tab", esc: "Esc" };

// ===================== 网络 =====================
let lastNetWarn = 0;
async function send(msg) {
  if (relayMode) {
    msg.name = $("relay-agent").value || "";
    msg.pin = $("relay-pin").value || "";
  } else {
    msg.token = TOKEN;
  }
  try {
    const r = await fetch("/api/input", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(msg),
    });
    if (r.status === 403) { setStatus(false); say("口令/令牌失效:请刷新页面重新连接"); return false; }
    let data = null;
    try { data = await r.json(); } catch (_) {}
    if (data && data.ok === false) {                 // 服务端明确拒绝(如未知按键名)
      say("未执行:" + (data.info || data.error || "未知原因"));
      return false;
    }
    return r.ok;
  } catch (_) {
    setStatus(false);
    const now = Date.now();                          // 断线提示做节流,避免刷屏
    if (now - lastNetWarn > 3000) { lastNetWarn = now; say("与电脑断开,正在自动重连…"); }
    return false;
  }
}

async function fetchInfo() {
  try {
    const r = await fetch("/api/info");
    if (!r.ok) return false;
    const info = await r.json();
    TOKEN = info.token || "";
    relayMode = (info.name || "").toLowerCase().indexOf("relay") >= 0;
    $("relaybar").classList.toggle("hidden", !relayMode);
    if (relayMode) { loadAgents(false); }
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
setInterval(() => { if (!connected) fetchInfo(); }, 3000);

async function loadAgents(keep) {
  try {
    const r = await fetch("/api/agents");
    if (!r.ok) return;
    const data = await r.json();
    const sel = $("relay-agent");
    const prev = keep && sel.value ? sel.value : "";
    sel.innerHTML = "";
    (data.agents || []).forEach((a) => {
      const o = document.createElement("option");
      o.value = a.name; o.textContent = a.name; sel.appendChild(o);
    });
    if (prev && [...sel.options].some((o) => o.value === prev)) sel.value = prev;
  } catch (_) {}
}
$("relay-refresh").addEventListener("click", () => loadAgents(true));

// ===================== 发送封装(按键 / 文本) =====================
function haptic() {
  if (S.vib > 0 && navigator.vibrate) { try { navigator.vibrate(S.vib); } catch (_) {} }
}
function say(text, boxId) {
  const el = $(boxId || (isPortrait() ? "statusP" : "statusL"));
  if (el) el.textContent = text;
}
function modsToSend() { return Array.from(new Set([...held, ...oneShot])); }

function sendKey(name, extraMods) {
  const mods = [...modsToSend(), ...(extraMods || [])];
  say("发送:" + (mods.length ? mods.map((m) => CN[m] || m).join("+") + "+" : "") + name);
  send({ t: "key", k: name, mods: mods });
  if (lockMode === "once") { oneShot.clear(); }
  paintSticky();
}
function sendText(ch) {                    // 英文/符号:无修饰键时按"文本"发送(绕过电脑输入法)
  send(pasteMode ? { t: "paste", s: ch } : { t: "txt", s: ch });
  say("输入:" + ch);
  if (lockMode === "once") { oneShot.clear(); }
  paintSticky();
}
function sendChar(ch, keyName) {           // 有修饰键 -> 走真实按键,否则走文本
  if (modsToSend().length) sendKey(keyName || ch);
  else sendText(ch);
}
function sendCombo(spec, boxId) {          // "Ctrl+Shift+T" -> key
  const parts = spec.split("+").map((s) => s.trim()).filter(Boolean);
  const mods = [];
  let key = "";
  parts.forEach((p) => {
    const low = p.toLowerCase();
    if (["ctrl", "alt", "shift", "win"].indexOf(low) >= 0) mods.push(low);
    else key = low.length === 1 ? low : low;
  });
  if (!key) return;
  say("发送组合键:" + spec, boxId);
  send({ t: "key", k: key, mods: mods });
}

// ===================== 粘滞锁定键 =====================
const held = new Set(), oneShot = new Set(), lastTap = {};
function paintSticky() {
  document.querySelectorAll("[data-sticky]").forEach((b) => {
    const k = b.dataset.sticky;
    b.classList.toggle("on", oneShot.has(k));
    b.classList.toggle("held", held.has(k));
  });
  const txt = lockMode === "hold"
    ? (held.size ? "已按住:" + [...held].map((k) => CN[k] || k).join(" + ") + " → 按任意键即组合键;再点该键松开"
                 : "点快捷键区的 Ctrl/Alt/Tab/Win 锁定后,再按键即可成组合键")
    : (oneShot.size ? "待生效:" + [...oneShot].map((k) => CN[k] || k).join(" + ") + "(只对下一个按键生效)"
                    : "只生效一次模式:点锁定键后,只对下一个按键生效");
  say(txt);
  paintFnLayer();
}
function bindSticky(b) {
  b.addEventListener("click", () => {
    haptic();
    const k = b.dataset.sticky, now = Date.now();
    if (k === "fn") {                       // Fn:一次性图层开关
      if (held.has("fn") || oneShot.has("fn")) { held.delete("fn"); oneShot.delete("fn"); }
      else { held.add("fn"); }
      paintSticky(); return;
    }
    if (lockMode === "hold") { if (held.has(k)) held.delete(k); else held.add(k); }
    else {
      if (oneShot.has(k)) oneShot.delete(k);
      else if (!(lastTap[k] && now - lastTap[k] < 400)) oneShot.add(k);
      lastTap[k] = now;
    }
    paintSticky();
  });
}

// ===================== 竖屏:键群(错位排列,单个键更宽) =====================
function mk(text, cls, flex, handler) {
  const b = document.createElement("button");
  b.className = cls; b.textContent = text;
  if (flex) b.style.flex = String(flex);
  b.addEventListener("click", handler);
  return b;
}
function mkSpacer(flex) {
  const d = document.createElement("div");
  d.className = "spacer"; d.style.flex = String(flex);
  return d;
}
function buildRows() {
  const sym = $("symRow");
  SYMS.forEach((c) => {
    const b = mk(c, "k sym", null, () => { haptic(); sendText(c); });
    b.style.minWidth = "50px";
    sym.appendChild(b);
  });

  const fr = $("fRow");
  FKEYS.forEach((k) => fr.appendChild(mk(k.toUpperCase(), "k mini2", null, () => { haptic(); sendKey(k); })));

  const nr = $("numRow");
  "1234567890".split("").forEach((d) => nr.appendChild(mk(d, "k mini2", null, () => { haptic(); sendChar(d, d); })));

  // 手机式错位:QWERTY 10 / ASDF 9(两侧半格) / ⇧ZXCV 7 + ⌫
  QWERTY[0].forEach((ch) => $("q1").appendChild(mk(ch.toUpperCase(), "k", null, () => { haptic(); sendChar(ch, ch); })));
  $("q2").appendChild(mkSpacer(0.5));
  QWERTY[1].forEach((ch) => $("q2").appendChild(mk(ch.toUpperCase(), "k", null, () => { haptic(); sendChar(ch, ch); })));
  $("q2").appendChild(mkSpacer(0.5));
  $("q3").appendChild(mk("⇧", "k mod", 1.2, null));
  $("q3").querySelector(".k.mod").dataset.sticky = "shift";
  "zxcvbnm".split("").forEach((ch) => $("q3").appendChild(mk(ch.toUpperCase(), "k", null, () => { haptic(); sendChar(ch, ch); })));
  $("q3").appendChild(mk("⌫", "k mod", 1.2, () => { haptic(); sendKey("backspace"); }));

  // 底部 Ctrl 与顶部锁定键同行为
  document.querySelectorAll('[data-sticky]').forEach(bindSticky);
  const ctrlBottom = document.querySelector("#q4 .k.mod");
  if (ctrlBottom) { ctrlBottom.dataset.sticky = "ctrl"; bindSticky(ctrlBottom); }
  document.querySelectorAll("#q4 [data-key]").forEach((b) => {
    b.addEventListener("click", () => { haptic(); sendKey(b.dataset.key); });
  });
}

// ===================== 中文输入(竖屏/横屏共用) =====================
let zhBarOn = false;
function showZhBar(on) {
  zhBarOn = on;
  $("zhBar").classList.toggle("hidden", !on);
  $("btn-zh").classList.toggle("on", on);
  $("btnZH").classList.toggle("on", on);
  $("btnEN").classList.toggle("on", !on);
  // 竖屏时中文态收起 26 键(横屏保留键盘,中文框显示在顶栏下方)
  const hideKeys = on && isPortrait();
  ["q1", "q2", "q3"].forEach((id) => $(id).classList.toggle("hidden", hideKeys));
  say(on ? "中文输入已开启:用手机输入法打字,内容实时进电脑" : "已切回键盘");
  if (on) setTimeout(() => $("zhInput").focus(), 60);
  saveAll();
}
$("btnEN").addEventListener("click", () => showZhBar(false));
$("btnZH").addEventListener("click", () => showZhBar(true));
$("btn-zh").addEventListener("click", () => showZhBar(!zhBarOn));
$("btnLandZH").addEventListener("click", () => showZhBar(!zhBarOn));

// ===================== 中文镜像输入(diff 增量发送) =====================
function diff(a, b) {
  let p = 0;
  const maxP = Math.min(a.length, b.length);
  while (p < maxP && a.charCodeAt(p) === b.charCodeAt(p)) p++;
  let q = 0;
  const oTail = a.length - p, nTail = b.length - p, maxQ = Math.min(oTail, nTail);
  while (q < maxQ && a.charCodeAt(a.length - 1 - q) === b.charCodeAt(b.length - 1 - q)) q++;
  return { removed: oTail - q, inserted: b.slice(p, b.length - q) };
}
function commitMirror(cur) {
  if (cur === mirrorValue) return;
  const d = diff(mirrorValue, cur);
  mirrorValue = cur;
  if (d.removed > 0) send({ t: "bs", n: d.removed });
  if (d.inserted.length) send(pasteMode ? { t: "paste", s: d.inserted } : { t: "txt", s: d.inserted });
}
function bindMirror(input) {
  input.addEventListener("input", () => {
    if (composing) return;
    commitMirror(input.value);
  });
  input.addEventListener("compositionstart", () => { composing = true; });
  input.addEventListener("compositionend", () => {
    composing = false;
    commitMirror(input.value);
  });
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") { e.preventDefault(); sendKey("enter"); }
  });
}
bindMirror($("zhInput"));

// ===================== 按键反馈(手机上看得到"点到了") =====================
function markPress(el) {
  if (!el) return;
  el.classList.add("pressed");
  clearTimeout(el._pt);
  el._pt = setTimeout(() => el.classList.remove("pressed"), 150);
}
document.addEventListener("touchstart", (e) => {
  const el = e.target.closest(".k, .hk, .mini, .slot, .ball, .strip");
  markPress(el);
}, { passive: true });
document.addEventListener("mousedown", (e) => {
  markPress(e.target.closest(".k, .hk, .mini, .slot, .ball, .strip"));
});
// 让 iOS Safari 支持 :active 反馈
document.addEventListener("touchstart", () => {}, { passive: true });

// ===================== 槽位(长按编辑) =====================
let SLOTS = ["Ctrl+C", "Ctrl+V", "Ctrl+S", "Ctrl+Z", "Ctrl+Shift+T", "Alt+F4"];
function renderSlots() {
  document.querySelectorAll("[data-slot]").forEach((b) => { b.textContent = SLOTS[+b.dataset.slot]; });
}
document.querySelectorAll("[data-slot]").forEach((b) => {
  let timer = null, long = false;
  const start = () => {
    long = false;
    timer = setTimeout(() => {
      long = true;
      const cur = SLOTS[+b.dataset.slot];
      const v = prompt("编辑槽位(用 + 连接,例如 Ctrl+Shift+T、Alt+F4):", cur);
      if (v !== null) { SLOTS[+b.dataset.slot] = v.trim() || cur; renderSlots(); saveAll(); }
    }, S.hold);
  };
  const cancel = () => clearTimeout(timer);
  b.addEventListener("touchstart", start, { passive: true });
  b.addEventListener("touchend", cancel);
  b.addEventListener("mousedown", start);
  b.addEventListener("mouseup", cancel);
  b.addEventListener("mouseleave", cancel);
  b.addEventListener("click", (e) => {
    if (long) { e.preventDefault(); long = false; return; }
    haptic(); sendCombo(SLOTS[+b.dataset.slot]);
  });
});
renderSlots();

// ===================== 触摸板 =====================
let pending = { x: 0, y: 0 }, inflight = 0;
let PIPE = 8;                       // 在途上限:延迟大时自动调小,避免越用越卡
let rttSamples = [];
function adaptPipe(ms) {
  rttSamples.push(ms);
  if (rttSamples.length < 20) return;
  const avg = rttSamples.reduce((a, b) => a + b, 0) / rttSamples.length;
  const next = avg > 300 ? 2 : (avg > 120 ? 4 : 8);
  if (next !== PIPE) say("鼠标跟手度自动调整(延迟约 " + Math.round(avg) + "ms)");
  PIPE = next;
  rttSamples = [];
}
function flushMove() {
  if (inflight >= PIPE) return;
  const m = pending;
  if (!m.x && !m.y) return;
  pending = { x: 0, y: 0 };
  inflight++;
  const t0 = performance.now();
  send({ t: "mv", x: m.x, y: m.y }).finally(() => {
    inflight--;
    adaptPipe(performance.now() - t0);
    if (pending.x || pending.y) flushMove();
  });
}
function queueMove(dx, dy) { pending.x += dx; pending.y += dy; flushMove(); }
function clickBtn(b) {
  send({ t: "mb", b: b, d: true });
  setTimeout(() => send({ t: "mb", b: b, d: false }), 40);
}
function setupPad(padId, toastId, stripId) {
  const pad = $(padId);
  if (!pad) return;
  let dragging = false, lx = 0, ly = 0, st = 0, moved = 0, holdTimer = null, holdFired = false;
  const start = (x, y) => {
    dragging = true; lx = x; ly = y; st = Date.now(); moved = 0; holdFired = false;
    holdTimer = setTimeout(() => { holdFired = true; say("长按 → 右键", toastId); clickBtn("right"); }, S.hold);
  };
  const move = (x, y) => {
    if (!dragging) return;
    const dx = x - lx, dy = y - ly; lx = x; ly = y;
    moved += Math.abs(dx) + Math.abs(dy);
    if (moved > 12) clearTimeout(holdTimer);
    queueMove(Math.round(dx * S.move), Math.round(dy * S.move));
  };
  const end = () => {
    if (!dragging) return;
    dragging = false; clearTimeout(holdTimer);
    if (!holdFired && Date.now() - st < 250 && moved < 12) { say("轻点 → 左键", toastId); clickBtn("left"); }
  };
  // 双指滚动:真正发送滚轮消息(之前只显示提示,不发)
  let twoLastY = null;
  const sendWheel = (dy) => {
    const steps = Math.round(-dy * S.scroll / 30);
    if (steps !== 0) send({ t: "wl", v: Math.max(-20, Math.min(20, steps)) });
  };
  pad.addEventListener("touchstart", (e) => {
    if (e.touches.length === 2) {
      twoLastY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
      say("双指滑动 → 滚动", toastId);
      return;
    }
    const t = e.changedTouches[0], r = pad.getBoundingClientRect();
    start(t.clientX - r.left, t.clientY - r.top);
  }, { passive: true });
  pad.addEventListener("touchmove", (e) => {
    const r = pad.getBoundingClientRect();
    if (e.touches.length === 2) {
      e.preventDefault();
      const cy = (e.touches[0].clientY + e.touches[1].clientY) / 2;
      if (twoLastY !== null) { sendWheel(cy - twoLastY); twoLastY = cy; }
      return;
    }
    const t = e.changedTouches[0];
    move(t.clientX - r.left, t.clientY - r.top);
  }, { passive: false });
  pad.addEventListener("touchend", (e) => {
    if (!e.touches || e.touches.length === 0) twoLastY = null;
    end();
  });
  pad.addEventListener("mousedown", (e) => { const r = pad.getBoundingClientRect(); start(e.clientX - r.left, e.clientY - r.top); });
  pad.addEventListener("mousemove", (e) => { const r = pad.getBoundingClientRect(); move(e.clientX - r.left, e.clientY - r.top); });
  window.addEventListener("mouseup", end);

  if (stripId) {
    const strip = $(stripId);
    let sLast = null;
    const push = (dy) => { send({ t: "wl", v: Math.round(-dy * S.scroll / 30) }); say("右缘滚动", toastId); };
    strip.addEventListener("touchstart", (e) => { e.stopPropagation(); sLast = e.changedTouches[0].clientY; }, { passive: true });
    strip.addEventListener("touchmove", (e) => {
      e.preventDefault(); e.stopPropagation();
      const y = e.changedTouches[0].clientY, dy = y - sLast; sLast = y; push(dy);
    }, { passive: false });
    strip.addEventListener("touchend", () => { sLast = null; });
    strip.addEventListener("mousedown", (e) => { e.stopPropagation(); sLast = e.clientY; });
    strip.addEventListener("mousemove", (e) => { if (sLast !== null) { e.stopPropagation(); const dy = e.clientY - sLast; sLast = e.clientY; push(dy); } });
    window.addEventListener("mouseup", () => { sLast = null; });
  }
}
setupPad("padP", "toastP", "stripP");
setupPad("padL", "toastL", null);

// ===================== 横屏 68 键 =====================
// 横屏 68 键:[显示, 宽度, 类型];类型=真实键名 或 t(字母/符号文本) / caps / mod / fn
// 双标键:fnX = 上排 F 键;bksp = ⌫/Del 双标
const L68 = [
  [["Esc", "1.2", "esc"], ["1", "1", "fn1"], ["2", "1", "fn2"], ["3", "1", "fn3"], ["4", "1", "fn4"],
   ["5", "1", "fn5"], ["6", "1", "fn6"], ["7", "1", "fn7"], ["8", "1", "fn8"], ["9", "1", "fn9"],
   ["0", "1", "fn10"], ["-", "1", "fn11"], ["=", "1", "fn12"], ["⌫", "1.7", "bksp"]],
  [["Tab", "1.6", "tab"], ["Q", "1", "t"], ["W", "1", "t"], ["E", "1", "t"], ["R", "1", "t"],
   ["T", "1", "t"], ["Y", "1", "t"], ["U", "1", "t"], ["I", "1", "t"], ["O", "1", "t"], ["P", "1", "t"],
   ["[", "1", "t"], ["]", "1", "t"], ["\\", "1.4", "t"]],
  [["Caps", "1.9", "caps"], ["A", "1", "t"], ["S", "1", "t"], ["D", "1", "t"], ["F", "1", "t"],
   ["G", "1", "t"], ["H", "1", "t"], ["J", "1", "t"], ["K", "1", "t"], ["L", "1", "t"],
   [";", "1", "t"], ["'", "1", "t"], ["⏎", "2.1", "enter"]],
  [["⇧", "2.3", "shift"], ["Z", "1", "t"], ["X", "1", "t"], ["C", "1", "t"], ["V", "1", "t"],
   ["B", "1", "t"], ["N", "1", "t"], ["M", "1", "t"], [",", "1", "t"], [".", "1", "t"], ["/", "1", "t"],
   ["⇧", "1.9", "shift"], ["↑", "1", "up"]],
  [["Ctrl", "1.5", "ctrl"], ["Win", "1.2", "win"], ["Alt", "1.2", "alt"], ["空格", "4.4", "space"],
   ["Alt", "1.2", "alt"], ["Fn", "1.2", "fn"], ["Ctrl", "1.5", "ctrl"],
   ["←", "1", "left"], ["↓", "1", "down"], ["→", "1", "right"]],
];
function paintFnLayer() {
  const on = held.has("fn") || oneShot.has("fn");
  document.querySelectorAll("#kbdLand .dual").forEach((b) => b.classList.toggle("fnlit", on));
}
function renderLand() {
  const box = $("kbdLand");
  box.innerHTML = "";
  L68.forEach((row) => {
    const r = document.createElement("div"); r.className = "krow";
    row.forEach(([label, w, kind]) => {
      const b = document.createElement("button");
      let cls = "hk";
      if (kind === "nav" || ["left", "right", "up", "down"].indexOf(kind) >= 0) cls += " nav";
      if (["ctrl", "alt", "shift", "win", "fn"].indexOf(kind) >= 0) cls += " mod";
      if (label.length > 3) cls += " small";
      b.className = cls;
      if (w) b.style.flexGrow = w;

      if (/^fn\d+$/.test(kind)) {                     // 数字键双标:上 F 键、下数字
        const n = kind.slice(2);
        b.classList.add("dual");
        b.dataset.fn = "f" + n;                       // Fn 按下时发送的键
        b.dataset.off = label;                        // 平时发送的字符
        b.dataset.offType = "text";
        b.innerHTML = '<span class="fl">F' + n + '</span><span class="mn">' + label + "</span>";
      } else if (kind === "bksp") {                    // ⌫ / Del 双标
        b.classList.add("dual");
        b.dataset.fn = "delete";                       // Fn 按下 = Del 键
        b.dataset.off = "backspace";                   // 平时 = 退格键(不是发字符!)
        b.dataset.offType = "key";
        b.innerHTML = '<span class="fl">Del</span><span class="mn">⌫</span>';
      } else {
        b.textContent = label;
      }
      if (["ctrl", "alt", "shift", "win", "fn"].indexOf(kind) >= 0) {
        b.dataset.sticky = (kind === "fn" ? "fn" : kind);
        bindSticky(b);
      } else {
        b.addEventListener("click", () => {
          haptic();
          const fnOn = held.has("fn") || oneShot.has("fn");
          if (b.dataset.fn) {                          // 双标键
            if (fnOn) { sendKey(b.dataset.fn); }
            else if (b.dataset.offType === "key") { sendKey(b.dataset.off); }
            else { sendChar(b.dataset.off, b.dataset.off); }
            return;
          }
          if (kind === "caps") { sendKey("capital"); return; }
          if (kind === "t") {                          // 字母/符号:无修饰键时走文本,更稳
            sendChar(label.toLowerCase(), label.toLowerCase());
            return;
          }
          sendKey(kind);                               // esc / tab / enter / space / 方向键…
        });
      }
      r.appendChild(b);
    });
    box.appendChild(r);
  });
  paintSticky();
}
renderLand();

// ===================== 悬浮球(横屏触摸板) =====================
const ball = $("ball"), drawer = $("padDrawer"), landBox = $("layout-landscape");
let ballDrag = null;
const clampBall = (x, y) => {
  const w = landBox.clientWidth, h = landBox.clientHeight;
  ball.style.left = Math.max(4, Math.min(w - 52, x)) + "px";
  ball.style.top = Math.max(4, Math.min(h - 52, y)) + "px";
};
const ballStart = (cx, cy) => {
  const r = landBox.getBoundingClientRect();
  ballDrag = { ox: cx - r.left - ball.offsetLeft, oy: cy - r.top - ball.offsetTop, moved: 0, r: r };
};
const ballMove = (cx, cy) => {
  if (!ballDrag) return;
  ballDrag.moved++;
  clampBall(cx - ballDrag.r.left - ballDrag.ox, cy - ballDrag.r.top - ballDrag.oy);
};
const ballEnd = () => {
  if (ballDrag && ballDrag.moved < 3) {
    drawer.classList.toggle("hidden");
    ball.classList.toggle("open", !drawer.classList.contains("hidden"));
  }
  ballDrag = null;
};
ball.addEventListener("touchstart", (e) => { e.preventDefault(); ballStart(e.changedTouches[0].clientX, e.changedTouches[0].clientY); }, { passive: false });
ball.addEventListener("touchmove", (e) => { e.preventDefault(); ballMove(e.changedTouches[0].clientX, e.changedTouches[0].clientY); }, { passive: false });
ball.addEventListener("touchend", ballEnd);
ball.addEventListener("mousedown", (e) => { e.preventDefault(); ballStart(e.clientX, e.clientY); });
window.addEventListener("mousemove", (e) => { if (ballDrag) ballMove(e.clientX, e.clientY); });
window.addEventListener("mouseup", () => { if (ballDrag) ballEnd(); });
$("padClose").addEventListener("click", () => { drawer.classList.add("hidden"); ball.classList.remove("open"); });

// ===================== 横竖屏 =====================
function isPortrait() { return !$("layout-portrait").classList.contains("hidden"); }
function applyOrientation() {
  let p;
  if (orientMode === "portrait") p = true;
  else if (orientMode === "landscape") p = false;
  else p = window.matchMedia("(orientation: portrait)").matches;
  $("layout-portrait").classList.toggle("hidden", !p);
  $("layout-landscape").classList.toggle("hidden", p);
  // 顶栏「自动 / 竖屏 / 横屏」三个按钮的高亮
  document.querySelectorAll("#segOrient [data-orient]").forEach((b) => {
    b.classList.toggle("on", b.dataset.orient === orientMode);
  });
  // 切换方向后,重新判断 26 键是否应收起(中文态在竖屏才收起)
  showZhBar(zhBarOn);
}
document.querySelectorAll("#segOrient [data-orient]").forEach((b) => {
  b.addEventListener("click", () => {
    orientMode = b.dataset.orient;
    applyOrientation(); saveAll();
  });
});
window.addEventListener("orientationchange", () => { if (orientMode === "auto") setTimeout(applyOrientation, 120); });
window.addEventListener("resize", () => { if (orientMode === "auto") applyOrientation(); });

// 出错时把原因显示在页面上(方便排查,不再"按键没反应也不知道为什么")
window.addEventListener("error", (e) => {
  try { say("脚本错误: " + (e.message || e.error || "未知")); } catch (_) {}
});
window.addEventListener("unhandledrejection", (e) => {
  try {
    const r = e.reason;
    say("操作失败: " + (r && r.message ? r.message : String(r)));
  } catch (_) {}
});

// ===================== 外观 + 设置 =====================
function hexToRgb(h) {
  h = (h || "#000").replace("#", "");
  if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
  const n = parseInt(h, 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}
function shade(h, d) {
  const c = hexToRgb(h), f = (v) => Math.max(0, Math.min(255, Math.round(v + d)));
  return "#" + [f(c.r), f(c.g), f(c.b)].map((v) => v.toString(16).padStart(2, "0")).join("");
}
function isLight(h) { const c = hexToRgb(h); return (c.r * 299 + c.g * 587 + c.b * 114) / 1000 > 150; }
function applyUI() {
  const root = document.documentElement.style;
  root.setProperty("--bg", UI.bg);
  root.setProperty("--panel", shade(UI.bg, isLight(UI.bg) ? -6 : 8));
  root.setProperty("--card", UI.card);
  root.setProperty("--line", shade(UI.card, isLight(UI.card) ? -30 : 16));
  root.setProperty("--txt", isLight(UI.card) ? "#12161d" : "#e8eaf0");
  root.setProperty("--bgdim", String(UI.dim / 100));
  const layer = $("bgLayer");
  if (UI.bgType === "photo" && UI.photo) {
    layer.style.backgroundImage = 'url("' + UI.photo + '")';
    layer.style.filter = "blur(" + UI.blur + "px)";
    layer.style.transform = "scale(1.08)";
  } else {
    layer.style.backgroundImage = "none";
    layer.style.filter = "none";
    layer.style.transform = "none";
  }
}
function saveAll() {
  try {
    localStorage.setItem(LSKEY, JSON.stringify({ UI: UI, S: S, lockMode: lockMode, orientMode: orientMode, slots: SLOTS, pasteMode: pasteMode }));
  } catch (_) { $("photoTip").textContent = "照片太大,本次生效但没能保存。"; }
}
function loadAll() {
  try {
    const d = JSON.parse(localStorage.getItem(LSKEY) || "null");
    if (!d) return;
    if (d.UI) UI = Object.assign(UI, d.UI);
    if (d.S) S = Object.assign(S, d.S);
    if (d.lockMode) lockMode = d.lockMode;
    if (d.orientMode) orientMode = d.orientMode;
    if (d.slots) SLOTS = d.slots;
    if (typeof d.pasteMode === "boolean") pasteMode = d.pasteMode;
  } catch (_) {}
}
$("btn-paste").addEventListener("click", () => {
  pasteMode = !pasteMode;
  $("btn-paste").textContent = pasteMode ? "文本:粘贴" : "文本:逐字";
  $("btn-paste").classList.toggle("on", pasteMode);
  saveAll();
});
$("btn-sync").addEventListener("click", () => {
  $("zhInput").value = "";
  mirrorValue = "";
  say("已镜像同步:从电脑当前光标处继续输入");
});
// 离开页面再回来时自动同步镜像,避免"电脑内容变了但手机不知道"导致打错位置
let wasHidden = false;
document.addEventListener("visibilitychange", () => {
  if (document.hidden) { wasHidden = true; return; }
  if (wasHidden) {
    wasHidden = false;
    $("zhInput").value = "";
    mirrorValue = "";
    say("已自动镜像同步(切回页面)");
  }
});
$("btn-gear").addEventListener("click", () => $("settings").classList.remove("hidden"));
$("btn-close-set").addEventListener("click", () => $("settings").classList.add("hidden"));

// 设置项绑定
function bindRange(id, out, key, after) {
  const el = $(id);
  el.addEventListener("input", () => {
    S[key] = parseFloat(el.value);
    $(out).textContent = el.value;
    if (after) after();
    saveAll();
  });
}
bindRange("scrollR", "scrollV", "scroll");
bindRange("moveR", "moveV", "move");
bindRange("holdR", "holdV", "hold");
bindRange("vibR", "vibV", "vib");
document.querySelectorAll("input[name=lockmode]").forEach((r) => {
  r.addEventListener("change", () => { lockMode = r.value; held.clear(); oneShot.clear(); paintSticky(); saveAll(); });
});
// 外观
const pbox = $("presets");
PRESETS.forEach((p) => {
  const b = document.createElement("button");
  b.className = "mini"; b.textContent = p.name;
  b.style.cssText += ";background:" + p.card + ";border-color:" + shade(p.card, 16) +
    ";color:" + (isLight(p.card) ? "#12161d" : "#e8eaf0");
  b.addEventListener("click", () => { UI.bgType = "color"; UI.bg = p.bg; UI.card = p.card; applyUI(); syncUIInputs(); saveAll(); });
  pbox.appendChild(b);
});
$("bgColor").addEventListener("input", (e) => { UI.bg = e.target.value; applyUI(); saveAll(); });
$("cardColor").addEventListener("input", (e) => { UI.card = e.target.value; applyUI(); saveAll(); });
$("blurR").addEventListener("input", (e) => { UI.blur = +e.target.value; $("blurV").textContent = e.target.value + "px"; applyUI(); saveAll(); });
$("dimR").addEventListener("input", (e) => { UI.dim = +e.target.value; $("dimV").textContent = e.target.value + "%"; applyUI(); saveAll(); });
document.querySelectorAll("input[name=bgtype]").forEach((r) => {
  r.addEventListener("change", () => { UI.bgType = r.value; applyUI(); syncUIInputs(); saveAll(); });
});
$("bgFile").addEventListener("change", (e) => {
  const f = e.target.files && e.target.files[0];
  if (!f) return;
  const rd = new FileReader();
  rd.onload = () => { UI.photo = rd.result; UI.bgType = "photo"; applyUI(); syncUIInputs(); saveAll(); };
  rd.readAsDataURL(f);
});
$("resetUI").addEventListener("click", () => {
  UI = { bgType: "color", bg: "#0f1115", card: "#1f2430", photo: "", blur: 0, dim: 0 };
  applyUI(); syncUIInputs(); saveAll();
});
function syncUIInputs() {
  $("bgColor").value = UI.bg;
  $("cardColor").value = UI.card;
  $("blurR").value = UI.blur; $("blurV").textContent = UI.blur + "px";
  $("dimR").value = UI.dim; $("dimV").textContent = UI.dim + "%";
  $("scrollR").value = S.scroll; $("scrollV").textContent = S.scroll;
  $("moveR").value = S.move; $("moveV").textContent = S.move;
  $("holdR").value = S.hold; $("holdV").textContent = S.hold;
  $("vibR").value = S.vib; $("vibV").textContent = S.vib;
  document.querySelectorAll("input[name=bgtype]").forEach((r) => { r.checked = (r.value === UI.bgType); });
  document.querySelectorAll("input[name=lockmode]").forEach((r) => { r.checked = (r.value === lockMode); });
  $("bgColorBox").classList.toggle("hidden", UI.bgType !== "color");
  $("bgPhotoBox").classList.toggle("hidden", UI.bgType !== "photo");
  $("photoTip").textContent = UI.photo ? "已选择照片(可重选或调滑块)" : "还没有选择照片。";
  $("btn-paste").textContent = pasteMode ? "文本:粘贴" : "文本:逐字";
  $("btn-paste").classList.toggle("on", pasteMode);
  renderSlots();
}

// ===================== 启动 =====================
loadAll();
buildRows();
renderSlots();
applyUI();
syncUIInputs();
applyOrientation();
paintSticky();
fetchInfo();

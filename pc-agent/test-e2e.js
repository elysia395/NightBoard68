// NightBoard68 PC Agent 端到端测试客户端
// 模拟手机端协议：UDP 发现 → TCP 连接 → hello → 心跳 → 按键 → 鼠标 → 释放
const dgram = require('dgram');
const net = require('net');

const HID = { N:0x11, B:0x05, '6':0x23, '8':0x25, O:0x12, K:0x0E, SPACE:0x2C, L:0x0F, A:0x04, N2:0x11 };

function udpDiscover() {
  return new Promise((resolve, reject) => {
    const s = dgram.createSocket('udp4');
    const timer = setTimeout(() => { s.close(); reject(new Error('discovery timeout')); }, 2500);
    s.on('message', (msg) => {
      const text = msg.toString();
      if (text.includes('"t":"disc"')) {
        clearTimeout(timer);
        s.close();
        resolve(JSON.parse(text));
      }
    });
    s.bind(() => {
      s.broadcast = true;
      // 本机回环不会收到广播包，测试时直接单播 127.0.0.1；
      // 真机上手机发 255.255.255.255 广播，跨设备无此限制
      s.send(Buffer.from('NB68_DISCOVER_V1'), 6869, '127.0.0.1');
    });
  });
}

function wait(ms) { return new Promise(r => setTimeout(r, ms)); }

(async () => {
  // 1. UDP 发现
  const disc = await udpDiscover();
  console.log('[1] UDP 发现成功:', JSON.stringify(disc));

  // 2. TCP 连接
  const port = disc.p || 6868;
  await new Promise((resolve, reject) => {
    client = net.connect(port, '127.0.0.1', resolve);
    client.on('error', reject);
  });
  console.log('[2] TCP 已连接 127.0.0.1:' + port);

  let pongResolve = null;
  client.on('data', (buf) => {
    for (const line of buf.toString().split('\n')) {
      if (!line.trim()) continue;
      const o = JSON.parse(line);
      if (o.t === 'po' && pongResolve) { pongResolve(Date.now()); pongResolve = null; }
      if (o.t === 'led') console.log('    <- led 状态:', o.c);
    }
  });

  const send = (o) => client.write(JSON.stringify(o) + '\n');

  // 3. hello + 心跳测延迟
  send({ t: 'hello', v: 1, n: 'E2E-Test-Phone' });
  await wait(100);
  const t0 = Date.now();
  send({ t: 'p', i: 1 });
  const pongAt = await new Promise(r => { pongResolve = r; setTimeout(() => r(Date.now()), 2000); });
  console.log('[3] 心跳往返:', (pongAt - t0) + 'ms');

  // 4. 键盘输入 "nb68 ok"（kd/ku 序列）
  const seq = ['N','B','6','8','SPACE','O','K'];
  const keyMap = { N:HID.N, B:HID.B, '6':HID['6'], '8':HID['8'], SPACE:HID.SPACE, O:HID.O, K:HID.K };
  for (const k of seq) {
    send({ t: 'kd', c: keyMap[k] });
    await wait(25);
    send({ t: 'ku', c: keyMap[k] });
    await wait(45);
  }
  console.log('[4] 已发送按键序列 nb68 ok');

  // 5. 鼠标：移动 + 左键单击
  for (let i = 0; i < 10; i++) { send({ t: 'm', dx: 5, dy: 0, w: 0, b: 0 }); await wait(12); }
  send({ t: 'm', dx: 0, dy: 0, w: 0, b: 1 });
  await wait(40);
  send({ t: 'm', dx: 0, dy: 0, w: 0, b: 0 });
  console.log('[5] 已发送鼠标移动+左键单击');

  // 6. 释放全部 + 收尾
  await wait(100);
  send({ t: 'ra' });
  await wait(100);
  client.end();
  console.log('[6] 测试完成，连接已关闭');
  process.exit(0);
})().catch(e => { console.error('测试失败:', e.message); process.exit(1); });

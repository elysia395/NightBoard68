// 鼠标路径专项测试：移动 + 点击 + 滚轮
const net = require('net');
const wait = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
  await new Promise((res, rej) => {
    client = net.connect(6868, '127.0.0.1', res);
    client.on('error', rej);
  });
  console.log('TCP 已连接');
  client.on('data', () => {});
  const send = (o) => client.write(JSON.stringify(o) + '\n');
  send({ t: 'hello', v: 1, n: 'mouse-test' });
  await wait(150);
  // 20 次小步移动（模拟真实触摸板慢速拖动）
  for (let i = 0; i < 20; i++) { send({ t: 'm', dx: 15, dy: 15, w: 0, b: 0 }); await wait(15); }
  console.log('已发送 20 次 dx=15 dy=15 移动（光标应右下移动 300,300）');
  await wait(300);
  // 慢速小步（每次 dx=1，模拟极慢拖动——考验小步是否丢失）
  for (let i = 0; i < 10; i++) { send({ t: 'm', dx: 1, dy: 0, w: 0, b: 0 }); await wait(15); }
  console.log('已发送 10 次 dx=1 慢速移动（光标应再右移 10px）');
  await wait(300);
  send({ t: 'm', dx: 0, dy: 0, w: 1, b: 0 });
  await wait(100);
  console.log('已发送滚轮 +1');
  client.end();
  process.exit(0);
})().catch(e => { console.error('失败:', e.message); process.exit(1); });

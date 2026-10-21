// 用 Chrome DevTools Protocol 给页面截图。
//
// 为什么需要它：没有浏览器插件时，页面的最终呈现（本地化的 CSS 到底有没有生效、
// Material Symbols 字体子集里到底有没有那个图标名）只靠 `curl` 看 HTML 是验不出来的。
// 类名在 HTML 里、样式在 CSS 里、字形在 woff2 里，三者都「正确」而屏幕上仍可能是错的。
//
// 用法：
//   1. 先起一个有远程调试端口的 Chrome：
//      Start-Process chrome.exe -ArgumentList '--headless=new','--remote-debugging-port=9222',
//        "--user-data-dir=$env:TEMP\nest-chrome",'--no-first-run','--disable-gpu','about:blank'
//   2. node tools/shot.mjs <配置.json>
//
// 配置：{ port?, cookie?:{name,value,url?}, shots:[{url,out,width?,height?,waitMs?,
//        click?,afterClickMs?,script?,afterScriptMs?}] }
import { writeFileSync, readFileSync } from 'node:fs';

const cfg = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const PORT = cfg.port || 9222;
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function findPage() {
  for (let i = 0; i < 60; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const p = list.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
      if (p) return p;
    } catch { /* 端口还没起来 */ }
    await sleep(500);
  }
  throw new Error('找不到可调试的页面 —— Chrome 没起来或端口不是 ' + PORT);
}

const page = await findPage();
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('WebSocket 连不上')); });

let seq = 0;
const pending = new Map();
const waiters = [];

ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    const p = pending.get(m.id);
    pending.delete(m.id);
    if (m.error) p.reject(new Error(JSON.stringify(m.error)));
    else p.resolve(m.result);
    return;
  }
  if (m.method) {
    for (let i = waiters.length - 1; i >= 0; i--) {
      if (waiters[i].name === m.method) waiters.splice(i, 1)[0].resolve(m.params);
    }
  }
};

const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++seq;
  pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});

const waitFor = (name, timeoutMs = 20000) => new Promise(resolve => {
  const w = { name, resolve };
  waiters.push(w);
  setTimeout(() => {
    const i = waiters.indexOf(w);
    if (i >= 0) { waiters.splice(i, 1); resolve(null); }
  }, timeoutMs);
});

await send('Page.enable');
await send('Network.enable');
await send('Runtime.enable');
// 关掉缓存：字体/样式改了以后，带着旧缓存截图会让人误判「改了没用」。
await send('Network.setCacheDisabled', { cacheDisabled: true });

if (cfg.cookie && cfg.cookie.value) {
  await send('Network.setCookie', {
    name: cfg.cookie.name, value: cfg.cookie.value,
    url: cfg.cookie.url || 'http://localhost:8080/', path: '/',
  });
  console.log('已注入 cookie', cfg.cookie.name);
}

for (const s of cfg.shots) {
  await send('Emulation.setDeviceMetricsOverride', {
    width: s.width || 1400, height: s.height || 1100, deviceScaleFactor: 1, mobile: !!s.mobile,
  });
  const loaded = waitFor('Page.loadEventFired', 25000);
  await send('Page.navigate', { url: s.url });
  const ok = await loaded;
  console.log(`${s.url} -> ${ok ? '载入完成' : '载入超时（继续截图）'}`);
  await sleep(s.waitMs || 1800);

  if (s.click) {
    const r = await send('Runtime.evaluate', {
      expression: `(() => { const el = document.querySelector(${JSON.stringify(s.click)}); if (!el) return 'NOT FOUND'; el.click(); return 'clicked'; })()`,
      returnByValue: true,
    });
    console.log('  click ' + s.click + ' -> ' + (r.result && r.result.value));
    await sleep(s.afterClickMs || 2200);
  }
  if (s.script) {
    const r = await send('Runtime.evaluate', { expression: s.script, returnByValue: true });
    console.log('  script -> ' + JSON.stringify(r.result && r.result.value));
    await sleep(s.afterScriptMs || 900);
  }

  const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  const buf = Buffer.from(shot.data, 'base64');
  writeFileSync(s.out, buf);
  console.log('  截图 -> ' + s.out + '  ' + buf.length + ' 字节');
}

ws.close();
console.log('全部完成');

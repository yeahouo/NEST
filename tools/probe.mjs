// 用 Chrome DevTools Protocol 在真实浏览器里跑一串断言并打印结果。
//
// 为什么不是截图：截图能证明「看起来对」，但证明不了「questionCount 真的渲染了 5」。
// 而且截图要我肉眼读，读错了也没有记录。这个脚本把断言的结果打成文本，能贴进验收记录。
//
// 与 shot.mjs 的区别：shot.mjs 一步一截图、每步重新导航；这里整个 steps 序列在**同一个页面**
// 里连续执行，而且支持 awaitPromise —— 所以可以 `await loadQuizzes()` 这种异步动作后再断言。
//
// 用法：node tools/probe.mjs <配置.json>
// 配置：{ port?, cookie?:{name,value,url?}, block?:[urlPattern...], offline?:bool,
//        page:{url,width?,height?,waitMs?},
//        steps:[ {name, eval, waitMs?} | {name, shot, file} | {name, block:[patterns]} | {name, offline:bool} ] }
//   block   = 按 URL 掐单条请求（测「后端接口挂了」这类分支）
//   offline = 真断网，请求仍经过 Service Worker（测「离线外壳」只能用这个）
//
// block 用 Network.setBlockedURLs 在**浏览器层**掐掉请求（例如 ["*/api/course/*"]）。
// 这比在页面里改写 fetch 更接近真事：断网/后端挂了就是这个形状，
// 而且页面自己的错误分支、重试按钮才真的会跑起来。
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
    const p = pending.get(m.id); pending.delete(m.id);
    if (m.error) p.reject(new Error(JSON.stringify(m.error))); else p.resolve(m.result);
    return;
  }
  if (m.method) for (let i = waiters.length - 1; i >= 0; i--) if (waiters[i].name === m.method) waiters.splice(i, 1)[0].resolve(m.params);
};
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++seq; pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});
const waitFor = (name, timeoutMs = 25000) => new Promise(resolve => {
  const w = { name, resolve }; waiters.push(w);
  setTimeout(() => { const i = waiters.indexOf(w); if (i >= 0) { waiters.splice(i, 1); resolve(null); } }, timeoutMs);
});

await send('Page.enable');
await send('Network.enable');
await send('Runtime.enable');
await send('Network.setCacheDisabled', { cacheDisabled: true });
// setCacheDisabled 只影响「下次要不要走网络」，浏览器里已经加载过的 css/js 仍可能直接复用内存副本
// —— 曾经因此量到「修好之后还是旧样式」，白查一轮。每轮清一次浏览器缓存，配置里 clearCache:false 可关。
if (cfg.clearCache !== false) await send('Network.clearBrowserCache');

if (cfg.cookie && cfg.cookie.value) {
  await send('Network.setCookie', { name: cfg.cookie.name, value: cfg.cookie.value, url: cfg.cookie.url || 'http://localhost:8080/', path: '/' });
  console.log('已注入 cookie ' + cfg.cookie.name);
}

// 先掐掉指定的请求，再导航：这样页面第一次加载就已经是「后端不可用」的状态。
if (cfg.block && cfg.block.length) {
  await send('Network.setBlockedURLs', { urls: cfg.block });
  console.log('已屏蔽 ' + cfg.block.join(', '));
}

// 媒体特性覆盖（可选）：例如 { media: { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] } }
// 必须在导航前设置，页面里的 matchMedia 才会读到被覆盖的值。
if (cfg.media) {
  await send('Emulation.setEmulatedMedia', { features: cfg.media.features || [] });
  console.log('已覆盖媒体特性 ' + JSON.stringify(cfg.media.features || []));
}

// 真正的断网：Network.emulateNetworkConditions 关掉整个网络栈，但请求**仍然照常经过
// Service Worker**（所以这才是检验离线外壳的正确方法）。
// 与上面 block 的区别：block 是「浏览器层按 URL 掐掉单条请求」，连 SW 里的 fetch 都被掐，
// 测出来是「SW 也没网」而不是「SW 有没有兜住」——用 block ["*"] 验离线会得到假阳性。
if (cfg.offline) {
  await send('Network.emulateNetworkConditions', { offline: true, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  console.log('已切到真断网（emulateNetworkConditions）');
}

// 页面里打印到 console 的错误也收上来：静态页最容易「看起来很对、控制台一片红」
const consoleErrors = [];
ws.addEventListener('message', ev => {
  const m = JSON.parse(ev.data);
  if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
    consoleErrors.push((m.params.args || []).map(a => a.value ?? a.description ?? a.type).join(' '));
  }
  if (m.method === 'Runtime.exceptionThrown') {
    consoleErrors.push('EXCEPTION: ' + (m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text));
  }
});

const p = cfg.page || {};
await send('Emulation.setDeviceMetricsOverride', { width: p.width || 1500, height: p.height || 1100, deviceScaleFactor: 1, mobile: false });
const loaded = waitFor('Page.loadEventFired');
await send('Page.navigate', { url: p.url });
const ok = await loaded;
console.log('载入 ' + p.url + ' -> ' + (ok ? '完成' : '超时'));
await sleep(p.waitMs || 2500);

for (const step of cfg.steps || []) {
  if (step.block) {
    await send('Network.setBlockedURLs', { urls: step.block });
    console.log(`[${step.name}] 屏蔽 -> ${step.block.length ? step.block.join(', ') : '(全部解除)'}`);
    continue;
  }
  if (typeof step.offline === 'boolean') {
    await send('Network.emulateNetworkConditions', { offline: step.offline, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    console.log(`[${step.name}] ${step.offline ? '切到真断网' : '恢复联网'}`);
    continue;
  }
  if (step.shot) {
    // fullPage 默认 true（整页图，历史行为）；步骤里写 fullPage:false 得到视口尺寸图（如 1500×1100）
    const fullPage = step.fullPage !== false;
    const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: fullPage });
    const buf = Buffer.from(shot.data, 'base64');
    writeFileSync(step.file || step.shot, buf);
    console.log(`[${step.name}] 截图 -> ${step.file || step.shot}  ${buf.length} 字节  ${fullPage ? '(整页)' : '(视口)'}`);
    continue;
  }
  try {
    const r = await send('Runtime.evaluate', { expression: step.eval, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
      console.log(`[${step.name}] 抛异常: ` + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    } else {
      console.log(`[${step.name}] ` + JSON.stringify(r.result && r.result.value));
    }
  } catch (e) {
    console.log(`[${step.name}] 调用失败: ` + e.message);
  }
  if (step.waitMs) await sleep(step.waitMs);
}

if (consoleErrors.length) {
  console.log('页面控制台错误 ' + consoleErrors.length + ' 条:');
  consoleErrors.slice(0, 12).forEach(e => console.log('  ! ' + e.split('\n')[0]));
} else {
  console.log('页面控制台无错误');
}

ws.close();
console.log('完成');

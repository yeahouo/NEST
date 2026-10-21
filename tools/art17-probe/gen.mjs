/*! 第十七轮「整站艺术化」验收用的探针配置生成器。
 *
 * 产物落在本目录（tools/art17-probe/）：
 *   index-light.json / index-dark.json / login-light.json / reduced.json / offline.json
 *   dashboard.json —— 里面 "value": "__SID__" 由 tools/check-art-layer.ps1 在运行时替换成
 *                     刚登录拿到的新 SID（同一账号再登录会让上一个会话失效，所以必须是新的）。
 *
 * 用法：node tools/art17-probe/gen.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const arg = (k, d) => { const i = argv.indexOf('--' + k); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const PORT = Number(arg('port', 9222));
const BASE = arg('base', 'http://127.0.0.1:8080');
const CB = arg('cb', 'al1');

/* 一条足够覆盖本轮全部断言的读数 */
const STATE = `(() => {
  const q = (s) => document.querySelector(s);
  const g = (e) => e ? getComputedStyle(e) : null;
  const h = q('h1') || q('h2');
  const b = q('.nx-btn') || q('.btn');
  const c = q('.nx-card') || q('.nx-panel') || q('.card');
  const cs = Array.from(document.querySelectorAll('canvas'));
  const sb = window.NestShaderBg, np = window.NestParticles;
  return JSON.stringify({
    page: location.pathname, title: document.title,
    dark: document.documentElement.classList.contains('dark'),
    bodyBg: getComputedStyle(document.body).backgroundColor,
    bodyImg: getComputedStyle(document.body).backgroundImage.slice(0, 15),
    /* 页面底色现在画在 html 上（body 那层被有意清成透明，否则会盖住 z-index:-10 的粒子层） */
    htmlBg: getComputedStyle(document.documentElement).backgroundColor,
    /* 粒子真的能画的证据：容器里的粒子算得出填充色（2026-10 真机定位的回归点——
       缺 color/opacity 更新器时这里是 none，canvas 在跑却一个像素都不画） */
    partFill: (np && np.container && np.container.particles && np.container.particles.get)
      ? (function () { try { const p = np.container.particles.get(0); const f = p && typeof p.getFillColor === 'function' ? p.getFillColor() : null; return f ? 'ok' : 'none'; } catch (e) { return 'err'; } })()
      : 'na',
    hFont: h ? g(h).fontFamily.split(',')[0].replace(/"/g, '') : null,
    btnR: b ? g(b).borderTopLeftRadius : null,
    btnH: b ? Math.round(b.getBoundingClientRect().height) : null,
    cardShadow: c ? g(c).boxShadow.slice(0, 30) : null,
    cardR: c ? g(c).borderTopLeftRadius : null,
    tabR: q('.nx-tab') ? g(q('.nx-tab')).borderTopLeftRadius : null,
    monoFont: (q('th') || q('.nx-badge')) ? g(q('th') || q('.nx-badge')).fontFamily.split(',')[0].replace(/"/g, '') : null,
    shadowToken: getComputedStyle(document.documentElement).getPropertyValue('--shadow-2').trim().slice(0, 20),
    easeToken: getComputedStyle(document.documentElement).getPropertyValue('--ease-3').trim().slice(0, 24),
    canvases: cs.length,
    box: cs.map((c2) => c2.clientWidth + 'x' + c2.clientHeight),
    shader: sb ? sb.state : 'absent',
    shaderTheme: sb ? sb.theme : null,
    shaderSpeed: sb && sb.speed ? sb.speed() : null,
    shaderErr: sb ? sb.error : null,
    part: np ? np.state : 'absent',
    partPreset: np ? np.preset : null,
    partHost: !!document.getElementById('nx-particles'),
    noWebgl: document.documentElement.classList.contains('nx-no-webgl'),
    scrollW: document.documentElement.scrollWidth, innerW: window.innerWidth
  });
})()`;

/* 只读一帧号：用于证明「减少动态效果」下着色器真的没在动 */
const FRAME = `window.NestShaderBg && window.NestShaderBg.mount ? window.NestShaderBg.mount.getCurrentFrame() : null`;

/* 第十八轮之后主题要写三处：localStorage（页面自举读它）、<html class="dark">（我们自己的 --nx-* 令牌）、
 * data-theme（daisyUI 组件的颜色）。只写前两处的话，重画过的登录页 / 工作台会一直是深色 ——
 * 无头 Chrome 会持久化 localStorage，上一轮探针留下的 'dark' 会污染这一轮所有「浅色」读数。 */
const LIGHT = `localStorage.setItem('nest-theme','light'); document.documentElement.classList.remove('dark'); document.documentElement.setAttribute('data-theme','nestlight'); 'ok'`;
const DARK = `localStorage.setItem('nest-theme','dark'); document.documentElement.classList.add('dark'); document.documentElement.setAttribute('data-theme','nestdark'); 'ok'`;

function write(name, url, steps, extra = {}) {
  const cfg = Object.assign(
    { port: PORT, clearCache: true, page: { url, width: 1440, height: 1100, waitMs: 2600 }, steps },
    extra
  );
  const file = path.join(DIR, name + '.json');
  fs.writeFileSync(file, JSON.stringify(cfg, null, 2) + '\n', 'utf8');
  console.log('wrote ' + path.relative(process.cwd(), file) + '  (' + steps.length + ' steps)');
}

write('index-light', `${BASE}/index.html?cb=${CB}i`, [
  { name: 'theme-light', eval: LIGHT, waitMs: 300 },
  { name: 'state', eval: STATE, waitMs: 800 },
  { name: 'shot', shot: true, file: 'target/zz-art17-light-index.png' },
]);

write('index-dark', `${BASE}/index.html?cb=${CB}d`, [
  { name: 'theme-dark', eval: DARK, waitMs: 900 },
  { name: 'state', eval: STATE, waitMs: 400 },
  { name: 'shot', shot: true, file: 'target/zz-art17-dark-index.png' },
]);

write('login-light', `${BASE}/login.html?cb=${CB}l`, [
  { name: 'theme-light', eval: LIGHT, waitMs: 900 },
  { name: 'state', eval: STATE, waitMs: 1000 },
  { name: 'shot', shot: true, file: 'target/zz-art17-light-login.png' },
]);

write('reduced', `${BASE}/index.html?cb=${CB}r`, [
  { name: 'state', eval: STATE, waitMs: 900 },
  { name: 'frame-a', eval: FRAME, waitMs: 800 },
  { name: 'frame-b', eval: FRAME, waitMs: 400 },
  { name: 'shot', shot: true, file: 'target/zz-art17-reduced-index.png' },
], { media: { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] } });

write('offline', `${BASE}/index.html?cb=${CB}o`, [
  { name: 'state-online', eval: STATE, waitMs: 600 },
  { name: 'go-offline', offline: true, eval: `location.reload(); 'reloading'`, waitMs: 2600 },
  { name: 'state-offline', eval: STATE, waitMs: 900 },
  { name: 'shot', shot: true, file: 'target/zz-art17-offline-index.png' },
]);

/* 工作页：需要登录态，SID 由验收脚本在运行时替换 */
write('dashboard', `${BASE}/dashboard.html?cb=${CB}w`, [
  { name: 'theme-light', eval: LIGHT, waitMs: 400 },
  { name: 'state', eval: STATE, waitMs: 900 },
  { name: 'shot', shot: true, file: 'target/zz-art17-work-dashboard.png' },
], { cookie: { name: 'JSESSIONID', value: '__SID__', url: BASE + '/' } });

const mobile = path.join(DIR, 'login-light.json');
const m = JSON.parse(fs.readFileSync(mobile, 'utf8'));
m.page.width = 375;
m.page.height = 812;
m.page.url = `${BASE}/login.html?cb=${CB}m`;
m.steps[2].file = 'target/zz-art17-mobile-login.png';
fs.writeFileSync(path.join(DIR, 'mobile.json'), JSON.stringify(m, null, 2) + '\n', 'utf8');
console.log('wrote tools/art17-probe/mobile.json  (375x812)');

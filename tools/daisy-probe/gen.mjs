/* 生成「daisyUI 样板页」的探针配置（durable，随 tools 一起交付）。
   用法：node tools/daisy-probe/gen.mjs [--port 9222] [--base http://127.0.0.1:8080]
   自己登录拿 SID（node fetch；用 curl 的 cookie jar 会 401），写 8 份配置到 tools/daisy-probe/。
   读数是**紧凑的 key=value 字符串**，不是大 JSON —— 探针输出长了会被截断，读数就看不见了。 */
import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const argOf = (name, dflt) => { const i = args.indexOf('--' + name); return i >= 0 ? args[i + 1] : dflt; };
const PORT = Number(argOf('port', 9222));
const BASE = argOf('base', 'http://127.0.0.1:8080');
const DIR = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const OUT = path.join(DIR, 'out');
fs.mkdirSync(OUT, { recursive: true });

async function sid(user) {
  const r = await fetch(BASE + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: user, password: '123456' }),
    redirect: 'manual',
  });
  const setCookie = r.headers.getSetCookie ? r.headers.getSetCookie() : [r.headers.get('set-cookie')];
  const m = /JSESSIONID=([A-F0-9]+)/.exec((setCookie || []).join(';'));
  if (!m) throw new Error('登录 ' + user + ' 失败（HTTP ' + r.status + '），拿不到 JSESSIONID');
  console.log('login ' + user + ' -> ' + m[1]);
  return m[1];
}

const LIGHT = "localStorage.setItem('nest-theme','light');document.documentElement.classList.remove('dark');document.documentElement.setAttribute('data-theme','nestlight');'light'";
const DARK = "localStorage.setItem('nest-theme','dark');document.documentElement.classList.add('dark');document.documentElement.setAttribute('data-theme','nestdark');'dark'";
const REVEAL = "document.querySelectorAll('[data-nx-reveal]').forEach(function(e){e.classList.add('is-in')});'revealed'";

/* 登录页读数 */
const LOGIN = `(() => {
  const q = (s) => document.querySelector(s);
  const cs = (el) => el ? getComputedStyle(el) : null;
  const p = [];
  p.push('page=' + location.pathname);
  p.push('theme=' + document.documentElement.getAttribute('data-theme'));
  p.push('dark=' + document.documentElement.classList.contains('dark'));
  p.push('bodyBg=' + cs(document.body).backgroundColor);
  p.push('h1font=' + cs(q('h1')).fontFamily.split(',')[0].replace(/"/g,'') + '/' + cs(q('h1')).fontSize);
  /* the primary button, not the first .btn in document order: the login page's header
     "返回" is a btn-ghost btn-sm (32px) and would make every size assertion lie. */
  const pbtn = q('#loginBtn') || q('.btn-primary') || q('.btn');
  p.push('btn=' + cs(pbtn).borderRadius + '/h' + Math.round(pbtn.getBoundingClientRect().height));
  p.push('card=' + cs(q('.card')).borderRadius + '/' + cs(q('.card')).backgroundColor);
  p.push('input=' + cs(q('.input')).borderRadius);
  p.push('alert=' + cs(q('#errorMsg')).display + '/hidden:' + q('#errorMsg').classList.contains('hidden'));
  p.push('canvas=' + document.querySelectorAll('canvas').length);
  p.push('shader=' + (window.NestShaderBg ? window.NestShaderBg.state + '/' + window.NestShaderBg.theme : '-'));
  p.push('part=' + (window.NestParticles ? window.NestParticles.state + '/' + window.NestParticles.preset : '-'));
  p.push('scrollW=' + document.documentElement.scrollWidth + '/' + window.innerWidth);
  return p.join('  ');
})()`;

/* 学生工作台读数 */
const DASH = `(() => {
  const q = (s) => document.querySelector(s);
  const cs = (el) => el ? getComputedStyle(el) : null;
  const p = [];
  const btn = q('#join-class-btn'), card = q('.card'), stat = q('#stats-row > .nx-stat');
  p.push('page=' + location.pathname);
  p.push('theme=' + document.documentElement.getAttribute('data-theme'));
  p.push('dark=' + document.documentElement.classList.contains('dark'));
  p.push('bodyBg=' + cs(document.body).backgroundColor);
  p.push('user=' + q('#user-name').textContent.trim());
  p.push('h1font=' + cs(q('h1')).fontFamily.split(',')[0].replace(/"/g,'') + '/' + cs(q('h1')).fontSize);
  p.push('btn=' + cs(btn).borderRadius + '/h' + Math.round(btn.getBoundingClientRect().height));
  p.push('card=' + cs(card).borderRadius + '/' + cs(card).borderTopWidth);
  p.push('badge=' + cs(q('.badge')).borderRadius);
  p.push('statCell=' + (stat ? cs(stat).borderTopWidth + '/' + cs(stat).borderRadius : '-'));
  p.push('statFont=' + cs(q('#stat-courses')).fontFamily.split(',')[0].replace(/"/g,''));
  p.push('lists=courses' + q('#courses-container').children.length + ' ann' + q('#announcements-container').children.length + ' todo' + q('#todo-container').children.length);
  p.push('counts=' + q('#courses-count').textContent.trim() + '/' + q('#todo-count').textContent.trim() + '/' + q('#announcements-count').textContent.trim());
  p.push('stats=' + q('#stat-courses').textContent.trim() + '/' + q('#stat-todo').textContent.trim() + '/' + q('#stat-announcements').textContent.trim());
  p.push('canvas=' + document.querySelectorAll('canvas').length);
  p.push('part=' + (window.NestParticles ? window.NestParticles.state + '/' + window.NestParticles.preset : '-'));
  p.push('shader=' + (window.NestShaderBg ? window.NestShaderBg.state : 'absent'));
  p.push('scrollW=' + document.documentElement.scrollWidth + '/' + window.innerWidth);
  return p.join('  ');
})()`;

const MODAL = `(() => {
  const q = (s) => document.querySelector(s);
  const cs = (el) => getComputedStyle(el);
  const modal = q('#join-class-modal');
  q('#join-class-btn').click();
  const p = [];
  p.push('open=' + cs(modal).display);
  p.push('box=' + cs(q('#join-class-modal .card')).borderRadius);
  p.push('input=' + cs(q('#class-code')).borderRadius);
  p.push('autofocus=' + (document.activeElement === q('#class-code')));
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
  p.push('closed=' + cs(modal).display);
  /* same two-space convention as the other readouts: values may contain single spaces */
  return p.join('  ');
})()`;

function write(name, url, width, height, steps, cookie) {
  const cfg = { port: PORT, page: { url: BASE + url, width, height, waitMs: 4000 }, steps };
  if (cookie) cfg.cookie = { name: 'JSESSIONID', value: cookie, url: BASE };
  fs.writeFileSync(path.join(OUT, name + '.json'), JSON.stringify(cfg, null, 2));
  console.log('wrote tools/daisy-probe/out/' + name + '.json');
}

const cb = argOf('cb', 'daisy1');
const student = await sid('chendw');

write('login-light', '/login.html?cb=' + cb, 1440, 1100, [{ name: 'light', eval: LIGHT }, { name: 'state', eval: LOGIN }]);
write('login-dark', '/login.html?cb=' + cb, 1440, 1100, [{ name: 'dark', eval: DARK }, { name: 'state', eval: LOGIN }]);
write('login-mobile', '/login.html?cb=' + cb, 375, 812, [{ name: 'light', eval: LIGHT }, { name: 'state', eval: LOGIN }]);
write('login-reduced', '/login.html?cb=' + cb, 1440, 1100, [
  { name: 'light', eval: LIGHT },
  { name: 'state', eval: LOGIN },
], null);
write('dashboard-light', '/dashboard.html?cb=' + cb, 1440, 1100, [
  { name: 'light', eval: LIGHT }, { name: 'state', eval: DASH }, { name: 'reveal', eval: REVEAL },
  { name: 'shot', shot: 'png', file: 'target/zz-daisy-dash-light.png' },
], student);
write('dashboard-dark', '/dashboard.html?cb=' + cb, 1440, 1100, [
  { name: 'dark', eval: DARK }, { name: 'state', eval: DASH }, { name: 'reveal', eval: REVEAL },
  { name: 'shot', shot: 'png', file: 'target/zz-daisy-dash-dark.png' },
], student);
write('dashboard-mobile', '/dashboard.html?cb=' + cb, 375, 812, [
  { name: 'light', eval: LIGHT }, { name: 'state', eval: DASH }, { name: 'reveal', eval: REVEAL },
  { name: 'shot', shot: 'png', file: 'target/zz-daisy-dash-mobile.png' },
], student);
write('dashboard-modal', '/dashboard.html?cb=' + cb, 1440, 1100, [
  { name: 'light', eval: LIGHT }, { name: 'modal', eval: MODAL },
  { name: 'state', eval: "(() => { document.querySelector('#join-class-btn').click(); return 'opened'; })()" },
  { name: 'reveal', eval: REVEAL },
  { name: 'shot', shot: 'png', file: 'target/zz-daisy-dash-modal.png' },
], student);

/* reduced-motion 那份要带媒体特性覆盖，写在最后单独改（JSON 字段与上面的 cfg 不同层级） */
const reduced = JSON.parse(fs.readFileSync(path.join(OUT, 'login-reduced.json'), 'utf8'));
reduced.media = { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] };
fs.writeFileSync(path.join(OUT, 'login-reduced.json'), JSON.stringify(reduced, null, 2));
console.log('login-reduced 已加 prefers-reduced-motion 覆盖');

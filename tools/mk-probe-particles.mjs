import fs from 'node:fs';
import path from 'node:path';

/* 生成 particle probes。用法：node target/mk-probe-par2.mjs <studentSID> [adminSID] */
const studentSid = process.argv[2] || '';
const adminSid = process.argv[3] || '';
const H = 'http://127.0.0.1:8080';
const CB = 'cb=911';

const STATE = `(function(){
  var P = window.NestParticles || {};
  var host = document.getElementById('nx-particles');
  var c = host ? host.querySelector('canvas') : null;
  var hcs = host ? getComputedStyle(host) : null;
  return JSON.stringify({
    page: location.pathname, title: document.title,
    v: P.version, preset: P.preset, state: P.state,
    host: !!host, canvases: host ? host.querySelectorAll('canvas').length : 0,
    hostBox: host ? [host.clientWidth, host.clientHeight] : null,
    css: hcs ? { position: hcs.position, zIndex: hcs.zIndex, pointerEvents: hcs.pointerEvents } : null,
    lib: window.tsParticles ? 'object' : String(window.tsParticles),
    libTags: document.querySelectorAll('script[src*="tsparticles"]').length,
    scriptTags: document.querySelectorAll('script[src*="nest-particles"]').length,
    dark: document.documentElement.classList.contains('dark')
  });
})()`;

const FPS = `new Promise(function(res){
  var n = 0, t0 = performance.now();
  function loop(){
    n++;
    var dt = performance.now() - t0;
    if (dt < 1500) requestAnimationFrame(loop);
    else res(JSON.stringify({ fps: Math.round(n / (dt / 1000) * 10) / 10, frames: n, ms: Math.round(dt) }));
  }
  requestAnimationFrame(loop);
})`;

const DESTROY = `(function(){
  if (window.NestParticles) window.NestParticles.destroy();
  var host = document.getElementById('nx-particles');
  return JSON.stringify({ state: window.NestParticles && window.NestParticles.state, canvases: host ? host.querySelectorAll('canvas').length : 0 });
})()`;

const theme = (name) => ({ name: 'theme-' + name, eval: `localStorage.setItem('nest-theme','${name}'); location.reload(); 'x'`, waitMs: 3500 });

const cfgs = {
  'index-light': {
    page: { url: `${H}/index.html?${CB}`, width: 1440, height: 1100, waitMs: 2500 },
    steps: [theme('light'), { name: 'state-light', eval: STATE }, { name: 'fps-on', eval: FPS },
      { name: 'shot-light', shot: path.resolve('target/zz-par2-light-index.png') },
      { name: 'destroy', eval: DESTROY }, { name: 'fps-off', eval: FPS }],
  },
  'index-dark': {
    page: { url: `${H}/index.html?${CB}`, width: 1440, height: 1100, waitMs: 2500 },
    steps: [theme('dark'), { name: 'state-dark', eval: STATE },
      { name: 'shot-dark', shot: path.resolve('target/zz-par2-dark-index.png') }],
  },
  'index-mobile': {
    page: { url: `${H}/index.html?${CB}`, width: 375, height: 812, waitMs: 2500 },
    steps: [theme('light'), { name: 'state-mobile', eval: STATE },
      { name: 'shot-mobile', shot: path.resolve('target/zz-par2-mobile-index.png') }],
  },
  'index-offline': {
    page: { url: `${H}/index.html?${CB}`, width: 1440, height: 950, waitMs: 2500 },
    steps: [{ name: 'state-online', eval: STATE }, { name: 'go-offline', offline: true },
      { name: 'reload', eval: `location.reload(); 'x'`, waitMs: 3500 }, { name: 'state-offline', eval: STATE }],
  },
  'index-reduced': {
    media: { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] },
    page: { url: `${H}/index.html?${CB}`, width: 1440, height: 1100, waitMs: 3000 },
    steps: [{ name: 'state-reduced', eval: STATE },
      { name: 'shot-reduced', shot: path.resolve('target/zz-par2-reduced-index.png') }],
  },
  'login-clean': {
    page: { url: `${H}/login.html?${CB}`, width: 1440, height: 1000, waitMs: 2500 },
    steps: [{ name: 'state-login', eval: STATE },
      { name: 'shot-login', shot: path.resolve('target/zz-par2-light-login.png') }],
  },
  'settings-soft': {
    cookie: { name: 'JSESSIONID', value: studentSid, url: `${H}/` },
    page: { url: `${H}/settings.html?${CB}`, width: 1440, height: 1100, waitMs: 2500 },
    steps: [{ name: 'state-settings', eval: STATE },
      { name: 'shot-settings', shot: path.resolve('target/zz-par2-light-settings.png') }],
  },
  'course-soft': {
    cookie: { name: 'JSESSIONID', value: studentSid, url: `${H}/` },
    page: { url: `${H}/leave.html?${CB}`, width: 1440, height: 1100, waitMs: 2500 },
    steps: [{ name: 'state-course', eval: STATE },
      { name: 'shot-course', shot: path.resolve('target/zz-par2-light-course.png') }],
  },
  'admin-soft': {
    cookie: { name: 'JSESSIONID', value: adminSid, url: `${H}/` },
    page: { url: `${H}/admin-dashboard.html?${CB}`, width: 1440, height: 1000, waitMs: 2500 },
    steps: [{ name: 'state-admin', eval: STATE },
      { name: 'shot-admin', shot: path.resolve('target/zz-par2-light-admin.png') }],
  },
};

let n = 0;
for (const [key, cfg] of Object.entries(cfgs)) {
  if (/admin/.test(key) && !adminSid) continue;
  if (/settings|course/.test(key) && !studentSid) continue;
  fs.writeFileSync(path.resolve(`target/probe-par2-${key}.json`), JSON.stringify(cfg, null, 2));
  n++;
}
console.log(`wrote ${n} config(s) into target/probe-par2-*.json`);

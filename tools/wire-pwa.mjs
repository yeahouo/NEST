// 把 PWA 四件套挂到页面的 <head> 里：icon / manifest / apple-touch-icon（每个页面都要），
// 以及 Service Worker 引导脚本 /pwa.js（只挂学生端页面，见下面 SW_PAGES）。
//
// 为什么单独一个脚本，而不是并进 wire-nest-ui.mjs：
// 那个脚本管的是"每个页面都要有的交互层"（主题引导 + Radix 色板 + nest-ui.js），
// 这个脚本管的是"能被装到手机主屏"（PWA 元数据）。两件事的幂等判断混在一起，
// 以后排查"为什么某个页面没生效"要同时读两套条件，不划算。
//
// 用法：node tools/wire-pwa.mjs [--dry]
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const DIR = 'src/main/resources/static';
const dry = process.argv.includes('--dry');

// key 用来判断"已存在"，所以只写属性里最稳的那一段，不写 href（顺序可能不一样）。
const WANT = [
  { key: 'rel="icon"', tag: '<link rel="icon" type="image/png" sizes="192x192" href="/icons/icon-192.png"/>' },
  { key: 'rel="manifest"', tag: '<link rel="manifest" href="/manifest.webmanifest"/>' },
  { key: 'rel="apple-touch-icon"', tag: '<link rel="apple-touch-icon" href="/icons/apple-touch-icon.png"/>' },
];

// Service Worker 只挂学生端页面，理由同 sw.js 的 SHELL：
//   1. 装这个 App 的是学生，管理端/辅导员页面不该替他的手机预下载工作台；
//   2. SW 一旦注册就按 scope 拦截整站请求并常驻缓存，管理端出问题时第一现场在
//      辅导员或管理员的浏览器里，而这类问题最难复现。学生端先上，范围可控。
// 公共入口（落地/登录/注册/找回）也算学生端：学生在手机上打开的第一个页面往往就是这些。
const SW_PAGES = new Set([
  'index.html',
  'login.html',
  'register.html',
  'forgot-password.html',
  'reset-password.html',
  'dashboard.html',
  'leave.html',
  'award-punishment.html',
  'aid.html',
  'poverty.html',
  'evaluation.html',
  'honor.html',
  'ranking.html',
  'psyche.html',
  'community.html',
  'party.html',
  'volunteer.html',
  'club.html',
  'workstudy.html',
  'profile.html',
  'settings.html',
  'dorm-portal.html',
  'under-development.html',
]);
const SW_TAG = { key: 'src="/pwa.js"', tag: '<script src="/pwa.js" defer></script>' };

const pages = readdirSync(DIR).filter((n) => n.endsWith('.html')).sort();
let touched = 0;
let skipped = 0;
let swTagged = 0;

for (const f of pages) {
  const p = join(DIR, f);
  const src = readFileSync(p, 'utf8');
  const want = SW_PAGES.has(f) ? [...WANT, SW_TAG] : WANT;
  const missing = want.filter((w) => !src.includes(w.key));
  if (!missing.length) {
    console.log(`= ${f} 已有 PWA 元数据`);
    continue;
  }

  const crlf = src.includes('\r\n');
  const lines = src.split(/\r?\n/);

  // 插入点紧跟在第一个样式表 <link> 后面：那里一定在 <head> 里，而且不会被
  // 「正文里提到的 <link>」这种坑骗到（rel 与 href 都要出现才算）。
  let idx = lines.findIndex((l) => /<link/i.test(l) && /rel=["']stylesheet/i.test(l) && /href=/i.test(l));
  if (idx < 0) idx = lines.findIndex((l) => /<title>/i.test(l));
  if (idx < 0) idx = lines.findIndex((l) => /<head>/i.test(l));
  if (idx < 0) {
    console.log(`! ${f} 找不到插入点，跳过`);
    skipped++;
    continue;
  }

  const indent = (lines[idx].match(/^\s*/) || [''])[0];
  lines.splice(idx + 1, 0, ...missing.map((m) => indent + m.tag));
  if (!dry) writeFileSync(p, lines.join(crlf ? '\r\n' : '\n'), 'utf8');
  if (missing.includes(SW_TAG)) swTagged++;
  console.log(`${dry ? '?' : '+'} ${f} 插入 ${missing.map((m) => m.key).join(' + ')}`);
  touched++;
}

console.log(`${dry ? '（dry run）' : ''}共 ${pages.length} 页：改动 ${touched}，跳过 ${skipped}，其中挂 /pwa.js 的 ${swTagged}`);

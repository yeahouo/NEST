#!/usr/bin/env node
/**
 * wire-shader-bg.mjs —— 把入口页的着色器背景按页接到站点上（幂等）
 *
 * 只在 6 个入口页插一行 <script type="module" src="/js/nest-shader-bg.js"></script>：
 * 首页 / 登录 / 注册 / 忘记密码 / 重置密码 / 开发中提示。工作页**不接** —— 那边已经有
 * /js/nest-particles.js 的粒子层，两层动画叠在一起对机房的老机器是白付填充率。
 *
 * 默认 dry-run，只打印计划；加 --apply 才写盘，写前整页备份到 target/backup-shader-bg/。
 * 重复跑不会重复加（幂等）。--file= 可只处理一页。
 *
 * 用法：
 *   node tools/wire-shader-bg.mjs
 *   node tools/wire-shader-bg.mjs --apply
 *   node tools/wire-shader-bg.mjs --file=index.html --apply
 */
import fs from 'node:fs';
import path from 'node:path';

const STATIC_DIR = path.resolve('src/main/resources/static');
const BACKUP_DIR = path.resolve('target/backup-shader-bg');
const SCRIPT_TAG = '<script type="module" src="/js/nest-shader-bg.js"></script>';
const AFTER_PARTICLES = /<script[^>]+src="\/js\/nest-particles\.js"[^>]*><\/script>/;
const AFTER_MOTION = /<script[^>]+src="\/js\/nest-motion\.js"[^>]*><\/script>/;

const PAGES = [
  'index.html',
  'login.html',
  'register.html',
  'forgot-password.html',
  'reset-password.html',
  'under-development.html',
];

const argv = process.argv.slice(2);
const apply = argv.includes('--apply');
const only = (argv.find((a) => a.startsWith('--file=')) || '').slice(7);

function detectEol(text) {
  return text.includes('\r\n') ? '\r\n' : '\n';
}

function plan(file) {
  const abs = path.join(STATIC_DIR, file);
  if (!fs.existsSync(abs)) { return { file, missing: true }; }
  const before = fs.readFileSync(abs, 'utf8');
  const eol = detectEol(before);
  let text = before;
  const notes = [];

  if (text.includes('nest-shader-bg.js')) {
    notes.push('script: already linked');
  } else if (AFTER_PARTICLES.test(text)) {
    text = text.replace(AFTER_PARTICLES, (m) => m + eol + SCRIPT_TAG);
    notes.push('script: +after nest-particles.js');
  } else if (AFTER_MOTION.test(text)) {
    text = text.replace(AFTER_MOTION, (m) => m + eol + SCRIPT_TAG);
    notes.push('script: +after nest-motion.js');
  } else if (text.includes('</body>')) {
    text = text.replace('</body>', SCRIPT_TAG + eol + '</body>');
    notes.push('script: +before </body>');
  } else {
    notes.push('script: no anchor');
  }

  return { file, abs, before, text, eol, notes, changed: text !== before };
}

const targets = PAGES.filter((f) => !only || f === only).map(plan);

let changed = 0;
console.log(`wire-shader-bg: ${apply ? 'APPLY' : 'dry-run'}  (${targets.length} page(s))`);
for (const t of targets) {
  if (t.missing) { console.log(`  ${t.file.padEnd(26)} MISSING`); continue; }
  const size = `${t.before.length} -> ${t.text.length} B`;
  console.log(`  ${t.file.padEnd(26)} ${t.changed ? 'CHANGE' : 'skip  '}  ${size.padEnd(20)} ${t.notes.join(' | ')}`);
  if (t.changed) { changed++; }
}
console.log(`  pages that would change: ${changed}`);

if (apply && changed) {
  fs.mkdirSync(BACKUP_DIR, { recursive: true });
  for (const t of targets) {
    if (t.missing || !t.changed) { continue; }
    fs.copyFileSync(t.abs, path.join(BACKUP_DIR, t.file));
    fs.writeFileSync(t.abs, t.text, 'utf8');
    console.log(`  wrote ${t.file}  (backup in target/backup-shader-bg/)`);
  }
} else if (apply) {
  console.log('  nothing to write (already wired)');
}

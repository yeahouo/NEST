#!/usr/bin/env node
/**
 * wire-particles.mjs —— 把背景粒子层按页接到站点上（幂等）
 *
 * 做两件事，缺哪件补哪件：
 *   1. 给 <body> 加 data-nx-particles="<preset>"（取值见 nest-particles.js 的 PRESETS）
 *   2. 在 /js/nest-motion.js 那行之后插入 <script defer src="/js/nest-particles.js"></script>
 *
 * 默认 dry-run，只打印计划；加 --apply 才写盘，写前整目录备份到
 * target/backup-particles/。重复跑不会重复加（幂等）。
 *
 * 用法：
 *   node tools/wire-particles.mjs
 *   node tools/wire-particles.mjs --apply
 *   node tools/wire-particles.mjs --file=login.html --preset=soft --apply
 */
import fs from 'node:fs';
import path from 'node:path';

const STATIC_DIR = path.resolve('src/main/resources/static');
const BACKUP_DIR = path.resolve('target/backup-particles');
const SCRIPT_TAG = '<script defer src="/js/nest-particles.js"></script>';
const ANCHOR = /<script[^>]+src="\/js\/nest-motion\.js"[^>]*><\/script>/;
const ATTR = 'data-nx-particles';

/* 全站统一：除 dorm.html（自带内联 style、不引 nest.css）外每页都挂同一层 canvas 粒子。
   强度分三档：门口/落地页最亮，工作台最安静（soft），免得动效抢读数据的注意力。 */
const PAGES = [
  ['index.html', 'dense'],
  ['login.html', 'on'],
  ['register.html', 'on'],
  ['forgot-password.html', 'on'],
  ['reset-password.html', 'on'],
  ['under-development.html', 'on'],
  ['dashboard.html', 'soft'],
  ['course-detail.html', 'soft'],
  ['settings.html', 'soft'],
  ['checkin.html', 'soft'],
  ['file-preview.html', 'soft'],
  ['dorm-portal.html', 'soft'],
  ['dorm-admin.html', 'soft'],
  ['dorm-psyche.html', 'soft'],
  ['admin-dashboard.html', 'soft'],
  ['admin-users.html', 'soft'],
  ['admin-courses.html', 'soft'],
  ['admin-invitation-codes.html', 'soft'],
  ['teacher-dashboard.html', 'soft'],
  ['teacher-course-detail.html', 'soft'],
];

const argv = process.argv.slice(2);
const apply = argv.includes('--apply');
const only = (argv.find((a) => a.startsWith('--file=')) || '').slice(7);
const presetArg = (argv.find((a) => a.startsWith('--preset=')) || '').slice(9);

function detectEol(text) {
  return text.includes('\r\n') ? '\r\n' : '\n';
}

function plan(file, preset) {
  const abs = path.join(STATIC_DIR, file);
  if (!fs.existsSync(abs)) { return { file, missing: true }; }
  const before = fs.readFileSync(abs, 'utf8');
  const eol = detectEol(before);
  let text = before;
  const notes = [];

  const bodyTag = text.match(/<body[^>]*>/);
  const hasAttr = new RegExp(`\\b${ATTR}\\s*=`).test(text);
  if (!bodyTag) {
    notes.push('no <body> tag');
  } else if (hasAttr) {
    notes.push(`attr: already ${ATTR}`);
  } else {
    const replaced = bodyTag[0].replace('<body', `<body ${ATTR}="${preset}"`);
    text = text.replace(bodyTag[0], replaced);
    notes.push(`attr: +${ATTR}="${preset}"`);
  }

  if (text.includes(SCRIPT_TAG)) {
    notes.push('script: already linked');
  } else if (ANCHOR.test(text)) {
    text = text.replace(ANCHOR, (m) => m + eol + SCRIPT_TAG);
    notes.push('script: +after nest-motion.js');
  } else if (text.includes('</body>')) {
    text = text.replace('</body>', SCRIPT_TAG + eol + '</body>');
    notes.push('script: +before </body>');
  } else {
    notes.push('script: no anchor');
  }

  return { file, abs, before, text, eol, notes, changed: text !== before };
}

const targets = PAGES.filter(([file]) => !only || file === only).map(([file, preset]) =>
  plan(file, only && presetArg ? presetArg : preset)
);

let changed = 0;
console.log(`wire-particles: ${apply ? 'APPLY' : 'dry-run'}  (${targets.length} page(s))`);
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
    console.log(`  wrote ${t.file}  (backup in target/backup-particles/)`);
  }
} else if (apply) {
  console.log('  nothing to write (already wired)');
}

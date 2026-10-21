// wire-open-props.mjs — 把 vendored 的 Open Props 令牌表接到每个页面上（幂等）
//
// 为什么需要它：input.css 的 nx-* 组件从第十七轮起引用 Open Props 的令牌
// （--radius-round / --shadow-* / --ease-* / --gradient-*）。这些令牌只在
// open-props.min.css 被加载时才存在；漏一页不会报错，只会静默少样式。
// 与 wire-particles.mjs 一样：默认 dry-run，--apply 前整页备份。
//
// 用法:
//   node tools/wire-open-props.mjs            # 预演
//   node tools/wire-open-props.mjs --apply    # 落盘（备份到 target/backup-open-props/）
//   node tools/wire-open-props.mjs --file=index.html

import { readFileSync, writeFileSync, mkdirSync, copyFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const STATIC = join(ROOT, 'src', 'main', 'resources', 'static');
const BACKUP = join(ROOT, 'target', 'backup-open-props');

const LINK = '<link href="/libs/open-props/open-props.min.css" rel="stylesheet"/>';
const FONTS_RE = /^[ \t]*<link[^>]*href="\/css\/nest-fonts\.css"[^>]*>[ \t]*$/m;
const MARK = '/libs/open-props/open-props.min.css';

const args = process.argv.slice(2);
const apply = args.includes('--apply');
const only = (args.find((a) => a.startsWith('--file=')) || '').slice(7);

// 目标页 = 引了 /css/nest-fonts.css 的页面（也就是走 nest.css 组件体系的页面）。
// art.html 不在其中：它有自己的一份令牌与 @font-face。
const pages = (process.argv.includes('--list') ? [] : null) || [
  'admin-courses.html', 'admin-dashboard.html', 'admin-invitation-codes.html', 'admin-users.html',
  'checkin.html', 'course-detail.html', 'dashboard.html',
  'dorm-admin.html', 'dorm-portal.html', 'dorm-psyche.html',
  'file-preview.html', 'forgot-password.html', 'index.html', 'login.html',
  'register.html', 'reset-password.html', 'settings.html',
  'teacher-course-detail.html', 'teacher-dashboard.html', 'under-development.html',
];

let changed = 0, skipped = 0, failed = 0;
for (const page of pages) {
  if (only && page !== only) continue;
  const file = join(STATIC, page);
  if (!existsSync(file)) { console.log(`  MISSING  ${page}`); failed++; continue; }
  const src = readFileSync(file, 'utf8');
  if (src.includes(MARK)) { console.log(`  skip     ${page}  (已有)`); skipped++; continue; }
  const m = src.match(FONTS_RE);
  if (!m) { console.log(`  NOLINK   ${page}  (找不到 nest-fonts.css 那一行)`); failed++; continue; }
  const indent = (m[0].match(/^[ \t]*/) || [''])[0];
  const out = src.replace(FONTS_RE, `${indent}${LINK}\n${m[0]}`);
  if (out === src) { console.log(`  skip     ${page}  (无变化)`); skipped++; continue; }
  console.log(`  CHANGE   ${page}  ${src.length} -> ${out.length} 字节`);
  changed++;
  if (apply) {
    mkdirSync(BACKUP, { recursive: true });
    copyFileSync(file, join(BACKUP, page));
    writeFileSync(file, out);
  }
}
console.log(`\n${apply ? '已落盘' : '预演'}：会改 ${changed} 页，跳过 ${skipped} 页，失败 ${failed} 页`);
if (apply && changed) console.log(`备份目录：target/backup-open-props/`);
if (failed) process.exit(1);

#!/usr/bin/env node
/**
 * wire-motion.mjs — 把「灵动层」的脚本挂到所有页面上（幂等）
 *
 * 做三件事（每页只做一次）：
 *   1. 在 <script defer src="/js/nest-ui.js"></script> 之后插入四个 vendored 动效库
 *   2. 再插入 <script defer src="/js/nest-motion.js"></script>
 *   3. 没有 nest-ui.js 的页面退化为插在 </body> 之前
 *
 * 库与出处（都在 src/main/resources/static/libs/ 下，离线可用）：
 *   anime.js v4.5.0 (MIT) · CountUp.js v2.10.1 (MIT)
 *   vanilla-tilt v1.8.1 (MIT) · canvas-confetti v1.9.4 (ISC)
 *
 * 用法：
 *   node tools/wire-motion.mjs            # 真写
 *   node tools/wire-motion.mjs --dry      # 只看会改哪些页
 */
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const STATIC = resolve(process.cwd(), 'src/main/resources/static');
const dry = process.argv.includes('--dry');

const LIBS = [
  '/libs/animejs/anime.umd.min.js',
  '/libs/countup/countUp.umd.js',
  '/libs/vanilla-tilt/vanilla-tilt.min.js',
  '/libs/canvas-confetti/confetti.browser.js'
];
const MOTION = '/js/nest-motion.js';
const MARK = '/js/nest-motion.js';

const pages = readdirSync(STATIC).filter((f) => f.endsWith('.html')).sort();
let changed = 0;
let skipped = 0;

for (const page of pages) {
  const file = join(STATIC, page);
  const src = readFileSync(file, 'utf8');
  if (src.includes(MARK)) {
    skipped++;
    console.log(`  = ${page} (已有灵动层)`);
    continue;
  }
  const crlf = src.includes('\r\n');
  const nl = crlf ? '\r\n' : '\n';
  const tags = [...LIBS, MOTION].map((h) => `<script defer src="${h}"></script>`);

  let out;
  const uiTag = /(<script[^>]+src=["'][^"']*\/js\/nest-ui\.js["'][^>]*><\/script>)/i;
  const m = uiTag.exec(src);
  if (m) {
    out = src.slice(0, m.index + m[0].length) + nl + tags.map((t) => '    ' + t).join(nl) + src.slice(m.index + m[0].length);
  } else if (/<\/body>/i.test(src)) {
    out = src.replace(/<\/body>/i, tags.map((t) => '    ' + t).join(nl) + nl + '</body>');
  } else {
    console.log(`  ! ${page} 找不到 nest-ui.js 也没有 </body>，跳过`);
    continue;
  }
  if (!dry) writeFileSync(file, out, 'utf8');
  changed++;
  console.log(`  + ${page} (插入 ${tags.length} 个 script)`);
}

console.log(`\n${dry ? '[dry] ' : ''}改动 ${changed} 页，跳过 ${skipped} 页，共 ${pages.length} 页`);

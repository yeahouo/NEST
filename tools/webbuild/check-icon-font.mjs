// 校验「页面里写的图标名」是否真的会渲染成图标。
//
// 为什么需要它：Material Symbols 是按清单子集化的 woff2，清单外的名字不会报错，
// 只会退化成字面英文（页面上出现 "expand_more" 这种词）。类名在 HTML 里、样式在 CSS 里、
// 字形在 woff2 里，三者都「正确」而屏幕上是错的 —— 只有渲染出来才看得见。
//
// 做法：从 woff2 里把 GSUB 连字规则解出来（图标名 = 连字输入串），再和页面里用到的名字对账。
// 解字形那步交给 python/fontTools（tools/webbuild/dump-icon-glyphs.py），本脚本负责对账，
// 这样检查本身不需要 Python，只用现成的 icon-glyphs.txt。
//
// 注意：fetch-icons.mjs 的「官方清单」检查只能报出拼错的名字，报不出「名字对但字体没带」。
//       真正的对账只有本脚本做得到。
//
// 用法：node tools/webbuild/check-icon-font.mjs [--quiet]
// 退出码：0 = 全部命中，1 = 有名字不在字体里
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const STATIC = join(HERE, '..', '..', 'src', 'main', 'resources', 'static');
const FONT = join(STATIC, 'fonts', 'material-symbols-outlined.woff2');
const GLYPHS = join(HERE, 'icon-glyphs.txt');
const QUIET = process.argv.includes('--quiet');

// ---- 1) 字体支持的图标名 ----
// icon-glyphs.txt 是字体的派生物；字体变了它就必须重跑，所以这里顺手比一下大小，
// 提醒而不是自动重建（重建要 Python，不该在一次纯检查里偷偷发生）。
if (!existsSync(GLYPHS)) {
  console.error('缺少 ' + GLYPHS);
  console.error('先跑：python tools/webbuild/dump-icon-glyphs.py');
  process.exit(2);
}
const fontNames = new Set(readFileSync(GLYPHS, 'utf8').split('\n').map(s => s.trim()).filter(Boolean));
const fontBytes = readFileSync(FONT);

// ---- 2) 页面里用到的图标名（与 fetch-icons.mjs 同一套抽取规则）----
// 除了 HTML，还扫 js/ 下的共享脚本：命令面板与 Toast 的图标名现在写在 JS 里，
// 只扫 HTML 的话「新加一个图标名，字体里没有」这件事会没人发现。
const pages = readdirSync(STATIC, { recursive: true }).map(String).filter(f => f.endsWith('.html'));
const jsDir = join(STATIC, 'js');
const scripts = existsSync(jsDir)
  ? readdirSync(jsDir, { recursive: true }).map(String).filter(f => f.endsWith('.js')).map(f => 'js/' + f.replace(/\\/g, '/'))
  : [];
const sources = [...pages, ...scripts].sort();
const used = new Map();
const note = (name, where) => {
  if (!name) return;
  if (!used.has(name)) used.set(name, new Set());
  used.get(name).add(where);
};
for (const p of sources) {
  let t;
  try { t = readFileSync(join(STATIC, p), 'utf8'); } catch { continue; }
  for (const m of t.matchAll(/material-symbols-outlined[^>]*>\s*([a-z0-9_]+)\s*</g)) note(m[1], p);
  for (const m of t.matchAll(/\b(?:card|statCard|icon|iconBtn)\s*\(\s*(?:'[^']*'|"[^"]*"|`[^`]*`)\s*,\s*(?:'([a-z0-9_]+)'|"([a-z0-9_]+)")/g)) {
    note(m[1] || m[2], p + ' (JS)');
  }
  // 命令面板条目的图标以属性给出（data-nx-cmd-icon="school"），不在图标 span 里
  for (const m of t.matchAll(/data-nx-cmd-icon="([a-z0-9_]+)"/g)) note(m[1], p + ' (cmdk)');
  // 只能由变量拼出来的名字（例如 TOAST_ICON 映射表）用一行指令显式声明：
  //   /* nx-icons: check_circle warning error info */
  for (const m of t.matchAll(/nx-icons:\s*([a-z0-9_\s]+?)\s*\*\//g)) {
    for (const name of m[1].split(/\s+/).filter(Boolean)) note(name, p + ' (nx-icons)');
  }
}

// ---- 3) 对账 ----
const missing = [...used.keys()].filter(n => !fontNames.has(n)).sort();
const present = [...used.keys()].filter(n => fontNames.has(n)).sort();
if (!QUIET) {
  console.log(`字体 ${FONT}`);
  console.log(`      ${fontBytes.length} 字节，支持 ${fontNames.size} 个图标名`);
  console.log(`      页面用到 ${used.size} 个不同的图标名，其中 ${present.length} 个在字体里\n`);
}
if (missing.length) {
  console.log(`✗ ${missing.length} 个名字不在字体里 —— 屏幕上会显示成字面英文：`);
  for (const n of missing) console.log(`    ${n}   ← ${[...used.get(n)].join(', ')}`);
  if (!QUIET) {
    console.log('\n修法：联网后跑 node tools/webbuild/fetch-icons.mjs 重新生成字体，');
    console.log('      再跑 python tools/webbuild/dump-icon-glyphs.py 刷新清单，然后重跑本脚本。');
  }
} else if (!QUIET) {
  console.log('✓ 页面用到的图标名全部在字体里');
}
process.exit(missing.length ? 1 : 0);

// 学生/教师/管理端「卡片、按钮、标签」现行写法的清点工具（只读）
// 用法: node tools/inventory-ui.mjs [--top 12] [--category card|button|chip|all]
//
// 目的：用户反馈「卡片也好按钮也罢，效果东一个西一个」，本工具把 21 个页面里
// 实际的 class 串按类别去重计数，作为统一改造的证据表 —— 改之前看到底有几种写法。
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const STATIC = 'src/main/resources/static';
const argv = process.argv.slice(2);
const topN = Number(argv[argv.indexOf('--top') + 1]) || 12;
const only = (() => {
  const i = argv.indexOf('--category');
  return i >= 0 ? argv[i + 1] : 'all';
})();

const files = readdirSync(STATIC).filter((f) => f.endsWith('.html')).sort();

const tally = (map, key, page) => {
  const cur = map.get(key) || { n: 0, pages: new Map() };
  cur.n += 1;
  cur.pages.set(page, (cur.pages.get(page) || 0) + 1);
  map.set(key, cur);
};

const cards = new Map();
const buttons = new Map();
const chips = new Map();
const radii = new Map();
const shadows = new Map();

const tagRe = /<([a-zA-Z][a-zA-Z0-9-]*)\b([^>]*)>/g;
const classRe = /\bclass\s*=\s*"([^"]*)"/i;

for (const file of files) {
  const src = readFileSync(join(STATIC, file), 'utf8');
  let m;
  while ((m = tagRe.exec(src))) {
    const tag = m[1].toLowerCase();
    const attrs = m[2];
    const cm = attrs.match(classRe);
    if (!cm) continue;
    const cls = cm[1].replace(/\s+/g, ' ').trim();
    for (const t of cls.match(/rounded[-\w./[\]]*/g) || []) tally(radii, t, file);
    for (const t of cls.match(/shadow[-\w./[\]]*/g) || []) tally(shadows, t, file);
    const isBtnLike =
      /nx-btn|nx-icon-btn/.test(cls) ||
      /cursor-pointer|onclick=/.test(attrs) ||
      (tag === 'button' && cls.length > 0);
    const isChipLike = /nx-chip|nx-badge|rounded-full/.test(cls) && /px-\d/.test(cls) && cls.length < 220;
    const isCardLike =
      /rounded-/.test(cls) && /(backdrop-blur|shadow-|border)/.test(cls) &&
      /^(div|section|article|aside|li|form|header|footer|main)$/.test(tag);
    if (isCardLike && cls.length < 260) tally(cards, cls, file);
    if (isBtnLike && cls.length < 220 && !/^\s*$/.test(cls)) tally(buttons, cls, file);
    if (isChipLike && cls.length < 160) tally(chips, cls, file);
  }
}

const report = (title, map, filterFn) => {
  const rows = [...map.entries()]
    .filter(([k]) => (filterFn ? filterFn(k) : true))
    .sort((a, b) => b[1].n - a[1].n);
  const distinct = rows.length;
  console.log(`\n=== ${title}（${distinct} 种写法，共 ${rows.reduce((s, r) => s + r[1].n, 0)} 处）`);
  for (const [k, v] of rows.slice(0, topN)) {
    const pages = [...v.pages.entries()].slice(0, 3).map(([p, n]) => `${p.replace('.html', '')}×${n}`).join(' ');
    console.log(`  ${String(v.n).padStart(3)}  ${k.slice(0, 150)}   [${pages}]`);
  }
  return distinct;
};

const want = (c) => only === 'all' || only === c;

if (want('card')) report('卡片/面板（含 rounded- 且带 border/shadow/blur 的容器）', cards);
if (want('button')) report('按钮（.nx-btn* / .nx-icon-btn / <button> / onclick 元素）', buttons);
if (want('chip')) report('标签/胶囊（含 rounded-full + px-*，或 .nx-chip/.nx-badge）', chips);
if (only === 'all') {
  report('圆角刻度', radii);
  report('阴影刻度', shadows);
}

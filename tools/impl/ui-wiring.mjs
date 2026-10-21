// UI 接线审计：找出「页面上的按钮/元素没有处理器」「JS 引用了不存在的元素 id」「页面根本没在调接口」这三类半成品信号。
// 用法：node tools/impl/ui-wiring.mjs
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..', '..');
const STATIC = path.join(ROOT, 'src', 'main', 'resources', 'static');
const pages = fs.readdirSync(STATIC).filter(f => f.endsWith('.html')).sort();

const rows = [];
for (const page of pages) {
  const src = fs.readFileSync(path.join(STATIC, page), 'utf8');

  // 已定义的函数名
  const defined = new Set();
  for (const m of src.matchAll(/\bfunction\s+([A-Za-z_$][\w$]*)\s*\(/g)) defined.add(m[1]);
  for (const m of src.matchAll(/\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:function\b|\()/g)) defined.add(m[1]);
  for (const m of src.matchAll(/\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*async\s+[A-Za-z_$][\w$]*\s*=>/g)) defined.add(m[1]);
  for (const m of src.matchAll(/window\.([A-Za-z_$][\w$]*)\s*=/g)) defined.add(m[1]);

  // 内联 onclick 里调用的函数
  const calledInline = new Set();
  for (const m of src.matchAll(/on(?:click|change|submit|input)\s*=\s*"([^"]*)"/g)) {
    for (const c of m[1].matchAll(/([A-Za-z_$][\w$]*)\s*\(/g)) calledInline.add(c[1]);
  }
  const builtin = new Set(['if','for','while','return','alert','confirm','prompt','console','JSON','String','Number','Boolean','Array','Object','Math','Date','parseInt','parseFloat','encodeURIComponent','decodeURIComponent','document','window','this','event','preventDefault','stopPropagation','setTimeout','setInterval','fetch','await','new','typeof','void','switch','catch','function','localStorage','sessionStorage','location','history','arguments','delete','in','of','instanceof','class','try','do','else','yield','throw','case','break','continue','var','let','const']);
  const unresolved = [...calledInline].filter(n => !defined.has(n) && !builtin.has(n));

  // getElementById / querySelector('#x') 的目标 id 是否存在于文档里
  const ids = new Set();
  for (const m of src.matchAll(/\bid\s*=\s*"([^"]+)"/g)) ids.add(m[1]);
  const wanted = new Set();
  for (const m of src.matchAll(/getElementById\(\s*['"]([^'"]+)['"]\s*\)/g)) wanted.add(m[1]);
  for (const m of src.matchAll(/querySelector(?:All)?\(\s*['"]#([A-Za-z_$][\w$-]*)['"]/g)) wanted.add(m[1]);
  const missingIds = [...wanted].filter(i => !ids.has(i));

  // 接口调用数
  const apiCalls = (src.match(/\/api\//g) || []).length;

  // 事件绑定数
  const listeners = (src.match(/addEventListener\s*\(/g) || []).length;

  rows.push({ page, defined: defined.size, unresolved, missingIds, apiCalls, listeners, lines: src.split(/\r?\n/).length });
}

console.log('');
console.log('================ 每页接线概览 ================');
console.log('  页面'.padEnd(34) + '行数   调接口  事件绑定  内联未定义函数  缺失元素id');
for (const r of rows) {
  console.log('  ' + r.page.padEnd(32) + String(r.lines).padStart(5) + String(r.apiCalls).padStart(8) + String(r.listeners).padStart(10) + String(r.unresolved.length).padStart(16) + String(r.missingIds.length).padStart(14));
}

console.log('');
console.log('================ 内联 onclick 调用了未定义的函数 ================');
let any = false;
for (const r of rows) {
  if (!r.unresolved.length) continue;
  any = true;
  console.log(`  ${r.page}: ${r.unresolved.join(', ')}`);
}
if (!any) console.log('  （无）');

console.log('');
console.log('================ JS 引用了页面上不存在的元素 id ================');
any = false;
for (const r of rows) {
  if (!r.missingIds.length) continue;
  any = true;
  const shown = r.missingIds.slice(0, 12);
  console.log(`  ${r.page} (${r.missingIds.length}): ${shown.join(', ')}${r.missingIds.length > 12 ? ' …' : ''}`);
}
if (!any) console.log('  （无）');

console.log('');
console.log('================ 从不调用任何接口的页面（静态样张嫌疑） ================');
any = false;
for (const r of rows) if (r.apiCalls === 0) { any = true; console.log(`  ${r.page}  (${r.lines} 行, 事件绑定 ${r.listeners})`); }
if (!any) console.log('  （无）');
console.log('');

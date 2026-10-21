#!/usr/bin/env node
/*! tools/page-contract.mjs —— 页面「不可动契约」抽取与校验（第十九轮起始件）
 *
 * 为什么需要它：整站艺术重构允许**任意改版面、改位置、改结构**，唯一的红线是
 * 「接口还在、能用」——也就是页面与后端、与共享脚本之间那些**机器可见的契约**：
 *   · 元素 id（脚本按 id 取元素；缺一个就是运行时 null）
 *   · 行内事件属性（onclick / onchange / onsubmit …）
 *   · 共享脚本标签（顺序敏感：nest-ui → 各库 → nest-motion → nest-particles → 模块脚本）
 *   · data-nx-* 标记（主题引导、粒子档位、导览步骤、命令面板 …）
 *   · 页面内联脚本里引用的 /api/** 端点（前端功能面）
 *
 * 用法：
 *   node tools/page-contract.mjs <file.html>                     # 打印摘要
 *   node tools/page-contract.mjs <file.html> --json out.json     # 落契约
 *   node tools/page-contract.mjs --all --outdir target/page-contracts
 *   node tools/page-contract.mjs <new.html> --verify old.json    # 重构后校验（缺项 exit 1）
 *
 * 只读：从不修改任何页面。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const STATIC = path.join(ROOT, 'src', 'main', 'resources', 'static');

const args = process.argv.slice(2);
const flag = (n) => args.includes('--' + n);
const opt = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 && args[i + 1] ? args[i + 1] : d; };

function analyse(file) {
  const html = fs.readFileSync(file, 'utf8');
  const name = path.basename(file);
  const ids = [...new Set([...html.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]))].sort();
  const handlers = [...new Set([...html.matchAll(/\bon([a-z]+)="([^"]*)"/g)].map((m) => m[1] + '=' + m[2].replace(/\s+/g, ' ').trim()))].sort();
  const dataAttrs = [...new Set([...html.matchAll(/\b(data-nx-[a-z-]+)="([^"]*)"/g)].map((m) => m[1] + '=' + m[2]))].sort();
  const scripts = [];
  for (const m of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
    const attrs = m[1] || '';
    const src = (attrs.match(/src="([^"]+)"/) || [])[1] || null;
    const inline = (m[2] || '').trim();
    scripts.push({
      src,
      defer: /\bdefer\b/.test(attrs),
      type: (attrs.match(/type="([^"]+)"/) || [])[1] || null,
      inlineBytes: inline.length,
      apiRefs: [...new Set([...inline.matchAll(/['"`](\/api\/[A-Za-z0-9_\-/{}$.]*)['"`]/g)].map((x) => x[1]))].sort(),
    });
  }
  const apis = [...new Set(scripts.flatMap((s) => s.apiRefs))].sort();
  const libs = scripts.filter((s) => s.src && !s.src.includes('?')) // 共享库/脚本标签（顺序敏感）
    .map((s) => s.src);
  return {
    page: name,
    bytes: Buffer.byteLength(html),
    ids,
    handlerCount: handlers.length,
    handlers,
    dataAttrs,
    scriptTags: scripts.map((s) => ({ src: s.src, defer: s.defer, type: s.type, inlineBytes: s.inlineBytes })),
    srcOrder: libs,
    apis,
  };
}

function summary(c) {
  const out = [];
  out.push(`${c.page}  ${c.bytes} bytes`);
  out.push(`  ids           ${c.ids.length}`);
  out.push(`  inline handlers ${c.handlerCount}`);
  out.push(`  data-nx-*     ${c.dataAttrs.length}`);
  out.push(`  script tags   ${c.scriptTags.length} (inline-script bytes: ${c.scriptTags.reduce((a, s) => a + s.inlineBytes, 0)})`);
  out.push(`  shared srcs   ${c.srcOrder.length}: ${c.srcOrder.map((s) => s.replace(/^\/(js|libs)\//, '')).join(' → ')}`);
  out.push(`  api refs      ${c.apis.length}${c.apis.length ? ': ' + c.apis.join(' ') : ''}`);
  return out.join('\n');
}

function verify(oldPath, newFile) {
  const old = JSON.parse(fs.readFileSync(oldPath, 'utf8'));
  const now = analyse(newFile);
  const missing = (a, b) => a.filter((x) => !b.includes(x));
  const problems = [];
  const mid = missing(old.ids, now.ids);
  const mh = missing(old.handlers, now.handlers);
  const ma = missing(old.apis, now.apis);
  const ms = old.srcOrder.filter((s) => !now.srcOrder.includes(s));
  const md = missing(old.dataAttrs, now.dataAttrs);
  if (mid.length) problems.push('missing ids (' + mid.length + '): ' + mid.join(', '));
  if (mh.length) problems.push('missing inline handlers (' + mh.length + '): ' + mh.slice(0, 8).join(' | '));
  if (ma.length) problems.push('missing api refs (' + ma.length + '): ' + ma.join(', '));
  if (ms.length) problems.push('missing shared script tags (' + ms.length + '): ' + ms.join(', '));
  if (md.length) problems.push('missing data-nx-* (' + md.length + '): ' + md.join(', '));
  const inlineBefore = old.scriptTags.reduce((a, s) => a + s.inlineBytes, 0);
  const inlineAfter = now.scriptTags.reduce((a, s) => a + s.inlineBytes, 0);
  if (inlineAfter < inlineBefore * 0.6) problems.push(`inline script shrank: ${inlineBefore} -> ${inlineAfter} bytes (behaviour may have been dropped)`);
  console.log(`verify ${old.page} -> ${now.page}  (${old.bytes} -> ${now.bytes} bytes)`);
  if (!problems.length) { console.log('  OK  contract intact'); return 0; }
  problems.forEach((p) => console.log('  DRIFT  ' + p));
  return 1;
}

if (flag('all')) {
  const outdir = path.join(ROOT, opt('outdir', 'target/page-contracts'));
  fs.mkdirSync(outdir, { recursive: true });
  const pages = fs.readdirSync(STATIC).filter((f) => f.endsWith('.html')).sort();
  let bad = 0;
  for (const p of pages) {
    const c = analyse(path.join(STATIC, p));
    fs.writeFileSync(path.join(outdir, p.replace(/\.html$/, '.json')), JSON.stringify(c, null, 2));
    const noIds = c.ids.length === 0;
    if (noIds) { bad++; }
    console.log(summary(c).split('\n')[0] + (noIds ? '   ← no ids' : ''));
  }
  console.log(`\nwrote ${pages.length} contracts to ${path.relative(ROOT, outdir)}`);
  process.exit(0);
}

const positional = args.filter((a) => !a.startsWith('--') && !['outdir'].includes(a) && args[args.indexOf(a) - 1] !== '--outdir');
const target = positional[0];
if (!target) { console.error('usage: node tools/page-contract.mjs <file.html> [--json out.json] | --all [--outdir dir] | <new.html> --verify old.json'); process.exit(2); }
const abs = path.isAbsolute(target) ? target : path.join(ROOT, target);

if (flag('verify')) {
  const oldPath = path.isAbsolute(opt('verify', '')) ? opt('verify', '') : path.join(ROOT, opt('verify', ''));
  process.exit(verify(oldPath, abs));
}

const c = analyse(abs);
console.log(summary(c));
const jf = opt('json', null);
if (jf) {
  const p = path.isAbsolute(jf) ? jf : path.join(ROOT, jf);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(c, null, 2));
  console.log(`\nwrote ${path.relative(ROOT, p)}`);
}

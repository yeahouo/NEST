#!/usr/bin/env node
/*! tools/redesign-shell.mjs —— 第十九轮「逐页重做」的机械换壳（默认 dry-run）

为什么这样重做：整站 19 个页面共用同一套「顶栏 + 刊头」结构（第十二轮统一外形时留下的），
所以「逐页重做」的第一步不是逐页手写，而是把这层结构**机械**换成第十九轮的设计语言
（daisyUI 4 的 navbar 语言 + 编辑部的等宽小标 + 发丝线），同时**一个字都不碰**页面里的行为脚本。

这一轮只动三处，且**不删除顶栏里的任何内容**：
  ① <header> 开标签的 class → navbar 语言（**其余属性原样保留**）
  ② 品牌包层与右侧操作包层的 class → navbar-start / navbar-end
  ③ 顶栏标题若是 学工平台品牌（h2 文本含 "学工平台"）→ .nx-brand；刊头小标插在第一个内容
     <h1> 之前（页内没有 h1 就插在 <main> 之后）

红线（每次运行都断言，任一条不过整页拒绝写盘）：
  · id 集合前后完全一致（顶栏里可能藏 id 和输入框，绝不能丢）
  · 行内事件 onclick/onchange… 出现次数一致 —— **含顶栏内部单独再数一遍**
  · <script> / data-nx-* / <header> / <input> 计数一致
  · 新顶栏确实带上了 navbar 类，且原顶栏里的每个 <button> 逐字出现在结果里

走过的坑（2026-10-16 第一版）：
  · 第一版把顶栏**拆开重拼**（提取按钮再拼新 header）：18 页全 FAIL —— 多行按钮重新缩进后不再
    逐字相等，而且顶栏里 button 以外的元素（含 id="who"）被整块丢掉。现在只改 class、不搬内容。
  · teacher-course-detail 的品牌 <h2> 自己带 onclick（点标题回工作台）；第三版把 h2 开标签整个
    换掉时**吃掉了这个 onclick**。现在 h2 也只换 class、保留其余属性（这条正是「行内事件计数」
    断言抓出来的：161 → 160）。

用法：
  node tools/redesign-shell.mjs                          # dry-run，逐页打印将发生什么
  node tools/redesign-shell.mjs --apply                  # 先整目录备份到 target/backup-shell/，再写
  node tools/redesign-shell.mjs --only admin-users.html  # 只处理一页
*/
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const STATIC = path.join(ROOT, 'src', 'main', 'resources', 'static');
const BACKUP = path.join(ROOT, 'target', 'backup-shell');
const REPORT = path.join(ROOT, 'target', 'shell-report.json');

const args = process.argv.slice(2);
const APPLY = args.includes('--apply');
const only = (() => { const i = args.indexOf('--only'); return i >= 0 ? args[i + 1] : null; })();

/* 不进机械换壳的页面（每个都写明理由；art/login/dashboard 是已完成的样板与概念页） */
const SKIP = {
  'art.html': '第十五轮艺术概念页（独立样式，不在产品导航里）',
  'login.html': '第十八轮样板页 1，已整页重画',
  'dashboard.html': '第十八轮样板页 2，已整页重画',
  'dorm.html': '自带内联 <style>、不引 nest.css ⇒ nx-* 类在这页不生效，列入单页精做',
};

/* 刊头小标的文案：只描述「角色 / 页面」，不编内容 */
const KICKER = {
  'index.html': 'Overview / Landing',
  'settings.html': 'Account / Settings',
  'register.html': 'Account / Register',
  'reset-password.html': 'Account / Reset',
  'forgot-password.html': 'Account / Recovery',
  'dorm-portal.html': 'Residence / Student portal',
  'dorm-admin.html': 'Residence / Admin console',
  'dorm-psyche.html': 'Residence / Psychometrics',
  'admin-dashboard.html': 'Admin / Overview',
  'admin-users.html': 'Admin / Users',
  'admin-invitation-codes.html': 'Admin / Invitation codes',
  'teacher-dashboard.html': 'Teacher / Dashboard',
  'under-development.html': null, // 占位页，不加
};

const HEADER_CLASS = 'nx-topbar navbar nx-glass nx-hairline-b sticky top-0 z-50 min-h-0 gap-2 justify-between rounded-none px-6 py-2 md:px-20';
const BRAND_START = 'navbar-start w-auto min-w-0 flex-1 items-center gap-3';
const BRAND_END = 'navbar-end w-auto flex-none items-center gap-1 md:gap-2';

const countAll = (hay, re) => [...hay.matchAll(re)].length;
const idSet = (html) => [...new Set([...html.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]))].sort();
/** 把开标签里的 class 换成 next，其余属性原样保留（顶栏与品牌 h2 都用它，别整个换开标签） */
const swapClass = (tag, next) => {
  if (!/\bclass="/.test(tag)) return tag.replace(/^<([a-zA-Z0-9]+)/, `<$1 class="${next}"`);
  return tag.replace(/\s*class="[^"]*"/, ` class="${next}"`);
};

/** 顶栏换壳：只改 class，内容一律不动（顶栏里可能藏输入框、id、data-nx-* 标记） */
function restyleHeader(block) {
  let out = block;
  const notes = [];

  // ① header 开标签：换 class，保留其它属性
  out = out.replace(/<header\b([^>]*)>/, (mm, attrs) => {
    const rest = attrs.replace(/\s*class="[^"]*"/, '').replace(/\s+$/, '');
    return `<header class="${HEADER_CLASS}"${rest}>`;
  });

  // ② 品牌包层（顶栏里第一个 flex + items-center + gap-3 容器）→ navbar-start
  let hitBrand = false;
  out = out.replace(/class="(flex[^"]*\bitems-center\b[^"]*\bgap-3\b[^"]*)"/, () => {
    hitBrand = true;
    return `class="${BRAND_START}"`;
  });
  if (!hitBrand) notes.push('未找到品牌包层（顶栏结构不同）');

  // ③ 顶栏标题：只有它确实是品牌（含 "学工平台"）才换成 .nx-brand；否则原样留着
  const h2 = out.match(/<h2\b[^>]*>[\s\S]*?<\/h2>/);
  let h2Text = '';
  if (h2) {
    h2Text = h2[0].replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
    if (/(学工平台|CodeClass)/i.test(h2Text)) {
      const newH2 = h2[0].replace(/<h2\b[^>]*>/, (tag) => swapClass(tag, 'nx-brand'));
      out = out.replace(h2[0], newH2);
    } else {
      notes.push(`顶栏标题不是品牌（"${h2Text.slice(0, 20)}"，当页名用，未改）`);
    }
  } else {
    notes.push('顶栏里没有 h2');
  }

  // ④ 右侧操作包层：品牌标题之后第一个 flex + items-center + gap-N 容器 → navbar-end
  const anchor = h2 ? (out.includes(h2[0]) ? h2[0] : h2[0].replace(/<h2\b[^>]*>/, (tag) => swapClass(tag, 'nx-brand'))) : null;
  const from = anchor ? out.indexOf(anchor) + anchor.length : 0;
  const tail = out.slice(from);
  let hitNav = false;
  const tail2 = tail.replace(/class="(flex[^"]*\bitems-center\b[^"]*\bgap-[0-9][^"]*)"/, () => {
    hitNav = true;
    return `class="${BRAND_END}"`;
  });
  if (hitNav) out = out.slice(0, from) + tail2;
  else notes.push('未找到右侧操作包层（顶栏结构不同）');

  return { html: out, notes };
}

/** 刊头小标：插在第一个内容 <h1> 之前；没有 h1 就插在 <main> 之后（管理员页的页名在顶栏里） */
function insertKicker(html, text) {
  const h1 = html.match(/<h1\b[^>]*>/);
  if (h1) {
    const indent = (html.slice(0, h1.index).match(/\n([ \t]*)$/) || [, ''])[1];
    return { html: html.replace(h1[0], `${indent}<p class="nx-kicker">${text}</p>\n${indent}${h1[0]}`), where: '<h1> 之前' };
  }
  const main = html.match(/<main\b[^>]*>\s*\n/);
  if (main) {
    const indent = ' '.repeat((main[0].split('\n')[1] || '').match(/^[ \t]*/)[0].length);
    return { html: html.replace(main[0], `${main[0]}${indent}<p class="nx-kicker">${text}</p>\n`), where: '<main> 之后' };
  }
  return { html, where: '未插（页内既无 <h1> 也无 <main>）' };
}

function processPage(name) {
  if (SKIP[name]) return { name, status: 'SKIP', why: SKIP[name] };

  const file = path.join(STATIC, name);
  const before = fs.readFileSync(file, 'utf8');
  const headerRe = /<header\b[^>]*>[\s\S]*?<\/header>/;
  const m = before.match(headerRe);

  let out = before;
  const notes = [];
  if (m) {
    const r = restyleHeader(m[0]);
    out = before.replace(headerRe, r.html);
    notes.push(...r.notes);
  } else {
    notes.push('没有 <header> 块（只插刊头小标）');
  }

  const kicker = KICKER[name];
  let kickerNote = '(不加)';
  if (kicker) {
    const k = insertKicker(out, kicker);
    out = k.html;
    kickerNote = `${kicker} → ${k.where}`;
  }

  // ── 红线断言 ────────────────────────────────────────────────
  const problems = [];
  const idsBefore = idSet(before), idsAfter = idSet(out);
  if (idsBefore.join(',') !== idsAfter.join(',')) {
    problems.push(`id 集合变了（丢:${idsBefore.filter((x) => !idsAfter.includes(x)).join(',') || '无'} 增:${idsAfter.filter((x) => !idsBefore.includes(x)).join(',') || '无'}）`);
  }
  for (const re of [/\bon[a-z]+="/g, /<script\b/gi, /\bdata-nx-[a-z-]+="/g, /<header\b/g, /<input\b/gi]) {
    const a = countAll(before, re), b = countAll(out, re);
    if (a !== b) problems.push(`${re} 数量 ${a} → ${b}`);
  }
  if (m) {
    // 顶栏内部单独再数一遍：丢的若是顶栏里的 onclick（点标题跳转之类），这里给出更准的定位
    const hb = countAll(m[0], /\bon[a-z]+="/g), ha = countAll((out.match(headerRe) || [''])[0], /\bon[a-z]+="/g);
    if (hb !== ha) problems.push(`顶栏内行内事件 ${hb} → ${ha}（属性被整块换掉了？）`);
    if (!/class="[^"]*\bnavbar\b/.test((out.match(headerRe) || [''])[0])) problems.push('新顶栏没带上 navbar');
    if ((out.match(headerRe) || [''])[0] === m[0]) problems.push('顶栏没有发生变化');
    for (const btn of [...m[0].matchAll(/<button\b[\s\S]*?<\/button>/g)].map((x) => x[0])) {
      if (!out.includes(btn)) problems.push('有按钮没有逐字保留');
    }
  }
  if (out === before) problems.push('整页没有发生变化');

  const h2 = ((m || [''])[0].match(/<h2\b[^>]*>[\s\S]*?<\/h2>/) || [''])[0];
  return {
    name, status: problems.length ? 'FAIL' : 'OK', problems, notes,
    brand: h2.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim() || '(无品牌)',
    buttons: countAll((m || [''])[0], /<button\b/g),
    kicker: kickerNote, delta: out.length - before.length, out,
  };
}

const pages = fs.readdirSync(STATIC)
  .filter((f) => f.endsWith('.html'))
  .filter((f) => !only || f === only)
  .sort();

let ok = 0, fail = 0, skip = 0;
const results = [];
for (const name of pages) {
  const r = processPage(name);
  results.push(r);
  if (r.status === 'OK') ok++; else if (r.status === 'FAIL') fail++; else skip++;
  if (r.status === 'OK') {
    console.log(`OK   ${name.padEnd(28)} brand=${r.brand.slice(0, 24).padEnd(24)} btn=${String(r.buttons).padEnd(2)} kicker=${r.kicker.padEnd(34)} ${r.delta >= 0 ? '+' : ''}${r.delta}B`);
    for (const n of r.notes) console.log(`       note: ${n}`);
  } else {
    console.log(`${r.status} ${name.padEnd(28)} ${r.why || r.problems.join('; ')}`);
    for (const n of r.notes || []) console.log(`       note: ${n}`);
  }
}

console.log(`\n汇总：OK ${ok} / FAIL ${fail} / SKIP ${skip}（共 ${pages.length} 页）`);
fs.writeFileSync(REPORT, JSON.stringify(results.map(({ out, ...r }) => r), null, 2), 'utf8');
console.log(`报告：${path.relative(ROOT, REPORT)}`);

if (APPLY) {
  const good = results.filter((r) => r.status === 'OK');
  if (!good.length) { console.log('没有可写的页面，未改动任何文件。'); process.exit(fail ? 1 : 0); }
  fs.mkdirSync(BACKUP, { recursive: true });
  for (const r of good) {
    fs.copyFileSync(path.join(STATIC, r.name), path.join(BACKUP, r.name));
    fs.writeFileSync(path.join(STATIC, r.name), r.out, 'utf8');
  }
  console.log(`已写入 ${good.length} 页；备份在 ${path.relative(ROOT, BACKUP)}\\`);
  console.log('下一步：逐页 node tools/page-contract.mjs <page> --verify target/page-contracts/<page>.json');
} else {
  console.log('这是 dry-run。加 --apply 才会写盘（写前整目录备份到 target\\backup-shell\\）。');
}
process.exit(fail ? 1 : 0);

#!/usr/bin/env node
/**
 * strip-legacy-particles.mjs —— 删掉被 canvas 粒子层取代的「遗留 CSS 粒子」块。
 *
 * 背景：早先每个页面都手工写了一批 <div class="particle ..."> 各自挂 float-fast 无限关键帧
 * （门口页 3–5 秒一轮、工作页 12.5–20 秒一轮，一个页面 4–16 个元素）。现在全站换成
 * /js/nest-particles.js 的单个 canvas 层（fpsLimit 60、pauseOnBlur、pauseOnOutsideViewport、
 * 尊重 prefers-reduced-motion），这批 DOM 动画就既多余又会和 canvas 叠在一起，所以移除。
 *
 * 删什么：
 *   1) 标记注释 `<!-- Dynamic Particle Background Container -->` 到它包裹的那个容器 `</div>`；
 *   2) 同一页内联 <style> 里随之失效的 `.particle { … }` 规则与 `@keyframes float-fast { … }`。
 * 不删什么：`#polygon-container` 里的 SVG 多边形（它们用 drift-rotate，不在本轮范围）。
 *
 * 用法：
 *   node tools/strip-legacy-particles.mjs              # dry-run，只打印计划
 *   node tools/strip-legacy-particles.mjs --apply      # 写盘（先把整份 static 备份到 target/backup-particles-legacy/）
 *   node tools/strip-legacy-particles.mjs --file=settings.html
 *
 * 安全：每页的「删掉的 .particle 行数」必须与下表登记的数字完全相符，任何一处不符就整体不写盘（exit 1）。
 *   删完还会断言 .particle / float-fast 归零、polygon-container 与 drift-rotate 仍在。
 */

import fs from 'node:fs';
import path from 'node:path';

const STATIC_DIR = path.resolve('src/main/resources/static');
const BACKUP_DIR = path.resolve('target/backup-particles-legacy');
const MARKER = 'Dynamic Particle Background Container';
const CONTAINER = '<div class="fixed inset-0 overflow-hidden pointer-events-none z-0">';

/* 表：文件名 → 预期删掉的 .particle 元素个数（普查所得，对不上就不写盘） */
const TARGETS = [
  { file: 'index.html', divs: 16 },
  { file: 'register.html', divs: 16 },
  { file: 'forgot-password.html', divs: 16 },
  { file: 'reset-password.html', divs: 16 },
  { file: 'under-development.html', divs: 16 },
  { file: 'course-detail.html', divs: 8 },
  { file: 'settings.html', divs: 12 },
  { file: 'teacher-course-detail.html', divs: 4 },
  { file: 'teacher-dashboard.html', divs: 6 },
];

const argv = process.argv.slice(2);
const apply = argv.includes('--apply');
const only = (argv.find((a) => a.startsWith('--file=')) || '').slice(7);

const countOf = (text, re) => (text.match(re) || []).length;

/** 从 start 行起按花括号配平找规则结束行（返回结束行下标，未配平返回 -1）。 */
function braceEnd(lines, start) {
  let depth = 0;
  for (let i = start; i < lines.length; i++) {
    depth += countOf(lines[i], /\{/g) - countOf(lines[i], /\}/g);
    if (depth === 0 && i > start) return i;
    if (depth < 0) return -1;
  }
  return -1;
}

/** 删掉「以 headTrim 开头、花括号配平」的一整条 CSS 规则，连带它前面的空行。 */
function cutRule(lines, headTrim) {
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].trim() !== headTrim) continue;
    const end = braceEnd(lines, i);
    if (end < 0) return { lines, removed: null, reason: `unbalanced ${headTrim}` };
    let from = i;
    while (from > 0 && lines[from - 1].trim() === '') from--;
    return { lines: lines.slice(0, from).concat(lines.slice(end + 1)), removed: `${from + 1}-${end + 1}`, reason: null };
  }
  return { lines, removed: null, reason: `not found: ${headTrim}` };
}

function plan(target) {
  const abs = path.join(STATIC_DIR, target.file);
  if (!fs.existsSync(abs)) return { file: target.file, missing: true };
  const before = fs.readFileSync(abs, 'utf8');
  const eol = before.includes('\r\n') ? '\r\n' : '\n';
  let lines = before.split(/\r?\n/);
  const problems = [];

  /* 1) 遗留粒子 DOM 块 */
  const m = lines.findIndex((l) => l.includes(MARKER));
  let blockInfo = 'none';
  let divs = 0;
  let tail = '';
  if (m >= 0) {
    if (lines[m + 1]?.trim() !== CONTAINER) {
      problems.push(`marker at ${m + 1} not followed by the particle container`);
    } else {
      let j = -1;
      for (let i = m + 2; i < lines.length; i++) {
        const t = lines[i].trim();
        /* 收尾的 </div> 有时与 #polygon-container 挤在同一行：只删前半个，后半行留着 */
        if (t.startsWith('</div>')) {
          j = i;
          tail = lines[i].slice(lines[i].indexOf('</div>') + 6).trim();
          break;
        }
        if (t === '') continue;
        if (t.startsWith('<!--')) continue;
        if (!t.includes('class="particle')) { problems.push(`unexpected line inside block: ${i + 1}: ${t.slice(0, 70)}`); j = -1; break; }
        divs++;
      }
      if (j < 0 && problems.length === 0) problems.push('no closing </div> found for the particle block');
      if (j >= 0 && problems.length === 0) {
        blockInfo = `${m + 1}-${j + 1}${tail ? ' (split line)' : ''}`;
        lines = lines.slice(0, m).concat(tail ? [tail] : []).concat(lines.slice(j + 1));
      }
    }
  }

  /* 2) 随之失效的内联 CSS（.particle 规则 + float-fast 关键帧） */
  const markerAbsent = m < 0;
  const cutParticle = cutRule(lines, '.particle {');
  if (cutParticle.reason && !markerAbsent) problems.push(`css: ${cutParticle.reason}`);
  lines = cutParticle.lines;
  const cutFloat = cutRule(lines, '@keyframes float-fast {');
  if (cutFloat.reason && !markerAbsent) problems.push(`css: ${cutFloat.reason}`);
  lines = cutFloat.lines;

  /* 3) 收掉多出来的空行 */
  let text = lines.join(eol).replace(new RegExp(`(${eol === '\r\n' ? '\\r\\n' : '\\n'}){3,}`, 'g'), eol + eol);

  if (!markerAbsent && divs !== target.divs) problems.push(`expected ${target.divs} .particle divs, removed ${divs}`);

  const after = text;
  const checks = {
    particleLeft: countOf(after, /class="particle[ "]/g),
    floatLeft: countOf(after, /float-fast/g),
    polygon: countOf(after, /id="polygon-container"/g),
    drift: countOf(after, /@keyframes drift-rotate/g),
  };
  if (checks.particleLeft !== 0) problems.push(`.particle left = ${checks.particleLeft}`);
  if (checks.floatLeft !== 0) problems.push(`float-fast left = ${checks.floatLeft}`);
  if (checks.polygon !== 1) problems.push(`polygon-container = ${checks.polygon} (expected 1)`);
  if (checks.drift !== 1) problems.push(`@keyframes drift-rotate = ${checks.drift} (expected 1)`);

  /* 已经清理过的页面（重跑）：标记、规则、元素都不在，且断言都成立 ⇒ 算 done，不算故障 */
  const done = m < 0 && !cutParticle.removed && !cutFloat.removed && problems.length === 0;

  return {
    file: target.file, abs, before, after, eol, divs, done,
    blockInfo, cssInfo: `particle ${cutParticle.removed || '-'} / float-fast ${cutFloat.removed || '-'}`,
    problems, changed: after !== before,
  };
}

const targets = TARGETS.filter((t) => !only || t.file === only).map(plan);
console.log(`strip-legacy-particles: ${apply ? 'APPLY' : 'dry-run'}  (${targets.length} page(s))`);
let bad = 0;
let changed = 0;
for (const t of targets) {
  if (t.missing) { console.log(`  ${t.file.padEnd(26)} MISSING`); bad++; continue; }
  if (t.problems.length) {
    console.log(`  ${t.file.padEnd(26)} BLOCKED  ${t.problems.join(' | ')}`);
    bad++;
    continue;
  }
  changed += t.changed ? 1 : 0;
  const state = t.changed ? 'strip' : (t.done ? 'done(already clean)' : 'clean');
  console.log(`  ${t.file.padEnd(26)} ${state.padEnd(20)} divs=${String(t.divs).padStart(2)} block=${t.blockInfo.padEnd(11)} css=${t.cssInfo.padEnd(22)} ${t.before.length} -> ${t.after.length} bytes`);
}

if (bad) {
  console.log(`\n${bad} page(s) blocked — nothing written.`);
  process.exit(1);
}
if (!apply) {
  console.log(`\n${changed} page(s) would change. Re-run with --apply to write.`);
  process.exit(0);
}

fs.mkdirSync(BACKUP_DIR, { recursive: true });
let written = 0;
for (const t of targets) {
  if (!t.changed) continue;
  fs.copyFileSync(t.abs, path.join(BACKUP_DIR, t.file));
  fs.writeFileSync(t.abs, t.after, 'utf8');
  written++;
}
console.log(`\n${written} page(s) written; backups in ${BACKUP_DIR}`);

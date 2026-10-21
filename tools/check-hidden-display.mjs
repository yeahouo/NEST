// 静态检查：同一个 class 属性里同时出现 `hidden` 与「会设置 display 的自定义类」时，
// 谁生效取决于 CSS 里的先后顺序与优先级 —— 这正是 2026-10-13 那次
// 「教师一进课程详情就弹出测验编辑器」的根因：
//   .hidden{display:none} 排在 .nx-qed{display:flex} 之前，两者同为 0,1,0，后者赢。
//
// 规则：只要某个 class 属性里同时有 hidden 和一个「在 nest.css 里声明了 display 且值不是 none」
// 的类，就报出来。修法是给那个组合加一条更高优先级的选择器（例如 `.nx-qed.hidden{display:none}`）。
//
// 用法：node tools/check-hidden-display.mjs            # 扫描 src/main/resources/static/*.html
//       node tools/check-hidden-display.mjs --json     # 输出 JSON

import { readFileSync, readdirSync } from 'node:fs';
import { join, basename } from 'node:path';

const ROOT = process.cwd();
const STATIC = join(ROOT, 'src', 'main', 'resources', 'static');
const CSS = join(STATIC, 'css', 'nest.css');
const jsonOnly = process.argv.includes('--json');

// ── 1. 从编译后的 nest.css 里取出「单类选择器 + display: 非 none」的类名 ──────────
// 只认形如 .foo{...} / .foo,.bar{...} 的规则；带组合器（.a .b）、伪类（.a:hover）、
// 媒体查询里的规则不参与判定 —— 那些本来就有额外优先级。
const css = readFileSync(CSS, 'utf8');

function stripMediaQueries(text) {
  // 去掉 @media / @supports / @keyframes 包裹的内容，只保留顶层规则
  let out = '';
  let i = 0;
  while (i < text.length) {
    const at = text.indexOf('@', i);
    if (at < 0) {
      out += text.slice(i);
      break;
    }
    out += text.slice(i, at);
    let j = text.indexOf('{', at);
    if (j < 0) break;
    // 找匹配的右花括号
    let depth = 1;
    let k = j + 1;
    while (k < text.length && depth > 0) {
      if (text[k] === '{') depth++;
      else if (text[k] === '}') depth--;
      k++;
    }
    i = k;
  }
  return out;
}

const flat = stripMediaQueries(css);
const displayClasses = new Map(); // 类名 -> { display, index }
// 顶层里 .hidden 自身的位置：只有排在它**后面**的同类选择器才会把它盖掉
const hiddenIndex = flat.indexOf('.hidden{display:none}');
if (hiddenIndex < 0) {
  console.error('nest.css 里找不到 .hidden{display:none} —— 检查方式需要更新');
  process.exit(2);
}
for (const m of flat.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
  const selector = m[1].trim();
  const body = m[2];
  const dm = body.match(/(?:^|;)display:([^;]+)/);
  if (!dm) continue;
  const value = dm[1].trim();
  if (value === 'none') continue;
  for (const part of selector.split(',')) {
    const s = part.trim();
    if (!/^\.[A-Za-z0-9_-]+$/.test(s)) continue;
    const name = s.slice(1);
    // 取该规则在整个文件里的位置（规则文本在 flat 里唯一）
    const at = flat.indexOf(m[0]);
    const prev = displayClasses.get(name);
    if (!prev || at > prev.index) displayClasses.set(name, { display: value, index: at });
  }
}
const riskyClasses = new Set(
  [...displayClasses.entries()]
    .filter(([name, v]) => name !== 'hidden' && v.index > hiddenIndex)
    .map(([name]) => name),
);

// ── 2. 扫描页面：同一个 class 属性里 hidden + 上述类 ─────────────────────────
const pages = readdirSync(STATIC).filter((f) => f.endsWith('.html')).sort();
const findings = [];
for (const page of pages) {
  const text = readFileSync(join(STATIC, page), 'utf8');
  const lines = text.split(/\r?\n/);
  lines.forEach((line, idx) => {
    for (const m of line.matchAll(/class="([^"]*)"/g)) {
      const tokens = m[1].split(/\s+/).filter(Boolean);
      if (!tokens.includes('hidden')) continue;
      for (const t of tokens) {
        if (t === 'hidden') continue;
        if (!riskyClasses.has(t)) continue;
        findings.push({
          page,
          line: idx + 1,
          cls: t,
          display: displayClasses.get(t).display,
          attr: m[1],
        });
        break;
      }
    }
  });
}

// ── 3. 输出 ──────────────────────────────────────────────────────────────────
if (jsonOnly) {
  console.log(JSON.stringify({ displayClasses: displayClasses.size, findings }, null, 2));
} else {
  console.log(`nest.css 里「会设置 display 且非 none」的单类选择器：${displayClasses.size} 个，`
    + `其中排在 .hidden 之后的（会把它盖掉）：${riskyClasses.size} 个`);
  if (riskyClasses.size) {
    console.log(`  ${[...riskyClasses].map((c) => `.${c}`).join(' ')}`);
  }
  console.log(`扫描页面：${pages.length} 个`);
  if (findings.length === 0) {
    console.log('OK - 没有发现 hidden 与 display 类互相打架的写法');
  } else {
    console.log(`注意 - 发现 ${findings.length} 处 hidden 可能被盖掉：`);
    for (const f of findings) {
      console.log(`  ${f.page}:${f.line}  .${f.cls} (display:${f.display})  <-- class="${f.attr.slice(0, 80)}"`);
    }
  }
}
process.exit(findings.length === 0 ? 0 : 1);

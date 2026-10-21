/*
 * 共享脚本里拼出来的 class，是否真的进了编译出来的 CSS。
 *
 * 为什么需要这个检查：
 *   Tailwind 是「扫到才生成」的 —— 页面里没写过的类名，不管你在 input.css 里写得多认真，
 *   都会被当成没用到的组件删掉。而交互层的 DOM 全是 nest-ui.js 现场拼的：
 *   元素加进去了、事件也通了，只有样式没生成。表现是「命令面板能打开，但以一个默认
 *   div 的样子贴在页面最底下」—— 这种坏法在截图里很难一眼看出来，必须在构建期拦住。
 *
 * 做两件事：
 *   1. 从 static/js/**\/*.js 里抽出所有被现场拼出来的类名（className= / classList /
 *      模板字符串里的 class="..."）；
 *   2. 逐个在编译产物 nest.css 里找 `.<类名>`，找不到就是漏了 —— 报错退出 1。
 *
 * 用法：node tools/webbuild/check-ui-css.mjs
 */
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const JS_DIR = join(ROOT, 'src', 'main', 'resources', 'static', 'js');
const CSS = join(ROOT, 'src', 'main', 'resources', 'static', 'css', 'nest.css');
// 手写的独立样式表（不是 Tailwind 产物）。`art.html` 那一页不引 nest.css，
// 它用的 `art-static` / `art-no-webgl` 这类状态类只存在于这里，所以一并查。
const HAND_CSS = join(ROOT, 'src', 'main', 'resources', 'static', 'css', 'nest-art.css');

if (!existsSync(CSS)) {
  console.error('找不到编译产物：' + CSS + '\n先跑 tools/webbuild/build-web-assets.ps1');
  process.exit(1);
}
if (!existsSync(JS_DIR)) {
  console.log('没有 static/js 目录，跳过。');
  process.exit(0);
}

const css = readFileSync(CSS, 'utf8');
const handCss = existsSync(HAND_CSS) ? readFileSync(HAND_CSS, 'utf8') : '';

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else if (full.endsWith('.js')) out.push(full);
  }
  return out;
}

// 只需要「类名」这一个事实，不需要解析 JS：把几种写法里出现的字符串原样捞出来。
// className 那条只取引号里的字面量 —— 它右边常常是表达式（'nx-toast ' + kind），
// 整段捞出来会把 kind / ratio 这种变量名当成类名。
const QUOTED = /['"`]([^'"`]*)['"`]/g;
const PATTERNS = [
  { re: /classList\s*\.\s*(?:add|remove|toggle)\s*\(([^)]*)\)/g, onlyQuoted: true },
  { re: /\bclassName\s*=\s*([^;\n]*)/g, onlyQuoted: true },
  { re: /setAttribute\s*\(\s*['"]class['"]\s*,\s*([^)]*)\)/g, onlyQuoted: true },
  { re: /class\s*=\s*\\?["']([^"'\\`]*)["']/g, onlyQuoted: false },
];
const TOKEN = /[A-Za-z][A-Za-z0-9_:/-]*/g;

const found = new Map(); // 类名 -> 出现在哪些文件
let scanned = 0;
for (const file of walk(JS_DIR)) {
  scanned++;
  const text = readFileSync(file, 'utf8');
  const rel = relative(ROOT, file).replace(/\\/g, '/');
  const add = raw => {
    for (const m of String(raw).matchAll(TOKEN)) {
      const name = m[0];
      // 跳过明显不是类名的东西：字符串拼接留下的半截前缀、带 ${} 的模板、路径片段
      if (name.length < 2) continue;
      if (/[-_/]$/.test(name)) continue;
      if (name.includes('://') || name.includes('.')) continue;
      if (!found.has(name)) found.set(name, new Set());
      found.get(name).add(rel);
    }
  };
  for (const { re, onlyQuoted } of PATTERNS) {
    for (const m of text.matchAll(re)) {
      if (onlyQuoted) {
        for (const q of String(m[1]).matchAll(QUOTED)) add(q[1]);
      } else {
        add(m[1]);
      }
    }
  }
}

// `dark` 这类状态类是加在 <html> 上的，同样要能查到；.dark 选择器在 CSS 里以 `.dark ` 出现。
const missing = [];
for (const [name, where] of [...found].sort()) {
  const re = new RegExp('\\.' + name.replace(/[/:]/g, '\\$&') + '(?![A-Za-z0-9_-])');
  if (!re.test(css) && !(handCss && re.test(handCss))) missing.push({ name, where: [...where].join(', ') });
}

console.log(`扫了 ${scanned} 个共享脚本、${found.size} 个脚本里拼出来的类名，在 nest.css${handCss ? ' 与 nest-art.css' : ''} 里找了一遍`);
if (missing.length) {
  console.error('\n以下类名只出现在 JS 里，编译出来的 CSS 中没有 —— 元素在、样式没生成：');
  for (const m of missing) console.error(`  · ${m.name}   (${m.where})`);
  console.error('\n修法：确认 tailwind.config.js 的 content 里包含 static/js/**/*.js；');
  console.error('      自定义类要在 input.css 里定义（且类名要原样出现在脚本里，别用字符串拼前缀）。');
  process.exit(1);
}
console.log('✓ 脚本里拼出来的类名，编译后的 CSS 里全都有');

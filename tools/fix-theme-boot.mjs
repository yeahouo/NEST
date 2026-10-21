/* 第十九轮补漏：把「data-theme 自举行」补齐到每个页面的内联主题脚本里。
 *
 * 为什么必须补：daisyUI 的整套主题变量挂在 [data-theme] 属性上（不是 .dark 类）。
 * 只有 login / dashboard / checkin 三页在第十八、十九轮手工补了这一行，其余页面
 * 要等 defer 的 js/nest-ui.js 跑到 DOMContentLoaded 里的 initTheme() 才被写上 ——
 * 也就是说深色用户会先看到一帧「daisyUI 默认浅色主题的 navbar / card / btn」再翻黑，
 * 正是各页内联脚本注释里点名要避免的那种闪白。
 *
 * 默认 dry-run；--apply 才写盘，写前把每个改动过的文件备份到 target/backup-themeboot/。
 * 用法：node tools/fix-theme-boot.mjs [--apply]
 */
import fs from 'node:fs';
import path from 'node:path';

const DIR = 'src/main/resources/static';
const APPLY = process.argv.includes('--apply');
const MARK = "localStorage.getItem('nest-theme')";
const ANCHOR = 'document.documentElement.style.colorScheme';
const LINE = "document.documentElement.setAttribute('data-theme', dark ? 'nestdark' : 'nestlight');";

// art.html 没有主题自举（整页只有自己的艺术 CSS，从不切主题），不在此工具的职责内。
const SKIP = new Set(['art.html']);

const files = fs.readdirSync(DIR).filter((f) => f.endsWith('.html')).sort();
const report = [];
let changed = 0;

for (const f of files) {
  const p = path.join(DIR, f);
  const src = fs.readFileSync(p, 'utf8');
  const before = src.length;

  if (SKIP.has(f)) { report.push({ file: f, status: 'skip-conceptual' }); continue; }
  if (!src.includes(MARK)) { report.push({ file: f, status: 'no-bootstrap' }); continue; }
  if (src.includes("setAttribute('data-theme'")) { report.push({ file: f, status: 'already' }); continue; }

  let out = src;

  // ① <html> 标签上先写一个浅色默认值（和 login.html 一样），JS 起来后再按需改成深色。
  const htmlTag = out.match(/<html\b[^>]*>/);
  if (!htmlTag) { report.push({ file: f, status: 'problem', note: '找不到 <html> 标签' }); continue; }
  if (!/data-theme=/.test(htmlTag[0])) {
    const next = htmlTag[0].replace(/^<html\b/, '<html data-theme="nestlight"');
    out = out.replace(htmlTag[0], next);
  }

  // ② 自举行插在 colorScheme 之前：此时 `dark` 已经算好，且样式表还没开始加载。
  const idx = out.indexOf(ANCHOR);
  if (idx < 0) { report.push({ file: f, status: 'problem', note: '找不到 colorScheme 锚点' }); continue; }
  const lineStart = out.lastIndexOf('\n', idx) + 1;
  const indent = (out.slice(lineStart, idx).match(/^\s*/) || [''])[0];
  if (!/var\s+dark\s*=/.test(out.slice(0, idx))) {
    report.push({ file: f, status: 'problem', note: 'dark 变量不在锚点之前' });
    continue;
  }
  out = out.slice(0, lineStart) + indent + LINE + '\n' + out.slice(lineStart);

  report.push({ file: f, status: 'fixed', htmlTag: htmlTag[0].trim(), delta: out.length - before });
  changed++;

  if (APPLY) {
    fs.mkdirSync('target/backup-themeboot', { recursive: true });
    fs.copyFileSync(p, path.join('target/backup-themeboot', f));
    fs.writeFileSync(p, out);
  }
}

for (const r of report) {
  const note = r.note ? '  <- ' + r.note : '';
  const tag = r.htmlTag ? '  ' + r.htmlTag : '';
  console.log(r.status.padEnd(17) + r.file.padEnd(32) + (r.delta ? '+' + r.delta + ' B' : '') + tag + note);
}
fs.writeFileSync('target/theme-boot-report.json', JSON.stringify({ apply: APPLY, changed, report }, null, 2));
console.log((APPLY ? 'APPLIED ' : 'DRY-RUN ') + changed + ' / ' + files.length + ' pages changed.  report -> target/theme-boot-report.json');

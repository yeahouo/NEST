// 把共享交互层（js/nest-ui.js）接到所有静态页面上。
//
// 只做四件事，而且都是「页面原来没有才加」：
//   1. <head> 里、样式表之前，插入主题自举脚本（避免暗色用户先看到一帧白底）
//   2. 在第一个外链样式表之前插入 Radix 色阶（/libs/radix-colors/colors.css）——
//      nest.css 里的 --nx-* 令牌全部引用它的 --indigo-*/--slate-* 等变量，少这一张
//      样式表，整站的品牌色与中性色都会退回浏览器默认值
//   3. </body> 之前插入 <script defer src="/js/nest-ui.js"></script>
//   4. 只有「页面自己标了 data-nx-tour」的页面，才再插一个
//      <script defer src="/js/nest-tour.js"></script> —— 没标引导步骤的页面
//      连 driver.js 都不会去下载（26 KB 没必要发给用不到的页面）
//
// 页面上「哪个按钮该进命令面板」「哪张卡片该渐入」「哪几步算新手引导」这种决定不改 ——
// 那些是页面自己的事，由页面上的 data-nx-* 标记表达（没标记就没有，不会凭空长出来）。
//
// 用法：node tools/wire-nest-ui.mjs          实写
//       node tools/wire-nest-ui.mjs --dry    只打印会改什么
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const DIR = 'src/main/resources/static';
const dry = process.argv.includes('--dry');

const BOOT = [
  '  <!--',
  '    主题必须在样式表之前定下来。放到 defer 的共享脚本里会先按浅色画一帧、',
  '    再翻成深色 —— 也就是俗称的「闪白」，比没有深色模式更难受。',
  '    所以这段必须内联，且必须排在 <link rel="stylesheet"> 前面。',
  '  -->',
  '  <script>',
  '    (function () {',
  '      try {',
  "        var saved = localStorage.getItem('nest-theme');",
  '        var dark = saved ? saved === \'dark\' : window.matchMedia(\'(prefers-color-scheme: dark)\').matches;',
  "        if (dark) { document.documentElement.classList.add('dark'); }",
  "        document.documentElement.style.colorScheme = dark ? 'dark' : 'light';",
  '      } catch (err) { /* 隐私模式下 localStorage 会抛异常，跟随系统即可 */ }',
  '    })();',
  '  </script>',
];

const RADIX_LINK = '  <link rel="stylesheet" href="/libs/radix-colors/colors.css">';
const SCRIPT_TAG = '  <script defer src="/js/nest-ui.js"></script>';
const TOUR_TAG = '  <script defer src="/js/nest-tour.js"></script>';

// 判断一行是不是「真的外链样式表」。必须带 href —— 上面 BOOT 注释里那句
// 「还是排在 <link rel="stylesheet"> 前面」也含 rel="stylesheet"，早期版本的锚点正则
// 被它骗过一次，把 radix 链接插进了 HTML 注释里，等于没插（页面照样没有色阶令牌）。
const isStylesheetLine = (l) => /<link/i.test(l) && /rel=["']stylesheet/i.test(l) && /href=/i.test(l);

function beforeBodyEnd(lines) {
  for (let i = lines.length - 1; i >= 0; i--) if (/<\/body>/i.test(lines[i])) return i;
  return -1;
}

const files = readdirSync(DIR).filter(f => f.endsWith('.html')).sort();
let bootCount = 0;
let radixCount = 0;
let tagCount = 0;
let tourCount = 0;
let skipCount = 0;

for (const f of files) {
  const path = join(DIR, f);
  const text = readFileSync(path, 'utf8');
  const hasScriptTag = text.indexOf('/js/nest-ui.js') >= 0;
  const hasBoot = text.indexOf("localStorage.getItem('nest-theme')") >= 0;
  const hasRadix = text.indexOf('/libs/radix-colors/colors.css') >= 0;
  const hasStylesheet = /<link[^>]+rel=["']stylesheet/i.test(text);
  const wantsTour = text.indexOf('data-nx-tour') >= 0;
  const hasTourTag = text.indexOf('/js/nest-tour.js') >= 0;
  if (hasScriptTag && hasBoot && (hasRadix || !hasStylesheet) && (hasTourTag || !wantsTour)) {
    console.log(`${f.padEnd(30)} 已接入，跳过`);
    skipCount++;
    continue;
  }

  const lines = text.split('\n');
  const actions = [];

  if (!hasBoot) {
    // 自举脚本要排在「第一处会开始画画面」的东西之前：外链样式表、内联 <style>、
    // 或者（都没有时）</head>。
    let anchor = -1;
    for (let i = 0; i < lines.length; i++) {
      if (isStylesheetLine(lines[i]) || /<style/i.test(lines[i]) || /<\/head>/i.test(lines[i])) { anchor = i; break; }
    }
    if (anchor < 0) throw new Error(f + '：找不到 <head> 里的插入点');
    lines.splice(anchor, 0, ...BOOT);
    actions.push('+主题自举');
  }

  if (!hasRadix) {
    // 加在第一个外链样式表之前，于是顺序是：自举脚本 → Radix 色阶 → nest.css。
    // 页面没有外链样式表（如 dorm.html 自带手写样式）就跳过，不需要这些令牌。
    let anchor = -1;
    for (let i = 0; i < lines.length; i++) {
      if (isStylesheetLine(lines[i])) { anchor = i; break; }
    }
    if (anchor < 0) {
      actions.push('(无外链样式表，跳过 radix)');
    } else {
      lines.splice(anchor, 0, RADIX_LINK);
      actions.push('+radix色阶');
    }
  }

  if (wantsTour && !hasTourTag) {
    // 插在 </body> 之前。和 nest-ui.js 谁先谁后都行：两个都是 defer，而 nest-ui 是在
    // DOMContentLoaded 里才去收集 [data-nx-cmd] 的，nest-tour 补的「新手引导」条目
    // 在那之前已经挂到 body 上了。
    const end = beforeBodyEnd(lines);
    if (end < 0) throw new Error(f + '：找不到 </body>');
    lines.splice(end, 0, TOUR_TAG);
    actions.push('+nest-tour.js');
  }

  if (!hasScriptTag) {
    const end = beforeBodyEnd(lines);
    if (end < 0) throw new Error(f + '：找不到 </body>');
    lines.splice(end, 0, SCRIPT_TAG);
    actions.push('+nest-ui.js');
  }

  const out = lines.join('\n');
  if (!dry) writeFileSync(path, out, 'utf8');
  console.log(`${f.padEnd(30)} ${actions.join(' ')}${dry ? '  (dry)' : ''}`);
  if (actions.indexOf('+主题自举') >= 0) bootCount++;
  if (actions.indexOf('+radix色阶') >= 0) radixCount++;
  if (actions.indexOf('+nest-ui.js') >= 0) tagCount++;
  if (actions.indexOf('+nest-tour.js') >= 0) tourCount++;
}

console.log(`\n${files.length} 个页面：${bootCount} 个加了主题自举，${radixCount} 个加了 Radix 色阶，${tagCount} 个加了共享脚本，${tourCount} 个加了新手引导，${skipCount} 个原本就有${dry ? '（dry run，未写盘）' : ''}`);

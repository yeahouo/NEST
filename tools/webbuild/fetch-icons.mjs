// 重新生成 Material Symbols 图标字体子集。
//
// 为什么需要这个脚本：图标字体是按「清单」子集化的 woff2，清单之外的图标名不会报错，
// 只会退化成字面英文词（页面上出现 "group" 这种文字）。类名在 HTML 里、样式在 CSS 里、
// 字形在 woff2 里，三者都「正确」而屏幕上是错的 —— 只能靠渲染出来看才发现。
// 所以每次页面新增图标后都要重跑本脚本。
//
// 生成完必须再跑 node tools/webbuild/check-icon-font.mjs 对账：
// 本脚本只能保证「把你要的名字都请求了」，只有对账能证明「它们真的进了字体」。
//
// 名字取三个来源的并集：
//   1) 页面里精确用到的：span 内容 + card/statCard/icon/iconBtn 的第 2 个参数
//   2) 现役字体里已经能渲染的（tools/webbuild/icon-glyphs.txt）
//      —— 并集保证「重新生成」永远不会把原本能用的图标弄丢，
//         也让本脚本在拿不到官方清单时依然可用（不会因为过滤失败反而变少）
//   3) 官方清单过滤后的宽抽候选（只有拿得到官方 codepoints 清单时才有这一项）
// Google 不认识的图标名会被忽略，不会污染结果；宽抽多带几个字形只是多几百字节。
//
// 用法：node tools/webbuild/fetch-icons.mjs
import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const STATIC = join(HERE, '..', '..', 'src', 'main', 'resources', 'static');
// 扫全部页面，不再只列 5 个交付页：字体是按清单子集化的，整站本地化之后
// 任何一页新增图标都要重新生成字体，靠人手维护清单迟早会漏。
const PAGES = readdirSync(STATIC, { recursive: true })
  .map(String).filter(f => f.endsWith('.html')).sort();
const OUT = join(STATIC, 'fonts', 'material-symbols-outlined.woff2');
const GLYPHS = join(HERE, 'icon-glyphs.txt');
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

// 1) 官方清单：既是「拼错名字」的探测器，也是宽抽候选的过滤器。
//    这个地址在部分网络环境里拿不到（ECONNRESET），拿不到就跳过第 3 项，不影响前两项。
const cpsUrl = 'https://raw.githubusercontent.com/google/material-design-icons/master/variablefont/MaterialSymbolsOutlined%5BFILL%2CGRAD%2Copsz%2Cwght%5D.codepoints';
let official = null;
try {
  const cpsText = await (await fetch(cpsUrl)).text();
  official = new Set(cpsText.split('\n').map(l => l.trim().split(/\s+/)[0]).filter(Boolean));
  console.log('官方图标名 ' + official.size + ' 个');
} catch (e) {
  console.log('⚠ 拿不到官方清单（' + (e.cause?.code || e.message) + '）—— 跳过拼写检查，只用页面清单 + 现役字体清单');
}

// 2) 精确抽取：这些名字一定会被请求
const exact = new Set();
for (const p of PAGES) {
  let t;
  try { t = readFileSync(join(STATIC, p), 'utf8'); } catch { continue; }
  for (const m of t.matchAll(/material-symbols-outlined[^>]*>\s*([a-z0-9_]+)\s*</g)) exact.add(m[1]);
  // card('标题','图标', ...) / statCard(...) / icon(...) 的第 2 个引号参数
  for (const m of t.matchAll(/\b(?:card|statCard|icon|iconBtn)\s*\(\s*(?:'[^']*'|"[^"]*"|`[^`]*`)\s*,\s*(?:'([a-z0-9_]+)'|"([a-z0-9_]+)")/g)) {
    const n = m[1] || m[2];
    if (n) exact.add(n);
  }
}

// 3) 宽抽：任何像图标名的短字符串字面量（抽多了只是字体大几百字节，漏抽会让页面出英文词）
const broad = new Set();
const explicit = new Set();
for (const p of PAGES) {
  let t;
  try { t = readFileSync(join(STATIC, p), 'utf8'); } catch { continue; }
  for (const m of t.matchAll(/material-symbols-outlined[^>]*>\s*([a-z0-9_]+)\s*</g)) explicit.add(m[1]);
  for (const m of t.matchAll(/['"`]([a-z][a-z0-9_]{2,39})['"`]/g)) broad.add(m[1]);
}

// 4) 现役字体里已经能渲染的名字：并集进来，保证不倒退
const seed = existsSync(GLYPHS)
  ? readFileSync(GLYPHS, 'utf8').split('\n').map(s => s.trim()).filter(Boolean)
  : [];
if (!seed.length) console.log('⚠ 没有 ' + GLYPHS + '，无法把「现役字体已有的图标」并进来（建议先跑 dump-icon-glyphs.py）');

const names = new Set([...exact, ...seed]);
const junkExplicit = [];
if (official) {
  for (const n of broad) if (official.has(n)) names.add(n);
  for (const n of [...explicit, ...exact]) if (!official.has(n) && !names.has(n)) junkExplicit.push(n);
}
for (const n of [...exact, ...seed]) if (!names.has(n)) names.add(n);
const sorted = [...names].filter(n => /^[a-z][a-z0-9_]*$/.test(n)).sort();

console.log(`精确用到 ${exact.size} 个，现役字体自带 ${seed.length} 个，宽抽命中官方清单若干 → 共请求 ${sorted.length} 个`);
if (junkExplicit.length) console.log('⚠ 以下名字不在官方清单里（可能是拼写错误，Google 会忽略）：' + junkExplicit.join(', '));

// 5) 拉子集
const url = 'https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined&icon_names='
  + sorted.join(',') + '&display=block';
const css = await (await fetch(url, { headers: { 'User-Agent': UA } })).text();
// 注意：名字多的时候 Google 会返回 https://fonts.gstatic.com/l/font?kit=... 这种不带 .woff2
// 后缀的地址（内容仍是 woff2），所以不能只认 .woff2 结尾的形式。
const m = css.match(/url\((https:\/\/fonts\.gstatic\.com\/[^)]+)\)/);
if (!m) throw new Error('Google 没返回字体地址，CSS 前 400 字：\n' + css.slice(0, 400));
const fontUrl = m[1];
console.log('字体地址 ' + fontUrl.slice(0, 80) + (fontUrl.length > 80 ? '…' : ''));
const buf = Buffer.from(await (await fetch(fontUrl, { headers: { 'User-Agent': UA } })).arrayBuffer());
// 真 woff2 的魔数（不是就看一眼，别把错误页写进资源目录）
const magic = buf.subarray(0, 4).toString('latin1');
if (magic !== 'wOF2') throw new Error('下载到的不是 woff2，魔数=' + JSON.stringify(magic) + '，大小=' + buf.length);
writeFileSync(OUT, buf);
console.log('写入 ' + OUT + '  ' + buf.length + ' 字节（请求了 ' + sorted.length + ' 个名字）');
console.log('\n下一步：node tools/webbuild/check-icon-font.mjs');
console.log('        python tools/webbuild/dump-icon-glyphs.py   （字体变了要刷新清单）');

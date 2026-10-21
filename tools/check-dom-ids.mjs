// 找出「JS 引用了、但页面里根本不存在的 id」。
//
// 为什么需要它：这个 bug 已经出现过两次，而且两次都不报错。
//   1) settings.html 判 getElementById('phone-verified-badge')?.classList —— 元素不在 HTML 里，
//      `?.` 把「手机号已验证」安静地吞掉了；
//   2) 更早的一轮：设置页读 userData.wechatOpenid，而接口从来不返回这个字段。
// 两次的共同点：判断恒为假、页面安静地少显示一样东西、控制台一片干净。
// 人眼读不出来，但 `getElementById('x')` 和 `id="x"` 对不对得上，机器一定读得出来。
//
// 用法：node tools/check-dom-ids.mjs [static 目录]
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const dir = process.argv[2] || 'src/main/resources/static';
const files = readdirSync(dir).filter(f => f.endsWith('.html')).sort();

const ID_ATTR = /\bid\s*=\s*["']([^"']+)["']/g;
const GET_BY_ID = /getElementById\(\s*['"]([^'"]+)['"]\s*\)/g;
const QUERY_SEL = /querySelector(?:All)?\(\s*['"]#([A-Za-z][\w-]*)['"]/g;

let problems = 0;
let checked = 0;

for (const f of files) {
  const src = readFileSync(join(dir, f), 'utf8');

  const defined = new Set();
  for (const m of src.matchAll(ID_ATTR)) defined.add(m[1]);

  const used = new Map();
  const add = (id) => used.set(id, (used.get(id) || 0) + 1);
  // 逐行扫描，好让「明知会取不到、但故意保留保护性判空」的调用可以就地写
  // `dom-ids-ignore` 放行（否则这个脚本会逼着人删掉合法的防御代码）。
  for (const line of src.split(/\r?\n/)) {
    if (line.includes('dom-ids-ignore')) continue;
    for (const m of line.matchAll(GET_BY_ID)) add(m[1]);
    for (const m of line.matchAll(QUERY_SEL)) add(m[1]);
  }

  const missing = [...used.keys()].filter(id => !defined.has(id));
  if (missing.length) {
    problems += missing.length;
    console.log(`✗ ${f}`);
    for (const id of missing) {
      console.log(`    #${id}  被引用 ${used.get(id)} 次，但页面里没有 id="${id}"`);
    }
  }
  checked += used.size;
}

console.log(`\n检查了 ${files.length} 个页面、${checked} 个 id 引用，对不上的 ${problems} 个`);
process.exit(problems ? 1 : 0);

// 检查静态 HTML 里内联 <script> 的语法。
//
// 为什么需要这个：这些页面没有构建步骤，改一行 JS 没有任何东西会告诉你写错了。
// 之前几次改动都是靠"打开浏览器看一眼"，太贵而且靠运气。
// 这里用 new Function(code) 只做解析不执行，能抓出拼写错误、漏掉的括号、
// 以及我自己制造的那类事故（比如删掉一行 const 声明，留下一个未定义变量 ——
// 那种 ReferenceError 运行时才炸，但语法错误这里就能挡住）。
//
// 用法：
//   node tools/check-inline-js.mjs src/main/resources/static/*.html
//   node tools/check-inline-js.mjs            # 不带参数 = 检查 static 下全部
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const staticDir = join(here, '..', 'src', 'main', 'resources', 'static');

let files = process.argv.slice(2);
if (files.length === 0) {
  files = readdirSync(staticDir)
      .filter((f) => f.endsWith('.html'))
      .map((f) => join(staticDir, f));
}

let checked = 0;
let failed = 0;

for (const file of files) {
  const html = readFileSync(file, 'utf8');
  // 只取有内容的内联脚本；带 src 的跳过
  const re = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi;
  let m;
  let index = 0;
  const problems = [];

  while ((m = re.exec(html)) !== null) {
    index += 1;
    const code = m[1];
    if (code.trim() === '') continue;
    checked += 1;
    const line = html.slice(0, m.index).split('\n').length + 1;
    try {
      // 只解析，不执行
      new Function(code);
    } catch (e) {
      failed += 1;
      problems.push(`  script#${index} (第 ${line} 行起): ${e.message}`);
    }
  }

  if (problems.length > 0) {
    console.log(`FAIL ${file}`);
    problems.forEach((p) => console.log(p));
  } else {
    console.log(`ok   ${file.replace(staticDir, 'static')}  (${index} 个内联脚本)`);
  }
}

console.log(
    failed === 0
        ? `\n全部通过：解析了 ${checked} 个内联脚本`
        : `\n有 ${failed} 个脚本语法不通过（共 ${checked} 个）`,
);
process.exit(failed === 0 ? 0 : 1);

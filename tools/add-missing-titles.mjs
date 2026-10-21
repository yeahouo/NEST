// 给没有 <title> 的页面补一个。
//
// 为什么值得单独写个脚本：13 个页面（含设置页、注册页、管理后台）浏览器标签上显示的是
// URL —— 学生开着三个标签就分不清哪个是哪个。这是探针在真实浏览器里量出来的
// （title 字段为空字符串），不是猜的。
//
// 名字跟页面自己的语言保持一致（管理后台是英文标签页，就写英文），
// 别把英文页面配个中文标题。
//
// 用法：node tools/add-missing-titles.mjs [--dry]
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const DIR = 'src/main/resources/static';
const SUFFIX = ' · 学工平台';

const TITLES = {
  'admin-dashboard.html': 'Admin',
  'admin-invitation-codes.html': 'Invitation Codes',
  'admin-users.html': 'User Management',
  'forgot-password.html': 'Forgot Password',
  'index.html': '学工平台 · 高校学生工作管理系统',
  'register.html': 'Create Account',
  'reset-password.html': 'Reset Password',
  'settings.html': 'Account Settings',
  'teacher-dashboard.html': 'Teacher',
  'under-development.html': '功能建设中',
};

const dry = process.argv.includes('--dry');
let added = 0;
let skipped = 0;

for (const f of readdirSync(DIR).filter(x => x.endsWith('.html')).sort()) {
  const path = join(DIR, f);
  const text = readFileSync(path, 'utf8');
  if (/<title>/i.test(text)) {
    skipped++;
    continue;
  }
  const name = TITLES[f];
  if (!name) {
    // 有标题需求就补进 TITLES；没登记就明确报出来，别悄悄跳过。
    console.log(`${f.padEnd(30)} 没有 <title>，也没登记名字 —— 需要补进 TITLES`);
    continue;
  }
  const lines = text.split('\n');
  let anchor = -1;
  for (let i = 0; i < lines.length; i++) {
    if (/<meta[^>]+name=["']viewport/i.test(lines[i])) { anchor = i; break; }
    if (/<meta[^>]+charset/i.test(lines[i]) && anchor < 0) { anchor = i; }
    if (/<\/head>/i.test(lines[i])) { break; }
  }
  if (anchor < 0) throw new Error(f + '：<head> 里找不到插入点');
  const indent = (lines[anchor].match(/^\s*/) || [''])[0];
  const title = name === '学工平台 · 高校学生工作管理系统' ? name : name + SUFFIX;
  lines.splice(anchor + 1, 0, `${indent}<title>${title}</title>`);
  if (!dry) writeFileSync(path, lines.join('\n'), 'utf8');
  console.log(`${f.padEnd(30)} + <title>${title}</title>${dry ? '  (dry)' : ''}`);
  added++;
}

console.log(`\n补了 ${added} 个标题，${skipped} 个页面本来就有${dry ? '（dry run，未写盘）' : ''}`);

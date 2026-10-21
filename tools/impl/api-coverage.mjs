// API 覆盖率审计：把「页面 JS 里请求的 /api/... 」与「Controller 里真实存在的映射」对账。
// 输出三类：
//   A. 页面请求了、后端没有  -> 真缺口（点了会 404）
//   B. 后端有、页面从没请求   -> 死接口（可能是给外部调用，也可能是没人用的半成品）
//   C. 对上的                   -> 仅供计数
// 用法：node tools/impl/api-coverage.mjs
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..', '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'umi', 'nest');
const STATIC = path.join(ROOT, 'src', 'main', 'resources', 'static');

function walk(dir, filter) {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p, filter));
    else if (filter(p)) out.push(p);
  }
  return out;
}

// ---------- 1. 从 Java 收集映射 ----------
const routes = []; // { method, pattern, file, line }
for (const f of walk(JAVA, p => p.endsWith('Controller.java'))) {
  const src = fs.readFileSync(f, 'utf8');
  const lines = src.split(/\r?\n/);
  const classIdx = lines.findIndex(l => /class\s+\w+Controller/.test(l));
  const classLevelLines = new Set();
  let prefix = '';
  for (let i = 0; i < (classIdx < 0 ? lines.length : classIdx); i++) {
    const m = lines[i].match(/@RequestMapping\(\s*(?:value\s*=\s*)?[{"]([^"}]+)["}]/);
    if (m) { prefix = m[1]; classLevelLines.add(i); }
  }
  for (let i = 0; i < lines.length; i++) {
    if (classLevelLines.has(i)) continue;          // 类级注解不算方法映射
    const l = lines[i];
    const m = l.match(/@(Get|Post|Put|Delete|Patch|Request)Mapping\s*\(\s*(?:value\s*=\s*)?(?:[{"]([^"]+)["}]|path\s*=\s*[{"]([^"]+)["}])/);
    if (!m) continue;
    const verb = m[1] === 'Request' ? 'ANY' : m[1].toUpperCase();
    let sub = m[2] || m[3] || '';
    if (!sub.startsWith('/')) sub = '/' + sub;
    let full = (prefix + sub).replace(/\/+$/, '') || '/';
    if (!full.startsWith('/')) full = '/' + full;
    routes.push({ method: verb, pattern: full, file: path.basename(f), line: i + 1 });
  }
}

const norm = s => s.replace(/\{[^}]*\}/g, '*').replace(/:[A-Za-z_][\w]*/g, '*').replace(/\/+$/, '');
const routeSet = new Set(routes.map(r => norm(r.pattern)));

// ---------- 2. 从页面收集请求 ----------
const requested = new Map(); // normPattern -> [{page,line,raw}]
for (const f of walk(STATIC, p => p.endsWith('.html'))) {
  const src = fs.readFileSync(f, 'utf8');
  const lines = src.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    const re = /(['"`])((?:[^'"`\\]|\\.)*?\/api\/[^'"`]*?)\1/g;
    let m;
    while ((m = re.exec(l)) !== null) {
      let raw = m[2];
      const idx = raw.indexOf('/api/');
      if (idx < 0) continue;
      let s = raw.slice(idx);
      s = s.replace(/\$\{[^}]*\}/g, '*');           // 模板占位
      s = s.replace(/'[^']*'\s*\+\s*[A-Za-z_$][\w.$]*\s*\+\s*'[^']*'/g, '*'); // 'a' + x + 'b'
      s = s.replace(/\/\/+$/, '/');
      s = s.split(/[?#\s'"+`]/)[0];
      s = s.replace(/\/\d+/g, '/*');                 // 路径里的数字 id
      s = s.replace(/\/\*$/, '/*');
      if (!/^\/api\//.test(s)) continue;
      let p = norm(s);
      if (p.endsWith('/')) p += '*';
      requested.set(p, [...(requested.get(p) || []), { page: path.basename(f), line: i + 1, raw: s }]);
    }
  }
}

// ---------- 3. 对账 ----------
// 页面路径 /a/b/* 与路由 /a/b/* 视为命中；若页面是 /a/b 而路由是 /a/b/* 也视为命中（参数为空的情况）
const routeByUrl = new Set([...routeSet].map(u => norm(u)));
const matchRoute = (p) => {
  if (routeByUrl.has(p)) return true;
  if (p.endsWith('/*') && routeByUrl.has(p.slice(0, -2))) return true;
  if (!p.endsWith('/*') && routeByUrl.has(p + '/*')) return true;
  // 前缀命中：/a/b/* 可以落在 /a/b/*/c 这类更深路由上
  for (const r of routeByUrl) if (r.startsWith(p.endsWith('/*') ? p : p + '/')) return true;
  return false;
};

const missing = [];
const used = new Set();
for (const [p, refs] of requested) {
  if (matchRoute(p)) { used.add(p); continue; }
  missing.push({ p, refs });
}
const unused = [...routeByUrl].filter(r => {
  if (!r.includes('/api/')) return false;
  for (const u of used) {
    if (r === u) return false;
    if (u.endsWith('/*') && (r.startsWith(u.slice(0, -1)) || r === u.slice(0, -2))) return false;
    if (r.startsWith(u.endsWith('/*') ? u.slice(0, -1) : u + '/')) return false;
    if (u.startsWith(r + '/')) return false;
  }
  return true;
});

const line = (s) => console.log(s);
line('');
line('================ A. 页面请求了但后端没有（点了就 404）================');
if (!missing.length) line('  （无）');
else for (const { p, refs } of missing.sort((a, b) => a.p.localeCompare(b.p))) {
  line(`  ${p}`);
  const byPage = new Map();
  for (const r of refs) { if (!byPage.has(r.page)) byPage.set(r.page, []); byPage.get(r.page).push(r.line); }
  for (const [pg, ls] of byPage) line(`      ${pg}:${[...new Set(ls)].join(',')}`);
}
line('');
line('================ B. 后端有但没有任何页面请求（死接口/半成品）================');
if (!unused.length) line('  （无）');
else for (const u of unused.sort()) {
  const rs = routes.filter(r => norm(r.pattern) === u);
  line(`  ${u}`);
  for (const r of rs) line(`      ${r.method} ${r.file}:${r.line}`);
}
line('');
line('================ C. 计数 ================');
line(`  后端映射 ${routes.length} 条（去重路径 ${routeSet.size} 条）`);
line(`  页面请求的不同路径 ${requested.size} 条`);
line(`  对上 ${requested.size - missing.length} 条 / 缺口 ${missing.length} 条 / 死接口 ${unused.length} 条`);
line('');

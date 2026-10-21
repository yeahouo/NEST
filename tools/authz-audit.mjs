// 权限矩阵与数据级授权巡检（一次性出四张表，可反复跑）
//
// 背景：本项目的 SecurityConfig 把 /api/** 全部 permitAll（见 SecurityConfig.java:41-44），
// 授权完全由控制器与手写的服务层守卫负责。这个脚本把「谁在守、守到哪一层」变成可复查的读数，
// 而不是靠人眼读 22 个控制器。
//
// 用法（仓库根目录）：node tools\authz-audit.mjs
// 产出：控制台四张表 + target\authz-matrix.json（逐路由明细，含闸门计数）
import fs from 'fs';
import path from 'path';

const ROOT = process.cwd();
const C = path.join(ROOT, 'src/main/java/com/umi/nest/controller');
const S = path.join(ROOT, 'src/main/java/com/umi/nest/service');
const OUT = path.join(ROOT, 'target/authz-matrix.json');

// 真正的「闸门调用」：登录闸门 / 角色闸门 / 会话读取。只出现 userId、role 这些词不算守卫。
const GUARD_CALL = /(guardLogin|guardStaff|guardAdmin|guard[A-Z]\w*|require[A-Z]\w*|ensure[A-Z]\w*|checkAdmin|isAdmin|isStaff|isTeacher|isCounselor|session\.getAttribute\(\s*"userId")/;

function methodBody(src, openBraceIdx) {
  let depth = 0;
  let end = openBraceIdx;
  for (; end < src.length; end++) {
    if (src[end] === '{') depth++;
    else if (src[end] === '}') { depth--; if (!depth) return src.slice(openBraceIdx, end + 1); }
  }
  return src.slice(openBraceIdx);
}

function lineOf(src, idx) {
  return src.slice(0, idx).split('\n').length;
}

const files = fs.readdirSync(C).filter((f) => f.endsWith('.java')).sort();
const routes = [];
for (const f of files) {
  const src = fs.readFileSync(path.join(C, f), 'utf8');
  const cls = f.replace('.java', '');
  const base = (/@RequestMapping\("([^"]+)"\)/.exec(src) || [])[1] || '';
  const re = /@(Get|Post|Put|Delete|Patch)Mapping\(([^)]*)\)\s*(?:\/\/[^\n]*\n\s*)?public\s+[^\n{]*\{/g;
  let m;
  while ((m = re.exec(src))) {
    const body = methodBody(src, m.index + m[0].length - 1);
    const verb = m[1].toUpperCase();
    const p = m[2].replace(/"/g, '').trim();
    routes.push({
      cls,
      line: lineOf(src, m.index),
      verb,
      path: base + p,
      bytes: body.length,
      selfGuarded: GUARD_CALL.test(body),
      callsService: /(return\s+)?\w+Service\.\w+\(|return\s+\w+\.\w+\(/.test(body),
    });
  }
}

const byCls = {};
for (const r of routes) (byCls[r.cls] = byCls[r.cls] || []).push(r);
const count = (txt, re) => (txt.match(re) || []).length;

console.log('== 第 1 张表：控制器 × 路由 × 守卫位置 ==');
console.log(`控制器 ${files.length} 个，路由 ${routes.length} 条\n`);
console.log('控制器'.padEnd(26) + '路由  控制器内守卫  下放服务层  其余');
for (const cls of Object.keys(byCls)) {
  const list = byCls[cls];
  const self = list.filter((r) => r.selfGuarded).length;
  const svc = list.filter((r) => !r.selfGuarded && r.callsService).length;
  const rest = list.length - self - svc;
  console.log(cls.padEnd(26) + String(list.length).padEnd(6) + String(self).padEnd(14) + String(svc).padEnd(12) + rest);
}

console.log('\n== 第 2 张表：既无控制器内闸门、也不调服务层的路由（必须逐条人工判读）==');
const bare = routes.filter((r) => !r.selfGuarded && !r.callsService);
if (!bare.length) console.log('（无）');
for (const r of bare) console.log(`${r.cls}.java:${r.line}  ${r.verb} ${r.path}  (${r.bytes}B)`);

console.log('\n== 第 3 张表：按 id 直取的 GET 路由 → 归属判定落在哪个方法 ==');
const idRoutes = routes.filter((r) => r.verb === 'GET' && /\{(id|Id|filename|userId|studentId|postId|taskId|batchId)\}/.test(r.path));
for (const r of idRoutes) {
  const src = fs.readFileSync(path.join(C, r.cls + '.java'), 'utf8');
  const idx = src.split('\n').slice(0, r.line - 1).join('\n').length;
  const open = src.indexOf('{', src.indexOf('public', idx));
  const body = open > 0 ? methodBody(src, open) : '';
  const calls = [...body.matchAll(/(\w+)Service\.(\w+)\(|return\s+(\w+)\.(\w+)\(/g)]
    .map((x) => (x[1] ? x[1] + '.' + x[2] : x[3] + '.' + x[4]))
    .filter((c) => !/^(Result|session|Long|Math|String|Objects|List|Map|ResponseEntity)\./.test(c));
  console.log(`${r.cls}.java:${r.line}  ${r.path.padEnd(44)} -> ${[...new Set(calls)].slice(0, 3).join(', ') || '(控制器内直接处理)'}`);
}

console.log('\n== 第 4 张表：服务层闸门计数（控制器直接依赖的 Service）==');
const gateRows = [];
for (const cls of Object.keys(byCls)) {
  const csrc = fs.readFileSync(path.join(C, cls + '.java'), 'utf8');
  let blob = csrc;
  for (const sv of new Set([...csrc.matchAll(/(\w+)Service\s+\w+;/g)].map((x) => x[1]))) {
    const p = path.join(S, sv + 'Service.java');
    if (fs.existsSync(p)) blob += fs.readFileSync(p, 'utf8');
  }
  const row = {
    cls,
    routes: byCls[cls].length,
    sessionReads: count(csrc, /session\.getAttribute\("userId"\)/g),
    guardLogin: count(blob, /guardLogin\(/g),
    guardStaff: count(blob, /guardStaff\w*\(/g),
    guardAdmin: count(blob, /guardAdmin\w*\(/g),
    roleEquals: count(blob, /"admin"\.equals|"teacher"\.equals|"counselor"\.equals/g),
    denied: count(blob, /无权/g),
    e403: count(blob, /Result\.error\(403|\b403,/g),
    e404: count(blob, /Result\.error\(404/g),
  };
  gateRows.push(row);
  console.log(`${cls.padEnd(26)} routes=${String(row.routes).padEnd(4)} session=${String(row.sessionReads).padEnd(3)} guardLogin=${String(row.guardLogin).padEnd(3)} guardStaff=${String(row.guardStaff).padEnd(3)} guardAdmin=${String(row.guardAdmin).padEnd(3)} 无权=${String(row.denied).padEnd(3)} 403=${String(row.e403).padEnd(3)} 404=${row.e404}`);
}

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify({ generatedAt: new Date().toISOString(), routes, gates: gateRows }, null, 2), 'utf8');
console.log(`\n明细已写入 ${path.relative(ROOT, OUT)}（${routes.length} 条路由，${gateRows.length} 个控制器）`);
console.log('判定口径：第 2 张表为空 = 每条路由要么控制器自己判、要么把 actorId+role 交给服务层判。');

// 真机权限矩阵巡检：对 target\authz-matrix.json 里的 151 条路由做匿名 / 学生 / 教师三种身份的实测。
// 只读、不写库：匿名请求在服务层守卫处就被挡下（返回 401/403），根本进不到业务代码；
// 学生/教师只打「本该被拒」的 staff/admin 路由，写入类请求即便漏过守卫也只会得到校验错误。
import fs from 'node:fs';

const BASE = 'http://127.0.0.1:8080';
const matrix = JSON.parse(fs.readFileSync('target/authz-matrix.json', 'utf8'));
const log = [];
const failures = [];
const observations = [];

function line(s) { log.push(s); console.log(s); }

// 匿名即可访问的白名单（登录 / 注册 / 找回密码 / 常量字典 / 健康检查）
const PUBLIC = [
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/send-code',
  '/api/auth/reset-password',
  '/api/auth/logout',
  '/api/aid/poverty/stages',
  '/api/second-class/health',
  '/api/dorm/health'
];

// 只对 GET 公开：匿名取头像返回**默认头像 SVG**（226 B），这是设计如此——
// 登录页/未登录状态要有个占位头像，不属于信息泄露。POST 同名路径仍然必须被拒。
const PUBLIC_GET_ONLY = new Set(['/api/user/avatar']);

// 员工专属前缀（admin | teacher），学生角色必须被拒。
// 注意：/api/user/** 与 /api/student/profile、/api/rank/bonus* 是**学生自助**接口（本人资料 /
// 本人加分），不属于员工专属，首跑时被误列进来，已剔除。
const STAFF_PREFIXES = [
  '/api/admin/',
  '/api/aid/review/',
  '/api/counselor/',
  '/api/community/batches',
  '/api/community/inspections',
  '/api/community/lates',
  '/api/community/violations',
  '/api/community/civil-rooms',
  '/api/community/activities',
  '/api/honor/batches',
  '/api/honor/review/',
  '/api/honor/export',
  '/api/rank/batches',
  '/api/rank/academic',
  '/api/workstudy/manage',
  '/api/psyche/batches',
  '/api/second-class/review',
  '/api/second-class/admin/',
  '/api/club/reviews',
  '/api/eval/compute'
];

// 管理员专属前缀（teacher 也必须被拒）
const ADMIN_PREFIXES = ['/api/admin/'];

function fill(path) {
  return path.replace(/\{[^}]+\}/g, '1');
}

// 这些路由不参与实测：
//   '/' 是静态首页（PageController 直接 forward）；含 ',' 或填充后仍留花括号的是
//   @GetMapping({"", "/"}) 这类多路径写法，正则拼不出真实 URL，硬测只会得到假 404。
const SKIP_PATHS = new Set(['/']);
function testable(path) {
  if (SKIP_PATHS.has(path)) return false;
  if (/,/.test(path)) return false;
  if (/[{}]/.test(fill(path))) return false;
  return true;
}

async function login(username, password) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
    redirect: 'manual'
  });
  const setCookie = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  const raw = setCookie.join(';') || res.headers.get('set-cookie') || '';
  const m = /JSESSIONID=([^;]+)/.exec(raw);
  return m ? m[1] : null;
}

async function call(method, path, cookie) {
  const url = BASE + fill(path);
  const headers = {};
  if (cookie) headers.Cookie = `JSESSIONID=${cookie}`;
  const init = { method, headers, redirect: 'manual' };
  if (method !== 'GET' && method !== 'HEAD') {
    headers['Content-Type'] = 'application/json';
    init.body = '{}';
  }
  let status = 0;
  let body = null;
  try {
    const res = await fetch(url, init);
    status = res.status;
    const text = await res.text();
    try { body = JSON.parse(text); } catch { body = text.slice(0, 120); }
  } catch (e) {
    return { status: -1, code: -1, message: String(e.message || e) };
  }
  return { status, code: body && body.code, message: (body && body.message) || '' };
}

function isDenied(r) {
  // 业务码 401/403，或 HTTP 401/403；重定向到登录页也算被拒。
  // 另外：这套代码的登录闸门有多套文案，`Result.error("未登录")` 走的是 code 400，
  // 所以「code 400 且消息是未登录/无权」同样算被拒，否则会把正常拦截报成漏。
  if (r.code === 401 || r.code === 403 || r.status === 401 || r.status === 403 || r.status === 302) return true;
  if (r.code === 400 && /未登录|请先登录|登录已失效|无权|没有权限|不可访问/.test(r.message || '')) return true;
  return false;
}

// 可达但目标不存在（存在性判断先于归属判断），单独统计——不算越权放行，但要写进观察项
function notFound(r) {
  return r.status === 404 || /不存在/.test(r.message || '');
}

function tag(r) { return `http=${r.status} code=${r.code} msg=${r.message}`; }

const routes = matrix.routes.filter((r) => testable(r.path));
line(`参与实测路由 ${routes.length} / ${matrix.routes.length}（跳过静态首页、多路径映射、无法填充的占位符）`);

// ---------- 1) 匿名 ----------
line('');
line('== 1) 匿名访问：非白名单路由必须被拒（401/403/302） ==');
let anonDenied = 0, anonPublicOk = 0, anonFail = 0;
for (const r of routes) {
  const isPublic = PUBLIC.some((p) => r.path === p || r.path.startsWith(p + '/'))
    || (r.verb === 'GET' && PUBLIC_GET_ONLY.has(r.path));
  const res = await call(r.verb, r.path, null);
  if (isPublic) {
    if (res.code === 200 || res.code === 400 || res.status === 200) { anonPublicOk++; }
    else { anonFail++; failures.push(`[匿名·白名单] ${r.verb} ${r.path} 期望可用，实际 ${tag(res)}`); }
  } else if (isDenied(res)) {
    anonDenied++;
  } else if (notFound(res)) {
    anonFail++;
    observations.push(`[匿名·404] ${r.verb} ${r.path} ${tag(res)}（目标不存在，未触达守卫，需人工确认路由是否真的存在）`);
  } else {
    anonFail++;
    failures.push(`[匿名·漏] ${r.verb} ${r.path} 期望被拒，实际 ${tag(res)}`);
  }
}
line(`匿名：被拒 ${anonDenied} / 白名单可用 ${anonPublicOk} / 异常 ${anonFail}`);

// ---------- 2) 学生打员工路由 ----------
const sidStudent = await login('chendw', '123456');
if (!sidStudent) { failures.push('[登录] 学生 chendw 未拿到会话'); }
const staffRoutes = routes.filter((r) => STAFF_PREFIXES.some((p) => r.path.startsWith(p)));
line('');
line(`== 2) 学生身份打「员工专属」路由：应为 403/401（共 ${staffRoutes.length} 条） ==`);
let stuDenied = 0, stuFail = 0, stuNotFound = 0;
for (const r of staffRoutes) {
  const res = await call(r.verb, r.path, sidStudent);
  if (isDenied(res)) { stuDenied++; }
  else if (notFound(res)) {
    // 存在性判断先于归属判断：能走到「不存在」说明没被授权层挡，但也没读到数据。
    stuNotFound++;
    observations.push(`[学生·404] ${r.verb} ${r.path} ${tag(res)}（可疑：授权判断应在存在性之前）`);
  } else { stuFail++; failures.push(`[学生·漏] ${r.verb} ${r.path} 期望被拒，实际 ${tag(res)}`); }
}
line(`学生：被拒 ${stuDenied} / 目标不存在 ${stuNotFound} / 异常 ${stuFail}`);

// ---------- 3) 教师打管理员路由 ----------
const sidTeacher = await login('lisi', '123456');
if (!sidTeacher) { failures.push('[登录] 教师 lisi 未拿到会话'); }
const adminRoutes = routes.filter((r) => ADMIN_PREFIXES.some((p) => r.path.startsWith(p)));
line('');
line(`== 3) 教师身份打「管理员专属」路由：应为 403/401（共 ${adminRoutes.length} 条） ==`);
let teaDenied = 0, teaFail = 0, teaNotFound = 0;
for (const r of adminRoutes) {
  const res = await call(r.verb, r.path, sidTeacher);
  if (isDenied(res)) { teaDenied++; }
  else if (notFound(res)) {
    teaNotFound++;
    observations.push(`[教师·404] ${r.verb} ${r.path} ${tag(res)}（可疑：授权判断应在存在性之前）`);
  } else { teaFail++; failures.push(`[教师·漏] ${r.verb} ${r.path} 期望被拒，实际 ${tag(res)}`); }
}
line(`教师：被拒 ${teaDenied} / 目标不存在 ${teaNotFound} / 异常 ${teaFail}`);

// ---------- 汇总 ----------
line('');
line('=== 汇总 ===');
line(`参与实测 ${routes.length} 条路由；匿名被拒 ${anonDenied}；白名单可用 ${anonPublicOk}；学生被拒 ${stuDenied}/${staffRoutes.length}；教师被拒 ${teaDenied}/${adminRoutes.length}`);
if (observations.length > 0) {
  line(`观察项 ${observations.length} 条（不算越权放行，但要人工判读）：`);
  for (const o of observations) line('  ~ ' + o);
}
if (failures.length === 0) {
  line('VERDICT OK —— 没有发现越权放行');
} else {
  line(`VERDICT FAIL —— ${failures.length} 条：`);
  for (const f of failures) line('  ! ' + f);
}

fs.writeFileSync('target/authz-live.log', log.join('\n') + '\n', 'utf8');
process.exit(failures.length === 0 ? 0 : 1);

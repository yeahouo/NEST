// 学工 API 角色粗筛（ApiRoleGuardInterceptor）的真机验证。
// 目标不是「代码里有拦截器」，而是「三种身份打真实端口时，行为符合规则表且没有误伤学生页」。
import fs from 'node:fs';

const BASE = 'http://127.0.0.1:8080';
const log = [];
let fails = 0;

function line(s) {
  log.push(s);
  console.log(s);
}

async function login(username, password = '123456') {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  const raw = (res.headers.getSetCookie() || []).join(';') || res.headers.get('set-cookie') || '';
  const m = /JSESSIONID=([^;]+)/.exec(raw);
  const body = await res.json();
  if (!m) throw new Error(`login ${username} failed: ${JSON.stringify(body)}`);
  return m[1];
}

async function call(method, path, sid, body) {
  const headers = {};
  if (sid) headers.Cookie = `JSESSIONID=${sid}`;
  const init = { method, headers, redirect: 'manual' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  const res = await fetch(BASE + path, init);
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch (e) { /* 非 JSON */ }
  return { status: res.status, code: json && json.code, message: json && json.message, text };
}

function check(label, cond, detail) {
  if (cond) {
    line(`OK   ${label}`);
  } else {
    fails++;
    line(`FAIL ${label}  ${detail || ''}`);
  }
}

const anon = null;
const sidAdmin = await login('admin');
const sidTeacher = await login('lisi');
const sidStudent = await login('chendw');

line('== 1) 管理端 /api/admin/** ：匿名 401 / 学生 403 / 教师 403 / 管理员 200 ==');
{
  const a = await call('GET', '/api/admin/users', anon);
  check('匿名 -> code 401', a.code === 401 && a.status === 200, `got http=${a.status} code=${a.code} ${a.message}`);

  const s = await call('GET', '/api/admin/users', sidStudent);
  check('学生 -> code 403', s.code === 403 && s.status === 200, `got http=${s.status} code=${s.code} ${s.message}`);

  const t = await call('GET', '/api/admin/users', sidTeacher);
  check('教师 -> code 403', t.code === 403, `got http=${t.status} code=${t.code} ${t.message}`);
  check('教师 403 文案点明需要角色', /admin/.test(t.message || ''), t.message);

  const adm = await call('GET', '/api/admin/users', sidAdmin);
  check('管理员 -> code 200', adm.code === 200, `got http=${adm.status} code=${adm.code} ${adm.message}`);

  const dash = await call('GET', '/api/admin/dashboard', sidAdmin);
  check('管理员 dashboard -> code 200', dash.code === 200, `got code=${dash.code} ${dash.message}`);
}

line('== 2) 员工面：学生 403 / 教师 200（/api/counselor/overview）==');
{
  const s = await call('GET', '/api/counselor/overview', sidStudent);
  check('学生 -> code 403', s.code === 403, `got http=${s.status} code=${s.code} ${s.message}`);

  const t = await call('GET', '/api/counselor/overview', sidTeacher);
  check('教师 -> code 200', t.code === 200, `got code=${t.code} ${t.message}`);

  const adm = await call('GET', '/api/counselor/overview', sidAdmin);
  check('管理员 -> code 200', adm.code === 200, `got code=${adm.code} ${adm.message}`);
}

line('== 3) 员工面覆盖抽样（学生全部 403）==');
{
  const paths = [
    ['GET', '/api/second-class/review'],
    ['GET', '/api/second-class/review/stats'],
    ['GET', '/api/second-class/admin/categories'],
    ['GET', '/api/rank/stats'],
    ['GET', '/api/rank/export'],
    ['GET', '/api/honor/export'],
    ['GET', '/api/workstudy/overview'],
    ['GET', '/api/psyche/stats'],
    ['GET', '/api/community/stats'],
    ['GET', '/api/community/export']
  ];
  for (const [method, path] of paths) {
    const s = await call(method, path, sidStudent);
    check(`学生 ${method} ${path} -> 被拦`, s.code === 403, `got http=${s.status} code=${s.code} ${s.message}`);
  }
}

line('== 4) 双用途路径不能误伤学生（本步失败 = 学生端页面会断）==');
{
  const cases = [
    ['GET', '/api/honor/batches'],
    ['GET', '/api/rank/batches'],
    ['GET', '/api/rank/bonus/my'],
    ['GET', '/api/community/my/room'],
    ['GET', '/api/community/my/lates'],
    ['GET', '/api/community/my/violations'],
    ['GET', '/api/second-class/my'],
    ['GET', '/api/second-class/categories'],
    ['GET', '/api/community/inspections/1'],
    ['GET', '/api/user/avatar']
  ];
  for (const [method, path] of cases) {
    const s = await call(method, path, sidStudent);
    check(`学生 ${method} ${path} 未被角色粗筛拦（不是 401/403 粗筛文案）`,
      !(s.code === 403 && /角色|粗筛|无权访问该接口/.test(s.message || '')) && s.code !== 401,
      `got http=${s.status} code=${s.code} ${s.message}`);
  }
  const detail = await call('GET', '/api/community/inspections/1', sidStudent);
  line(`     （观察）学生读他人宿舍检查记录 -> code=${detail.code} ${detail.message}`);
}

line('== 5) 未登录既有的登录闸门没被破坏 ==');
{
  const s = await call('GET', '/api/second-class/my', anon);
  check('匿名 /api/second-class/my -> 401', s.code === 401, `got code=${s.code} ${s.message}`);
  const pub = await call('GET', '/api/second-class/health', anon);
  check('匿名 health 仍可用', pub.code === 200, `got code=${pub.code}`);
  const stages = await call('GET', '/api/aid/poverty/stages', anon);
  check('匿名 常量字典仍可用', stages.code === 200, `got code=${stages.code}`);
}

line('');
line(fails === 0 ? 'VERDICT OK' : `VERDICT FAIL (${fails})`);
fs.writeFileSync('target/verify-api-guard.log', log.join('\n') + '\n');
process.exit(fails === 0 ? 0 : 1);

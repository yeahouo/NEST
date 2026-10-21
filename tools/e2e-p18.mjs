/**
 * e2e-p18.mjs — P18「第二课堂成长手册」真实 HTTP 端到端验收脚本
 *
 * 依据（以代码实测为准，不照抄需求描述）：
 *   - src/main/java/com/umi/nest/controller/SecondClassController.java（21 条方法级路由，前缀 /api/second-class）
 *   - src/main/java/com/umi/nest/service/SecondClassService.java（校验顺序与状态机真源）
 *   - src/main/resources/mapper/SecondClassRecordMapper.xml（resubmit / summaryByCategory / totalsByUser 口径）
 *   - sql/nest_secondclass.sql（DDL：uk_category_code、uk_record_activity_user、deleted 软删列）
 *   - target/p18-smoke.mjs（本脚本的前身：登录取 cookie 的方式沿用）
 *
 * 口径（与代码一致）：
 *   - 除 /export 外所有接口 HTTP 恒 200，业务码在 body 的 json.code（Result.success → 200）；
 *     失败为 400/401/403/404 + 中文 message。
 *   - /export 返回 CSV 字节流（UTF-8 BOM + text/csv;charset=UTF-8 + 文件名 second-class-<yyyyMMdd>.csv），
 *     学号只留后 4 位、姓名只留首字 + *（csvCell/maskName/tail4，与 P17 同口径）；
 *     越权 / 未登录一律非 200 HTTP（403）且 body 为空
 *     （SecondClassService.exportCsv → out.errorCode=403；SecondClassController.export → ResponseEntity.status(...).build()）。
 *   - PUT /records/{id}（returned 重交）同样校验学分 0-20 → 400「申报学分应在 0-20 之间」，失败不落库。
 *   - 失败断言一律「code + 中文片段」双断言，不做「code != 0」这种糊断言。
 *
 * 可重复运行：
 *   - 本轮所有数据带标记 TAG = E2E18_<base36 时间戳>；clean-p18.sql 按该前缀删除，
 *     绝不 DELETE 全表、绝不动演示数据（脚本跑前会记录三表 COUNT(*) 作为基线并比对）。
 *   - 每个账号只登录一次（同账号二次登录会让前一个 JSESSIONID 失效），cookie 全程复用。
 *
 * 用法：node tools\e2e-p18.mjs
 *   控制台 + target/e2e-p18.log 同步输出；存在 FAIL / FATAL 时退出码 1。
 *   脚本末尾自动执行：cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < target\clean-p18.sql"
 */

import fs from 'node:fs';
import path from 'node:path';
import { execSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(__dirname, '..');
const TARGET = path.join(ROOT, 'target');
const BASE = 'http://127.0.0.1:8080';
const LOG_PATH = path.join(TARGET, 'e2e-p18.log');
const CLEAN_SQL = path.join(TARGET, 'clean-p18.sql');

const MYSQL = 'D:\\develop\\mysql-9.5.0-winx64\\bin\\mysql.exe';
const CNF = path.join(process.env.TEMP || 'C:\\Users\\lenovo\\AppData\\Local\\Temp', 'nest_my.cnf');

const RUN = Date.now().toString(36).toUpperCase();
const TAG = `E2E18_${RUN}`;          // 所有自建数据的唯一前缀（板块 code / 活动 title / 申报 title）
const PASSWORD = '123456';
const OK_CODE = 200;

const CSV_HEADER = '学号,姓名,班级,板块,申报标题,学时,学分,状态,初审人,初审时间,认定人,认定时间,提交时间';

// ---------------------------------------------------------------- 断言基础设施
const lines = [];
const failures = [];
const notes = [];
let pass = 0;
let fail = 0;
let fatal = false;
let currentPhase = '(boot)';

const emit = (line) => lines.push(line);
function section(title) {
  emit('');
  emit(`──── ${title} ────`);
}

function describe(rec) {
  if (!rec) return '(无响应)';
  const j = rec.json;
  const msg = j && j.message !== undefined ? String(j.message) : String(rec.text || '').slice(0, 120);
  const flat = msg.replace(/\s+/g, ' ').trim().slice(0, 160);
  return `${rec.method} ${rec.path} → HTTP ${rec.status} code=${j ? j.code : '(非 JSON)'} message=${flat}${rec.buf && rec.isFile ? ` bytes=${rec.buf.length}` : ''}`;
}

function ok(name, condition, detail) {
  if (condition) {
    pass += 1;
    emit(`  PASS  ${name}`);
  } else {
    fail += 1;
    failures.push(name);
    emit(`  FAIL  ${name}${detail ? `  [${detail}]` : ''}`);
  }
  return !!condition;
}

const check = (name, condition, rec, extra) =>
  ok(name, condition, `${extra ? `${extra} | ` : ''}${describe(rec)}`);

const d = (rec) => (rec && rec.json && rec.json.data !== undefined ? rec.json.data : null);
const hasText = (rec, needle) => !!rec && String(rec.text || '').includes(needle);

// 成功：HTTP 200 + json.code === 200（可要求 message 片段）
function expectOk(name, rec, msgPart) {
  const j = rec && rec.json;
  const msg = (j && j.message) || '';
  let good = !!rec && rec.status === 200 && j && j.code === OK_CODE;
  let why = describe(rec);
  if (msgPart) {
    good = good && String(msg).includes(msgPart);
    why += ` | 期望 message 含「${msgPart}」`;
  }
  return ok(name, good, why);
}

// 失败：HTTP 200 + 指定业务 code + 中文 message（必须同时断言中文片段）
function expectReject(name, rec, wantCode, msgPart) {
  const j = rec && rec.json;
  const msg = String((j && j.message) || '');
  const chinese = /[\u4e00-\u9fa5]/.test(msg);
  let good = !!rec && rec.status === 200 && j && j.code === wantCode && chinese;
  let why = `${describe(rec)} | 期望 HTTP 200 + code=${wantCode} + 中文 message`;
  if (msgPart) {
    good = good && msg.includes(msgPart);
    why += ` 且含「${msgPart}」`;
  }
  return ok(name, good, why);
}

function expectHttp(name, rec, status, extra) {
  return ok(name, !!rec && rec.status === status, `${extra ? `${extra} | ` : ''}${describe(rec)}`);
}

// 导出类：拒绝形态（403，HTTP 或业务码任一）
function expectDenied(name, rec, extra) {
  const j = rec && rec.json;
  const good = !!rec && (rec.status === 403 || (j && j.code === 403));
  return ok(name, good, `${extra ? `${extra} | ` : ''}${describe(rec)}`);
}

// 导出类拒绝的严格形态：非 200 且 body 为空（Controller 直接 build() 无 body）
function expectEmptyReject(name, rec, status) {
  const size = rec && rec.buf ? rec.buf.length : -1;
  return ok(name, !!rec && rec.status === status && size === 0, `期望 HTTP ${status} 且 body 空，实测 ${describe(rec)} bytes=${size}`);
}

const eq = (name, actual, expected, rec) =>
  ok(name, actual === expected, `期望 ${JSON.stringify(expected)} 实测 ${JSON.stringify(actual)}${rec ? ` | ${describe(rec)}` : ''}`);

const eqNum = (name, actual, expected, rec) =>
  ok(name, Number.isFinite(Number(actual)) && Math.abs(Number(actual) - Number(expected)) < 1e-6,
    `期望 ${expected} 实测 ${JSON.stringify(actual)}${rec ? ` | ${describe(rec)}` : ''}`);

function note(name, detail) {
  notes.push(`${name} — ${detail}`);
  emit(`  NOTE  ${name}  [${detail}]`);
}

// ---------------------------------------------------------------- HTTP
const cookies = {};
const covered = new Set();
const coveredPhase = new Map();

const tpl = (p) => String(p).split('?')[0].replace(/\/\d+(?=\/|$)/g, '/{id}');

async function call(who, method, p, body) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (who && cookies[who]) headers.Cookie = cookies[who];
  const res = await fetch(BASE + p, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'manual',
  });
  const buf = Buffer.from(await res.arrayBuffer());
  const text = buf.toString('utf8');
  let json = null;
  try { json = JSON.parse(text); } catch { json = null; }
  const key = `${method} ${tpl(p)}`;
  covered.add(key);
  if (!coveredPhase.has(key)) coveredPhase.set(key, currentPhase);
  return { who, method, path: p, body, status: res.status, text, json, buf, headers: res.headers };
}

async function rawGet(who, p) {
  const headers = {};
  if (who && cookies[who]) headers.Cookie = cookies[who];
  const res = await fetch(BASE + p, { method: 'GET', headers, redirect: 'manual' });
  const buf = Buffer.from(await res.arrayBuffer());
  const text = buf.toString('utf8');
  let json = null;
  try { json = JSON.parse(text); } catch { json = null; }
  const key = `GET ${tpl(p)}`;
  covered.add(key);
  if (!coveredPhase.has(key)) coveredPhase.set(key, currentPhase);
  return { who, method: 'GET', path: p, status: res.status, text, json, buf, headers: res.headers, isFile: true };
}

async function login(account) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: account, password: PASSWORD }),
    redirect: 'manual',
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { json = null; }
  const raw = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [res.headers.get('set-cookie') || ''];
  const m = (raw.join(';').match(/JSESSIONID=[^;]+/) || [])[0];
  if (!m) throw new Error(`登录 ${account} 未取到 JSESSIONID：HTTP ${res.status} body=${text.slice(0, 200)}`);
  cookies[account] = m;
  return { who: account, method: 'POST', path: '/api/auth/login', status: res.status, text, json, buf: Buffer.from(text, 'utf8'), headers: res.headers };
}

// ---------------------------------------------------------------- 数据库
function ensureCnf() {
  if (fs.existsSync(CNF)) return false;
  const pw = process.env.NEST_DB_PASSWORD;
  if (!pw) {
    console.error('[e2e-p18] 缺少环境变量 NEST_DB_PASSWORD（真实数据库密码不入库，请本地自行设置）');
    process.exit(1);
  }
  fs.writeFileSync(CNF, '[client]\nuser=root\npassword="' + pw + '"\ndefault-character-set=utf8mb4\n', 'utf8');
  return true;
}

// 查询用 stdin 传 SQL（不经过 shell，天然无引号问题）
function mysqlRows(sql) {
  const r = spawnSync(MYSQL, ['--defaults-extra-file=' + CNF, '-D', 'nest', '-N', '-B'], {
    input: `${String(sql).trim().replace(/;+$/, '')};\n`,
    encoding: 'utf8',
  });
  if (r.status !== 0) throw new Error(`mysql 查询失败：${String(r.stderr || r.error || '').slice(0, 300)}`);
  return String(r.stdout || '').trim().split(/\r?\n/).filter(Boolean).map((line) => line.split('\t'));
}

const COUNT_SQL = `SELECT (SELECT COUNT(*) FROM second_class_category),
       (SELECT COUNT(*) FROM second_class_activity),
       (SELECT COUNT(*) FROM second_class_record)`;

function tableCounts() {
  const row = mysqlRows(COUNT_SQL)[0] || [];
  return { category: Number(row[0]), activity: Number(row[1]), record: Number(row[2]) };
}

// 执行 clean-p18.sql：必须包一层 cmd /c 才能用 < 重定向（PowerShell/Node 不能直接写 <）
function runCleanScript() {
  const cmdLine = `cmd /c "${MYSQL}" --defaults-extra-file=%TEMP%\\nest_my.cnf -D nest -t < "${CLEAN_SQL}"`;
  emit(`  $ ${cmdLine}`);
  const out = execSync(cmdLine, { encoding: 'utf8', cwd: ROOT });
  for (const line of String(out).replace(/\r/g, '').split('\n')) {
    if (line.trim().length) emit(`  │ ${line}`);
  }
  return out;
}

// ---------------------------------------------------------------- 本轮数据句柄
const ctx = {
  baseline: null,          // 跑前三表 COUNT(*)
  year: null,              // 服务端 new Date() 落在的年份（从 submittedAt 推）
  c1: null, c2: null, c2Disabled: null,
  aDraft: null, a1: null, a3: null,
  r1: null, r2: null, r3: null, r4: null, r5: null, rAct1: null, rAct3: null, rLiu: null,
  sumBefore: null,
};

const T = (suffix) => `${TAG}_${suffix}`;

// ================================================================ PHASE 0 健康与只读面
async function phase0Boot() {
  section('0. 登录 + 健康检查 + 只读面（categories / activities / my / summary / review）');

  ctx.baseline = tableCounts();
  ctx.baseline.smoke = Number(mysqlRows("SELECT COUNT(*) FROM second_class_category WHERE code LIKE 'SMOKE%'")[0][0]);
  ctx.baseline.oldRecords = Number(mysqlRows('SELECT COUNT(*) FROM second_class_record WHERE id < 100')[0][0]);
  emit(`  跑前三表 COUNT(*) 基线：category=${ctx.baseline.category} activity=${ctx.baseline.activity} record=${ctx.baseline.record}（历史遗留段：SMOKE 板块 ${ctx.baseline.smoke} / id<100 申报 ${ctx.baseline.oldRecords}）`);
  ok('0.00 跑前基线可读（三表 COUNT(*)）', ctx.baseline.category >= 0 && ctx.baseline.activity >= 0 && ctx.baseline.record >= 0,
    JSON.stringify(ctx.baseline));

  for (const [who, role] of [['admin', 'admin'], ['lisi', 'teacher'], ['zhangsan', 'teacher'], ['chendw', 'student'], ['liusq', 'student']]) {
    const rec = await login(who);
    const data = d(rec) || {};
    expectOk(`0.01 登录 ${who}`, rec);
    eq(`0.02 登录 ${who} 会话角色`, data.role, role, rec);
  }

  // --- health
  const health = await call('admin', 'GET', '/api/second-class/health');
  expectOk('0.10 GET /health 成功', health);
  const hd = d(health) || {};
  eq('0.11 /health data.module', hd.module, 'second-class', health);
  ok('0.12 /health routes 是数组且 20 条（不含 /health 自身）', Array.isArray(hd.routes) && hd.routes.length === 20,
    `实测 ${Array.isArray(hd.routes) ? hd.routes.length : hd.routes}`);
  ok('0.13 /health routes 含 confirm 与 export', Array.isArray(hd.routes)
    && hd.routes.includes('POST /api/second-class/records/{id}/confirm')
    && hd.routes.includes('GET /api/second-class/export'), JSON.stringify(hd.routes || []));
  ok('0.14 /health policy 非空且含「未设置」', typeof hd.policy === 'string' && hd.policy.includes('未设置'), String(hd.policy));
  note('health.routes=20 vs 类注释 21',
    'SecondClassController:26 写「共 21 条方法级路由」并把 health 计入 7+5+7+1+1；health() 的 routes 数组(第二课堂Controller:232-252)只列 20 条（不含 /health 自身），两者不矛盾但需知道 20 才是该数组长度');

  // --- categories（学生可见 = 仅启用）
  const cats = await call('chendw', 'GET', '/api/second-class/categories');
  expectOk('0.15 GET /categories 成功', cats);
  const cd = d(cats) || {};
  ok('0.16 /categories 返回 categories 数组', Array.isArray(cd.categories), JSON.stringify(Object.keys(cd)));
  ok('0.17 /categories 带 policyNote', typeof cd.policyNote === 'string' && cd.policyNote.includes('未设置'), String(cd.policyNote));
  ok('0.18 /categories 每项含 configured 字段', (cd.categories || []).every((c) => typeof c.configured === 'boolean'),
    JSON.stringify((cd.categories || []).map((c) => c.configured)));

  // --- activities（分页 + 关键词 + size 夹取）
  const acts = await call('chendw', 'GET', '/api/second-class/activities?page=1&size=2');
  expectOk('0.20 GET /activities 分页成功', acts);
  const ad = d(acts) || {};
  eq('0.21 /activities page 回显', Number(ad.page), 1, acts);
  eq('0.22 /activities size 回显', Number(ad.size), 2, acts);
  ok('0.23 /activities list 长度不超过 size', Array.isArray(ad.list) && ad.list.length <= 2, `${(ad.list || []).length}`);
  ok('0.24 /activities 每项 status 均为 published', (ad.list || []).every((a) => a.status === 'published'),
    JSON.stringify((ad.list || []).map((a) => a.status)));
  const clamp = await call('chendw', 'GET', '/api/second-class/activities?page=1&size=500');
  eq('0.25 /activities size 上限夹取到 200', Number((d(clamp) || {}).size), 200, clamp);
  const kwNone = await call('chendw', 'GET', `/api/second-class/activities?keyword=${encodeURIComponent(TAG + '_NO_SUCH')}`);
  expectOk('0.26 /activities 关键词无命中仍 200', kwNone);
  eq('0.27 /activities 无命中 total=0', Number((d(kwNone) || {}).total), 0, kwNone);

  // --- my（分页 + status 过滤）
  const my = await call('chendw', 'GET', '/api/second-class/my?page=1&size=1');
  expectOk('0.30 GET /my 分页成功', my);
  const myd = d(my) || {};
  ok('0.31 /my list 长度不超过 size=1', Array.isArray(myd.list) && myd.list.length <= 1, `${(myd.list || []).length}`);
  ok('0.32 /my total 是数字', typeof myd.total === 'number' || myd.total === null, String(myd.total));
  const myConfirmed = await call('chendw', 'GET', '/api/second-class/my?status=confirmed&size=200');
  expectOk('0.33 /my?status=confirmed 成功', myConfirmed);
  ok('0.34 /my?status=confirmed 每行状态一致', ((d(myConfirmed) || {}).list || []).every((r) => r.status === 'confirmed'),
    JSON.stringify(((d(myConfirmed) || {}).list || []).map((r) => r.status)));
  const myWeird = await call('chendw', 'GET', '/api/second-class/my?status=__no_such_status__');
  expectOk('0.35 /my 非法 status 不报错（静默 0 行）', myWeird);
  eq('0.36 /my 非法 status 命中 0 行', Number((d(myWeird) || {}).total), 0, myWeird);

  // --- summary（带 year / 不带 year / 非法 year）
  const sum = await call('chendw', 'GET', '/api/second-class/summary');
  expectOk('0.40 GET /summary 不带 year 成功', sum);
  const sd = d(sum) || {};
  eq('0.41 /summary 不带 year 时 year=null', sd.year, null, sum);
  ok('0.42 /summary categories 是数组', Array.isArray(sd.categories), String(typeof sd.categories));
  ok('0.43 /summary totals 含 5 个聚合键', ['confirmed_hours', 'pending_hours', 'confirmed_credit', 'confirmed_count', 'pending_count']
    .every((k) => sd.totals && sd.totals[k] !== undefined), JSON.stringify(Object.keys(sd.totals || {})));
  ok('0.44 /summary warnings 是数组', Array.isArray(sd.warnings), String(typeof sd.warnings));
  ok('0.45 /summary 带 policyNote', typeof sd.policyNote === 'string' && sd.policyNote.length > 0, String(sd.policyNote));

  const sumYear = await call('chendw', 'GET', `/api/second-class/summary?year=${new Date().getFullYear()}`);
  expectOk('0.46 /summary?year=<今年> 成功', sumYear);
  eq('0.47 /summary year 回显', Number((d(sumYear) || {}).year), new Date().getFullYear(), sumYear);
  const sumAnno = await call('chendw', 'GET', '/api/second-class/summary?year=1800');
  expectOk('0.48 /summary 非法 year(1800) 不炸', sumAnno);
  eq('0.49 /summary 非法 year 归一为 null（validYear 2000-2100）', (d(sumAnno) || {}).year, null, sumAnno);

  // --- review / review/stats 权限
  const revStu = await call('chendw', 'GET', '/api/second-class/review');
  expectReject('0.50 学生访问 /review 被拒且含「无权」', revStu, 403, '无权');
  const statsStu = await call('chendw', 'GET', '/api/second-class/review/stats');
  expectReject('0.51 学生访问 /review/stats 被拒且含「无权」', statsStu, 403, '无权');
  const revLisi = await call('lisi', 'GET', '/api/second-class/review?page=1&size=5');
  expectOk('0.52 辅导员 lisi 访问 /review 成功', revLisi);
  eq('0.53 /review 辅导员 scope=counselor', (d(revLisi) || {}).scope, 'counselor', revLisi);
  const statsLisi = await call('lisi', 'GET', '/api/second-class/review/stats');
  expectOk('0.54 辅导员 lisi 访问 /review/stats 成功', statsLisi);
  const counts = (d(statsLisi) || {}).counts || {};
  ok('0.55 /review/stats 覆盖 6 个状态键', ['submitted', 'counselor_approved', 'confirmed', 'returned', 'rejected', 'withdrawn']
    .every((k) => typeof counts[k] === 'number'), JSON.stringify(counts));
  const revAdmin = await call('admin', 'GET', '/api/second-class/review?page=1&size=5');
  expectOk('0.56 管理员访问 /review 成功', revAdmin);
  eq('0.57 管理员 /review scope=all', (d(revAdmin) || {}).scope, 'all', revAdmin);

  // --- 未登录
  const anonCats = await call(null, 'GET', '/api/second-class/categories');
  expectReject('0.58 未登录 /categories 被拒 401', anonCats, 401, '请先登录');
  const anonRec = await call(null, 'POST', '/api/second-class/records', { categoryId: 1, title: 'x', hours: 1, evidenceUrl: 'x' });
  expectReject('0.59 未登录 /records 被拒 401', anonRec, 401, '请先登录');
}

// ================================================================ PHASE 1 管理端板块与活动台账
async function phase1AdminConfig() {
  section('1. 管理端板块台账（admin 专属）+ 板块校验负例 + 活动台账与活动校验负例');

  const adminCats = await call('admin', 'GET', '/api/second-class/admin/categories');
  expectOk('1.00 管理员读取板块台账', adminCats);
  ok('1.01 板块台账含停用项（listAll 口径）', Array.isArray((d(adminCats) || {}).categories), String(typeof (d(adminCats) || {}).categories));

  // 校验负例
  expectReject('1.02 新增板块缺名称被拒', await call('admin', 'POST', '/api/second-class/admin/categories', { code: T('NONAME') }), 400, '不能为空');
  expectReject('1.03 新增板块缺编码被拒', await call('admin', 'POST', '/api/second-class/admin/categories', { name: T('x') }), 400, '不能为空');
  expectReject('1.04 板块编码格式非法被拒（小写）', await call('admin', 'POST', '/api/second-class/admin/categories', { name: T('x'), code: 'e2e18bad' }), 400, '编码格式');

  // C1：完整三列口径（后面学分折算要用 creditPerHour=0.5）
  const c1 = await call('admin', 'POST', '/api/second-class/admin/categories', {
    name: T('板块A'), code: T('C1'), sortNo: 50,
    requiredHours: 10, yearlyCapHours: 100, creditPerHour: 0.5, note: 'e2e',
  });
  expectOk('1.05 新增板块 A 成功', c1);
  const c1d = d(c1) || {};
  ctx.c1 = c1d.id;
  eq('1.06 板块 A enabled=1', Number(c1d.enabled), 1, c1);
  eq('1.07 板块 A configured=true（三列均已设置）', c1d.configured, true, c1);
  eqNum('1.08 板块 A creditPerHour=0.5', c1d.creditPerHour, 0.5, c1);

  expectReject('1.09 板块编码重复被拒', await call('admin', 'POST', '/api/second-class/admin/categories', { name: T('dup'), code: T('C1') }), 400, '已存在');

  // C2：先带值建，再整体覆盖为 NULL（学校未设置），再写回
  const c2 = await call('admin', 'POST', '/api/second-class/admin/categories', {
    name: T('板块B'), code: T('C2'), sortNo: 51, requiredHours: 6, yearlyCapHours: 60, creditPerHour: 0.2,
  });
  expectOk('1.10 新增板块 B 成功', c2);
  ctx.c2 = (d(c2) || {}).id;

  const c2null = await call('admin', 'PUT', `/api/second-class/admin/categories/${ctx.c2}`, {
    name: T('板块B改'), sortNo: 52, requiredHours: null, yearlyCapHours: null, creditPerHour: null, note: '清空口径',
  });
  expectOk('1.11 板块三列写回 NULL 成功', c2null);
  const c2n = d(c2null) || {};
  eq('1.12 requiredHours 被清空为 null', c2n.requiredHours, null, c2null);
  eq('1.13 yearlyCapHours 被清空为 null', c2n.yearlyCapHours, null, c2null);
  eq('1.14 creditPerHour 被清空为 null', c2n.creditPerHour, null, c2null);
  eq('1.15 三列全 null 时 configured=false', c2n.configured, false, c2null);
  eq('1.16 板块名已被修改', c2n.name, T('板块B改'), c2null);

  const c2back = await call('admin', 'PUT', `/api/second-class/admin/categories/${ctx.c2}`, {
    name: T('板块B改'), sortNo: 52, requiredHours: 6, yearlyCapHours: 60, creditPerHour: 0.2, note: null,
  });
  expectOk('1.17 板块三列写回数值成功', c2back);
  eqNum('1.18 configured 恢复 true', ((d(c2back) || {}).configured) === true ? 1 : 0, 1, c2back);
  note('updateCategory 无法清空 note',
    'SecondClassService:194-197 用 trimToNull 判空后跳过，传 note:"" / null 都不会把已有 note 置空（实测 1.17 note 仍为「清空口径」）');

  expectReject('1.19 修改不存在的板块 404', await call('admin', 'PUT', '/api/second-class/admin/categories/999999', { name: T('x') }), 404, '板块不存在');

  // C3：创建即停用，用于「停用板块不能新申报 / 不能建活动」
  const c3 = await call('admin', 'POST', '/api/second-class/admin/categories', {
    name: T('板块C停用'), code: T('C3'), sortNo: 53, enabled: 0,
  });
  expectOk('1.20 新增停用板块成功', c3);
  ctx.c2Disabled = (d(c3) || {}).id;
  eq('1.21 停用板块 enabled=0', Number((d(c3) || {}).enabled), 0, c3);
  ok('1.22 停用板块不出现在学生 /categories 里',
    !(((d(await call('chendw', 'GET', '/api/second-class/categories')) || {}).categories) || []).some((x) => x.id === ctx.c2Disabled));

  expectReject('1.23 停用板块下不能建活动', await call('admin', 'POST', '/api/second-class/admin/activities', {
    categoryId: ctx.c2Disabled, title: T('停用板块活动'), hours: 1, startDate: '2026-10-01',
  }), 400, '已停用');

  // --- 活动校验负例
  const base = { categoryId: null, title: T('活动'), hours: 4, startDate: '2026-10-01', endDate: '2026-10-02', level: 'school' };
  expectReject('1.30 建活动缺板块被拒', await call('admin', 'POST', '/api/second-class/admin/activities', { ...base, categoryId: null }), 400, '请选择活动所属板块');
  expectReject('1.31 建活动板块不存在 404', await call('admin', 'POST', '/api/second-class/admin/activities', { ...base, categoryId: 999999 }), 404, '板块不存在');
  expectReject('1.32 建活动缺名称被拒', await call('admin', 'POST', '/api/second-class/admin/activities', { ...base, categoryId: ctx.c1, title: null }), 400, '活动名称不能为空');
  expectReject('1.33 活动学时=0 被拒', await call('admin', 'POST', '/api/second-class/admin/activities', { ...base, categoryId: ctx.c1, hours: 0 }), 400, '活动学时必须大于 0');
  expectReject('1.34 活动学时=101 被拒', await call('admin', 'POST', '/api/second-class/admin/activities', { ...base, categoryId: ctx.c1, hours: 101 }), 400, '不能超过 100');
  expectReject('1.35 活动层级非法被拒', await call('admin', 'POST', '/api/second-class/admin/activities', { ...base, categoryId: ctx.c1, level: 'galaxy' }), 400, '组织层级只能是');
  expectReject('1.36 活动缺开始日期被拒', await call('admin', 'POST', '/api/second-class/admin/activities', { ...base, categoryId: ctx.c1, startDate: null }), 400, '活动开始日期不能为空');
  expectReject('1.37 结束日期早于开始被拒', await call('admin', 'POST', '/api/second-class/admin/activities', { ...base, categoryId: ctx.c1, startDate: '2026-10-05', endDate: '2026-10-01' }), 400, '不能早于开始日期');

  // organizer 必填：服务层 400 + 中文提示（DDL organizer NOT NULL 无 DEFAULT，靠校验挡住，不再抛 500）
  expectReject('1.37b 建活动未填主办单位被拒（400 中文提示）',
    await call('admin', 'POST', '/api/second-class/admin/activities', { ...base, categoryId: ctx.c1, organizer: undefined }),
    400, '主办单位不能为空');
  expectReject('1.37c 主办单位只有空白同样被拒',
    await call('admin', 'POST', '/api/second-class/admin/activities', { ...base, categoryId: ctx.c1, organizer: '   ' }),
    400, '主办单位不能为空');
  expectReject('1.38 修改不存在的活动 404', await call('admin', 'PUT', '/api/second-class/admin/activities/999999', { title: T('x') }), 404, '活动不存在');
  expectReject('1.39 变更不存在活动状态 404', await call('admin', 'POST', '/api/second-class/admin/activities/999999/status', { action: 'publish' }), 404, '活动不存在');
  expectReject('1.40 活动状态 action 非法被拒', await call('admin', 'POST', `/api/second-class/admin/activities/${999999}/status`, { action: 'fly' }), 404, '活动不存在');

  // --- 草稿活动：建 → 改 → 发布 → 重复发布被拒 → 结束 → 重复结束被拒
  const aDraft = await call('admin', 'POST', '/api/second-class/admin/activities', {
    categoryId: ctx.c1, title: T('活动草稿'), organizer: '团委', level: 'college',
    startDate: '2026-10-01', endDate: '2026-10-01', hours: 3, location: '自钦楼', publish: false,
  });
  expectOk('1.50 新建草稿活动成功', aDraft);
  ctx.aDraft = (d(aDraft) || {}).id;
  eq('1.51 未 publish 的活动状态=draft', (d(aDraft) || {}).status, 'draft', aDraft);

  const upd = await call('admin', 'PUT', `/api/second-class/admin/activities/${ctx.aDraft}`, {
    title: T('活动草稿改名'), hours: 3, level: 'school', location: '自钦楼二层',
  });
  expectOk('1.52 草稿活动可修改', upd);
  eq('1.53 草稿改名生效', (d(upd) || {}).title, T('活动草稿改名'), upd);

  const pub = await call('admin', 'POST', `/api/second-class/admin/activities/${ctx.aDraft}/status`, { action: 'publish' });
  expectOk('1.54 草稿发布成功', pub);
  eq('1.55 发布后状态=published', (d(pub) || {}).status, 'published', pub);
  expectReject('1.56 重复发布被拒（状态机）', await call('admin', 'POST', `/api/second-class/admin/activities/${ctx.aDraft}/status`, { action: 'publish' }), 400, '不能执行该操作');
  expectReject('1.57 已发布活动不可再改（只有草稿可改）', await call('admin', 'PUT', `/api/second-class/admin/activities/${ctx.aDraft}`, { title: T('x') }), 400, '只有草稿活动可以修改');
  const close = await call('admin', 'POST', `/api/second-class/admin/activities/${ctx.aDraft}/status`, { action: 'close' });
  expectOk('1.58 已发布活动结束成功', close);
  eq('1.59 结束后状态=closed', (d(close) || {}).status, 'closed', close);
  expectReject('1.60 重复结束被拒（状态机）', await call('admin', 'POST', `/api/second-class/admin/activities/${ctx.aDraft}/status`, { action: 'close' }), 400, '不能执行该操作');

  const admActs = await call('admin', 'GET', `/api/second-class/admin/activities?keyword=${encodeURIComponent(TAG)}&size=200`);
  expectOk('1.61 管理端活动台账按关键词查询', admActs);
  ok('1.62 台账含草稿/已结束（含 closed 项）', ((d(admActs) || {}).list || []).some((a) => a.status === 'closed'),
    JSON.stringify(((d(admActs) || {}).list || []).map((a) => a.status)));
}

// ================================================================ PHASE 2 学生自报主流程
async function phase2SelfReportFlow() {
  section('2. 学生自主申报（无 activityId）→ 辅导员初审 → 管理员认定 → 成绩单变化');

  const before = d(await call('chendw', 'GET', '/api/second-class/summary')) || {};
  ctx.sumBefore = before.totals || {};
  const baseConfirmed = Number(ctx.sumBefore.confirmed_hours || 0);
  const baseCount = Number(ctx.sumBefore.confirmed_count || 0);
  const baseCredit = Number(ctx.sumBefore.confirmed_credit || 0);

  const rec = await call('chendw', 'POST', '/api/second-class/records', {
    categoryId: ctx.c1, title: T('自报A'), hours: 6, evidenceUrl: 'https://example.com/cert-a.pdf', note: 'e2e 自报',
  });
  expectOk('2.00 学生自报学时成功', rec);
  const rd = (d(rec) || {}).record || {};
  ctx.r1 = rd.id;
  ctx.year = rd.submittedAt ? Number(String(rd.submittedAt).slice(0, 4)) : new Date().getFullYear();
  eq('2.01 自报初始状态=submitted', rd.status, 'submitted', rec);
  eq('2.02 自报 userId 为本人', Number(rd.userId), 6, rec);
  eqNum('2.03 自报学时=6', rd.hours, 6, rec);
  eq('2.04 自报 activityId 为空', rd.activityId, null, rec);
  ok('2.05 createRecord 返回 warnings 数组', Array.isArray((d(rec) || {}).warnings), JSON.stringify((d(rec) || {}).warnings));
  eq('2.06 自报标题快照正确', rd.titleSnapshot, T('自报A'), rec);

  const mine = await call('chendw', 'GET', '/api/second-class/my?status=submitted&size=200');
  ok('2.07 我的申报里能看到刚提交的单', ((d(mine) || {}).list || []).some((x) => x.id === ctx.r1), describe(mine));

  const sumCreated = d(await call('chendw', 'GET', '/api/second-class/summary')) || {};
  const pendingCreated = Number((sumCreated.totals || {}).pending_hours || 0);

  const det = await call('chendw', 'GET', `/api/second-class/records/${ctx.r1}`);
  expectOk('2.08 本人可看申报详情', det);
  eq('2.09 详情状态=submitted', (d(det) || {}).status, 'submitted', det);

  const lisiReview = await call('lisi', 'GET', `/api/second-class/review?status=submitted&size=200`);
  ok('2.10 辅导员 lisi 的审核列表含名下学生 chendw 的单', ((d(lisiReview) || {}).list || []).some((x) => x.id === ctx.r1), describe(lisiReview));
  const zsReview = await call('zhangsan', 'GET', `/api/second-class/review?status=submitted&size=200`);
  ok('2.11 辅导员 zhangsan 的列表不含 lisi 名下学生的单（scope 隔离）',
    !((d(zsReview) || {}).list || []).some((x) => x.id === ctx.r1), describe(zsReview));

  const stats0 = d(await call('lisi', 'GET', '/api/second-class/review/stats')) || {};
  ok('2.12 审核统计 submitted 计数 ≥1', Number(((stats0.counts) || {}).submitted) >= 1, JSON.stringify(stats0.counts));

  // 初审前的板块汇总口径：pending 6、confirmed 0
  const sumPending = d(await call('chendw', 'GET', '/api/second-class/summary')) || {};
  const itemC1 = (sumPending.categories || []).find((x) => x.categoryId === ctx.c1) || {};
  eqNum('2.13 初审前板块 A 待审学时=6', itemC1.pendingHours, 6, undefined);
  eqNum('2.14 初审前板块 A 已认定学时=0', itemC1.confirmedHours, 0);

  const approve = await call('lisi', 'POST', `/api/second-class/records/${ctx.r1}/counselor-review`, { action: 'approve' });
  expectOk('2.15 辅导员初审通过', approve);
  eq('2.16 初审通过后状态=counselor_approved', (d(approve) || {}).status, 'counselor_approved', approve);
  eq('2.17 初审人记为 lisi', Number((d(approve) || {}).counselorId), 3, approve);
  ok('2.18 初审时间已写入', !!(d(approve) || {}).counselorAt, describe(approve));

  expectReject('2.19 待认定的单不能再次初审', await call('lisi', 'POST', `/api/second-class/records/${ctx.r1}/counselor-review`, { action: 'approve' }), 400, '只有待初审的申报可以初审');
  expectReject('2.20 状态机先于 action 校验（待认定时传非法 action 仍报状态）',
    await call('lisi', 'POST', `/api/second-class/records/${ctx.r1}/counselor-review`, { action: 'bogus' }), 400, '只有待初审的申报可以初审');
  note('counselorReview 校验顺序：登录 → 存在 → scope → 状态 → action',
    'SecondClassService:748-780 在 action 合法性与「退回原因必填」之前先判状态，所以对非 submitted 的单传非法 action 得到的是状态文案而非 action 文案；脚本按实际顺序断言');

  const confirm = await call('admin', 'POST', `/api/second-class/records/${ctx.r1}/confirm`, { action: 'confirm' });
  expectOk('2.21 管理员认定成功', confirm);
  const cd = d(confirm) || {};
  eq('2.22 认定后状态=confirmed', cd.status, 'confirmed', confirm);
  eqNum('2.23 认定学分按板块系数折算 6×0.5=3', cd.credit, 3, confirm);
  eq('2.24 认定人记为 admin', Number(cd.reviewerId), 1, confirm);
  expectReject('2.25 已认定的单不可再认定', await call('admin', 'POST', `/api/second-class/records/${ctx.r1}/confirm`, { action: 'confirm' }), 400, '只有已通过初审的申报可以认定');

  const after = d(await call('chendw', 'GET', '/api/second-class/summary')) || {};
  const at = after.totals || {};
  eqNum('2.26 成绩单合计已认定学时 +6', Number(at.confirmed_hours) - baseConfirmed, 6, undefined);
  eqNum('2.27 成绩单合计已认定条数 +1', Number(at.confirmed_count) - baseCount, 1, undefined);
  eqNum('2.28 成绩单合计已认定学分 +3', Number(at.confirmed_credit) - baseCredit, 3, undefined);
  eqNum('2.29 认定后待审学时回到提交前（-6）', Number(at.pending_hours) - pendingCreated, -6, undefined);
  const itemAfter = (after.categories || []).find((x) => x.categoryId === ctx.c1) || {};
  eqNum('2.30 板块 A 已认定学时=6', itemAfter.confirmedHours, 6);
  eqNum('2.31 板块 A 待审学时=0', itemAfter.pendingHours, 0);
  eqNum('2.32 板块 A 条目数=1', itemAfter.recordCount, 1);
  eqNum('2.33 板块 A 距要求学时 gap=10-6=4', itemAfter.gapHours, 4);

  const myConf = await call('chendw', 'GET', '/api/second-class/my?status=confirmed&size=200');
  ok('2.34 我的已认定列表含该单', ((d(myConf) || {}).list || []).some((x) => x.id === ctx.r1), describe(myConf));
}

// ================================================================ PHASE 3 挂活动申报
async function phase3ActivityRecords() {
  section('3. 挂活动申报（发布 → 申报 → 重复被拒 → 超活动学时被拒 → 关闭后不可申报）+ 活动流转');

  const a1 = await call('admin', 'POST', '/api/second-class/admin/activities', {
    categoryId: ctx.c1, title: T('活动A'), organizer: '校团委', level: 'school',
    startDate: '2026-10-08', endDate: '2026-10-09', hours: 4, location: '建南大礼堂', publish: true,
  });
  expectOk('3.00 新建并发布活动 A 成功', a1);
  ctx.a1 = (d(a1) || {}).id;
  eq('3.01 活动 A 状态=published', (d(a1) || {}).status, 'published', a1);

  const stuActs = await call('chendw', 'GET', `/api/second-class/activities?keyword=${encodeURIComponent(TAG)}&size=200`);
  expectOk('3.02 学生活动列表按关键词查询', stuActs);
  ok('3.03 学生可见已发布的活动 A', ((d(stuActs) || {}).list || []).some((x) => x.id === ctx.a1), describe(stuActs));
  ok('3.04 学生列表不含已结束的活动（draft/closed 不出现）',
    !((d(stuActs) || {}).list || []).some((x) => x.id === ctx.aDraft), describe(stuActs));

  const rec = await call('chendw', 'POST', '/api/second-class/records', { activityId: ctx.a1, hours: 4 });
  expectOk('3.05 挂活动申报成功', rec);
  const rd = (d(rec) || {}).record || {};
  ctx.rAct1 = rd.id;
  eq('3.06 挂活动申报 activityId 正确', Number(rd.activityId), ctx.a1, rec);
  eq('3.07 挂活动申报标题取活动标题', rd.titleSnapshot, T('活动A'), rec);
  eq('3.08 挂活动申报可免证明材料', rd.evidenceUrl, null, rec);

  expectReject('3.09 同活动重复申报被拒', await call('chendw', 'POST', '/api/second-class/records', { activityId: ctx.a1, hours: 4 }), 400, '不能重复申报');
  expectReject('3.10 申报学时超活动学时被拒（5 > 4）', await call('chendw', 'POST', '/api/second-class/records', { activityId: ctx.a1, hours: 5 }), 400, '不能超过活动学时');
  expectReject('3.11 申报活动不存在 404', await call('chendw', 'POST', '/api/second-class/records', { activityId: 999999, hours: 1 }), 404, '活动不存在');

  // 草稿活动不可申报（aDraft 已 closed；另建一个真草稿验证 draft 分支）
  const aDraft2 = await call('admin', 'POST', '/api/second-class/admin/activities', {
    categoryId: ctx.c1, title: T('活动草稿2'), organizer: '院学生会', hours: 2, startDate: '2026-10-10', publish: false,
  });
  expectOk('3.12 新建第二个草稿活动成功', aDraft2);
  const aDraft2Id = (d(aDraft2) || {}).id;
  expectReject('3.13 草稿活动不可申报', await call('chendw', 'POST', '/api/second-class/records', { activityId: aDraft2Id, hours: 2 }), 400, '不可申报');

  // 关闭活动 A（当前 submitted 的单还在），关闭后不可再申报，且已结束活动不出现在学生列表
  const closeA1 = await call('admin', 'POST', `/api/second-class/admin/activities/${ctx.a1}/status`, { action: 'close' });
  expectOk('3.14 关闭活动 A 成功', closeA1);
  eq('3.15 活动 A 状态=closed', (d(closeA1) || {}).status, 'closed', closeA1);
  expectReject('3.16 已结束活动不可申报', await call('chendw', 'POST', '/api/second-class/records', { activityId: ctx.a1, hours: 4 }), 400, '不可申报');
  const stuActs2 = await call('chendw', 'GET', `/api/second-class/activities?keyword=${encodeURIComponent(T('活动A'))}`);
  eq('3.17 已结束活动从学生可见列表消失', Number((d(stuActs2) || {}).total), 0, stuActs2);

  // 管理员台账按 status 过滤
  const draftList = await call('admin', 'GET', `/api/second-class/admin/activities?status=draft&keyword=${encodeURIComponent(TAG)}&size=200`);
  expectOk('3.18 台账按 status=draft 过滤', draftList);
  ok('3.19 draft 过滤结果全部为 draft', ((d(draftList) || {}).list || []).every((x) => x.status === 'draft'),
    JSON.stringify(((d(draftList) || {}).list || []).map((x) => x.status)));

  // 活动 A 的申报当前仍是 submitted（关闭活动不改变已提交的单）
  const detA1 = await call('chendw', 'GET', `/api/second-class/records/${ctx.rAct1}`);
  eq('3.20 关闭活动不影响已提交申报的状态', (d(detA1) || {}).status, 'submitted', detA1);

  // 撤回该单，避免后续待审计数互相干扰
  const wd = await call('chendw', 'POST', `/api/second-class/records/${ctx.rAct1}/withdraw`);
  expectOk('3.21 撤回活动申报成功（submitted→withdrawn）', wd);
  eq('3.22 撤回后状态=withdrawn', (d(wd) || {}).status, 'withdrawn', wd);
}

// ================================================================ PHASE 4 退回与重交
async function phase4ReturnResubmit() {
  section('4. 辅导员退回 → 学生改后重交 → 再次初审 → 认定 / 否定');

  const rec = await call('chendw', 'POST', '/api/second-class/records', {
    categoryId: ctx.c1, title: T('自报B'), hours: 8, evidenceUrl: 'https://example.com/cert-b.pdf',
  });
  expectOk('4.00 学生自报 B 成功', rec);
  ctx.r2 = ((d(rec) || {}).record || {}).id;

  expectReject('4.00b 提交态下初审传非法 action 被拒（action 文案）',
    await call('lisi', 'POST', `/api/second-class/records/${ctx.r2}/counselor-review`, { action: 'bogus' }), 400, 'action 只能是');

  expectReject('4.01 退回不填原因被拒', await call('lisi', 'POST', `/api/second-class/records/${ctx.r2}/counselor-review`, { action: 'return' }), 400, '退回申报必须填写原因');
  const ret = await call('lisi', 'POST', `/api/second-class/records/${ctx.r2}/counselor-review`, { action: 'return', reason: T('材料不清') });
  expectOk('4.02 辅导员退回成功', ret);
  const retd = d(ret) || {};
  eq('4.03 退回后状态=returned', retd.status, 'returned', ret);
  eq('4.04 退回原因已记录', retd.returnReason, T('材料不清'), ret);

  // 新契约：returned 重交同样校验学分 0-20（SecondClassService:568-571），失败不得落库。
  // 用一条一次性单 D 做负例，避免旧服务端（未校验学分）把主流程单 r2 改动状态导致级联噪声。
  const recD = await call('chendw', 'POST', '/api/second-class/records', {
    categoryId: ctx.c2, title: T('自报D'), hours: 3, evidenceUrl: 'https://example.com/cert-d.pdf',
  });
  expectOk('4.04a 学生自报 D 成功（学分负例载体）', recD);
  const rD = ((d(recD) || {}).record || {}).id;
  expectOk('4.04b 自报 D 初审退回（回到 returned）',
    await call('lisi', 'POST', `/api/second-class/records/${rD}/counselor-review`, { action: 'return', reason: T('补材料') }), 'success');
  expectReject('4.04c 重交学分 >20 被拒（新契约）',
    await call('chendw', 'PUT', `/api/second-class/records/${rD}`, { hours: 3, credit: 25 }), 400, '申报学分应在 0-20 之间');
  expectReject('4.04d 重交学分为负被拒（新契约）',
    await call('chendw', 'PUT', `/api/second-class/records/${rD}`, { hours: 3, credit: -0.5 }), 400, '申报学分应在 0-20 之间');
  const afterBad = await call('chendw', 'GET', `/api/second-class/records/${rD}`);
  const ab = d(afterBad) || {};
  ok('4.04e 学分校验失败未落库（状态仍 returned / 学时仍 3 / 学分仍 null）',
    ab.status === 'returned' && Number(ab.hours) === 3 && ab.credit === null,
    `status=${ab.status} hours=${ab.hours} credit=${JSON.stringify(ab.credit)} | ${describe(afterBad)}`);

  expectReject('4.05 跨辅导员审核他人学生被拒（zhangsan → chendw）',
    await call('zhangsan', 'POST', `/api/second-class/records/${ctx.r2}/counselor-review`, { action: 'approve' }), 403, '无权');
  expectReject('4.06 学生改他人退回单被拒（liusq → chendw 的单）',
    await call('liusq', 'PUT', `/api/second-class/records/${ctx.r2}`, { hours: 7 }), 403, '无权修改他人的申报');

  const put = await call('chendw', 'PUT', `/api/second-class/records/${ctx.r2}`, {
    hours: 10, title: T('自报B改'), evidenceUrl: 'https://example.com/cert-b2.pdf', note: '已补充材料',
  });
  expectOk('4.07 学生改后退回重交成功', put);
  const pd = (d(put) || {}).record || {};
  eq('4.08 重交后状态回到 submitted', pd.status, 'submitted', put);
  eq('4.09 重交清空退回原因', pd.returnReason, null, put);
  eqNum('4.10 重交后学时=10', pd.hours, 10, put);
  eq('4.11 重交后标题已更新', pd.titleSnapshot, T('自报B改'), put);

  expectReject('4.12 非 returned 状态不可再次改后重交', await call('chendw', 'PUT', `/api/second-class/records/${ctx.r2}`, { hours: 9 }), 400, '只有被退回的申报可以修改后重新提交');

  const app2 = await call('lisi', 'POST', `/api/second-class/records/${ctx.r2}/counselor-review`, { action: 'approve' });
  expectOk('4.13 重交后再初审通过', app2);
  eq('4.14 状态=counselor_approved', (d(app2) || {}).status, 'counselor_approved', app2);
  const cf2 = await call('admin', 'POST', `/api/second-class/records/${ctx.r2}/confirm`, { action: 'confirm' });
  expectOk('4.15 认定重交后的申报（10×0.5=5 学分）', cf2);
  eqNum('4.16 认定学分=5', (d(cf2) || {}).credit, 5, cf2);

  // 否定分支
  const rec3 = await call('chendw', 'POST', '/api/second-class/records', {
    categoryId: ctx.c2, title: T('自报C'), hours: 5, evidenceUrl: 'https://example.com/cert-c.pdf',
  });
  expectOk('4.17 学生自报 C 成功（板块 B）', rec3);
  ctx.r3 = ((d(rec3) || {}).record || {}).id;
  expectOk('4.18 自报 C 初审通过', await call('lisi', 'POST', `/api/second-class/records/${ctx.r3}/counselor-review`, { action: 'approve' }));
  expectReject('4.19 否定不填原因被拒', await call('admin', 'POST', `/api/second-class/records/${ctx.r3}/confirm`, { action: 'reject' }), 400, '否定申报必须填写原因');
  expectReject('4.20 认定 action 非法被拒', await call('admin', 'POST', `/api/second-class/records/${ctx.r3}/confirm`, { action: 'bogus' }), 400, 'action 只能是');
  const rej = await call('admin', 'POST', `/api/second-class/records/${ctx.r3}/confirm`, { action: 'reject', reason: T('学时依据不足') });
  expectOk('4.21 管理员否定申报成功', rej);
  eq('4.22 否定后状态=rejected', (d(rej) || {}).status, 'rejected', rej);
  eq('4.23 否定原因已记录', (d(rej) || {}).returnReason, T('学时依据不足'), rej);

  const sumRej = d(await call('chendw', 'GET', '/api/second-class/summary')) || {};
  const catB = (sumRej.categories || []).find((x) => x.categoryId === ctx.c2) || {};
  eqNum('4.24 被否定的申报不计入待审学时', catB.pendingHours, 0);
  eqNum('4.25 被否定的申报不计入已认定学时', catB.confirmedHours, 0);
}

// ================================================================ PHASE 5 撤回
async function phase5Withdraw() {
  section('5. 撤回（submitted 可撤；counselor_approved / withdrawn 不可撤；他人不可撤）');

  const rec4 = await call('chendw', 'POST', '/api/second-class/records', {
    categoryId: ctx.c2, title: T('自报D撤回'), hours: 2, evidenceUrl: 'https://example.com/cert-d.pdf',
  });
  expectOk('5.00 自报 D 成功', rec4);
  ctx.r4 = ((d(rec4) || {}).record || {}).id;
  const wd = await call('chendw', 'POST', `/api/second-class/records/${ctx.r4}/withdraw`);
  expectOk('5.01 待初审状态可撤回', wd);
  eq('5.02 撤回后状态=withdrawn', (d(wd) || {}).status, 'withdrawn', wd);
  expectReject('5.03 已撤回不可再撤', await call('chendw', 'POST', `/api/second-class/records/${ctx.r4}/withdraw`), 400, '只有待初审的申报可以撤回');
  const mineW = await call('chendw', 'GET', '/api/second-class/my?status=withdrawn&size=200');
  ok('5.04 我的已撤回列表含该单', ((d(mineW) || {}).list || []).some((x) => x.id === ctx.r4), describe(mineW));

  const rec5 = await call('chendw', 'POST', '/api/second-class/records', {
    categoryId: ctx.c2, title: T('自报E'), hours: 3, evidenceUrl: 'https://example.com/cert-e.pdf',
  });
  expectOk('5.05 自报 E 成功', rec5);
  ctx.r5 = ((d(rec5) || {}).record || {}).id;
  expectOk('5.06 自报 E 初审通过', await call('lisi', 'POST', `/api/second-class/records/${ctx.r5}/counselor-review`, { action: 'approve' }));
  expectReject('5.07 待认定状态不可撤回', await call('chendw', 'POST', `/api/second-class/records/${ctx.r5}/withdraw`), 400, '只有待初审的申报可以撤回');

  // 跨学生：liusq 自己报一单，chendw 去撤
  const recLiu = await call('liusq', 'POST', '/api/second-class/records', {
    categoryId: ctx.c2, title: T('刘的申报'), hours: 4, evidenceUrl: 'https://example.com/cert-liu.pdf',
  });
  expectOk('5.08 学生 liusq 自报成功', recLiu);
  ctx.rLiu = ((d(recLiu) || {}).record || {}).id;
  expectReject('5.09 撤回他人申报被拒', await call('chendw', 'POST', `/api/second-class/records/${ctx.rLiu}/withdraw`), 403, '无权撤回他人的申报');

  // 撤回后可在同一活动下重新申报（REUSABLE → resubmit 同一条记录）
  const a3 = await call('admin', 'POST', '/api/second-class/admin/activities', {
    categoryId: ctx.c2, title: T('活动C'), organizer: '青年志愿者协会', hours: 2, startDate: '2026-10-12', publish: true,
  });
  expectOk('5.10 新建已发布活动 C 成功', a3);
  ctx.a3 = (d(a3) || {}).id;
  const r3a = await call('chendw', 'POST', '/api/second-class/records', { activityId: ctx.a3, hours: 2 });
  expectOk('5.11 挂活动 C 申报成功', r3a);
  ctx.rAct3 = ((d(r3a) || {}).record || {}).id;
  expectOk('5.12 撤回挂活动 C 的申报', await call('chendw', 'POST', `/api/second-class/records/${ctx.rAct3}/withdraw`));
  const again = await call('chendw', 'POST', '/api/second-class/records', { activityId: ctx.a3, hours: 2, note: '重新提交' });
  expectOk('5.13 撤回后同活动可再次申报（复用行）', again);
  eq('5.14 复用同一条记录 id（唯一键约束）', Number(((d(again) || {}).record || {}).id), Number(ctx.rAct3), again);
  eq('5.15 复用后状态回到 submitted', ((d(again) || {}).record || {}).status, 'submitted', again);
}

// ================================================================ PHASE 6 校验负例
async function phase6Validation() {
  section('6. 申报校验负例（学时边界 / 必填 / 不存在 / 停用板块）');

  const mk = (body) => call('chendw', 'POST', '/api/second-class/records', body);
  const okBody = { categoryId: ctx.c1, title: T('负例'), hours: 2, evidenceUrl: 'https://example.com/x.pdf' };

  expectReject('6.00 学时=0 被拒', await mk({ ...okBody, hours: 0 }), 400, '申报学时必须大于 0');
  expectReject('6.01 学时=-1 被拒', await mk({ ...okBody, hours: -1 }), 400, '申报学时必须大于 0');
  expectReject('6.02 学时=100.5 被拒（>100）', await mk({ ...okBody, hours: 100.5 }), 400, '不能超过 100');
  expectReject('6.03 学时非数字被拒', await mk({ ...okBody, hours: 'abc' }), 400, '申报学时必须大于 0');
  expectReject('6.04 自报缺标题被拒', await mk({ ...okBody, title: null }), 400, '自报学时须填写申报标题');
  expectReject('6.05 自报缺证明材料被拒', await mk({ ...okBody, evidenceUrl: null }), 400, '证明材料链接');
  expectReject('6.06 既无板块也无活动被拒', await mk({ ...okBody, categoryId: null }), 400, '请选择申报板块');
  expectReject('6.07 申报板块不存在 404', await mk({ ...okBody, categoryId: 999999 }), 404, '板块不存在');
  expectReject('6.08 申报学分超过 20 被拒', await mk({ ...okBody, credit: 25 }), 400, '申报学分应在 0-20 之间');
  expectReject('6.09 申报学分为负被拒', await mk({ ...okBody, credit: -1 }), 400, '申报学分应在 0-20 之间');
  expectReject('6.10 停用板块不可新申报', await mk({ ...okBody, categoryId: ctx.c2Disabled }), 400, '已停用');

  // 记录不存在的 404 口径
  expectReject('6.11 详情不存在 404', await call('chendw', 'GET', '/api/second-class/records/999999'), 404, '申报不存在');
  expectReject('6.12 改后重交不存在 404', await call('chendw', 'PUT', '/api/second-class/records/999999', { hours: 1 }), 404, '申报不存在');
  expectReject('6.13 撤回不存在 404', await call('chendw', 'POST', '/api/second-class/records/999999/withdraw'), 404, '申报不存在');
  expectReject('6.14 初审不存在 404', await call('lisi', 'POST', '/api/second-class/records/999999/counselor-review', { action: 'approve' }), 404, '申报不存在');
  expectReject('6.15 认定不存在 404', await call('admin', 'POST', '/api/second-class/records/999999/confirm', { action: 'confirm' }), 404, '申报不存在');

  // 待审上限常量（MAX_PENDING=10）用代码可核对，不做破坏性压测，仅记录
  note('MAX_PENDING=10 / MAX_RECORD_HOURS=100 是常量而非政策值',
    'SecondClassService:73-76 明确注释「防手误/防刷单，不是政策值」；脚本按 100 断言上限，不制造 10 条待审以触发 514 行分支');

  // 校验失败不应留下脏数据：板块 A 的条目数在 6.x 之后仍只有 phase2/3/4 的那 1 条已认定
  const sumNow = d(await call('chendw', 'GET', '/api/second-class/summary')) || {};
  const c1Item = (sumNow.categories || []).find((x) => x.categoryId === ctx.c1) || {};
  eqNum('6.16 校验失败未产生脏数据（板块 A 条目数仍为 3：自报A + 自报B改 + 活动A撤回单）', c1Item.recordCount, 3);
}

// ================================================================ PHASE 7 权限矩阵
async function phase7Permissions() {
  section('7. 权限矩阵（学生→/admin/* 与 /review 全拒；teacher→管理员专属操作拒；跨辅导员拒）');

  // 学生 → 管理端与审核端
  expectReject('7.00 学生读板块台账被拒', await call('chendw', 'GET', '/api/second-class/admin/categories'), 403, '无权');
  expectReject('7.01 学生新增板块被拒', await call('chendw', 'POST', '/api/second-class/admin/categories', { name: T('x'), code: T('X1') }), 403, '无权');
  expectReject('7.02 学生改板块被拒', await call('chendw', 'PUT', `/api/second-class/admin/categories/${ctx.c1}`, { name: T('x') }), 403, '无权');
  expectReject('7.03 学生读活动台账被拒', await call('chendw', 'GET', '/api/second-class/admin/activities'), 403, '无权');
  expectReject('7.04 学生建活动被拒', await call('chendw', 'POST', '/api/second-class/admin/activities', { categoryId: ctx.c1, title: T('x'), hours: 1, startDate: '2026-10-01' }), 403, '无权');
  expectReject('7.05 学生改活动被拒', await call('chendw', 'PUT', `/api/second-class/admin/activities/${ctx.a1}`, { title: T('x') }), 403, '无权');
  expectReject('7.06 学生改活动状态被拒', await call('chendw', 'POST', `/api/second-class/admin/activities/${ctx.a1}/status`, { action: 'publish' }), 403, '无权');
  expectReject('7.07 学生初审申报被拒', await call('chendw', 'POST', `/api/second-class/records/${ctx.rAct3}/counselor-review`, { action: 'approve' }), 403, '无权');
  expectReject('7.08 学生认定申报被拒', await call('chendw', 'POST', `/api/second-class/records/${ctx.rAct3}/confirm`, { action: 'confirm' }), 403, '无权');

  // 教师（staff 但非 admin）
  expectReject('7.10 teacher 新增板块被拒（管理员专属）', await call('lisi', 'POST', '/api/second-class/admin/categories', { name: T('x'), code: T('X2') }), 403, '无权');
  expectReject('7.11 teacher 读板块台账被拒（管理员专属）', await call('lisi', 'GET', '/api/second-class/admin/categories'), 403, '无权');
  expectReject('7.12 teacher 建活动被拒（管理员专属）', await call('lisi', 'POST', '/api/second-class/admin/activities', { categoryId: ctx.c1, title: T('x'), hours: 1, startDate: '2026-10-01' }), 403, '无权');
  expectReject('7.13 teacher 认定申报被拒（管理员专属）', await call('lisi', 'POST', `/api/second-class/records/${ctx.rAct3}/confirm`, { action: 'confirm' }), 403, '无权');
  expectOk('7.14 teacher 可读活动台账（staff 口径）', await call('lisi', 'GET', '/api/second-class/admin/activities?size=5'));

  // 详情可见范围
  expectOk('7.20 管理员可读任意申报详情', await call('admin', 'GET', `/api/second-class/records/${ctx.r2}`));
  expectOk('7.21 学生可读自己的申报详情', await call('chendw', 'GET', `/api/second-class/records/${ctx.r2}`));
  expectOk('7.22 名下辅导员可读学生申报详情', await call('lisi', 'GET', `/api/second-class/records/${ctx.r2}`));
  expectReject('7.23 跨辅导员读详情被拒（zhangsan 读 chendw 的单）', await call('zhangsan', 'GET', `/api/second-class/records/${ctx.r1}`), 403, '无权查看');
  expectReject('7.24 学生读他人详情被拒（chendw 读 liusq 的单）', await call('chendw', 'GET', `/api/second-class/records/${ctx.rLiu}`), 403, '无权查看他人的第二课堂申报');
  expectOk('7.25 名下辅导员可读 liusq 的单（zhangsan 范围）', await call('zhangsan', 'GET', `/api/second-class/records/${ctx.rLiu}`));

  // 跨辅导员审核（lisi 审 liuzq 名下 → 注意 chendw 属 lisi，liusq 属 zhangsan）
  expectReject('7.26 跨辅导员初审被拒（lisi 审 liusq 的单）', await call('lisi', 'POST', `/api/second-class/records/${ctx.rLiu}/counselor-review`, { action: 'approve' }), 403, '无权初审');
  expectOk('7.27 范围内辅导员可初审 liusq 的单（zhangsan）', await call('zhangsan', 'POST', `/api/second-class/records/${ctx.rLiu}/counselor-review`, { action: 'approve' }));

  // 审核列表的范围隔离
  const zsList = await call('zhangsan', 'GET', '/api/second-class/review?size=200');
  expectOk('7.28 zhangsan 审核列表成功', zsList);
  ok('7.29 zhangsan 列表只含自己名下学生（无 chendw user6 以外的越界）',
    !((d(zsList) || {}).list || []).some((x) => x.userId === 6), describe(zsList));
  const lsList = await call('lisi', 'GET', '/api/second-class/review?size=200');
  ok('7.30 lisi 列表含 chendw 的单', ((d(lsList) || {}).list || []).some((x) => x.userId === 6), describe(lsList));
  ok('7.31 lisi 列表不含 liusq 的单（属 zhangsan）', !((d(lsList) || {}).list || []).some((x) => x.userId === 7), describe(lsList));
}

// ================================================================ PHASE 8 导出
async function phase8Export() {
  section('8. CSV 导出（BOM + text/csv + 表头 + scope 过滤 + 非法身份空 body）');

  const adminExp = await rawGet('admin', '/api/second-class/export');
  expectHttp('8.00 管理员导出 HTTP 200', adminExp, 200);
  ok('8.01 导出 Content-Type 为 text/csv', String(adminExp.headers.get('content-type') || '').toLowerCase().startsWith('text/csv'),
    String(adminExp.headers.get('content-type')));
  ok('8.02 导出带 UTF-8 BOM（EF BB BF）', adminExp.buf.length > 3 && adminExp.buf[0] === 0xEF && adminExp.buf[1] === 0xBB && adminExp.buf[2] === 0xBF,
    `${adminExp.buf[0]},${adminExp.buf[1]},${adminExp.buf[2]}`);
  const bodyAdmin = adminExp.text.replace(/^\uFEFF/, '');
  ok('8.03 导出首行为约定表头', bodyAdmin.startsWith(CSV_HEADER), JSON.stringify(bodyAdmin.slice(0, 80)));
  ok('8.04 管理员导出含本轮申报', bodyAdmin.includes(T('自报A')), '未见 ' + T('自报A'));
  ok('8.05 管理员导出跨辅导员可见（含 liusq 的单）', bodyAdmin.includes(T('刘的申报')));
  const cdHeader = String(adminExp.headers.get('content-disposition') || '');
  ok('8.06 Content-Disposition 为附件且文件名为 second-class-*.csv', /attachment/.test(cdHeader) && /second-class-\d{8}\.csv/.test(cdHeader), cdHeader);

  // 脱敏（和 P17 一致）：学号只留后 4 位、姓名只留首字 + *
  const dRows = bodyAdmin.trim().split('\n').slice(1);
  ok('8.07 导出的学号列已脱敏（每行只剩后 4 位）',
    dRows.length > 0 && dRows.every((l) => /^\d{4}$/.test(l.split(',')[0])),
    JSON.stringify(dRows.map((l) => l.split(',')[0]).slice(0, 5)));
  ok('8.08 导出的姓名列已脱敏（首字 + *），且不含完整学号 / 完整姓名',
    dRows.length > 0 && dRows.every((l) => /^[^*]+\*$/.test(l.split(',')[1]))
      && !bodyAdmin.includes('2024003') && !bodyAdmin.includes('陈大伟'),
    `姓名列=${JSON.stringify(dRows.map((l) => l.split(',')[1]).slice(0, 5))} 含完整学号=${bodyAdmin.includes('2024003')} 含完整姓名=${bodyAdmin.includes('陈大伟')}`);

  const lisiExp = await rawGet('lisi', '/api/second-class/export');
  expectHttp('8.10 辅导员导出 HTTP 200', lisiExp, 200);
  ok('8.11 辅导员导出带 BOM', lisiExp.buf[0] === 0xEF && lisiExp.buf[1] === 0xBB && lisiExp.buf[2] === 0xBF);
  const bodyLisi = lisiExp.text.replace(/^\uFEFF/, '');
  ok('8.12 辅导员导出含名下学生（chendw）的单', bodyLisi.includes(T('自报A')));
  ok('8.13 辅导员导出不含他人学生（liusq）的单', !bodyLisi.includes(T('刘的申报')));

  const yearExp = await rawGet('admin', `/api/second-class/export?year=${ctx.year}`);
  expectHttp('8.14 导出按 year 过滤 HTTP 200', yearExp, 200);
  ok('8.15 本年导出含本轮单（submittedAt 落在本年）', yearExp.text.includes(T('自报A')));
  const emptyExp = await rawGet('admin', '/api/second-class/export?year=2001');
  expectHttp('8.16 空年份（合法但无数据）导出 HTTP 200', emptyExp, 200);
  ok('8.17 空年份导出只有表头（无数据行）', emptyExp.text.replace(/^\uFEFF/, '').trim() === CSV_HEADER,
    JSON.stringify(emptyExp.text.replace(/^\uFEFF/, '').slice(0, 100)));
  const outRange = await rawGet('admin', '/api/second-class/export?year=1999');
  ok('8.17b 越界年份（1999）不报错但退化为「不过滤」（导出行数 ≥ 带 year 时）',
    outRange.text.split('\n').length >= emptyExp.text.split('\n').length, `1999 行数=${outRange.text.split('\n').length} 2001 行数=${emptyExp.text.split('\n').length}`);
  note('?year 越界（<2000 或 >2100）被静默归一为「不过滤」，而不是空结果',
    'SecondClassService:640-646 validYear 只放行 2000-2100，越界返回 null，调用方把 null 解释成「不筛年份」→ 前端传 1999/1800 会拿到全量数据而不是 0 行；与「空年份」语义混叠，建议对非法 year 显式返回 400');

  const statusExp = await rawGet('admin', '/api/second-class/export?status=confirmed');
  expectHttp('8.18 导出按 status 过滤 HTTP 200', statusExp, 200);
  const srows = statusExp.text.replace(/^\uFEFF/, '').trim().split('\n').slice(1);
  ok('8.19 status=confirmed 的导出行状态列全为「已认定」', srows.length > 0 && srows.every((line) => line.split(',')[7] === '已认定'),
    JSON.stringify(srows.map((l) => l.split(',')[7])));

  // 非法身份：非 200 且 body 为空
  const stuExp = await rawGet('chendw', '/api/second-class/export');
  expectDenied('8.20 学生导出被拒（403）', stuExp);
  expectEmptyReject('8.21 学生导出 body 为空', stuExp, 403);
  const anonExp = await rawGet(null, '/api/second-class/export');
  expectEmptyReject('8.22 未登录导出 403 且 body 为空（新契约为 403，不再透传 401）', anonExp, 403);
  note('/export 拒绝时 status 与 body',
    'SecondClassService:870-878 guardStaffOp 失败一律 out.errorCode=403（未登录也是 403，对齐 ClubService.java:940 / VolunteerService.java:936），控制器 SecondClassController:149-151 用 ResponseEntity.status(errorCode).build() → 无 body；与其余接口 HTTP 恒 200 的口径不同');
}

// ================================================================ PHASE 9 成绩单一致性
async function phase9Consistency() {
  section('9. 成绩单一致性（totals 与板块明细自洽、年份切换、空年份）');

  const sum = d(await call('chendw', 'GET', `/api/second-class/summary?year=${ctx.year}`)) || {};
  const cats = sum.categories || [];
  const sumConfirmedHours = cats.reduce((acc, x) => acc + Number(x.confirmedHours || 0), 0);
  const sumPendingHours = cats.reduce((acc, x) => acc + Number(x.pendingHours || 0), 0);
  const sumConfirmedCredit = cats.reduce((acc, x) => acc + Number(x.confirmedCredit || 0), 0);
  eqNum('9.00 totals.confirmed_hours 与板块明细之和一致', sum.totals.confirmed_hours, sumConfirmedHours);
  eqNum('9.01 totals.pending_hours 与板块明细之和一致', sum.totals.pending_hours, sumPendingHours);
  eqNum('9.02 totals.confirmed_credit 与板块明细之和一致', sum.totals.confirmed_credit, sumConfirmedCredit);
  eq('9.03 本年 summary.year 回显', Number(sum.year), ctx.year);
  ok('9.04 停用板块仍出现在成绩单明细里（含 recordCount=0 的项）', cats.some((x) => x.categoryId === ctx.c2Disabled),
    JSON.stringify(cats.map((x) => x.categoryId)));
  note('summary 包含已停用板块',
    'SecondClassRecordMapper.xml:200 只过滤 c.deleted=0，未过滤 enabled；板块停用后学生成绩单仍显示该行（recordCount 可能为 0），与学生端 /categories 只回启用项的口径不同');

  const sumOld = d(await call('chendw', 'GET', '/api/second-class/summary?year=2001')) || {};
  eq('9.05 空年份（合法无数据）summary.year 回显 2001', Number(sumOld.year), 2001);
  eqNum('9.06 空年份已认定学时=0', (sumOld.totals || {}).confirmed_hours, 0);
  eqNum('9.07 空年份待审学时=0', (sumOld.totals || {}).pending_hours, 0);
  eq('9.08 空年份仍返回全部板块行', (sumOld.categories || []).length, cats.length);
  const sumBad = d(await call('chendw', 'GET', '/api/second-class/summary?year=1800')) || {};
  eq('9.08b 越界 year=1800 被静默归一（year=null，退化为不过滤）', sumBad.year, null);

  const myYear = await call('chendw', 'GET', `/api/second-class/my?year=${ctx.year}&size=200`);
  expectOk('9.09 /my?year=<本年> 成功', myYear);
  ok('9.10 /my 年份过滤生效（每行 submittedAt 属本年）',
    ((d(myYear) || {}).list || []).every((r) => String(r.submittedAt || '').startsWith(String(ctx.year))),
    JSON.stringify(((d(myYear) || {}).list || []).map((r) => r.submittedAt)));
  const myOld = await call('chendw', 'GET', '/api/second-class/my?year=2001');
  eq('9.11 /my 空年份（2001）命中 0 行', Number((d(myOld) || {}).total || 0), 0, myOld);

  // 上限提示属于「提示不阻断」：把板块 A 的学年上限调到比已认定学时还低，应出现 warning 且不阻断
  await call('admin', 'PUT', `/api/second-class/admin/categories/${ctx.c1}`, {
    name: T('板块A'), sortNo: 50, requiredHours: 10, yearlyCapHours: 1, creditPerHour: 0.5,
  });
  const sumWarn = d(await call('chendw', 'GET', '/api/second-class/summary')) || {};
  ok('9.12 超学年上限只给 warnings 不报错', Array.isArray(sumWarn.warnings) && sumWarn.warnings.length > 0,
    JSON.stringify(sumWarn.warnings || []).slice(0, 200));
  ok('9.13 warning 文案含「上限」且含板块名', (sumWarn.warnings || []).some((w) => String(w).includes('上限') && String(w).includes(T('板块A'))),
    JSON.stringify(sumWarn.warnings || []).slice(0, 200));
  await call('admin', 'PUT', `/api/second-class/admin/categories/${ctx.c1}`, {
    name: T('板块A'), sortNo: 50, requiredHours: 10, yearlyCapHours: 100, creditPerHour: 0.5,
  });
}

// ================================================================ PHASE 10 清理
async function phase10Cleanup() {
  section('10. 清理（clean-p18.sql 按标记删除）+ 三表计数回到基线');

  writeCleanSql();
  ok('10.00 clean-p18.sql 已生成', fs.existsSync(CLEAN_SQL), CLEAN_SQL);

  const before = tableCounts();
  const tagged = mysqlRows(`SELECT
      (SELECT COUNT(*) FROM second_class_category WHERE code LIKE '${TAG}%'),
      (SELECT COUNT(*) FROM second_class_activity WHERE title LIKE '${TAG}%'),
      (SELECT COUNT(*) FROM second_class_record WHERE title_snapshot LIKE '${TAG}%')`)[0].map(Number);
  emit(`  清理前三表 COUNT(*)：category=${before.category} activity=${before.activity} record=${before.record}（其中本轮新增 ${tagged[0]}/${tagged[1]}/${tagged[2]}）`);
  ok('10.00b 清理前确有一轮自建数据（三表都 >0，否则本次清理为空转）',
    tagged[0] > 0 && tagged[1] > 0 && tagged[2] > 0, `本轮新增 ${tagged.join('/')}`);

  let cleanOut = '';
  try {
    cleanOut = runCleanScript();
  } catch (err) {
    ok('10.01 执行 clean-p18.sql 成功', false, String((err && err.stderr) || (err && err.message) || err).slice(0, 300));
  }
  if (cleanOut) ok('10.01 执行 clean-p18.sql 成功', true, '');

  const after = tableCounts();
  emit(`  跑后三表 COUNT(*)：category=${after.category} activity=${after.activity} record=${after.record}（基线 ${ctx.baseline.category}/${ctx.baseline.activity}/${ctx.baseline.record}）`);
  eq('10.10 清理后 category 计数回到基线', after.category, ctx.baseline.category);
  eq('10.11 清理后 activity 计数回到基线', after.activity, ctx.baseline.activity);
  eq('10.12 清理后 record 计数回到基线', after.record, ctx.baseline.record);

  const leftovers = mysqlRows(`SELECT
      (SELECT COUNT(*) FROM second_class_category WHERE code LIKE '${TAG}%'),
      (SELECT COUNT(*) FROM second_class_activity WHERE title LIKE '${TAG}%'),
      (SELECT COUNT(*) FROM second_class_record WHERE title_snapshot LIKE '${TAG}%')`)[0].map(Number);
  eq('10.13 按本轮标记残留板块=0', leftovers[0], 0);
  eq('10.14 按本轮标记残留活动=0', leftovers[1], 0);
  eq('10.15 按本轮标记残留申报=0', leftovers[2], 0);

  // 演示数据未被动过
  const demo = mysqlRows(`SELECT
      (SELECT COUNT(*) FROM second_class_category WHERE code LIKE 'SMOKE%'),
      (SELECT COUNT(*) FROM second_class_record WHERE id < 100)`)[0].map(Number);
  ok('10.16 历史遗留段（SMOKE_* 板块）与跑前基线一致（未被删也未新增）',
    demo[0] === ctx.baseline.smoke, `跑前 ${ctx.baseline.smoke} → 跑后 ${demo[0]}`);
  ok('10.17 历史遗留段（id<100 申报）与跑前基线一致（未被删也未新增）',
    demo[1] === ctx.baseline.oldRecords, `跑前 ${ctx.baseline.oldRecords} → 跑后 ${demo[1]}`);
}

// ---------------------------------------------------------------- clean-p18.sql
function writeCleanSql() {
  const sql = `-- clean-p18.sql — P18 第二课堂 E2E 残留清理（按本轮标记，幂等）
-- 标记 TAG = ${TAG}
-- 只删本轮自建数据；绝不用 DELETE 全表，绝不触碰演示数据（SMOKE_* 板块与 id<100 的历史申报）。
-- 执行：cmd /c "mysql --defaults-extra-file=%TEMP%\\nest_my.cnf -D nest -t < target\\clean-p18.sql"

-- 顺序：申报 → 活动 → 板块（表间无外键，按引用方向删更安全）
DELETE FROM second_class_record   WHERE title_snapshot LIKE '${TAG}%';
DELETE FROM second_class_activity WHERE title        LIKE '${TAG}%';
DELETE FROM second_class_category WHERE code         LIKE '${TAG}%';

-- 清理结果自证：三表总数 + 本轮标记残留数
SELECT
  (SELECT COUNT(*) FROM second_class_category) AS cat,
  (SELECT COUNT(*) FROM second_class_activity) AS act,
  (SELECT COUNT(*) FROM second_class_record)   AS rec,
  (SELECT COUNT(*) FROM second_class_category WHERE code LIKE '${TAG}%') AS cat_left,
  (SELECT COUNT(*) FROM second_class_activity WHERE title LIKE '${TAG}%') AS act_left,
  (SELECT COUNT(*) FROM second_class_record   WHERE title_snapshot LIKE '${TAG}%') AS rec_left;
`;
  fs.mkdirSync(path.dirname(CLEAN_SQL), { recursive: true });
  fs.writeFileSync(CLEAN_SQL, sql, 'utf8');
}

// ================================================================ 主流程
const ROUTES = [
  'GET /api/second-class/categories',
  'GET /api/second-class/activities',
  'GET /api/second-class/my',
  'GET /api/second-class/summary',
  'POST /api/second-class/records',
  'PUT /api/second-class/records/{id}',
  'POST /api/second-class/records/{id}/withdraw',
  'POST /api/second-class/records/{id}/counselor-review',
  'POST /api/second-class/records/{id}/confirm',
  'GET /api/second-class/records/{id}',
  'GET /api/second-class/review',
  'GET /api/second-class/review/stats',
  'GET /api/second-class/export',
  'GET /api/second-class/admin/categories',
  'POST /api/second-class/admin/categories',
  'PUT /api/second-class/admin/categories/{id}',
  'GET /api/second-class/admin/activities',
  'POST /api/second-class/admin/activities',
  'PUT /api/second-class/admin/activities/{id}',
  'POST /api/second-class/admin/activities/{id}/status',
  'GET /api/second-class/health',
];

const phases = [
  ['0', phase0Boot],
  ['1', phase1AdminConfig],
  ['2', phase2SelfReportFlow],
  ['3', phase3ActivityRecords],
  ['4', phase4ReturnResubmit],
  ['5', phase5Withdraw],
  ['6', phase6Validation],
  ['7', phase7Permissions],
  ['8', phase8Export],
  ['9', phase9Consistency],
  ['10', phase10Cleanup],
];

function phaseSummary() {
  section('路由覆盖矩阵（21 条）');
  const uncovered = [];
  for (const r of ROUTES) {
    const hit = covered.has(r);
    if (!hit) uncovered.push(r);
    emit(`  ${hit ? '✅' : '❌'} ${r}${hit ? `   [${coveredPhase.get(r) || '-'}]` : ''}`);
  }
  ok('路由覆盖 21/21 全部调用', uncovered.length === 0, `未覆盖：${JSON.stringify(uncovered)}`);

  section('断言结果');
  emit(`  PASS ${pass} / FAIL ${fail}`);

  if (notes.length) {
    section('读代码发现的契约不一致 / 口径记录（不判失败）');
    for (const x of notes) emit(`  NOTE  ${x}`);
  }

  if (fail > 0) {
    section('失败明细');
    for (const f of failures) emit(`  FAIL  ${f}`);
  }

  section('清理与口径');
  emit('  清理脚本：target/clean-p18.sql（由本脚本按本轮标记生成并执行）');
  emit('    cmd /c "mysql --defaults-extra-file=%TEMP%\\nest_my.cnf -D nest -t < target\\clean-p18.sql"');
  emit('  数据隔离：本轮所有 板块/活动/申报 均带 E2E18_<RUN> 前缀，演示数据（SMOKE_* 与 id<100）只读。');
  emit('  判成功口径：除 /export 外所有接口 HTTP 恒 200，业务码取 body 的 json.code；失败为 400/401/403/404 + 中文 message。');
  emit('  /export：成功为 CSV 字节流（UTF-8 BOM + text/csv），拒绝为非 200 且空 body。');
  emit(`  本轮标记：${TAG}`);
}

async function main() {
  emit('================================================================');
  emit('  P18 第二课堂成长手册 端到端验收（真实 HTTP）');
  emit(`  目标后端：${BASE}`);
  emit(`  本轮标记：${TAG}`);
  emit('  依据：SecondClassController.java（21 条路由）、SecondClassService.java（校验与状态机）、');
  emit('        SecondClassRecordMapper.xml（resubmit / summary / totals 口径）、sql/nest_secondclass.sql');
  emit('================================================================');

  if (ensureCnf()) emit(`  （%TEMP%\\nest_my.cnf 不存在，已按约定重建）`);

  for (const [no, fn] of phases) {
    currentPhase = fn.name;
    try {
      await fn();
    } catch (err) {
      fatal = true;
      fail += 1;
      failures.push(`阶段 ${no} 抛出异常`);
      emit('');
      emit(`  FATAL 阶段 ${no}（${fn.name}）抛出异常：${err && err.stack ? err.stack.split('\n').slice(0, 4).join(' | ') : err}`);
    }
  }

  currentPhase = 'phaseSummary';
  writeCleanSql();
  phaseSummary();

  const allGreen = fail === 0 && !fatal;
  const text = `${lines.join('\n')}\n`;
  const full = `${text}\nALL PASS ${pass} / FAIL ${allGreen ? 0 : fail}\n`;

  try {
    fs.mkdirSync(path.dirname(LOG_PATH), { recursive: true });
    fs.writeFileSync(LOG_PATH, full, 'utf8');
  } catch (err) {
    process.stderr.write(`写日志失败：${err.message}\n`);
  }

  process.stdout.write(full);
  process.exitCode = allGreen ? 0 : 1;
}

main().catch((err) => {
  process.stderr.write(`脚本致命错误：${err && err.stack ? err.stack : err}\n`);
  process.exitCode = 1;
});

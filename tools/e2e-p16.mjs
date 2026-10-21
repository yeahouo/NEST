#!/usr/bin/env node
/**
 * P16 学生党建（`/api/party/**`，38 条方法级路由）端到端验收脚本。
 *
 * 依据（硬口径）：
 *   * 统一响应：除 `GET /api/party/export` 外，所有接口 **HTTP 200 + {code,message,data}**，
 *     业务失败用 code（400/401/403/404）——断言必须打 json.code，不认同 HTTP 状态；
 *   * `GET /api/party/export?type=stages|applications` 返回 text/csv 字节流（**带 UTF-8 BOM**，
 *     失败回 401/403/400 空体）；
 *   * 学生端由**服务层**抹掉支部内部考察意见/推优讨论（§11.2-4），并只能看本人数据；
 *   * 阶段推进只向前 + CAS（§11.2-9）；公示 close 只接受 publicizing，
 *     **已结束再 close 必须 400**（不做幂等 200）；
 *   * 期限文案两条来源并存（国标 S24 / 校规 S23），平台不替学校选（§11.2-3）。
 *
 * 可重复跑：
 *   * 每轮生成时间戳唯一数据（TAG=`e2e-p16-<base36 时间戳>`），申请/公示/班次/材料/考察都带 TAG；
 *   * 申请登记用**本轮专用合成 userId**（`SYN`，≥990000000，不属于任何演示学生），
 *     因此「一生一条申请」的唯一性约束不会让第二轮失败，也不改动演示数据（用户 1..8 / id 89001-89079）；
 *   * 模块 38 条路由里**没有 DELETE**，脚本无法自清理 —— 文末打印清理 SQL（不自动执行）。
 *
 * 用法：node tools/e2e-p16.mjs
 * 输出：控制台 + target/e2e-p16.log（全绿时另存 target/e2e-p16-final.log）；
 *       有 FAIL 时进程退出码非 0（结尾 PROCESS EXIT）。
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASE = 'http://127.0.0.1:8080';
const LOG_PATH = path.join(__dirname, '..', 'target', 'e2e-p16.log');
const FINAL_LOG_PATH = path.join(__dirname, '..', 'target', 'e2e-p16-final.log');

const RUN = Date.now().toString(36);
const TAG = `e2e-p16-${RUN}`;
/** 本轮专用合成学生 userId：不落在演示用户 1..8 上，避免污染演示数据、也避开「一生一条申请」的唯一约束。 */
const SYN = 990000000 + (Date.now() % 10000000);
const PASSWORD = '123456';

function fmt(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
function addDays(s, n) {
  const d = new Date(`${s}T00:00:00`);
  d.setDate(d.getDate() + n);
  return fmt(d);
}
/** 与服务端 PartyService.addWorkdays 同口径：从起始日次日起算，跳过周六周日。 */
function addWorkdays(s, n) {
  const d = new Date(`${s}T00:00:00`);
  let counted = 0;
  while (counted < n) {
    d.setDate(d.getDate() + 1);
    const w = d.getDay();
    if (w !== 0 && w !== 6) counted += 1;
  }
  return fmt(d);
}
function dayDiff(a, b) {
  return Math.round((new Date(`${a}T00:00:00`) - new Date(`${b}T00:00:00`)) / 86400000);
}

const TODAY = fmt(new Date());

// ---------------------------------------------------------------- 断言基础设施

const lines = [];
const failures = [];
const divergences = [];
let pass = 0;
let fail = 0;
let fatal = false;
const covered = new Set();
const cookies = {};

const ROUTES = [
  'POST /api/party/applications',
  'GET /api/party/applications',
  'GET /api/party/applications/{id}',
  'PUT /api/party/applications/{id}',
  'GET /api/party/stages',
  'GET /api/party/stages/mine',
  'GET /api/party/stages/{id}',
  'POST /api/party/stages/{id}/advance',
  'POST /api/party/stages/{id}/rollback',
  'POST /api/party/stages/{id}/publicity',
  'PUT /api/party/stages/{id}/internal',
  'POST /api/party/observations/thought-report',
  'POST /api/party/observations',
  'GET /api/party/observations/mine',
  'GET /api/party/observations',
  'POST /api/party/observations/{id}/review',
  'POST /api/party/trainings',
  'GET /api/party/trainings',
  'GET /api/party/trainings/mine',
  'GET /api/party/trainings/stats',
  'GET /api/party/trainings/{id}',
  'PUT /api/party/trainings/{id}',
  'GET /api/party/training-members',
  'POST /api/party/trainings/{id}/signup',
  'POST /api/party/training-members/{id}/review',
  'POST /api/party/training-members/{id}/complete',
  'PUT /api/party/training-members/{id}/cert',
  'POST /api/party/publicities',
  'GET /api/party/publicities',
  'GET /api/party/publicities/{id}',
  'PUT /api/party/publicities/{id}',
  'POST /api/party/publicities/{id}/publish',
  'POST /api/party/publicities/{id}/close',
  'POST /api/party/materials',
  'GET /api/party/materials',
  'POST /api/party/materials/{id}/submit',
  'GET /api/party/stats',
  'GET /api/party/export',
];

function section(title) {
  lines.push('');
  lines.push(`===== ${title} =====`);
}

function ok(name, condition, detail) {
  if (condition) {
    pass += 1;
    lines.push(`PASS  ${name}`);
  } else {
    fail += 1;
    failures.push({ name, detail: detail || '' });
    lines.push(`FAIL  ${name}${detail ? `  ← ${detail}` : ''}`);
  }
  return !!condition;
}

function tpl(p) {
  return p.split('?')[0].replace(/\/\d+/g, '/{id}');
}

function describe(rec) {
  if (!rec) return '(无响应)';
  const bodyText = rec.body === undefined || rec.body === null ? '' : ` body=${JSON.stringify(rec.body)}`;
  return `${rec.who || 'anon'} ${rec.method} ${rec.path}${bodyText} → HTTP ${rec.status} ${
    (rec.text === undefined ? '' : String(rec.text)).slice(0, 800)
  }`;
}

function check(name, condition, rec, extra) {
  const detail = extra ? `${extra} | ${describe(rec)}` : describe(rec);
  return ok(name, condition, detail);
}

/** 断言：HTTP 200 + json.code=want + message 含 msgPart。 */
function expectCode(name, rec, want, msgPart) {
  const j = rec && rec.json;
  const httpOk = rec && rec.status === 200;
  const codeOk = !!j && j.code === want;
  const msgOk = msgPart ? !!(j && typeof j.message === 'string' && j.message.includes(msgPart)) : true;
  const expect = `期望 HTTP 200 + code=${want}${msgPart ? ` + message 含「${msgPart}」` : ''}`;
  return check(name, httpOk && codeOk && msgOk, rec, expect);
}

function expectOk(name, rec, msgPart) {
  return expectCode(name, rec, 200, msgPart);
}

function expectHttp(name, rec, status, extra) {
  return check(name, rec && rec.status === status, rec, extra || `期望 HTTP ${status}`);
}

function eq(name, actual, expected, rec) {
  return check(name, actual === expected, rec, `期望 ${JSON.stringify(expected)}，实测 ${JSON.stringify(actual)}`);
}

function d(rec) {
  return rec && rec.json ? rec.json.data : undefined;
}

function keysOf(v) {
  return v && typeof v === 'object' && !Array.isArray(v) ? Object.keys(v) : [];
}

function hasText(rec, needle) {
  return !!(rec && typeof rec.text === 'string' && rec.text.includes(needle));
}

// ---------------------------------------------------------------- HTTP

async function call(who, method, p, body) {
  const headers = {};
  if (cookies[who]) headers.Cookie = cookies[who];
  const opts = { method, headers };
  if (body !== undefined && body !== null) {
    headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(BASE + p, opts);
  const buf = Buffer.from(await res.arrayBuffer());
  const text = buf.toString('utf8');
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  covered.add(`${method} ${tpl(p)}`);
  return { who, method, path: p, body, status: res.status, text, json, buf, headers: res.headers };
}

async function rawGet(who, p) {
  const headers = {};
  if (cookies[who]) headers.Cookie = cookies[who];
  const res = await fetch(BASE + p, { headers });
  const buf = Buffer.from(await res.arrayBuffer());
  covered.add(`GET ${tpl(p)}`);
  return {
    who,
    method: 'GET',
    path: p,
    body: null,
    status: res.status,
    text: buf.toString('utf8'),
    json: null,
    buf,
    headers: res.headers,
  };
}

async function login(account) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: account, password: PASSWORD }),
  });
  const raw = await res.text();
  let json = null;
  try {
    json = JSON.parse(raw);
  } catch {
    json = null;
  }
  const setCookie = typeof res.headers.getSetCookie === 'function'
    ? res.headers.getSetCookie().join('; ')
    : (res.headers.get('set-cookie') || '');
  const m = /JSESSIONID=[^;]+/.exec(setCookie);
  if (!m) throw new Error(`登录失败 ${account}: HTTP ${res.status} ${raw.slice(0, 200)}`);
  cookies[account] = m[0];
  return { account, json, status: res.status };
}

// ---------------------------------------------------------------- 上下文（运行期收集的 id）

const ctx = {
  appId: null,
  stageId: null,
  pubBindId: null,
  pubId: null,
  t1: null,
  t2: null,
  t3: null,
  t4: null,
  m4: null,
  m7: null,
  obs1: null,
  obs2: null,
  obs3: null,
  matId: null,
  studentResponses: [],
};

function trackStudent(label, rec) {
  if (rec && rec.json) ctx.studentResponses.push({ label, rec });
  return rec;
}

// ---------------------------------------------------------------- 0. 登录

async function phaseLogin() {
  section('0. 环境与登录（每个账号只登录一次）');
  const expect = [
    ['admin', 'admin', 1],
    ['lisi', 'teacher', 3],
    ['zhangsan', 'teacher', 2],
    ['wangxm', 'student', 4],
    ['zhaoxh', 'student', 5],
    ['chendw', 'student', 6],
    ['liusq', 'student', 7],
  ];
  for (const [account, role, id] of expect) {
    let rec = null;
    try {
      rec = await login(account);
    } catch (e) {
      ok(`登录 ${account}`, false, String(e && e.message ? e.message : e));
      continue;
    }
    ok(`登录 ${account} 拿到 JSESSIONID`, !!cookies[account], rec.json ? JSON.stringify(rec.json).slice(0, 300) : '');
    ok(`登录 ${account} 角色=${role}、id=${id}`, !!rec.json && rec.json.code === 200 && d(rec).role === role && d(rec).id === id,
      rec.json ? JSON.stringify(rec.json).slice(0, 300) : '');
  }
  // 伪造/匿名会话
  cookies.forged = 'JSESSIONID=e2e-p16-forged-0000';
  lines.push(`运行标记 TAG=${TAG}；合成学生 userId=${SYN}；服务器日期=${TODAY}`);
}

// ---------------------------------------------------------------- 1. 申请登记（4 条）

async function phaseApplications() {
  section('1. 入党申请登记（4 条路由 + 状态机负数）');

  const applyDate = TODAY;
  const talkDate = TODAY;
  const rec = await call('lisi', 'POST', '/api/party/applications', {
    userId: SYN,
    branchName: `${TAG}支部`,
    applyDate,
    talkDate,
    talkBy: '李四（支部书记）',
    talkSummary: `${TAG} 谈话摘要`,
    internalRemark: `${TAG} 内部备注（仅 staff 可见）`,
  });
  expectOk('POST /applications 代学生登记入党申请（staff 传 userId）', rec, '入党申请已登记');
  ctx.appId = d(rec) ? d(rec).id : null;
  ok('POST /applications 返回新申请 id', Number.isInteger(ctx.appId) && ctx.appId > 0, describe(rec));
  eq('POST /applications 申请状态=active', d(rec) && d(rec).status, 'active', rec);
  ok('POST /applications 响应不含内部备注（学生端字段由服务层抹掉；staff 侧本接口亦回抹后对象）',
    !hasText(rec, `${TAG} 内部备注`), describe(rec));

  // 负数：staff 不传 userId
  const negNoUser = await call('lisi', 'POST', '/api/party/applications', { applyDate, talkDate });
  expectCode('POST /applications 负数：staff 不传 userId → 400', negNoUser, 400, '请指定 userId');

  // 负数：谈话日期早于申请日期
  const negDate = await call('lisi', 'POST', '/api/party/applications', {
    userId: SYN,
    applyDate,
    talkDate: addDays(applyDate, -3),
  });
  expectCode('POST /applications 负数：谈话日期早于申请日期 → 400', negDate, 400, '谈话日期不能早于申请日期');

  // 负数：同一学生重复登记（唯一性约束）
  const negDup = await call('lisi', 'POST', '/api/party/applications', { userId: SYN, applyDate, talkDate });
  expectCode('POST /applications 负数：该学生已有申请记录 → 400', negDup, 400, '该学生已有入党申请记录');

  // 列表（admin 全量 / lisi 名下 / 学生本人）
  const listAdmin = await call('admin', 'GET', `/api/party/applications?userId=${SYN}&page=1&size=20`);
  expectOk('GET /applications admin 按 userId 过滤', listAdmin);
  ok('GET /applications 命中本轮合成学生', JSON.stringify(d(listAdmin)) .includes(String(SYN)), describe(listAdmin));
  eq('GET /applications 分页封装 total=1', d(listAdmin) && d(listAdmin).total, 1, listAdmin);

  const listTeacher = await call('lisi', 'GET', '/api/party/applications?page=1&size=50');
  expectOk('GET /applications lisi 名下列表', listTeacher);
  const teacherAppRows = d(listTeacher) && Array.isArray(d(listTeacher).list) ? d(listTeacher).list : [];
  ok('GET /applications 辅导员列表只含名下学生（再无 userId=SYN 的合成行）',
    teacherAppRows.every((r) => [4, 5, 6].includes(r.userId)), describe(listTeacher));

  const listStudent = await call('wangxm', 'GET', '/api/party/applications?page=1&size=20');
  trackStudent('GET /applications (student)', listStudent);
  expectOk('GET /applications 学生本人列表', listStudent);
  const stuAppRows = d(listStudent) && Array.isArray(d(listStudent).list) ? d(listStudent).list : [];
  ok('GET /applications 学生列表只有本人数据（userId=4）', stuAppRows.length > 0 && stuAppRows.every((r) => r.userId === 4),
    describe(listStudent));

  // 详情（本人 / staff）
  const detailStaff = await call('admin', 'GET', `/api/party/applications/${ctx.appId}`);
  expectOk('GET /applications/{id} admin 详情', detailStaff, 'success');
  eq('GET /applications/{id} 返回同一 id', d(detailStaff) && d(detailStaff).id, ctx.appId, detailStaff);
  ok('GET /applications/{id} staff 可见内部备注（非空）',
    !!(d(detailStaff) && d(detailStaff).internalRemark && String(d(detailStaff).internalRemark).includes(TAG)), describe(detailStaff));
  ok('GET /applications/{id} 带谈话时限提示（含条款引用 S24/S23）',
    hasText(detailStaff, 'S24') || hasText(detailStaff, 'S23'), describe(detailStaff));

  const detailOwn = await call('wangxm', 'GET', '/api/party/applications/89001');
  trackStudent('GET /applications/{id} (student own)', detailOwn);
  expectOk('GET /applications/{id} 学生看本人记录', detailOwn);
  ok('GET /applications/{id} 学生本人记录 internalRemark 已抹掉',
    !!d(detailOwn) && !d(detailOwn).internalRemark, describe(detailOwn));

  const detail404 = await call('admin', 'GET', '/api/party/applications/99999999');
  expectCode('GET /applications/{id} 不存在 → 404', detail404, 404, '入党申请记录不存在');

  // 负数：学生看他人申请 → 403
  const otherApp = await call('wangxm', 'GET', '/api/party/applications/89002');
  expectCode('GET /applications/{id} 负数：学生看他人记录 → 403', otherApp, 403, '无权查看他人');

  // 修改（PUT）
  const upd = await call('lisi', 'PUT', `/api/party/applications/${ctx.appId}`, {
    applyDate,
    talkDate,
    talkBy: '李四（支部书记）',
    talkSummary: `${TAG} 谈话摘要（已更新）`,
    branchName: `${TAG}支部`,
  });
  expectOk('PUT /applications/{id} 修改申请（CAS active）', upd, '已保存');
  ok('PUT /applications/{id} 回写生效', hasText(upd, `${TAG} 谈话摘要（已更新）`), describe(upd));

  const negPut = await call('lisi', 'PUT', `/api/party/applications/${ctx.appId}`, {
    applyDate,
    talkDate: addDays(applyDate, -1),
  });
  expectCode('PUT /applications/{id} 负数：谈话日期早于申请日期 → 400', negPut, 400, '谈话日期不能早于申请日期');

  const negPutEmpty = await call('lisi', 'PUT', `/api/party/applications/${ctx.appId}`, { applyDate: '' });
  expectCode('PUT /applications/{id} 负数：申请日期为空 → 400', negPutEmpty, 400, '申请日期不能为空');

  const negPutStudent = await call('wangxm', 'PUT', '/api/party/applications/89001', { applyDate: TODAY });
  expectCode('PUT /applications/{id} 负数：学生改申请 → 403', negPutStudent, 403, '无权访问');
}

// ---------------------------------------------------------------- 2. 阶段台账（7 条）

async function phaseStages() {
  section('2. 阶段台账与推进（7 条路由 + CAS/权限负数）');

  // 合成学生的台账由 ensureStage 在登记申请时自动建立
  const listSyn = await call('admin', 'GET', `/api/party/stages?userId=${SYN}`);
  expectOk('GET /stages admin 按 userId 过滤', listSyn);
  const synRows = d(listSyn) && Array.isArray(d(listSyn).list) ? d(listSyn).list : [];
  ok('GET /stages 登记申请时自动建立台账（applicant）', synRows.length === 1 && synRows[0].stage === 'applicant', describe(listSyn));
  ctx.stageId = synRows.length ? synRows[0].id : null;
  if (!ctx.stageId) {
    ok('后续阶段用例依赖合成台账 id', false, '未取到合成学生台账 id');
    return;
  }

  const listAll = await call('admin', 'GET', '/api/party/stages?page=1&size=100');
  expectOk('GET /stages admin 全量列表', listAll);
  const allRows = d(listAll) && Array.isArray(d(listAll).list) ? d(listAll).list : [];
  const ids = allRows.map((r) => r.userId);
  ok('GET /stages admin 列表含演示数据（user 4/5）与本轮合成学生',
    ids.includes(4) && ids.includes(5) && ids.includes(SYN), describe(listAll));
  ok('GET /stages 列表行带联表字段 userName/studentNo/className',
    allRows.length > 0 && 'userName' in allRows[0] && 'studentNo' in allRows[0] && 'className' in allRows[0], describe(listAll));

  // 我的台账（学生端自助）
  const mine = await call('wangxm', 'GET', '/api/party/stages/mine');
  trackStudent('GET /stages/mine (wangxm)', mine);
  expectOk('GET /stages/mine 学生端自助台账', mine);
  eq('GET /stages/mine 命中本人台账（89011）', d(mine) && d(mine).id, 89011, mine);
  eq('GET /stages/mine 阶段=full', d(mine) && d(mine).stage, 'full', mine);
  ok('GET /stages/mine 学生端抹掉 internalNote/internalDiscussion（值为空）',
    !!d(mine) && !d(mine).internalNote && !d(mine).internalDiscussion, describe(mine));
  ok('GET /stages/mine 带下一步提示（含条款引用）',
    /S2[34]|第[一二三四五六七八九十百零]+条/.test(mine.text), describe(mine));

  // 无台账学生走 /stages/mine
  const mineNone = await call('liusq', 'GET', '/api/party/stages/mine');
  trackStudent('GET /stages/mine (liusq 无台账)', mineNone);
  expectOk('GET /stages/mine 无台账学生仍 200', mineNone);
  ok('GET /stages/mine 无台账时给出提示文案', hasText(mineNone, '尚未建立党建台账'), describe(mineNone));
  ok('GET /stages/mine 无台账提示带条款来源（S24 第七条）',
    hasText(mineNone, 'S24') || /第[一二三四五六七八九十百零]+条/.test(mineNone.text), describe(mineNone));

  // 详情（staff / 学生本人 / 学生他人）
  const stageStaff = await call('admin', 'GET', `/api/party/stages/${ctx.stageId}`);
  expectOk('GET /stages/{id} admin 详情', stageStaff);
  eq('GET /stages/{id} 阶段=applicant', d(stageStaff) && d(stageStaff).stage, 'applicant', stageStaff);

  const stageDemo = await call('admin', 'GET', '/api/party/stages/89011');
  expectOk('GET /stages/{id} 演示台账详情（89011）', stageDemo);
  ok('GET /stages/{id} staff 可见内部考察意见（非空）',
    !!(d(stageDemo) && d(stageDemo).internalNote), describe(stageDemo));

  const stageOwn = await call('wangxm', 'GET', '/api/party/stages/89011');
  trackStudent('GET /stages/{id} (student own)', stageOwn);
  expectOk('GET /stages/{id} 学生看本人台账', stageOwn);
  ok('GET /stages/{id} 学生本人台账内部字段被抹掉',
    !!d(stageOwn) && !d(stageOwn).internalNote && !d(stageOwn).internalDiscussion, describe(stageOwn));

  const stage404 = await call('admin', 'GET', '/api/party/stages/99999999');
  expectCode('GET /stages/{id} 不存在 → 404', stage404, 404, '党建台账不存在');

  const stageOther = await call('wangxm', 'GET', '/api/party/stages/89012');
  expectCode('GET /stages/{id} 负数：学生看他人台账 → 403', stageOther, 403, '无权查看他人');

  // 内部信息维护（PUT /stages/{id}/internal）
  const internal = await call('lisi', 'PUT', `/api/party/stages/${ctx.stageId}/internal`, {
    branchName: `${TAG}支部`,
    internalNote: `${TAG} 支部内部考察意见`,
    internalDiscussion: `${TAG} 推优讨论内容`,
  });
  expectOk('PUT /stages/{id}/internal 维护支部信息/内部意见', internal, '已保存');
  const stageAfterInternal = await call('admin', 'GET', `/api/party/stages/${ctx.stageId}`);
  ok('PUT /stages/{id}/internal 写入生效（staff 可见）',
    hasText(stageAfterInternal, `${TAG} 支部内部考察意见`), describe(stageAfterInternal));

  // 阶段推进负数：跨级
  const skip = await call('lisi', 'POST', `/api/party/stages/${ctx.stageId}/advance`, { toStage: 'candidate' });
  expectCode('POST /stages/{id}/advance 负数：跨级推进 → 400', skip, 400, '阶段只能逐级向前');

  // 负数：学生推进/回退
  const stuAdvance = await call('wangxm', 'POST', `/api/party/stages/${ctx.stageId}/advance`, { toStage: 'activist' });
  expectCode('POST /stages/{id}/advance 负数：学生调用 → 403', stuAdvance, 403, '无权访问');
  const stuRollback = await call('wangxm', 'POST', `/api/party/stages/${ctx.stageId}/rollback`, {
    toStage: 'applicant',
    reason: 'x',
  });
  expectCode('POST /stages/{id}/rollback 负数：学生调用 → 403', stuRollback, 403, '仅管理员可操作');

  // 负数：非 admin 不可回退
  const teacherRollback = await call('lisi', 'POST', `/api/party/stages/${ctx.stageId}/rollback`, {
    toStage: 'applicant',
    reason: 'x',
  });
  expectCode('POST /stages/{id}/rollback 负数：teacher 调用 → 403', teacherRollback, 403, '仅管理员可操作');

  // 负数：目标阶段枚举非法（阶段枚举之外的值）
  const badTarget = await call('admin', 'POST', `/api/party/stages/${ctx.stageId}/rollback`, {
    toStage: 'bogus',
    reason: 'x',
  });
  expectCode('POST /stages/{id}/rollback 负数：目标阶段枚举非法 → 400', badTarget, 400, '请指定合法的目标阶段');

  // 负数：当前处于第一阶段（applicant，index=0）不可回退
  const firstStage = await call('admin', 'POST', `/api/party/stages/${ctx.stageId}/rollback`, {
    toStage: 'applicant',
    reason: 'x',
  });
  expectCode('POST /stages/{id}/rollback 负数：当前处于第一阶段 → 400', firstStage, 400, '无法回退');

  // 推进成功路径（applicant → activist）
  const advance = await call('lisi', 'POST', `/api/party/stages/${ctx.stageId}/advance`, {
    toStage: 'activist',
    stepDate: TODAY,
    stepRef: `${TAG} 推进依据`,
  });
  expectOk('POST /stages/{id}/advance 成功：applicant → activist', advance, '已推进到');
  const stageActivist = await call('admin', 'GET', `/api/party/stages/${ctx.stageId}`);
  eq('POST /stages/{id}/advance 阶段落库=activist', d(stageActivist) && d(stageActivist).stage, 'activist', stageActivist);
  eq('POST /stages/{id}/advance 写入 activistDate', d(stageActivist) && d(stageActivist).activistDate, TODAY, stageActivist);

  // 负数：客户端持过期阶段再次推进（stale / fromStage 不匹配）
  const stale = await call('lisi', 'POST', `/api/party/stages/${ctx.stageId}/advance`, {
    toStage: 'activist',
    stepDate: TODAY,
  });
  expectCode('POST /stages/{id}/advance 负数：持过期阶段（stale）再次推进 → 400', stale, 400, '阶段只能逐级向前');

  // 负数：步进日期早于上一步（当前 activist，锚点为 activistDate；服务端 index<=0 时无锚点）
  const badDate = await call('lisi', 'POST', `/api/party/stages/${ctx.stageId}/advance`, {
    toStage: 'candidate',
    stepDate: addDays(TODAY, -30),
  });
  expectCode('POST /stages/{id}/advance 负数：步进日期早于上一步 → 400', badDate, 400, '日期不能早于');

  // 负数：回退不填原因（当前 activist）
  const noReason = await call('admin', 'POST', `/api/party/stages/${ctx.stageId}/rollback`, { toStage: 'applicant' });
  expectCode('POST /stages/{id}/rollback 负数：不填原因 → 400', noReason, 400, '阶段回退必须填写原因');

  // 负数：跨级回退（演示数据 89012 处于 candidate，按逐级规则只能回到 activist）
  const crossRollback = await call('admin', 'POST', '/api/party/stages/89012/rollback', {
    toStage: 'probationary',
    reason: 'x',
  });
  expectCode('POST /stages/{id}/rollback 负数：跨级回退（candidate→probationary）→ 400', crossRollback, 400, '只支持逐级回退');

  // 负数：已是最后阶段
  const alreadyFull = await call('admin', 'POST', '/api/party/stages/89011/advance', { stepDate: TODAY });
  expectCode('POST /stages/{id}/advance 负数：已 full 再推进 → 400', alreadyFull, 400, '已是最后阶段');

  // 回退成功（activist → applicant，仅 admin + 原因）
  const rollback = await call('admin', 'POST', `/api/party/stages/${ctx.stageId}/rollback`, {
    toStage: 'applicant',
    reason: `${TAG} 回退原因：材料有误，退回补正`,
  });
  expectOk('POST /stages/{id}/rollback 成功：admin + 原因', rollback, '已回退');
  const stageRolled = await call('admin', 'GET', `/api/party/stages/${ctx.stageId}`);
  eq('POST /stages/{id}/rollback 阶段落库=applicant', d(stageRolled) && d(stageRolled).stage, 'applicant', stageRolled);
  ok('POST /stages/{id}/rollback 写入回退原因（审计）',
    hasText(stageRolled, `${TAG} 回退原因`), describe(stageRolled));

  // 为绑定阶段公示准备一条「属于本学生」的公示
  const pubForBind = await call('admin', 'POST', '/api/party/publicities', {
    nodeType: 'probation_convert',
    title: `${TAG} 阶段公示（用于绑定）`,
    userId: SYN,
    startDate: TODAY,
    workdays: 5,
  });
  expectOk('POST /publicities 为阶段绑定的公示（userId=合成学生）', pubForBind);
  ctx.pubBindId = d(pubForBind) ? d(pubForBind).id : null;

  // 绑定当前阶段公示（POST /stages/{id}/publicity）
  const bind = await call('lisi', 'POST', `/api/party/stages/${ctx.stageId}/publicity`, {
    publicityId: ctx.pubBindId,
  });
  expectOk('POST /stages/{id}/publicity 绑定本学生公示', bind, '已绑定');
  const stageBound = await call('admin', 'GET', `/api/party/stages/${ctx.stageId}`);
  eq('POST /stages/{id}/publicity stagePublicityId 落库', d(stageBound) && d(stageBound).stagePublicityId, ctx.pubBindId, stageBound);

  // 负数：绑定他人公示
  const bindOther = await call('lisi', 'POST', `/api/party/stages/${ctx.stageId}/publicity`, { publicityId: 89051 });
  expectCode('POST /stages/{id}/publicity 负数：绑定他人公示 → 400', bindOther, 400, '该公示不属于本学生');

  // 负数：缺 publicityId
  const bindNone = await call('lisi', 'POST', `/api/party/stages/${ctx.stageId}/publicity`, {});
  expectCode('POST /stages/{id}/publicity 负数：缺 publicityId → 400', bindNone, 400, '请指定 publicityId');
}

// ---------------------------------------------------------------- 3. 考察与思想汇报（5 条）

async function phaseObservations() {
  section('3. 考察与思想汇报（5 条路由 + CAS 负数）');

  // 学生提交思想汇报
  const thought = await call('wangxm', 'POST', '/api/party/observations/thought-report', {
    content: `${TAG} 王小明思想汇报正文`,
    periodLabel: '2026Q4',
  });
  expectOk('POST /observations/thought-report 学生提交思想汇报', thought, '思想汇报已提交');
  ctx.obs1 = d(thought) ? d(thought).id : null;
  eq('POST /observations/thought-report 初始审核态=pending', d(thought) && d(thought).reviewStatus, 'pending', thought);

  // 我的思想汇报/考察
  const mine = await call('wangxm', 'GET', '/api/party/observations/mine?page=1&size=20');
  trackStudent('GET /observations/mine', mine);
  expectOk('GET /observations/mine 学生自助', mine);
  ok('GET /observations/mine 返回分页封装（list/total）',
    !!(d(mine) && Array.isArray(d(mine).list) && typeof d(mine).total === 'number'), describe(mine));
  ok('GET /observations/mine 附带三条期限口径（国标+校规+思想汇报）',
    hasText(mine, 'thoughtReportRule') && hasText(mine, 'observeRuleNational') && hasText(mine, 'observeRuleSchool'),
    describe(mine));
  ok('GET /observations/mine 可见本人新提交的思想汇报',
    JSON.stringify(d(mine)).includes(`${TAG} 王小明思想汇报正文`), describe(mine));

  // staff 登记考察记录
  const obs4 = await call('lisi', 'POST', '/api/party/observations', {
    userId: 4,
    type: 'observation',
    content: `${TAG} 李四对王小明的考察记录`,
    submittedAt: `${TODAY} 09:30:00`,
  });
  expectOk('POST /observations staff 登记考察记录', obs4, '已登记');
  ctx.obs2 = d(obs4) ? d(obs4).id : null;

  // 跨辅导员数据（users 7 属 zhangsan 名下）
  const obs7 = await call('admin', 'POST', '/api/party/observations', {
    userId: 7,
    type: 'observation',
    content: `${TAG} 跨辅导员考察记录（李四不应可见）`,
  });
  expectOk('POST /observations 为 zhangsan 名下学生登记考察记录', obs7);
  ctx.obs3 = d(obs7) ? d(obs7).id : null;

  // 负数：type 非法 / 内容为空 / 缺 userId
  const badType = await call('lisi', 'POST', '/api/party/observations', { userId: 4, type: 'bogus', content: 'x' });
  expectCode('POST /observations 负数：type 非法 → 400', badType, 400, 'type 只能是 observation 或 thought_report');
  const emptyContent = await call('lisi', 'POST', '/api/party/observations', { userId: 4, type: 'observation', content: '' });
  expectCode('POST /observations 负数：内容为空 → 400', emptyContent, 400, '内容不能为空');
  const noUser = await call('lisi', 'POST', '/api/party/observations', { type: 'observation', content: 'x' });
  expectCode('POST /observations 负数：缺 userId → 400', noUser, 400, '请指定 userId');

  const badTime = await call('lisi', 'POST', '/api/party/observations', {
    userId: 4,
    type: 'observation',
    content: 'x',
    submittedAt: 'not-a-date',
  });
  expectCode('POST /observations 负数：提交时间格式错 → 400', badTime, 400, '提交时间格式不正确');

  // 台账列表
  const listStaff = await call('lisi', 'GET', '/api/party/observations?userId=4&page=1&size=50');
  expectOk('GET /observations staff 按 userId 过滤', listStaff);
  ok('GET /observations 命中本轮登记记录', hasText(listStaff, `${TAG} 李四对王小明的考察记录`), describe(listStaff));

  const listAdmin = await call('admin', 'GET', '/api/party/observations?page=1&size=100');
  expectOk('GET /observations admin 全量', listAdmin);

  const listStudent = await call('wangxm', 'GET', '/api/party/observations?page=1&size=50');
  trackStudent('GET /observations (student)', listStudent);
  expectOk('GET /observations 学生本人台账', listStudent);
  const stuObsRows = d(listStudent) && Array.isArray(d(listStudent).list) ? d(listStudent).list : [];
  ok('GET /observations 学生列表只含本人数据', stuObsRows.length > 0 && stuObsRows.every((r) => r.userId === 4), describe(listStudent));

  const listOther = await call('wangxm', 'GET', '/api/party/observations?userId=5');
  expectCode('GET /observations 负数：学生查他人台账 → 403', listOther, 403, '无权查看他人党建台账');

  // 审核（成功 + 重复审核负数）
  const review = await call('lisi', 'POST', `/api/party/observations/${ctx.obs2}/review`, {
    action: 'approve',
    reviewOpinion: `${TAG} 审核通过意见`,
  });
  expectOk('POST /observations/{id}/review 审核通过', review, '已审核');
  const reviewAgain = await call('lisi', 'POST', `/api/party/observations/${ctx.obs2}/review`, { action: 'approve' });
  expectCode('POST /observations/{id}/review 负数：重复审核 → 400', reviewAgain, 400, '只有「待审核」的记录可以审核');
  const reviewDemo = await call('lisi', 'POST', '/api/party/observations/89021/review', { action: 'approve' });
  expectCode('POST /observations/{id}/review 负数：演示数据已审核记录再审核 → 400', reviewDemo, 400, '只有「待审核」的记录可以审核');
  const review404 = await call('lisi', 'POST', '/api/party/observations/99999999/review', { action: 'approve' });
  expectCode('POST /observations/{id}/review 不存在 → 404', review404, 404, '记录不存在');
  const reviewByStudent = await call('wangxm', 'POST', `/api/party/observations/${ctx.obs1}/review`, { action: 'approve' });
  expectCode('POST /observations/{id}/review 负数：学生越权审核 → 403', reviewByStudent, 403, '无权访问');

  // 退回路径
  const thought2 = await call('wangxm', 'POST', '/api/party/observations/thought-report', { content: `${TAG} 第二份思想汇报` });
  expectOk('POST /observations/thought-report 再提交一份（用于退回用例）', thought2);
  const obsReturn = await call('lisi', 'POST', `/api/party/observations/${d(thought2).id}/review`, {
    action: 'return',
    reviewOpinion: `${TAG} 内容需补充`,
  });
  expectOk('POST /observations/{id}/review 退回（action=return）', obsReturn, '已退回');
  const returned = await call('lisi', 'GET', '/api/party/observations?userId=4&reviewStatus=returned&page=1&size=20');
  ok('POST /observations/{id}/review 退回态落库（reviewStatus=returned）',
    hasText(returned, `${TAG} 第二份思想汇报`), describe(returned));
}

// ---------------------------------------------------------------- 4. 党校培训（11 条）

async function phaseTrainings() {
  section('4. 党校培训（11 条路由 + 名额/状态机负数）');

  const mk = (name, extra) => ({
    trainingName: name,
    term: 'e2e-p16',
    startDate: TODAY,
    endDate: addDays(TODAY, 6),
    days: 6,
    hours: 40,
    location: '线上',
    organizer: '党委组织部',
    ...extra,
  });

  const t1 = await call('admin', 'POST', '/api/party/trainings', mk(`${TAG} 党校班次A`, { quota: 1, status: 'signup' }));
  expectOk('POST /trainings 新建班次（status=signup, quota=1）', t1, '班次已创建');
  ctx.t1 = d(t1) ? d(t1).id : null;

  const t2 = await call('admin', 'POST', '/api/party/trainings', mk(`${TAG} 党校班次B（已结束）`, { status: 'finished' }));
  expectOk('POST /trainings 新建班次（status=finished）', t2);
  ctx.t2 = d(t2) ? d(t2).id : null;

  const t3 = await call('admin', 'POST', '/api/party/trainings', mk(`${TAG} 党校班次C（草稿）`, { status: 'draft' }));
  expectOk('POST /trainings 新建班次（status=draft）', t3);
  ctx.t3 = d(t3) ? d(t3).id : null;

  const t4 = await call('admin', 'POST', '/api/party/trainings', mk(`${TAG} 党校班次D`, { quota: 0, status: 'signup' }));
  expectOk('POST /trainings 新建班次（quota=0 不限名额）', t4);
  ctx.t4 = d(t4) ? d(t4).id : null;

  const noName = await call('admin', 'POST', '/api/party/trainings', { startDate: TODAY });
  expectCode('POST /trainings 负数：班次名称为空 → 400', noName, 400, '班次名称不能为空');
  const badRange = await call('admin', 'POST', '/api/party/trainings', {
    trainingName: `${TAG} 日期倒挂`,
    startDate: TODAY,
    endDate: addDays(TODAY, -5),
  });
  expectCode('POST /trainings 负数：结业日期早于开班日期 → 400', badRange, 400, '结业日期不能早于开班日期');
  const badStatus = await call('admin', 'POST', '/api/party/trainings', mk(`${TAG} 非法状态`, { status: 'bogus' }));
  expectCode('POST /trainings 负数：status 非法 → 400', badStatus, 400, 'status 只能是');

  // 列表（staff / 学生端过滤）
  const listStaff = await call('admin', 'GET', '/api/party/trainings?term=e2e-p16&page=1&size=50');
  expectOk('GET /trainings staff 按 term 过滤', listStaff);
  const trRows = d(listStaff) && Array.isArray(d(listStaff).list) ? d(listStaff).list : [];
  ok('GET /trainings 命中本轮 4 个班次', [ctx.t1, ctx.t2, ctx.t3, ctx.t4].every((id) => trRows.some((r) => r.id === id)),
    describe(listStaff));
  ok('GET /trainings 每行带 hoursTip（两条口径并存）',
    trRows.length > 0 && trRows.every((r) => typeof r.hoursTip === 'string' && r.hoursTip.includes('二十四个学时') && r.hoursTip.includes('四十个学时')),
    describe(listStaff));

  const listStudent = await call('wangxm', 'GET', '/api/party/trainings?page=1&size=100');
  trackStudent('GET /trainings (student)', listStudent);
  expectOk('GET /trainings 学生端列表', listStudent);
  const stuTrRows = d(listStudent) && Array.isArray(d(listStudent).list) ? d(listStudent).list : [];
  ok('GET /trainings 学生端隐藏 draft/cancelled',
    stuTrRows.every((r) => ['signup', 'ongoing', 'finished'].includes(r.status)), describe(listStudent));
  ok('GET /trainings 学生端看不到草稿班次', !stuTrRows.some((r) => r.id === ctx.t3), describe(listStudent));

  // 详情
  const detailStaff = await call('admin', 'GET', `/api/party/trainings/${ctx.t1}`);
  expectOk('GET /trainings/{id} staff 详情', detailStaff);
  eq('GET /trainings/{id} staff 分支直接回班次对象', d(detailStaff) && d(detailStaff).id, ctx.t1, detailStaff);

  const detailDraft = await call('wangxm', 'GET', `/api/party/trainings/${ctx.t3}`);
  expectCode('GET /trainings/{id} 负数：学生看草稿班次 → 404', detailDraft, 404, '党校班次不存在');
  const detail404 = await call('admin', 'GET', '/api/party/trainings/99999999');
  expectCode('GET /trainings/{id} 不存在 → 404', detail404, 404, '党校班次不存在');

  // 修改
  const upd = await call('admin', 'PUT', `/api/party/trainings/${ctx.t1}`, {
    trainingName: `${TAG} 党校班次A（改名）`,
    startDate: TODAY,
    endDate: addDays(TODAY, 6),
    quota: 1,
    status: 'signup',
  });
  expectOk('PUT /trainings/{id} 修改报名中班次', upd, '已保存');
  const afterUpd = await call('admin', 'GET', `/api/party/trainings/${ctx.t1}`);
  eq('PUT /trainings/{id} 改名生效', d(afterUpd) && d(afterUpd).trainingName, `${TAG} 党校班次A（改名）`, afterUpd);

  const updFinished = await call('admin', 'PUT', `/api/party/trainings/${ctx.t2}`, { trainingName: `${TAG} 改已结束` });
  expectCode('PUT /trainings/{id} 负数：已结束班次不可改 → 400', updFinished, 400, '状态的班次不能修改');
  const upd404 = await call('admin', 'PUT', '/api/party/trainings/99999999', { trainingName: 'x' });
  expectCode('PUT /trainings/{id} 不存在 → 404', upd404, 404, '党校班次不存在');

  // 我的党校
  const mineStudent = await call('wangxm', 'GET', '/api/party/trainings/mine?page=1&size=20');
  trackStudent('GET /trainings/mine (student)', mineStudent);
  expectOk('GET /trainings/mine 学生自助报名/结业', mineStudent);
  ok('GET /trainings/mine 附带结业证书口径与培训时长口径',
    hasText(mineStudent, 'certRule') && hasText(mineStudent, 'hoursRule'), describe(mineStudent));
  ok('GET /trainings/mine 证书口径含「一年」来源字样（S23）',
    /一年/.test(mineStudent.text) && /S23/.test(mineStudent.text), describe(mineStudent));

  // 统计
  const tstats = await call('admin', 'GET', '/api/party/trainings/stats');
  expectOk('GET /trainings/stats 党校统计', tstats);
  ok('GET /trainings/stats 含班次状态/学员状态分布 + 两条时长口径',
    !!(d(tstats) && d(tstats).trainingStatus && d(tstats).memberStatus) && hasText(tstats, '二十四个学时') && hasText(tstats, '四十个学时'),
    describe(tstats));

  // 学员名单
  const members0 = await call('admin', 'GET', `/api/party/training-members?trainingId=${ctx.t1}&page=1&size=50`);
  expectOk('GET /training-members 按 trainingId 查名单', members0);

  // 报名（成功 / 重复 / 名额满 / 班次非开放 / 不存在）
  const signup1 = await call('wangxm', 'POST', `/api/party/trainings/${ctx.t1}/signup`, {});
  expectOk('POST /trainings/{id}/signup 学生报名成功', signup1, '报名已提交');

  const signupDup = await call('wangxm', 'POST', `/api/party/trainings/${ctx.t1}/signup`, {});
  expectCode('POST /trainings/{id}/signup 负数：重复报名 → 400', signupDup, 400, '你已报名该班次');

  const signupFull = await call('zhaoxh', 'POST', `/api/party/trainings/${ctx.t1}/signup`, {});
  expectCode('POST /trainings/{id}/signup 负数：名额已满 → 400', signupFull, 400, '该班次名额已满');

  const signupClosed = await call('wangxm', 'POST', `/api/party/trainings/${ctx.t2}/signup`, {});
  expectCode('POST /trainings/{id}/signup 负数：班次已结束（非 signup|ongoing）→ 400', signupClosed, 400, '不接受报名');
  const signupDraft = await call('zhaoxh', 'POST', `/api/party/trainings/${ctx.t3}/signup`, {});
  expectCode('POST /trainings/{id}/signup 负数：草稿班次 → 400', signupDraft, 400, '不接受报名');
  const signup404 = await call('wangxm', 'POST', '/api/party/trainings/99999999/signup', {});
  expectCode('POST /trainings/{id}/signup 班次不存在 → 404', signup404, 404, '党校班次不存在');

  // 演示数据上的只读负数：chendw 已报名 89031
  const signupDemo = await call('chendw', 'POST', '/api/party/trainings/89031/signup', {});
  expectCode('POST /trainings/{id}/signup 负数：演示数据已报名 → 400', signupDemo, 400, '你已报名该班次');

  // 班次D 报名（用于审核/结业/证书 + 跨辅导员名单）
  const signupD1 = await call('wangxm', 'POST', `/api/party/trainings/${ctx.t4}/signup`, {});
  expectOk('POST /trainings/{id}/signup 班次D：王小明报名', signupD1);
  const signupD2 = await call('liusq', 'POST', `/api/party/trainings/${ctx.t4}/signup`, {});
  expectOk('POST /trainings/{id}/signup 班次D：刘思琪报名（zhangsan 名下）', signupD2);

  const membersT1 = await call('admin', 'GET', `/api/party/training-members?trainingId=${ctx.t1}&userId=4`);
  const row1 = d(membersT1) && d(membersT1).list && d(membersT1).list.length ? d(membersT1).list[0] : null;
  ok('GET /training-members 取得王小明在班次A的报名记录', !!row1, describe(membersT1));
  const membersT4 = await call('admin', 'GET', `/api/party/training-members?trainingId=${ctx.t4}&page=1&size=50`);
  const t4rows = d(membersT4) && Array.isArray(d(membersT4).list) ? d(membersT4).list : [];
  ctx.m4 = (t4rows.find((r) => r.userId === 4) || {}).id || null;
  ctx.m7 = (t4rows.find((r) => r.userId === 7) || {}).id || null;
  ok('GET /training-members 取得班次D 的两条报名记录（4 与 7）', !!ctx.m4 && !!ctx.m7, describe(membersT4));

  // 学生视角的名单权限
  const membersStudentOwn = await call('wangxm', 'GET', '/api/party/training-members?page=1&size=50');
  trackStudent('GET /training-members (student own)', membersStudentOwn);
  expectOk('GET /training-members 学生查本人报名记录', membersStudentOwn);
  const stuMemRows = d(membersStudentOwn) && Array.isArray(d(membersStudentOwn).list) ? d(membersStudentOwn).list : [];
  ok('GET /training-members 学生只看到本人记录', stuMemRows.length > 0 && stuMemRows.every((r) => r.userId === 4), describe(membersStudentOwn));
  const membersOther = await call('wangxm', 'GET', '/api/party/training-members?userId=7');
  expectCode('GET /training-members 负数：学生查他人报名记录 → 403', membersOther, 403, '无权查看他人党校报名记录');

  // 辅导员范围
  const membersByLisi = await call('lisi', 'GET', `/api/party/training-members?trainingId=${ctx.t4}&page=1&size=50`);
  expectOk('GET /training-members 按辅导员范围过滤', membersByLisi);
  const lisiRows = d(membersByLisi) && Array.isArray(d(membersByLisi).list) ? d(membersByLisi).list : [];
  ok('GET /training-members lisi 只看得到名下学生（不含 userId=7）',
    lisiRows.length > 0 && lisiRows.every((r) => [4, 5, 6].includes(r.userId)), describe(membersByLisi));
  const membersByZhangsan = await call('zhangsan', 'GET', `/api/party/training-members?trainingId=${ctx.t4}&page=1&size=50`);
  ok('GET /training-members zhangsan 能看到自己名下学生（userId=7）',
    hasText(membersByZhangsan, '"userId":7'), describe(membersByZhangsan));
  const membersCross = await call('lisi', 'GET', '/api/party/training-members?userId=7');
  eq('GET /training-members lisi 按 userId=7（跨辅导员）过滤后为空', d(membersCross) && d(membersCross).total, 0, membersCross);

  // 审核（成功 / 重复 / 驳回缺原因 / 驳回成功 / 404）
  const review1 = await call('lisi', 'POST', `/api/party/training-members/${ctx.m4}/review`, {});
  expectOk('POST /training-members/{id}/review 审核通过', review1, '已审核通过');
  const review1again = await call('lisi', 'POST', `/api/party/training-members/${ctx.m4}/review`, {});
  expectCode('POST /training-members/{id}/review 负数：重复审核 → 400', review1again, 400, '只有「已报名」的记录可以审核');
  const rejectNoReason = await call('zhangsan', 'POST', `/api/party/training-members/${ctx.m7}/review`, { action: 'reject' });
  expectCode('POST /training-members/{id}/review 负数：驳回不填原因 → 400', rejectNoReason, 400, '驳回必须填写原因');
  const reject = await call('zhangsan', 'POST', `/api/party/training-members/${ctx.m7}/review`, {
    action: 'reject',
    rejectReason: `${TAG} 材料不全`,
  });
  expectOk('POST /training-members/{id}/review 驳回（action=reject）', reject, '已驳回');
  const rejectedList = await call('admin', 'GET', `/api/party/training-members?trainingId=${ctx.t4}&status=rejected`);
  ok('POST /training-members/{id}/review 驳回态落库', hasText(rejectedList, '"id":' + ctx.m7), describe(rejectedList));
  const review404 = await call('lisi', 'POST', '/api/party/training-members/99999999/review', {});
  expectCode('POST /training-members/{id}/review 不存在 → 404', review404, 404, '报名记录不存在');

  // 结业登记（坏结论 / 成功 / 前置态不匹配）
  const completeBad = await call('lisi', 'POST', `/api/party/training-members/${ctx.m4}/complete`, { completeResult: 'bogus' });
  expectCode('POST /training-members/{id}/complete 负数：结业结论非法 → 400', completeBad, 400, '结业结论只能是 pass');
  const completeNoResult = await call('lisi', 'POST', `/api/party/training-members/${ctx.m4}/complete`, {});
  expectCode('POST /training-members/{id}/complete 负数：未填结业结论 → 400', completeNoResult, 400, '请填写结业结论');
  const complete = await call('lisi', 'POST', `/api/party/training-members/${ctx.m4}/complete`, {
    completeResult: 'pass',
    completeDate: TODAY,
    note: `${TAG} 结业备注`,
  });
  expectOk('POST /training-members/{id}/complete 结业登记（pass）', complete, '结业登记已保存');
  const afterComplete = await call('admin', 'GET', `/api/party/training-members?trainingId=${ctx.t4}&userId=4`);
  const compRow = d(afterComplete) && d(afterComplete).list && d(afterComplete).list.length ? d(afterComplete).list[0] : null;
  ok('POST /training-members/{id}/complete 落库：status=completed / completeResult=pass',
    !!compRow && compRow.status === 'completed' && compRow.completeResult === 'pass', describe(afterComplete));
  ok('POST /training-members/{id}/complete 默认证书有效期 = 颁发日 + 365 天',
    !!compRow && !!compRow.certIssueDate && !!compRow.certValidUntil && dayDiff(compRow.certValidUntil, compRow.certIssueDate) === 365,
    describe(afterComplete));
  ok('POST /training-members/{id}/complete 不自动生成证书编号（只登记人工结业结论，编号由 PUT /cert 录入）',
    !!compRow && !compRow.certNo, `certNo=${JSON.stringify(compRow && compRow.certNo)} | ${describe(afterComplete)}`);

  const completeWrongState = await call('lisi', 'POST', `/api/party/training-members/${ctx.m4}/complete`, { completeResult: 'pass' });
  expectCode('POST /training-members/{id}/complete 负数：前置态不匹配（已结业）→ 400', completeWrongState, 400, '只有「审核通过」的学员可以登记结业');
  const completeDemo = await call('lisi', 'POST', '/api/party/training-members/89043/complete', { completeResult: 'pass' });
  expectCode('POST /training-members/{id}/complete 负数：演示数据 signed 记录 → 400', completeDemo, 400, '只有「审核通过」的学员可以登记结业');
  const complete404 = await call('lisi', 'POST', '/api/party/training-members/99999999/complete', { completeResult: 'pass' });
  expectCode('POST /training-members/{id}/complete 不存在 → 404', complete404, 404, '报名记录不存在');

  // 证书覆盖
  const cert = await call('admin', 'PUT', `/api/party/training-members/${ctx.m4}/cert`, {
    certNo: `PX-${TAG}`,
    certIssueDate: TODAY,
    certValidUntil: addDays(TODAY, 400),
  });
  expectOk('PUT /training-members/{id}/cert 覆盖证书信息', cert, '证书信息已更新');
  const afterCert = await call('admin', 'GET', `/api/party/training-members?trainingId=${ctx.t4}&userId=4`);
  ok('PUT /training-members/{id}/cert 落库（人工覆盖值被采信）', hasText(afterCert, `PX-${TAG}`), describe(afterCert));
  const certBadState = await call('admin', 'PUT', `/api/party/training-members/${ctx.m7}/cert`, { certNo: 'x' });
  expectCode('PUT /training-members/{id}/cert 负数：未结业学员 → 400', certBadState, 400, '只有「已结业」的学员才有证书信息');
  const certDemo = await call('admin', 'PUT', '/api/party/training-members/89042/cert', { certNo: 'x' });
  expectCode('PUT /training-members/{id}/cert 负数：演示数据 approved 记录 → 400', certDemo, 400, '只有「已结业」的学员才有证书信息');
  const cert404 = await call('admin', 'PUT', '/api/party/training-members/99999999/cert', { certNo: 'x' });
  expectCode('PUT /training-members/{id}/cert 不存在 → 404', cert404, 404, '报名记录不存在');

  // 学生视角详情（报名后 mySignup 非空）
  const detailStudent = await call('wangxm', 'GET', `/api/party/trainings/${ctx.t1}`);
  trackStudent('GET /trainings/{id} (student)', detailStudent);
  expectOk('GET /trainings/{id} 学生端详情', detailStudent);
  ok('GET /trainings/{id} 学生端返回 {training,mySignup} 且本人报名可见',
    !!(d(detailStudent) && d(detailStudent).training && d(detailStudent).mySignup), describe(detailStudent));
}

// ---------------------------------------------------------------- 5. 公示（6 条）

async function phasePublicities() {
  section('5. 公示（6 条路由 + 状态机负数）');

  const create = await call('admin', 'POST', '/api/party/publicities', {
    nodeType: 'develop_party',
    title: `${TAG} 发展对象公示`,
    startDate: TODAY,
    workdays: 5,
  });
  expectOk('POST /publicities 创建公示（草稿）', create, '公示已创建（草稿）');
  ctx.pubId = d(create) ? d(create).id : null;
  eq('POST /publicities 初始状态=draft', d(create) && d(create).status, 'draft', create);
  eq('POST /publicities 结束日期按 5 个工作日推算（跳过周末）', d(create) && d(create).endDate, addWorkdays(TODAY, 5), create);
  ok('POST /publicities 默认异议渠道为校级受理渠道（不含支部个人联系方式）',
    hasText(create, '异议') && !/\d{11}/.test(create.text), describe(create));

  const noNode = await call('admin', 'POST', '/api/party/publicities', { title: 'x' });
  expectCode('POST /publicities 负数：缺 nodeType → 400', noNode, 400, 'nodeType 只能是 training_student / develop_party / probation_convert');
  const badNode = await call('admin', 'POST', '/api/party/publicities', { nodeType: 'bogus', title: 'x' });
  expectCode('POST /publicities 负数：nodeType 非法 → 400', badNode, 400, 'nodeType 只能是');
  const noTitle = await call('admin', 'POST', '/api/party/publicities', { nodeType: 'develop_party' });
  expectCode('POST /publicities 负数：标题为空 → 400', noTitle, 400, '公示标题不能为空');
  const badRange = await call('admin', 'POST', '/api/party/publicities', {
    nodeType: 'develop_party',
    title: 'x',
    startDate: addDays(TODAY, 3),
    endDate: TODAY,
  });
  expectCode('POST /publicities 负数：结束日期早于起始日期 → 400', badRange, 400, '公示结束日期不能早于起始日期');

  // 列表 / 详情
  const listStaff = await call('admin', 'GET', '/api/party/publicities?page=1&size=100');
  expectOk('GET /publicities staff 列表', listStaff);
  ok('GET /publicities 命中本轮草稿公示', hasText(listStaff, `${TAG} 发展对象公示`), describe(listStaff));

  const detail = await call('admin', 'GET', `/api/party/publicities/${ctx.pubId}`);
  expectOk('GET /publicities/{id} staff 详情', detail);
  eq('GET /publicities/{id} 返回同一 id', d(detail) && d(detail).id, ctx.pubId, detail);

  const detailDraftStudent = await call('wangxm', 'GET', `/api/party/publicities/${ctx.pubId}`);
  trackStudent('GET /publicities/{id} (student, draft)', detailDraftStudent);
  expectCode('GET /publicities/{id} 负数：学生看草稿 → 404', detailDraftStudent, 404, '公示记录不存在');
  const detailDemoDraft = await call('wangxm', 'GET', '/api/party/publicities/89053');
  expectCode('GET /publicities/{id} 负数：学生看演示草稿（89053）→ 404', detailDemoDraft, 404, '公示记录不存在');
  const detailOther = await call('wangxm', 'GET', '/api/party/publicities/89052');
  expectCode('GET /publicities/{id} 负数：学生看他人公示 → 403', detailOther, 403, '无权查看他人公示');
  const detailOwn = await call('wangxm', 'GET', '/api/party/publicities/89051');
  trackStudent('GET /publicities/{id} (student own)', detailOwn);
  expectOk('GET /publicities/{id} 学生看本人公示', detailOwn);

  const listStudent = await call('wangxm', 'GET', '/api/party/publicities?page=1&size=100');
  trackStudent('GET /publicities (student)', listStudent);
  expectOk('GET /publicities 学生端列表', listStudent);
  const stuPubRows = d(listStudent) && Array.isArray(d(listStudent).list) ? d(listStudent).list : [];
  ok('GET /publicities 学生端只含 publicizing/closed（草稿不外泄）',
    stuPubRows.length > 0 && stuPubRows.every((r) => ['publicizing', 'closed'].includes(r.status)), describe(listStudent));
  ok('GET /publicities 学生端列表不含草稿公示（89053 / 本轮）',
    !stuPubRows.some((r) => r.id === 89053 || r.id === ctx.pubId), describe(listStudent));

  // 修改
  const upd = await call('admin', 'PUT', `/api/party/publicities/${ctx.pubId}`, {
    title: `${TAG} 发展对象公示（已修改）`,
    objectionChannel: '校级受理渠道：党委组织部 电话 0000-0000000',
  });
  expectOk('PUT /publicities/{id} 修改草稿公示', upd, '已保存');
  const afterUpd = await call('admin', 'GET', `/api/party/publicities/${ctx.pubId}`);
  eq('PUT /publicities/{id} 修改生效', d(afterUpd) && d(afterUpd).title, `${TAG} 发展对象公示（已修改）`, afterUpd);

  const updEmptyChannel = await call('admin', 'PUT', `/api/party/publicities/${ctx.pubId}`, {
    title: `${TAG} 发展对象公示（已修改）`,
    objectionChannel: '',
  });
  expectCode('PUT /publicities/{id} 负数：异议入口为空 → 400', updEmptyChannel, 400, '异议入口不能为空');
  const updDemoClosed = await call('admin', 'PUT', '/api/party/publicities/89051', { title: 'x' });
  expectCode('PUT /publicities/{id} 负数：已结束公示不可改 → 400', updDemoClosed, 400, '只有「草稿」状态的公示可以修改');
  const upd404 = await call('admin', 'PUT', '/api/party/publicities/99999999', { title: 'x' });
  expectCode('PUT /publicities/{id} 不存在 → 404', upd404, 404, '公示记录不存在');
  const updDemoPublicizing = await call('admin', 'PUT', '/api/party/publicities/89052', { title: 'x' });
  expectCode('PUT /publicities/{id} 负数：公示中不可改 → 400', updDemoPublicizing, 400, '只有「草稿」状态的公示可以修改');

  // 发布
  const publish = await call('admin', 'POST', `/api/party/publicities/${ctx.pubId}/publish`);
  expectOk('POST /publicities/{id}/publish 发布公示', publish, '公示已发布');
  const afterPublish = await call('admin', 'GET', `/api/party/publicities/${ctx.pubId}`);
  eq('POST /publicities/{id}/publish 状态=publicizing', d(afterPublish) && d(afterPublish).status, 'publicizing', afterPublish);

  const publishAgain = await call('admin', 'POST', `/api/party/publicities/${ctx.pubId}/publish`);
  expectCode('POST /publicities/{id}/publish 负数：非草稿再发布 → 400', publishAgain, 400, '只有「草稿」状态的公示可以发布');
  const publishDemo = await call('admin', 'POST', '/api/party/publicities/89052/publish');
  expectCode('POST /publicities/{id}/publish 负数：演示公示中记录 → 400', publishDemo, 400, '只有「草稿」状态的公示可以发布');
  const publish404 = await call('admin', 'POST', '/api/party/publicities/99999999/publish');
  expectCode('POST /publicities/{id}/publish 不存在 → 404', publish404, 404, '公示记录不存在');

  // 关闭
  const close = await call('admin', 'POST', `/api/party/publicities/${ctx.pubId}/close`, {
    result: '公示期满，未收到异议。',
  });
  expectOk('POST /publicities/{id}/close 结束公示', close, '公示已结束');
  const afterClose = await call('admin', 'GET', `/api/party/publicities/${ctx.pubId}`);
  eq('POST /publicities/{id}/close 状态=closed', d(afterClose) && d(afterClose).status, 'closed', afterClose);

  const closeAgain = await call('admin', 'POST', `/api/party/publicities/${ctx.pubId}/close`, { result: 'x' });
  expectCode('POST /publicities/{id}/close 负数：已结束再 close 必须 400（不做幂等 200）', closeAgain, 400, '只有「公示中」的公示可以结束');
  const closeDemo = await call('admin', 'POST', '/api/party/publicities/89051/close', { result: 'x' });
  expectCode('POST /publicities/{id}/close 负数：演示数据 closed 记录 → 400', closeDemo, 400, '只有「公示中」的公示可以结束');
  const closeDraft = await call('admin', 'POST', '/api/party/publicities/89053/close', { result: 'x' });
  expectCode('POST /publicities/{id}/close 负数：草稿公示 → 400', closeDraft, 400, '只有「公示中」的公示可以结束');
  const close404 = await call('admin', 'POST', '/api/party/publicities/99999999/close', { result: 'x' });
  expectCode('POST /publicities/{id}/close 不存在 → 404', close404, 404, '公示记录不存在');

  const listAfterClose = await call('admin', 'GET', '/api/party/publicities?status=closed&page=1&size=100');
  expectOk('GET /publicities staff 按 status=closed 过滤', listAfterClose);
  ok('GET /publicities 结束后（closed）可被 staff 按状态检索到', hasText(listAfterClose, `${TAG} 发展对象公示（已修改）`), describe(listAfterClose));
}

// ---------------------------------------------------------------- 6. 材料清单（3 条）

async function phaseMaterials() {
  section('6. 材料清单（3 条路由 + 辅导员范围）');

  const add = await call('admin', 'POST', '/api/party/materials', {
    userId: 7,
    materialName: `${TAG} 跨辅导员材料项`,
    stage: 'applicant',
    required: 1,
    submitted: 0,
    ruleRef: `${TAG} 依据说明`,
  });
  expectOk('POST /materials 登记材料项（userId=7 / zhangsan 名下）', add, '材料项已登记');
  ctx.matId = d(add) ? d(add).id : null;
  eq('POST /materials 初始 submitted=0', d(add) && d(add).submitted, 0, add);

  const noUser = await call('admin', 'POST', '/api/party/materials', { materialName: 'x' });
  expectCode('POST /materials 负数：缺 userId → 400', noUser, 400, '请指定 userId');
  const noName = await call('admin', 'POST', '/api/party/materials', { userId: 7 });
  expectCode('POST /materials 负数：材料名称为空 → 400', noName, 400, '材料名称不能为空');
  const badStage = await call('admin', 'POST', '/api/party/materials', { userId: 7, materialName: 'x', stage: 'bogus' });
  expectCode('POST /materials 负数：stage 非法 → 400', badStage, 400, 'stage 只能是 applicant/activist/candidate/probationary/full');

  const listAdmin = await call('admin', 'GET', '/api/party/materials?userId=7&page=1&size=50');
  expectOk('GET /materials admin 按 userId 过滤', listAdmin);
  ok('GET /materials 命中本轮材料项', hasText(listAdmin, `${TAG} 跨辅导员材料项`), describe(listAdmin));

  const listLisi = await call('lisi', 'GET', '/api/party/materials?userId=7&page=1&size=50');
  expectOk('GET /materials lisi 按 userId=7 过滤（跨辅导员）', listLisi);
  eq('GET /materials 辅导员看不到非名下学生材料（total=0）', d(listLisi) && d(listLisi).total, 0, listLisi);
  const listZhangsan = await call('zhangsan', 'GET', '/api/party/materials?userId=7&page=1&size=50');
  ok('GET /materials 该生辅导员（zhangsan）可见', d(listZhangsan) && d(listZhangsan).total >= 1, describe(listZhangsan));

  const listStudent = await call('wangxm', 'GET', '/api/party/materials?page=1&size=50');
  trackStudent('GET /materials (student)', listStudent);
  expectOk('GET /materials 学生本人清单', listStudent);
  const stuMatRows = d(listStudent) && Array.isArray(d(listStudent).list) ? d(listStudent).list : [];
  ok('GET /materials 学生列表只含本人材料', stuMatRows.length > 0 && stuMatRows.every((r) => r.userId === 4), describe(listStudent));
  const listOther = await call('wangxm', 'GET', '/api/party/materials?userId=7');
  expectCode('GET /materials 负数：学生查他人材料 → 403', listOther, 403, '无权查看他人党建台账');

  const filtered = await call('admin', 'GET', '/api/party/materials?userId=4&stage=applicant');
  expectOk('GET /materials 按 stage 过滤', filtered);
  ok('GET /materials 演示数据命中（user 4 / applicant）', d(filtered) && d(filtered).total >= 1, describe(filtered));

  const submit = await call('admin', 'POST', `/api/party/materials/${ctx.matId}/submit`, {
    submitted: 1,
    submitDate: TODAY,
    note: `${TAG} 已收件`,
  });
  expectOk('POST /materials/{id}/submit 登记已交件', submit, '已登记交件');
  const afterSubmit = await call('admin', 'GET', '/api/party/materials?userId=7&page=1&size=50');
  ok('POST /materials/{id}/submit submitted 落库=1', hasText(afterSubmit, '"submitted":1'), describe(afterSubmit));

  const unsubmit = await call('admin', 'POST', `/api/party/materials/${ctx.matId}/submit`, { submitted: 0 });
  expectOk('POST /materials/{id}/submit 登记未交（submitted=0）', unsubmit, '已标记为未交');
  const badSubmitted = await call('admin', 'POST', `/api/party/materials/${ctx.matId}/submit`, { submitted: 2 });
  expectCode('POST /materials/{id}/submit 负数：submitted 非法 → 400', badSubmitted, 400, 'submitted 只能是 0（未交）或 1（已交）');
  const submit404 = await call('admin', 'POST', '/api/party/materials/99999999/submit', { submitted: 1 });
  expectCode('POST /materials/{id}/submit 不存在 → 404', submit404, 404, '材料项不存在');
}

// ---------------------------------------------------------------- 7. 统计与导出（2 条）

async function phaseStatsExport() {
  section('7. 党建统计与导出（2 条路由 + 脱敏/权限）');

  const stats = await call('admin', 'GET', '/api/party/stats');
  expectOk('GET /stats admin 综合统计', stats);
  const sd = d(stats) || {};
  const statKeys = ['stageDistribution', 'totalStage', 'applicationStatus', 'applicationTotal', 'publicityStatus',
    'observationReviewStatus', 'trainingStatus', 'trainingMemberStatus', 'materialByStage', 'rules'];
  ok('GET /stats 含 10 个统计分组', statKeys.every((k) => k in sd), `实测 keys=${keysOf(sd).join(',')} | ${describe(stats)}`);
  ok('GET /stats 阶段分布含演示数据（full/candidate）',
    JSON.stringify(sd.stageDistribution || []).includes('full'), describe(stats));

  const statsStudent = await call('wangxm', 'GET', '/api/party/stats');
  trackStudent('GET /stats (student)', statsStudent);
  expectOk('GET /stats 学生端亦可访问（guardLogin）', statsStudent);

  // ---- 期限文案：国标 S24 与校规 S23 两条并存
  const rules = sd.rules || {};
  ok('GET /stats rules.trainingRuleNational 含国标「三天/二十四个学时」',
    typeof rules.trainingRuleNational === 'string' && rules.trainingRuleNational.includes('三天') && rules.trainingRuleNational.includes('二十四个学时'),
    `trainingRuleNational=${JSON.stringify(rules.trainingRuleNational)}`);
  ok('GET /stats rules.trainingRuleSchool 含校规「五至七天/四十个学时」',
    typeof rules.trainingRuleSchool === 'string' && rules.trainingRuleSchool.includes('五至七天') && rules.trainingRuleSchool.includes('四十个学时'),
    `trainingRuleSchool=${JSON.stringify(rules.trainingRuleSchool)}`);
  ok('GET /stats rules.trainingHoursRule 两条口径并存且标明国标/校规',
    typeof rules.trainingHoursRule === 'string' && rules.trainingHoursRule.includes('国标') && rules.trainingHoursRule.includes('校规')
    && rules.trainingHoursRule.includes('三天') && rules.trainingHoursRule.includes('五至七天'),
    `trainingHoursRule=${JSON.stringify(rules.trainingHoursRule)}`);
  ok('GET /stats 文案带来源字样 S24 / S23',
    hasText(stats, 'S24') && hasText(stats, 'S23'), describe(stats));
  ok('GET /stats 关键条款引用齐全（第七条/第十一条/第十三条/第十七条/第三十二条）',
    ['第七条', '第十一条', '第十三条', '第十七条', '第三十二条'].every((t) => hasText(stats, t)), describe(stats));
  ok('GET /stats 公示口径含 S22（5 个工作日）与 S23（5-7 天）',
    hasText(stats, 'S22') && typeof rules.publicityRule === 'string' && rules.publicityRule.includes('5 个工作日'), describe(stats));
  ok('GET /stats 统计口径说明：不做自动资格判定 / 不替学校选择',
    typeof rules.note === 'string' && rules.note.includes('不做自动资格判定') && rules.note.includes('不替学校选'),
    `note=${JSON.stringify(rules.note)}`);

  const mineTip = await call('wangxm', 'GET', '/api/party/stages/mine');
  trackStudent('GET /stages/mine (citation)', mineTip);
  ok('GET /stages/mine 提示文案带条款来源（S23/S24/第 N 条）',
    /S2[34]/.test(mineTip.text) && /第[一二三四五六七八九十百零]+条/.test(mineTip.text), describe(mineTip));

  // ---- 导出（CSV 字节流）
  const expStages = await rawGet('admin', '/api/party/export?type=stages');
  expectHttp('GET /export?type=stages HTTP 200', expStages, 200);
  ok('GET /export Content-Type=text/csv;charset=UTF-8',
    (expStages.headers.get('content-type') || '').startsWith('text/csv'), `content-type=${expStages.headers.get('content-type')}`);
  const bom = expStages.buf.subarray(0, 3);
  ok('GET /export 前 3 字节为 UTF-8 BOM（EF BB BF）',
    bom[0] === 0xef && bom[1] === 0xbb && bom[2] === 0xbf, `实测=${[...bom].map((b) => b.toString(16)).join(' ')}`);
  ok('GET /export Content-Disposition 带文件名（party_stages_日期.csv）',
    (expStages.headers.get('content-disposition') || '').includes(`party_stages_${TODAY}.csv`),
    `content-disposition=${expStages.headers.get('content-disposition')}`);

  const csvStages = expStages.text.replace(/^\uFEFF/, '');
  const linesStages = csvStages.split(/\r?\n/).filter((l) => l.length > 0);
  eq('GET /export?type=stages 表头正确',
    linesStages[0], '学号后四位,姓名,班级,当前阶段,申请人日期,积极分子日期,发展对象日期,预备党员日期,正式党员日期,党支部', expStages);
  ok('GET /export?type=stages 含演示数据行（王小明/学号后四位 4001）',
    csvStages.includes('王小明') && csvStages.includes('4001'), describe(expStages));
  ok('GET /export?type=stages 学号为后四位（无完整 7 位学号）', !csvStages.includes('2024001'), describe(expStages));
  ok('GET /export?type=stages 无身份证号（17 位）', !/\d{17}[\dXx]/.test(csvStages), describe(expStages));
  ok('GET /export?type=stages 无手机号（11 位连续数字）', !/\d{11}/.test(csvStages), describe(expStages));
  ok('GET /export?type=stages 无「身份证/手机」列名', !/身份证|手机|电话|contact|idCard/.test(csvStages), describe(expStages));

  const expApps = await rawGet('admin', '/api/party/export?type=applications');
  expectHttp('GET /export?type=applications HTTP 200', expApps, 200);
  const csvApps = expApps.text.replace(/^\uFEFF/, '');
  eq('GET /export?type=applications 表头正确',
    csvApps.split(/\r?\n/).filter((l) => l.length > 0)[0],
    '学号后四位,姓名,班级,申请日期,谈话日期,谈话人,党支部,状态', expApps);
  ok('GET /export?type=applications 无手机号/身份证号',
    !/\d{11}/.test(csvApps) && !/\d{17}[\dXx]/.test(csvApps), describe(expApps));
  ok('GET /export?type=applications 文件名带日期',
    (expApps.headers.get('content-disposition') || '').includes(`party_applications_${TODAY}.csv`),
    `content-disposition=${expApps.headers.get('content-disposition')}`);

  const expDefault = await rawGet('admin', '/api/party/export');
  expectHttp('GET /export（缺 type 默认 stages）HTTP 200', expDefault, 200);
  ok('GET /export 缺 type 时按 stages 口径导出',
    expDefault.text.replace(/^\uFEFF/, '').startsWith('学号后四位,姓名,班级,当前阶段'), describe(expDefault));

  const expFiltered = await rawGet('admin', '/api/party/export?type=stages&stage=full');
  expectHttp('GET /export?stage=full 过滤导出 HTTP 200', expFiltered, 200);
  ok('GET /export?stage=full 只含 full 阶段（王小明 / 无赵小红）',
    expFiltered.text.includes('王小明') && !expFiltered.text.includes('赵小红'), describe(expFiltered));

  const expBadType = await rawGet('admin', '/api/party/export?type=bogus');
  expectHttp('GET /export 负数：type 非法 → 400', expBadType, 400, '期望 HTTP 400（空体）');
  eq('GET /export 负数：type 非法回空体', expBadType.buf.length, 0, expBadType);

  const expLisi = await rawGet('lisi', '/api/party/export?type=stages');
  expectHttp('GET /export 辅导员导出 HTTP 200', expLisi, 200);
  ok('GET /export 辅导员导出只含名下学生（无周杰/刘思琪）',
    !expLisi.text.includes('周杰') && !expLisi.text.includes('刘思琪'), describe(expLisi));

  const expStudent = await rawGet('wangxm', '/api/party/export?type=stages');
  expectHttp('GET /export 负数：学生 → 403', expStudent, 403, '期望 HTTP 403（空体）');
  eq('GET /export 负数：学生回空体', expStudent.buf.length, 0, expStudent);
  const expStudent2 = await rawGet('liusq', '/api/party/export?type=applications');
  expectHttp('GET /export 负数：另一学生 → 403', expStudent2, 403);
  const expForged = await rawGet('forged', '/api/party/export?type=stages');
  expectHttp('GET /export 负数：伪造会话 → 401', expForged, 401);
  const expAnon = await rawGet('anon', '/api/party/export?type=stages');
  expectHttp('GET /export 负数：未登录 → 401', expAnon, 401);
}

// ---------------------------------------------------------------- 8. 权限与保密

async function phasePermission() {
  section('8. 权限、辅导员范围与保密（§11.2-4/5/6）');

  // 未登录（伪造 JSESSIONID）
  const forgedMine = await call('forged', 'GET', '/api/party/stages/mine');
  expectCode('未登录（伪造会话）GET /stages/mine → code 401', forgedMine, 401, '请先登录');
  const forgedStages = await call('forged', 'GET', '/api/party/stages');
  expectCode('未登录（伪造会话）GET /stages → code 401', forgedStages, 401, '请先登录');
  const forgedStats = await call('forged', 'GET', '/api/party/stats');
  expectCode('未登录（伪造会话）GET /stats → code 401', forgedStats, 401, '请先登录');
  const forgedTrainings = await call('forged', 'GET', '/api/party/trainings');
  expectCode('未登录（伪造会话）GET /trainings → code 401', forgedTrainings, 401, '请先登录');
  const forgedThought = await call('forged', 'POST', '/api/party/observations/thought-report', { content: 'x' });
  expectCode('未登录（伪造会话）POST /observations/thought-report → code 401', forgedThought, 401, '请先登录');

  // 学生越权（写操作）
  const stuCreatePub = await call('wangxm', 'POST', '/api/party/publicities', { nodeType: 'develop_party', title: 'x' });
  expectCode('学生越权 POST /publicities → 403', stuCreatePub, 403, '无权访问');
  const stuCreateTraining = await call('wangxm', 'POST', '/api/party/trainings', { trainingName: 'x' });
  expectCode('学生越权 POST /trainings → 403', stuCreateTraining, 403, '无权访问');
  const stuMaterial = await call('wangxm', 'POST', '/api/party/materials', { userId: 4, materialName: 'x', stage: 'applicant' });
  expectCode('学生越权 POST /materials → 403', stuMaterial, 403, '无权访问');
  const stuObservation = await call('wangxm', 'POST', '/api/party/observations', { userId: 4, type: 'observation', content: 'x' });
  expectCode('学生越权 POST /observations → 403', stuObservation, 403, '无权访问');

  // 学生越权：staff-only 路由逐条扫（写操作与跨人查询）
  const studentForbidden = [
    ['PUT', `/api/party/stages/${ctx.stageId}/internal`, { internalNote: 'x' }, '无权访问'],
    ['GET', '/api/party/stages?userId=5', null, '无权查看他人党建台账'],
    ['GET', '/api/party/applications?userId=5', null, '无权查看他人党建台账'],
    ['PUT', `/api/party/trainings/${ctx.t1}`, { trainingName: 'x' }, '无权访问'],
    ['POST', `/api/party/training-members/${ctx.m4}/review`, {}, '无权访问'],
    ['POST', `/api/party/training-members/${ctx.m4}/complete`, { completeResult: 'pass' }, '无权访问'],
    ['PUT', `/api/party/training-members/${ctx.m4}/cert`, { certNo: 'x' }, '无权访问'],
    ['PUT', `/api/party/publicities/${ctx.pubId}`, { title: 'x' }, '无权访问'],
    ['POST', `/api/party/publicities/${ctx.pubId}/publish`, undefined, '无权访问'],
    ['POST', `/api/party/publicities/${ctx.pubId}/close`, {}, '无权访问'],
    ['POST', `/api/party/materials/${ctx.matId}/submit`, { submitted: 1 }, '无权访问'],
  ];
  for (const [method, p2, body, msg] of studentForbidden) {
    const r = await call('wangxm', method, p2, body);
    expectCode(`学生越权 ${method} ${tpl(p2)} → 403`, r, 403, msg);
  }

  // 未登录（伪造会话）：更多路由抽样扫描
  const anonSweep = [
    ['GET', '/api/party/applications', null],
    ['GET', '/api/party/publicities', null],
    ['GET', '/api/party/materials', null],
    ['GET', `/api/party/stages/${ctx.stageId}`, null],
    ['GET', '/api/party/observations/mine', null],
    ['POST', '/api/party/publicities', { nodeType: 'develop_party', title: 'x' }],
    ['POST', '/api/party/materials', { userId: 4, materialName: 'x', stage: 'applicant' }],
  ];
  for (const [method, p2, body] of anonSweep) {
    const r = await call('forged', method, p2, body);
    expectCode(`未登录（伪造会话）${method} ${tpl(p2)} → code 401`, r, 401, '请先登录');
  }

  // 辅导员范围（台账）
  const lisiStages = await call('lisi', 'GET', '/api/party/stages?page=1&size=100');
  expectOk('GET /stages lisi 名下台账', lisiStages);
  const lisiRows = d(lisiStages) && Array.isArray(d(lisiStages).list) ? d(lisiStages).list : [];
  ok('GET /stages lisi 只返回 counselor_id=3 名下学生（4/5/6）',
    lisiRows.length >= 2 && lisiRows.every((r) => [4, 5, 6].includes(r.userId)), describe(lisiStages));
  ok('GET /stages lisi 不含非名下学生（7/8）与本轮合成学生',
    !lisiRows.some((r) => r.userId === 7 || r.userId === 8 || r.userId === SYN), describe(lisiStages));
  const lisiCross = await call('lisi', 'GET', '/api/party/stages?userId=7');
  eq('GET /stages lisi 按 userId=7 过滤后为空（跨辅导员）', d(lisiCross) && d(lisiCross).total, 0, lisiCross);
  const zsStages = await call('zhangsan', 'GET', '/api/party/stages?page=1&size=100');
  expectOk('GET /stages zhangsan 名下台账', zsStages);
  ok('GET /stages zhangsan 列表不含 lisi 名下学生（4/5/6）',
    !(d(zsStages) && d(zsStages).list ? d(zsStages).list : []).some((r) => [4, 5, 6].includes(r.userId)), describe(zsStages));

  // 保密：服务层必须抹掉内部字段的**值**
  const leaks = [];
  const presentKeys = [];
  for (const { label, rec } of ctx.studentResponses) {
    const text = rec.text || '';
    for (const key of ['internalNote', 'internalDiscussion', 'internalRemark']) {
      const re = new RegExp(`"${key}"\\s*:\\s*"([^"]*)"`, 'g');
      let m;
      while ((m = re.exec(text)) !== null) {
        if (m[1] !== '') leaks.push(`${label} → ${key}="${m[1]}"`);
      }
    }
    const keys = ['internalNote', 'internalDiscussion', 'internalRemark'].filter((k) => text.includes(`"${k}"`));
    if (keys.length) presentKeys.push(`${label} → ${keys.join('/')}`);
  }
  ok('保密：学生端响应体不含内部字段的**值**（internalNote/internalDiscussion/internalRemark）',
    leaks.length === 0, `出现非空内部字段：${leaks.join('; ')}`);
  ok('保密：学生端响应体不下发内部字段**键名**（§11.2-4「学生端不下发」）',
    presentKeys.length === 0,
    `以下学生端响应仍带 internalNote/internalDiscussion/internalRemark 键（实测值为 null，未泄露内容，但键名仍在）：${presentKeys.join('; ')}`);
  ok('保密扫描覆盖到足够多的学生端响应（≥8 个）', ctx.studentResponses.length >= 8, `实测覆盖 ${ctx.studentResponses.length} 个学生端响应`);

  // 反向校验：staff 侧必须能看到内部信息（证明抹除是「按角色」而非「一律为空」）
  const staffStage = await call('admin', 'GET', '/api/party/stages/89011');
  ok('staff 侧可见内部字段值（证明学生端抹除是按角色的）', hasText(staffStage, 'internalNote') && !/"internalNote":null/.test(staffStage.text),
    describe(staffStage));
  const staffObs = await call('admin', 'GET', '/api/party/observations?userId=4&page=1&size=20');
  ok('staff 侧考察记录可见内部考察意见的值（演示数据 89021 的 internalNote 非空）',
    /"internalNote":"[^"]+"/.test(staffObs.text), describe(staffObs));
}

// ---------------------------------------------------------------- 汇总

function phaseSummary() {
  section('9. 覆盖率与汇总');
  const missed = ROUTES.filter((r) => !covered.has(r));
  lines.push('路由覆盖矩阵（38 条）：');
  for (const r of ROUTES) {
    lines.push(`  ${covered.has(r) ? '✅' : '❌'} ${r}`);
  }
  const extra = [...covered].filter((r) => !ROUTES.includes(r));
  if (extra.length) {
    lines.push(`额外覆盖（非清单内，正常为同类路由的不同调用）：${extra.join(', ')}`);
  }
  ok(`路由覆盖：38/38 全部调用（未覆盖 ${missed.length} 条）`, missed.length === 0,
    missed.length ? `未覆盖：${missed.join(', ')}` : '');

  lines.push('');
  lines.push('清理 SQL（本模块无 DELETE 路由，脚本无法自清理；演示数据 89001-89079 未被修改）：');
  const cleanup = [
    `DELETE FROM party_training_member WHERE training_id IN (SELECT id FROM party_training WHERE term = 'e2e-p16');`,
    `DELETE FROM party_training WHERE term = 'e2e-p16';`,
    `DELETE FROM party_other_placeholder;`,
    `DELETE FROM party_publicity WHERE title LIKE 'e2e-p16-%';`,
    `DELETE FROM party_observation WHERE content LIKE 'e2e-p16-%';`,
    `DELETE FROM party_material WHERE material_name LIKE 'e2e-p16-%';`,
    `DELETE FROM party_application WHERE user_id >= 990000000;`,
    `DELETE FROM party_stage WHERE user_id >= 990000000;`,
  ].filter((s) => !s.includes('placeholder'));
  for (const s of cleanup) lines.push(`  ${s}`);
  lines.push(`  本轮 TAG=${TAG}，合成学生 userId=${SYN}`);

  lines.push('');
  if (divergences.length) {
    lines.push('口径说明 / 非失败分歧：');
    for (const v of divergences) lines.push(`  - ${v}`);
  }
  lines.push(`PASS ${pass} / FAIL ${fail}`);
  const code = fail === 0 && !fatal ? 0 : 1;
  lines.push(`PROCESS EXIT ${code}`);
}

// ---------------------------------------------------------------- main

async function main() {
  lines.push('P16 学生党建 E2E 验收（/api/party/**，38 条方法级路由）');
  lines.push(`目标服务：${BASE}；脚本：tools/e2e-p16.mjs；运行标记：${TAG}`);
  lines.push(`说明：JSON 接口统一 HTTP 200 + {code,message,data}，断言打 json.code；/export 为 CSV 字节流。`);

  const phases = [
    ['0', phaseLogin],
    ['1', phaseApplications],
    ['2', phaseStages],
    ['3', phaseObservations],
    ['4', phaseTrainings],
    ['5', phasePublicities],
    ['6', phaseMaterials],
    ['7', phaseStatsExport],
    ['8', phasePermission],
  ];
  for (const [no, fn] of phases) {
    try {
      await fn();
    } catch (e) {
      fatal = true;
      fail += 1;
      failures.push({ name: `阶段 ${no} 抛出异常`, detail: String(e && e.stack ? e.stack : e) });
      lines.push(`FATAL 阶段 ${no} 抛出异常：${e && e.stack ? e.stack : e}`);
    }
  }
  phaseSummary();

  const text = lines.join('\n') + '\n';
  const failedDetails = failures.length
    ? `\n逐条失败原因：\n${failures.map((f, i) => `  ${i + 1}. ${f.name}\n     ${f.detail}`).join('\n')}\n`
    : '';
  const full = text + failedDetails;
  try {
    fs.mkdirSync(path.dirname(LOG_PATH), { recursive: true });
    fs.writeFileSync(LOG_PATH, full, 'utf8');
    if (fail === 0 && !fatal) {
      fs.writeFileSync(FINAL_LOG_PATH, full, 'utf8');
    }
  } catch (e) {
    lines.push(`（日志写入失败：${e && e.message}）`);
  }

  process.stdout.write(full);
  process.stdout.write(`日志：${LOG_PATH}${fail === 0 && !fatal ? `（全绿，另存 ${FINAL_LOG_PATH}）` : ''}\n`);
  process.exitCode = fail === 0 && !fatal ? 0 : 1;
}

main().catch((e) => {
  process.stderr.write(`脚本异常终止：${e && e.stack ? e.stack : e}\n`);
  process.exitCode = 1;
});

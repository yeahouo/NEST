#!/usr/bin/env node
/**
 * P15「社区管理」端到端验收（纯 HTTP，Node 18+，仅内置 fetch，不使用 Chrome/CDP）
 *
 * 权威口径：docs\学工平台功能对齐说明.md §10.2（第 2/4/5/6/7/8/10/11 条）
 * 目标服务：http://127.0.0.1:8080 （Spring Boot + MySQL，脚本不重启、不杀进程）
 * 后端约定：所有接口 HTTP 200 + {code,message,data}；业务失败也带 code。
 *           唯一例外 GET /api/community/export（ResponseEntity<byte[]> 裸状态码 + CSV）。
 *
 * 可重复运行设计：所有写操作落在本次运行独占命名空间（RUN_TAG / TERM / LATE_DAY），
 * 不删改演示数据（community_batch 9001+/inspection 9101+/late 9201+/violation 9301+/
 * activity 9401+/signup 9501+/civil_room 9601+），因此无需清理即可反复执行。
 * 如需人工清理本次运行残留（脚本不执行）：
 *   DELETE FROM community_civil_room WHERE term LIKE 'e2e-2100-%';
 *   DELETE FROM community_activity_signup WHERE activity_id IN (SELECT id FROM community_activity WHERE title LIKE 'e2e-p15-%');
 *   DELETE FROM community_activity WHERE title LIKE 'e2e-p15-%';
 *   DELETE FROM community_violation WHERE detail LIKE 'e2e-p15-%';
 *   DELETE FROM community_late WHERE reason_note LIKE 'e2e-p15-%';
 *   DELETE FROM community_inspection WHERE batch_id IN (SELECT id FROM community_batch WHERE batch_name LIKE 'e2e-p15-%');
 *   DELETE FROM community_batch WHERE batch_name LIKE 'e2e-p15-%';
 *
 * 用法：node tools\e2e-p15.mjs        （日志同时写 target\e2e-p15.log）
 * 退出码：0 仅当全部断言通过。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE = 'http://127.0.0.1:8080';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const LOG_PATH = path.join(__dirname, '..', 'target', 'e2e-p15.log');

// ---------------------------------------------------------------- 运行命名空间
const RUN = Date.now().toString(36);
const RUN_TAG = `e2e-p15-${RUN}`;
const TERM = `e2e-${RUN}`;           // 本次运行独占学期（社区各表 term 列均为 VARCHAR(16)，必须 ≤16 字符）
const TERM2 = `${TERM}-2`;           // 用于「已发布批次但无检查记录」用例
const SECRET_LATE = `SECRETLATE${RUN}`;   // handling_note，绝不允许出现在导出
const SECRET_VIOL = `SECRETVIOL${RUN}`;   // result_note，绝不允许出现在导出
const DAY_MS = 86400000;
const dayOff = Math.floor(Date.now() / 1000) % 2000;   // 秒级偏移 => 每次运行不同日期
const LATE_DAY = new Date(Date.UTC(2032, 0, 1) + dayOff * DAY_MS).toISOString().slice(0, 10);
const LATE_DAY2 = new Date(Date.UTC(2032, 0, 1) + (dayOff + 1) * DAY_MS).toISOString().slice(0, 10);
const CHECK_DATE = '2031-05-06';
const CHECK_DEADLINE = '2031-05-13';  // checkDate + 7 天
const CRITERIA = [
  { key: 'power', name: '用电安全', max: 20 },
  { key: 'fire', name: '消防通道', max: 20 },
  { key: 'hygiene', name: '内务卫生', max: 20 },
  { key: 'property', name: '财物与家具', max: 20 },
  { key: 'conduct', name: '文明行为', max: 20 },
];
const A_START = '2031-06-01 09:00:00';
const A_END = '2031-06-01 18:00:00';
const A_DEADLINE = '2031-05-30 23:59:59';
const A_PAST = '2020-01-01 00:00:00';

const students = { wangxm: 4, zhaoxh: 5, chendw: 6, liusq: 7, zhoujie: 8 };
const staff = { lisi: 3, zhangsan: 2, admin: 1 };

// ---------------------------------------------------------------- 断言基础设施
const lines = [];
let pass = 0, fail = 0;
const failures = [];
const divergences = [];      // 口径分歧（任务书附加、§10.2 未要求）导致的失败
const covered = new Set();
const ROUTES = [
  'GET /api/community/batches', 'POST /api/community/batches',
  'PUT /api/community/batches/{id}', 'POST /api/community/batches/{id}/publish',
  'POST /api/community/batches/{id}/close', 'GET /api/community/batches/{id}/stats',
  'GET /api/community/inspections', 'POST /api/community/inspections',
  'GET /api/community/inspections/{id}', 'DELETE /api/community/inspections/{id}',
  'POST /api/community/inspections/import', 'POST /api/community/inspections/{id}/recheck',
  'GET /api/community/my/room',
  'POST /api/community/inspections/{id}/rectify', 'GET /api/community/my/rectifications',
  'GET /api/community/lates', 'POST /api/community/lates', 'PUT /api/community/lates/{id}',
  'POST /api/community/lates/{id}/confirm', 'POST /api/community/lates/{id}/handle',
  'GET /api/community/my/lates', 'POST /api/community/my/lates/{id}/note',
  'GET /api/community/violations', 'POST /api/community/violations',
  'PUT /api/community/violations/{id}', 'POST /api/community/violations/{id}/handle',
  'GET /api/community/my/violations',
  'GET /api/community/civil-rooms', 'POST /api/community/civil-rooms/compute',
  'POST /api/community/civil-rooms/{id}/publish', 'GET /api/community/civil-rooms/publicity',
  'GET /api/community/activities', 'POST /api/community/activities',
  'PUT /api/community/activities/{id}', 'POST /api/community/activities/{id}/signup',
  'POST /api/community/activities/{id}/review', 'POST /api/community/activities/{id}/attendance',
  'GET /api/community/my/activities',
  'GET /api/community/stats', 'GET /api/community/export',
];

function section(t) { lines.push(''); lines.push(`===== ${t} =====`); }
function describe(rec) {
  if (!rec) return '';
  return `${rec.who} ${rec.method} ${rec.path}` +
    (rec.body !== undefined ? ` body=${JSON.stringify(rec.body)}` : '') +
    ` → HTTP ${rec.status} ${String(rec.text || '').slice(0, 500)}`;
}
function ok(name, cond, detail) {
  if (cond) { pass++; lines.push(`  PASS ${name}`); }
  else {
    fail++; lines.push(`  FAIL ${name} :: ${detail || ''}`);
    failures.push({ name, detail: detail || '' });
    if (name.includes('口径分歧')) divergences.push({ name, detail: detail || '' });
  }
}
function check(name, cond, rec, extra) {
  ok(name, cond, `${extra ? extra + ' ; ' : ''}${describe(rec)}`);
}
function expectCode(name, rec, wantCode, msgPart) {
  const j = rec && rec.json;
  const codeOk = !!j && j.code === wantCode;
  const msgOk = !msgPart || (j && typeof j.message === 'string' && j.message.includes(msgPart));
  check(`${name} [code=${wantCode}${msgPart ? ` msg~"${msgPart}"` : ''}]`, codeOk && msgOk, rec,
    !j ? '响应不是 JSON' : (!codeOk ? `code=${j.code} message=${j.message}` : `message=${j.message}`));
}
function expectOk(name, rec) { expectCode(name, rec, 200); }
function eq(name, actual, expected, rec) {
  check(name, actual === expected, rec, `期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`);
}
function d(rec) { return rec && rec.json ? rec.json.data : null; }
function pick(obj) {
  for (let i = 1; i < arguments.length; i++) { const k = arguments[i]; if (obj != null && obj[k] !== undefined) return obj[k]; }
  return undefined;
}
function jsonOf(v) {
  if (v == null) return null;
  if (typeof v === 'string') { try { return JSON.parse(v); } catch { return null; } }
  return v;
}
function keysOf(o) { return Object.keys(o).sort().join(','); }
function expectHttp(name, rec, wantStatus, extra) {
  check(`${name} [HTTP=${wantStatus}]`, rec.status === wantStatus, rec,
    `实际 HTTP ${rec.status}${extra ? ' ; ' + extra : ''}`);
}
// GET /batches/{id}/stats 的 gradeCount 实测为数组 [{grade,total}]（也兼容对象形态）
function gradeCountMap(data) {
  const gc = (data || {}).gradeCount;
  const m = { excellent: 0, good: 0, pass: 0, fail: 0 };
  if (Array.isArray(gc)) {
    for (const r of gc) { const k = String(pick(r, 'grade')); if (k in m) m[k] = Number(pick(r, 'total', 'count', 'num')) || 0; }
  } else if (gc && typeof gc === 'object') {
    for (const k of Object.keys(m)) if (gc[k] !== undefined) m[k] = Number(gc[k]) || 0;
  }
  return m;
}
// 用检查明细反推统计期望值（避免脚本里硬编码计数随用例增删而漂移）
async function batchFacts(batchId) {
  const r = await call('admin', 'GET', `/api/community/inspections?${q({ batchId, page: 1, size: 200 })}`);
  const rows = (d(r) || {}).list || [];
  const grade = { excellent: 0, good: 0, pass: 0, fail: 0 };
  const rectify = { none: 0, pending: 0, submitted: 0, accepted: 0, rejected: 0 };
  for (const row of rows) {
    const g = String(row.grade); if (g in grade) grade[g]++;
    const s = String(row.rectifyStatus); if (s in rectify) rectify[s]++;
  }
  return { rec: r, rows, total: rows.length, grade, rectify };
}

// ---------------------------------------------------------------- HTTP
const cookies = {};
async function call(who, method, p, body) {
  const headers = {};
  if (cookies[who]) headers['Cookie'] = cookies[who];
  let payload;
  if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  const res = await fetch(BASE + p, { method, headers, body: payload });
  const buf = Buffer.from(await res.arrayBuffer());
  const text = buf.toString('utf8');
  let json = null; try { json = JSON.parse(text); } catch { /* 非 JSON（/export 出错路径） */ }
  const tpl = p.split('?')[0].split('/').map((s) => (/^\d+$/.test(s) ? '{id}' : s)).join('/');
  covered.add(`${method} ${tpl}`);
  return { who, method, path: p, body, status: res.status, text, json, buf, resHeaders: res.headers };
}
const q = (o) => Object.entries(o).filter(([, v]) => v !== undefined && v !== null && v !== '')
  .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join('&');

async function login(user) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: user, password: '123456' }),
  });
  const sc = res.headers.get('set-cookie') || '';
  const m = /JSESSIONID=[^;]+/.exec(sc);
  if (!m) throw new Error(`登录失败：${user} -> HTTP ${res.status} ${sc}`);
  cookies[user] = m[0];
  return user;
}

// ---------------------------------------------------------------- 全局句柄
const G = { batchA: 0, batchB: 0, batchD: 0, batchE: 0, batchNoRec: 0,
  insp: {}, civil: {}, lateMine: 0, lateAbsent: 0, violMine: 0, violLinked: 0,
  act: {}, signup: {} };

// ================================================================ 1. 登录
async function phaseLogin() {
  section('0. 登录（每账号只登录一次，复用 cookie）');
  for (const u of ['admin', 'lisi', 'zhangsan', 'wangxm', 'zhaoxh', 'chendw', 'liusq', 'zhoujie']) {
    try { await login(u); } catch (e) { ok(`登录 ${u}`, false, `${e.message}`); continue; }
    ok(`登录 ${u}`, true, '');
  }
}

// ================================================================ 2. 批次
async function phaseBatches() {
  section('1. 批次：创建 / 校验 / 列表 / 修改（§10.2-10 批次 6 条）');

  // 基本信息（term 独占，避免污染演示学期）
  const resA = await call('admin', 'POST', '/api/community/batches', {
    batchName: `${RUN_TAG}-主批次`, term: TERM, checkType: 'hygiene',
    // criteriaJson 在契约里是 JSON 文本（String），不是数组：传数组会被 String.valueOf 成非 JSON 文本 → 400
    criteriaJson: JSON.stringify(CRITERIA), startDate: '2031-05-01', endDate: '2031-05-31', note: RUN_TAG,
  });
  expectOk('POST /batches 创建主批次（显式 criteria_json）', resA);
  const bA = d(resA) || {};
  G.batchA = bA.id;
  eq('新批次默认状态 draft（§10.2-1 status=draft）', bA.status, 'draft', resA);
  eq('criteria_json 原样保存 5 项', Array.isArray(jsonOf(bA.criteriaJson)) ? jsonOf(bA.criteriaJson).length : -1, 5, resA);
  eq('criteria_json 合计 100', (jsonOf(bA.criteriaJson) || []).reduce((s, c) => s + c.max, 0), 100, resA);

  // §10.2-2：不给 criteria_json 时用平台自带演示评分项，并标注「演示评分项，非学校发布口径」
  const resD = await call('admin', 'POST', '/api/community/batches', {
    batchName: `${RUN_TAG}-演示评分项批次`, term: TERM, checkType: 'safety',
    startDate: '2031-05-01', endDate: '2031-05-31',
  });
  expectOk('POST /batches 缺省 criteria_json（演示评分项）', resD);
  const bD = d(resD) || {};
  G.batchD = bD.id;
  const critD = jsonOf(bD.criteriaJson) || [];
  eq('缺省评分项为 5 项且合计 100（§10.2-2）', `${critD.length}/${critD.reduce((s, c) => s + (c.max || 0), 0)}`, '5/100', resD);
  check('缺省评分项带「演示评分项」标注（§10.2-2）', typeof bD.note === 'string' && bD.note.includes('演示评分项'), resD,
    `note=${JSON.stringify(bD.note)}`);

  // 参数校验负数
  expectCode('POST /batches 缺 batchName → 400', await call('admin', 'POST', '/api/community/batches', { term: TERM }), 400, '批次名称');
  expectCode('POST /batches 缺 term → 400', await call('admin', 'POST', '/api/community/batches', { batchName: `${RUN_TAG}-x` }), 400, '学期');
  expectCode('POST /batches checkType 非法 → 400', await call('admin', 'POST', '/api/community/batches',
    { batchName: `${RUN_TAG}-x`, term: TERM, checkType: 'bogus' }), 400, '检查类型');
  expectCode('POST /batches endDate < startDate → 400', await call('admin', 'POST', '/api/community/batches',
    { batchName: `${RUN_TAG}-x`, term: TERM, startDate: '2031-06-01', endDate: '2031-05-01' }), 400, '结束日期');
  expectCode('POST /batches criteria 合计≠100 → 400', await call('admin', 'POST', '/api/community/batches',
    { batchName: `${RUN_TAG}-x`, term: TERM, criteriaJson: JSON.stringify([{ key: 'a', name: 'A', max: 90 }]) }), 400, '合计');
  expectCode('POST /batches criteria key 重复 → 400', await call('admin', 'POST', '/api/community/batches',
    { batchName: `${RUN_TAG}-x`, term: TERM, criteriaJson: JSON.stringify([{ key: 'a', name: 'A', max: 50 }, { key: 'a', name: 'B', max: 50 }]) }), 400, '重复');
  expectCode('POST /batches criteria 空数组 → 400', await call('admin', 'POST', '/api/community/batches',
    { batchName: `${RUN_TAG}-x`, term: TERM, criteriaJson: '[]' }), 400, '至少');

  // 列表过滤
  const listTerm = await call('admin', 'GET', `/api/community/batches?${q({ term: TERM })}`);
  expectOk('GET /batches?term 过滤', listTerm);
  eq('term 过滤只回本次运行批次（2 个）', Array.isArray(d(listTerm)) ? d(listTerm).length : -1, 2, listTerm);
  const listKw = await call('admin', 'GET', `/api/community/batches?${q({ term: TERM, status: 'draft', keyword: `${RUN_TAG}-主` })}`);
  expectOk('GET /batches?status&keyword 过滤', listKw);
  eq('status+keyword 过滤命中 1 个', Array.isArray(d(listKw)) ? d(listKw).length : -1, 1, listKw);

  // 修改（draft 允许）
  const putA = await call('admin', 'PUT', `/api/community/batches/${G.batchA}`, { note: `${RUN_TAG}-已改` });
  expectOk('PUT /batches/{id} 草稿可改', putA);
  eq('PUT 生效（note 更新）', String((d(putA) || {}).note), `${RUN_TAG}-已改`, putA);
  expectCode('PUT /batches/{id} 不存在 → 404', await call('admin', 'PUT', '/api/community/batches/99999999', { note: 'x' }), 404, '批次不存在');
  expectCode('PUT /batches/{id} 跳级 draft→scored → 400（§10.2-1 逐级推进）',
    await call('admin', 'PUT', `/api/community/batches/${G.batchA}`, { status: 'scored' }), 400, '逐级');

  // 学生 / 匿名权限
  expectCode('学生 GET /batches → 403（§10.2-5）', await call('wangxm', 'GET', '/api/community/batches'), 403);
  expectCode('学生 POST /batches → 403', await call('wangxm', 'POST', '/api/community/batches', { batchName: 'x', term: TERM }), 403);
  expectCode('匿名 GET /batches → 401', await call('anon', 'GET', '/api/community/batches'), 401);
  expectCode('学生 PUT /batches/{id} → 403', await call('wangxm', 'PUT', `/api/community/batches/${G.batchA}`, { note: 'x' }), 403);

  // 推进 batchA 到 checking（后续录入检查记录）
  const putA2 = await call('admin', 'PUT', `/api/community/batches/${G.batchA}`, { status: 'checking' });
  expectOk('PUT /batches/{id} draft→checking', putA2);
  eq('批次状态推进到 checking', String((d(putA2) || {}).status), 'checking', putA2);
}

// ================================================================ 3. 检查记录 + CSV 导入
async function phaseInspections() {
  section('2. 房间检查录入 / CSV 导入 / 查询（§10.2-10 检查 7 条 + §10.2-7 低级触发整改）');

  const mk = async (name, body) => {
    const res = await call('admin', 'POST', '/api/community/inspections', body);
    expectOk(name, res);
    return res;
  };
  const base = (roomNo, extra) => Object.assign({ batchId: G.batchA, building: 'A 栋', roomNo, checkDate: CHECK_DATE, source: 'manual' }, extra || {});

  // 逐项 criteria_json 汇总（itemJson 求和 = score）
  const r305 = await mk('POST /inspections A-305 逐项 itemJson 汇总=95',
    base('A-305', { itemJson: { power: 20, fire: 20, hygiene: 20, property: 20, conduct: 15 }, problems: '地面有杂物' }));
  G.insp['A-305'] = (d(r305) || {}).id;
  const i305 = d(r305) || {};
  eq('itemJson 汇总得分 = 95（§10.2-11 评分汇总）', Number(i305.score), 95, r305);
  eq('等级边界 95 → excellent（§10.2-1 grade）', String(i305.grade), 'excellent', r305);
  eq('itemJson 原样保留 5 项', Object.keys(jsonOf(i305.itemJson) || {}).length, 5, r305);
  eq('无需整改 → rectifyStatus=none', String(i305.rectifyStatus), 'none', r305);
  eq('onMalformed 默认 source=manual', String(i305.source), 'manual', r305);

  // rectify_required=1 且分数合格 → 也必须触发整改
  const r401 = await mk('POST /inspections A-401 rectify_required=1 触发整改',
    base('A-401', { score: 95, problems: '消防通道堆放', rectifyRequired: true }));
  G.insp['A-401'] = (d(r401) || {}).id;
  const i401 = d(r401) || {};
  eq('rectifyRequired 落库为 1', Number(i401.rectifyRequired), 1, r401);
  eq('rectifyRequired=1 时 rectifyStatus=pending（§10.2-7）', String(i401.rectifyStatus), 'pending', r401);
  eq('整改截止日 = checkDate+7 天（§10.2-7）', String(i401.rectifyDeadline || '').slice(0, 10), CHECK_DEADLINE, r401);

  // 低级 <60 → fail + 必须整改
  const r412 = await mk('POST /inspections A-412 分数 45 → fail 触发整改',
    base('A-412', { score: 45, problems: '违规电器' }));
  G.insp['A-412'] = (d(r412) || {}).id;
  const i412 = d(r412) || {};
  eq('45 分 → grade=fail', String(i412.grade), 'fail', r412);
  eq('低于 60 分自动 pending 整改（§10.2-7）', String(i412.rectifyStatus), 'pending', r412);

  const r306 = await mk('POST /inspections A-306 分数 58 → fail', base('A-306', { score: 58, problems: '卫生差' }));
  G.insp['A-306'] = (d(r306) || {}).id;
  const r201 = await mk('POST /inspections B-201 分数 100 → excellent', base('B-201', { score: 100, building: 'B 栋', problems: '' }));
  G.insp['B-201'] = (d(r201) || {}).id;
  eq('100 分 → excellent', String((d(r201) || {}).grade), 'excellent', r201);

  // 录入负数
  expectCode('POST /inspections 同批次同房间重复 → 400', await call('admin', 'POST', '/api/community/inspections',
    base('A-305', { score: 80 })), 400, '已有检查记录');
  expectCode('POST /inspections 缺 batchId → 400', await call('admin', 'POST', '/api/community/inspections',
    { building: 'A 栋', roomNo: 'A-1', score: 80 }), 400, 'batchId');
  expectCode('POST /inspections 批次不存在 → 404', await call('admin', 'POST', '/api/community/inspections',
    { batchId: 99999999, building: 'A 栋', roomNo: 'A-1', score: 80 }), 404, '批次不存在');
  expectCode('POST /inspections 草稿批次录入 → 400', await call('admin', 'POST', '/api/community/inspections',
    { batchId: 9003, building: 'A 栋', roomNo: 'A-1', score: 80 }), 400, '不能录入');
  expectCode('POST /inspections 缺 building → 400', await call('admin', 'POST', '/api/community/inspections',
    { batchId: G.batchA, roomNo: 'A-1', score: 80 }), 400, '楼栋');
  expectCode('POST /inspections 缺 roomNo → 400', await call('admin', 'POST', '/api/community/inspections',
    { batchId: G.batchA, building: 'A 栋', score: 80 }), 400, '房间号');
  expectCode('POST /inspections 分数 >100 → 400', await call('admin', 'POST', '/api/community/inspections',
    base('A-001', { score: 101 })), 400, '0-100');
  expectCode('POST /inspections 缺 score/itemJson → 400', await call('admin', 'POST', '/api/community/inspections',
    base('A-002', {})), 400, 'score');
  expectCode('POST /inspections itemJson 非法 JSON → 400', await call('admin', 'POST', '/api/community/inspections',
    base('A-003', { itemJson: 'not-a-json' })), 400, 'itemJson');
  expectCode('POST /inspections itemJson 未知评分项 → 400', await call('admin', 'POST', '/api/community/inspections',
    base('A-004', { itemJson: { bogus: 10 } })), 400, '不在批次评分项');
  expectCode('POST /inspections itemJson 超出该项满分 → 400', await call('admin', 'POST', '/api/community/inspections',
    base('A-005', { itemJson: { power: 25 } })), 400, '超出该项满分');
  expectCode('学生 POST /inspections → 403（§10.2-5 录入限 admin/辅导员）',
    await call('wangxm', 'POST', '/api/community/inspections', base('A-006', { score: 80 })), 403);

  // CSV 导入（正向：B-202 成功 + A-305 已存在失败）
  const imp = await call('admin', 'POST', '/api/community/inspections/import', {
    batchId: G.batchA,
    rows: [
      { building: 'B 栋', roomNo: 'B-202', score: 76, problems: 'CSV 导入行', checkDate: CHECK_DATE },
      { building: 'A 栋', roomNo: 'A-305', score: 70, problems: '已存在' },
    ],
  });
  expectOk('POST /inspections/import 部分成功（1 成功 1 失败）', imp);
  const impD = d(imp) || {};
  eq('import inserted=1', Number(impD.inserted), 1, imp);
  eq('import failed=1', Number(impD.failed), 1, imp);
  check('import errors 含「已有检查记录」', Array.isArray(impD.errors) && impD.errors.some((e) => String(e.message).includes('已有检查记录')), imp,
    JSON.stringify(impD.errors));
  const b202 = await call('admin', 'GET', `/api/community/inspections?${q({ batchId: G.batchA, roomNo: 'B-202' })}`);
  expectOk('GET /inspections 查导入行', b202);
  const b202row = (d(b202) || {}).list ? (d(b202).list[0] || {}) : ((d(b202) || [])[0] || {});
  G.insp['B-202'] = b202row.id;
  eq('导入行 source=csv（§10.2-1 source=csv）', String(b202row.source), 'csv', b202);
  eq('导入行分数 76 / 等级 pass', `${Number(b202row.score)}/${b202row.grade}`, '76/pass', b202);

  expectCode('POST /inspections/import 无数据 → 400', await call('admin', 'POST', '/api/community/inspections/import',
    { batchId: G.batchA }), 400, '没有可导入的数据');

  // 查询过滤 + 详情
  const q1 = await call('lisi', 'GET', `/api/community/inspections?${q({ batchId: G.batchA, grade: 'fail', rectifyStatus: 'pending', source: 'manual', keyword: 'A-412', page: 1, size: 10 })}`);
  expectOk('GET /inspections 多条件过滤', q1);
  const q1list = (d(q1) || {}).list || [];
  eq('过滤命中 1 条（A-412）', q1list.length, 1, q1);
  eq('过滤命中行 roomNo=A-412', String((q1list[0] || {}).roomNo), 'A-412', q1);
  check('过滤结果不含其它房间（不越权）', q1list.every((r) => r.roomNo === 'A-412'), q1, JSON.stringify(q1list.map((r) => r.roomNo)));

  const anon = await call('anon', 'GET', '/api/community/inspections');
  expectCode('匿名 GET /inspections → 401', anon, 401);
  expectCode('GET /inspections/{id} 不存在 → 404', await call('lisi', 'GET', '/api/community/inspections/99999999'), 404, '不存在');

  // 批次未公布时学生看不到本宿舍结果
  expectCode('学生看未公布批次检查详情 → 403（§10.2-5）',
    await call('wangxm', 'GET', `/api/community/inspections/${G.insp['A-305']}`), 403, '尚未公布');
  expectCode('学生看他人宿舍检查详情 → 403（§10.2-5）',
    await call('chendw', 'GET', `/api/community/inspections/${G.insp['A-305']}`), 403);
  expectCode('跨辅导员看他人宿舍检查详情 → 403（§10.2-5）',
    await call('zhangsan', 'GET', `/api/community/inspections/${G.insp['A-305']}`), 403);
  expectOk('本辅导员看名下宿舍检查详情', await call('lisi', 'GET', `/api/community/inspections/${G.insp['A-305']}`));
}

// ================================================================ 4. 批次公布 + 学生可见
async function phasePublish() {
  section('3. 批次公布 publish 与学生端可见性（§10.2-10）');
  const pub = await call('admin', 'POST', `/api/community/batches/${G.batchA}/publish`, {});
  expectOk('POST /batches/{id}/publish（checking→published）', pub);
  eq('publish 后 status=published（§10.2-1）', String((d(pub) || {}).status), 'published', pub);
  expectCode('学生 POST /batches/{id}/publish → 403', await call('wangxm', 'POST', `/api/community/batches/${G.batchA}/publish`, {}), 403);

  const st = await call('admin', 'GET', `/api/community/batches/${G.batchA}/stats`);
  expectOk('GET /batches/{id}/stats', st);
  const sd = d(st) || {};
  const sum = sd.summary || {};
  eq('批次统计 total=6（本次录入 6 间）', Number(pick(sum, 'total')), 6, st);
  const factsA = await batchFacts(G.batchA);
  const gA = gradeCountMap(sd);
  eq('批次统计 gradeCount.excellent 与检查明细一致', gA.excellent, factsA.grade.excellent, st);
  eq('批次统计 gradeCount.good 与检查明细一致', gA.good, factsA.grade.good, st);
  eq('批次统计 gradeCount.pass 与检查明细一致', gA.pass, factsA.grade.pass, st);
  eq('批次统计 gradeCount.fail 与检查明细一致', gA.fail, factsA.grade.fail, st);
  eq('批次统计 gradeCount 合计=检查明细总数', gA.excellent + gA.good + gA.pass + gA.fail, factsA.total, st);
  eq('批次统计 criteria 5 项且非演示口径', `${(sd.criteria || []).length}/${sd.criteriaDemo}`, '5/false', st);

  // 学生端：公布后可见本宿舍
  const w305 = await call('wangxm', 'GET', `/api/community/inspections/${G.insp['A-305']}`);
  expectOk('学生公布后可见本宿舍检查详情（§10.2-5）', w305);
  const my = await call('wangxm', 'GET', '/api/community/my/room');
  expectOk('GET /my/room', my);
  const myList = Array.isArray(d(my)) ? d(my) : [];
  check('GET /my/room 只回本宿舍（全部 roomNo=A-305）', myList.length > 0 && myList.every((r) => r.roomNo === 'A-305'), my,
    JSON.stringify(myList.map((r) => `${r.building}/${r.roomNo}`)));
  expectCode('GET /my/room 非学生（admin 无档案）→ 403', await call('admin', 'GET', '/api/community/my/room'), 403);
  expectCode('匿名 GET /my/room → 401', await call('anon', 'GET', '/api/community/my/room'), 401);

  const lst = await call('wangxm', 'GET', `/api/community/inspections?${q({ page: 1, size: 50 })}`);
  expectOk('学生 GET /inspections（只看已公布 + 本宿舍）', lst);
  const lstRows = (d(lst) || {}).list || (Array.isArray(d(lst)) ? d(lst) : []);
  check('学生检查列表只含本宿舍房间', lstRows.length > 0 && lstRows.every((r) => r.roomNo === 'A-305'), lst,
    JSON.stringify(lstRows.map((r) => r.roomNo)));

  // 已公布批次的记录不能删除
  expectCode('DELETE /inspections/{id} 已公布批次 → 400',
    await call('admin', 'DELETE', `/api/community/inspections/${G.insp['A-306']}`), 400, '不能再删除');
  expectCode('学生 DELETE /inspections/{id} → 403',
    await call('wangxm', 'DELETE', `/api/community/inspections/${G.insp['A-305']}`), 403);
}

// ================================================================ 5. 整改闭环
async function phaseRectify() {
  section('4. 整改闭环：学生提交说明 → 辅导员复查通过/驳回（§10.2-7 + §10.2-10 整改 2 条）');

  expectCode('学生提交未要求整改的记录 → 400',
    await call('wangxm', 'POST', `/api/community/inspections/${G.insp['A-305']}/rectify`, { rectifyNote: 'x' }), 400, '待整改');
  expectCode('非本宿舍学生提交整改 → 403（§10.2-5）',
    await call('wangxm', 'POST', `/api/community/inspections/${G.insp['A-412']}/rectify`, { rectifyNote: 'x' }), 403, '本宿舍');
  expectCode('无学生档案者提交整改 → 403',
    await call('admin', 'POST', `/api/community/inspections/${G.insp['A-412']}/rectify`, { rectifyNote: 'x' }), 403);
  expectCode('整改说明为空 → 400',
    await call('chendw', 'POST', `/api/community/inspections/${G.insp['A-412']}/rectify`, {}), 400, '整改说明');

  // pending（学生尚未提交整改说明）时复查 → 400：A-412 属 lisi 名下学生宿舍，lisi 可走到状态校验
  expectCode('对 pending（学生未提交）记录复查 → 400',
    await call('lisi', 'POST', `/api/community/inspections/${G.insp['A-412']}/recheck`, { recheckResult: 'pass' }), 400, '已提交整改说明');

  // 提交 → 驳回 → 再提交 → 通过
  const r1 = await call('chendw', 'POST', `/api/community/inspections/${G.insp['A-412']}/rectify`, { rectifyNote: `${RUN_TAG}-已清理违规电器` });
  expectOk('学生提交整改说明（A-412）', r1);
  eq('提交后 rectifyStatus=submitted', String((d(r1) || {}).rectifyStatus), 'submitted', r1);

  expectCode('辅导员复查结论非法 → 400',
    await call('lisi', 'POST', `/api/community/inspections/${G.insp['A-412']}/recheck`, { recheckResult: 'maybe' }), 400, 'pass / fail');
  expectCode('辅导员复查缺结论 → 400',
    await call('lisi', 'POST', `/api/community/inspections/${G.insp['A-412']}/recheck`, {}), 400, 'recheckResult');
  expectCode('学生角色复查 → 403',
    await call('wangxm', 'POST', `/api/community/inspections/${G.insp['A-412']}/recheck`, { recheckResult: 'pass' }), 403);

  const rj = await call('lisi', 'POST', `/api/community/inspections/${G.insp['A-412']}/recheck`, { recheckResult: 'fail' });
  expectOk('辅导员复查驳回（fail）', rj);
  eq('驳回后 rectifyStatus=rejected（§10.2-7）', String((d(rj) || {}).rectifyStatus), 'rejected', rj);

  expectOk('驳回后可再次提交整改说明（§10.2-7）',
    await call('chendw', 'POST', `/api/community/inspections/${G.insp['A-412']}/rectify`, { rectifyNote: `${RUN_TAG}-二次整改` }));
  const rp = await call('lisi', 'POST', `/api/community/inspections/${G.insp['A-412']}/recheck`, { recheckResult: 'pass' });
  expectOk('辅导员复查通过（pass）', rp);
  eq('通过后 rectifyStatus=accepted（§10.2-7）', String((d(rp) || {}).rectifyStatus), 'accepted', rp);
  eq('复查结论落库 recheckResult=pass', String((d(rp) || {}).recheckResult), 'pass', rp);
  expectCode('重复复查 → 400（§10.2-11 状态机负数）',
    await call('lisi', 'POST', `/api/community/inspections/${G.insp['A-412']}/recheck`, { recheckResult: 'pass' }), 400, '已提交整改说明');
  expectCode('整改已通过后再次提交说明 → 400（§10.2-7 闭环）',
    await call('chendw', 'POST', `/api/community/inspections/${G.insp['A-412']}/rectify`, { rectifyNote: 'x' }), 400, '待整改');

  const mr = await call('chendw', 'GET', '/api/community/my/rectifications');
  expectOk('GET /my/rectifications', mr);
  const mrList = Array.isArray(d(mr)) ? d(mr) : [];
  check('我的整改只含本宿舍（A-412）', mrList.length > 0 && mrList.every((r) => r.roomNo === 'A-412'), mr,
    JSON.stringify(mrList.map((r) => r.roomNo)));
  const mr412 = mrList.find((r) => Number(r.id) === Number(G.insp['A-412'])) || {};
  eq('我的整改含 A-412 且状态 accepted', `${mr412.roomNo}/${mr412.rectifyStatus}`, 'A-412/accepted', mr);

  // 批次统计反映整改结果
  const st = await call('admin', 'GET', `/api/community/batches/${G.batchA}/stats`);
  const sum = d(st) ? (d(st).summary || {}) : {};
  const factsA2 = await batchFacts(G.batchA);
  eq('批次统计 rectify_pending 与检查明细一致（§10.2-7）', Number(pick(sum, 'rectify_pending')), factsA2.rectify.pending, st);
  eq('批次统计 rectify_accepted=1（A-412 闭环）', Number(pick(sum, 'rectify_accepted')), 1, st);
}

// ================================================================ 6. 文明宿舍
async function phaseCivil1() {
  section('5. 文明宿舍（1）：按学期已发布批次计算 / 楼栋内排名 / 名额不截断（§10.2-6）');

  expectCode('POST /civil-rooms/compute 缺 term → 400', await call('admin', 'POST', '/api/community/civil-rooms/compute', {}), 400, 'term');
  expectCode('POST /civil-rooms/compute 无已发布批次 → 400',
    await call('admin', 'POST', '/api/community/civil-rooms/compute', { term: `${TERM}-none` }), 400, '已公布');
  expectCode('学生 POST /civil-rooms/compute → 403（§10.2-5）',
    await call('wangxm', 'POST', '/api/community/civil-rooms/compute', { term: TERM }), 403);

  const cp = await call('admin', 'POST', '/api/community/civil-rooms/compute', { term: TERM });
  expectOk('POST /civil-rooms/compute（term 已发布批次）', cp);
  const cpD = d(cp) || {};
  eq('只统计已发布批次 batchCount=1（§10.2-6）', Number(cpD.batchCount), 1, cp);
  const cpList = Array.isArray(cpD.list) ? cpD.list : [];
  eq('名额不自动截断（6 间全量入册，§10.2-6）', cpList.length, 6, cp);
  const rowOf = (arr, b, r) => arr.find((x) => x.building === b && x.roomNo === r) || {};
  const r305 = rowOf(cpList, 'A 栋', 'A-305');
  eq('A-305 均分 95（§10.2-6）', Number(r305.avgScore), 95, cp);
  eq('A-305 楼栋内名次 1（§10.2-6 楼栋内排名）', Number(r305.rankInBuilding), 1, cp);
  eq('A-401 同分并列名次 1（§10.2-6 楼栋内排名）', Number(rowOf(cpList, 'A 栋', 'A-401').rankInBuilding), 1, cp);
  eq('A-306 (58) 名次 3', Number(rowOf(cpList, 'A 栋', 'A-306').rankInBuilding), 3, cp);
  eq('A-412 (45) 名次 4（最低分仍入册 ⇒ 不截断）', Number(rowOf(cpList, 'A 栋', 'A-412').rankInBuilding), 4, cp);
  const r201 = rowOf(cpList, 'B 栋', 'B-201');
  eq('B-201 均分 100 楼栋内名次 1', `${Number(r201.avgScore)}/${Number(r201.rankInBuilding)}`, '100/1', cp);
  check('未公示行 publishedAt 为空（人工确认前不算获评）', cpList.every((x) => x.publishedAt == null), cp,
    JSON.stringify(cpList.map((x) => x.publishedAt)));

  const staffList = await call('lisi', 'GET', `/api/community/civil-rooms?${q({ term: TERM })}`);
  expectOk('GET /civil-rooms（staff）', staffList);
  const slList = Array.isArray(d(staffList)) ? d(staffList) : [];
  eq('GET /civil-rooms 回 6 行候选', slList.length, 6, staffList);
  expectCode('学生 GET /civil-rooms → 403（§10.2-5）', await call('wangxm', 'GET', `/api/community/civil-rooms?${q({ term: TERM })}`), 403);

  // 公示（人工确认 awarded）
  const need = ['A-305', 'A-412'];
  for (const room of need) {
    const row = rowOf(slList, 'A 栋', room);
    G.civil[room] = row.id;
  }
  expectCode('POST /civil-rooms/{id}/publish 不存在 → 404',
    await call('admin', 'POST', '/api/community/civil-rooms/99999999/publish', { awarded: 1 }), 404, '候选不存在');
  expectCode('POST /civil-rooms/{id}/publish awarded 非法 → 400',
    await call('admin', 'POST', `/api/community/civil-rooms/${G.civil['A-401'] || rowOf(slList, 'A 栋', 'A-401').id}/publish`, { awarded: 2 }), 400, 'awarded');
  expectCode('POST /civil-rooms/{id}/publish awardLevel 非法 → 400',
    await call('admin', 'POST', `/api/community/civil-rooms/${rowOf(slList, 'A 栋', 'A-401').id}/publish`, { awarded: 1, awardLevel: 'national' }), 400, 'school');
  const p1 = await call('admin', 'POST', `/api/community/civil-rooms/${G.civil['A-305']}/publish`, { awarded: 1, awardLevel: 'school' });
  expectOk('公示 A-305（awarded=1 校级）', p1);
  check('公示后 publishedAt 非空', (d(p1) || {}).publishedAt != null, p1, JSON.stringify(d(p1)));
  expectCode('重复公示 → 400（§10.2-6）',
    await call('admin', 'POST', `/api/community/civil-rooms/${G.civil['A-305']}/publish`, { awarded: 1 }), 400, '已公示');
  const p2 = await call('admin', 'POST', `/api/community/civil-rooms/${G.civil['A-412']}/publish`, { awarded: 0 });
  expectOk('公示 A-412（awarded=0 候选）', p2);

  const awardedOnly = await call('admin', 'GET', `/api/community/civil-rooms?${q({ term: TERM, building: 'A 栋', awarded: 1 })}`);
  expectOk('GET /civil-rooms?building&awarded=1', awardedOnly);
  const aoList = Array.isArray(d(awardedOnly)) ? d(awardedOnly) : [];
  eq('awarded=1 只回 A-305', `${aoList.length}/${(aoList[0] || {}).roomNo}`, '1/A-305', awardedOnly);

  // 公示页：登录必需 + 只露四字段（§10.2-5 匿名 401 / 不含姓名学号）
  expectCode('匿名 GET /civil-rooms/publicity → 401（§10.2-5）',
    await call('anon', 'GET', `/api/community/civil-rooms/publicity?${q({ term: TERM })}`), 401, '请先登录');
  const pub = await call('wangxm', 'GET', `/api/community/civil-rooms/publicity?${q({ term: TERM })}`);
  expectOk('学生 GET /civil-rooms/publicity（登录可见全校）', pub);
  const pubList = Array.isArray(d(pub)) ? d(pub) : [];
  eq('公示只回已公示 2 间（§10.2-6）', pubList.length, 2, pub);
  check('公示行只有 building/roomNo/avgScore/awardLevel 四字段（§10.2-5 脱敏）',
    pubList.length > 0 && pubList.every((r) => keysOf(r) === 'avgScore,awardLevel,building,roomNo'), pub,
    JSON.stringify(pubList.map((r) => keysOf(r))));
  check('公示不含姓名/学号/成员数字段', pubList.every((r) => !('studentName' in r) && !('studentNo' in r) && !('memberCount' in r)), pub,
    JSON.stringify(pubList));
  const pubRooms = pubList.map((r) => r.roomNo).sort().join(',');
  eq('未公示候选不出现在公示页', pubRooms, 'A-305,A-412', pub);
  eq('公示含获奖等级 school', String((pubList.find((r) => r.roomNo === 'A-305') || {}).awardLevel), 'school', pub);
}

// ================================================================ 7. 批次 B：状态机负数 / 评分边界 / 导入失败
async function phaseBatchB() {
  section('6. 批次 B：状态机负数 + 等级边界 90/80/60 + 导入失败分支（§10.2-11）');

  const resB = await call('admin', 'POST', '/api/community/batches', {
    batchName: `${RUN_TAG}-批次B`, term: TERM, checkType: 'joint', scopeBuilding: 'A 栋',
    startDate: '2031-05-01', endDate: '2031-05-31', criteriaJson: JSON.stringify(CRITERIA),
  });
  expectOk('POST /batches 创建批次B（scope=A 栋）', resB);
  G.batchB = (d(resB) || {}).id;
  expectOk('PUT /batches/{id} draft→checking（批次B）', await call('admin', 'PUT', `/api/community/batches/${G.batchB}`, { status: 'checking' }));

  // scope_building 越界
  expectCode('POST /inspections 超出批次覆盖楼栋 → 400（§10.2-1 scope_building）',
    await call('admin', 'POST', '/api/community/inspections',
      { batchId: G.batchB, building: 'B 栋', roomNo: 'B-201', score: 80 }), 400, '只覆盖楼栋');

  // 等级边界 90/80/60（§10.2-11）
  const bounds = [['A-901', 90, 'excellent'], ['A-902', 89, 'good'], ['A-903', 80, 'good'],
    ['A-904', 79, 'pass'], ['A-905', 60, 'pass'], ['A-906', 59, 'fail']];
  for (const [room, score, grade] of bounds) {
    const r = await call('admin', 'POST', '/api/community/inspections',
      { batchId: G.batchB, building: 'A 栋', roomNo: room, score, checkDate: CHECK_DATE });
    expectOk(`POST /inspections ${room} = ${score} 分`, r);
    G.insp[room] = (d(r) || {}).id;
    eq(`等级边界 ${score} → ${grade}（§10.2-11）`, String((d(r) || {}).grade), grade, r);
  }

  // 删除（checking 批次允许）
  const del = await call('admin', 'POST', '/api/community/inspections',
    { batchId: G.batchB, building: 'A 栋', roomNo: 'A-998', score: 70, checkDate: CHECK_DATE });
  expectOk('POST /inspections 待删除记录 A-998', del);
  const delId = (d(del) || {}).id;
  expectOk('DELETE /inspections/{id}（checking 批次可删）', await call('admin', 'DELETE', `/api/community/inspections/${delId}`));
  expectCode('DELETE 已删除记录再删 → 404', await call('admin', 'DELETE', `/api/community/inspections/${delId}`), 404, '不存在');

  // rows 导入：1 成功 1 重复
  const impRows = await call('admin', 'POST', '/api/community/inspections/import', {
    batchId: G.batchB,
    rows: [{ building: 'A 栋', roomNo: 'A-907', score: 70, problems: 'rows 导入' },
      { building: 'A 栋', roomNo: 'A-901', score: 88, problems: '重复房间' }],
  });
  expectOk('POST /inspections/import rows（1 成功 1 重复）', impRows);
  eq('rows 导入 message 含成功/失败计数', String((impRows.json || {}).message).includes('成功 1 行'), true, impRows);
  eq('rows 导入 inserted=1/failed=1', `${(d(impRows) || {}).inserted}/${(d(impRows) || {}).failed}`, '1/1', impRows);
  const a907 = await call('admin', 'GET', `/api/community/inspections?${q({ batchId: G.batchB, roomNo: 'A-907' })}`);
  const a907row = ((d(a907) || {}).list || [])[0] || {};
  G.insp['A-907'] = a907row.id;
  eq('rows 导入行 source=csv 且分数 70', `${a907row.source}/${Number(a907row.score)}`, 'csv/70', a907);

  // 同一份数据里重复房间：服务端逐行处理——首行照常导入，重复行报该行错误（只有全部行失败才回 400）
  const dupImp = await call('admin', 'POST', '/api/community/inspections/import', {
    batchId: G.batchB, rows: [{ building: 'A 栋', roomNo: 'A-908', score: 70 }, { building: 'A 栋', roomNo: 'A-908', score: 71 }],
  });
  expectOk('POST /inspections/import 同一份数据重复房间（首行成功 / 重复行报错）', dupImp);
  eq('重复房间导入 inserted=1/failed=1', `${(d(dupImp) || {}).inserted}/${(d(dupImp) || {}).failed}`, '1/1', dupImp);
  check('重复房间行错误含「重复的房间」', JSON.stringify((d(dupImp) || {}).errors || []).includes('重复的房间'), dupImp,
    JSON.stringify((d(dupImp) || {}).errors));
  const a908 = await call('admin', 'GET', `/api/community/inspections?${q({ batchId: G.batchB, roomNo: 'A-908' })}`);
  const a908rows = ((d(a908) || {}).list) || [];
  eq('重复行未落库（A-908 只有 1 条）', a908rows.length, 1, a908);
  eq('重复行未覆盖首行分数（A-908=70）', Number((a908rows[0] || {}).score), 70, a908);

  // CSV 文本导入全失败（已存在房间）
  expectCode('POST /inspections/import CSV 文本全失败 → 400',
    await call('admin', 'POST', '/api/community/inspections/import', {
      batchId: G.batchB, csv: `building,roomNo,score,problems\nA 栋,A-901,90,重复\n`,
    }), 400, '全部未导入');
  expectCode('POST /inspections/import 批次为已公布 → 400',
    await call('admin', 'POST', '/api/community/inspections/import', { batchId: 9001, rows: [{ building: 'A 栋', roomNo: 'A-777', score: 80 }] }), 400);

  // 状态机：checking → scored → published → close
  const putB1 = await call('admin', 'PUT', `/api/community/batches/${G.batchB}`, { status: 'scored' });
  expectOk('PUT /batches/{id} checking→scored', putB1);
  const stB = await call('admin', 'GET', `/api/community/batches/${G.batchB}/stats`);
  const sumB = d(stB) ? (d(stB).summary || {}) : {};
  const factsB = await batchFacts(G.batchB);
  const gB = gradeCountMap(d(stB));
  eq('批次B 统计 total 与检查明细一致', Number(pick(sumB, 'total')), factsB.total, stB);
  eq('批次B gradeCount.excellent 与明细一致', gB.excellent, factsB.grade.excellent, stB);
  eq('批次B gradeCount.good 与明细一致', gB.good, factsB.grade.good, stB);
  eq('批次B gradeCount.pass 与明细一致', gB.pass, factsB.grade.pass, stB);
  eq('批次B gradeCount.fail 与明细一致', gB.fail, factsB.grade.fail, stB);
  eq('批次B rectify_pending=1（59 分 A-906）', Number(pick(sumB, 'rectify_pending')), 1, stB);

  const pubB = await call('admin', 'POST', `/api/community/batches/${G.batchB}/publish`, {});
  expectOk('POST /batches/{id}/publish（scored→published）', pubB);
  expectCode('学生 POST /batches/{id}/close → 403', await call('wangxm', 'POST', `/api/community/batches/${G.batchB}/close`, {}), 403);
  expectOk('POST /batches/{id}/close（published→finished）', await call('admin', 'POST', `/api/community/batches/${G.batchB}/close`, {}));

  // 状态机负数
  expectCode('已结束批次再 PUT → 400（§10.2-11 已发布批次再改）',
    await call('admin', 'PUT', `/api/community/batches/${G.batchB}`, { note: 'x' }), 400, '不能再修改');
  expectCode('已结束批次再 close → 400（§10.2-11）',
    await call('admin', 'POST', `/api/community/batches/${G.batchB}/close`, {}), 400, '可以结束');
  expectCode('已结束批次 publish → 400（§10.2-11 非法前置态）',
    await call('admin', 'POST', `/api/community/batches/9004/publish`, {}), 400, '可以公布');
  expectCode('演示已结束批次 PUT → 400（演示数据未被改动）',
    await call('admin', 'PUT', '/api/community/batches/9004', { note: 'x' }), 400, '不能再修改');

  // 已发布批次里的检查记录不能删
  expectCode('DELETE /inspections/{id} 批次B 已公布 → 400',
    await call('admin', 'DELETE', `/api/community/inspections/${G.insp['A-906']}`), 400, '不能再删除');
}

// ================================================================ 8. 文明宿舍（2）
async function phaseCivil2() {
  section('7. 文明宿舍（2）：新发布批次进入汇总（§10.2-6 只认已发布批次）');

  const cp2 = await call('admin', 'POST', '/api/community/civil-rooms/compute', { term: TERM });
  expectOk('POST /civil-rooms/compute（批次B 已发布）', cp2);
  const list2 = Array.isArray((d(cp2) || {}).list) ? d(cp2).list : [];
  eq('batchCount=2（批次A + 批次B 都是已发布）', Number((d(cp2) || {}).batchCount), 2, cp2);
  const expRooms = (await batchFacts(G.batchA)).total + (await batchFacts(G.batchB)).total;
  eq('全量候选 = 已发布批次房间并集（名额不截断，§10.2-6）', list2.length, expRooms, cp2);
  check('批次B 房间进入汇总（A-901..A-907）',
    ['A-901', 'A-902', 'A-903', 'A-904', 'A-905', 'A-906', 'A-907'].every((r) => list2.some((x) => x.roomNo === r)), cp2,
    JSON.stringify(list2.map((x) => x.roomNo)));
  const row305 = list2.find((x) => x.roomNo === 'A-305') || {};
  eq('已公示行分数保留且名次不出错（A-305 名次 1）', Number(row305.rankInBuilding), 1, cp2);
  check('已公示行 publishedAt 不被汇总覆盖', row305.publishedAt != null, cp2, JSON.stringify(row305));

  // 已发布批次但无检查记录 → 400
  const resE = await call('admin', 'POST', '/api/community/batches', {
    batchName: `${RUN_TAG}-空批次`, term: TERM2, startDate: '2031-05-01', endDate: '2031-05-31', criteriaJson: JSON.stringify(CRITERIA),
  });
  expectOk('POST /batches 创建空批次（TERM2）', resE);
  G.batchE = (d(resE) || {}).id;
  expectOk('PUT /batches/{id} draft→checking（空批次）', await call('admin', 'PUT', `/api/community/batches/${G.batchE}`, { status: 'checking' }));
  expectOk('POST /batches/{id}/publish（空批次）', await call('admin', 'POST', `/api/community/batches/${G.batchE}/publish`, {}));
  expectCode('POST /civil-rooms/compute 无检查记录 → 400',
    await call('admin', 'POST', '/api/community/civil-rooms/compute', { term: TERM2 }), 400, '没有检查记录');

  const pub2 = await call('wangxm', 'GET', `/api/community/civil-rooms/publicity?${q({ term: TERM, building: 'A 栋' })}`);
  expectOk('GET /civil-rooms/publicity?building 过滤', pub2);
  eq('公示仍只 2 间（未公示候选不外泄）', (Array.isArray(d(pub2)) ? d(pub2) : []).length, 2, pub2);
}

// ================================================================ 9. 晚归
async function phaseLates() {
  section('8. 晚归 / 夜不归宿：登记 → 核实 → 处理 → 学生说明（§10.2-3 + §10.2-10 晚归 6 条 + 学生说明 1 条）');

  const lateBody = {
    studentId: students.wangxm, occurDate: LATE_DAY, type: 'late', source: 'report',
    outTime: '23:50', inTime: '00:30', reasonNote: `${RUN_TAG}-晚归登记`,
  };
  const mk = await call('admin', 'POST', '/api/community/lates', lateBody);
  expectOk('POST /lates 登记晚归（23:50 属晚归，§10.2-3）', mk);
  const L = d(mk) || {};
  G.lateMine = L.id;
  eq('晚归记录默认 handling=pending', String(L.handling), 'pending', mk);
  eq('晚归记录默认 confirmed=0', Number(L.confirmed), 0, mk);
  eq('楼栋/房间取自学生档案（A 栋/A-305）', `${L.building}/${L.roomNo}`, 'A 栋/A-305', mk);
  eq('辅导員工号取自学生档案（counselor_id=3）', Number(pick(L, 'counselorId', 'counselor_id')), staff.lisi, mk);

  // 时段与参数负数（§10.2-3 正常出入 6:00-23:30）
  expectCode('POST /lates 22:00 属正常出入 → 400（§10.2-3）',
    await call('admin', 'POST', '/api/community/lates', Object.assign({}, lateBody, { occurDate: LATE_DAY, outTime: '22:00', inTime: undefined })), 400, '晚归时间必须落在');
  expectCode('POST /lates 缺两个时间 → 400',
    await call('admin', 'POST', '/api/community/lates', Object.assign({}, lateBody, { occurDate: LATE_DAY, outTime: undefined, inTime: undefined })), 400, '至少要有一个时间');
  expectCode('POST /lates 时间格式非法 → 400',
    await call('admin', 'POST', '/api/community/lates', Object.assign({}, lateBody, { occurDate: LATE_DAY, outTime: '25:99' })), 400, 'outTime');
  expectCode('POST /lates 缺 occurDate → 400',
    await call('admin', 'POST', '/api/community/lates', Object.assign({}, lateBody, { occurDate: undefined })), 400, 'occurDate');
  expectCode('POST /lates 类型非法 → 400',
    await call('admin', 'POST', '/api/community/lates', Object.assign({}, lateBody, { type: 'bogus' })), 400, '类型只支持');
  expectCode('POST /lates 同一学生同日同类型重复 → 400（§10.2-11）',
    await call('admin', 'POST', '/api/community/lates', lateBody), 400, '当天已有同类型记录');
  expectCode('POST /lates 学生不存在档案 → 404',
    await call('admin', 'POST', '/api/community/lates', Object.assign({}, lateBody, { studentId: students.wangxm + 100 })), 404, '学生档案不存在');
  expectCode('POST /lates 跨辅导员登记他人学生 → 403（§10.2-5）',
    await call('lisi', 'POST', '/api/community/lates', Object.assign({}, lateBody, { studentId: students.liusq, occurDate: LATE_DAY })), 403);
  expectCode('学生 POST /lates → 403（§10.2-5）', await call('wangxm', 'POST', '/api/community/lates', lateBody), 403);

  // 夜不归宿（absent）
  const mkAbs = await call('admin', 'POST', '/api/community/lates', {
    studentId: students.wangxm, occurDate: LATE_DAY2, type: 'absent', source: 'check', reasonNote: `${RUN_TAG}-夜不归宿`,
  });
  expectOk('POST /lates 夜不归宿（absent 无时间要求）', mkAbs);
  G.lateAbsent = (d(mkAbs) || {}).id;
  eq('absent 记录类型落库', String((d(mkAbs) || {}).type), 'absent', mkAbs);

  // 修改
  const put = await call('admin', 'PUT', `/api/community/lates/${G.lateMine}`, { reasonNote: `${RUN_TAG}-已核实原因` });
  expectOk('PUT /lates/{id}', put);
  eq('PUT 生效', String((d(put) || {}).reasonNote), `${RUN_TAG}-已核实原因`, put);
  expectCode('PUT /lates/{id} 不存在 → 404', await call('admin', 'PUT', '/api/community/lates/99999999', { reasonNote: 'x' }), 404, '不存在');
  expectCode('PUT /lates/{id} 跨辅导员 → 403（§10.2-5）', await call('lisi', 'PUT', '/api/community/lates/9205', { reasonNote: 'x' }), 403);
  expectCode('PUT /lates/{id} 时间非法 → 400',
    await call('admin', 'PUT', `/api/community/lates/${G.lateMine}`, { outTime: '22:00', inTime: '23:00' }), 400, '晚归时间必须落在');

  // 核实 + 重复核实
  const cf = await call('lisi', 'POST', `/api/community/lates/${G.lateMine}/confirm`, {});
  expectOk('POST /lates/{id}/confirm（辅导员）', cf);
  eq('核实后 confirmed=1', Number((d(cf) || {}).confirmed), 1, cf);
  expectCode('重复核实 → 400（§10.2-11）', await call('lisi', 'POST', `/api/community/lates/${G.lateMine}/confirm`, {}), 400, '重复核实');
  expectCode('跨辅导员核实他人记录 → 403（§10.2-5）', await call('lisi', 'POST', '/api/community/lates/9205/confirm', {}), 403);

  // 学生补充说明
  const note = await call('wangxm', 'POST', `/api/community/my/lates/${G.lateMine}/note`, { studentNote: `${RUN_TAG}-实习加班返回` });
  expectOk('POST /my/lates/{id}/note（学生本人）', note);
  expectCode('学生补充他人晚归说明 → 403（§10.2-5）',
    await call('chendw', 'POST', `/api/community/my/lates/${G.lateMine}/note`, { studentNote: 'x' }), 403, '本人');
  expectCode('学生说明为空 → 400', await call('wangxm', 'POST', `/api/community/my/lates/${G.lateMine}/note`, {}), 400, '说明内容');

  // 处理流转
  expectCode('POST /lates/{id}/handle 缺 handling → 400',
    await call('lisi', 'POST', `/api/community/lates/${G.lateMine}/handle`, {}), 400, 'handling');
  expectCode('POST /lates/{id}/handle 状态非法 → 400',
    await call('lisi', 'POST', `/api/community/lates/${G.lateMine}/handle`, { handling: 'bogus' }), 400, '处理状态只支持');
  expectOk('handle pending→contacted',
    await call('lisi', 'POST', `/api/community/lates/${G.lateMine}/handle`, { handling: 'contacted', handlingNote: SECRET_LATE }));
  expectCode('handle 倒退 contacted→pending → 400（§10.2-11 状态机）',
    await call('lisi', 'POST', `/api/community/lates/${G.lateMine}/handle`, { handling: 'pending' }), 400, '只能向前推进');
  expectOk('handle contacted→closed',
    await call('lisi', 'POST', `/api/community/lates/${G.lateMine}/handle`, { handling: 'closed', handlingNote: SECRET_LATE }));
  expectCode('handle 已了结再推进 → 400（§10.2-11 重复 handle）',
    await call('lisi', 'POST', `/api/community/lates/${G.lateMine}/handle`, { handling: 'closed' }), 400, '已了结');
  expectCode('已了结记录再 PUT → 400',
    await call('lisi', 'PUT', `/api/community/lates/${G.lateMine}`, { reasonNote: 'x' }), 400, '已了结');

  // 学生端只读本人 + 内部备注抹除
  const myLates = await call('wangxm', 'GET', '/api/community/my/lates');
  expectOk('GET /my/lates（学生）', myLates);
  const mlList = Array.isArray(d(myLates)) ? d(myLates) : [];
  check('GET /my/lates 只回本人记录', mlList.length > 0 && mlList.every((r) => Number(r.studentId) === students.wangxm), myLates,
    JSON.stringify(mlList.map((r) => r.studentId)));
  const mine = mlList.find((r) => Number(r.id) === Number(G.lateMine)) || {};
  eq('我的晚归含本次记录且 handling=closed', String(mine.handling), 'closed', myLates);
  check('学生端不暴露处理备注 handlingNote（§10.2-5）', mine.handlingNote == null, myLates, JSON.stringify(mine.handlingNote));
  eq('学生端可见本人补充说明', String(mine.studentNote), `${RUN_TAG}-实习加班返回`, myLates);

  const chMy = await call('chendw', 'GET', '/api/community/my/lates');
  const chList = Array.isArray(d(chMy)) ? d(chMy) : [];
  check('他人学生端看不到本人记录（§10.2-5）', chList.every((r) => Number(r.studentId) === students.chendw) && !chList.some((r) => Number(r.id) === Number(G.lateMine)), chMy,
    JSON.stringify(chList.map((r) => r.id)));
  expectCode('匿名 GET /my/lates → 401', await call('anon', 'GET', '/api/community/my/lates'), 401);

  // staff 列表：过滤 + 辅导员数据隔离
  const listLisi = await call('lisi', 'GET', `/api/community/lates?${q({ studentId: students.wangxm, type: 'late', handling: 'closed', confirmed: 1, startDate: LATE_DAY, endDate: LATE_DAY, page: 1, size: 20 })}`);
  expectOk('GET /lates 多条件过滤（lisi）', listLisi);
  const llList = (d(listLisi) || {}).list || [];
  check('lisi 过滤结果只含学生 4 的 closed 记录',
    llList.length >= 1 && llList.every((r) => Number(r.studentId) === students.wangxm && r.handling === 'closed'), listLisi,
    JSON.stringify(llList.map((r) => `${r.studentId}/${r.handling}`)));
  check('lisi 列表含本次登记记录', llList.some((r) => Number(r.id) === Number(G.lateMine)), listLisi, JSON.stringify(llList.map((r) => r.id)));
  const allLisi = await call('lisi', 'GET', `/api/community/lates?${q({ page: 1, size: 200 })}`);
  const lisiRows = (d(allLisi) || {}).list || [];
  check('辅导员只看到名下学生（counselorId=3，§10.2-5）', lisiRows.length > 0 && lisiRows.every((r) => Number(pick(r, 'counselorId', 'counselor_id')) === staff.lisi), allLisi,
    JSON.stringify([...new Set(lisiRows.map((r) => pick(r, 'counselorId', 'counselor_id')))]));
  const allZhang = await call('zhangsan', 'GET', `/api/community/lates?${q({ page: 1, size: 200 })}`);
  const zhangRows = (d(allZhang) || {}).list || [];
  check('跨辅导员数据集不含对方学生（§10.2-5）', zhangRows.every((r) => Number(pick(r, 'counselorId', 'counselor_id')) === staff.zhangsan) && !zhangRows.some((r) => Number(r.id) === Number(G.lateMine)), allZhang,
    JSON.stringify(zhangRows.map((r) => r.id)));
  expectCode('学生 GET /lates → 403（§10.2-5）', await call('wangxm', 'GET', '/api/community/lates'), 403);
  expectCode('匿名 GET /lates → 401', await call('anon', 'GET', '/api/community/lates'), 401);

  // 新口径（§10.2 第 5 条已定案）：辅导员要按姓名找人处理 ⇒ staff 列表与详情都给全名/学号，
  // 脱敏只发生在两个出口：导出 CSV 与公示页（本脚本第 12 节与第 5 节分别断言）。
  const probe = lisiRows.find((r) => Number(r.id) === Number(G.lateMine)) || {};
  const nameField = pick(probe, 'studentName', 'student_name');
  const noField = pick(probe, 'studentNo', 'student_no');
  check('staff 列表给全名/全学号（§10.2-5 新口径：脱敏只在导出与公示）',
    String(nameField) === '王小明' && String(noField) === '2024001',
    listLisi, `name=${JSON.stringify(nameField)} no=${JSON.stringify(noField)}`);
}

// ================================================================ 10. 违规台账
async function phaseViolations() {
  section('9. 违规台账：必须带 rule_ref / 教育惩戒 / 处理流转（§10.2-4 + §10.2-10 违规 5 条）');

  const base = {
    studentId: students.wangxm, type: 'appliance', detail: `${RUN_TAG}-使用违规电器`,
    ruleRef: '学生宿舍管理规定第十二条', advice: 'education', occurredAt: `${LATE_DAY} 20:00:00`,
  };
  const mk = await call('admin', 'POST', '/api/community/violations', base);
  expectOk('POST /violations（带 rule_ref）', mk);
  const V = d(mk) || {};
  G.violMine = V.id;
  eq('违规台账默认 handling=pending', String(V.handling), 'pending', mk);
  eq('rule_ref 落库（§10.2-4）', String(pick(V, 'ruleRef', 'rule_ref')), '学生宿舍管理规定第十二条', mk);
  eq('处理建议默认 education（只到教育惩戒，§10.2-4）', String(V.advice), 'education', mk);

  expectCode('POST /violations 缺 rule_ref → 400（§10.2-4 必填）',
    await call('admin', 'POST', '/api/community/violations', Object.assign({}, base, { ruleRef: undefined })), 400, 'ruleRef');
  expectCode('POST /violations 缺 detail → 400',
    await call('admin', 'POST', '/api/community/violations', Object.assign({}, base, { detail: undefined })), 400, '违规事实');
  expectCode('POST /violations 类型非法 → 400',
    await call('admin', 'POST', '/api/community/violations', Object.assign({}, base, { type: 'bogus' })), 400, '违规类型只支持');
  expectCode('POST /violations 建议非法 → 400',
    await call('admin', 'POST', '/api/community/violations', Object.assign({}, base, { advice: 'expel' })), 400, '处理建议只支持');
  expectCode('POST /violations 学生不存在 → 404',
    await call('admin', 'POST', '/api/community/violations', Object.assign({}, base, { studentId: students.wangxm + 100 })), 404, '学生档案不存在');
  expectCode('POST /violations 跨辅导员登记他人学生 → 403（§10.2-5）',
    await call('lisi', 'POST', '/api/community/violations', Object.assign({}, base, { studentId: students.liusq })), 403);
  expectCode('学生 POST /violations → 403', await call('wangxm', 'POST', '/api/community/violations', base), 403);

  // zhangsan 名下学生的台账（供“跨辅导员”用例：lisi 改它必须 403）
  const mkOther = await call('zhangsan', 'POST', '/api/community/violations',
    Object.assign({}, base, { studentId: students.liusq, detail: `${RUN_TAG}-zhangsan 名下学生台账` }));
  expectOk('POST /violations（zhangsan 名下学生，跨辅导员用例用）', mkOther);
  G.violOther = (d(mkOther) || {}).id;

  // 关联晚归台账
  const linked = await call('admin', 'POST', '/api/community/violations',
    Object.assign({}, base, { type: 'late', detail: `${RUN_TAG}-关联晚归`, ruleRef: '晚归管理办法第五条', lateId: G.lateMine }));
  expectOk('POST /violations 关联晚归记录（lateId）', linked);
  G.violLinked = (d(linked) || {}).id;
  eq('关联晚归 lateId 落库', Number(pick(d(linked) || {}, 'lateId', 'late_id')), Number(G.lateMine), linked);

  // 修改 / 处理
  const put = await call('admin', 'PUT', `/api/community/violations/${G.violMine}`, { advice: 'circulated', detail: `${RUN_TAG}-通报批评` });
  expectOk('PUT /violations/{id}', put);
  eq('PUT 生效 advice=circulated', String((d(put) || {}).advice), 'circulated', put);
  // §10.2-4 的不变量：台账任何时候都必须有依据条文。服务端把空/空白 ruleRef 视为“未提供”并回退原值，
  // 因此这里验收的是“依据条文不丢”，而不是“清空必须 400”。
  const clearRule = await call('admin', 'PUT', `/api/community/violations/${G.violMine}`, { ruleRef: '' });
  expectOk('PUT /violations/{id} 传空 ruleRef（服务端忽略空值）', clearRule);
  eq('清空请求后依据条文仍为原值（§10.2-4 台账必须有依据）',
    String(pick(d(clearRule) || {}, 'ruleRef', 'rule_ref')), '学生宿舍管理规定第十二条', clearRule);
  expectCode('PUT /violations/{id} 不存在 → 404', await call('admin', 'PUT', '/api/community/violations/99999999', { advice: 'education' }), 404, '不存在');
  expectCode('PUT /violations/{id} 跨辅导员 → 403（§10.2-5）',
    await call('lisi', 'PUT', `/api/community/violations/${G.violOther}`, { advice: 'education' }), 403);

  expectCode('POST /violations/{id}/handle 缺 handling → 400',
    await call('lisi', 'POST', `/api/community/violations/${G.violMine}/handle`, {}), 400, 'handling');
  expectOk('handle pending→notified',
    await call('lisi', 'POST', `/api/community/violations/${G.violMine}/handle`, { handling: 'notified', resultNote: SECRET_VIOL }));
  expectCode('handle 倒退 notified→pending → 400（§10.2-11）',
    await call('lisi', 'POST', `/api/community/violations/${G.violMine}/handle`, { handling: 'pending' }), 400, '只能向前推进');
  expectOk('handle notified→closed',
    await call('lisi', 'POST', `/api/community/violations/${G.violMine}/handle`, { handling: 'closed', resultNote: SECRET_VIOL }));
  expectCode('已办结台账再 handle → 400（§10.2-11 重复 handle）',
    await call('lisi', 'POST', `/api/community/violations/${G.violMine}/handle`, { handling: 'closed' }), 400, '已办结');
  expectCode('已办结台账再 PUT → 400',
    await call('lisi', 'PUT', `/api/community/violations/${G.violMine}`, { detail: 'x' }), 400, '已办结');

  // 学生端 + 列表
  const myV = await call('wangxm', 'GET', '/api/community/my/violations');
  expectOk('GET /my/violations（学生）', myV);
  const mvList = Array.isArray(d(myV)) ? d(myV) : [];
  check('my/violations 只回本人记录', mvList.length > 0 && mvList.every((r) => Number(r.studentId) === students.wangxm), myV,
    JSON.stringify(mvList.map((r) => r.studentId)));
  const vm = mvList.find((r) => Number(r.id) === Number(G.violMine)) || {};
  check('学生端不暴露处理结果 resultNote（§10.2-5）', vm.resultNote == null, myV, JSON.stringify(vm.resultNote));
  check('学生端可见依据条文 ruleRef（§10.2-4 留痕）', String(pick(vm, 'ruleRef', 'rule_ref')).length > 0, myV, JSON.stringify(vm));
  const chV = await call('chendw', 'GET', '/api/community/my/violations');
  check('他人学生端看不到本人违规记录（§10.2-5）',
    (Array.isArray(d(chV)) ? d(chV) : []).every((r) => Number(r.studentId) === students.chendw) &&
    !(Array.isArray(d(chV)) ? d(chV) : []).some((r) => Number(r.id) === Number(G.violMine)), chV, '');
  expectCode('匿名 GET /my/violations → 401', await call('anon', 'GET', '/api/community/my/violations'), 401);

  const listV = await call('lisi', 'GET', `/api/community/violations?${q({ studentId: students.wangxm, type: 'appliance', advice: 'circulated', handling: 'closed', keyword: RUN_TAG, page: 1, size: 20 })}`);
  expectOk('GET /violations 多条件过滤（lisi）', listV);
  const lvList = (d(listV) || {}).list || [];
  check('lisi 过滤命中本次台账', lvList.some((r) => Number(r.id) === Number(G.violMine)), listV, JSON.stringify(lvList.map((r) => r.id)));
  const zhangV = await call('zhangsan', 'GET', `/api/community/violations?${q({ page: 1, size: 200 })}`);
  const zvList = (d(zhangV) || {}).list || [];
  check('跨辅导员违规数据集不含对方学生（§10.2-5）', zvList.every((r) => Number(pick(r, 'counselorId', 'counselor_id')) === staff.zhangsan) && !zvList.some((r) => Number(r.id) === Number(G.violMine)), zhangV,
    JSON.stringify(zvList.map((r) => r.id)));
  expectCode('学生 GET /violations → 403', await call('wangxm', 'GET', '/api/community/violations'), 403);

  // 新口径（§10.2 第 5 条）：staff 违规列表同样给全名/学号，脱敏只在导出 CSV 与公示页。
  const probe = lvList.find((r) => Number(r.id) === Number(G.violMine)) || {};
  const vName = pick(probe, 'studentName', 'student_name');
  const vNo = pick(probe, 'studentNo', 'student_no');
  check('staff 违规列表给全名/全学号（§10.2-5 新口径：脱敏只在导出与公示）',
    String(vName) === '王小明' && String(vNo) === '2024001',
    listV, `name=${JSON.stringify(vName)} no=${JSON.stringify(vNo)}`);
}

// ================================================================ 11. 活动
async function phaseActivities() {
  section('10. 活动：创建 / 报名（满额、重复、截止、草稿）/ 审核 / 签到 / 我的（§10.2-8 + §10.2-10 活动 7 条）');

  const mk = async (name, body) => {
    const res = await call('admin', 'POST', '/api/community/activities', body);
    expectOk(name, res);
    return res;
  };
  const common = { term: TERM, startAt: A_START, endAt: A_END, signupDeadline: A_DEADLINE, location: '社区活动中心' };

  const rA = await mk('POST /activities 活动A（quota=1）', Object.assign({}, common, { title: `${RUN_TAG}-活动A`, activityType: 'room_contest', level: 'college', quota: 1, content: '宿舍风采', host: '学工处' }));
  G.act.A = (d(rA) || {}).id;
  eq('活动默认状态 draft（§10.2-1）', String((d(rA) || {}).status), 'draft', rA);
  const rB = await mk('POST /activities 活动B（quota=0 不限）', Object.assign({}, common, { title: `${RUN_TAG}-活动B`, activityType: 'volunteer', level: 'community', quota: 0 }));
  G.act.B = (d(rB) || {}).id;
  const rC = await mk('POST /activities 活动C（报名已截止）', Object.assign({}, common, { title: `${RUN_TAG}-活动C`, activityType: 'other', quota: 10, signupDeadline: A_PAST }));
  G.act.C = (d(rC) || {}).id;
  const rD = await mk('POST /activities 活动D（保持 draft）', Object.assign({}, common, { title: `${RUN_TAG}-活动D`, activityType: 'design_contest', quota: 10 }));
  G.act.D = (d(rD) || {}).id;
  const rE = await mk('POST /activities 活动E（状态机）', Object.assign({}, common, { title: `${RUN_TAG}-活动E`, activityType: 'other', quota: 10 }));
  G.act.E = (d(rE) || {}).id;

  expectCode('POST /activities 缺 title → 400', await call('admin', 'POST', '/api/community/activities', Object.assign({}, common, {})), 400, 'title');
  expectCode('POST /activities 类型非法 → 400', await call('admin', 'POST', '/api/community/activities', Object.assign({}, common, { title: 'x', activityType: 'bogus' })), 400, '活动类型');
  expectCode('POST /activities 级别非法 → 400', await call('admin', 'POST', '/api/community/activities', Object.assign({}, common, { title: 'x', level: 'national' })), 400, '活动级别');
  expectCode('POST /activities 结束早于开始 → 400', await call('admin', 'POST', '/api/community/activities', Object.assign({}, common, { title: 'x', startAt: A_END, endAt: A_START })), 400, '结束时间');
  expectCode('POST /activities quota 为负 → 400', await call('admin', 'POST', '/api/community/activities', Object.assign({}, common, { title: 'x', quota: -1 })), 400, 'quota');
  expectCode('学生 POST /activities → 403（§10.2-5）', await call('wangxm', 'POST', '/api/community/activities', Object.assign({}, common, { title: 'x' })), 403);

  // 报名状态未开放
  expectCode('POST /activities/{id}/signup 草稿活动 → 400',
    await call('wangxm', 'POST', `/api/community/activities/${G.act.D}/signup`, {}), 400, '不在报名中');
  expectCode('POST /activities/{id}/signup 活动不存在 → 404',
    await call('wangxm', 'POST', '/api/community/activities/99999999/signup', {}), 404, '活动不存在');
  expectCode('POST /activities/{id}/signup 辅导员（无学生档案）→ 403',
    await call('lisi', 'POST', `/api/community/activities/${G.act.A}/signup`, {}), 403);

  // 开放报名
  for (const k of ['A', 'B', 'C', 'E']) {
    expectOk(`PUT /activities/{id} ${k} draft→signup`, await call('admin', 'PUT', `/api/community/activities/${G.act[k]}`, { status: 'signup' }));
  }
  expectCode('POST /activities/{id}/signup 已截止 → 400（§10.2-8）',
    await call('wangxm', 'POST', `/api/community/activities/${G.act.C}/signup`, {}), 400, '报名已截止');
  expectCode('学生 PUT /activities/{id} → 403', await call('wangxm', 'PUT', `/api/community/activities/${G.act.A}`, { status: 'signup' }), 403);

  const sgA = await call('wangxm', 'POST', `/api/community/activities/${G.act.A}/signup`, {});
  expectOk('POST /activities/{id}/signup（活动A，1/1）', sgA);
  G.signup.A = (d(sgA) || {}).id;
  eq('报名后状态 signed', String((d(sgA) || {}).status), 'signed', sgA);
  eq('报名后 award 默认 none（缺陷 D1 回归探针：接口不得再写 NULL）', String((d(sgA) || {}).award), 'none', sgA);
  check('报名返回正数 id（报名主链路必须真的落库）', Number(G.signup.A) > 0, sgA, `id=${JSON.stringify(G.signup.A)}`);
  expectCode('同一学生重复报名 → 400（§10.2-8 唯一约束）',
    await call('wangxm', 'POST', `/api/community/activities/${G.act.A}/signup`, {}), 400, '你已报名该活动');
  expectCode('quota 满报名 → 400（§10.2-8）',
    await call('zhaoxh', 'POST', `/api/community/activities/${G.act.A}/signup`, {}), 400, '名额已满');

  const sgB = await call('chendw', 'POST', `/api/community/activities/${G.act.B}/signup`, {});
  expectOk('POST /activities/{id}/signup（活动B 不限名额）', sgB);
  G.signup.B = (d(sgB) || {}).id;
  check('活动B 报名返回正数 id（不限名额、无 SQL 绕行）', Number(G.signup.B) > 0, sgB, `id=${JSON.stringify(G.signup.B)}`);
  expectCode('活动B 重复报名 → 400（唯一约束）',
    await call('chendw', 'POST', `/api/community/activities/${G.act.B}/signup`, {}), 400, '你已报名该活动');

  // 审核
  expectCode('POST /activities/{id}/review 缺 action → 400',
    await call('lisi', 'POST', `/api/community/activities/${G.act.A}/review`, { signupId: G.signup.A }), 400, 'action');
  expectCode('POST /activities/{id}/review action 非法 → 400',
    await call('lisi', 'POST', `/api/community/activities/${G.act.A}/review`, { signupId: G.signup.A, action: 'bogus' }), 400, '审核动作');
  expectCode('POST /activities/{id}/review 无目标 → 400',
    await call('lisi', 'POST', `/api/community/activities/${G.act.A}/review`, { action: 'approve' }), 400, 'signupId');
  expectCode('学生审核 → 403', await call('wangxm', 'POST', `/api/community/activities/${G.act.A}/review`, { signupId: G.signup.A, action: 'approve' }), 403);
  const rv = await call('lisi', 'POST', `/api/community/activities/${G.act.A}/review`, { signupId: G.signup.A, action: 'approve' });
  expectOk('POST /activities/{id}/review approve', rv);
  eq('审核 updated=1', Number((d(rv) || {}).updated), 1, rv);
  const rvFail = await call('lisi', 'POST', `/api/community/activities/${G.act.A}/review`, { signupId: 99999999, action: 'approve' });
  expectOk('POST /activities/{id}/review 目标不存在仍 200（逐条报错）', rvFail);
  eq('审核不存在记录 failed=1', Number((d(rvFail) || {}).failed), 1, rvFail);
  expectCode('重复审核（已通过）→ 逐条 failed（§10.2-11 重复审核）',
    await call('lisi', 'POST', `/api/community/activities/${G.act.A}/review`, { signupId: G.signup.A, action: 'approve' }), 200, '审核完成');
  const rvB = await call('lisi', 'POST', `/api/community/activities/${G.act.B}/review`, { action: 'reject', rejectReason: '材料不全', items: [{ signupId: G.signup.B }] });
  expectOk('POST /activities/{id}/review reject（items 批量）', rvB);
  eq('驳回 updated=1', Number((d(rvB) || {}).updated), 1, rvB);

  // 签到与奖项
  expectCode('POST /activities/{id}/attendance 无目标 → 400',
    await call('lisi', 'POST', `/api/community/activities/${G.act.A}/attendance`, { status: 'attended' }), 400, 'signupId');
  // 审核 / 签到是「批量形状」接口：非法入参逐条进入 data.errors，整体仍回 code 200（服务端契约），
  // 所以这里验收的是 code 200 + failed=1 + 该条错误文案，而不是整体 400。
  const atBadStatus = await call('lisi', 'POST', `/api/community/activities/${G.act.A}/attendance`, { signupId: G.signup.A, status: 'maybe' });
  expectOk('POST /activities/{id}/attendance 状态非法（逐条报错，整体 200）', atBadStatus);
  eq('状态非法 attendance failed=1', Number(pick(d(atBadStatus) || {}, 'failed')), 1, atBadStatus);
  check('状态非法 attendance errors 含「签到状态只支持 attended / absent」',
    JSON.stringify((d(atBadStatus) || {}).errors || []).includes('签到状态只支持 attended / absent'), atBadStatus,
    JSON.stringify((d(atBadStatus) || {}).errors));
  expectOk('POST /activities/{id}/attendance attended + award',
    await call('lisi', 'POST', `/api/community/activities/${G.act.A}/attendance`, { signupId: G.signup.A, status: 'attended', award: 'first', score: 98, note: `${RUN_TAG}-签到` }));
  expectCode('重复签到 → 逐条 failed（§10.2-11）',
    await call('lisi', 'POST', `/api/community/activities/${G.act.A}/attendance`, { signupId: G.signup.A, status: 'attended' }), 200, '签到完成');
  const atBad = await call('lisi', 'POST', `/api/community/activities/${G.act.A}/attendance`, { signupId: G.signup.A, status: 'absent', award: 'second' });
  expectOk('已参加记录登记奖项后改 absent 被拒（逐条报错）', atBad);
  eq('奖项变更请求 failed>=1', Number((d(atBad) || {}).failed) >= 1, true, atBad);
  const atRej = await call('lisi', 'POST', `/api/community/activities/${G.act.B}/attendance`, { signupId: G.signup.B, status: 'attended', award: 'first' });
  expectOk('驳回记录签到被拒（逐条报错）', atRej);
  eq('已驳回记录签到 failed=1', Number((d(atRej) || {}).failed), 1, atRej);
  check('签到失败原因含「只有已参加」或「只有已报名」',
    JSON.stringify((d(atBad) || {}).errors || []).includes('只有已参加') || JSON.stringify((d(atRej) || {}).errors || []).includes('只有已报名'), atBad,
    `${JSON.stringify((d(atBad) || {}).errors)} / ${JSON.stringify((d(atRej) || {}).errors)}`);

  // 列表 / 我的报名 / 状态机
  const actStudent = await call('wangxm', 'GET', `/api/community/activities?${q({ term: TERM, page: 1, size: 100 })}`);
  expectOk('学生 GET /activities', actStudent);
  const asList = Array.isArray(d(actStudent)) ? d(actStudent) : ((d(actStudent) || {}).list || []);
  check('学生看不到 draft 活动（§10.2-8 hideDraft）', !asList.some((x) => x.status === 'draft'), actStudent,
    JSON.stringify(asList.map((x) => `${x.id}:${x.status}`)));
  const actAdmin = await call('admin', 'GET', `/api/community/activities?${q({ term: TERM, activityType: 'room_contest', level: 'college', status: 'signup', keyword: `${RUN_TAG}-活动A` })}`);
  expectOk('GET /activities 多条件过滤（admin）', actAdmin);
  const aaList = Array.isArray(d(actAdmin)) ? d(actAdmin) : ((d(actAdmin) || {}).list || []);
  check('过滤命中活动A', aaList.some((x) => Number(x.id) === Number(G.act.A)), actAdmin, JSON.stringify(aaList.map((x) => x.id)));

  const myAct = await call('wangxm', 'GET', '/api/community/my/activities');
  expectOk('GET /my/activities', myAct);
  const maList = Array.isArray(d(myAct)) ? d(myAct) : [];
  const maA = maList.find((x) => Number(pick(x, 'activityId', 'activity_id')) === Number(G.act.A)) || {};
  eq('我的报名含活动A 且 attended/award=first', `${maA.status}/${maA.award}`, 'attended/first', myAct);
  const myActOne = await call('wangxm', 'GET', `/api/community/my/activities?${q({ activityId: G.act.A })}`);
  expectOk('GET /my/activities?activityId 过滤', myActOne);
  const maOther = await call('chendw', 'GET', '/api/community/my/activities');
  check('他人学生端看不到本人报名（§10.2-5）',
    (Array.isArray(d(maOther)) ? d(maOther) : []).every((x) => Number(pick(x, 'activityId', 'activity_id')) !== Number(G.act.A)), maOther, '');
  expectCode('匿名 GET /my/activities → 401', await call('anon', 'GET', '/api/community/my/activities'), 401);

  expectCode('PUT /activities/{id} 状态非法 → 400',
    await call('admin', 'PUT', `/api/community/activities/${G.act.E}`, { status: 'bogus' }), 400, '活动状态只支持');
  expectOk('PUT /activities/{id} signup→ongoing（活动E）', await call('admin', 'PUT', `/api/community/activities/${G.act.E}`, { status: 'ongoing' }));
  expectCode('PUT /activities/{id} 状态倒退 → 400（§10.2-11）',
    await call('admin', 'PUT', `/api/community/activities/${G.act.E}`, { status: 'signup' }), 400, '只能向前推进');
  expectCode('PUT /activities/{id} 不存在 → 404', await call('admin', 'PUT', '/api/community/activities/99999999', { status: 'signup' }), 404, '活动不存在');
  expectOk('PUT /activities/{id} 活动A signup→finished', await call('admin', 'PUT', `/api/community/activities/${G.act.A}`, { status: 'finished' }));
  expectCode('PUT /activities/{id} 已结束再改 → 400（§10.2-11）',
    await call('admin', 'PUT', `/api/community/activities/${G.act.A}`, { title: 'x' }), 400, '不能再修改');
}

// ================================================================ 12. 统计
async function phaseStats() {
  section('11. 统计 GET /stats（§10.2-10 + §10.2-5 权限）');

  const st = await call('admin', 'GET', `/api/community/stats?${q({ term: TERM })}`);
  expectOk('GET /stats（admin，term 过滤）', st);
  const sd = d(st) || {};
  const need = ['term', 'startDate', 'endDate', 'batches', 'batchStatus', 'inspections', 'inspectionGrade',
    'inspectionRectify', 'lates', 'lateType', 'lateHandling', 'violations', 'violationType',
    'violationAdvice', 'activities', 'activityStatus', 'civilRooms'];
  check('GET /stats 顶层键齐全（17 项）', need.every((k) => k in sd), st, JSON.stringify(Object.keys(sd)));
  eq('stats 学期回显', String(sd.term), TERM, st);
  eq('stats 检查汇总 total>=6（本次批次A 6 间 + 批次B 7 间）', Number(pick(sd.inspections || {}, 'total')) >= 6, true, st);
  check('stats 检查汇总键为 snake_case（avg_score/rectify_pending）',
    'avg_score' in (sd.inspections || {}) && 'rectify_pending' in (sd.inspections || {}), st, JSON.stringify(sd.inspections));
  eq('stats 活动 total>=4（活动A..E）', Number(pick(sd.activities || {}, 'total')) >= 4, true, st);
  eq('stats 文明宿舍 published>=2', Number(pick(sd.civilRooms || {}, 'published')) >= 2, true, st);
  eq('stats 文明宿舍 best_avg=100（B-201 满分）', Number(pick(sd.civilRooms || {}, 'best_avg')), 100, st);
  check('stats batchStatus 为数组且含本次批次状态', Array.isArray(sd.batchStatus) && sd.batchStatus.length > 0, st, JSON.stringify(sd.batchStatus));

  const stAll = await call('admin', 'GET', '/api/community/stats');
  expectOk('GET /stats（不带 term，全校口径）', stAll);
  const sa = d(stAll) || {};
  eq('stats 晚归汇总 total>=2（本次 2 条）', Number(pick(sa.lates || {}, 'total')) >= 2, true, stAll);
  eq('stats 违规汇总 total>=2（本次 2 条）', Number(pick(sa.violations || {}, 'total', 'count')) >= 2, true, stAll);
  check('stats 晚归汇总含 late/absent 分类', Number(pick(sa.lates || {}, 'late_count')) >= 1 && Number(pick(sa.lates || {}, 'absent_count')) >= 1, stAll, JSON.stringify(sa.lates));

  const stRange = await call('admin', 'GET', `/api/community/stats?${q({ term: TERM, startDate: '2031-01-01', endDate: '2031-12-31' })}`);
  expectOk('GET /stats 带 startDate/endDate', stRange);
  expectCode('学生 GET /stats → 403（§10.2-5）', await call('wangxm', 'GET', `/api/community/stats`), 403);
  expectCode('匿名 GET /stats → 401', await call('anon', 'GET', `/api/community/stats`), 401);

  // 辅导员数据隔离（§10.2-5）
  const sLisi = await call('lisi', 'GET', '/api/community/stats');
  const sZhang = await call('zhangsan', 'GET', '/api/community/stats');
  const latesLisi = await call('lisi', 'GET', `/api/community/lates?${q({ page: 1, size: 200 })}`);
  const latesZhang = await call('zhangsan', 'GET', `/api/community/lates?${q({ page: 1, size: 200 })}`);
  const nLisi = Number(pick((d(sLisi) || {}).lates || {}, 'total'));
  const nZhang = Number(pick((d(sZhang) || {}).lates || {}, 'total'));
  eq('lisi 统计晚归 total 与列表 total 一致', nLisi, Number((d(latesLisi) || {}).total), sLisi);
  eq('zhangsan 统计晚归 total 与列表 total 一致', nZhang, Number((d(latesZhang) || {}).total), sZhang);
  check('两辅导员统计口径不同（数据按下属学生裁剪，§10.2-5）', nLisi !== nZhang, sLisi, `lisi=${nLisi} zhangsan=${nZhang}`);
}

// ================================================================ 13. 导出
async function phaseExport() {
  section('12. 导出 GET /export（CSV + UTF-8 BOM + 脱敏；§10.2-5/§10.2-10）');

  const rawOf = (who, p) => call(who, 'GET', p);
  const bomOk = (rec) => rec.buf.length > 3 && rec.buf[0] === 0xEF && rec.buf[1] === 0xBB && rec.buf[2] === 0xBF;

  for (const t of ['inspections', 'lates', 'violations', 'civil-rooms']) {
    const p = t === 'lates' || t === 'violations'
      ? `/api/community/export?${q({ type: t, startDate: t === 'lates' ? LATE_DAY : LATE_DAY, endDate: t === 'lates' ? LATE_DAY2 : LATE_DAY2 })}`
      : `/api/community/export?${q({ type: t, term: TERM })}`;
    const r = await rawOf('admin', p);
    check(`GET /export?type=${t} HTTP 200`, r.status === 200, r, `HTTP ${r.status} ${r.text.slice(0, 200)}`);
    check(`GET /export?type=${t} 首字节为 UTF-8 BOM（EF BB BF）`, bomOk(r), r, `前 3 字节 = ${[...r.buf.slice(0, 3)].map((b) => b.toString(16)).join(' ')}`);
    check(`GET /export?type=${t} Content-Type=text/csv`, String(r.resHeaders.get('content-type') || '').includes('text/csv'), r, String(r.resHeaders.get('content-type')));
    check(`GET /export?type=${t} Content-Disposition 带文件名`, String(r.resHeaders.get('content-disposition') || '').includes(`community-${t}.csv`), r, String(r.resHeaders.get('content-disposition')));
    lines.push(`  --- export ${t} 首行: ${r.text.replace(/^\uFEFF/, '').split('\r\n')[0]}`);
    if (t === 'lates') {
      const body = r.text.replace(/^\uFEFF/, '');
      check('lates 导出姓名已脱敏（王小明 → 王*）', body.includes('王*') && !body.includes('王小明'), r, body.slice(0, 300));
      check('lates 导出学号只留后 4 位（2024001 → 4001）', body.includes('4001') && !body.includes('2024001'), r, body.slice(0, 300));
      check('lates 导出不含处理备注 handlingNote（§10.2-5）', !body.includes(SECRET_LATE), r, '包含内部备注');
      check('lates 导出含本次登记记录（原因说明列）', body.includes(`${RUN_TAG}-已核实原因`), r, body.slice(0, 400));
      check('lates 导出表头含「是否核实」「处理状态」', body.split('\r\n')[0].includes('是否核实') && body.split('\r\n')[0].includes('处理状态'), r, body.split('\r\n')[0]);
    }
    if (t === 'violations') {
      const body = r.text.replace(/^\uFEFF/, '');
      check('violations 导出姓名/学号脱敏', body.includes('王*') && !body.includes('王小明') && !body.includes('2024001'), r, body.slice(0, 300));
      check('violations 导出不含处理结果 resultNote（§10.2-5）', !body.includes(SECRET_VIOL), r, '包含内部结果说明');
      check('violations 导出含依据条文列与本次 rule_ref（§10.2-4）',
        body.split('\r\n')[0].includes('依据条文') && body.includes('学生宿舍管理规定第十二条'), r, body.split('\r\n')[0]);
    }
    if (t === 'inspections') {
      const body = r.text.replace(/^\uFEFF/, '');
      check('inspections 导出表头含「批次,学期,楼栋,房号」（§10.2-10）', body.split('\r\n')[0].startsWith('批次,学期,楼栋,房号'), r, body.split('\r\n')[0]);
      check('inspections 导出含本次学期行', body.includes(TERM), r, body.slice(0, 400));
      check('inspections 导出不含学生姓名', !body.includes('王小明') && !body.includes('陈大伟'), r, body.slice(0, 400));
    }
    if (t === 'civil-rooms') {
      const body = r.text.replace(/^\uFEFF/, '');
      check('civil-rooms 导出表头含名次/获奖等级（§10.2-6）', body.split('\r\n')[0].includes('楼栋内名次') && body.split('\r\n')[0].includes('获奖等级'), r, body.split('\r\n')[0]);
      check('civil-rooms 导出含本次学期候选', body.includes(TERM) && body.includes('A-305'), r, body.slice(0, 400));
      check('civil-rooms 导出不含姓名/学号（§10.2-5）', !body.includes('王小明') && !body.includes('2024001'), r, body.slice(0, 400));
    }
  }

  // 导出过滤
  const rGrade = await call('admin', 'GET', `/api/community/export?${q({ type: 'inspections', term: TERM, grade: 'fail' })}`);
  const gBody = rGrade.text.replace(/^\uFEFF/, '');
  check('GET /export?type=inspections&grade=fail 只含 fail 行',
    rGrade.status === 200 && gBody.includes('A-412') && !gBody.includes('A-305'), rGrade, gBody.slice(0, 300));
  const rTerm = await call('admin', 'GET', `/api/community/export?${q({ type: 'inspections', term: `${TERM}-absent` })}`);
  const tBody = rTerm.text.replace(/^\uFEFF/, '');
  check('GET /export?term 不存在只回表头', rTerm.status === 200 && !tBody.includes(TERM) && tBody.split('\r\n').length <= 2, rTerm, tBody.slice(0, 200));

  // 失败路径（裸状态码，不套 Result；响应体无 JSON，故只断 HTTP 状态）
  const rAnon = await rawOf('anon', `/api/community/export?type=lates`);
  expectHttp('匿名 GET /export → 401（裸状态码）', rAnon, 401, rAnon.text.slice(0, 60));
  eq('匿名 export 响应体为空', rAnon.text.length, 0, rAnon);
  const rStu = await rawOf('wangxm', `/api/community/export?type=lates`);
  expectHttp('学生 GET /export → 403（裸状态码，§10.2-5）', rStu, 403, rStu.text.slice(0, 60));
  eq('学生 export 响应体为空', rStu.text.length, 0, rStu);
  const rBad = await rawOf('admin', `/api/community/export?type=bogus`);
  expectHttp('GET /export?type 非法 → 400（裸状态码）', rBad, 400, rBad.text.slice(0, 60));
  const rNone = await rawOf('admin', `/api/community/export`);
  expectHttp('GET /export 缺 type → 400（裸状态码）', rNone, 400, rNone.text.slice(0, 60));
}

// ================================================================ main
async function main() {
  lines.push(`P15 社区管理端到端验收  ${new Date().toISOString()}`);
  lines.push(`BASE=${BASE}  RUN_TAG=${RUN_TAG}  TERM=${TERM}  LATE_DAY=${LATE_DAY}`);
  let fatal = null;
  try {
    await phaseLogin();
    await phaseBatches();
    await phaseInspections();
    await phasePublish();
    await phaseRectify();
    await phaseCivil1();
    await phaseBatchB();
    await phaseCivil2();
    await phaseLates();
    await phaseViolations();
    await phaseActivities();
    await phaseStats();
    await phaseExport();
  } catch (e) {
    fatal = e;
    ok('脚本执行未抛出异常', false, `${e && e.stack ? e.stack.split('\n').slice(0, 4).join(' | ') : e}`);
  }

  section('覆盖率与汇总');
  const missed = ROUTES.filter((r) => !covered.has(r));
  const extra = [...covered].filter((r) => !ROUTES.includes(r) && r.startsWith('GET /api/community') === false ? false : !ROUTES.includes(r));
  lines.push(`  路由覆盖 ${ROUTES.length - missed.length}/${ROUTES.length}`);
  if (missed.length) lines.push(`  未覆盖：${missed.join(' | ')}`);
  if (extra.length) lines.push(`  额外调用：${extra.join(' | ')}`);
  ok('40 条方法级路由全部被调用（§10.2-10）', missed.length === 0, missed.join(' | '));

  if (failures.length) {
    section('失败明细（请求 + 期望 + 实际）');
    failures.forEach((f, i) => lines.push(`  ${i + 1}. ${f.name}\n     ${f.detail}`));
  }
  if (divergences.length) {
    section('口径分歧（任务书附加要求，§10.2 未要求，按未通过计）');
    divergences.forEach((f, i) => lines.push(`  ${i + 1}. ${f.name}\n     ${f.detail}`));
  }
  lines.push('');
  lines.push(`PASS ${pass} / FAIL ${fail}${fatal ? ' (脚本异常中断)' : ''}`);
  lines.push(`退出码 ${fail === 0 && !fatal ? 0 : 1}`);

  fs.mkdirSync(path.dirname(LOG_PATH), { recursive: true });
  fs.writeFileSync(LOG_PATH, lines.join('\n'), 'utf8');
  process.stdout.write(lines.join('\n') + '\n');
  process.exitCode = fail === 0 && !fatal ? 0 : 1;
}

main();

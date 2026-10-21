/**
 * e2e-p17.mjs — P17「校园文化」端到端验收脚本（志愿服务时长认证 + 学生社团年审/星级评定）
 *
 * 依据：
 *   - target/p17-plan.md（唯一实现契约：§4 规则常量、§5 48 条路由与响应字段、§6 权限与 scope、§7 状态机与 CAS）
 *   - target/p17-spec.md §12.1（一手事实，带条文号）/ §12.2（实现口径与诚实清单）
 *   - sql/nest_volunteer.sql、sql/nest_club.sql（DDL 实测字段与枚举）
 *   - tools/e2e-p16.mjs（同仓库验收脚本的写法模板）
 *
 * 覆盖：
 *   - /api/volunteer 26 条路由 + /api/club 22 条路由 = 48 条，全部至少调用一次（文末覆盖矩阵逐条打勾）
 *   - 权限矩阵（学生/辅导员/管理员；辅导员只能看自己名下学生，不能审核/定星级/登记年审结果）
 *   - 状态机与 CAS 违规必拒（终态不可再改、重复审核/重复关闭/重复登记结果等）
 *   - 边界（志愿者星级 50/100/150/300/500、每学年 20 小时、项目结束 6 个月补录窗口、
 *           黑名单 6 个月到期推算、申诉 5 个工作日窗口）
 *   - 脱敏（导出 CSV 不含完整学号与联系方式；公示明细不含学号）
 *
 * 可重复运行：
 *   - 本轮创建的数据全部带 `e2e-p17` 文本标记（TAG = e2e-p17-<RUN>），挂在脚本自建的
 *     项目/社团上；演示数据（id 90001-91044 区间）一律只读，不改数据库。
 *   - 清理残留（幂等，按标记删除）：cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < target\clean-p17.sql"
 *
 * 用法：node tools\e2e-p17.mjs
 *   - 控制台输出 + 落盘 target/e2e-p17.log；全绿另存 target/e2e-p17-final.log
 *   - 存在 FAIL / FATAL 时退出码为 1
 *
 * 判成功口径：后端所有接口 HTTP 200，业务码在响应体 json.code（common/Result.java 的 success() 置 200），
 *             失败给中文 message。故断言一律看 json.code，不断言 HTTP 状态码（导出类接口除外，见 expectHttp/expectDenied）。
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASE = 'http://127.0.0.1:8080';
const LOG_PATH = path.join(__dirname, '..', 'target', 'e2e-p17.log');
const FINAL_LOG_PATH = path.join(__dirname, '..', 'target', 'e2e-p17-final.log');

const RUN = Date.now().toString(36);
const TAG = `e2e-p17-${RUN}`;   // 清理脚本按 '%e2e-p17%' 匹配，必须保留该前缀
const PASSWORD = '123456';
const OK_CODE = 200;            // common/Result.java: success() → code 200

// ---------------------------------------------------------------- 日期工具
const pad = (n) => String(n).padStart(2, '0');
const fmt = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parse = (s) => {
  const [y, m, d] = String(s).slice(0, 10).split('-').map(Number);
  return new Date(y, m - 1, d);
};
const addDays = (s, n) => {
  const d = parse(s);
  d.setDate(d.getDate() + n);
  return fmt(d);
};
// 自然月推算（与 java.time.LocalDate.plusMonths 同口径：月末夹取）
const addMonths = (s, n) => {
  const d = parse(s);
  const day = d.getDate();
  d.setDate(1);
  d.setMonth(d.getMonth() + n);
  const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
  d.setDate(Math.min(day, last));
  return fmt(d);
};
const isWeekend = (d) => d.getDay() === 0 || d.getDay() === 6;
// 从次日起算、跳过周六周日（与服务端口径一致）
const addWorkdays = (s, n) => {
  const d = parse(s);
  let left = n;
  while (left > 0) {
    d.setDate(d.getDate() + 1);
    if (!isWeekend(d)) left -= 1;
  }
  return fmt(d);
};
const dayDiff = (a, b) => Math.round((parse(b) - parse(a)) / 86400000);
const round2 = (n) => Math.round(Number(n) * 100) / 100;
const TODAY = fmt(new Date());

// 志愿者星级阈值（S21 第三十条：50/100/150/300/500 小时 → 一/二/三/四/五星级志愿者）
const STAR_THRESHOLDS = [50, 100, 150, 300, 500];
const STAR_CN = ['一', '二', '三', '四', '五'];
const starIndexByHours = (h) => {
  let idx = 0;
  for (let i = 0; i < STAR_THRESHOLDS.length; i += 1) if (Number(h) >= STAR_THRESHOLDS[i]) idx = i + 1;
  return idx;
};
const nextThreshold = (h) => {
  for (const t of STAR_THRESHOLDS) if (Number(h) < t) return t;
  return null;
};

// ---------------------------------------------------------------- 断言基础设施
const lines = [];
const failures = [];
const divergences = [];
let pass = 0;
let fail = 0;
let fatal = false;
const covered = new Set();
const coveredPhase = new Map();
const cookies = {};
let currentPhase = '(boot)';

function emit(line) {
  lines.push(line);
}

function section(title) {
  emit('');
  emit(`──── ${title} ────`);
}

function describe(rec) {
  if (!rec) return '(无响应)';
  const j = rec.json;
  const msg = j && j.message !== undefined ? String(j.message) : String(rec.text || '').slice(0, 140);
  return `${rec.method} ${rec.path} → HTTP ${rec.status} code=${j ? j.code : '(非 JSON)'} message=${msg}`;
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

function check(name, condition, rec, extra) {
  return ok(name, condition, `${extra ? `${extra} | ` : ''}${describe(rec)}`);
}

const d = (rec) => (rec && rec.json && rec.json.data !== undefined ? rec.json.data : null);
const keysOf = (v) => (v && typeof v === 'object' ? Object.keys(v).join(',') : String(v));
const hasText = (rec, needle) => !!rec && String(rec.text || '').includes(needle);

// 列表响应形状兼容：数组 或 分页对象 {list,total}
function rowsOf(data) {
  if (Array.isArray(data)) return data;
  if (data && typeof data === 'object' && Array.isArray(data.list)) return data.list;
  return null;
}

// 成功：HTTP 200 且 json.code === OK_CODE（可要求 message 片段）
function expectOk(name, rec, msgPart) {
  const j = rec && rec.json;
  const got = j ? j.code : undefined;
  const msg = (j && j.message) || '';
  let good = rec && rec.status === 200 && got === OK_CODE;
  let why = describe(rec);
  if (msgPart) {
    good = good && String(msg).includes(msgPart);
    why += ` | 期望 message 含「${msgPart}」`;
  }
  return ok(name, good, why);
}

// 失败：HTTP 200 + 指定业务 code + 中文 message（可要求片段）—— 绝不写成「只断言 code != 0」
function expectReject(name, rec, wantCode, msgPart) {
  const j = rec && rec.json;
  const got = j ? j.code : undefined;
  const msg = (j && j.message) || '';
  const chinese = /[\u4e00-\u9fa5]/.test(String(msg));
  let good = rec && rec.status === 200 && got === wantCode && chinese;
  let why = `${describe(rec)} | 期望 code=${wantCode} 且 message 含中文`;
  if (msgPart) {
    good = good && String(msg).includes(msgPart);
    why += ` 且含「${msgPart}」`;
  }
  return ok(name, good, why);
}

function expectHttp(name, rec, status, extra) {
  return ok(name, rec && rec.status === status, `${extra ? `${extra} | ` : ''}${describe(rec)}`);
}

// 导出/文件类接口的拒绝：HTTP 403 或（HTTP 200 + 业务码 403）两种实现都接受
function expectDenied(name, rec, extra) {
  const j = rec && rec.json;
  const good = rec && (rec.status === 403 || (j && j.code === 403));
  return ok(name, good, `${extra ? `${extra} | ` : ''}${describe(rec)}`);
}

function eq(name, actual, expected, rec) {
  const good = actual === expected;
  return ok(name, good, `期望 ${JSON.stringify(expected)} 实测 ${JSON.stringify(actual)}${rec ? ` | ${describe(rec)}` : ''}`);
}

function okNum(name, actual, expected, tolerance, rec) {
  const good = Number.isFinite(Number(actual)) && Math.abs(Number(actual) - Number(expected)) <= tolerance;
  return ok(name, good, `期望 ${expected}±${tolerance} 实测 ${JSON.stringify(actual)}${rec ? ` | ${describe(rec)}` : ''}`);
}

function divergence(name, detail) {
  divergences.push(`${name} — ${detail}`);
  emit(`  NOTE  ${name}  [${detail}]`);
}

// ---------------------------------------------------------------- HTTP
// 路径归一（与 §5 路由表写法对齐：/{id} 占位；/summary/{userId} 保留 userId 命名）
const TPL_ALIAS = { '/api/volunteer/summary/{id}': '/api/volunteer/summary/{userId}' };
const tpl = (p) => {
  const base = String(p).split('?')[0].replace(/\/\d+/g, '/{id}');
  return TPL_ALIAS[base] || base;
};

async function call(who, method, p, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (cookies[who]) headers.Cookie = cookies[who];
  const res = await fetch(BASE + p, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const buf = Buffer.from(await res.arrayBuffer());
  const text = buf.toString('utf8');
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  const key = `${method} ${tpl(p)}`;
  covered.add(key);
  if (!coveredPhase.has(key)) coveredPhase.set(key, currentPhase);
  return { who, method, path: p, body, status: res.status, text, json, buf, headers: res.headers };
}

// 只拿字节（导出用），同样解析 JSON 以便识别「HTTP 200 + code 403」形态
async function rawGet(who, p) {
  const headers = {};
  if (cookies[who]) headers.Cookie = cookies[who];
  const res = await fetch(BASE + p, { method: 'GET', headers });
  const buf = Buffer.from(await res.arrayBuffer());
  const text = buf.toString('utf8');
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  const key = `GET ${tpl(p)}`;
  covered.add(key);
  if (!coveredPhase.has(key)) coveredPhase.set(key, currentPhase);
  return { who, method: 'GET', path: p, status: res.status, text, json, buf, headers: res.headers };
}

/**
 * 请求体键名契约未逐字给定的写接口：依次尝试候选体，命中 code===200 即返回。
 * 覆盖记账按「方法 + 路径模板」去重，重试不影响覆盖；仅在首个候选失败时才追加请求。
 */
async function callFirst(who, method, p, bodies) {
  let last = null;
  for (const b of bodies) {
    last = await call(who, method, p, b);
    if (last && last.json && last.json.code === OK_CODE) return last;
  }
  return last;
}

async function login(account) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: account, password: PASSWORD }),
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  const raw = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [res.headers.get('set-cookie') || ''];
  const m = (raw.join(';').match(/JSESSIONID=[^;]+/) || [])[0];
  if (!m) throw new Error(`登录 ${account} 未取到 JSESSIONID：HTTP ${res.status} body=${text.slice(0, 200)}`);
  cookies[account] = m;
  return { who: account, method: 'POST', path: '/api/auth/login', status: res.status, text, json, buf: Buffer.from(text, 'utf8'), headers: res.headers };
}

// ---------------------------------------------------------------- 48 条路由
const ROUTES = [
  // VolunteerController /api/volunteer（26）
  'GET /api/volunteer/projects',
  'GET /api/volunteer/projects/{id}',
  'POST /api/volunteer/projects',
  'PUT /api/volunteer/projects/{id}',
  'POST /api/volunteer/projects/{id}/review',
  'POST /api/volunteer/projects/{id}/enroll',
  'GET /api/volunteer/enrollments',
  'POST /api/volunteer/enrollments/{id}/cancel',
  'POST /api/volunteer/enrollments/{id}/attendance',
  'GET /api/volunteer/records',
  'GET /api/volunteer/records/mine',
  'GET /api/volunteer/records/{id}',
  'POST /api/volunteer/records',
  'POST /api/volunteer/records/batch',
  'POST /api/volunteer/records/{id}/review',
  'POST /api/volunteer/records/{id}/makeup',
  'GET /api/volunteer/summary/mine',
  'GET /api/volunteer/summary/{userId}',
  'GET /api/volunteer/stats',
  'GET /api/volunteer/stats/export',
  'GET /api/volunteer/blacklist',
  'POST /api/volunteer/blacklist',
  'POST /api/volunteer/blacklist/{id}/revoke',
  'GET /api/volunteer/appeals',
  'POST /api/volunteer/appeals',
  'POST /api/volunteer/appeals/{id}/rule',
  // ClubController /api/club（22）
  'GET /api/club/list',
  'GET /api/club/list/mine',
  'GET /api/club/{id}',
  'POST /api/club',
  'PUT /api/club/{id}',
  'POST /api/club/{id}/deregister',
  'GET /api/club/members',
  'POST /api/club/members',
  'DELETE /api/club/members/{id}',
  'GET /api/club/reviews',
  'POST /api/club/reviews',
  'POST /api/club/reviews/{id}/opinion',
  'POST /api/club/reviews/{id}/submit',
  'POST /api/club/reviews/{id}/result',
  'GET /api/club/ratings',
  'POST /api/club/ratings',
  'PUT /api/club/ratings/{id}',
  'GET /api/club/publicity',
  'GET /api/club/publicity/{id}',
  'POST /api/club/publicity',
  'POST /api/club/publicity/{id}/close',
  'GET /api/club/stats/export',
];

// ---------------------------------------------------------------- 运行期上下文
const ctx = {
  uid: {},            // 账号 → userId
  studentNo: {},      // 账号 → 学号
  projectMain: null,  // 主流程项目（自建，approve 后报名）
  projectEdit: null,  // 用于 draft/rejected 可编辑断言
  projectPending: null, // 未审批项目（报名负例）
  projectOld: null,   // 结束时间超 6 个月（补录窗口负例）
  enrollment: {},     // 报名 id
  record: {},         // 时长记录 id
  blacklist: {},
  appeal: {},
  clubMain: null,
  clubDrop: null,
  member: {},
  reviewId: null,
  ratingId: null,
  pubId: null,
};

const stuCaptures = [];
function trackStudent(label, rec) {
  stuCaptures.push({ label, text: String((rec && rec.text) || '') });
}

const nid = (v) => (v === undefined || v === null ? 99999999 : v);
const weekYear = '2025-2026';

// ================================================================ 阶段 0：登录
async function phase0Login() {
  section('阶段 0：登录（每账号只登录一次，全程复用同一 JSESSIONID）');
  const accounts = ['admin', 'zhangsan', 'lisi', 'wangxm', 'zhaoxh', 'chendw', 'liusq', 'zhoujie'];
  const roles = { admin: 'admin', zhangsan: 'teacher', lisi: 'teacher', wangxm: 'student', zhaoxh: 'student', chendw: 'student', liusq: 'student', zhoujie: 'student' };
  const knownIds = { admin: 1, zhangsan: 2, lisi: 3, wangxm: 4, zhaoxh: 5, chendw: 6, liusq: 7 };
  for (const acc of accounts) {
    const rec = await login(acc);
    expectOk(`POST /api/auth/login 登录 ${acc}`, rec);
    ok(`登录 ${acc} → 响应含 role=${roles[acc]}`, hasText(rec, `"${roles[acc]}"`), describe(rec));
    const data = d(rec);
    ctx.uid[acc] = data && data.id !== undefined ? data.id : null;
    ctx.studentNo[acc] = (data && (data.studentNo || data.student_no)) || null;
    if (knownIds[acc] !== undefined) {
      eq(`登录 ${acc} → userId=${knownIds[acc]}`, ctx.uid[acc], knownIds[acc], rec);
    } else {
      ok(`登录 ${acc} → 取得 userId`, ctx.uid[acc] !== null, `实测 userId=${JSON.stringify(ctx.uid[acc])} | ${describe(rec)}`);
    }
  }
  // 辅导员-学生对应关系（student_profile.counselor_id）
  eq('wangxm 的辅导员为 lisi(id=3)', ctx.uid.lisi, 3);
  eq('liusq 的辅导员为 zhangsan(id=2)', ctx.uid.zhangsan, 2);
  ok('zhoujie 归属 zhangsan 名下（用于跨辅导员 scope 负例）', ctx.uid.zhoujie !== null, `实测 zhoujie userId=${JSON.stringify(ctx.uid.zhoujie)}`);

  // 伪造会话
  cookies.forged = 'JSESSIONID=e2e-p17-forged-0000';
  const forged = await call('forged', 'GET', '/api/volunteer/records/mine');
  expectReject('伪造会话 GET /api/volunteer/records/mine → 401 请先登录', forged, 401, '请先登录');
  const anon = await call(undefined, 'GET', '/api/club/list');
  expectReject('未登录 GET /api/club/list → 401 请先登录', anon, 401, '请先登录');
}

// ================================================================ 阶段 1：志愿项目
async function phase1Project() {
  section('阶段 1：志愿服务项目（立项/审批/编辑/报名前置态）');

  // 主项目：昨天结束（补录窗口内）
  const body = {
    projectName: `${TAG} 校园公益志愿服务`,
    category: '校园公益',
    organizer: `${TAG} 青年志愿服务指导中心`,
    organizerType: 'center',
    startTime: `${addDays(TODAY, -2)} 09:00:00`,
    endTime: `${addDays(TODAY, -1)} 17:00:00`,
    location: '主楼广场',
    durationNote: `${TAG} 时长认定说明（8 小时/天）`,
    recruitLimit: 30,
  };
  const created = await call('admin', 'POST', '/api/volunteer/projects', body);
  expectOk('POST /api/volunteer/projects 管理员立项', created);
  ctx.projectMain = d(created) && d(created).id !== undefined ? d(created).id : null;
  ok('立项返回 id', ctx.projectMain !== null, describe(created));
  const pd = d(created) || {};
  ok('立项响应含契约字段 projectName/category/organizerType/status',
    ['projectName', 'category', 'organizerType', 'status'].every((k) => k in pd),
    `实测 keys=${keysOf(pd)}`);
  ok('立项 project_name 带 e2e-p17 标记（clean-p17.sql 据此清理）', hasText(created, 'e2e-p17'), describe(created));

  // 必填负例
  const noName = await call('admin', 'POST', '/api/volunteer/projects', { ...body, projectName: '' });
  expectReject('POST /projects 负数：projectName 为空 → 400', noName, 400, '不能为空');
  const noOrg = await call('admin', 'POST', '/api/volunteer/projects', { ...body, organizer: '' });
  expectReject('POST /projects 负数：organizer 为空 → 400', noOrg, 400, '不能为空');
  const badType = await call('admin', 'POST', '/api/volunteer/projects', { ...body, organizerType: 'school' });
  expectReject('POST /projects 负数：organizerType 非 center/department/club/student_activity → 400', badType, 400, '只能是');
  const badCat = await call('admin', 'POST', '/api/volunteer/projects', { ...body, category: '不存在的类别' });
  expectReject('POST /projects 负数：category 非 15 类志愿类别 → 400', badCat, 400, '只能是');
  const stuCreate = await call('wangxm', 'POST', '/api/volunteer/projects', body);
  expectReject('POST /projects 负数：学生立项 → 403（立项须经中心审批，第十五条(一)）', stuCreate, 403, '无权');

  // 列表与详情
  const list = await call('wangxm', 'GET', '/api/volunteer/projects');
  expectOk('GET /api/volunteer/projects 列表', list);
  const rows = rowsOf(d(list));
  ok('项目列表返回数组或分页对象', rows !== null, `实测 data=${keysOf(d(list))}`);
  if (rows) {
    ok('学生项目列表只含 approved 项目（§12.2-5）',
      rows.every((r) => r.status === 'approved'),
      `实测 status 集合=${[...new Set(rows.map((r) => r.status))].join(',')}`);
  }
  // 队长裁定（2026-09-22）：学生侧只应看到已批准项目（§6「学生只看 approved」），
  // 未批准项目详情对学生按越权拒（原断言直接 expectOk 与 §6 冲突）；管理端详情不受状态限制。
  const detail = await call('wangxm', 'GET', `/api/volunteer/projects/${nid(ctx.projectMain)}`);
  if (pd.status === 'approved') {
    expectOk('GET /api/volunteer/projects/{id} 详情（学生可见已批准项目）', detail);
  } else {
    expectReject('GET /projects/{id} 学生查未批准项目 → 403（§6 学生只看已批准项目）', detail, 403, '无权');
  }
  const detailAdmin = await call('admin', 'GET', `/api/volunteer/projects/${nid(ctx.projectMain)}`);
  expectOk('GET /api/volunteer/projects/{id} 详情（管理端，不受状态限制）', detailAdmin);
  const missProject = await call('admin', 'GET', '/api/volunteer/projects/99999999');
  expectReject('GET /projects/{id} 负数：不存在 → 404', missProject, 404, '不存在');

  // 编辑：仅 draft/rejected
  const curStatus = pd.status;
  const editable = curStatus === 'draft' || curStatus === 'rejected';
  const putMain = await call('admin', 'PUT', `/api/volunteer/projects/${nid(ctx.projectMain)}`, { ...body, location: '主楼广场（西侧）' });
  if (editable) {
    expectOk(`PUT /api/volunteer/projects/{id} 编辑（当前 status=${curStatus}，属可编辑态）`, putMain);
  } else {
    expectReject(`PUT /projects/{id} 负数：status=${curStatus}（仅 draft/rejected 可编辑）→ 400`, putMain, 400);
  }
  ok(`立项后默认 status 记录（实测 ${curStatus}）`, typeof curStatus === 'string' && curStatus.length > 0, describe(created));
  if (curStatus === 'pending') {
    divergence('PUT /projects/{id} 可编辑态', 'POST /projects 直接落 pending，则 draft 无从产生；本脚本改用「驳回后编辑」覆盖 PUT 成功路径');
  }

  // 提交审批：前端 volunteer-review.html:1530-1534 的 submitProject 即 PUT { status:'pending' }（POST /projects 只落 draft）
  const submitMain = await call('admin', 'PUT', `/api/volunteer/projects/${nid(ctx.projectMain)}`, { status: 'pending' });
  expectOk('PUT /projects/{id} 提交审批（draft→pending）', submitMain);

  // 未审批项目（报名负例）——新建即 draft，刻意不提交
  const pending = await call('admin', 'POST', '/api/volunteer/projects', {
    ...body,
    projectName: `${TAG} 未审批项目（报名负例）`,
  });
  expectOk('POST /projects 立项（未审批项目，供报名负例）', pending);
  ctx.projectPending = d(pending) && d(pending).id !== undefined ? d(pending).id : null;

  // 可供编辑的 rejected 项目
  const editOne = await call('admin', 'POST', '/api/volunteer/projects', { ...body, projectName: `${TAG} 驳回后可编辑项目` });
  expectOk('POST /projects 立项（驳回项目，供 PUT 成功路径）', editOne);
  ctx.projectEdit = d(editOne) && d(editOne).id !== undefined ? d(editOne).id : null;
  const submitEdit = await call('admin', 'PUT', `/api/volunteer/projects/${nid(ctx.projectEdit)}`, { status: 'pending' });
  expectOk('PUT /projects/{id} 提交审批（projectEdit draft→pending，供驳回路径）', submitEdit);
  const rejectOne = await call('admin', 'POST', `/api/volunteer/projects/${nid(ctx.projectEdit)}/review`, {
    action: 'reject',
    rejectReason: `${TAG} 材料不全，退回修改`,
  });
  expectOk('POST /api/volunteer/projects/{id}/review 驳回（供 rejected 编辑路径）', rejectOne);
  const putEdit = await call('admin', 'PUT', `/api/volunteer/projects/${nid(ctx.projectEdit)}`, { ...body, projectName: `${TAG} 驳回后可编辑项目（已修改）` });
  expectOk('PUT /projects/{id} 编辑 rejected 项目（draft/rejected 可编辑，§5）', putEdit);

  // 辅导员不能审批
  const teacherReview = await call('lisi', 'POST', `/api/volunteer/projects/${nid(ctx.projectMain)}/review`, { action: 'approve' });
  expectReject('POST /projects/{id}/review 负数：辅导员审批 → 403（§6 辅导员不能审核）', teacherReview, 403, '无权');

  // 管理员审批主项目
  const review = await call('admin', 'POST', `/api/volunteer/projects/${nid(ctx.projectMain)}/review`, { action: 'approve' });
  expectOk('POST /api/volunteer/projects/{id}/review 管理员审批通过', review);
  const afterReview = await call('admin', 'GET', `/api/volunteer/projects/${nid(ctx.projectMain)}`);
  eq('审批后项目 status=approved', d(afterReview) && d(afterReview).status, 'approved', afterReview);

  // 终态：approved 项目不可再编辑、不可重复审批
  const putApproved = await call('admin', 'PUT', `/api/volunteer/projects/${nid(ctx.projectMain)}`, { ...body, location: '非法修改' });
  expectReject('PUT /projects/{id} 负数：approved 项目再编辑 → 400（approved 为终态）', putApproved, 400);
  const reReview = await call('admin', 'POST', `/api/volunteer/projects/${nid(ctx.projectMain)}/review`, { action: 'approve' });
  expectReject('POST /projects/{id}/review 负数：approved 项目重复审批 → 400（终态不可再改，§7）', reReview, 400);
  const missReview = await call('admin', 'POST', '/api/volunteer/projects/99999999/review', { action: 'approve' });
  expectReject('POST /projects/{id}/review 负数：项目不存在 → 404', missReview, 404, '不存在');

  // 学生端 now 应能看到 approved 项目（队长裁定：审批通过后的详情 200 断言补在这里）
  const detailAfter = await call('wangxm', 'GET', `/api/volunteer/projects/${nid(ctx.projectMain)}`);
  expectOk('GET /api/volunteer/projects/{id} 详情（审批通过后学生可见）', detailAfter);
  const list2 = await call('wangxm', 'GET', '/api/volunteer/projects');
  const rows2 = rowsOf(d(list2)) || [];
  ok('学生项目列表包含本轮已审批项目，且全部 approved',
    rows2.length > 0 && rows2.every((r) => r.status === 'approved') && rows2.some((r) => r.id === ctx.projectMain),
    `实测行数=${rows2.length} | ${describe(list2)}`);

  // 超窗项目（结束 > 6 个月）
  const old = await call('admin', 'POST', '/api/volunteer/projects', {
    ...body,
    projectName: `${TAG} 超窗项目（结束超 6 个月）`,
    startTime: `${addDays(TODAY, -210)} 09:00:00`,
    endTime: `${addDays(TODAY, -200)} 17:00:00`,
  });
  expectOk('POST /projects 立项（结束超 6 个月，供补录窗口负例）', old);
  ctx.projectOld = d(old) && d(old).id !== undefined ? d(old).id : null;
  const submitOld = await call('admin', 'PUT', `/api/volunteer/projects/${nid(ctx.projectOld)}`, { status: 'pending' });
  expectOk('PUT /projects/{id} 提交审批（projectOld draft→pending）', submitOld);
  const reviewOld = await call('admin', 'POST', `/api/volunteer/projects/${nid(ctx.projectOld)}/review`, { action: 'approve' });
  expectOk('POST /api/volunteer/projects/{id}/review 审批通过（超窗项目，供补录窗口负例）', reviewOld);
}

// ================================================================ 阶段 2：报名与出勤
async function phase2Enrollment() {
  section('阶段 2：报名与出勤登记（/enrollments、/enroll、/cancel、/attendance）');

  // 未 approved 项目不能报名
  const early = await call('wangxm', 'POST', `/api/volunteer/projects/${nid(ctx.projectPending)}/enroll`, {});
  expectReject('POST /projects/{id}/enroll 负数：未 approved 项目报名 → 400（§4/§7 违序必拒）', early, 400);

  // 正常报名
  const e1 = await call('wangxm', 'POST', `/api/volunteer/projects/${nid(ctx.projectMain)}/enroll`, { remark: `${TAG} 报名` });
  expectOk('POST /api/volunteer/projects/{id}/enroll 学生报名 approved 项目', e1);
  ctx.enrollment.main = d(e1) && d(e1).id !== undefined ? d(e1).id : null;
  const e1d = d(e1) || {};
  ok('报名记录初始 status 属枚举 pending/confirmed（契约未定谁置 confirmed，见 README 歧义项）',
    ['pending', 'confirmed'].includes(e1d.status),
    `实测 status=${JSON.stringify(e1d.status)} | ${describe(e1)}`);

  // 重复报名（uk_project_user）
  const dup = await call('wangxm', 'POST', `/api/volunteer/projects/${nid(ctx.projectMain)}/enroll`, { remark: `${TAG} 重复报名` });
  expectReject('POST /projects/{id}/enroll 负数：同一学生重复报名 → 400（uk_project_user）', dup, 400);

  // 其他学生报名
  const e2 = await call('zhaoxh', 'POST', `/api/volunteer/projects/${nid(ctx.projectMain)}/enroll`, { remark: `${TAG} 报名` });
  expectOk('POST /projects/{id}/enroll 学生 zhaoxh 报名', e2);
  ctx.enrollment.zhaoxh = d(e2) && d(e2).id !== undefined ? d(e2).id : null;
  const e3 = await call('chendw', 'POST', `/api/volunteer/projects/${nid(ctx.projectMain)}/enroll`, { remark: `${TAG} 报名` });
  expectOk('POST /projects/{id}/enroll 学生 chendw 报名', e3);
  ctx.enrollment.chendw = d(e3) && d(e3).id !== undefined ? d(e3).id : null;
  const e4 = await call('liusq', 'POST', `/api/volunteer/projects/${nid(ctx.projectMain)}/enroll`, { remark: `${TAG} 报名（zhangsan 名下）` });
  expectOk('POST /projects/{id}/enroll 学生 liusq 报名', e4);
  ctx.enrollment.liusq = d(e4) && d(e4).id !== undefined ? d(e4).id : null;

  // 辅导员登记出勤（名下学生）
  const att = await callFirst('lisi', 'POST', `/api/volunteer/enrollments/${nid(ctx.enrollment.zhaoxh)}/attendance`, [
    { status: 'attended' },
    { attendance: 'attended' },
  ]);
  expectOk('POST /api/volunteer/enrollments/{id}/attendance 辅导员登记出勤（attended）', att);
  const badAtt = await callFirst('lisi', 'POST', `/api/volunteer/enrollments/${nid(ctx.enrollment.chendw)}/attendance`, [
    { status: 'present' },
    { attendance: 'present' },
  ]);
  expectReject('POST /enrollments/{id}/attendance 负数：出勤状态非 attended/absent → 400', badAtt, 400, '只能是');
  const stuAtt = await call('wangxm', 'POST', `/api/volunteer/enrollments/${nid(ctx.enrollment.liusq)}/attendance`, { status: 'attended' });
  expectReject('POST /enrollments/{id}/attendance 负数：学生登记出勤 → 403', stuAtt, 403, '无权');
  const missAtt = await callFirst('admin', 'POST', '/api/volunteer/enrollments/99999999/attendance', [{ status: 'attended' }]);
  expectReject('POST /enrollments/{id}/attendance 负数：报名不存在 → 404', missAtt, 404, '不存在');

  // 取消报名 → cancelled 不可登记出勤
  const cancel = await call('wangxm', 'POST', `/api/volunteer/enrollments/${nid(ctx.enrollment.chendw ? ctx.enrollment.chendw : null)}/cancel`);
  // 说明：chendw 的报名不是 wangxm 的，学生不能取消他人报名 → 先断言越权，再取消自己的
  expectReject('POST /enrollments/{id}/cancel 负数：学生取消他人报名 → 403', cancel, 403, '无权');

  const cancelOwn = await call('wangxm', 'POST', `/api/volunteer/enrollments/${nid(ctx.enrollment.main)}/cancel`);
  expectOk('POST /api/volunteer/enrollments/{id}/cancel 学生取消本人报名', cancelOwn);
  const attCancelled = await callFirst('lisi', 'POST', `/api/volunteer/enrollments/${nid(ctx.enrollment.main)}/attendance`, [
    { status: 'attended' },
    { attendance: 'attended' },
  ]);
  expectReject('POST /enrollments/{id}/attendance 负数：cancelled 报名登记出勤 → 400（§7 违序必拒）', attCancelled, 400);
  const reCancel = await call('wangxm', 'POST', `/api/volunteer/enrollments/${nid(ctx.enrollment.main)}/cancel`);
  expectReject('POST /enrollments/{id}/cancel 负数：已 cancelled 报名重复取消 → 400（CAS）', reCancel, 400);

  // 缺席（黑名单 c34_1 的事实来源）
  const absent = await callFirst('lisi', 'POST', `/api/volunteer/enrollments/${nid(ctx.enrollment.liusq)}/attendance`, [
    { status: 'absent' },
    { attendance: 'absent' },
  ]);
  expectOk('POST /enrollments/{id}/attendance 辅导员登记缺席（absent）', absent);

  // 列表与 scope
  const all = await call('admin', 'GET', '/api/volunteer/enrollments');
  expectOk('GET /api/volunteer/enrollments 管理员全量', all);
  const allRows = rowsOf(d(all));
  ok('报名列表返回数组或分页对象', allRows !== null, `实测 data=${keysOf(d(all))}`);
  if (allRows) {
    ok('管理员可见本轮报名记录', allRows.some((r) => r.id === ctx.enrollment.liusq), `实测行数=${allRows.length}`);
  }
  const mine = await call('wangxm', 'GET', '/api/volunteer/enrollments?mine=1');
  expectOk('GET /enrollments 学生查询（带 mine 参数）', mine);
  trackStudent('enrollments 学生查询', mine);
  const mineRows = rowsOf(d(mine));
  if (mineRows && mineRows.length > 0 && mineRows[0].userId !== undefined) {
    ok('学生只看到本人报名（scope：只读自己）', mineRows.every((r) => r.userId === ctx.uid.wangxm),
      `实测 userId 集合=${[...new Set(mineRows.map((r) => r.userId))].join(',')}`);
  } else {
    divergence('GET /enrollments?mine=1', '未按 mine 过滤（学生视角返回全量或缺少 userId 字段），已改由 /records/mine 与 /summary/mine 校验学生 scope');
  }
}

// ================================================================ 阶段 3：时长记录
async function phase3Record() {
  section('阶段 3：志愿服务时长记录（录入/批量/审核/补录）');

  const base = {
    projectId: ctx.projectMain,
    serviceDate: addDays(TODAY, -1),
    hourType: 'credit',
    source: 'platform',
    recorderSubject: 'center',
    signIn: `${addDays(TODAY, -1)} 09:00:00`,
    signOut: `${addDays(TODAY, -1)} 12:30:00`,
    makeupNote: `${TAG} 平台打卡`,
  };

  // 正常录入（辅导员录入名下学生）
  const r1 = await call('lisi', 'POST', '/api/volunteer/records', { ...base, userId: ctx.uid.wangxm, hours: 3.5 });
  expectOk('POST /api/volunteer/records 辅导员录入时长（credit/platform）', r1);
  ctx.record.r1 = d(r1) && d(r1).id !== undefined ? d(r1).id : null;
  const r1d = d(r1) || {};
  ok('时长记录响应含契约字段（userId/studentName/studentNo/hours/hourType/source/status/recorderSubject）',
    ['userId', 'studentName', 'studentNo', 'hours', 'hourType', 'source', 'status', 'recorderSubject'].every((k) => k in r1d),
    `实测 keys=${keysOf(r1d)}`);
  eq('新录入记录初始 status=pending', r1d.status, 'pending', r1);
  okNum('录入 hours 回显', r1d.hours, 3.5, 0.001, r1);
  ctx.studentNo.wangxm = r1d.studentNo || ctx.studentNo.wangxm;

  // 必填/枚举负例
  const noUser = await call('lisi', 'POST', '/api/volunteer/records', { ...base, hours: 2 });
  expectReject('POST /records 负数：缺 userId → 400', noUser, 400, '不能为空');
  const noDate = await call('lisi', 'POST', '/api/volunteer/records', { ...base, userId: ctx.uid.wangxm });
  expectReject('POST /records 负数：缺 serviceDate → 400', noDate, 400, '不能为空');
  const badHourType = await call('lisi', 'POST', '/api/volunteer/records', { ...base, userId: ctx.uid.wangxm, hours: 2, hourType: 'extra' });
  expectReject('POST /records 负数：hourType 非 credit/honor → 400', badHourType, 400, '只能是');
  const badSource = await call('lisi', 'POST', '/api/volunteer/records', { ...base, userId: ctx.uid.wangxm, hours: 2, source: 'manual' });
  expectReject('POST /records 负数：source 非 platform/makeup → 400', badSource, 400, '只能是');
  const badSubject = await call('lisi', 'POST', '/api/volunteer/records', { ...base, userId: ctx.uid.wangxm, hours: 2, recorderSubject: 'gov' });
  expectReject('POST /records 负数：recorderSubject 非四类主体 → 400', badSubject, 400, '只能是');
  const outOfScope = await call('lisi', 'POST', '/api/volunteer/records', { ...base, userId: ctx.uid.liusq, hours: 2 });
  expectReject('POST /records 负数：辅导员录入非名下学生（liusq 属 zhangsan）→ 403', outOfScope, 403, '无权');
  // 队长裁定（2026-09-22）：学生补录申请走 POST /records（服务端强制 source=makeup、userId=本人）；
  // 学生端 volunteer.html:1033 的 body 正是 {projectId, serviceDate, hours, hourType, source:'makeup', makeupNote}（不带 userId）。
  const stuRecord = await call('wangxm', 'POST', '/api/volunteer/records', {
    projectId: ctx.projectMain,
    serviceDate: addDays(TODAY, -2),
    hours: 1,
    hourType: 'credit',
    source: 'makeup',
    makeupNote: `${TAG} 学生补录申请`,
  });
  expectOk('POST /records 学生补录申请（服务端强制 source=makeup、userId=本人）', stuRecord);
  const srD = d(stuRecord) || {};
  eq('学生补录申请 source 被强制为 makeup', String(srD.source || ''), 'makeup', stuRecord);
  eq('学生补录申请 userId 被默认/强制为本人', Number(srD.userId), Number(ctx.uid.wangxm), stuRecord);
  eq('学生补录申请初始 status=pending（待中心审核）', String(srD.status || ''), 'pending', stuRecord);
  const stuRecordOther = await call('wangxm', 'POST', '/api/volunteer/records', {
    projectId: ctx.projectMain, serviceDate: addDays(TODAY, -2), hours: 1, hourType: 'credit', source: 'makeup',
    userId: ctx.uid.chendw, makeupNote: `${TAG} 学生替他人补录`,
  });
  expectReject('POST /records 负数：学生替他人提交补录 → 403', stuRecordOther, 403, '无权');

  // 荣誉时数（credit/honor 分流）
  const rHonor = await call('lisi', 'POST', '/api/volunteer/records', {
    ...base, userId: ctx.uid.wangxm, hours: 2, hourType: 'honor', recorderSubject: 'studentActivity', makeupNote: `${TAG} 荣誉时数`,
  });
  expectOk('POST /records 录入荣誉时数（hour_type=honor）', rHonor);
  ctx.record.honor = d(rHonor) && d(rHonor).id !== undefined ? d(rHonor).id : null;

  // 单日超 8 小时：只提示不硬拒（S21 第二十二条）
  const over8 = await call('lisi', 'POST', '/api/volunteer/records', { ...base, userId: ctx.uid.wangxm, hours: 10, makeupNote: `${TAG} 单日超 8 小时` });
  expectOk('POST /records 边界：单日 10 小时只提示不硬拒（§4 第二十二条）', over8);
  divergence('单日 8 小时上限提示载体', '契约只规定「只提示不硬拒」，未规定提示落在 message 还是 data 的提示字段，本脚本仅断言不硬拒');

  // 批量录入
  const batch = await callFirst('lisi', 'POST', '/api/volunteer/records/batch', [
    {
      records: [
        { ...base, userId: ctx.uid.zhaoxh, hours: 4, serviceDate: addDays(TODAY, -2), makeupNote: `${TAG} 批量录入` },
        { ...base, userId: ctx.uid.chendw, hours: 2, serviceDate: addDays(TODAY, -2), makeupNote: `${TAG} 批量录入` },
      ],
    },
    {
      items: [
        { ...base, userId: ctx.uid.zhaoxh, hours: 4, serviceDate: addDays(TODAY, -2), makeupNote: `${TAG} 批量录入` },
        { ...base, userId: ctx.uid.chendw, hours: 2, serviceDate: addDays(TODAY, -2), makeupNote: `${TAG} 批量录入` },
      ],
    },
    {
      list: [
        { ...base, userId: ctx.uid.zhaoxh, hours: 4, serviceDate: addDays(TODAY, -2), makeupNote: `${TAG} 批量录入` },
        { ...base, userId: ctx.uid.chendw, hours: 2, serviceDate: addDays(TODAY, -2), makeupNote: `${TAG} 批量录入` },
      ],
    },
  ]);
  expectOk('POST /api/volunteer/records/batch 批量录入（辅导员名下学生）', batch);
  ok('批量录入命中参数键（records/items/list 之一）', hasText(batch, `${TAG} 批量录入`) || d(batch) !== null, describe(batch));

  // 补录窗口（6 个月，S21 第二十六条）：项目结束超 6 个月不得补录
  const late = await call('lisi', 'POST', '/api/volunteer/records', {
    ...base,
    userId: ctx.uid.wangxm,
    hours: 2,
    serviceDate: addDays(TODAY, -200),
    source: 'makeup',
    makeupNote: `${TAG} 超窗补录`,
    projectId: ctx.projectOld,
  });
  expectReject('POST /records 边界：项目结束超 6 个月补录 → 400（S21 第二十六条）', late, 400);

  // 审核：辅导员不能审核
  const tReview = await call('lisi', 'POST', `/api/volunteer/records/${nid(ctx.record.r1)}/review`, { action: 'approve' });
  expectReject('POST /records/{id}/review 负数：辅导员审核时长 → 403（§6）', tReview, 403, '无权');

  // 管理员审核通过
  const aReview = await call('admin', 'POST', `/api/volunteer/records/${nid(ctx.record.r1)}/review`, { action: 'approve' });
  expectOk('POST /api/volunteer/records/{id}/review 管理员审核通过', aReview);
  const r1After = await call('admin', 'GET', `/api/volunteer/records/${nid(ctx.record.r1)}`);
  eq('审核通过后记录 status=approved', d(r1After) && d(r1After).status, 'approved', r1After);
  const reReview = await call('admin', 'POST', `/api/volunteer/records/${nid(ctx.record.r1)}/review`, { action: 'approve' });
  expectReject('POST /records/{id}/review 负数：approved 记录重复审核 → 400（终态不可再改，§7）', reReview, 400);
  const missReview = await call('admin', 'POST', '/api/volunteer/records/99999999/review', { action: 'approve' });
  expectReject('POST /records/{id}/review 负数：记录不存在 → 404', missReview, 404, '不存在');

  // 驳回 → 驳回后重来做显式重新提交（新记录），被驳记录不得再审核
  const r2 = await call('lisi', 'POST', '/api/volunteer/records', { ...base, userId: ctx.uid.wangxm, hours: 1.5, serviceDate: addDays(TODAY, -3), makeupNote: `${TAG} 待驳回` });
  expectOk('POST /records 录入待驳回记录', r2);
  ctx.record.r2 = d(r2) && d(r2).id !== undefined ? d(r2).id : null;
  const rej = await call('admin', 'POST', `/api/volunteer/records/${nid(ctx.record.r2)}/review`, {
    action: 'reject',
    rejectReason: `${TAG} 材料不全`,
  });
  expectOk('POST /records/{id}/review 管理员驳回（带中文原因）', rej);
  const r2After = await call('admin', 'GET', `/api/volunteer/records/${nid(ctx.record.r2)}`);
  eq('驳回后记录 status=rejected', d(r2After) && d(r2After).status, 'rejected', r2After);
  const rejAgain = await call('admin', 'POST', `/api/volunteer/records/${nid(ctx.record.r2)}/review`, { action: 'approve' });
  expectReject('POST /records/{id}/review 负数：rejected 记录再审核 → 400（终态）', rejAgain, 400);
  const resubmit = await call('lisi', 'POST', '/api/volunteer/records', { ...base, userId: ctx.uid.wangxm, hours: 1.5, serviceDate: addDays(TODAY, -3), makeupNote: `${TAG} 驳回后重新提交` });
  expectOk('POST /records 驳回后显式重新提交（新记录，§7 驳回后重来）', resubmit);

  // 补录接口（POST /records/{id}/makeup）
  const makeup = await call('lisi', 'POST', `/api/volunteer/records/${nid(ctx.record.r2)}/makeup`, { makeupNote: `${TAG} 补录说明` });
  expectOk('POST /api/volunteer/records/{id}/makeup 补录/更正（窗口内）', makeup);
  const missMakeup = await call('lisi', 'POST', '/api/volunteer/records/99999999/makeup', { makeupNote: `${TAG} 不存在` });
  expectReject('POST /records/{id}/makeup 负数：记录不存在 → 404', missMakeup, 404, '不存在');

  // 列表与 scope
  const all = await call('admin', 'GET', '/api/volunteer/records');
  expectOk('GET /api/volunteer/records 管理员全量', all);
  const allRows = rowsOf(d(all));
  ok('时长记录列表返回数组或分页对象', allRows !== null, `实测 data=${keysOf(d(all))}`);
  if (allRows) {
    ok('管理员列表含本轮记录', allRows.some((r) => r.id === ctx.record.r1), `实测行数=${allRows.length}`);
    for (const acc of ['zhaoxh', 'chendw']) {
      const hit = allRows.find((r) => r.userId === ctx.uid[acc]);
      if (hit && !ctx.studentNo[acc]) ctx.studentNo[acc] = hit.studentNo;
    }
  }

  const lisiRec = await call('lisi', 'GET', '/api/volunteer/records');
  expectOk('GET /records 辅导员查询', lisiRec);
  const lisiRows = rowsOf(d(lisiRec)) || [];
  ok('辅导员只能看到自己名下学生的时长记录（wangxm/zhaoxh/chendw）',
    lisiRows.length > 0 && lisiRows.every((r) => [ctx.uid.wangxm, ctx.uid.zhaoxh, ctx.uid.chendw].includes(r.userId)),
    `实测 userId 集合=${[...new Set(lisiRows.map((r) => r.userId))].join(',')}`);

  const zsRec = await call('zhangsan', 'GET', '/api/volunteer/records');
  expectOk('GET /records 辅导员 zhangsan 查询', zsRec);
  const zsRows = rowsOf(d(zsRec)) || [];
  ok('跨辅导员：zhangsan 看不到 lisi 名下学生记录',
    zsRows.every((r) => ![ctx.uid.wangxm, ctx.uid.zhaoxh, ctx.uid.chendw].includes(r.userId)),
    `实测 userId 集合=${[...new Set(zsRows.map((r) => r.userId))].join(',')}`);
  const filtered = await call('zhangsan', 'GET', `/api/volunteer/records?userId=${ctx.uid.wangxm}`);
  expectOk('GET /records 辅导员按名下学生过滤（越权参数）', filtered);
  const fRows = rowsOf(d(filtered)) || [];
  ok('跨辅导员按 userId 过滤归属他人学生 → 结果为空',
    fRows.length === 0 || fRows.every((r) => r.userId !== ctx.uid.wangxm),
    `实测行数=${fRows.length} | ${describe(filtered)}`);

  const mine = await call('wangxm', 'GET', '/api/volunteer/records/mine');
  expectOk('GET /api/volunteer/records/mine 学生本人记录（字面量路由优先于 {id}）', mine);
  trackStudent('records/mine', mine);
  const mineRows = rowsOf(d(mine));
  ok('records/mine 返回数组或分页对象', mineRows !== null, `实测 data=${keysOf(d(mine))}`);
  if (mineRows) {
    ok('学生只看到本人记录（scope）',
      mineRows.length > 0 && mineRows.every((r) => r.userId === ctx.uid.wangxm),
      `实测 userId 集合=${[...new Set(mineRows.map((r) => r.userId))].join(',')}`);
    const other = ctx.studentNo.zhaoxh;
    if (other) {
      ok('学生明细不含他人学号（脱敏）', !mine.text.includes(other), `他人学号=${other} | ${describe(mine)}`);
    } else {
      divergence('学生明细他人学号扫描', '未从管理员列表中取到 zhaoxh 的学号，跳过该条扫描');
    }
  }

  const detail = await call('admin', 'GET', `/api/volunteer/records/${nid(ctx.record.r1)}`);
  expectOk('GET /api/volunteer/records/{id} 详情', detail);
  const missDetail = await call('admin', 'GET', '/api/volunteer/records/99999999');
  expectReject('GET /records/{id} 负数：不存在 → 404', missDetail, 404, '不存在');
  const stuOther = await call('wangxm', 'GET', `/api/volunteer/records/${nid(ctx.record.r2)}`);
  ok('学生读本人记录详情（不被 scope 误拒）', stuOther.json && stuOther.json.code === OK_CODE, describe(stuOther));
}

// ================================================================ 阶段 4：累计与星级推算
async function phase4Summary() {
  section('阶段 4：累计时长、星级推算与本学年 20 小时达标（/summary、/stats）');

  const before = await call('wangxm', 'GET', '/api/volunteer/summary/mine');
  expectOk('GET /api/volunteer/summary/mine 学生本人累计', before);
  trackStudent('summary/mine', before);
  const b = d(before) || {};
  const SUMMARY_KEYS = ['totalHours', 'creditHours', 'honorHours', 'yearHours', 'yearQualified', 'starLevel', 'starLevelText', 'starSource', 'nextLevelHours', 'remainToNextLevel'];
  ok('summary 含契约字段 10 项', SUMMARY_KEYS.every((k) => k in b), `实测 keys=${keysOf(b)}`);

  // 追加 50 小时（跨星级阈值），验证累计增量与阈值推算
  const add = await call('lisi', 'POST', '/api/volunteer/records', {
    projectId: ctx.projectMain,
    userId: ctx.uid.wangxm,
    serviceDate: addDays(TODAY, -5),
    hours: 50,
    hourType: 'credit',
    source: 'platform',
    recorderSubject: 'center',
    makeupNote: `${TAG} 阈值推算用 50 小时`,
  });
  expectOk('POST /records 录入 50 小时（跨星级阈值）', add);
  const addId = d(add) && d(add).id !== undefined ? d(add).id : null;
  const addReview = await call('admin', 'POST', `/api/volunteer/records/${nid(addId)}/review`, { action: 'approve' });
  expectOk('POST /records/{id}/review 审核 50 小时记录（供星级阈值推算）', addReview);

  const after = await call('wangxm', 'GET', '/api/volunteer/summary/mine');
  expectOk('GET /summary/mine 追加后累计', after);
  trackStudent('summary/mine(追加后)', after);
  const a = d(after) || {};

  okNum('累计时长增量 = 新增记录时长（50）', Number(a.totalHours) - Number(b.totalHours), 50, 0.01, after);
  ok('yearQualified 与本学年 20 小时口径一致（S21 第三十三条：只提示不阻断）',
    (a.yearQualified === true || a.yearQualified === 1) === (Number(a.yearHours) >= 20),
    `实测 yearHours=${a.yearHours} yearQualified=${JSON.stringify(a.yearQualified)}`);
  okNum('creditHours + honorHours = totalHours（信用/荣誉分流）',
    Number(a.creditHours) + Number(a.honorHours), Number(a.totalHours), 0.01, after);

  const idx = starIndexByHours(a.totalHours);
  const nt = nextThreshold(a.totalHours);
  if (nt === null) {
    ok('已达五星阈值（≥500h）：nextLevelHours 为空或 500', a.nextLevelHours === null || a.nextLevelHours === undefined || Number(a.nextLevelHours) === 500 || Number(a.nextLevelHours) === 0,
      `实测 nextLevelHours=${JSON.stringify(a.nextLevelHours)} totalHours=${a.totalHours}`);
  } else {
    eq(`星级推算：nextLevelHours=${nt}（按 50/100/150/300/500，S21 第三十条）`, Number(a.nextLevelHours), nt, after);
    okNum(`星级推算：remainToNextLevel = ${nt} - 累计`, Number(a.remainToNextLevel), nt - Number(a.totalHours), 0.01, after);
  }
  if (idx > 0) {
    ok(`starLevelText 与累计 ${a.totalHours}h 所处档位一致（第 ${idx} 档，一~五星级志愿者）`,
      String(a.starLevelText || '').includes(STAR_CN[idx - 1]) || String(a.starLevel || '') === `star${idx}` || String(a.starLevel || '') === String(idx),
      `实测 starLevel=${JSON.stringify(a.starLevel)} starLevelText=${JSON.stringify(a.starLevelText)}`);
    ok('starSource 标注推算/登记出处（平台不替学校认定）', typeof a.starSource === 'string' && a.starSource.length > 0,
      `实测 starSource=${JSON.stringify(a.starSource)}`);
  } else {
    divergence('星级档位文案', `累计 ${a.totalHours}h 未达一星阈值，未断言 starLevelText 文案`);
  }

  // /summary/{userId}：管理员任意、辅导员仅名下、学生仅本人
  const sAdmin = await call('admin', 'GET', `/api/volunteer/summary/${nid(ctx.uid.wangxm)}`);
  expectOk('GET /api/volunteer/summary/{userId} 管理员查看任意学生', sAdmin);
  const sLisi = await call('lisi', 'GET', `/api/volunteer/summary/${nid(ctx.uid.wangxm)}`);
  expectOk('GET /summary/{userId} 辅导员查看名下学生', sLisi);
  const sLisiCross = await call('lisi', 'GET', `/api/volunteer/summary/${nid(ctx.uid.liusq)}`);
  expectReject('GET /summary/{userId} 负数：辅导员查看非名下学生（liusq）→ 403（§6）', sLisiCross, 403, '无权');
  const sStuOther = await call('wangxm', 'GET', `/api/volunteer/summary/${nid(ctx.uid.zhaoxh)}`);
  expectReject('GET /summary/{userId} 负数：学生查看他人累计 → 403', sStuOther, 403, '无权');
  trackStudent('summary/{userId} 越权（应被拒）', sStuOther);
  const sStuSelf = await call('wangxm', 'GET', `/api/volunteer/summary/${nid(ctx.uid.wangxm)}`);
  expectOk('GET /summary/{userId} 学生查看本人', sStuSelf);
  const sMiss = await call('admin', 'GET', '/api/volunteer/summary/99999999');
  expectReject('GET /summary/{userId} 负数：用户不存在 → 404', sMiss, 404, '不存在');

  // /stats
  const stats = await call('admin', 'GET', '/api/volunteer/stats');
  expectOk('GET /api/volunteer/stats 管理员统计', stats);
  const sd = d(stats);
  ok('统计返回非空对象（契约未规定字段名，见 README 歧义项）',
    !!sd && typeof sd === 'object' && Object.keys(sd).length > 0,
    `实测 keys=${keysOf(sd)}`);
  const stuStats = await call('wangxm', 'GET', '/api/volunteer/stats');
  expectReject('GET /api/volunteer/stats 负数：学生访问管理端统计 → 403', stuStats, 403, '无权');
  trackStudent('stats 越权（应被拒）', stuStats);
}

// ================================================================ 阶段 5：黑名单
async function phase5Blacklist() {
  section('阶段 5：志愿服务黑名单（6 个月到期推算，第三十四~三十六条）');

  const mk = await call('admin', 'POST', '/api/volunteer/blacklist', {
    userId: ctx.uid.zhaoxh,
    clause: 'c34_1',
    reason: `${TAG} 无故缺席致活动无法开展`,
  });
  expectOk('POST /api/volunteer/blacklist 管理员登记黑名单（c34_1）', mk);
  ctx.blacklist.main = d(mk) && d(mk).id !== undefined ? d(mk).id : null;
  const md = d(mk) || {};
  ok('黑名单响应含契约字段', ['id', 'userId', 'studentName', 'clause', 'clauseText', 'reason', 'listedAt', 'expireAt', 'daysLeft', 'status', 'statusText'].every((k) => k in md),
    `实测 keys=${keysOf(md)}`);
  eq('黑名单初始 status=active', md.status, 'active', mk);
  eq('黑名单 expireAt = 列入日 + 6 个月（S21 第三十六条，服务层按日期推算）',
    String(md.expireAt || '').slice(0, 10), addMonths(String(md.listedAt || TODAY).slice(0, 10), 6), mk);
  okNum('黑名单 daysLeft 与 expireAt 自洽', Number(md.daysLeft), dayDiff(String(md.listedAt || TODAY).slice(0, 10), String(md.expireAt || TODAY).slice(0, 10)), 1, mk);
  eq('黑名单 clauseText 为中文条款说明', /[\u4e00-\u9fa5]/.test(String(md.clauseText || '')), true, mk);

  const badClause = await call('admin', 'POST', '/api/volunteer/blacklist', { userId: ctx.uid.zhaoxh, clause: 'c99', reason: `${TAG} 非法条款` });
  expectReject('POST /blacklist 负数：clause 非 c34_1..c34_5/c35 → 400', badClause, 400, '只能是');
  const noReason = await call('admin', 'POST', '/api/volunteer/blacklist', { userId: ctx.uid.zhaoxh, clause: 'c34_2', reason: '' });
  expectReject('POST /blacklist 负数：reason 为空 → 400', noReason, 400, '不能为空');
  const missUser = await call('admin', 'POST', '/api/volunteer/blacklist', { userId: 99999999, clause: 'c34_3', reason: `${TAG} 用户不存在` });
  expectReject('POST /blacklist 负数：userId 不存在 → 404', missUser, 404, '不存在');
  // 队长裁定（2026-09-22）：学生可读本人黑名单条目（知情权 + 申诉窗口自 notify_time 起算，S21 第三十四条）；
  // 学生端 volunteer.html:1086 就是这条调用。原「学生一律 403」断言与页面验收要求冲突。
  const stuList = await call('wangxm', 'GET', '/api/volunteer/blacklist');
  expectOk('GET /api/volunteer/blacklist 学生可读（只回本人条目）', stuList);
  trackStudent('blacklist 学生读', stuList);
  const sbRows = rowsOf(d(stuList)) || [];
  ok('学生黑名单列表只含本人条目（无越界）',
    sbRows.every((r) => Number(r.userId) === Number(ctx.uid.wangxm)),
    `学生可见行数=${sbRows.length} 可见 userId=${[...new Set(sbRows.map((r) => r.userId))].join(',')}`);
  const stuMk = await call('wangxm', 'POST', '/api/volunteer/blacklist', { userId: ctx.uid.wangxm, clause: 'c34_1', reason: `${TAG} 自列` });
  expectReject('POST /blacklist 负数：学生自行登记黑名单 → 403', stuMk, 403, '无权');

  const list = await call('admin', 'GET', '/api/volunteer/blacklist');
  expectOk('GET /api/volunteer/blacklist 管理员名单', list);
  const rows = rowsOf(d(list));
  ok('黑名单列表返回数组或分页对象', rows !== null, `实测 data=${keysOf(d(list))}`);
  if (rows) ok('名单含本轮登记条目', rows.some((r) => r.id === ctx.blacklist.main), `实测行数=${rows.length}`);

  // 撤销 → revoked 终态
  const revoke = await call('admin', 'POST', `/api/volunteer/blacklist/${nid(ctx.blacklist.main)}/revoke`, { reason: `${TAG} 撤销` });
  expectOk('POST /api/volunteer/blacklist/{id}/revoke 撤销', revoke);
  const afterRevoke = await call('admin', 'GET', '/api/volunteer/blacklist');
  const rRows = rowsOf(d(afterRevoke)) || [];
  const hit = rRows.find((r) => r.id === ctx.blacklist.main);
  ok('撤销后 status=revoked', !!hit && hit.status === 'revoked', `实测 status=${hit ? hit.status : '(未找到)'} | ${describe(afterRevoke)}`);
  const reRevoke = await call('admin', 'POST', `/api/volunteer/blacklist/${nid(ctx.blacklist.main)}/revoke`, { reason: `${TAG} 重复撤销` });
  expectReject('POST /blacklist/{id}/revoke 负数：已 revoked 再撤销 → 400（CAS/终态）', reRevoke, 400);
  const missRevoke = await call('admin', 'POST', '/api/volunteer/blacklist/99999999/revoke', { reason: `${TAG} 不存在` });
  expectReject('POST /blacklist/{id}/revoke 负数：记录不存在 → 404', missRevoke, 404, '不存在');

  // 第二条：在册（供申诉窗口用）
  const mk2 = await call('admin', 'POST', '/api/volunteer/blacklist', { userId: ctx.uid.wangxm, clause: 'c35', reason: `${TAG} 未按要求开展工作` });
  expectOk('POST /blacklist 登记第二条（在册，供申诉用）', mk2);
  ctx.blacklist.appeal = d(mk2) && d(mk2).id !== undefined ? d(mk2).id : null;
  const stuOwn = await call('wangxm', 'GET', '/api/volunteer/blacklist');
  const soRows = rowsOf(d(stuOwn)) || [];
  ok('学生能看到本人被列入的条目（申诉窗口起算前已知情）',
    soRows.some((r) => Number(r.id) === Number(ctx.blacklist.appeal)),
    `学生可见行数=${soRows.length} 目标 id=${ctx.blacklist.appeal} status=${(soRows.find((r) => Number(r.id) === Number(ctx.blacklist.appeal)) || {}).status}`);
  ok('学生看到的黑名单行含 expireAt 与 clauseText（可自行推算申诉窗口）',
    soRows.every((r) => !!r.expireAt && !!r.clauseText),
    `实测 keys=${soRows.length ? keysOf(soRows[0]) : '(空)'}`);

  // 超窗申诉数据构造（需接口接受 notifyTime/listedAt）
  const mkOld = await call('admin', 'POST', '/api/volunteer/blacklist', {
    userId: ctx.uid.liusq,
    clause: 'c34_4',
    reason: `${TAG} 超窗申诉构造`,
    listedAt: addDays(TODAY, -40),
    notifyTime: `${addDays(TODAY, -15)} 09:00:00`,
  });
  expectOk('POST /blacklist 登记第三条（构造超窗申诉数据）', mkOld);
  const od = d(mkOld) || {};
  ctx.blacklist.old = od.id !== undefined ? od.id : null;
  const echoedNotify = String(od.notifyTime || '').slice(0, 10);
  ok('POST /blacklist 接受 notifyTime/listedAt 以承接历史数据（申诉窗口断言前置条件）',
    echoedNotify === addDays(TODAY, -15),
    `实测 notifyTime=${echoedNotify || '(空)'} 期望 ${addDays(TODAY, -15)} | ${describe(mkOld)}`);
}

// ================================================================ 阶段 6：申诉
async function phase6Appeal() {
  section('阶段 6：志愿服务申诉（5 个工作日窗口，第三十七条）');

  const ap = await call('wangxm', 'POST', '/api/volunteer/appeals', {
    blacklistId: ctx.blacklist.appeal,
    reason: `${TAG} 对黑名单认定有异议`,
  });
  expectOk('POST /api/volunteer/appeals 学生申诉（窗口内）', ap);
  ctx.appeal.main = d(ap) && d(ap).id !== undefined ? d(ap).id : null;
  const ad = d(ap) || {};
  ok('申诉响应含契约字段', ['id', 'userId', 'studentName', 'recordId', 'blacklistId', 'reason', 'submitTime', 'acceptOrg', 'withinWindow', 'deadline', 'status', 'statusText', 'ruling', 'rulingTime'].every((k) => k in ad),
    `实测 keys=${keysOf(ad)}`);
  ok('窗口内申诉 withinWindow=true', ad.withinWindow === true || ad.withinWindow === 1, `实测 withinWindow=${JSON.stringify(ad.withinWindow)}`);
  eq('申诉受理主体默认「团委」（第三十七条受理主体）', String(ad.acceptOrg || '').includes('团委'), true, ap);
  eq('申诉 deadline = 通知日起 5 个工作日', String(ad.deadline || '').slice(0, 10), addWorkdays(String(ad.submitTime || TODAY).slice(0, 10), 5), ap);
  eq('申诉初始 status=pending', ad.status, 'pending', ap);

  // 超窗申诉（依赖阶段 5 构造的历史 notifyTime）
  const oldNotify = addDays(TODAY, -15);
  const apOld = await call('liusq', 'POST', '/api/volunteer/appeals', { blacklistId: ctx.blacklist.old, reason: `${TAG} 超窗申诉` });
  if (ctx.blacklist.old) {
    expectReject('POST /appeals 边界：超过 5 个工作日窗口申诉 → 400（第三十七条）', apOld, 400, '工作日');
  } else {
    ok('POST /appeals 超窗断言：前置数据构造失败', false, describe(apOld));
  }
  divergence('申诉超窗断言的构造依赖', `本脚本通过 POST /blacklist 的 listedAt/notifyTime 构造历史数据（期望通知日 ${oldNotify}）；若接口不接受该参数需调整`);

  // 记录类申诉（recordId 二选一）
  const apRec = await call('wangxm', 'POST', '/api/volunteer/appeals', { recordId: ctx.record.r2, reason: `${TAG} 对时长记录认定有异议` });
  expectOk('POST /appeals 以 recordId 发起申诉（二选一）', apRec);
  ctx.appeal.record = d(apRec) && d(apRec).id !== undefined ? d(apRec).id : null;
  const neither = await call('wangxm', 'POST', '/api/volunteer/appeals', { reason: `${TAG} 未指定对象` });
  expectReject('POST /appeals 负数：recordId/blacklistId 均未指定 → 400', neither, 400, '不能为空');
  const noReason = await call('wangxm', 'POST', '/api/volunteer/appeals', { blacklistId: ctx.blacklist.appeal, reason: '' });
  expectReject('POST /appeals 负数：reason 为空 → 400', noReason, 400, '不能为空');
  const missTarget = await call('wangxm', 'POST', '/api/volunteer/appeals', { blacklistId: 99999999, reason: `${TAG} 对象不存在` });
  expectReject('POST /appeals 负数：申诉对象不存在 → 404', missTarget, 404, '不存在');

  // 名单与 scope
  const list = await call('admin', 'GET', '/api/volunteer/appeals');
  expectOk('GET /api/volunteer/appeals 管理员名单', list);
  const rows = rowsOf(d(list));
  ok('申诉列表返回数组或分页对象', rows !== null, `实测 data=${keysOf(d(list))}`);
  if (rows) ok('名单含本轮申诉', rows.some((r) => r.id === ctx.appeal.main), `实测行数=${rows.length}`);
  // 队长裁定（2026-09-22）：学生可读本人申诉（含裁定进展）；学生端 volunteer.html:1132 即此调用。
  const stuList = await call('wangxm', 'GET', '/api/volunteer/appeals');
  expectOk('GET /api/volunteer/appeals 学生可读（只回本人申诉）', stuList);
  trackStudent('appeals 学生读', stuList);
  const saRows = rowsOf(d(stuList)) || [];
  ok('学生申诉列表只含本人申诉（无越界）',
    saRows.every((r) => Number(r.userId) === Number(ctx.uid.wangxm)),
    `学生可见行数=${saRows.length} 可见 userId=${[...new Set(saRows.map((r) => r.userId))].join(',')}`);
  ok('学生能看到本人本轮申诉',
    saRows.some((r) => Number(r.id) === Number(ctx.appeal.main)),
    `实测行数=${saRows.length} 目标 id=${ctx.appeal.main}`);

  // 裁定
  const stuRule = await call('wangxm', 'POST', `/api/volunteer/appeals/${nid(ctx.appeal.main)}/rule`, { status: 'accepted', ruling: `${TAG} 学生无权裁定` });
  expectReject('POST /appeals/{id}/rule 负数：学生裁定 → 403', stuRule, 403, '无权');
  const rule = await call('admin', 'POST', `/api/volunteer/appeals/${nid(ctx.appeal.main)}/rule`, { status: 'accepted', ruling: `${TAG} 经团委受理，认定成立予以受理` });
  expectOk('POST /api/volunteer/appeals/{id}/rule 团委受理并裁定（accepted）', rule);
  const afterRule = await call('admin', 'GET', '/api/volunteer/appeals');
  const aRows = rowsOf(d(afterRule)) || [];
  const hit = aRows.find((r) => r.id === ctx.appeal.main);
  ok('裁定后 status=accepted 且 ruling/rulingTime 落库',
    !!hit && hit.status === 'accepted' && !!hit.ruling && !!hit.rulingTime,
    `实测 status=${hit ? hit.status : '(未找到)'} ruling=${hit ? JSON.stringify(hit.ruling) : '-'} | ${describe(afterRule)}`);
  const reRule = await call('admin', 'POST', `/api/volunteer/appeals/${nid(ctx.appeal.main)}/rule`, { status: 'rejected', ruling: `${TAG} 重复裁定` });
  expectReject('POST /appeals/{id}/rule 负数：已裁定申诉再裁定 → 400（终态不可再改）', reRule, 400);
  const badStatus = await call('admin', 'POST', `/api/volunteer/appeals/${nid(ctx.appeal.record)}/rule`, { status: 'done', ruling: `${TAG} 非法状态` });
  expectReject('POST /appeals/{id}/rule 负数：status 非 accepted/rejected → 400', badStatus, 400, '只能是');
  const missRule = await call('admin', 'POST', '/api/volunteer/appeals/99999999/rule', { status: 'accepted', ruling: `${TAG} 不存在` });
  expectReject('POST /appeals/{id}/rule 负数：申诉不存在 → 404', missRule, 404, '不存在');
}

// ================================================================ 阶段 7：社团台账
async function phase7Club() {
  section('阶段 7：学生社团台账（/api/club 台账与注销）');

  const body = {
    clubName: `${TAG} 志愿公益社`,
    category: '志愿公益类',
    attachUnit: `${TAG} 挂靠单位（校团委）`,
    foundDate: addDays(TODAY, -800),
    leaderName: `${TAG} 负责人`,
    advisorName: `${TAG} 指导老师`,
  };
  const created = await call('admin', 'POST', '/api/club', body);
  expectOk('POST /api/club 管理员新建社团', created);
  ctx.clubMain = d(created) && d(created).id !== undefined ? d(created).id : null;
  const cd = d(created) || {};
  ok('社团响应含契约字段', ['id', 'clubName', 'category', 'attachUnit', 'foundDate', 'leaderName', 'advisorName', 'status', 'statusText', 'memberCount'].every((k) => k in cd),
    `实测 keys=${keysOf(cd)}`);
  eq('新建社团 status=active', cd.status, 'active', created);
  ok('club_name 带 e2e-p17 标记（clean-p17.sql 据此清理）', hasText(created, 'e2e-p17'), describe(created));

  const noName = await call('admin', 'POST', '/api/club', { ...body, clubName: '' });
  expectReject('POST /api/club 负数：clubName 为空 → 400', noName, 400, '不能为空');
  const badCat = await call('admin', 'POST', '/api/club', { ...body, category: '电竞类' });
  expectReject('POST /api/club 负数：category 非六类中文类别 → 400', badCat, 400, '只能是');
  const stuCreate = await call('wangxm', 'POST', '/api/club', body);
  expectReject('POST /api/club 负数：学生新建社团 → 403', stuCreate, 403, '无权');

  // 注销用社团
  const drop = await call('admin', 'POST', '/api/club', { ...body, clubName: `${TAG} 待注销社团` });
  expectOk('POST /api/club 新建待注销社团', drop);
  ctx.clubDrop = d(drop) && d(drop).id !== undefined ? d(drop).id : null;

  const list = await call('admin', 'GET', '/api/club/list');
  expectOk('GET /api/club/list 列表', list);
  const rows = rowsOf(d(list));
  ok('社团列表返回数组或分页对象', rows !== null, `实测 data=${keysOf(d(list))}`);
  if (rows) ok('列表含本轮新建社团', rows.some((r) => r.id === ctx.clubMain), `实测行数=${rows.length}`);

  const detail = await call('wangxm', 'GET', `/api/club/${nid(ctx.clubMain)}`);
  expectOk('GET /api/club/{id} 详情（学生可见）', detail);
  const miss = await call('admin', 'GET', '/api/club/99999999');
  expectReject('GET /api/club/{id} 负数：不存在 → 404', miss, 404, '不存在');

  const put = await call('admin', 'PUT', `/api/club/${nid(ctx.clubMain)}`, { ...body, attachUnit: `${TAG} 挂靠单位（已调整）` });
  expectOk('PUT /api/club/{id} 修改台账', put);
  const stuPut = await call('wangxm', 'PUT', `/api/club/${nid(ctx.clubMain)}`, body);
  expectReject('PUT /api/club/{id} 负数：学生修改台账 → 403', stuPut, 403, '无权');
  const missPut = await call('admin', 'PUT', '/api/club/99999999', body);
  expectReject('PUT /api/club/{id} 负数：社团不存在 → 404', missPut, 404, '不存在');

  // 我的社团（加入成员后）
  const mineBefore = await call('wangxm', 'GET', '/api/club/list/mine');
  expectOk('GET /api/club/list/mine 我的社团（字面量路由优先于 {id}）', mineBefore);
  const mb = rowsOf(d(mineBefore));
  ok('我的社团返回数组或分页对象', mb !== null, `实测 data=${keysOf(d(mineBefore))}`);

  // 注销
  const dereg = await call('admin', 'POST', `/api/club/${nid(ctx.clubDrop)}/deregister`, { reason: `${TAG} 社团注销` });
  expectOk('POST /api/club/{id}/deregister 注销社团', dereg);
  const afterDereg = await call('admin', 'GET', `/api/club/${nid(ctx.clubDrop)}`);
  eq('注销后 status=deregistered（终态）', d(afterDereg) && d(afterDereg).status, 'deregistered', afterDereg);
  const reDereg = await call('admin', 'POST', `/api/club/${nid(ctx.clubDrop)}/deregister`, { reason: `${TAG} 重复注销` });
  expectReject('POST /club/{id}/deregister 负数：已注销社团重复注销 → 400（CAS/终态）', reDereg, 400);
  const stuDereg = await call('wangxm', 'POST', `/api/club/${nid(ctx.clubMain)}/deregister`, { reason: `${TAG} 学生注销` });
  expectReject('POST /club/{id}/deregister 负数：学生注销社团 → 403', stuDereg, 403, '无权');
}

// ================================================================ 阶段 8：社团成员
async function phase8Member() {
  section('阶段 8：社团成员（在册/角色/移除）');

  const noClub = await call('admin', 'GET', '/api/club/members');
  expectOk('GET /api/club/members 查询成员', noClub);
  const emptyRows = rowsOf(d(noClub));
  ok('成员查询返回数组或分页对象', emptyRows !== null, `实测 data=${keysOf(d(noClub))}`);

  const addMember = await call('admin', 'POST', '/api/club/members', { clubId: ctx.clubMain, userId: ctx.uid.wangxm, role: 'member', joinDate: TODAY });
  expectOk('POST /api/club/members 添加成员（member）', addMember);
  const addLeader = await call('admin', 'POST', '/api/club/members', { clubId: ctx.clubMain, userId: ctx.uid.zhaoxh, role: 'leader', joinDate: TODAY });
  expectOk('POST /api/club/members 添加负责人（leader）', addLeader);
  const addTemp = await call('admin', 'POST', '/api/club/members', { clubId: ctx.clubMain, userId: ctx.uid.chendw, role: 'member', joinDate: TODAY });
  expectOk('POST /api/club/members 添加待移除成员', addTemp);
  ctx.member.temp = d(addTemp) && d(addTemp).id !== undefined ? d(addTemp).id : null;

  const noClubId = await call('admin', 'POST', '/api/club/members', { userId: ctx.uid.chendw, role: 'member' });
  expectReject('POST /club/members 负数：缺 clubId → 400', noClubId, 400, '不能为空');
  const noUser = await call('admin', 'POST', '/api/club/members', { clubId: ctx.clubMain, role: 'member' });
  expectReject('POST /club/members 负数：缺 userId → 400', noUser, 400, '不能为空');
  const badRole = await call('admin', 'POST', '/api/club/members', { clubId: ctx.clubMain, userId: ctx.uid.chendw, role: 'president' });
  expectReject('POST /club/members 负数：role 非 leader/member → 400', badRole, 400, '只能是');
  const stuAdd = await call('wangxm', 'POST', '/api/club/members', { clubId: ctx.clubMain, userId: ctx.uid.wangxm, role: 'leader' });
  expectReject('POST /club/members 负数：学生自行加成员/自封负责人 → 403', stuAdd, 403, '无权');
  const missClub = await call('admin', 'POST', '/api/club/members', { clubId: 99999999, userId: ctx.uid.chendw, role: 'member' });
  expectReject('POST /club/members 负数：社团不存在 → 404', missClub, 404, '不存在');

  const members = await call('admin', 'GET', `/api/club/members?clubId=${nid(ctx.clubMain)}`);
  expectOk('GET /api/club/members?clubId= 按社团查询成员', members);
  const mRows = rowsOf(d(members)) || [];
  ok('成员列表含本轮添加的成员',
    [ctx.uid.wangxm, ctx.uid.zhaoxh].every((u) => mRows.some((r) => r.userId === u)),
    `实测行数=${mRows.length} | ${describe(members)}`);

  // 我的社团：加入后应出现
  const mineAfter = await call('wangxm', 'GET', '/api/club/list/mine');
  expectOk('GET /api/club/list/mine 加入后查询我的社团', mineAfter);
  const miRows = rowsOf(d(mineAfter)) || [];
  ok('学生可见自己所属社团（含角色）',
    miRows.some((r) => r.id === ctx.clubMain || r.clubId === ctx.clubMain),
    `实测行数=${miRows.length} | ${describe(mineAfter)}`);

  const del = await call('admin', 'DELETE', `/api/club/members/${nid(ctx.member.temp)}`);
  expectOk('DELETE /api/club/members/{id} 移除成员', del);
  const afterDel = await call('admin', 'GET', `/api/club/members?clubId=${nid(ctx.clubMain)}`);
  const dRows = rowsOf(d(afterDel)) || [];
  ok('移除后成员不再在册（quit_date 或删除）',
    !dRows.some((r) => r.userId === ctx.uid.chendw && (!r.quitDate || String(r.quitDate).length === 0)),
    `实测行数=${dRows.length} | ${describe(afterDel)}`);
  const stuDel = await call('wangxm', 'DELETE', `/api/club/members/${nid(ctx.member.temp)}`);
  expectReject('DELETE /club/members/{id} 负数：学生移除成员 → 403', stuDel, 403, '无权');
  const missDel = await call('admin', 'DELETE', '/api/club/members/99999999');
  expectReject('DELETE /club/members/{id} 负数：成员记录不存在 → 404', missDel, 404, '不存在');
}

// ================================================================ 阶段 9：社团年审
async function phase9Review() {
  section('阶段 9：社团年审（材料提报/挂靠单位意见/结果登记）');

  const body = {
    clubId: ctx.clubMain,
    schoolYear: weekYear,
    architectureMaterial: `${TAG} 组织架构合理性说明`,
    backboneMaterial: `${TAG} 骨干成员胜任力说明`,
    activityMaterial: `${TAG} 学期活动运行质量说明`,
    fundMaterial: `${TAG} 经费使用合规性说明`,
  };
  const created = await call('admin', 'POST', '/api/club/reviews', body);
  expectOk('POST /api/club/reviews 提报年审材料（四项材料）', created);
  ctx.reviewId = d(created) && d(created).id !== undefined ? d(created).id : null;
  const rd = d(created) || {};
  ok('年审响应含契约字段',
    ['id', 'clubId', 'clubName', 'schoolYear', 'architectureMaterial', 'backboneMaterial', 'activityMaterial', 'fundMaterial', 'attachOpinion', 'result', 'resultText', 'submitTime', 'reviewTime', 'publicityId'].every((k) => k in rd),
    `实测 keys=${keysOf(rd)}`);
  eq('新提报年审 result 为 null（未登记结论，平台不做判定）', rd.result === null || rd.result === undefined || rd.result === '', true, created);
  ok('architecture_material 带 e2e-p17 标记（clean-p17.sql 据此清理）', hasText(created, 'e2e-p17'), describe(created));

  const noClub = await call('admin', 'POST', '/api/club/reviews', { ...body, clubId: undefined });
  expectReject('POST /club/reviews 负数：缺 clubId → 400', noClub, 400, '不能为空');
  const badYear = await call('admin', 'POST', '/api/club/reviews', { ...body, schoolYear: '25-26' });
  expectReject('POST /club/reviews 负数：schoolYear 非「YYYY-YYYY」形式 → 400', badYear, 400);
  const stuCreate = await call('wangxm', 'POST', '/api/club/reviews', { ...body, clubId: ctx.clubMain });
  expectReject('POST /club/reviews 负数：学生提报年审 → 403', stuCreate, 403, '无权');
  const missClub = await call('admin', 'POST', '/api/club/reviews', { ...body, clubId: 99999999 });
  expectReject('POST /club/reviews 负数：社团不存在 → 404', missClub, 404, '不存在');

  const list = await call('admin', 'GET', '/api/club/reviews');
  expectOk('GET /api/club/reviews 年审列表', list);
  const rows = rowsOf(d(list));
  ok('年审列表返回数组或分页对象', rows !== null, `实测 data=${keysOf(d(list))}`);
  if (rows) ok('列表含本轮年审', rows.some((r) => r.id === ctx.reviewId), `实测行数=${rows.length}`);
  // 队长裁定：年审结果属公示口径 → 学生可读，但只能看到本人在册社团的行
  const stuList = await call('wangxm', 'GET', '/api/club/reviews');
  expectOk('GET /club/reviews 学生可读（年审结果属公示口径）', stuList);
  trackStudent('club/reviews 学生读', stuList);
  const stuMine = await call('wangxm', 'GET', '/api/club/list/mine');
  const myClubIds = new Set((rowsOf(d(stuMine)) || []).map((r) => Number(r.id)));
  const stuRows = rowsOf(d(stuList)) || [];
  ok('学生年审列表只含本人在册社团的行（无越界）',
    stuRows.every((r) => myClubIds.has(Number(r.clubId))),
    `学生可见行数=${stuRows.length} 在册社团=${[...myClubIds].join(',')} 越界行=${JSON.stringify(stuRows.filter((r) => !myClubIds.has(Number(r.clubId))).map((r) => ({ id: r.id, clubId: r.clubId, clubName: r.clubName })))}`);
  ok('学生年审列表能看到本轮本社团的年审行',
    stuRows.some((r) => r.id === ctx.reviewId),
    `实测行数=${stuRows.length}`);

  // 挂靠单位意见
  const opinion = await call('admin', 'POST', `/api/club/reviews/${nid(ctx.reviewId)}/opinion`, { attachOpinion: `${TAG} 挂靠单位意见：材料齐备，同意上报` });
  expectOk('POST /api/club/reviews/{id}/opinion 登记挂靠单位意见', opinion);
  const emptyOpinion = await call('admin', 'POST', `/api/club/reviews/${nid(ctx.reviewId)}/opinion`, { attachOpinion: '' });
  expectReject('POST /reviews/{id}/opinion 负数：意见为空 → 400', emptyOpinion, 400, '不能为空');
  const missOpinion = await call('admin', 'POST', '/api/club/reviews/99999999/opinion', { attachOpinion: `${TAG} 不存在` });
  expectReject('POST /reviews/{id}/opinion 负数：年审记录不存在 → 404', missOpinion, 404, '不存在');

  // 提交年审
  const submit = await call('admin', 'POST', `/api/club/reviews/${nid(ctx.reviewId)}/submit`, {});
  expectOk('POST /api/club/reviews/{id}/submit 提交年审', submit);
  const reSubmit = await call('admin', 'POST', `/api/club/reviews/${nid(ctx.reviewId)}/submit`, {});
  expectReject('POST /reviews/{id}/submit 负数：重复提交 → 400（CAS）', reSubmit, 400);

  // 结果登记：辅导员不能登记（§6）
  const tResult = await call('lisi', 'POST', `/api/club/reviews/${nid(ctx.reviewId)}/result`, { result: 'qualified' });
  expectReject('POST /reviews/{id}/result 负数：辅导员登记年审结果 → 403（§6 辅导员不能登记年审结果）', tResult, 403, '无权');
  const badResult = await call('admin', 'POST', `/api/club/reviews/${nid(ctx.reviewId)}/result`, { result: 'pass' });
  expectReject('POST /reviews/{id}/result 负数：result 非 qualified/unqualified/deregistered → 400', badResult, 400, '只能是');
  const stuResult = await call('wangxm', 'POST', `/api/club/reviews/${nid(ctx.reviewId)}/result`, { result: 'qualified' });
  expectReject('POST /reviews/{id}/result 负数：学生登记年审结果 → 403', stuResult, 403, '无权');

  const result = await call('admin', 'POST', `/api/club/reviews/${nid(ctx.reviewId)}/result`, { result: 'qualified' });
  expectOk('POST /api/club/reviews/{id}/result 管理员登记年审结果（qualified）', result);
  const afterResult = await call('admin', 'GET', '/api/club/reviews');
  const aRows = rowsOf(d(afterResult)) || [];
  const hit = aRows.find((r) => r.id === ctx.reviewId);
  ok('登记后 result=qualified 且 resultText 为中文',
    !!hit && hit.result === 'qualified' && /[\u4e00-\u9fa5]/.test(String(hit.resultText || '')),
    `实测 result=${hit ? JSON.stringify(hit.result) : '(未找到)'} resultText=${hit ? JSON.stringify(hit.resultText) : '-'}`);
  const reResult = await call('admin', 'POST', `/api/club/reviews/${nid(ctx.reviewId)}/result`, { result: 'unqualified' });
  expectReject('POST /reviews/{id}/result 负数：结果登记后重设 → 400（§7 终态不可再改）', reResult, 400);
  const missResult = await call('admin', 'POST', '/api/club/reviews/99999999/result', { result: 'qualified' });
  expectReject('POST /reviews/{id}/result 负数：年审记录不存在 → 404', missResult, 404, '不存在');
}

// ================================================================ 阶段 10：星级评定
async function phase10Rating() {
  section('阶段 10：社团星级评定（三/四/五星级登记，平台不自动评级）');

  const body = {
    clubId: ctx.clubMain,
    schoolYear: weekYear,
    starLevel: 'star5',
    businessUnit: `${TAG} 业务指导单位（校团委社团管理部）`,
    basisNote: `${TAG} 依据：校园文化传承、活动策划执行、社会实践参与三项表现突出`,
  };
  const created = await call('admin', 'POST', '/api/club/ratings', body);
  expectOk('POST /api/club/ratings 管理员登记星级（star5）', created);
  ctx.ratingId = d(created) && d(created).id !== undefined ? d(created).id : null;
  const rd = d(created) || {};
  ok('星级响应含契约字段（含 businessUnit 业务指导单位，与挂靠单位不互换）',
    ['id', 'clubId', 'clubName', 'schoolYear', 'starLevel', 'starLevelText', 'businessUnit', 'basisNote', 'publicityId', 'registerTime'].every((k) => k in rd),
    `实测 keys=${keysOf(rd)}`);
  ok('starLevelText 为中文星级文案', /[\u4e00-\u9fa5]/.test(String(rd.starLevelText || '')), `实测 starLevelText=${JSON.stringify(rd.starLevelText)}`);
  ok('basis_note 带 e2e-p17 标记（clean-p17.sql 据此清理）', hasText(created, 'e2e-p17'), describe(created));

  const noBasis = await call('admin', 'POST', '/api/club/ratings', { ...body, basisNote: '' });
  expectReject('POST /club/ratings 负数：basisNote 必填为空 → 400', noBasis, 400, '不能为空');
  const badStar = await call('admin', 'POST', '/api/club/ratings', { ...body, starLevel: 'star6' });
  expectReject('POST /club/ratings 负数：starLevel 非 star3/star4/star5 → 400', badStar, 400, '只能是');
  const tRating = await call('lisi', 'POST', '/api/club/ratings', body);
  expectReject('POST /club/ratings 负数：辅导员定星级 → 403（§6 辅导员不能定星级）', tRating, 403, '无权');
  const stuRating = await call('wangxm', 'POST', '/api/club/ratings', body);
  expectReject('POST /club/ratings 负数：学生定星级 → 403', stuRating, 403, '无权');
  const missClub = await call('admin', 'POST', '/api/club/ratings', { ...body, clubId: 99999999 });
  expectReject('POST /club/ratings 负数：社团不存在 → 404', missClub, 404, '不存在');

  const list = await call('admin', 'GET', '/api/club/ratings');
  expectOk('GET /api/club/ratings 星级列表', list);
  const rows = rowsOf(d(list));
  ok('星级列表返回数组或分页对象', rows !== null, `实测 data=${keysOf(d(list))}`);
  if (rows) ok('列表含本轮登记星级', rows.some((r) => r.id === ctx.ratingId), `实测行数=${rows.length}`);

  // 队长裁定：星级属公示口径 → 学生可读，但只能看到本人在册社团的行
  const stuList = await call('wangxm', 'GET', '/api/club/ratings');
  expectOk('GET /club/ratings 学生可读（星级属公示口径）', stuList);
  trackStudent('club/ratings 学生读', stuList);
  const stuMine = await call('wangxm', 'GET', '/api/club/list/mine');
  const myClubIds = new Set((rowsOf(d(stuMine)) || []).map((r) => Number(r.id)));
  const stuRows = rowsOf(d(stuList)) || [];
  ok('学生星级列表只含本人在册社团的行（无越界）',
    stuRows.every((r) => myClubIds.has(Number(r.clubId))),
    `学生可见行数=${stuRows.length} 在册社团=${[...myClubIds].join(',')} 越界行=${JSON.stringify(stuRows.filter((r) => !myClubIds.has(Number(r.clubId))).map((r) => ({ id: r.id, clubId: r.clubId, clubName: r.clubName })))}`);
  ok('学生星级列表能看到本轮本社团的星级行',
    stuRows.some((r) => r.id === ctx.ratingId),
    `实测行数=${stuRows.length}`);

  const put = await call('admin', 'PUT', `/api/club/ratings/${nid(ctx.ratingId)}`, { ...body, starLevel: 'star4', basisNote: `${TAG} 复核后调整为四星级` });
  expectOk('PUT /api/club/ratings/{id} 修改星级登记', put);
  const tPut = await call('lisi', 'PUT', `/api/club/ratings/${nid(ctx.ratingId)}`, { ...body, starLevel: 'star3' });
  expectReject('PUT /club/ratings/{id} 负数：辅导员修改星级 → 403', tPut, 403, '无权');
  const missPut = await call('admin', 'PUT', '/api/club/ratings/99999999', body);
  expectReject('PUT /club/ratings/{id} 负数：登记不存在 → 404', missPut, 404, '不存在');
}

// ================================================================ 阶段 11：公示
async function phase11Publicity() {
  section('阶段 11：社团年审/星级公示（创建、明细、关闭，重复关闭必拒）');

  const body = {
    publicityType: 'club_rating',
    title: `${TAG} 2025-2026 学年学生社团星级评定结果公示`,
    content: `${TAG} 依据 S25b 公示 5 天，异议请发 twh@xujc.com`,
    startDate: TODAY,
    endDate: addDays(TODAY, 4),
    contactEmail: 'twh@xujc.com',
  };
  const created = await call('admin', 'POST', '/api/club/publicity', body);
  expectOk('POST /api/club/publicity 创建公示（club_rating）', created);
  ctx.pubId = d(created) && d(created).id !== undefined ? d(created).id : null;
  const pd = d(created) || {};
  ok('公示响应含契约字段', ['id', 'publicityType', 'publicityTypeText', 'title', 'content', 'startDate', 'endDate', 'contactEmail', 'status', 'statusText', 'items'].every((k) => k in pd),
    `实测 keys=${keysOf(pd)}`);
  eq('公示初始 status=open', pd.status, 'open', created);
  eq('公示期 5 天（S25b：2026-07-07~07-11）', dayDiff(String(pd.startDate).slice(0, 10), String(pd.endDate).slice(0, 10)), 4, created);
  eq('异议邮箱默认 twh@xujc.com', String(pd.contactEmail || ''), 'twh@xujc.com', created);
  ok('items 为公示明细数组', Array.isArray(pd.items), `实测 items 类型=${typeof pd.items} 值=${JSON.stringify(pd.items).slice(0, 200)}`);
  if (Array.isArray(pd.items)) {
    ok('公示明细含本轮社团（社团名/星级或结果/单位/类别）',
      pd.items.some((it) => JSON.stringify(it).includes('e2e-p17')),
      `实测 items=${JSON.stringify(pd.items).slice(0, 300)}`);
    ok('公示明细不含学号（明细只有社团维度，不落学生信息）',
      !/\b\d{8,}\b/.test(JSON.stringify(pd.items)),
      `实测 items=${JSON.stringify(pd.items).slice(0, 200)}`);
  }

  const noTitle = await call('admin', 'POST', '/api/club/publicity', { ...body, title: '' });
  expectReject('POST /club/publicity 负数：title 为空 → 400', noTitle, 400, '不能为空');
  const badType = await call('admin', 'POST', '/api/club/publicity', { ...body, publicityType: 'club_star' });
  expectReject('POST /club/publicity 负数：publicityType 非 club_review/club_rating → 400', badType, 400, '只能是');
  const badRange = await call('admin', 'POST', '/api/club/publicity', { ...body, startDate: TODAY, endDate: addDays(TODAY, -1) });
  expectReject('POST /club/publicity 负数：结束日期早于起始日期 → 400', badRange, 400);
  const stuCreate = await call('wangxm', 'POST', '/api/club/publicity', body);
  expectReject('POST /club/publicity 负数：学生创建公示 → 403', stuCreate, 403, '无权');

  // 年审类公示（publicityType=club_review）
  const reviewPub = await call('admin', 'POST', '/api/club/publicity', {
    ...body,
    publicityType: 'club_review',
    title: `${TAG} 2025-2026 学年学生社团年审结果公示`,
  });
  expectOk('POST /club/publicity 创建年审结果公示（club_review）', reviewPub);
  ctx.pubReviewId = d(reviewPub) && d(reviewPub).id !== undefined ? d(reviewPub).id : null;

  const list = await call('admin', 'GET', '/api/club/publicity');
  expectOk('GET /api/club/publicity 公示列表', list);
  const rows = rowsOf(d(list));
  ok('公示列表返回数组或分页对象', rows !== null, `实测 data=${keysOf(d(list))}`);
  if (rows) ok('列表含本轮公示', rows.some((r) => r.id === ctx.pubId), `实测行数=${rows.length}`);

  const detail = await call('admin', 'GET', `/api/club/publicity/${nid(ctx.pubId)}`);
  expectOk('GET /api/club/publicity/{id} 公示详情', detail);
  const miss = await call('admin', 'GET', '/api/club/publicity/99999999');
  expectReject('GET /club/publicity/{id} 负数：不存在 → 404', miss, 404, '不存在');

  // 学生可见性：open 公示全站可见（§6），且不含学生隐私
  const stuList = await call('wangxm', 'GET', '/api/club/publicity');
  expectOk('GET /api/club/publicity 学生查询（open 公示全站可见）', stuList);
  trackStudent('publicity 学生查询', stuList);
  const sRows = rowsOf(d(stuList)) || [];
  ok('学生可见本轮 open 公示',
    sRows.some((r) => r.id === ctx.pubId && r.status === 'open'),
    `实测行数=${sRows.length} | ${describe(stuList)}`);
  ok('学生可见公示不含完整学号',
    !Object.values(ctx.studentNo).filter(Boolean).some((no) => stuList.text.includes(no)),
    `已知学号=${JSON.stringify(ctx.studentNo)}`);
  const stuDetail = await call('wangxm', 'GET', `/api/club/publicity/${nid(ctx.pubId)}`);
  expectOk('GET /club/publicity/{id} 学生看详情', stuDetail);
  trackStudent('publicity 学生详情', stuDetail);

  // 关闭 → 重复关闭必拒（§7 不做幂等 200）
  const close = await call('admin', 'POST', `/api/club/publicity/${nid(ctx.pubId)}/close`, {});
  expectOk('POST /api/club/publicity/{id}/close 关闭公示', close);
  const afterClose = await call('admin', 'GET', `/api/club/publicity/${nid(ctx.pubId)}`);
  eq('关闭后 status=closed', d(afterClose) && d(afterClose).status, 'closed', afterClose);
  const reClose = await call('admin', 'POST', `/api/club/publicity/${nid(ctx.pubId)}/close`, {});
  expectReject('POST /club/publicity/{id}/close 负数：closed 公示重复关闭 → 400（§7 明确不做幂等 200）', reClose, 400);
  const stuClose = await call('wangxm', 'POST', `/api/club/publicity/${nid(ctx.pubReviewId)}/close`, {});
  expectReject('POST /club/publicity/{id}/close 负数：学生关闭公示 → 403', stuClose, 403, '无权');
  const missClose = await call('admin', 'POST', '/api/club/publicity/99999999/close', {});
  expectReject('POST /club/publicity/{id}/close 负数：公示不存在 → 404', missClose, 404, '不存在');

  const closeReviewPub = await call('admin', 'POST', `/api/club/publicity/${nid(ctx.pubReviewId)}/close`, {});
  expectOk('POST /club/publicity/{id}/close 关闭年审公示', closeReviewPub);
}

// ================================================================ 阶段 12：导出与脱敏
async function phase12Export() {
  section('阶段 12：统计导出（CSV）与脱敏（不含完整学号与联系方式）');

  // ---- 志愿服务统计导出
  const v = await rawGet('admin', '/api/volunteer/stats/export');
  expectHttp('GET /api/volunteer/stats/export 管理员导出 HTTP 200', v, 200);
  const vct = String(v.headers.get('content-type') || '').toLowerCase();
  ok('志愿服务导出 Content-Type 为 CSV', vct.includes('csv'), `实测 content-type=${vct}`);
  const vBom = v.buf.length >= 3 && v.buf[0] === 0xef && v.buf[1] === 0xbb && v.buf[2] === 0xbf;
  ok('志愿服务导出带 UTF-8 BOM（Excel 中文兼容）', vBom, `实测前 3 字节=${[...v.buf.slice(0, 3)].map((x) => x.toString(16)).join(' ')}`);
  const vcd = String(v.headers.get('content-disposition') || '');
  ok('志愿服务导出带 content-disposition 且为 .csv 附件', /\.csv/i.test(vcd), `实测=${vcd}`);
  const vcsv = v.text.replace(/^\uFEFF/, '');
  const vLines = vcsv.split(/\r?\n/).filter((l) => l.length > 0);
  ok('志愿服务导出含表头与数据行', vLines.length >= 2, `实测行数=${vLines.length} 表头=${JSON.stringify((vLines[0] || '').slice(0, 160))}`);
  const vNo = ctx.studentNo.wangxm;
  if (vNo) {
    ok(`志愿服务导出不含完整学号（脱敏为后四位，S21/§12.2-7）`, !vcsv.includes(vNo), `完整学号=${vNo}`);
    ok('志愿服务导出含学号后四位', vcsv.includes(String(vNo).slice(-4)), `后四位=${String(vNo).slice(-4)}`);
  } else {
    divergence('志愿服务导出台账学号', '未取得学生学号，跳过「不含完整学号/含后四位」两条断言');
  }
  ok('志愿服务导出不含身份证号', !/\d{17}[\dXx]/.test(vcsv), '匹配 \\d{17}[\\dXx]');
  ok('志愿服务导出不含手机号', !/(?<!\d)1[3-9]\d{9}(?!\d)/.test(vcsv), '匹配大陆手机号 11 位');
  ok('志愿服务导出无联系方式表头或字段', !/身份证|手机|电话|联系方式|contact|idCard|phone/i.test(vcsv), '匹配联系方式列名');

  const vStu = await rawGet('wangxm', '/api/volunteer/stats/export');
  expectDenied('GET /volunteer/stats/export 负数：学生导出被拒（403）', vStu);
  ok('学生导出被拒时响应体为空（不回数据）', vStu.buf.length === 0, `实测 body 字节数=${vStu.buf.length}`);
  const vForged = await rawGet('forged', '/api/volunteer/stats/export');
  expectDenied('GET /volunteer/stats/export 负数：伪造会话被拒', vForged);

  // ---- 社团统计导出
  const c = await rawGet('admin', '/api/club/stats/export');
  expectHttp('GET /api/club/stats/export 管理员导出 HTTP 200', c, 200);
  const cct = String(c.headers.get('content-type') || '').toLowerCase();
  ok('社团导出 Content-Type 为 CSV', cct.includes('csv'), `实测 content-type=${cct}`);
  const cBom = c.buf.length >= 3 && c.buf[0] === 0xef && c.buf[1] === 0xbb && c.buf[2] === 0xbf;
  ok('社团导出带 UTF-8 BOM', cBom, `实测前 3 字节=${[...c.buf.slice(0, 3)].map((x) => x.toString(16)).join(' ')}`);
  const ccd = String(c.headers.get('content-disposition') || '');
  ok('社团导出带 content-disposition 且为 .csv 附件', /\.csv/i.test(ccd), `实测=${ccd}`);
  const ccsv = c.text.replace(/^\uFEFF/, '');
  const cLines = ccsv.split(/\r?\n/).filter((l) => l.length > 0);
  ok('社团导出含表头与数据行', cLines.length >= 2, `实测行数=${cLines.length} 表头=${JSON.stringify((cLines[0] || '').slice(0, 160))}`);
  ok('社团导出含本轮自建社团（含星级/年审结果维度）', ccsv.includes(`e2e-p17`) || ccsv.includes(weekYear), `标记或学年存在性检查`);
  ok('社团导出不含完整学号', !Object.values(ctx.studentNo).filter(Boolean).some((no) => ccsv.includes(no)), `已知学号=${JSON.stringify(ctx.studentNo)}`);
  ok('社团导出不含手机号', !/(?<!\d)1[3-9]\d{9}(?!\d)/.test(ccsv), '匹配大陆手机号 11 位');
  ok('社团导出不含邮箱等联系方式', !/[\w.+-]+@[\w-]+\.[\w.]+/.test(ccsv), '匹配邮箱');
  ok('社团导出不含身份证号', !/\d{17}[\dXx]/.test(ccsv), '匹配 \\d{17}[\\dXx]');

  const cStu = await rawGet('wangxm', '/api/club/stats/export');
  expectDenied('GET /club/stats/export 负数：学生导出被拒（403）', cStu);
  ok('社团导出学生被拒时响应体为空', cStu.buf.length === 0, `实测 body 字节数=${cStu.buf.length}`);
  const cForged = await rawGet('forged', '/api/club/stats/export');
  expectDenied('GET /club/stats/export 负数：伪造会话被拒', cForged);
}

// ================================================================ 阶段 13：学生端泄露扫描
async function phase13LeakScan() {
  section('阶段 13：学生端响应泄露扫描（他人学号 / 他人记录）');
  const others = Object.entries(ctx.studentNo)
    .filter(([acc]) => acc !== 'wangxm' && acc !== 'admin' && acc !== 'lisi' && acc !== 'zhangsan')
    .map(([, no]) => no)
    .filter(Boolean);
  const uniq = [...new Set(others)];
  if (uniq.length === 0) {
    divergence('学生端泄露扫描', '未取到其他学生学号，无法扫描他人学号泄露');
    return;
  }
  for (const cap of stuCaptures) {
    const leak = uniq.filter((no) => cap.text.includes(no));
    ok(`学生响应「${cap.label}」不含他人学号`, leak.length === 0, `泄露学号=${leak.join(',')}`);
  }
}

// ================================================================ 主流程
const phases = [
  ['0', phase0Login],
  ['1', phase1Project],
  ['2', phase2Enrollment],
  ['3', phase3Record],
  ['4', phase4Summary],
  ['5', phase5Blacklist],
  ['6', phase6Appeal],
  ['7', phase7Club],
  ['8', phase8Member],
  ['9', phase9Review],
  ['10', phase10Rating],
  ['11', phase11Publicity],
  ['12', phase12Export],
  ['13', phase13LeakScan],
];

function phaseSummary() {
  section('路由覆盖矩阵（48 条）');
  const uncovered = [];
  for (const r of ROUTES) {
    const hit = covered.has(r);
    if (!hit) uncovered.push(r);
    emit(`  ${hit ? '✅' : '❌'} ${r}${hit ? `   [${coveredPhase.get(r) || '-'}]` : ''}`);
  }
  const extra = [...covered].filter((k) => !ROUTES.includes(k));
  const coveredCount = ROUTES.length - uncovered.length;
  emit('');
  emit(`  路由覆盖：${coveredCount}/${ROUTES.length} 全部调用${uncovered.length ? `（未覆盖 ${uncovered.length} 条）` : '（无遗漏）'}`);
  if (extra.length) emit(`  额外调用（不在 48 条清单内，如 ?mine=1 变体）：${extra.join('、')}`);

  section('断言结果');
  emit(`  PASS ${pass} / FAIL ${fail}`);

  if (divergences.length) {
    section('契约缺口与口径记录（不判失败，供实现端确认）');
    for (const x of divergences) emit(`  NOTE  ${x}`);
  }

  section('清理与口径');
  emit('  清理本轮自建数据（幂等，按 e2e-p17 文本标记删除，演示数据 90001-91044 不受影响）：');
  emit('    cmd /c "mysql --defaults-extra-file=%TEMP%\\nest_my.cnf -D nest -t < target\\clean-p17.sql"');
  emit('  数据隔离：本轮所有 项目/记录/黑名单/申诉/社团/成员/年审/星级/公示 均挂在自建 e2e-p17* 实体上，演示数据只读。');
  emit('  判成功口径：后端所有接口 HTTP 200，业务码取响应体 json.code（common/Result.java 的 success() = 200），失败给中文 message。');
  emit(`  本轮标记：${TAG}`);

  section('PROCESS EXIT');
  emit(`  ${fail === 0 && !fatal ? 0 : 1}`);
}

async function main() {
  emit('================================================================');
  emit('  P17 校园文化 端到端验收（志愿服务时长认证 + 学生社团年审/星级评定）');
  emit(`  目标后端：${BASE}`);
  emit(`  本轮标记：${TAG}    运行日期：${TODAY}`);
  emit('  依据：target/p17-plan.md（48 路由/字段/权限/状态机）、target/p17-spec.md §12、sql/nest_volunteer.sql、sql/nest_club.sql');
  emit('================================================================');

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
  phaseSummary();

  if (fail > 0) {
    section('失败明细');
    for (const f of failures) emit(`  FAIL  ${f}`);
  }

  const text = `${lines.join('\n')}\n`;
  const allGreen = fail === 0 && !fatal;
  const full = allGreen ? `${text}\nALL PASS ${pass} / FAIL 0\n` : `${text}\nALL PASS ${pass} / FAIL ${fail}\n`;

  try {
    fs.mkdirSync(path.dirname(LOG_PATH), { recursive: true });
    fs.writeFileSync(LOG_PATH, full, 'utf8');
    if (allGreen) fs.writeFileSync(FINAL_LOG_PATH, full, 'utf8');
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

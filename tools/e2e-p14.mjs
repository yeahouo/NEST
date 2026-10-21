#!/usr/bin/env node
/**
 * P14「心理健康」端到端接口验证脚本（纯 HTTP，无 CDP / 无浏览器）
 *
 * 用法：
 *   node tools/e2e-p14.mjs
 *   node tools/e2e-p14.mjs --base http://127.0.0.1:8080
 *
 * 断言口径（与 PsycheController javadoc 一致，**不看 HTTP 状态码**）：
 *   所有 /api/psyche/** 路由 HTTP 一律 200，业务结果在 {code,message,data}：
 *     code=200 成功、401 未登录、403 越权、400 参数非法（中文文案）、404 不存在。
 *   ⚠ 注意：任务书里写的「code=0 成功」与本仓库实际不符——本仓库 Result.success() 回 code=200
 *     （common/Result.java:21 / :29），且 P13 的 e2e 脚本同样断言 code===200。脚本按实际代码断言。
 *   唯一例外：GET /api/psyche/export 是 CSV 字节流（text/csv;charset=UTF-8 + UTF-8 BOM
 *     + Content-Disposition attachment），失败时回裸状态码（未登录 401 / 非教职工 403 / 类型非法 400）。
 *
 * 覆盖：控制器全部 32 条方法级路由（PsycheController 共 33 处 *Mapping，其中 1 处是类级
 *   @RequestMapping("/api/psyche")）逐条触碰 + 负数用例（未登录 / 越权 / 非法状态迁移 /
 *   不存在 id / 参数非法 / 提前不足 1 个工作日 / 非服务时段 / 未答完 / 重复续约等）。
 *
 * 可重复跑：只读演示数据（算子见 sql/nest_psyche_work_demo.sql：量表 9001-9009、批次 9101-9109、
 *   名单 91001-91099、关注 93001+、预约 95001+、转介 96001+），写操作全部落在
 *   RUN_TAG 唯一后缀的新数据上（新量表 code / 新批次名 / 新关注的 summary），
 *   不删改任何演示数据行；自己造的预约都在本节内收敛（cancel/complete），避免阻塞下一轮 create。
 */
import { Buffer } from 'node:buffer';

// ---------------------------------------------------------------- 参数
const argv = process.argv.slice(2);
let BASE = 'http://127.0.0.1:8080';
for (let i = 0; i < argv.length; i += 1) {
  if (argv[i] === '--base' && argv[i + 1]) {
    BASE = argv[i + 1].replace(/\/+$/, '');
    i += 1;
  } else if (argv[i].startsWith('--base=')) {
    BASE = argv[i].slice('--base='.length).replace(/\/+$/, '');
  }
}
const RUN_TAG = 'e2e-p14-' + Date.now().toString(36);

// ---------------------------------------------------------------- 断言框架（沿用 target/e2e-p13.mjs 风格）
let pass = 0;
const fails = [];
function check(label, cond, extra) {
  if (cond) {
    pass += 1;
    console.log('PASS  ' + label);
  } else {
    const suffix = extra ? ' :: ' + String(extra).slice(0, 400) : '';
    fails.push(label + suffix);
    console.log('FAIL  ' + label + suffix);
  }
}
function section(title) {
  console.log('\n=== ' + title + ' ===');
}
const code = (r) => (r.json ? r.json.code : undefined);
const data = (r) => (r.json ? r.json.data : null);
const msg = (r) => (r.json ? r.json.message : String(r.text || '').slice(0, 200));
const arr = (v) => (Array.isArray(v) ? v : (v && Array.isArray(v.list) ? v.list : []));
const brief = (r) => {
  const body = r.json ? JSON.stringify(r.json) : String(r.text || '');
  return body.slice(0, 400);
};
const near = (a, b) => Math.abs(Number(a) - Number(b)) < 0.0001;

// ---------------------------------------------------------------- 账号（sql/nest_psyche_work_demo.sql 的真实归属）
//   学生：wangxm=4 / zhaoxh=5 / chendw=6（辅导员 3=lisi）；liusq=7 / zhoujie=8（辅导员 2=zhangsan）
//   同一账号第二次登录会让上一个会话失效 ⇒ 每个角色只登录一次、复用 cookie。
const accounts = {
  admin: 'admin',
  lisi: 'lisi',
  zhangsan: 'zhangsan',
  chendw: 'chendw',
  wangxm: 'wangxm',
  liusq: 'liusq',
  zhaoxh: 'zhaoxh',
  zhoujie: 'zhoujie',
};
const sid = {};

async function login(who) {
  const res = await fetch(BASE + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: accounts[who], password: '123456' }),
    redirect: 'manual',
  });
  const raw = res.headers.get('set-cookie') || '';
  const matched = raw.match(/JSESSIONID=([A-Za-z0-9]+)/);
  sid[who] = matched ? matched[1] : null;
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch (e) { /* 登录页 HTML */ }
  check('登录 ' + who, !!sid[who] && !!json && json.code === 200, JSON.stringify(json || text.slice(0, 160)));
}

async function api(who, method, path, body) {
  const headers = {};
  if (who && sid[who]) headers.Cookie = 'JSESSIONID=' + sid[who];
  const init = { method, headers, redirect: 'manual' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  const res = await fetch(BASE + path, init);
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch (e) { /* CSV / 空体 */ }
  return { http: res.status, json, text, headers: res.headers };
}

async function raw(who, path) {
  const headers = {};
  if (who && sid[who]) headers.Cookie = 'JSESSIONID=' + sid[who];
  const res = await fetch(BASE + path, { headers, redirect: 'manual' });
  const buf = Buffer.from(await res.arrayBuffer());
  return { http: res.status, buf, text: buf.toString('utf8'), headers: res.headers };
}

// ---------------------------------------------------------------- 时间工具（严格对齐 PsycheAppointmentService）
//   addWorkingDays(LocalDateTime,1) = 加 1 个工作日但**保留时分秒**（PsycheAppointmentService.java:412-415）
//   inServiceWindow = 非周六周日 且 08:00 ≤ t ≤ 17:00（**含** 17:00，:373-383）
const pad2 = (n) => String(n).padStart(2, '0');
const stamp = (d) =>
  `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ` +
  `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
const isWeekend = (d) => d.getDay() === 0 || d.getDay() === 6;
const minutesOfDay = (d) => d.getHours() * 60 + d.getMinutes();
const inServiceWindow = (d) => !isWeekend(d) && minutesOfDay(d) >= 480 && minutesOfDay(d) <= 1020;
function addWorkingDays(from, days) {
  const d = new Date(from.getTime());
  let remain = days;
  while (remain > 0) {
    d.setDate(d.getDate() + 1);
    if (!isWeekend(d)) remain -= 1;
  }
  return d;
}
function firstInWindowFrom(from) {
  const d = new Date(from.getTime());
  d.setSeconds(0, 0);
  if (d.getTime() < from.getTime()) d.setMinutes(d.getMinutes() + 1);
  for (let i = 0; i < 30000; i += 1) {
    if (inServiceWindow(d)) return d;
    d.setMinutes(d.getMinutes() + 1);
  }
  throw new Error('找不到服务时段');
}
function firstWeekdayAt(from, hh, mm) {
  for (let k = 0; k < 30; k += 1) {
    const d = new Date(from.getTime());
    d.setDate(d.getDate() + k);
    d.setHours(hh, mm, 0, 0);
    if (!isWeekend(d) && d.getTime() >= from.getTime()) return d;
  }
  throw new Error('找不到工作日时点');
}
// 最近的一个「服务时段内」时点（用于触发 S17 提前半天提示：renew 没有 1 工作日门槛）
function nearTermInWindow(now) {
  if (!isWeekend(now) && minutesOfDay(now) < 1020) {
    const d = new Date(now.getTime());
    d.setMinutes(d.getMinutes() + 30 >= 1020 ? 1020 : d.getMinutes() + 30, 0, 0);
    if (inServiceWindow(d) && d.getTime() < now.getTime() + 12 * 3600 * 1000) return d;
  }
  for (let k = 0; k < 8; k += 1) {
    const d = new Date(now.getTime());
    d.setDate(d.getDate() - k);
    d.setHours(8, 0, 0, 0);
    if (!isWeekend(d) && inServiceWindow(d) && d.getTime() < now.getTime() + 12 * 3600 * 1000) return d;
  }
  return null;
}

// ---------------------------------------------------------------- 主体
async function main() {
  console.log('# P14 心理健康 E2E（' + BASE + '）RUN_TAG=' + RUN_TAG);

  // ============================================================ 1. 登录 + 未登录 401
  section('1. 登录与会话');
  await login('admin');
  await login('lisi');
  await login('zhangsan');
  await login('chendw');
  await login('wangxm');
  await login('liusq');
  await login('zhaoxh');

  const anonRoutes = [
    ['GET', '/api/psyche/instruments'],
    ['POST', '/api/psyche/instruments'],
    ['GET', '/api/psyche/batches'],
    ['POST', '/api/psyche/batches'],
    ['PUT', '/api/psyche/batches/9101'],
    ['POST', '/api/psyche/batches/9101/publish'],
    ['POST', '/api/psyche/batches/9101/close'],
    ['GET', '/api/psyche/batches/9101/tasks'],
    ['POST', '/api/psyche/batches/9101/tasks/import'],
    ['GET', '/api/psyche/batches/9101/stats'],
    ['POST', '/api/psyche/tasks/91001/interview'],
    ['GET', '/api/psyche/my'],
    ['GET', '/api/psyche/tasks/91001'],
    ['POST', '/api/psyche/tasks/91001/submit'],
    ['GET', '/api/psyche/tasks/91001/result'],
    ['PUT', '/api/psyche/tasks/91001/result'],
    ['GET', '/api/psyche/focus'],
    ['POST', '/api/psyche/focus'],
    ['PUT', '/api/psyche/focus/93001'],
    ['POST', '/api/psyche/focus/93001/followups'],
    ['POST', '/api/psyche/appointments'],
    ['GET', '/api/psyche/appointments/my'],
    ['GET', '/api/psyche/appointments'],
    ['POST', '/api/psyche/appointments/95001/confirm'],
    ['POST', '/api/psyche/appointments/95001/complete'],
    ['POST', '/api/psyche/appointments/95001/cancel'],
    ['POST', '/api/psyche/appointments/95001/renew'],
    ['GET', '/api/psyche/referrals'],
    ['POST', '/api/psyche/referrals'],
    ['PUT', '/api/psyche/referrals/96001'],
    ['GET', '/api/psyche/stats'],
  ];
  let anonOk = 0;
  for (const [method, path] of anonRoutes) {
    const r = await api(null, method, path, method === 'GET' ? undefined : {});
    if (r.http === 200 && code(r) === 401) anonOk += 1;
    else console.log('  ! 未登录未回 401: ' + method + ' ' + path + ' -> http=' + r.http + ' ' + brief(r));
  }
  check('未登录访问 31 条路由全部 {code:401}（' + anonOk + '/' + anonRoutes.length + '）',
    anonOk === anonRoutes.length, '非 401 条数 ' + (anonRoutes.length - anonOk));
  const anonExport = await raw(null, '/api/psyche/export?type=tasks');
  check('未登录 GET /export 回裸 401（CSV 接口不走 {code} 包装）', anonExport.http === 401,
    'http=' + anonExport.http + ' body=' + anonExport.text.slice(0, 120));

  // ============================================================ 2. 量表
  section('2. 量表 /instruments');
  const instStudent = await api('wangxm', 'GET', '/api/psyche/instruments');
  const stuList = arr(data(instStudent));
  check('学生 GET /instruments = 200 且为数组', code(instStudent) === 200 && Array.isArray(stuList), brief(instStudent));
  check('学生端只下发启用中的量表（9001 在、未启用 9002/9003 不在）',
    stuList.some((x) => x.id === 9001) && !stuList.some((x) => x.id === 9002) && !stuList.some((x) => x.id === 9003),
    JSON.stringify(stuList.map((x) => x.id)));
  const stuKeys = stuList.length ? Object.keys(stuList[0]) : [];
  check('学生端量表视图不含题本 itemJson / item_json',
    !stuKeys.includes('itemJson') && !stuKeys.includes('item_json'), stuKeys.join(','));
  check('学生端量表视图不含阈值 bandJson / band_json',
    !stuKeys.includes('bandJson') && !stuKeys.includes('band_json'), stuKeys.join(','));
  check('学生端量表视图不含出处 sourceNote / source_note',
    !stuKeys.includes('sourceNote') && !stuKeys.includes('source_note'), stuKeys.join(','));
  check('学生端量表视图有 id/code/name/itemCount',
    stuKeys.includes('id') && stuKeys.includes('code') && stuKeys.includes('name') && stuKeys.includes('itemCount'),
    stuKeys.join(','));

  const instAdmin = await api('admin', 'GET', '/api/psyche/instruments');
  const admList = arr(data(instAdmin));
  const demo9001 = admList.find((x) => x.id === 9001);
  check('admin GET /instruments 拿得到题本与阈值明细（9001 有 itemJson + bandJson）',
    code(instAdmin) === 200 && !!demo9001 && !!demo9001.itemJson && !!demo9001.bandJson,
    demo9001 ? Object.keys(demo9001).join(',') : brief(instAdmin));
  // 实测口径（观察点，非验收项）：listInstruments 对 admin 与学生一视同仁，都走 instrumentMapper.list(1)，
  // 只回 active=1；admin 只是多拿 itemJson/bandJson 明细，并不包含停用中的量表。
  check('admin 的量表集合同样只含 active=1（停用的 9002/9003 连 admin 也不回）',
    !admList.some((x) => x.id === 9002) && !admList.some((x) => x.id === 9003)
    && admList.some((x) => x.id === 9001),
    JSON.stringify(admList.map((x) => x.id)));

  const instCode = 'e2e-demo-' + RUN_TAG;
  const instItemJson = JSON.stringify([
    { no: 1, text: '最近两周我睡得好', choices: [{ value: 1, label: '没有' }, { value: 3, label: '经常' }] },
    { no: 2, text: '最近两周我情绪低落', choices: [{ value: 1, label: '没有' }, { value: 3, label: '经常' }] },
  ]);
  const instBandJson = JSON.stringify([
    { band: 'normal', max: 4 },
    { band: 'attention', max: 8 },
    { band: 'warning', max: 12 },
  ]);
  const instFail1 = await api('admin', 'POST', '/api/psyche/instruments', { name: '无编码' });
  check('POST /instruments 缺 code → 400「请填写量表编码」',
    code(instFail1) === 400 && msg(instFail1).includes('请填写量表编码'), brief(instFail1));
  const instFail2 = await api('admin', 'POST', '/api/psyche/instruments', { code: instCode });
  check('POST /instruments 缺 name → 400「请填写量表名称」',
    code(instFail2) === 400 && msg(instFail2).includes('请填写量表名称'), brief(instFail2));
  const instFail3 = await api('admin', 'POST', '/api/psyche/instruments',
    { code: instCode, name: '非法题本', itemJson: '{"no":1}' });
  check('POST /instruments itemJson 非数组 → 400「题本 JSON 必须是题目数组」',
    code(instFail3) === 400 && msg(instFail3).includes('题本 JSON 必须是题目数组'), brief(instFail3));
  const instFail4 = await api('admin', 'POST', '/api/psyche/instruments',
    { code: instCode, name: '非法阈值', bandJson: '{"band":"normal"}' });
  check('POST /instruments bandJson 非数组 → 400「分档阈值 JSON 必须是数组」',
    code(instFail4) === 400 && msg(instFail4).includes('分档阈值 JSON 必须是数组'), brief(instFail4));
  const instFail5 = await api('admin', 'POST', '/api/psyche/instruments',
    { code: 'demo-self-rating', name: '重复编码' });
  check('POST /instruments 重复 code → 400「量表编码已存在」',
    code(instFail5) === 400 && msg(instFail5).includes('量表编码已存在'), brief(instFail5));
  const instFail6 = await api('chendw', 'POST', '/api/psyche/instruments', {});
  check('POST /instruments 学生调用 → 403「无权访问」', code(instFail6) === 403, brief(instFail6));

  const instNew = await api('admin', 'POST', '/api/psyche/instruments',
    { code: instCode, name: 'E2E 演示量表 ' + RUN_TAG, itemJson: instItemJson, bandJson: instBandJson, guideText: 'E2E 非临床演示题，请按最近两周实际情况填写。' });
  const I1 = data(instNew);
  check('admin POST /instruments 建新量表（唯一 code）→ 200 且带 id',
    code(instNew) === 200 && !!I1 && !!I1.id && I1.code === instCode, brief(instNew));
  check('新量表 item_count 由题本数组自动折算（2 题）', !!I1 && I1.itemCount === 2,
    I1 ? 'itemCount=' + I1.itemCount : brief(instNew));

  // ============================================================ 3. 批次
  section('3. 普查批次 /batches');
  const now = new Date();
  const nowStamp = stamp(now);
  const bName1 = 'E2E 心理健康普查 ' + RUN_TAG;
  const bName2 = 'E2E 未来窗口普查 ' + RUN_TAG;
  const bName3 = 'E2E 草稿批次 ' + RUN_TAG;

  const bFail1 = await api('admin', 'POST', '/api/psyche/batches', { grade: '2024级' });
  check('POST /batches 缺 batchName → 400「请填写批次名称」',
    code(bFail1) === 400 && msg(bFail1).includes('请填写批次名称'), brief(bFail1));
  const bFail2 = await api('admin', 'POST', '/api/psyche/batches', { batchName: '缺年级 ' + RUN_TAG });
  check('POST /batches 缺 grade → 400「请填写普查对象年级」',
    code(bFail2) === 400 && msg(bFail2).includes('请填写普查对象年级'), brief(bFail2));
  const bFail3 = await api('admin', 'POST', '/api/psyche/batches',
    { batchName: '量表不存在 ' + RUN_TAG, grade: '2024级', instrumentId: 999999 });
  check('POST /batches instrumentId 不存在 → 400「量表不存在」',
    code(bFail3) === 400 && msg(bFail3).includes('量表不存在'), brief(bFail3));
  const bFail4 = await api('admin', 'POST', '/api/psyche/batches',
    { batchName: '时间格式 ' + RUN_TAG, grade: '2024级', openAt: '2026/10/01', closeAt: nowStamp });
  check('POST /batches openAt 格式错 → 400「开放时间格式不正确（yyyy-MM-dd HH:mm:ss）」',
    code(bFail4) === 400 && msg(bFail4).includes('开放时间格式不正确'), brief(bFail4));
  const bFail5 = await api('admin', 'POST', '/api/psyche/batches',
    {
      batchName: '关早于开 ' + RUN_TAG, grade: '2024级', instrumentId: 9001,
      openAt: stamp(new Date(now.getTime() + 7 * 86400000)), closeAt: stamp(now),
    });
  check('POST /batches closeAt 早于 openAt → 400「关闭时间不能早于开放时间」',
    code(bFail5) === 400 && msg(bFail5).includes('关闭时间不能早于开放时间'), brief(bFail5));
  const bFail6 = await api('lisi', 'POST', '/api/psyche/batches', { batchName: 'x', grade: '2024级' });
  check('POST /batches 辅导员调用 → 403「仅管理员可操作」',
    code(bFail6) === 403 && msg(bFail6).includes('仅管理员可操作'), brief(bFail6));

  const b1Res = await api('admin', 'POST', '/api/psyche/batches', {
    batchName: bName1, grade: '2024级', academicYear: '2026-2027', instrumentId: I1.id,
    openAt: stamp(new Date(now.getTime() - 3600 * 1000)), closeAt: stamp(new Date(now.getTime() + 7 * 86400000)),
    interviewRequired: 1, interviewNote: '普查结束后随机抽查面对面访谈（S8）。',
    noticeTitle: 'E2E 普查通知 ' + RUN_TAG, noticeUrl: 'https://example.invalid/e2e',
  });
  const B1 = data(b1Res);
  check('admin POST /batches 建批次（唯一名）→ 200 且落到 draft',
    code(b1Res) === 200 && !!B1 && !!B1.id && B1.status === 'draft', brief(b1Res));

  const b2Res = await api('admin', 'POST', '/api/psyche/batches', {
    batchName: bName2, grade: '2024级', academicYear: '2026-2027', instrumentId: 9001,
    openAt: stamp(new Date(now.getTime() + 5 * 86400000)), closeAt: stamp(new Date(now.getTime() + 12 * 86400000)),
  });
  const B2 = data(b2Res);
  check('admin POST /batches 建「窗口未开始」批次 → 200', code(b2Res) === 200 && !!B2 && !!B2.id, brief(b2Res));

  const b3Res = await api('admin', 'POST', '/api/psyche/batches', {
    batchName: bName3, grade: '2099级', instrumentId: 9001,
    openAt: stamp(new Date(now.getTime() + 20 * 86400000)), closeAt: stamp(new Date(now.getTime() + 21 * 86400000)),
  });
  const B3 = data(b3Res);
  check('admin POST /batches 建草稿批次（用于 close 非法迁移）→ 200',
    code(b3Res) === 200 && !!B3 && !!B3.id && B3.status === 'draft', brief(b3Res));

  const batchesAdmin = await api('admin', 'GET', '/api/psyche/batches');
  check('admin GET /batches = 200 且为数组、含新建批次',
    code(batchesAdmin) === 200 && Array.isArray(data(batchesAdmin))
    && arr(data(batchesAdmin)).some((x) => x.id === B1.id), brief(batchesAdmin));
  const batchesKw = await api('admin', 'GET', '/api/psyche/batches?keyword=' + encodeURIComponent(RUN_TAG));
  check('GET /batches?keyword= 命中新建批次',
    code(batchesKw) === 200 && arr(data(batchesKw)).length >= 3, brief(batchesKw));
  const batchesStatus = await api('admin', 'GET', '/api/psyche/batches?status=draft');
  check('GET /batches?status=draft 只回 draft',
    code(batchesStatus) === 200 && arr(data(batchesStatus)).every((x) => x.status === 'draft'), brief(batchesStatus));
  const batchesStudent = await api('wangxm', 'GET', '/api/psyche/batches');
  check('学生 GET /batches 看不到 draft 批次（B3 不在、演示 9102 draft 不在）',
    code(batchesStudent) === 200 && !arr(data(batchesStudent)).some((x) => x.id === B3.id)
    && !arr(data(batchesStudent)).some((x) => x.id === 9102), brief(batchesStudent));

  const putOk = await api('admin', 'PUT', '/api/psyche/batches/' + B1.id, { noticeTitle: 'E2E 改后通知 ' + RUN_TAG });
  check('admin PUT /batches/{id}（draft）改通知标题 → 200 且字段生效',
    code(putOk) === 200 && !!data(putOk) && data(putOk).noticeTitle === 'E2E 改后通知 ' + RUN_TAG, brief(putOk));
  const putBadTime = await api('admin', 'PUT', '/api/psyche/batches/' + B1.id, { closeAt: stamp(new Date(now.getTime() - 86400000)) });
  check('PUT /batches/{id} 关闭时间早于开放时间 → 400「关闭时间不能早于开放时间」',
    code(putBadTime) === 400 && msg(putBadTime).includes('关闭时间不能早于开放时间'), brief(putBadTime));
  const put404 = await api('admin', 'PUT', '/api/psyche/batches/999999', { noticeTitle: 'x' });
  check('PUT /batches/999999 → 404「批次不存在」', code(put404) === 404 && msg(put404).includes('批次不存在'), brief(put404));
  const putTeacher = await api('lisi', 'PUT', '/api/psyche/batches/' + B1.id, { noticeTitle: 'x' });
  check('PUT /batches/{id} 辅导员调用 → 403「仅管理员可操作」',
    code(putTeacher) === 403 && msg(putTeacher).includes('仅管理员可操作'), brief(putTeacher));

  const pub1 = await api('admin', 'POST', '/api/psyche/batches/' + B1.id + '/publish');
  check('POST /batches/{id}/publish（draft→open）→ 200 且 status=open、publishedAt 非空',
    code(pub1) === 200 && !!data(pub1) && data(pub1).status === 'open' && !!data(pub1).publishedAt, brief(pub1));
  const pub1Again = await api('admin', 'POST', '/api/psyche/batches/' + B1.id + '/publish');
  check('非法状态迁移：已公示批次再 publish → 400「状态已变化，请刷新后重试」',
    code(pub1Again) === 400 && msg(pub1Again).includes('状态已变化'), brief(pub1Again));
  const pub404 = await api('admin', 'POST', '/api/psyche/batches/999999/publish');
  check('POST /batches/999999/publish → 404', code(pub404) === 404, brief(pub404));
  const closeDraft = await api('admin', 'POST', '/api/psyche/batches/' + B3.id + '/close');
  check('非法状态迁移：草稿批次 close → 400「状态已变化，请刷新后重试」',
    code(closeDraft) === 400 && msg(closeDraft).includes('状态已变化'), brief(closeDraft));
  const pubB2 = await api('admin', 'POST', '/api/psyche/batches/' + B2.id + '/publish');
  check('POST /batches/{id}/publish（未来窗口批次同样可公示）→ 200',
    code(pubB2) === 200 && data(pubB2).status === 'open', brief(pubB2));
  const closeB2 = await api('admin', 'POST', '/api/psyche/batches/' + B2.id + '/close');
  check('POST /batches/{id}/close（open→closed）→ 200 且数据含未参加处理提示',
    code(closeB2) === 200 && data(closeB2).batch && data(closeB2).batch.status === 'closed'
    && msg(closeB2).includes('按未参加处理'), brief(closeB2));
  const closeB2Again = await api('admin', 'POST', '/api/psyche/batches/' + B2.id + '/close');
  check('非法状态迁移：已关闭批次再 close → 400「状态已变化，请刷新后重试」',
    code(closeB2Again) === 400 && msg(closeB2Again).includes('状态已变化'), brief(closeB2Again));
  // 负数用例：已关闭批次再改（规格 §9 未显式写这条，但任务书把它列为「非法状态迁移」用例；
  // 实现只做并发 CAS（PsycheService.java:296-297 javadoc + batchMapper.update(merged, exist.getStatus())），
  // 不校验 draft/open/closed 状态机 —— 这里按任务书期望断言 400，实际结果见日志。
  const putClosed = await api('admin', 'PUT', '/api/psyche/batches/' + B2.id, { noticeTitle: 'E2E 已关闭后改 ' + RUN_TAG });
  check('非法状态迁移：已关闭批次再 PUT → 期望 400（实现疑似放行，见报告）',
    code(putClosed) === 400, brief(putClosed));

  // ============================================================ 4. 名单导入与进度
  section('4. 名单导入 /batches/{id}/tasks/import');
  const impNone = await api('admin', 'POST', '/api/psyche/batches/' + B1.id + '/tasks/import', {});
  check('import 既无 studentIds 也无 csv → 400「请提供 studentIds 或 csv 文本」',
    code(impNone) === 400 && msg(impNone).includes('请提供 studentIds 或 csv 文本'), brief(impNone));
  const imp404 = await api('admin', 'POST', '/api/psyche/batches/999999/tasks/import', { studentIds: [6] });
  check('import 到不存在批次 → 404「批次不存在」', code(imp404) === 404, brief(imp404));
  const imp1 = await api('admin', 'POST', '/api/psyche/batches/' + B1.id + '/tasks/import', { studentIds: [6] });
  check('import {studentIds:[6]} 首次 → created=1',
    code(imp1) === 200 && data(imp1).created === 1 && data(imp1).skipped === 0, brief(imp1));
  const imp2 = await api('admin', 'POST', '/api/psyche/batches/' + B1.id + '/tasks/import', { studentIds: [6] });
  check('import 同一学生二次 → skipped=1 created=0（UNIQUE(batch_id,student_id)）',
    code(imp2) === 200 && data(imp2).created === 0 && data(imp2).skipped === 1, brief(imp2));
  const imp3 = await api('lisi', 'POST', '/api/psyche/batches/' + B1.id + '/tasks/import', { csv: '学号,姓名\n2024003,陈大伟\n' });
  check('import CSV 带表头（学号,姓名）→ 跳过表头且学号命中已有名单 → skipped=1',
    code(imp3) === 200 && data(imp3).created === 0 && data(imp3).skipped === 1, brief(imp3));
  const imp4 = await api('admin', 'POST', '/api/psyche/batches/' + B1.id + '/tasks/import', { studentIds: ['999999999'] });
  check('import 不存在的学号 → notFound 列出该 token',
    code(imp4) === 200 && Array.isArray(data(imp4).notFound) && data(imp4).notFound.includes('999999999'), brief(imp4));
  const imp5 = await api('admin', 'POST', '/api/psyche/batches/' + B1.id + '/tasks/import', { studentIds: '5,4' });
  check('import studentIds 传字符串 "5,4" → created=2（逗号分隔）',
    code(imp5) === 200 && data(imp5).created === 2, brief(imp5));
  const impStudent = await api('wangxm', 'POST', '/api/psyche/batches/' + B1.id + '/tasks/import', { studentIds: [6] });
  check('import 学生调用 → 403「无权访问」', code(impStudent) === 403, brief(impStudent));

  section('5. 名单与进度 /batches/{id}/tasks ? /batches/{id}/stats');
  const tasksAll = await api('lisi', 'GET', '/api/psyche/batches/' + B1.id + '/tasks?size=50');
  const taskRows = arr(data(tasksAll));
  check('GET /batches/{id}/tasks = 200 且 data={list,total,page,size,stats}',
    code(tasksAll) === 200 && !!data(tasksAll) && Array.isArray(data(tasksAll).list)
    && typeof data(tasksAll).total === 'number' && typeof data(tasksAll).page === 'number'
    && typeof data(tasksAll).size === 'number' && !!data(tasksAll).stats, brief(tasksAll));
  check('名单列表脱敏：姓名只留姓 + *、学号只留后四位',
    taskRows.length >= 3 && taskRows.every((x) => /^\*$|^\S\*$/.test(x.studentName)) && taskRows.every((x) => /^\d{4}$/.test(x.studentNo)),
    JSON.stringify(taskRows.map((x) => x.studentName + '/' + x.studentNo)));
  const tRow = taskRows.find((x) => x.studentId === 6);
  check('名单列表含 chendw(id=6) 的 pending 名单', !!tRow && tRow.status === 'pending', JSON.stringify(tRow || {}).slice(0, 200));
  const T1 = tRow ? tRow.id : null;
  const tasksPending = await api('lisi', 'GET', '/api/psyche/batches/' + B1.id + '/tasks?status=pending&size=50');
  check('GET tasks?status=pending 过滤生效',
    code(tasksPending) === 200 && arr(data(tasksPending)).length > 0
    && arr(data(tasksPending)).every((x) => x.status === 'pending'), brief(tasksPending));
  const tasksKw = await api('lisi', 'GET', '/api/psyche/batches/' + B1.id + '/tasks?keyword=4003');
  check('GET tasks?keyword=学号后四位 命中', code(tasksKw) === 200 && arr(data(tasksKw)).length >= 1, brief(tasksKw));
  const tasks404 = await api('lisi', 'GET', '/api/psyche/batches/999999/tasks');
  check('GET tasks 不存在批次 → 404「批次不存在」', code(tasks404) === 404, brief(tasks404));
  const tasksStudent = await api('wangxm', 'GET', '/api/psyche/batches/' + B1.id + '/tasks');
  check('GET tasks 学生调用 → 403「无权访问」', code(tasksStudent) === 403, brief(tasksStudent));
  const bStats = await api('lisi', 'GET', '/api/psyche/batches/' + B1.id + '/stats');
  const bs = data(bStats);
  check('GET /batches/{id}/stats = 200 且带聚合口径（total/completed/pending/completionPercent）',
    code(bStats) === 200 && !!bs && bs.total >= 3 && typeof bs.completed === 'number'
    && typeof bs.completionPercent === 'number' && Array.isArray(bs.byClass)
    && !!bs.focusLevels && typeof bs.focusLevels.total === 'number', brief(bStats));
  check('批次统计 byClass 行只有聚合字段（name/total/completed/completionRate/completionPercent）',
    Array.isArray(bs && bs.byClass) && bs.byClass.every((r) => Object.keys(r).sort().join(',')
      === 'completed,completionPercent,completionRate,name,total'),
    bs && bs.byClass ? JSON.stringify(bs.byClass).slice(0, 240) : 'no byClass');
  const bStats404 = await api('lisi', 'GET', '/api/psyche/batches/999999/stats');
  check('GET /batches/999999/stats → 404', code(bStats404) === 404, brief(bStats404));

  // ============================================================ 6. 学生端任务与作答
  section('6. 学生端 /my ? /tasks/{id} ? /tasks/{id}/submit ? /tasks/{id}/result');
  const myFail = await api('lisi', 'GET', '/api/psyche/my');
  check('GET /my 辅导员调用 → 403「仅学生可用」', code(myFail) === 403 && msg(myFail).includes('仅学生可用'), brief(myFail));
  const myStu = await api('chendw', 'GET', '/api/psyche/my');
  const myBatches = myStu.json && myStu.json.data ? data(myStu).batches : [];
  check('学生 GET /my = 200 且 {batches,appointments,focusAdvice}',
    code(myStu) === 200 && Array.isArray(myBatches) && Array.isArray(data(myStu).appointments)
    && data(myStu).focusAdvice && typeof data(myStu).focusAdvice.text === 'string', brief(myStu));
  const myB1 = myBatches.find((x) => x.batch && x.batch.id === B1.id);
  check('学生 /my 含新批次 B1 与 myTask（含题量）',
    !!myB1 && !!myB1.myTask && myB1.myTask.totalItems === 2, JSON.stringify(myB1 || {}).slice(0, 240));
  check('学生 /my 的 myTask 已置空 resultLevel / resultNote（§9 口径 7）',
    !!myB1 && myB1.myTask.resultLevel === null && myB1.myTask.resultNote === null,
    myB1 ? JSON.stringify({ l: myB1.myTask.resultLevel, n: myB1.myTask.resultNote }) : 'no myTask');
  check('学生 /my 不下发预约中心备注 note',
    code(myStu) === 200 && data(myStu).appointments.every((x) => x.note === null || x.note === undefined),
    JSON.stringify(arr(data(myStu).appointments).map((x) => x.note)));

  const detail404 = await api('chendw', 'GET', '/api/psyche/tasks/999999');
  check('GET /tasks/999999 → 404「名单不存在」', code(detail404) === 404 && msg(detail404).includes('名单不存在'), brief(detail404));
  const detailOther = await api('zhaoxh', 'GET', '/api/psyche/tasks/' + T1);
  check('学生访问他人名单 → 403「只能查看自己的普查名单」',
    code(detailOther) === 403 && msg(detailOther).includes('只能查看自己的普查名单'), brief(detailOther));
  const detailCross = await api('zhangsan', 'GET', '/api/psyche/tasks/' + T1);
  check('跨辅导员访问名单 → 403「只能查看名下学生的普查记录」',
    code(detailCross) === 403 && msg(detailCross).includes('只能查看名下学生的普查记录'), brief(detailCross));
  const detailOwnerStaff = await api('lisi', 'GET', '/api/psyche/tasks/' + T1);
  check('该生辅导员 GET /tasks/{id} → 200（台账含 task/batch/instrument/answer）',
    code(detailOwnerStaff) === 200 && !!data(detailOwnerStaff).task && !!data(detailOwnerStaff).batch
    && !!data(detailOwnerStaff).instrument, brief(detailOwnerStaff));
  const detailAdm = await api('admin', 'GET', '/api/psyche/tasks/' + T1);
  check('admin GET /tasks/{id} → 200', code(detailAdm) === 200, brief(detailAdm));

  const detailStu = await api('chendw', 'GET', '/api/psyche/tasks/' + T1);
  check('窗口内学生首次取题 → 200 且 pending 推进为 in_progress、落 startedAt',
    code(detailStu) === 200 && data(detailStu).task.status === 'in_progress' && !!data(detailStu).task.startedAt,
    brief(detailStu));
  check('学生取题拿得到题本 item_json（取题是学生唯一需要题本的路径）',
    code(detailStu) === 200 && !!data(detailStu).instrument
    && (data(detailStu).instrument.item_json || data(detailStu).instrument.itemJson), brief(detailStu));
  check('学生取题不下发分档阈值（instrument 视图无 bandJson）',
    code(detailStu) === 200 && !!data(detailStu).instrument
    && !('bandJson' in data(detailStu).instrument) && !('band_json' in data(detailStu).instrument),
    JSON.stringify(Object.keys((data(detailStu) || {}).instrument || {})));

  const subStaff = await api('lisi', 'POST', '/api/psyche/tasks/' + T1 + '/submit', { answers: [] });
  check('教职工提交整卷 → 403「仅学生本人可提交普查问卷」',
    code(subStaff) === 403 && msg(subStaff).includes('仅学生本人可提交普查问卷'), brief(subStaff));
  const subOther = await api('zhaoxh', 'POST', '/api/psyche/tasks/' + T1 + '/submit',
    { answers: [{ no: 1, value: 1 }, { no: 2, value: 1 }] });
  check('学生提交他人问卷 → 403「只能提交自己的普查问卷」',
    code(subOther) === 403 && msg(subOther).includes('只能提交自己的普查问卷'), brief(subOther));
  const subPartial = await api('chendw', 'POST', '/api/psyche/tasks/' + T1 + '/submit',
    { answers: [{ no: 1, value: 3 }] });
  check('未答完全部题目 → 400「请完成全部题目后再提交」（S8：测试无效按未参加处理）',
    code(subPartial) === 400 && msg(subPartial).includes('请完成全部题目后再提交'), brief(subPartial));
  const subDup = await api('chendw', 'POST', '/api/psyche/tasks/' + T1 + '/submit',
    { answers: [{ no: 1, value: 3 }, { no: 1, value: 3 }] });
  check('答案题号重复（2 条但只有 1 个题号）→ 400「请完成全部题目后再提交」',
    code(subDup) === 400 && msg(subDup).includes('请完成全部题目后再提交'), brief(subDup));
  const subOk = await api('chendw', 'POST', '/api/psyche/tasks/' + T1 + '/submit',
    { answers: [{ no: 1, value: 3 }, { no: 2, value: 3 }], durationSeconds: 120 });
  check('答完全部题目提交 → 200 且写 scoreTotal/scoreBand',
    code(subOk) === 200 && !!data(subOk) && Number(data(subOk).scoreTotal) === 6
    && data(subOk).scoreBand === 'attention', brief(subOk));
  check('提交后 task.status=completed、answerCount=2、submittedAt 非空',
    code(subOk) === 200 && data(subOk).task.status === 'completed'
    && data(subOk).task.answerCount === 2 && !!data(subOk).task.submittedAt, brief(subOk));
  const subAgain = await api('chendw', 'POST', '/api/psyche/tasks/' + T1 + '/submit',
    { answers: [{ no: 1, value: 3 }, { no: 2, value: 3 }] });
  check('重复提交 → 400「该量表已提交，不能重复提交」',
    code(subAgain) === 400 && msg(subAgain).includes('不能重复提交'), brief(subAgain));
  const sub404 = await api('chendw', 'POST', '/api/psyche/tasks/999999/submit', { answers: [] });
  check('POST /tasks/999999/submit → 404', code(sub404) === 404, brief(sub404));

  const resStaff = await api('lisi', 'GET', '/api/psyche/tasks/' + T1 + '/result');
  check('辅导员 GET /tasks/{id}/result → 200 且 resultLevel 仍为 null（平台不自动判定）',
    code(resStaff) === 200 && !!data(resStaff) && data(resStaff).resultLevel === null
    && data(resStaff).scoreBand === 'attention', brief(resStaff));
  check('GET /tasks/{id}/result 回 scoreTotal/scoreBand（只按 band_json 折算标签）',
    code(resStaff) === 200 && !!data(resStaff) && Number(data(resStaff).scoreTotal) === 6
    && Number(data(resStaff).task.scoreTotal) === 6, brief(resStaff));
  const res404 = await api('lisi', 'GET', '/api/psyche/tasks/999999/result');
  check('GET /tasks/999999/result → 404', code(res404) === 404, brief(res404));

  // 结果等级回填（只能由中心/辅导员登记）
  const putResBad = await api('lisi', 'PUT', '/api/psyche/tasks/' + T1 + '/result', { resultLevel: 'high' });
  check('PUT /tasks/{id}/result 非法等级 → 400「结果等级只接受 normal / attention / warning」',
    code(putResBad) === 400 && msg(putResBad).includes('结果等级只接受 normal / attention / warning'), brief(putResBad));
  const putResCross = await api('zhangsan', 'PUT', '/api/psyche/tasks/' + T1 + '/result', { resultLevel: 'attention' });
  check('跨辅导员回填结果等级 → 403「只能回填名下学生的结果等级」',
    code(putResCross) === 403 && msg(putResCross).includes('只能回填名下学生的结果等级'), brief(putResCross));
  const putResStu = await api('chendw', 'PUT', '/api/psyche/tasks/' + T1 + '/result', { resultLevel: 'normal' });
  check('学生回填结果等级 → 403「无权访问」', code(putResStu) === 403, brief(putResStu));
  const putResOk = await api('lisi', 'PUT', '/api/psyche/tasks/' + T1 + '/result',
    { resultLevel: 'attention', resultNote: 'E2E 中心回填备注 ' + RUN_TAG });
  check('辅导员回填结果等级 → 200 且 resultLevel=attention、resultNote 落库',
    code(putResOk) === 200 && data(putResOk).resultLevel === 'attention'
    && data(putResOk).resultNote === 'E2E 中心回填备注 ' + RUN_TAG, brief(putResOk));
  const putRes404 = await api('lisi', 'PUT', '/api/psyche/tasks/999999/result', { resultLevel: 'normal' });
  check('PUT /tasks/999999/result → 404', code(putRes404) === 404, brief(putRes404));

  // ---- 「学生端看不到等级原文」——§9 口径 7 + PsycheController.java:30 的显式契约
  //      （PsycheService.java:636-637 只在 /my 里置空；taskDetail/resultView 未按角色置空）
  const leakDetail = await api('chendw', 'GET', '/api/psyche/tasks/' + T1);
  check('学生 GET /tasks/{id}（已回填）不下发 task.resultLevel / resultNote',
    code(leakDetail) === 200 && !!data(leakDetail).task
    && (data(leakDetail).task.resultLevel === null || data(leakDetail).task.resultLevel === undefined)
    && (data(leakDetail).task.resultNote === null || data(leakDetail).task.resultNote === undefined),
    brief(leakDetail));
  const leakResult = await api('chendw', 'GET', '/api/psyche/tasks/' + T1 + '/result');
  check('学生 GET /tasks/{id}/result 不下发 resultLevel / resultNote',
    code(leakResult) === 200 && (data(leakResult).resultLevel === null || data(leakResult).resultLevel === undefined)
    && (data(leakResult).resultNote === null || data(leakResult).resultNote === undefined),
    brief(leakResult));
  const leakDemo = await api('wangxm', 'GET', '/api/psyche/tasks/91001/result');
  check('学生 GET /tasks/91001/result（演示数据已回填 attention）不下发 resultLevel / resultNote',
    code(leakDemo) === 200 && (data(leakDemo).resultLevel === null || data(leakDemo).resultLevel === undefined)
    && (data(leakDemo).resultNote === null || data(leakDemo).resultNote === undefined), brief(leakDemo));
  const leakDemoDetail = await api('wangxm', 'GET', '/api/psyche/tasks/91001');
  check('学生 GET /tasks/91001（演示已回填）不下发 task.resultLevel / resultNote',
    code(leakDemoDetail) === 200
    && (data(leakDemoDetail).task.resultLevel === null || data(leakDemoDetail).task.resultLevel === undefined)
    && (data(leakDemoDetail).task.resultNote === null || data(leakDemoDetail).task.resultNote === undefined),
    brief(leakDemoDetail));

  // ---- 抽查访谈记录
  section('7. 抽查访谈 /tasks/{id}/interview');
  const ivStu = await api('chendw', 'POST', '/api/psyche/tasks/' + T1 + '/interview', { interviewed: 1 });
  check('访谈记录 学生调用 → 403「无权访问」', code(ivStu) === 403, brief(ivStu));
  const ivCross = await api('zhangsan', 'POST', '/api/psyche/tasks/' + T1 + '/interview', { interviewed: 1 });
  check('访谈记录 跨辅导员 → 403「只能填写名下学生的访谈记录」',
    code(ivCross) === 403 && msg(ivCross).includes('只能填写名下学生的访谈记录'), brief(ivCross));
  const ivLong = await api('lisi', 'POST', '/api/psyche/tasks/' + T1 + '/interview', { interviewed: 1, note: 'x'.repeat(501) });
  check('访谈记录超 500 字 → 400「访谈记录不能超过 500 个字符」',
    code(ivLong) === 400 && msg(ivLong).includes('访谈记录不能超过 500 个字符'), brief(ivLong));
  const iv404 = await api('lisi', 'POST', '/api/psyche/tasks/999999/interview', { interviewed: 1 });
  check('访谈记录 不存在的名单 → 404「名单不存在」', code(iv404) === 404 && msg(iv404).includes('名单不存在'), brief(iv404));
  const ivOk = await api('lisi', 'POST', '/api/psyche/tasks/' + T1 + '/interview',
    { interviewed: 1, note: 'E2E 已面谈：情绪平稳 ' + RUN_TAG });
  check('辅导员登记抽查访谈 → 200 且 interviewed=1、interviewNote 落库',
    code(ivOk) === 200 && data(ivOk).interviewed === 1
    && data(ivOk).interviewNote === 'E2E 已面谈：情绪平稳 ' + RUN_TAG, brief(ivOk));
  const ivAdmin = await api('admin', 'POST', '/api/psyche/tasks/' + T1 + '/interview', { interviewed: 0 });
  check('admin 可改访谈标记为未访谈 → 200 且 interviewed=0',
    code(ivAdmin) === 200 && data(ivAdmin).interviewed === 0, brief(ivAdmin));

  // ============================================================ 8. 关注台账
  section('8. 关注台账 /focus');
  const focusStu = await api('wangxm', 'GET', '/api/psyche/focus');
  check('学生 GET /focus → 403「无权访问」（服务层 guardStaff，非 404）',
    code(focusStu) === 403 && msg(focusStu).includes('无权访问'), brief(focusStu));
  const focusLisi = await api('lisi', 'GET', '/api/psyche/focus?size=50');
  const fRows = arr(data(focusLisi));
  check('lisi GET /focus 只看到名下学生（演示 93001 在、zhangsan 的 93002 不在）',
    code(focusLisi) === 200 && fRows.some((x) => x.id === 93001) && !fRows.some((x) => x.id === 93002),
    JSON.stringify(fRows.map((x) => x.id)));
  const focusZs = await api('zhangsan', 'GET', '/api/psyche/focus?size=50');
  const fRows2 = arr(data(focusZs));
  check('zhangsan GET /focus 只看到名下学生（93002 在、lisi 的 93001 不在）',
    code(focusZs) === 200 && fRows2.some((x) => x.id === 93002) && !fRows2.some((x) => x.id === 93001),
    JSON.stringify(fRows2.map((x) => x.id)));
  check('跨辅导员在 /focus 列表里搜不到他人学生（keyword=4001 王*）',
    code((await api('zhangsan', 'GET', '/api/psyche/focus?keyword=' + encodeURIComponent('4001')))) === 200
    && arr(data(await api('zhangsan', 'GET', '/api/psyche/focus?keyword=' + encodeURIComponent('4001')))).length === 0,
    'zhangsan 搜 4001 结果非空');
  check('关注台账列表脱敏：姓名只留姓、学号只留后四位',
    fRows.length >= 1 && fRows.every((x) => /^\S\*$/.test(x.studentName) || /^\S$/.test(x.studentName))
    && fRows.every((x) => /^\d{4}$/.test(x.studentNo)),
    JSON.stringify(fRows.map((x) => x.studentName + '/' + x.studentNo)));

  const focusNoStu = await api('lisi', 'POST', '/api/psyche/focus', { level: 'general' });
  check('POST /focus 缺 studentId → 400「请指定学生」',
    code(focusNoStu) === 400 && msg(focusNoStu).includes('请指定学生'), brief(focusNoStu));
  const focusBadLevel = await api('lisi', 'POST', '/api/psyche/focus', { studentId: 6, level: 'urgent' });
  check('POST /focus 非法等级 → 400「关注等级只接受 general / key / risk」',
    code(focusBadLevel) === 400 && msg(focusBadLevel).includes('关注等级只接受 general / key / risk'), brief(focusBadLevel));
  const focusBadSource = await api('lisi', 'POST', '/api/psyche/focus', { studentId: 6, source: 'magic' });
  check('POST /focus 非法来源 → 400「关注来源只接受 task / apply / counselor / appointment / referral / other」',
    code(focusBadSource) === 400 && msg(focusBadSource).includes('关注来源只接受'), brief(focusBadSource));
  const focusCross = await api('zhangsan', 'POST', '/api/psyche/focus', { studentId: 6, level: 'general' });
  check('跨辅导员建关注台账（studentId=6 属 lisi）→ 403「只能给自己名下的学生建关注台账」',
    code(focusCross) === 403 && msg(focusCross).includes('只能给自己名下的学生建关注台账'), brief(focusCross));
  const focusStuPost = await api('chendw', 'POST', '/api/psyche/focus', { studentId: 6 });
  check('学生 POST /focus → 403', code(focusStuPost) === 403, brief(focusStuPost));
  const focusNew = await api('lisi', 'POST', '/api/psyche/focus', {
    studentId: 6, level: 'general', source: 'apply', batchId: B1.id,
    summary: 'E2E 台账 ' + RUN_TAG, measures: 'E2E 措施：先观察，必要时转介。',
  });
  const F1 = data(focusNew);
  check('lisi 给自己名下学生建关注台账 → 200 且 status=open',
    code(focusNew) === 200 && !!F1 && !!F1.id && F1.status === 'open' && F1.counselorId === 3, brief(focusNew));

  const fPutCross = await api('zhangsan', 'PUT', '/api/psyche/focus/' + F1.id, { level: 'key' });
  check('跨辅导员改关注台账 → 403「只能改自己名下学生的关注台账」',
    code(fPutCross) === 403 && msg(fPutCross).includes('只能改自己名下学生的关注台账'), brief(fPutCross));
  const fPutOther = await api('lisi', 'PUT', '/api/psyche/focus/93002', { level: 'key' });
  check('改他人学生台账（93002 属 zhangsan）→ 403',
    code(fPutOther) === 403, brief(fPutOther));
  const fPut404 = await api('lisi', 'PUT', '/api/psyche/focus/999999', { level: 'key' });
  check('PUT /focus/999999 → 404「关注台账不存在」',
    code(fPut404) === 404 && msg(fPut404).includes('关注台账不存在'), brief(fPut404));
  const fPutBad = await api('lisi', 'PUT', '/api/psyche/focus/' + F1.id, { status: 'zzz' });
  check('PUT /focus/{id} 非法状态 → 400「台账状态只接受 open / following / closed」',
    code(fPutBad) === 400 && msg(fPutBad).includes('台账状态只接受 open / following / closed'), brief(fPutBad));
  const fPutOk = await api('lisi', 'PUT', '/api/psyche/focus/' + F1.id, { level: 'key', status: 'following' });
  check('PUT /focus/{id} 改等级+状态 → 200 且生效',
    code(fPutOk) === 200 && data(fPutOk).level === 'key' && data(fPutOk).status === 'following', brief(fPutOk));

  const fuCross = await api('zhangsan', 'POST', '/api/psyche/focus/' + F1.id + '/followups', { content: 'x' });
  check('跨辅导员登记跟进 → 403「只能给自己名下学生的台账登记跟进」',
    code(fuCross) === 403 && msg(fuCross).includes('只能给自己名下学生的台账登记跟进'), brief(fuCross));
  const fuEmpty = await api('lisi', 'POST', '/api/psyche/focus/' + F1.id + '/followups', { content: '  ' });
  check('跟进内容为空 → 400「请填写跟进内容」',
    code(fuEmpty) === 400 && msg(fuEmpty).includes('请填写跟进内容'), brief(fuEmpty));
  const fu404 = await api('lisi', 'POST', '/api/psyche/focus/999999/followups', { content: 'x' });
  check('跟进 不存在的台账 → 404「关注台账不存在」', code(fu404) === 404, brief(fu404));
  const fuOk = await api('lisi', 'POST', '/api/psyche/focus/' + F1.id + '/followups',
    { content: 'E2E 跟进：已电话联系 ' + RUN_TAG, measures: '维持每周一次跟进', nextPlan: '两周后复评' });
  check('登记跟进 → 200 且回 {followup,focus,followups} 三件套',
    code(fuOk) === 200 && !!data(fuOk).followup && !!data(fuOk).focus && Array.isArray(data(fuOk).followups)
    && data(fuOk).followups.length >= 1, brief(fuOk));
  check('登记跟进顺带把台账推进为 following',
    code(fuOk) === 200 && data(fuOk).focus.status === 'following', brief(fuOk));
  const fAdvice = await api('chendw', 'GET', '/api/psyche/my');
  check('学生 /my focusAdvice.show=true（有未关闭台账时只给建议卡，不给等级）',
    code(fAdvice) === 200 && data(fAdvice).focusAdvice.show === true
    && data(fAdvice).focusAdvice.text.includes('建议你与辅导员或中心联系'), JSON.stringify(data(fAdvice).focusAdvice));

  // ============================================================ 9. 转介
  section('9. 转介 /referrals');
  const refStu = await api('chendw', 'GET', '/api/psyche/referrals');
  check('学生 GET /referrals → 403「无权访问」', code(refStu) === 403, brief(refStu));
  const refLisi = await api('lisi', 'GET', '/api/psyche/referrals?size=50');
  const rRows = arr(data(refLisi));
  check('lisi GET /referrals 只看到名下学生（96001 在）',
    code(refLisi) === 200 && rRows.some((x) => x.id === 96001), brief(refLisi));
  check('转介列表脱敏：姓名只留姓、学号只留后四位',
    rRows.length >= 1 && rRows.every((x) => /^\S\*$/.test(x.studentName)) && rRows.every((x) => /^\d{4}$/.test(x.studentNo)),
    JSON.stringify(rRows.map((x) => x.studentName + '/' + x.studentNo)));
  const refZs = await api('zhangsan', 'GET', '/api/psyche/referrals?size=50');
  check('zhangsan GET /referrals 看不到 lisi 学生的 96001',
    code(refZs) === 200 && !arr(data(refZs)).some((x) => x.id === 96001), brief(refZs));
  const refFilter = await api('lisi', 'GET', '/api/psyche/referrals?target=campus&status=following');
  check('GET /referrals?target=campus&status=following 过滤生效且只回聚合列表',
    code(refFilter) === 200 && !!data(refFilter) && typeof data(refFilter).total === 'number', brief(refFilter));

  const refNoStu = await api('lisi', 'POST', '/api/psyche/referrals', { target: 'campus' });
  check('POST /referrals 缺 studentId → 400「请指定学生」',
    code(refNoStu) === 400 && msg(refNoStu).includes('请指定学生'), brief(refNoStu));
  const refBadTarget = await api('lisi', 'POST', '/api/psyche/referrals', { studentId: 6, target: 'moon' });
  check('POST /referrals 非法去向 → 400「转介去向只接受 campus / hospital / family」',
    code(refBadTarget) === 400 && msg(refBadTarget).includes('转介去向只接受 campus / hospital / family'), brief(refBadTarget));
  const refBadSource = await api('lisi', 'POST', '/api/psyche/referrals', { studentId: 6, sourceType: 'magic' });
  check('POST /referrals 非法来源 → 400「转介来源只接受 task / focus / appointment / other」',
    code(refBadSource) === 400 && msg(refBadSource).includes('转介来源只接受'), brief(refBadSource));
  const refCross = await api('zhangsan', 'POST', '/api/psyche/referrals', { studentId: 6, target: 'campus' });
  check('跨辅导员建转介 → 403「只能给自己名下的学生登记转介」',
    code(refCross) === 403 && msg(refCross).includes('只能给自己名下的学生登记转介'), brief(refCross));
  const refNew = await api('lisi', 'POST', '/api/psyche/referrals', {
    studentId: 6, sourceType: 'focus', sourceId: F1.id, target: 'campus',
    reason: 'E2E 转介缘由 ' + RUN_TAG,
  });
  const R1 = data(refNew);
  check('lisi 给自己名下学生建转介 → 200 且 status=open、target=campus',
    code(refNew) === 200 && !!R1 && !!R1.id && R1.status === 'open' && R1.target === 'campus', brief(refNew));
  const refPutBad = await api('lisi', 'PUT', '/api/psyche/referrals/' + R1.id, { target: 'moon' });
  check('PUT /referrals/{id} 非法去向 → 400',
    code(refPutBad) === 400 && msg(refPutBad).includes('转介去向只接受'), brief(refPutBad));
  const refPutBadStatus = await api('lisi', 'PUT', '/api/psyche/referrals/' + R1.id, { status: 'zzz' });
  check('PUT /referrals/{id} 非法状态 → 400「转介状态只接受 open / following / closed」',
    code(refPutBadStatus) === 400 && msg(refPutBadStatus).includes('转介状态只接受 open / following / closed'), brief(refPutBadStatus));
  const refPut404 = await api('lisi', 'PUT', '/api/psyche/referrals/999999', { target: 'campus' });
  check('PUT /referrals/999999 → 404「转介记录不存在」',
    code(refPut404) === 404 && msg(refPut404).includes('转介记录不存在'), brief(refPut404));
  const refPutCross = await api('zhangsan', 'PUT', '/api/psyche/referrals/' + R1.id, { target: 'hospital' });
  check('跨辅导员改转介 → 403「只能改自己名下学生的转介记录」',
    code(refPutCross) === 403 && msg(refPutCross).includes('只能改自己名下学生的转介记录'), brief(refPutCross));
  const refPutOk = await api('lisi', 'PUT', '/api/psyche/referrals/' + R1.id,
    { target: 'hospital', status: 'following', followNote: 'E2E 已对接医院 ' + RUN_TAG });
  check('PUT /referrals/{id} 改去向/状态/跟踪记录 → 200 且生效',
    code(refPutOk) === 200 && data(refPutOk).target === 'hospital' && data(refPutOk).status === 'following'
    && data(refPutOk).followNote === 'E2E 已对接医院 ' + RUN_TAG, brief(refPutOk));

  // ============================================================ 10. 预约
  section('10. 预约咨询 /appointments');
  const minBound = addWorkingDays(now, 1);           // 与后端同口径：1 个工作日（保留时分秒）
  const legalStart = firstInWindowFrom(minBound);    // 最早合法 expectStart
  const earlyStart = new Date(minBound.getTime() - 60000);
  const preOpenStart = firstWeekdayAt(minBound, 7, 0);
  const at1700 = firstWeekdayAt(minBound, 17, 0);
  const at1701 = firstWeekdayAt(minBound, 17, 1);
  console.log('  · 本机 now=' + stamp(now) + ' | 1 工作日边界=' + stamp(minBound)
    + ' | 最早合法=' + stamp(legalStart));

  // 幂等清场：本脚本前几轮可能给 chendw 留下 requested/confirmed 的预约（create 会被
  // 「你已有待处理的预约」挡住）。cancel 没有状态机守卫，恰好可以用它做清理；
  // 只动 chendw(id=6) 自己的行 —— 演示数据 95001/95002/95004 属于学生 4/5/7，不受影响。
  const apMine = await api('chendw', 'GET', '/api/psyche/appointments/my');
  let apCleaned = 0;
  for (const a of arr(data(apMine))) {
    if (a.status === 'requested' || a.status === 'confirmed') {
      const r = await api('admin', 'POST', '/api/psyche/appointments/' + a.id + '/cancel',
        { cancelReason: 'E2E 清场 ' + RUN_TAG });
      if (code(r) === 200) apCleaned += 1;
    }
  }
  console.log('  · 清场：取消 chendw 遗留的待处理预约 ' + apCleaned + ' 条');

  const apMode = await api('chendw', 'POST', '/api/psyche/appointments',
    { mode: 'phone', mainIssue: 'E2E', contactPhone: '13800000006', expectStart: stamp(legalStart) });
  check('POST /appointments 非法方式 → 400「预约方式只接受 online / office」',
    code(apMode) === 400 && msg(apMode).includes('预约方式只接受 online / office'), brief(apMode));
  const apIssue = await api('chendw', 'POST', '/api/psyche/appointments',
    { mode: 'office', contactPhone: '13800000006', expectStart: stamp(legalStart) });
  check('POST /appointments 缺主要咨询问题 → 400「请填写主要咨询问题」',
    code(apIssue) === 400 && msg(apIssue).includes('请填写主要咨询问题'), brief(apIssue));
  const apPhone = await api('chendw', 'POST', '/api/psyche/appointments',
    { mode: 'office', mainIssue: 'E2E', expectStart: stamp(legalStart) });
  check('POST /appointments 缺联系方式 → 400「请填写联系方式」',
    code(apPhone) === 400 && msg(apPhone).includes('请填写联系方式'), brief(apPhone));
  const apTime = await api('chendw', 'POST', '/api/psyche/appointments',
    { mode: 'office', mainIssue: 'E2E', contactPhone: '13800000006', expectStart: 'not-a-time' });
  check('POST /appointments 时间不可解析 → 400「请选择期望咨询时间（yyyy-MM-dd HH:mm:ss）」',
    code(apTime) === 400 && msg(apTime).includes('请选择期望咨询时间'), brief(apTime));
  const apDur = await api('chendw', 'POST', '/api/psyche/appointments',
    { mode: 'office', mainIssue: 'E2E', contactPhone: '13800000006', expectStart: stamp(legalStart), durationMinutes: 0 });
  check('POST /appointments 时长 0 → 400「单次咨询时长必须在 1~240 分钟之间（默认 50 分钟）」',
    code(apDur) === 400 && msg(apDur).includes('单次咨询时长必须在 1~240 分钟之间'), brief(apDur));
  const apDur2 = await api('chendw', 'POST', '/api/psyche/appointments',
    { mode: 'office', mainIssue: 'E2E', contactPhone: '13800000006', expectStart: stamp(legalStart), durationMinutes: 241 });
  check('POST /appointments 时长 241 → 400（上限 240）',
    code(apDur2) === 400 && msg(apDur2).includes('1~240'), brief(apDur2));
  const apTeacher = await api('lisi', 'POST', '/api/psyche/appointments',
    { mode: 'office', mainIssue: 'E2E', contactPhone: '13800000000', expectStart: stamp(legalStart) });
  check('POST /appointments 辅导员调用 → 403「仅学生可用」',
    code(apTeacher) === 403 && msg(apTeacher).includes('仅学生可用'), brief(apTeacher));

  // S16：非应激必须提前至少一个工作日 —— 边界正好卡在 minBound
  const apEarly = await api('chendw', 'POST', '/api/psyche/appointments',
    { mode: 'office', mainIssue: 'E2E 提前不足 ' + RUN_TAG, contactPhone: '13800000006', expectStart: stamp(earlyStart) });
  check('提前不足 1 个工作日（边界 -1 分钟）→ 400 且文案含「如有紧急情况请直接电话联系 0596-6288525」',
    code(apEarly) === 400 && msg(apEarly).includes('请至少提前一个工作日')
    && msg(apEarly).includes('0596-6288525'), brief(apEarly));
  const apPreOpen = await api('chendw', 'POST', '/api/psyche/appointments',
    { mode: 'office', mainIssue: 'E2E', contactPhone: '13800000006', expectStart: stamp(preOpenStart) });
  check('非服务时段（工作日 07:00，已过提前量门槛）→ 400「心理咨询预约时间为周一至周五 8:00-17:00」',
    code(apPreOpen) === 400 && msg(apPreOpen).includes('周一至周五 8:00-17:00'), brief(apPreOpen));
  const ap1701 = await api('chendw', 'POST', '/api/psyche/appointments',
    { mode: 'office', mainIssue: 'E2E', contactPhone: '13800000006', expectStart: stamp(at1701) });
  check('非服务时段（17:01）→ 400', code(ap1701) === 400 && msg(ap1701).includes('周一至周五 8:00-17:00'), brief(ap1701));

  const ap1700 = await api('chendw', 'POST', '/api/psyche/appointments',
    { mode: 'office', mainIssue: 'E2E 17:00 边界 ' + RUN_TAG, contactPhone: '13800000006', expectStart: stamp(at1700) });
  check('服务时段上界 17:00 含在内 → 200（inServiceWindow 用 !isAfter(WORK_END)）',
    code(ap1700) === 200 && !!data(ap1700), brief(ap1700));
  if (code(ap1700) === 200) {
    const cancel1700 = await api('chendw', 'POST', '/api/psyche/appointments/' + data(ap1700).id + '/cancel', {});
    check('17:00 边界预约由学生本人取消 → 200 且 status=cancelled',
      code(cancel1700) === 200 && data(cancel1700).status === 'cancelled', brief(cancel1700));
  } else {
    check('17:00 边界预约由学生本人取消 → 200 且 status=cancelled', false, '上一步创建失败，无法继续');
  }

  const apA = await api('chendw', 'POST', '/api/psyche/appointments', {
    mode: 'online', mainIssue: 'E2E-P14 主要咨询问题 ' + RUN_TAG,
    contactPhone: '13800000006', expectStart: stamp(legalStart),
  });
  const A = data(apA) || { id: 0 };  // 防御：创建失败时给占位 id，避免后续断言把脚本打崩
  check('学生建预约（合法时间）→ 200 且 status=requested',
    code(apA) === 200 && !!A && !!A.id && A.status === 'requested', brief(apA));
  check('duration_minutes 默认 50（S16 每次咨询 50 分钟）', !!A && A.durationMinutes === 50, A ? 'duration=' + A.durationMinutes : 'no data');
  check('expectEnd = expectStart + 50 分钟', !!A && !!A.expectEnd
    && (new Date(A.expectEnd).getTime() - new Date(A.expectStart).getTime()) === 50 * 60000,
    A ? A.expectStart + ' -> ' + A.expectEnd : 'no data');
  check('预约落到该生真实辅导员（student_profile.counselor_id=3 / lisi）', !!A && A.counselorId === 3,
    A ? 'counselorId=' + A.counselorId : 'no data');

  const apDup = await api('chendw', 'POST', '/api/psyche/appointments',
    { mode: 'office', mainIssue: 'E2E', contactPhone: '13800000006', expectStart: stamp(legalStart) });
  check('已有待处理预约再建 → 400「你已有待处理的预约」',
    code(apDup) === 400 && msg(apDup).includes('你已有待处理的预约'), brief(apDup));

  const apCrossConfirm = await api('zhangsan', 'POST', '/api/psyche/appointments/' + A.id + '/confirm');
  check('跨辅导员确认预约 → 403「只能处理名下学生的预约」',
    code(apCrossConfirm) === 403 && msg(apCrossConfirm).includes('只能处理名下学生的预约'), brief(apCrossConfirm));
  const apCrossComplete = await api('zhangsan', 'POST', '/api/psyche/appointments/' + A.id + '/complete');
  check('跨辅导员完成预约 → 403「只能处理名下学生的预约」',
    code(apCrossComplete) === 403, brief(apCrossComplete));
  const apCrossCancel = await api('zhangsan', 'POST', '/api/psyche/appointments/' + A.id + '/cancel', { reason: 'x' });
  check('跨辅导员取消预约 → 403', code(apCrossCancel) === 403, brief(apCrossCancel));
  const apStuConfirm = await api('chendw', 'POST', '/api/psyche/appointments/' + A.id + '/confirm');
  check('学生确认预约 → 403「无权访问」', code(apStuConfirm) === 403, brief(apStuConfirm));

  const apConfirm = await api('lisi', 'POST', '/api/psyche/appointments/' + A.id + '/confirm');
  check('辅导员确认预约（requested→confirmed）→ 200 且 confirmedAt/confirmedBy 落库',
    code(apConfirm) === 200 && data(apConfirm).status === 'confirmed' && !!data(apConfirm).confirmedAt
    && data(apConfirm).confirmedBy === 3, brief(apConfirm));
  // §9.2(6) 的另一半：脱敏只作用于列表，「有权限的 detail」回全名全学号。
  // 这里拿的就是辅导员的单行响应（appointmentMapper.findById 带 Joined_Columns）。
  check('有权限的明细（辅导员确认的单行响应）回全名全学号，与列表脱敏形成对照',
    code(apConfirm) === 200 && data(apConfirm).studentName === '陈大伟'
    && data(apConfirm).studentNo === '2024003', brief(apConfirm));
  const apConfirm2 = await api('lisi', 'POST', '/api/psyche/appointments/' + A.id + '/confirm');
  check('重复确认 → 400「状态已变化，请刷新后重试」',
    code(apConfirm2) === 400 && msg(apConfirm2).includes('状态已变化'), brief(apConfirm2));
  const apComplete = await api('lisi', 'POST', '/api/psyche/appointments/' + A.id + '/complete');
  check('辅导员完成预约（confirmed→done）→ 200 且 status=done、时长仍为 50',
    code(apComplete) === 200 && data(apComplete).status === 'done' && data(apComplete).durationMinutes === 50, brief(apComplete));
  const apComplete2 = await api('lisi', 'POST', '/api/psyche/appointments/' + A.id + '/complete');
  check('重复完成 → 400「状态已变化，请刷新后重试」',
    code(apComplete2) === 400 && msg(apComplete2).includes('状态已变化'), brief(apComplete2));
  // 注意：「终态预约不应再被迁移」的用例刻意放到本节末尾、另建一条预约来做 ——
  // 实测 cancel 对已完成预约会成功（见报告 §3），一旦在这里取消 A，
  // 后面的 renew 全部会被「已取消的预约不能续约」提前挡住，用例就测不到续约了。

  const nearStart = nearTermInWindow(now);
  const apRenewCross = await api('liusq', 'POST', '/api/psyche/appointments/' + A.id + '/renew',
    { expectStart: stamp(nearStart || legalStart) });
  check('他人续约我的预约 → 403「只能续约自己的预约」',
    code(apRenewCross) === 403 && msg(apRenewCross).includes('只能续约自己的预约'), brief(apRenewCross));
  const apRenew404 = await api('chendw', 'POST', '/api/psyche/appointments/999999/renew', { expectStart: stamp(legalStart) });
  check('续约不存在的预约 → 404「预约不存在」', code(apRenew404) === 404, brief(apRenew404));
  const apRenewNoTime = await api('chendw', 'POST', '/api/psyche/appointments/' + A.id + '/renew', {});
  check('续约缺 expectStart → 400「请选择期望咨询时间（yyyy-MM-dd HH:mm:ss）」',
    code(apRenewNoTime) === 400 && msg(apRenewNoTime).includes('请选择期望咨询时间'), brief(apRenewNoTime));
  const apRenewOut = await api('chendw', 'POST', '/api/psyche/appointments/' + A.id + '/renew',
    { expectStart: stamp(firstWeekdayAt(minBound, 7, 0)) });
  check('续约仍要求落在服务时段内（07:00）→ 400「心理咨询预约时间为周一至周五 8:00-17:00」',
    code(apRenewOut) === 400 && msg(apRenewOut).includes('周一至周五 8:00-17:00'), brief(apRenewOut));

  const apRenew = await api('chendw', 'POST', '/api/psyche/appointments/' + A.id + '/renew',
    { expectStart: stamp(nearStart || legalStart) });
  const RN = data(apRenew) || { id: 0 };
  check('续约成功（免网上预约程序，S17 第五步）→ 200 且 status=requested',
    code(apRenew) === 200 && !!RN && RN.status === 'requested' && !!RN.id, brief(apRenew));
  check('续约延续同一辅导员（counselorId 不变）', !!RN && RN.counselorId === A.counselorId,
    RN ? RN.counselorId + ' vs ' + A.counselorId : 'no data');
  check('续约写入 renewed_from = 原预约 id', !!RN && RN.renewedFrom === A.id,
    RN ? 'renewedFrom=' + RN.renewedFrom : 'no data');
  check('续约沿用量表外的原预约方式 mode', !!RN && RN.mode === A.mode, RN ? RN.mode + ' vs ' + A.mode : 'no data');
  check('续约不做「提前一个工作日」判定（可预约近端时间）',
    !!RN && Math.abs(new Date(RN.expectStart).getTime() - (nearStart || legalStart).getTime()) < 1000,
    RN ? RN.expectStart : 'no data');

  if (nearStart && RN && RN.id) {
    const apConfirmNear = await api('lisi', 'POST', '/api/psyche/appointments/' + RN.id + '/confirm');
    check('S17 提前半天确认：距开始不足 12 小时仍确认成功（非阻塞）→ code 200 + status=confirmed',
      code(apConfirmNear) === 200 && data(apConfirmNear).status === 'confirmed', brief(apConfirmNear));
    check('S17 提示：message 含「线上咨询需提前半天确认（S17）」',
      code(apConfirmNear) === 200 && msg(apConfirmNear).includes('线上咨询需提前半天确认（S17）'), brief(apConfirmNear));
  } else {
    check('S17 提前半天确认：距开始不足 12 小时仍确认成功（非阻塞）→ code 200 + status=confirmed', false,
      '当轮找不到 12 小时内的服务时段（周末/时点受限），未构造');
    check('S17 提示：message 含「线上咨询需提前半天确认（S17）」', false, '同上，未构造');
  }

  if (RN && RN.id) {
    const apCancelOwn = await api('chendw', 'POST', '/api/psyche/appointments/' + RN.id + '/cancel',
      { cancelReason: 'E2E 学生取消 ' + RUN_TAG });
    check('学生取消自己的预约 → 200 且 status=cancelled',
      code(apCancelOwn) === 200 && data(apCancelOwn).status === 'cancelled', brief(apCancelOwn));
    const apCancelOwn2 = await api('chendw', 'POST', '/api/psyche/appointments/' + RN.id + '/cancel', {});
    check('非法状态迁移：已取消的预约再 cancel → 400「状态已变化，请刷新后重试」（实际见附注）',
      code(apCancelOwn2) === 400, brief(apCancelOwn2));
    const apRenewCancelled = await api('chendw', 'POST', '/api/psyche/appointments/' + RN.id + '/renew',
      { expectStart: stamp(nearStart || legalStart) });
    check('已取消的预约不能续约 → 400「已取消的预约不能续约」',
      code(apRenewCancelled) === 400 && msg(apRenewCancelled).includes('已取消的预约不能续约'), brief(apRenewCancelled));
  } else {
    check('学生取消自己的预约 → 200 且 status=cancelled', false, '续约未成功，未构造');
    check('非法状态迁移：已取消的预约再 cancel → 400', false, '续约未成功，未构造');
    check('已取消的预约不能续约 → 400「已取消的预约不能续约」', false, '续约未成功，未构造');
  }

  const apC = await api('chendw', 'POST', '/api/psyche/appointments', {
    mode: 'office', mainIssue: 'E2E-P14 第二次预约 ' + RUN_TAG,
    contactPhone: '13800000006', expectStart: stamp(legalStart),
  });
  const C = data(apC) || { id: 0 };
  check('前序预约收敛后可再建一条 → 200', code(apC) === 200 && !!C && !!C.id, brief(apC));
  const apStaffCancel = await api('lisi', 'POST', '/api/psyche/appointments/' + C.id + '/cancel',
    { cancelReason: 'E2E 中心取消 ' + RUN_TAG });
  check('辅导员取消名下学生预约 → 200 且 status=cancelled',
    code(apStaffCancel) === 200 && data(apStaffCancel).status === 'cancelled', brief(apStaffCancel));
  const apConfirm404 = await api('lisi', 'POST', '/api/psyche/appointments/999999/confirm');
  const apComplete404 = await api('lisi', 'POST', '/api/psyche/appointments/999999/complete');
  const apCancel404 = await api('lisi', 'POST', '/api/psyche/appointments/999999/cancel', {});
  check('confirm/complete/cancel 不存在的预约 → 全部 404「预约不存在」',
    code(apConfirm404) === 404 && code(apComplete404) === 404 && code(apCancel404) === 404,
    [brief(apConfirm404), brief(apComplete404), brief(apCancel404)].join(' || '));

  // 「终态预约不该再被迁移」——另建一条预约单独做（chendw 此刻无 pending，可再建）
  const apD = await api('chendw', 'POST', '/api/psyche/appointments', {
    mode: 'office', mainIssue: 'E2E-P14 终态用例 ' + RUN_TAG,
    contactPhone: '13800000006', expectStart: stamp(legalStart),
  });
  const D = data(apD) || { id: 0 };
  check('终态用例：另建一条预约 → 200', code(apD) === 200 && !!D && !!D.id, brief(apD));
  if (code(apD) === 200 && D && D.id) {
    await api('lisi', 'POST', '/api/psyche/appointments/' + D.id + '/confirm');
    await api('lisi', 'POST', '/api/psyche/appointments/' + D.id + '/complete');
    const apCancelDone = await api('admin', 'POST', '/api/psyche/appointments/' + D.id + '/cancel', {});
    check('非法状态迁移：已完成的预约取消 → 400「状态已变化」（实际见附注：done→cancelled 被放行）',
      code(apCancelDone) === 400, brief(apCancelDone));
    const apConfirmCancelled = await api('lisi', 'POST', '/api/psyche/appointments/' + D.id + '/confirm');
    check('非法状态迁移：已取消的预约再 confirm → 400（实际见附注：cancelled→confirmed 被放行）',
      code(apConfirmCancelled) === 400, brief(apConfirmCancelled));
    // 收尾：上面那条 confirm 若被放行，D 会停在 confirmed（= 待处理），下一轮 create 就会被
    // 「你已有待处理的预约」挡住 —— 用 cancel 把它清掉，保证脚本可重复运行。
    await api('admin', 'POST', '/api/psyche/appointments/' + D.id + '/cancel', { cancelReason: 'E2E 收尾 ' + RUN_TAG });
  } else {
    check('非法状态迁移：已完成的预约取消 → 400', false, '终态用例预约未建成');
    check('非法状态迁移：已取消的预约再 confirm → 400', false, '终态用例预约未建成');
  }

  const apMy = await api('chendw', 'GET', '/api/psyche/appointments/my');
  check('GET /appointments/my = 200 且为数组、含本轮预约', code(apMy) === 200 && arr(data(apMy)).length >= 2
    && arr(data(apMy)).some((x) => x.id === A.id), brief(apMy));
  check('我的预约不下发中心内部备注 note',
    code(apMy) === 200 && arr(data(apMy)).every((x) => x.note === null || x.note === undefined), brief(apMy));
  check('我的预约能看到自己的 renewedFrom 留痕（续约溯源）',
    code(apMy) === 200 && arr(data(apMy)).some((x) => x.renewedFrom === A.id), brief(apMy));
  const apMyStaff = await api('lisi', 'GET', '/api/psyche/appointments/my');
  check('GET /appointments/my 辅导员调用 → 403「仅学生可用」', code(apMyStaff) === 403, brief(apMyStaff));
  const apListStu = await api('chendw', 'GET', '/api/psyche/appointments');
  check('GET /appointments 学生调用 → 403「无权访问」', code(apListStu) === 403, brief(apListStu));
  const apList = await api('lisi', 'GET', '/api/psyche/appointments?size=50');
  const apRows = arr(data(apList));
  check('lisi GET /appointments = 200 且 data={list,total,page,size}',
    code(apList) === 200 && !!data(apList) && Array.isArray(data(apList).list)
    && typeof data(apList).total === 'number' && typeof data(apList).page === 'number', brief(apList));
  check('预约列表脱敏（姓名只留姓、学号只留后四位）',
    apRows.length >= 1 && apRows.every((x) => /^\S\*$/.test(x.studentName)) && apRows.every((x) => /^\d{4}$/.test(x.studentNo)),
    JSON.stringify(apRows.map((x) => x.studentName + '/' + x.studentNo)).slice(0, 200));
  check('预约列表不下发主要咨询问题全文（mainIssue 置空）',
    apRows.length >= 1 && apRows.every((x) => x.mainIssue === null || x.mainIssue === undefined),
    JSON.stringify(apRows.map((x) => x.mainIssue)).slice(0, 200));
  check('预约列表不下发中心内部备注 note',
    apRows.every((x) => x.note === null || x.note === undefined), brief(apList));
  const apListZs = await api('zhangsan', 'GET', '/api/psyche/appointments?size=50');
  check('zhangsan 只看得到自己名下学生的预约（看不到 chendw 的预约）',
    code(apListZs) === 200 && !arr(data(apListZs)).some((x) => x.studentId === 6), brief(apListZs));

  // ============================================================ 11. 统计与导出
  section('11. 统计 /stats 与导出 /export');
  const statsStu = await api('chendw', 'GET', '/api/psyche/stats');
  check('学生 GET /stats → 403「无权访问」', code(statsStu) === 403, brief(statsStu));
  const stats = await api('lisi', 'GET', '/api/psyche/stats');
  const st = data(stats);
  check('GET /stats = 200 且为聚合口径（batches/byClass/byMajor/focusLevels/appointments）',
    code(stats) === 200 && !!st && Array.isArray(st.batches) && Array.isArray(st.byClass)
    && Array.isArray(st.byMajor) && !!st.focusLevels && !!st.appointments, brief(stats));
  check('/stats.focusLevels 只有 {general,key,risk,total} 计数',
    !!st && st.focusLevels && Object.keys(st.focusLevels).sort().join(',') === 'general,key,risk,total',
    st && st.focusLevels ? JSON.stringify(st.focusLevels) : 'no focusLevels');
  check('/stats.appointments 只有聚合计数与完成率',
    !!st && st.appointments && Object.keys(st.appointments).sort().join(',')
      === 'cancelled,completionPercent,completionRate,confirmed,done,no_show,requested,total',
    st && st.appointments ? Object.keys(st.appointments).join(',') : 'no appointments');
  const statsJson = JSON.stringify(st || {});
  check('/stats 不含可反推个体的明细（无姓名/学号/手机号/主要咨询问题字段）',
    !statsJson.includes('studentName') && !statsJson.includes('studentNo')
    && !statsJson.includes('contactPhone') && !statsJson.includes('mainIssue')
    && !statsJson.includes('resultLevel'), statsJson.slice(0, 300));
  check('/stats.byClass 行只有 {name,total,completed,completionRate,completionPercent}',
    !!st && Array.isArray(st.byClass) && st.byClass.every((r) => Object.keys(r).sort().join(',')
      === 'completed,completionPercent,completionRate,name,total'),
    st && st.byClass ? JSON.stringify(st.byClass).slice(0, 200) : 'no byClass');

  const expStu = await raw('chendw', '/api/psyche/export?type=tasks');
  check('学生 GET /export → 裸 403（非 CSV）', expStu.http === 403, 'http=' + expStu.http);
  const expNoType = await raw('admin', '/api/psyche/export');
  check('GET /export 缺 type → 裸 400', expNoType.http === 400, 'http=' + expNoType.http);
  const expBadType = await raw('admin', '/api/psyche/export?type=magic');
  check('GET /export type 非法 → 裸 400', expBadType.http === 400, 'http=' + expBadType.http);
  const expTasks = await raw('admin', '/api/psyche/export?type=tasks');
  const expTasksBody = expTasks.text.replace(/^\uFEFF/, '');
  check('GET /export?type=tasks → 200 且 Content-Type=text/csv;charset=UTF-8',
    expTasks.http === 200 && String(expTasks.headers.get('content-type')).toLowerCase().includes('text/csv')
    && String(expTasks.headers.get('content-type')).toLowerCase().includes('utf-8'),
    'http=' + expTasks.http + ' ct=' + expTasks.headers.get('content-type'));
  check('CSV 带 UTF-8 BOM（Excel 打开不乱码）', expTasks.text.charCodeAt(0) === 0xFEFF,
    '首字符码=' + expTasks.text.charCodeAt(0).toString(16));
  check('CSV 首行是表头「姓名,学号后四位,班级,专业,批次,状态,结果等级,是否已访谈」',
    expTasksBody.split('\r\n')[0] === '姓名,学号后四位,班级,专业,批次,状态,结果等级,是否已访谈',
    expTasksBody.split('\r\n')[0]);
  check('导出 CSV 已脱敏（不含全名「陈大伟」，含掩码学号「4003」/掩码姓名「陈*」）',
    !expTasksBody.includes('陈大伟') && expTasksBody.includes('4003') && expTasksBody.includes('陈*'),
    expTasksBody.split('\r\n').slice(1, 3).join(' | '));
  check('Content-Disposition 带 attachment; filename="psyche-tasks.csv"',
    String(expTasks.headers.get('content-disposition')).includes('attachment')
    && String(expTasks.headers.get('content-disposition')).includes('psyche-tasks.csv'),
    expTasks.headers.get('content-disposition'));
  const expFocus = await raw('lisi', '/api/psyche/export?type=focus');
  const expFocusBody = expFocus.text.replace(/^\uFEFF/, '');
  check('GET /export?type=focus → 200 且表头为关注台账口径（等级/来源/跟进辅导员）',
    expFocus.http === 200 && expFocusBody.split('\r\n')[0] === '姓名,学号后四位,班级,等级,来源,关注缘由,状态,跟进辅导员,登记时间',
    expFocusBody.split('\r\n')[0]);
  check('关注台账 CSV 含本轮新建台账（E2E 台账 ' + RUN_TAG + '）',
    expFocusBody.includes('E2E 台账 ' + RUN_TAG), expFocusBody.split('\r\n').length + ' 行');
  const expAppt = await raw('lisi', '/api/psyche/export?type=appointments');
  const expApptBody = expAppt.text.replace(/^\uFEFF/, '');
  check('GET /export?type=appointments → 200 且表头不含「咨询问题」全文（保密口径）',
    expAppt.http === 200 && expApptBody.split('\r\n')[0].includes('时长(分钟)')
    && !expApptBody.split('\r\n')[0].includes('问题') && !expApptBody.split('\r\n')[0].includes('联系方式'),
    expApptBody.split('\r\n')[0]);
  check('预约 CSV 正文不含主要咨询问题原文（main_issue 未导出）',
    !expApptBody.includes('E2E-P14 主要咨询问题 ' + RUN_TAG), expApptBody.split('\r\n').slice(0, 2).join(' | ').slice(0, 200));
  check('预约 CSV 含续约溯源列 renewed_from', expApptBody.split('\r\n')[0].includes('续约来源'),
    expApptBody.split('\r\n')[0]);
  const expBatch = await raw('admin', '/api/psyche/export?type=tasks&batchId=' + B1.id);
  const expBatchBody = expBatch.text.replace(/^\uFEFF/, '');
  check('GET /export?type=tasks&batchId= 只导出指定批次（行数=名单数+表头）',
    expBatch.http === 200 && expBatchBody.split('\r\n').filter((l) => l.trim().length > 0).length >= 2
    && !expBatchBody.includes('2024 级学生心理健康普查（演示）')
    && expBatchBody.includes(bName1), expBatchBody.split('\r\n').slice(0, 2).join(' | ').slice(0, 240));

  // ============================================================ 12. 跨窗口用例（未开始批次）
  section('12. 窗口外提交被拦（S8 时间窗）');
  // B2 的开放时间在 5 天后（closeAt 12 天后）⇒ 窗口尚未开始。
  // B2 在第 3 节已被 close（close 只把未作答名单置 absent，不影响 import 与窗口判定：absent 也不是 completed）。
  // 这里现导入 chendw，构造「名单存在但窗口未开」的用例。
  const impB2 = await api('lisi', 'POST', '/api/psyche/batches/' + B2.id + '/tasks/import', { studentIds: [6] });
  check('窗口未开始的批次导入 chendw 名单 → created=1',
    code(impB2) === 200 && data(impB2).created === 1, brief(impB2));
  const tasksB2 = await api('admin', 'GET', '/api/psyche/batches/' + B2.id + '/tasks?size=50');
  let T2 = null;
  if (code(tasksB2) === 200) {
    const row = arr(data(tasksB2)).find((x) => x.studentId === 6);
    T2 = row ? row.id : null;
  }
  check('窗口未开始的批次里 chendw 名单存在（用于窗口外用例）', T2 !== null,
    JSON.stringify(arr(data(tasksB2)).map((x) => x.studentId + ':' + x.id)));
  if (T2) {
    const winDetail = await api('chendw', 'GET', '/api/psyche/tasks/' + T2);
    check('窗口外取题 → 400「当前不在普查时间内」',
      code(winDetail) === 400 && msg(winDetail).includes('当前不在普查时间内'), brief(winDetail));
    const winSubmit = await api('chendw', 'POST', '/api/psyche/tasks/' + T2 + '/submit',
      { answers: [{ no: 1, value: 1 }, { no: 2, value: 1 }] });
    check('窗口外提交 → 400「当前不在普查时间内」',
      code(winSubmit) === 400 && msg(winSubmit).includes('当前不在普查时间内'), brief(winSubmit));
  } else {
    check('窗口外取题 → 400「当前不在普查时间内」', false, '未取到窗口外名单 id');
    check('窗口外提交 → 400「当前不在普查时间内」', false, '未取到窗口外名单 id');
  }

  // ============================================================ 13. 收尾：把本轮新建的开放批次关掉
  section('13. 收尾');
  const closeB1 = await api('admin', 'POST', '/api/psyche/batches/' + B1.id + '/close');
  check('收尾：关闭本轮新建的开放批次 B1 → 200（把未完成名单按未参加处理）',
    code(closeB1) === 200 && data(closeB1).batch.status === 'closed', brief(closeB1));

  // ============================================================ 汇总
  section('汇总');
  const total = pass + fails.length;
  console.log('断言总数 ' + total + '，PASS ' + pass + '，FAIL ' + fails.length);
  if (fails.length === 0) {
    console.log('ALL PASS ' + pass);
  } else {
    for (const f of fails) console.log('  ✗ ' + f);
    console.log(fails.length + ' FAILED');
    process.exitCode = 1;
  }
}

main().catch((e) => {
  console.error('脚本异常：' + ((e && e.stack) || e));
  process.exitCode = 2;
});

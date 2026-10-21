// P19 生源地贷款登记：真机验证。
// 打真实端口 :8080，按「学生 / 辅导员 / 其他辅导员 / 管理员 / 匿名」五种身份走完整状态机，
// 并核对导出脱敏、汇总口径与粗筛（ApiRoleGuardInterceptor）是否挡住员工面。
//
// 测试数据统一落在学年 2099-2100（一眼能认出是验证残渣），跑完用
//   cmd /c "...mysql.exe --defaults-extra-file=%TEMP%\nest_my.cnf -D nest < target\clean-loan.sql"
// 清掉；脚本会把清理语句写进 target\clean-loan.sql。
import fs from 'node:fs';

const BASE = 'http://127.0.0.1:8080';
const YEAR = '2099-2100';
const log = [];
let fails = 0;
let checks = 0;
const createdIds = [];

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
  const body = await res.json().catch(() => null);
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
  return { status: res.status, code: json && json.code, message: json && json.message, data: json && json.data, text, headers: res.headers };
}

function check(label, cond, detail) {
  checks++;
  if (cond) {
    line(`OK   ${label}`);
  } else {
    fails++;
    line(`FAIL ${label}  ${detail === undefined ? '' : detail}`);
  }
}

/** 导出要看原始字节（fetch 的 .text() 会把 UTF-8 BOM 解掉），所以单独留一个取字节的入口。 */
async function callRaw(path, sid) {
  const headers = {};
  if (sid) headers.Cookie = `JSESSIONID=${sid}`;
  const res = await fetch(BASE + path, { headers, redirect: 'manual' });
  const buf = Buffer.from(await res.arrayBuffer());
  return { status: res.status, headers: res.headers, buf };
}

function body(over = {}) {
  return Object.assign({
    academicYear: YEAR,
    originProvince: '福建省',
    originCity: '漳州市',
    originCounty: '龙海区',
    bankName: '国家开发银行福建省分行',
    contractNo: `E2E19_${Date.now().toString().slice(-8)}`,
    receiptNo: 'HZ2026200001',
    amount: '12000',
    remark: 'P19 真机验证数据'
  }, over);
}

const anon = null;
const sidStudent = await login('chendw');     // 辅导员 = lisi(3)
const sidOther = await login('liusq');        // 辅导员 = zhangsan(2)
const sidTeacher = await login('lisi');
const sidTeacher2 = await login('zhangsan');
const sidAdmin = await login('admin');

line('== 1) 未登录 ==');
{
  const a = await call('GET', '/api/loan/my', anon);
  check('匿名 GET /my -> code 401', a.code === 401, `code=${a.code} ${a.message}`);
  const b = await call('POST', '/api/loan', anon, body());
  check('匿名 POST -> code 401', b.code === 401, `code=${b.code} ${b.message}`);
  // 导出是员工面，已被框架层粗筛（ApiRoleGuardInterceptor）接住：先答 HTTP 200 + 业务码 401/403，
  // 走不到控制器里「HTTP 403 + 空响应体」那条分支。那条分支仍在，由 LoanServiceTest 直接钉住。
  const c = await call('GET', '/api/loan/export', anon);
  check('匿名导出 -> 粗筛先答 code 401', c.code === 401 && c.status === 200, `http=${c.status} code=${c.code} ${c.message}`);
  const d = await call('GET', '/api/loan/health', anon);
  check('health 是公开的 -> 200 且路由数 13', d.code === 200 && d.data && d.data.routes && d.data.routes.length === 13, JSON.stringify(d.data));
}

line('== 2) 学生提交与校验 ==');
let recordId = null;
{
  const my = await call('GET', '/api/loan/my', sidStudent);
  check('学生 GET /my -> 200', my.code === 200, `code=${my.code} ${my.message}`);
  check('/my 返回 list/page/size', my.data && Array.isArray(my.data.list) && my.data.page >= 1, JSON.stringify(my.data && Object.keys(my.data)));

  const sum = await call('GET', '/api/loan/summary', sidStudent);
  check('学生 GET /summary -> 200', sum.code === 200, `code=${sum.code} ${sum.message}`);
  check('summary 六个状态键齐全', sum.data && sum.data.counts && Object.keys(sum.data.counts).length === 6, JSON.stringify(sum.data && sum.data.counts));
  check('summary 带口径说明且写明「学校自定」', typeof sum.data.policy === 'string' && /学校自定/.test(sum.data.policy), sum.data && String(sum.data.policy).slice(0, 60));
  check('口径说明里没有任何四位数以上的政策数字', !/\d{4,}/.test(sum.data.policy || ''), sum.data && sum.data.policy);

  const badYear1 = await call('POST', '/api/loan', sidStudent, body({ academicYear: '2026/2027' }));
  check('学年写成 2026/2027 -> 400 学年', badYear1.code === 400 && /学年/.test(badYear1.message || ''), `${badYear1.message}`);
  const badYear2 = await call('POST', '/api/loan', sidStudent, body({ academicYear: '2026-2028' }));
  check('学年跨三年 2026-2028 -> 400', badYear2.code === 400 && /学年/.test(badYear2.message || ''), `${badYear2.message}`);

  const noCounty = await call('POST', '/api/loan', sidStudent, body({ originCounty: '' }));
  check('缺生源地县/区 -> 400 点名县/区', noCounty.code === 400 && /县/.test(noCounty.message || ''), `${noCounty.message}`);

  const zero = await call('POST', '/api/loan', sidStudent, body({ amount: '0' }));
  check('金额 0 -> 400「必须大于 0」', zero.code === 400 && /必须大于 0/.test(zero.message || ''), `${zero.message}`);

  const huge = await call('POST', '/api/loan', sidStudent, body({ amount: '80000000' }));
  check('金额 80000000 -> 400 且文案说明是防误填不是政策上限',
    huge.code === 400 && /防误填/.test(huge.message || '') && /不设政策上限/.test(huge.message || ''), `${huge.message}`);

  const scale = await call('POST', '/api/loan', sidStudent, body({ amount: '12000.123' }));
  check('金额三位小数 -> 400 两位小数', scale.code === 400 && /两位小数/.test(scale.message || ''), `${scale.message}`);

  const ok = await call('POST', '/api/loan', sidStudent, body());
  check('合法提交 -> 200 且状态 submitted', ok.code === 200 && ok.data && ok.data.status === 'submitted', `${ok.code} ${ok.message} ${JSON.stringify(ok.data)}`);
  recordId = ok.data && ok.data.id;
  if (recordId) createdIds.push(recordId);
  check('提交后金额按原样存（12000）', ok.data && String(ok.data.amount).replace(/\.0+$/, '') === '12000', ok.data && ok.data.amount);

  const dup = await call('POST', '/api/loan', sidStudent, body());
  check('同学年重复提交 -> 400 且点名已有状态', dup.code === 400 && /本学年已有贷款登记/.test(dup.message || '') && /待核验/.test(dup.message || ''), `${dup.message}`);
}

line('== 3) 学生改写 / 到账 / 撤回（只能动自己的） ==');
{
  const upd = await call('PUT', `/api/loan/${recordId}`, sidStudent, body({ amount: '13500', remark: '改过金额' }));
  check('学生改写自己的 submitted -> 200', upd.code === 200, `${upd.code} ${upd.message}`);
  check('改写后金额是 13500', upd.data && String(upd.data.amount).replace(/\.0+$/, '') === '13500', upd.data && upd.data.amount);

  const othersUpd = await call('PUT', `/api/loan/${recordId}`, sidOther, body());
  check('别的学生改写 -> 403 只能修改自己的登记', othersUpd.code === 403 && /只能修改自己的登记/.test(othersUpd.message || ''), `${othersUpd.code} ${othersUpd.message}`);

  const othersDetail = await call('GET', `/api/loan/${recordId}`, sidOther);
  check('别的学生看详情 -> 403', othersDetail.code === 403, `${othersDetail.code} ${othersDetail.message}`);

  const othersWithdraw = await call('POST', `/api/loan/${recordId}/withdraw`, sidOther);
  check('别的学生撤回 -> 400 撤回失败', othersWithdraw.code === 400 && /撤回失败/.test(othersWithdraw.message || ''), `${othersWithdraw.code} ${othersWithdraw.message}`);

  const disb = await call('POST', `/api/loan/${recordId}/disbursed`, sidStudent, { disbursed: '是', disbursedAt: '2026-09-01' });
  check('学生标记已到账 -> 200 且 disbursed=1', disb.code === 200 && disb.data && Number(disb.data.disbursed) === 1, `${disb.code} ${disb.message}`);

  const disbBad = await call('POST', `/api/loan/${recordId}/disbursed`, sidStudent, { disbursed: '也许' });
  check('到账状态填怪值 -> 400 是/否', disbBad.code === 400 && /是 \/ 否/.test(disbBad.message || ''), `${disbBad.message}`);

  const detail = await call('GET', `/api/loan/${recordId}`, sidStudent);
  check('学生看自己的详情 -> 200', detail.code === 200 && detail.data && detail.data.id === recordId, `${detail.code} ${detail.message}`);
}

line('== 4) 员工面：粗筛 + 辅导员范围 ==');
{
  const stuReview = await call('GET', '/api/loan/review', sidStudent);
  check('学生打员工名单 -> 403（框架层粗筛先答）', stuReview.code === 403, `${stuReview.code} ${stuReview.message}`);
  const stuExport = await call('GET', '/api/loan/export', sidStudent);
  check('学生打导出 -> 403', stuExport.code === 403, `${stuExport.code} ${stuExport.message}`);

  const tReview = await call('GET', `/api/loan/review?academicYear=${encodeURIComponent(YEAR)}`, sidTeacher);
  check('辅导员（本人学生）看名单 -> 200 且 scope=counselor', tReview.code === 200 && tReview.data.scope === 'counselor', `${tReview.code} ${tReview.message} ${tReview.data && tReview.data.scope}`);
  check('辅导员名单里能看到这条', (tReview.data.list || []).some((r) => r.id === recordId), `total=${tReview.data && tReview.data.total}`);

  const t2Review = await call('GET', `/api/loan/review?academicYear=${encodeURIComponent(YEAR)}`, sidTeacher2);
  check('别的辅导员看名单 -> 200 但看不到这条（数据级隔离）',
    t2Review.code === 200 && !(t2Review.data.list || []).some((r) => r.id === recordId), `total=${t2Review.data && t2Review.data.total}`);

  const stats = await call('GET', `/api/loan/review/stats?academicYear=${encodeURIComponent(YEAR)}`, sidTeacher);
  check('辅导员看统计 -> 200 且计数里有这条', stats.code === 200 && stats.data && Number(stats.data.total) >= 1, `${stats.code} ${stats.message} total=${stats.data && stats.data.total}`);

  const adm = await call('GET', `/api/loan/review?academicYear=${encodeURIComponent(YEAR)}`, sidAdmin);
  check('管理员看名单 -> 200 且 scope=all', adm.code === 200 && adm.data.scope === 'all', `${adm.code} ${adm.message}`);
}

line('== 5) 状态机：辅导员核验 -> 学校确认 ==');
{
  const stuReview = await call('POST', `/api/loan/${recordId}/counselor-review`, sidStudent, { action: 'approve' });
  check('学生自己核验 -> 403', stuReview.code === 403, `${stuReview.code} ${stuReview.message}`);

  const noReason = await call('POST', `/api/loan/${recordId}/counselor-review`, sidTeacher, { action: 'return', reason: '' });
  check('退回不填原因 -> 400', noReason.code === 400 && /原因/.test(noReason.message || ''), `${noReason.message}`);

  const ret = await call('POST', `/api/loan/${recordId}/counselor-review`, sidTeacher, { action: 'return', reason: '回执号看不清，请重拍' });
  check('退回 -> 200 状态 returned', ret.code === 200 && ret.data && ret.data.status === 'returned', `${ret.code} ${ret.message} ${ret.data && ret.data.status}`);

  const resubmit = await call('PUT', `/api/loan/${recordId}`, sidStudent, body({ receiptNo: 'HZ2026200099', remark: '已重拍回执' }));
  check('退回后可再改并重新提交 -> 200 submitted', resubmit.code === 200 && resubmit.data && resubmit.data.status === 'submitted', `${resubmit.code} ${resubmit.message}`);

  const early = await call('POST', `/api/loan/${recordId}/confirm`, sidAdmin);
  check('还没核验就确认 -> 400「需先由辅导员核验」', early.code === 400 && /需先由辅导员核验/.test(early.message || ''), `${early.message}`);

  const tConfirm = await call('POST', `/api/loan/${recordId}/confirm`, sidTeacher);
  check('辅导员确认 -> 403（只有学校资助中心能确认）', tConfirm.code === 403, `${tConfirm.code} ${tConfirm.message}`);

  const approve = await call('POST', `/api/loan/${recordId}/counselor-review`, sidTeacher, { action: 'approve' });
  check('辅导员核验通过 -> 200 counselor_approved', approve.code === 200 && approve.data && approve.data.status === 'counselor_approved', `${approve.code} ${approve.message} ${approve.data && approve.data.status}`);

  const approveAgain = await call('POST', `/api/loan/${recordId}/counselor-review`, sidTeacher, { action: 'approve' });
  check('重复核验 -> 400 状态已变', approveAgain.code === 400, `${approveAgain.code} ${approveAgain.message}`);

  const stuEditLocked = await call('PUT', `/api/loan/${recordId}`, sidStudent, body());
  check('辅导员核验后学生不能再改 -> 400', stuEditLocked.code === 400 && /不能修改/.test(stuEditLocked.message || ''), `${stuEditLocked.message}`);

  const confirm = await call('POST', `/api/loan/${recordId}/confirm`, sidAdmin);
  check('管理员确认 -> 200 confirmed', confirm.code === 200 && confirm.data && confirm.data.status === 'confirmed', `${confirm.code} ${confirm.message} ${confirm.data && confirm.data.status}`);

  const confirmAgain = await call('POST', `/api/loan/${recordId}/confirm`, sidAdmin);
  check('重复确认 -> 400', confirmAgain.code === 400, `${confirmAgain.code} ${confirmAgain.message}`);

  const withdrawConfirmed = await call('POST', `/api/loan/${recordId}/withdraw`, sidStudent);
  check('已确认的登记不能撤回 -> 400', withdrawConfirmed.code === 400 && /撤回失败/.test(withdrawConfirmed.message || ''), `${withdrawConfirmed.message}`);

  const myAfter = await call('GET', `/api/loan/my?academicYear=${encodeURIComponent(YEAR)}`, sidStudent);
  check('学生自己的台账里这条已是 confirmed', (myAfter.data.list || []).some((r) => r.id === recordId && r.status === 'confirmed'), JSON.stringify((myAfter.data.list || []).map((r) => r.status)));
}

line('== 6) 撤销路径：student 撤 submitted / 换学年重提 ==');
{
  const created = await call('POST', '/api/loan', sidOther, body({ academicYear: '2098-2099', contractNo: 'E2E19_W' }));
  check('另一个学生提交 2098-2099 -> 200', created.code === 200, `${created.code} ${created.message}`);
  const wid = created.data && created.data.id;
  if (wid) createdIds.push(wid);
  const w = await call('POST', `/api/loan/${wid}/withdraw`, sidOther);
  check('撤回自己的 submitted -> 200 withdrawn', w.code === 200 && w.data && w.data.status === 'withdrawn', `${w.code} ${w.message}`);
  const again = await call('POST', '/api/loan', sidOther, body({ academicYear: '2098-2099', contractNo: 'E2E19_W2' }));
  check('撤回后同学年可重新提交 -> 200', again.code === 200, `${again.code} ${again.message}`);
  if (again.data && again.data.id) createdIds.push(again.data.id);
}

line('== 7) 导出 CSV：脱敏 + 拒绝 ==');
{
  // 不带学年过滤：这样 liusq（有中文名）那条也会进导出，才能验到姓名脱敏。
  const raw = await callRaw('/api/loan/export', sidAdmin);
  check('管理员导出 -> HTTP 200 且是 CSV', raw.status === 200 && /text\/csv/.test(raw.headers.get('content-type') || ''), `${raw.status} ${raw.headers.get('content-type')}`);
  check('导出带 UTF-8 BOM（原始字节 EF BB BF）',
    raw.buf.length > 3 && raw.buf[0] === 0xEF && raw.buf[1] === 0xBB && raw.buf[2] === 0xBF,
    raw.buf.subarray(0, 3).toString('hex'));
  check('导出带 attachment 文件名', /attachment; filename="student-loan-\d{8}\.csv"/.test(raw.headers.get('content-disposition') || ''), raw.headers.get('content-disposition'));
  const csv = raw.buf.subarray(3).toString('utf8');
  const header = csv.split(/\r?\n/)[0] || '';
  check('CSV 表头 14 列且含「学号」与「经办机构」', header.split(',').length === 14 && /学号/.test(header) && /经办机构/.test(header), header);
  check('CSV 里学号已脱敏（只留后 4 位，不出现完整学号）', !/20240\d{2}/.test(csv) && /\b400[34]\b/.test(csv), csv.slice(0, 160));
  check('CSV 里姓名只留首字打星（刘思琪 -> 刘*）', /刘\*/.test(csv), csv.slice(0, 200));

  const t = await call('GET', `/api/loan/export?academicYear=${encodeURIComponent(YEAR)}`, sidTeacher);
  check('辅导员导出 -> HTTP 200（只在本人范围内）', t.status === 200, `http=${t.status}`);
}

line('== 8) 汇总口径与台账一致性 ==');
{
  const sum = await call('GET', '/api/loan/summary', sidStudent);
  const detail = await call('GET', `/api/loan/${recordId}`, sidStudent);
  const expectAmount = detail.data && String(detail.data.amount).replace(/\.0+$/, '');
  const confirmedAmount = sum.data && String(sum.data.confirmedAmount);
  check(`已确认金额合计 = 这条登记金额（${expectAmount}）`, confirmedAmount === expectAmount, `summary=${confirmedAmount} detail=${expectAmount}`);
  check('汇总里 years 含本次验证的学年', (sum.data.years || []).includes(YEAR), JSON.stringify(sum.data.years));
  const st = sum.data.statusText || {};
  check('状态中文映射六条齐全', ['submitted', 'counselor_approved', 'confirmed', 'returned', 'rejected', 'withdrawn'].every((k) => typeof st[k] === 'string'), JSON.stringify(st));
}

line('');
line(`检查项 ${checks}，失败 ${fails}`);
const verdict = fails === 0 ? 'VERDICT OK' : 'VERDICT FAIL';
line(verdict);
fs.writeFileSync('target/verify-loan.log', log.join('\n') + '\n', 'utf8');

const sql = [
  '-- P19 真机验证残渣清理（verify-loan.mjs 生成）',
  "DELETE FROM student_loan WHERE academic_year IN ('2099-2100','2098-2099');",
  'SELECT COUNT(*) AS rows_left FROM student_loan;',
  ''
].join('\n');
fs.writeFileSync('target/clean-loan.sql', sql, 'utf8');
console.log(`已创建 id: ${createdIds.join(', ')}；清理语句见 target\\clean-loan.sql`);

if (fails > 0) process.exit(1);

#!/usr/bin/env node
/**
 * P0-3 数据级授权实测探针（真实登录态、真实接口，不看代码看行为）。
 *
 * 为什么要有它：P0-3 的验收口径是「接口级注解 + 资源归属校验 + 越权用例测试」。
 * 单元测试覆盖的是 service 内部的分支；这里补的是「从 HTTP 进来，带着真实 session，
 * 换不同角色去读同一条数据，返回码到底对不对」——代码审计容易漏掉拦截器、参数搬运、
 * 路由前缀这些层，真机探一遍才算数。
 *
 * 用法：
 *   node tools/authz-probe.mjs                 # 需 8080 已启动
 *   node tools/authz-probe.mjs --out target/authz-matrix.md
 *
 * 退出码：有 cell 不符合预期 → 1（可接进收尾测试）。
 */

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const BASE = process.env.NEST_BASE || 'http://127.0.0.1:8080';
const PASSWORD = process.env.NEST_PROBE_PASSWORD || '123456';
const OUT = (() => {
  const i = process.argv.indexOf('--out');
  return i > 0 ? process.argv[i + 1] : 'target/authz-matrix.md';
})();

/** 账号：admin=1；lisi(3) 是 4/5/6 的辅导员；zhangsan(2) 是 7/8 的辅导员。 */
const ACCOUNTS = [
  { key: 'admin', username: 'admin', label: '管理员' },
  { key: 'lisi', username: 'lisi', label: '辅导员A(lisi)' },
  { key: 'zhangsan', username: 'zhangsan', label: '辅导员B(zhangsan)' },
  { key: 'wangxm', username: 'wangxm', label: '学生本人(wangxm,4)' },
  { key: 'liusq', username: 'liusq', label: '其他学生(liusq,7)' },
];

async function login(username) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: PASSWORD }),
  });
  const setCookie = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  const raw = setCookie.join(';') || res.headers.get('set-cookie') || '';
  const m = /JSESSIONID=([^;]+)/.exec(raw);
  const json = await res.json().catch(() => ({}));
  if (!m) {
    throw new Error(`登录失败 ${username}: HTTP ${res.status} code=${json.code} msg=${json.message}`);
  }
  return m[1];
}

async function get(sid, path) {
  const res = await fetch(BASE + path, { headers: sid ? { Cookie: `JSESSIONID=${sid}` } : {} });
  let json = {};
  try {
    json = await res.json();
  } catch {
    /* 空 body（例如 CSV/空响应）留空 */
  }
  return { http: res.status, code: json.code, message: json.message, data: json.data };
}

/**
 * 期望矩阵。每条 cell 是一个断言：某角色读某接口 → 期望的业务 code。
 * 只写「有确定语义」的 cell；不确定的接口不写，避免把探针变成猜测。
 */
const CELLS = [
  // ── 综测明细（本轮修复的越权点：原来只判 staff，任何教师能读任意学生）──
  { api: 'GET /api/rank/detail/{studentId}', target: 'wangxm(4)', actor: 'admin', expect: 200, why: 'admin 全校' },
  { api: 'GET /api/rank/detail/{studentId}', target: 'wangxm(4)', actor: 'lisi', expect: 200, why: 'lisi 是 4 号名下辅导员' },
  { api: 'GET /api/rank/detail/{studentId}', target: 'wangxm(4)', actor: 'zhangsan', expect: 403, why: '跨辅导员必须 403' },
  { api: 'GET /api/rank/detail/{studentId}', target: 'wangxm(4)', actor: 'wangxm', expect: 403, why: '学生不读综测明细' },
  { api: 'GET /api/rank/detail/{studentId}', target: 'liusq(7)', actor: 'lisi', expect: 403, why: 'lisi 不是 7 号辅导员' },
  { api: 'GET /api/rank/detail/{studentId}', target: 'liusq(7)', actor: 'zhangsan', expect: 200, why: 'zhangsan 是 7 号名下辅导员' },
  { api: 'GET /api/rank/detail/{studentId}', target: 'wangxm(4)', actor: 'anon', expect: 401, why: '未登录' },

  // ── 综测打分表（P3 起就有 canTouch）──
  { api: 'GET /api/eval/student/{userId}', target: 'wangxm(4)', actor: 'admin', expect: 200, why: 'admin 全校' },
  { api: 'GET /api/eval/student/{userId}', target: 'wangxm(4)', actor: 'lisi', expect: 200, why: '名下辅导员' },
  { api: 'GET /api/eval/student/{userId}', target: 'wangxm(4)', actor: 'zhangsan', expect: 403, why: '跨辅导员' },
  { api: 'GET /api/eval/student/{userId}', target: 'wangxm(4)', actor: 'anon', expect: 401, why: '未登录' },

  // ── 学生画像（辅导员工作台）──
  { api: 'GET /api/counselor/students/{id}/portrait', target: 'wangxm(4)', actor: 'admin', expect: 200, why: 'admin 全校' },
  { api: 'GET /api/counselor/students/{id}/portrait', target: 'wangxm(4)', actor: 'lisi', expect: 200, why: '名下辅导员' },
  { api: 'GET /api/counselor/students/{id}/portrait', target: 'wangxm(4)', actor: 'zhangsan', expect: 403, why: '跨辅导员' },
  { api: 'GET /api/counselor/students/{id}/portrait', target: 'wangxm(4)', actor: 'wangxm', expect: 403, why: '学生本人也不走工作台画像' },
  { api: 'GET /api/counselor/students/{id}/portrait', target: 'wangxm(4)', actor: 'anon', expect: 401, why: '未登录' },

  // ── 志愿服务时长汇总（P17 自己写的 403 文案）──
  { api: 'GET /api/volunteer/summary/{userId}', target: 'wangxm(4)', actor: 'admin', expect: 200, why: 'admin 全校' },
  { api: 'GET /api/volunteer/summary/{userId}', target: 'wangxm(4)', actor: 'lisi', expect: 200, why: '名下辅导员' },
  { api: 'GET /api/volunteer/summary/{userId}', target: 'wangxm(4)', actor: 'zhangsan', expect: 403, why: '跨辅导员' },
  { api: 'GET /api/volunteer/summary/{userId}', target: 'wangxm(4)', actor: 'wangxm', expect: 200, why: '学生看自己' },
  { api: 'GET /api/volunteer/summary/{userId}', target: 'wangxm(4)', actor: 'liusq', expect: 403, why: '学生看别人' },
  { api: 'GET /api/volunteer/summary/{userId}', target: 'wangxm(4)', actor: 'anon', expect: 401, why: '未登录' },
  { api: 'GET /api/volunteer/summary/{userId}', target: 'liusq(7)', actor: 'lisi', expect: 403, why: '跨辅导员（另一方向）' },
  { api: 'GET /api/volunteer/summary/{userId}', target: 'liusq(7)', actor: 'zhangsan', expect: 200, why: '名下辅导员（另一方向）' },

  // ── 宿舍域（整段由 DormStaffInterceptor 默认拒绝）──
  { api: 'GET /api/dorm/missingness/summary', target: '-', actor: 'admin', expect: 200, why: '宿舍域默认只放 admin' },
  { api: 'GET /api/dorm/missingness/summary', target: '-', actor: 'lisi', expect: 403, why: '教师不在 dorm staff 名单内' },
  { api: 'GET /api/dorm/missingness/summary', target: '-', actor: 'wangxm', expect: 403, why: '学生不在名单内' },
  { api: 'GET /api/dorm/missingness/summary', target: '-', actor: 'anon', expect: 401, why: '未登录' },

  // ── 本人视角（登录即可，只看自己）──
  { api: 'GET /api/rank/my', target: '-', actor: 'wangxm', expect: 200, why: '本人综测' },
  { api: 'GET /api/rank/my', target: '-', actor: 'anon', expect: 401, why: '未登录' },
  { api: 'GET /api/psyche/my', target: '-', actor: 'wangxm', expect: 200, why: '本人测评' },
  { api: 'GET /api/psyche/my', target: '-', actor: 'anon', expect: 401, why: '未登录' },
];

const TARGET_ID = {
  'wangxm(4)': 4,
  'liusq(7)': 7,
};

function resolvePath(api, target) {
  const base = api.replace('GET ', '');
  return base.replace(/\{(studentId|userId|id)\}/g, String(TARGET_ID[target] ?? target));
}

/** 从任意嵌套结构里找第一个带数字 id 的对象（批次列表的包装层各版本不一致，别写死）。 */
function firstId(data) {
  if (Array.isArray(data)) {
    for (const item of data) {
      const id = firstId(item);
      if (id != null) return id;
    }
    return null;
  }
  if (data && typeof data === 'object') {
    if (typeof data.id === 'number') return data.id;
    if (typeof data.batchId === 'number') return data.batchId;
    for (const v of Object.values(data)) {
      const id = firstId(v);
      if (id != null) return id;
    }
  }
  return null;
}

async function main() {
  console.log(`[authz] base=${BASE}`);
  const sids = {};
  for (const a of ACCOUNTS) {
    sids[a.key] = await login(a.username);
    console.log(`[authz] 登录 ok: ${a.key}`);
  }

  const results = [];
  // 综测明细必须带批次号：先用 admin 查一次最新批次，查不到就把这几条标为「跳过」而不是误判失败
  const needBatch = CELLS.some((c) => c.api.includes('/api/rank/detail/'));
  let batchId = null;
  if (needBatch) {
    const b = await get(sids.admin, '/api/rank/batches');
    batchId = firstId(b.data);
    console.log(`[authz] 综测批次 batchId=${batchId ?? '(无)'}`);
  }

  for (const cell of CELLS) {
    let path = resolvePath(cell.api, cell.target);
    if (cell.api.includes('/api/rank/detail/')) {
      if (batchId == null) {
        results.push({ ...cell, path, actual: 'skip', http: '-', message: '库里没有综测批次', skipped: true });
        continue;
      }
      path += `?batchId=${batchId}`;
    }
    const sid = cell.actor === 'anon' ? null : sids[cell.actor];
    const r = await get(sid, path);
    // 拦截器直接写 Result JSON（code=401/403）与 service 返回同构；若 body 为空则回落 HTTP 状态
    const actual = r.code ?? r.http;
    results.push({ ...cell, path, actual, http: r.http, message: r.message ?? '' });
  }

  const failed = results.filter((r) => r.actual !== r.expect);
  const lines = [];
  lines.push('# P0-3 数据级授权实测矩阵');
  lines.push('');
  lines.push(`- 探针：\`tools/authz-probe.mjs\`；目标：\`${BASE}\`；生成时间：${new Date().toISOString()}`);
  lines.push(`- 账号：admin=管理员；lisi=辅导员（名下 4/5/6）；zhangsan=辅导员（名下 7/8）；wangxm=学生 4；liusq=学生 7`);
  lines.push('- 口径：断言的是**业务 code**（本项目所有接口都 HTTP 200 + JSON code），拦截器写出的 401/403 同构；`anon` = 不带 Cookie。');
  lines.push(`- 结果：**${results.length - failed.length}/${results.length} 符合预期**。`);
  lines.push('');
  lines.push('| 接口 | 目标 | 角色 | 期望 | 实际 | HTTP | 结论 | 依据 |');
  lines.push('|---|---|---|---|---|---|---|---|');
  for (const r of results) {
    const ok = r.actual === r.expect ? '✅' : '❌';
    const msg = r.message ? `（${String(r.message).replace(/\|/g, '\\|')}）` : '';
    lines.push(`| \`${r.api}\` | ${r.target} | ${r.actor} | ${r.expect} | **${r.actual}**${msg} | ${r.http} | ${ok} | ${r.why} |`);
  }
  lines.push('');
  lines.push('## 未纳入本矩阵的已知项（有意保留现状，不作为缺陷）');
  lines.push('');
  lines.push('- `HonorService.reviewDetail`（评优台账）：按设计只判 staff，节点级范围在 `review()` 里用 `app.getCounselorId()` 判；评优域无「学院岗位绑定」，强行加辅导员范围会改变既定业务语义。');
  lines.push('- `PsycheService` 的辅导员口径是「跨辅导员 403」，与 P13 综测的 `scopeCounselor`（未分派学生的辅导员看全校）**刻意不同**：心理数据比综测更敏感。');
  lines.push('- 写接口（POST/PUT/DELETE）的越权由各 service 的单测覆盖，本探针只读，避免污染数据。');
  lines.push('');
  lines.push('## 复跑');
  lines.push('');
  lines.push('```powershell');
  lines.push('node tools/authz-probe.mjs --out target/authz-matrix.md');
  lines.push('```');

  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, lines.join('\n') + '\n', 'utf8');

  for (const r of failed) {
    console.log(`[authz] FAIL ${r.api} target=${r.target} actor=${r.actor} expect=${r.expect} actual=${r.actual} msg=${r.message}`);
  }
  console.log(`[authz] RESULT: ${results.length - failed.length}/${results.length} ok -> ${OUT}`);
  process.exit(failed.length === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('[authz] ERROR', e);
  process.exit(2);
});

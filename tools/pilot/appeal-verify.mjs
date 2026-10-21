// 申诉三层验收工具（试点实例 8081）
//   用法：node tools/pilot/appeal-verify.mjs dump           只打印数据形状，不做申诉
//         node tools/pilot/appeal-verify.mjs run <batchId>  在已有批次上跑两个用例
//
// 为什么用 Node 而不是 PowerShell：Invoke-RestMethod 会把 UTF-8 中文串成乱码，
// 而申诉的全部价值就在那些中文理由里；fetch 返回的 text() 是干净的 UTF-8。
const BASE = process.env.NEST_BASE || 'http://127.0.0.1:8081';
let COOKIE = '';

async function http(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(COOKIE ? { Cookie: COOKIE } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  for (const c of (res.headers.getSetCookie?.() || [])) {
    const m = c.match(/JSESSIONID=([^;]+)/);
    if (m) COOKIE = 'JSESSIONID=' + m[1];
  }
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 就返回原文 */ }
  return { status: res.status, json, text };
}

async function login() {
  const r = await http('POST', '/api/auth/login', { username: 'admin', password: '123456' });
  if (r.json?.code !== 200) throw new Error('登录失败: ' + r.text.slice(0, 200));
  return r.json.data;
}

async function assign(name) {
  const r = await http('POST', '/api/dorm/assign', { name, withExplanations: true });
  if (r.json?.code !== 200) throw new Error('分配失败: ' + r.text.slice(0, 400));
  return r.json.data;
}

// 申诉：房间级试算就在创建响应里；跨块试算是异步作业。
async function appeal(batchId, studentId, wantRoomWith, costBudget) {
  const r = await http('POST', `/api/dorm/batches/${batchId}/appeal`, { studentId, wantRoomWith, costBudget });
  if (r.json?.code !== 200) throw new Error('申诉失败: ' + r.text.slice(0, 400));
  return r.json.data;
}

async function cross(batchId, appealId, maxTicks = 200) {
  const r = await http('POST', `/api/dorm/batches/${batchId}/appeals/${appealId}/cross`);
  if (r.json?.code !== 200) throw new Error('跨块试算提交失败: ' + r.text.slice(0, 400));
  const jobId = r.json.data.jobId;
  for (let i = 0; i < maxTicks; i++) {
    await new Promise((s) => setTimeout(s, 400));
    const s2 = await http('GET', `/api/dorm/appeals/cross/${jobId}`);
    const d = s2.json?.data;
    if (d && d.state && d.state !== 'RUNNING' && d.state !== 'PENDING') return d;
  }
  return { state: 'TIMEOUT' };
}

const yuan = (d) => (typeof d === 'number' ? (d >= 0 ? '+' : '') + d.toFixed(6) : String(d));

function showTrial(tag, r) {
  console.log(`  [${tag}] status=${r.status}  scope=${r.scope}  elapsed=${r.elapsedMs}ms`);
  console.log(`         message: ${r.message}`);
  if (r.suggestions?.length) {
    for (const s of r.suggestions) {
      // 建议是结构化的 {fromStudent, toStudent, fromRoom, toRoom, text?}，直接插值会打成
      // 「[object Object]」。优先用它的 text，否则按字段拼一句可读的。
      // 建议是 AppealResult.Swap 记录 {studentId, studentName, fromRoom, toRoom,
      // fromLabel, toLabel, otherId, otherName, delta, note}，直接插值会打成
      // 「[object Object]」。按字段拼一句人话（系统里每次变动都写成「两人对调」）。
      const text = typeof s === 'string'
        ? s
        : (s.note
            || `${s.studentName}：${s.fromLabel} ⇄ ${s.toLabel}（与 ${s.otherName || '空床位'}，Δ ${s.delta}）`);
      console.log(`         建议: ${text}`);
    }
  }
  if (r.relaxations?.length) console.log(`         放宽: ${JSON.stringify(r.relaxations)}`);
  else if (r.relaxations) console.log('         放宽: []');
  // 判定以 status 为准：失败路径上的 number 位是占位 0，不是「代价为 0」，
  // 直接把它当数字打出来会被误读成「改动为零」。只有 SATISFIED 才报代价。
  if (r.status === 'SATISFIED') {
    if (typeof r.totalCostDelta === 'number') console.log(`         代价 totalCostDelta=${yuan(r.totalCostDelta)}  budget=${r.costBudget}`);
    if (typeof r.objectiveDelta === 'number') console.log(`         代价 objectiveDelta=${yuan(r.objectiveDelta)}  目标 ${r.objectiveBefore?.toFixed?.(4)} -> ${r.objectiveAfter?.toFixed?.(4)}`);
    if (r.moveCount != null) console.log(`         波及 移动 ${r.moveCount} 人，合并 ${r.mergedStudents} 人 / ${r.mergedRooms} 间`);
  } else {
    console.log('         代价: 本次试算没有产出可采纳的方案，因此不报代价、也不报波及人数。');
  }
  if (r.stillBlocked?.length) console.log(`         仍未满足: ${JSON.stringify(r.stillBlocked)}`);
}

async function main() {
  const mode = process.argv[2] || 'dump';
  const who = await login();
  console.log('登录成功:', JSON.stringify(who).slice(0, 200));

  if (mode === 'dump') {
    const a = await assign('试点-形状探查-' + Date.now());
    console.log('\n=== assign 返回 ===');
    console.log(JSON.stringify(a, null, 1).slice(0, 900));
    const batchId = a.batchId ?? a.id;
    const asg = (await http('GET', `/api/dorm/batches/${batchId}/assignments`)).json.data;
    console.log(`\n=== assignments（共 ${asg.length} 行，前 3 行）===`);
    console.log(JSON.stringify(asg.slice(0, 3), null, 1));
    const rooms = (await http('GET', `/api/dorm/batches/${batchId}/rooms`)).json.data;
    console.log(`\n=== rooms（共 ${rooms.length} 行，前 2 行）===`);
    console.log(JSON.stringify(rooms.slice(0, 2), null, 1));
    const blocks = {};
    for (const r of asg) { const k = r.blockKey ?? r.block ?? '?'; (blocks[k] = blocks[k] || []).push(r); }
    console.log('\n=== 块分布 ===');
    for (const [k, v] of Object.entries(blocks)) console.log(`${k}: ${v.length} 人`);
    console.log('\nbatchId=' + batchId);
    return;
  }

  if (mode === 'run') {
    const batchId = Number(process.argv[3]);
    const asg = (await http('GET', `/api/dorm/batches/${batchId}/assignments`)).json.data;
    console.log(`批次 ${batchId}：${asg.length} 行分配`);
    // 分配行是 snake_case；块键不返回，按 BlockDecomposer.blockKey 的语义自己拼：性别|普通or特殊|专业大类|年级
    const blockKeyOf = (s) => `${s.gender}|${s.special_need === 'NONE' ? 'N' : 'S'}|${s.major_cluster}|${s.grade}`;
    const byBlock = {};
    for (const r of asg) (byBlock[blockKeyOf(r)] = byBlock[blockKeyOf(r)] || []).push(r);
    console.log('块:', Object.entries(byBlock).map(([k, v]) => `${k}(${v.length})`).join(' '));
    const f = (s) => `${s.name}(${s.student_id}) ${s.room_label}`;

    const pickA = () => {
      for (const [k, v] of Object.entries(byBlock)) {
        if (k.includes('|S|')) continue; // 特殊需求单人房不适用
        for (const a of v) for (const b of v) if (a.room_id !== b.room_id) return [a, b, k];
      }
      return null;
    };
    const pickB = () => {
      const g = {};
      for (const r of asg) if (r.special_need === 'NONE') (g[r.gender + '|' + r.major_cluster] = g[r.gender + '|' + r.major_cluster] || []).push(r);
      for (const [, list] of Object.entries(g)) {
        for (const a of list) for (const b of list) {
          if (a.grade !== b.grade && blockKeyOf(a) !== blockKeyOf(b)) return [a, b];
        }
      }
      return null;
    };

    const one = async (tag, x, y, note) => {
      console.log(`\n=== ${tag}：让 ${f(x)} 与 ${f(y)} 同住 ===`);
      console.log(`  ${note}`);
      console.log(`  块 ${blockKeyOf(x)}(${x.major}/${x.grade}) / ${blockKeyOf(y)}(${y.major}/${y.grade})`);
      const ap = await appeal(batchId, x.student_id, [y.student_id], 0.5);
      const appealId = ap.appealId ?? ap.id;
      console.log(`  appealId=${appealId}`);
      showTrial('房间级', ap.result || ap);
      const cr = await cross(batchId, appealId);
      console.log(`  跨块级（state=${cr.state}）：`);
      showTrial('跨块级', cr.result || cr);
      return appealId;
    };

    const A = pickA();
    if (A) await one('用例 A（同块内、不同房）', A[0], A[1], '硬约束都满足，只差把两人排到一间');
    const B = pickB();
    if (B) await one('用例 B（跨年级同大类）', B[0], B[1], '年级不同 -> 跨块，必须放宽「年级不混住」或「专业大类集中」之一');
  }
}

main().catch((e) => { console.error('FAILED: ' + e.message); process.exit(1); });

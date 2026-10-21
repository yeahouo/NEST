#!/usr/bin/env node
/**
 * 试点名单工具：生成模拟名单，或者把名单 CSV 转成导入接口的 JSON。
 *
 * 用法：
 *   node tools/pilot/build-pilot.mjs gen
 *       生成 tools/pilot/名单模板.csv、试点名单.csv、试点房间.csv
 *   node tools/pilot/build-pilot.mjs convert [--students 路径] [--rooms 路径] [--out 路径] [--no-replace]
 *       把 CSV 转成 target/pilot-import.json（默认 students=试点名单.csv、rooms=试点房间.csv）
 *
 * 为什么用 Node 而不是 PowerShell 生成 JSON：`ConvertTo-Json` 会把「只有一个元素的数组」
 * 序列化成标量，于是 `"wantRoommateWith": "CS23011"` 而不是 `["CS23011"]`，服务端
 * 反序列化 `List<String>` 直接失败——而且只在「恰好填了一个志愿」时才出现，极难排查。
 * `JSON.stringify` 没有这个行为。
 *
 * CSV 里「空」的一律表示**未知**，不是 0。特征里某一维不知道就留空，转换出来是 null。
 * 拿 0 顶替会把「他什么也没说过」变成「他明确表示不介意」，这是两件事。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const PILOT = path.join(ROOT, 'tools', 'pilot');

/** 特征 11 维，顺序必须与 StudentFeature.toArray() 一致。 */
const DIMS = ['入睡时刻', '起床时刻', '吸烟', '整洁', '外向', '寝室学习',
  '噪声容忍', '社交活跃', '物品共享', '打游戏', '学业拼搏'];
const STUDENT_HEADER = ['学号', '姓名', '性别', '年级', '专业', '专业大类', '楼栋意愿', '特殊需求',
  ...DIMS, '想同住', '不想同住'];
const ROOM_HEADER = ['楼栋', '房号', '性别', '容量', '特殊需求宿舍', '年级限制'];

// ---------------------------------------------------------------- 生成试点数据

/** 真实专业名与专业大类（按学院口径分组，分块就是按这个切）。 */
const CLUSTERS = [
  { name: '信息类', abbr: 'JSJ', majors: [{ code: 'CS', name: '计算机科学与技术' }, { code: 'SE', name: '软件工程' }] },
  { name: '工科类', abbr: 'GK', majors: [{ code: 'AU', name: '自动化' }, { code: 'CE', name: '土木工程' }] },
  { name: '经管类', abbr: 'JG', majors: [{ code: 'BA', name: '工商管理' }, { code: 'AC', name: '会计学' }] },
  { name: '人文类', abbr: 'RW', majors: [{ code: 'EN', name: '英语' }, { code: 'LA', name: '法学' }] },
];
const GRADES = ['2023', '2024'];
const GENDERS = [
  { csv: '女', db: 'F', building: ['女生1号楼', '女生2号楼'] },
  { csv: '男', db: 'M', building: ['男生1号楼', '男生2号楼'] },
];

/** 4 个有特殊需求的学生（每个 (性别,年级,大类) 组一个，住单人间）。 */
const SPECIALS = [
  { code: 'AU', grade: '2023', gender: '女', seq: 41, need: 'WHEELCHAIR' },
  { code: 'EN', grade: '2024', gender: '女', seq: 41, need: 'QUIET' },
  { code: 'CS', grade: '2023', gender: '男', seq: 41, need: 'LOW_FLOOR' },
  { code: 'CE', grade: '2024', gender: '男', seq: 41, need: 'WHEELCHAIR' },
];

const SURNAMES = '王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘蒋蔡余杜'.split('');
const MALE_GIVEN = ['伟彬', '浩然', '子涵', '宇航', '泽宇', '博文', '嘉豪', '一鸣', '明轩', '思远',
  '俊杰', '天佑', '睿杰', '昊然', '晨曦', '文轩', '子豪', '家豪', '立诚', '景行'];
const FEMALE_GIVEN = ['欣怡', '雨轩', '佳仪', '语彤', '静雯', '若曦', '诗琪', '可欣', '雨桐', '雅静',
  '梦琪', '思彤', '芷若', '书瑶', '晓萱', '嘉怡', '雨薇', '奕辰', '沁雅', '知微'];

function makeRng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}
const rng = makeRng(20261008);
const ri = (n) => Math.floor(rng() * n);
const round2 = (v) => Math.round(v * 100) / 100;

/** 一个学生：没做问卷的人特征整段为 null；做了的也会故意漏 1~2 维，模拟真实零散缺失。 */
function feature(haveSurvey) {
  if (!haveSurvey) return null;
  const v = [
    round2(21.5 + ri(9) * 0.5),          // 入睡 21.5~25.5
    round2(6.0 + ri(10) * 0.5),          // 起床 6.0~10.5
    ...Array.from({ length: 9 }, () => round2(0.05 + rng() * 0.9)),
  ];
  for (let k = 0; k < (rng() < 0.45 ? 2 : 1); k++) v[2 + ri(9)] = null;   // 零散漏答
  return v;
}

function generate() {
  const students = [];
  const majorOf = new Map();
  for (const c of CLUSTERS) for (const m of c.majors) majorOf.set(m.code, { ...m, cluster: c.name });

  let nameSeq = 0;
  for (const cluster of CLUSTERS) {
    for (const grade of GRADES) {
      for (const g of GENDERS) {
        const group = [];   // 先攒够这一组，才能在同组内互相填志愿
        for (const m of cluster.majors) {
          for (let i = 1; i <= 10; i++) {
            const seq = (grade === '2023' ? 0 : 20) + (g.db === 'F' ? i : 10 + i);
            const given = g.db === 'F' ? FEMALE_GIVEN[nameSeq % 20] : MALE_GIVEN[nameSeq % 20];
            nameSeq++;
            group.push({
              no: `${m.code}${grade.slice(2)}${String(seq).padStart(3, '0')}`,
              name: SURNAMES[nameSeq % SURNAMES.length] + given,
              gender: g.csv,
              grade,
              major: m.name,
              cluster: cluster.name,
              building: g.building,
              need: '',
              feature: feature(rng() < 0.35),
            });
          }
        }
        addWishes(group);
        students.push(...group);
      }
    }
  }

  // 4 个特殊需求学生：额外加在同一 (性别,年级,大类) 组里，住单人间。
  for (const sp of SPECIALS) {
    const m = majorOf.get(sp.code);
    const g = GENDERS.find((x) => x.csv === sp.gender);
    const given = sp.gender === '女' ? FEMALE_GIVEN[nameSeq % 20] : MALE_GIVEN[nameSeq % 20];
    nameSeq++;
    students.push({
      no: `${sp.code}${sp.grade.slice(2)}${String(sp.seq).padStart(3, '0')}`,
      name: SURNAMES[nameSeq % SURNAMES.length] + given,
      gender: sp.gender,
      grade: sp.grade,
      major: m.name,
      cluster: m.cluster,
      building: g.building,
      need: sp.need,
      // 特殊需求学生不进 addWishes（他们单独成块），但字段要齐全，
      // 否则 CSV 那一列会拿到 undefined 而整行炸掉
      buildingPref: '',
      want: [],
      avoid: [],
      feature: feature(rng() < 0.5),
    });
  }
  return students;
}

/** 给一组人填志愿：约 18% 有想同住、约 6% 有不想同住、约 25% 有楼栋意愿。 */
function addWishes(group) {
  for (let i = 0; i < group.length; i++) {
    const s = group[i];
    s.want = [];
    s.avoid = [];
    const others = group.filter((x) => x !== s).map((x) => x.no);
    if (rng() < 0.18) {
      const n = rng() < 0.35 ? 2 : 1;
      for (let k = 0; k < n; k++) {
        const pick = others[ri(others.length)];
        if (!s.want.includes(pick)) s.want.push(pick);
      }
    }
    if (rng() < 0.06) {
      const pick = others[ri(others.length)];
      if (!s.want.includes(pick)) s.avoid.push(pick);
    }
    // 楼栋意愿：多数只写自己学院那栋，少数写另一栋（用来观察无法满足时的表现）
    s.buildingPref = '';
    if (rng() < 0.25) {
      s.buildingPref = rng() < 0.7 ? s.building[0] : s.building[1];
    }
  }
}

/** 房间：每个 (性别,普通/特殊,大类,年级) 块都恰好住满，这样 CP-SAT 才证得出最优。 */
function generateRooms(students) {
  const rooms = [];
  const byKey = new Map();
  for (const s of students) {
    const key = `${s.gender}|${s.need ? 'S' : 'N'}|${s.cluster}|${s.grade}`;
    if (!byKey.has(key)) byKey.set(key, []);
    byKey.get(key).push(s);
  }
  const used = { '女生1号楼': 0, '女生2号楼': 0, '男生1号楼': 0, '男生2号楼': 0 };
  const specialIdx = { '女生1号楼': 900, '女生2号楼': 900, '男生1号楼': 900, '男生2号楼': 900 };

  const keys = [...byKey.keys()].sort();
  for (const key of keys) {
    const [gender, kind, cluster, grade] = key.split('|');
    const list = byKey.get(key);
    const g = GENDERS.find((x) => x.csv === gender);
    // 普通块：同一年级的两个大类各占一半房间，分到该性别的两栋楼里（一栋一年，贴近真实安排）
    const building = g.building[grade === '2023' ? 0 : 1];
    if (kind === 'N') {
      const n = Math.ceil(list.length / 4);
      for (let i = 0; i < n; i++) {
        used[building]++;
        rooms.push({
          buildingId: building,
          roomNo: String(100 + used[building]),
          gender,
          capacity: 4,
          special: false,
          gradeRestriction: '',
        });
      }
    } else {
      for (let i = 0; i < list.length; i++) {
        specialIdx[building]++;
        rooms.push({
          buildingId: building,
          roomNo: String(specialIdx[building]),
          gender,
          capacity: 1,
          special: true,
          gradeRestriction: '',
        });
      }
    }
  }
  return rooms;
}

// ---------------------------------------------------------------- CSV 读写

const csvCell = (v) => {
  const s = v === null || v === undefined ? '' : String(v);
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};

/** 写 CSV 时带 UTF-8 BOM：Excel 在中文 Windows 上没有 BOM 就会按 GBK 打开，中文全成乱码。 */
function writeCsv(file, header, rows) {
  const text = '\ufeff' + [header, ...rows].map((r) => r.map(csvCell).join(',')).join('\r\n') + '\r\n';
  fs.writeFileSync(file, text, 'utf8');
  return rows.length;
}

function readCsv(file) {
  const text = fs.readFileSync(file, 'utf8').replace(/^\ufeff/, '');
  const rows = [];
  let row = [], cell = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"') {
        if (text[i + 1] === '"') { cell += '"'; i++; } else quoted = false;
      } else cell += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ',') { row.push(cell); cell = ''; }
    else if (ch === '\n') { row.push(cell.replace(/\r$/, '')); rows.push(row); row = []; cell = ''; }
    else cell += ch;
  }
  if (cell !== '' || row.length) { row.push(cell.replace(/\r$/, '')); rows.push(row); }
  const header = rows.shift();
  if (!header) return [];
  return rows.filter((r) => r.some((c) => c !== '')).map((r) => {
    const o = {};
    header.forEach((h, i) => { o[h.trim()] = (r[i] ?? '').trim(); });
    return o;
  });
}

// ---------------------------------------------------------------- 两个子命令

function cmdGen() {
  const students = generate();
  const rooms = generateRooms(students);

  writeCsv(path.join(PILOT, '试点名单.csv'), STUDENT_HEADER, students.map((s) => [
    s.no, s.name, s.gender, s.grade, s.major, s.cluster,
    s.buildingPref, s.need, ...(s.feature || Array(11).fill(null)),
    s.want.join(';'), s.avoid.join(';'),
  ]));
  const roomRows = writeCsv(path.join(PILOT, '试点房间.csv'), ROOM_HEADER, rooms.map((r) => [
    r.buildingId, r.roomNo, r.gender, r.capacity, r.special ? '是' : '否', r.gradeRestriction,
  ]));

  // 给学校看的模板：只有表头 + 两行示例
  writeCsv(path.join(PILOT, '名单模板.csv'), STUDENT_HEADER, [
    ['CS23001', '王小明', '男', '2023', '计算机科学与技术', '信息类', '男生1号楼', '',
      '23.5', '8', '0', '0.8', '0.6', '', '0.4', '0.3', '0.7', '0.2', '0.9', 'CS23002', ''],
    ['CS23002', '李小红', '女', '2023', '计算机科学与技术', '信息类', '', '',
      '', '', '', '', '', '', '', '', '', '', '', '', ''],
  ]);

  const beds = rooms.reduce((a, r) => a + r.capacity, 0);
  console.log(`试点名单：${students.length} 人`);
  console.log(`试点房间：${roomRows} 间，${beds} 个床位`);
  console.log(`带特征：${students.filter((s) => s.feature).length} 人`);
  console.log(`有想同住志愿：${students.filter((s) => s.want.length).length} 人`);
  console.log(`有不想同住志愿：${students.filter((s) => s.avoid.length).length} 人`);
  console.log(`有楼栋意愿：${students.filter((s) => s.buildingPref).length} 人`);
  console.log(`特殊需求：${students.filter((s) => s.need).length} 人`);
  for (const g of ['女', '男']) {
    const sn = students.filter((s) => s.gender === g).length;
    const sb = rooms.filter((r) => r.gender === g).reduce((a, r) => a + r.capacity, 0);
    console.log(`  ${g}生：${sn} 人 / ${sb} 床位`);
  }
}

function cmdConvert(argv) {
  const get = (flag, dflt) => {
    const i = argv.indexOf(flag);
    return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
  };
  const stuFile = path.resolve(ROOT, get('--students', path.join('tools/pilot', '试点名单.csv')));
  const roomFile = path.resolve(ROOT, get('--rooms', path.join('tools/pilot', '试点房间.csv')));
  const outFile = path.resolve(ROOT, get('--out', 'target/pilot-import.json'));
  const replaceAll = !argv.includes('--no-replace');

  const rows = readCsv(stuFile);
  const students = rows.map((r, i) => {
    const vals = DIMS.map((d) => r[d]);
    const known = vals.filter((v) => v !== '' && v !== null);
    const split = (s) => (s || '').split(/[,，;；、\s]+/).filter(Boolean);
    return {
      studentNo: r['学号'],
      name: r['姓名'] || null,
      gender: r['性别'] || null,
      grade: r['年级'] || null,
      major: r['专业'] || null,
      majorCluster: r['专业大类'] || null,
      buildingPref: r['楼栋意愿'] || '',
      specialNeed: r['特殊需求'] || '',
      // 整段没填 → null（特征全缺）；填了一部分 → 逐维 null，让服务端自己判缺失
      feature: known.length === 0 ? null : vals.map((v) => (v === '' ? null : Number(v))),
      wantRoommateWith: split(r['想同住']),
      avoidRoommateWith: split(r['不想同住']),
      _row: i + 2,
    };
  });

  let rooms = [];
  if (fs.existsSync(roomFile)) {
    rooms = readCsv(roomFile).map((r) => ({
      buildingId: r['楼栋'],
      roomNo: r['房号'],
      gender: r['性别'] || null,
      capacity: r['容量'] === '' ? null : Number(r['容量']),
      forSpecialNeeds: ['是', 'true', 'TRUE', '1', 'Y', 'y'].includes(r['特殊需求宿舍']),
      gradeRestriction: r['年级限制'] || null,
    }));
  }

  for (const s of students) delete s._row;
  const payload = { replaceAll, students, rooms };
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, JSON.stringify(payload), 'utf8');

  const withFeat = students.filter((s) => s.feature).length;
  const partial = students.filter((s) => s.feature && s.feature.some((v) => v === null)).length;
  console.log(`已写出 ${outFile}`);
  console.log(`  学生 ${students.length} 人（带特征 ${withFeat}，其中特征有零散缺失的 ${partial}）`);
  console.log(`  房间 ${rooms.length} 间，床位 ${rooms.reduce((a, r) => a + r.capacity, 0)} 个`);
  console.log(`  replaceAll=${replaceAll}，文件 ${fs.statSync(outFile).size} 字节`);
}

const argv = process.argv.slice(2);
const cmd = argv[0] || 'gen';
if (cmd === 'gen') cmdGen();
else if (cmd === 'convert') cmdConvert(argv);
else {
  console.error(`未知子命令 ${cmd}；可用：gen | convert`);
  process.exit(2);
}

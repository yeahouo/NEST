/**
 * tools/check-mapper-columns.mjs
 *
 * 目的：防「实体加了字段，但 MyBatis 映射没跟上」这类**静默**缺陷。
 *
 * 背景（第十九轮真事故）：给 User 实体加了 failedLoginCount / lockedUntil 两个登录锁定字段，
 * UserService 的逻辑全对、11 个单测全绿（mock 让 mapper 直接返回对象），
 * 但 UserMapper.xml 的 Base_Column_List 与 BaseResultMap 都没带这两列 ⇒ 真机上线后
 * failedLoginCount 永远是 null ⇒ 登录失败锁定**完全不生效**，且不报任何错。
 * 冒烟测试（连打 5 次错密码）才暴露出来。
 *
 * 判据：对每个实体类，找出映射它的 mapper XML，检查它的每个持久化字段是否在 XML 里出现过
 * （camelCase 或 snake_case 任一种）。允许的例外写在 ALLOW 里，每条都必须有理由。
 *
 * 用法：node tools/check-mapper-columns.mjs
 * 退出码：0 = 无缺失；1 = 有字段没被任何 mapper 引用。
 */

import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');
const ENTITY_DIR = path.join(ROOT, 'src', 'main', 'java', 'com', 'umi', 'nest', 'entity');
const MAPPER_DIR = path.join(ROOT, 'src', 'main', 'resources', 'mapper');

/** 允许的例外：实体 == 不落库或故意不在 Base_Column_List 里的字段 == 理由 */
const ALLOW = {
  'User.serialVersionUID': 'JVM 序列化常量，不是列',
  'User.account': 'LoginDTO 的兼容别名，不是列',
  // 以下 7 个是统计/展示字段：由 Service 用 Map 装配（AssignmentService.java:206-207、544-546；
  // QuizService.java:184-187、237-260），或仅作列表展示，从来不落库，因此 mapper 里不该有列。
  'Assignment.courseName': '列表展示字段，未落库',
  'Assignment.submittedCount': '统计字段，Service 用 Map 装配（AssignmentService.java:206）',
  'Assignment.gradedCount': '统计字段，Service 用 Map 装配（AssignmentService.java:207）',
  'Assignment.totalStudents': '统计字段，Service 用 Map 装配（AssignmentService.java:544）',
  'Quiz.submittedCount': '统计字段，Service 用 Map 装配（QuizService.java:184）',
  'Quiz.totalStudents': '统计字段，Service 用 Map 装配（QuizService.java:187）',
  'Quiz.avgScore': '统计字段，Service 用 Map 装配（QuizService.java:260）',
  // P13 补的两个：页面要直接渲染中文，服务端在返回前用 EvalBonusService.fillText(...) 统一补，
  // 与 statusText 同一口径（列里存的是英文码），不落库 ⇒ mapper 里不该有列。
  'EvalBonus.categoryText': '展示字段，服务端补（EvalBonusService.fillText / categoryText）',
  'EvalBonus.statusText': '展示字段，服务端补（EvalBonusService.fillText / statusText）',
  // P16 学生党建：下面这些是「服务端算出来的提示/时限字段」，列里存的是英文码或原始日期，
  // 展示文案与倒计时一律在 PartyService / PartyTrainingService 返回前装配（口径 §11.2-1：
  // 「应当/不得」是带 rule_ref 的提示，不落库、不阻断推进），因此 mapper 里不该有列。
  // 其余展示字段（userName/studentNo/className/statusText/…）都走 JOIN 的 AS 别名，仍由本检查覆盖。
  'PartyApplication.talkLimitTip': '提示字段，服务端按 TALK_WITHIN_DAYS 生成（PartyService 装配）',
  'PartyApplication.talkOverdue': '提示字段，服务端按当日比算（PartyService 装配）',
  'PartyStage.stageText': '展示字段，服务端按 stage 枚举转中文（PartyService.stageText）',
  'PartyStage.nextTip': '提示字段，服务端按下一步时限生成（PartyService.nextStageTip）',
  'PartyObservation.overdue': '提示字段，服务端按 THOUGHT_REPORT_WARN_DAYS 比算（PartyService 装配）',
  'PartyObservation.limitTip': '提示字段，服务端生成带 rule_ref 的时限提示（PartyService 装配）',
  'PartyTraining.hoursTip': '提示字段，国标/校规两条时长口径并列提示（PartyService 装配）',
  // 班次列表/详情的报名与结业统计：由 PartyTrainingService 用 trainingMapper.countMembers(...) 装配，
  // 不在班次表上落列（quota 才是落库的字段，countMembers 是它当时的占用数）。
  'PartyTraining.memberCount': '统计字段，PartyTrainingService 用 countMembers(id) 装配，未落库',
  'PartyTraining.completedCount': '统计字段，未落库（结业数由学员表按状态统计得出）',
  'PartyTrainingMember.certValid': '提示字段，服务端按当日比算证书有效期（PartyTrainingService 装配）',
  // 公示剩余天数：服务端按当日与 end_date 比算（PartyService.fill(PartyPublicity)），不落库。
  'PartyPublicity.remainDays': '提示字段，服务端按当日比算公示剩余天数（PartyService 装配，未落库）',
  // 枚举 → 中文的展示文案字段：服务端 fill() 时由 switch 转换（PartyService / PartyTrainingService），
  // 表里存的是枚举码（stage / type / review_status / node_type / status / complete_result），不存中文。
  'PartyMaterial.stageText': '展示字段，服务端按 stage 枚举转中文（PartyService 装配）',
  'PartyMaterial.requiredText': '展示字段，服务端按 required 0/1 转「应备/选备」（PartyService 装配）',
  'PartyMaterial.submittedText': '展示字段，服务端按 submitted 0/1 转「已交/未交」（PartyService 装配）',
  'PartyObservation.typeText': '展示字段，服务端按 type 枚举转中文（PartyService 装配）',
  'PartyObservation.reviewStatusText': '展示字段，服务端按 review_status 枚举转中文（PartyService 装配）',
  'PartyPublicity.nodeTypeText': '展示字段，服务端按 node_type 枚举转中文（PartyService 装配）',
  'PartyPublicity.statusText': '展示字段，服务端按 status 枚举转中文（PartyService 装配）',
  'PartyTraining.statusText': '展示字段，服务端按 status 枚举转中文（PartyTrainingService 装配）',
  'PartyTrainingMember.statusText': '展示字段，服务端按 status 枚举转中文（PartyTrainingService 装配）',
  'PartyTrainingMember.completeResultText': '展示字段，服务端按 complete_result pass/fail 转中文（PartyTrainingService 装配）',
};

const snake = (s) => s.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase();

/** 取 Java 实体的实例字段名 */
function fieldsOf(javaSrc) {
  const body = javaSrc.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
  const out = [];
  for (const m of body.matchAll(/(?:private|protected|public)\s+(?:static\s+|final\s+|transient\s+|volatile\s+)*([A-Za-z_$][\w$.<>,\[\]\s]*?)\s+([a-z_$][\w$]*)\s*[;=]/g)) {
    const type = m[1].trim();
    const name = m[2];
    if (/\bstatic\b/.test(type) || name === 'serialVersionUID') continue;
    out.push(name);
  }
  return [...new Set(out)];
}

/**
 * 把一个 mapper XML 里「能证明某列被读出来」的地方收集起来：
 *  1) <sql id="..."> 片段里的列名（Base_Column_List 就在这）
 *  2) 任意 <result column="x"> / <id column="x">
 *  3) 任意 `AS <alias>`（联表查询给实体用的别名，如 c.name AS courseName）
 * 注意：UPDATE 里的 SET 列**不算**覆盖 —— 只写得出来、读不回来的列正是本检查要抓的缺陷形态。
 */
function coverageOf(xml) {
  const ids = new Set();
  const add = (s) => { if (s) ids.add(s.toLowerCase()); };

  for (const m of xml.matchAll(/<sql\s+id="[^"]*"[^>]*>([\s\S]*?)<\/sql>/g)) {
    for (const t of m[1].matchAll(/[A-Za-z_][A-Za-z0-9_]*/g)) add(t[0]);
  }
  for (const m of xml.matchAll(/<(?:result|id)\s+column="([^"]+)"/g)) add(m[1]);
  for (const m of xml.matchAll(/\bAS\s+([A-Za-z_][A-Za-z0-9_]*)/gi)) add(m[1]);
  return ids;
}

const entityFiles = fs.readdirSync(ENTITY_DIR).filter((f) => f.endsWith('.java'));
const mapperFiles = fs.readdirSync(MAPPER_DIR).filter((f) => f.endsWith('.xml'));
const mapperSrc = new Map(mapperFiles.map((f) => [f, fs.readFileSync(path.join(MAPPER_DIR, f), 'utf8')]));
const coverage = new Map(mapperFiles.map((f) => [f, coverageOf(mapperSrc.get(f))]));

const problems = [];
let checked = 0;

for (const ef of entityFiles) {
  const cls = ef.replace(/\.java$/, '');
  const fqn = `com.umi.nest.entity.${cls}`;
  const owners = mapperFiles.filter((m) => mapperSrc.get(m).includes(`"${fqn}"`));
  if (!owners.length) continue; // 没有 mapper 的实体（DTO 风格）跳过

  const src = fs.readFileSync(path.join(ENTITY_DIR, ef), 'utf8');
  const fields = fieldsOf(src);

  for (const f of fields) {
    if (ALLOW[`${cls}.${f}`]) continue;
    checked += 1;
    const s = snake(f);
    const hit = owners.some((m) => coverage.get(m).has(s) || coverage.get(m).has(f.toLowerCase()));
    if (!hit) problems.push({ cls, field: f, snake: s, mappers: owners.join(',') });
  }
}

if (problems.length) {
  console.error(`FAIL: ${problems.length} 个实体字段对应的列在任何查询/结果映射里都读不出来（真机上是静默失效）`);
  for (const p of problems) {
    console.error(`  - ${p.cls}.${p.field}  (列名 ${p.snake})  映射文件: ${p.mappers}`);
  }
  console.error('\n判据只认「能读出来」的位置：<sql> 列片段、<result column=...>、AS 别名。');
  console.error('若字段是故意不落库的统计/展示字段，请加进 tools/check-mapper-columns.mjs 的 ALLOW 并写明理由。');
  process.exit(1);
}

console.log(`OK mapper columns: ${entityFiles.length} entities, ${mapperFiles.length} mappers, ${checked} fields all readable`);

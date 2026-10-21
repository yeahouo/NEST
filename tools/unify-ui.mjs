#!/usr/bin/env node
/**
 * unify-ui.mjs —— 第十二轮「整站统一」的机械迁移脚本
 *
 * 背景：页面里 ad-hoc 的工具类（rounded-md/xl/2xl 混用、bg-white/50 + blur-md、
 * px-6 py-3 rounded-full bg-primary ...）权重高于组件层，只要不把它们删掉，
 * 组件层的统一外形就永远被压住 —— 这就是用户说的「东一个西一个」。
 * 这个脚本按 class 令牌级别把它们换成统一组件：.nx-panel / .nx-dialog /
 * .nx-btn(-primary|-soft|-quiet) / .nx-tab。
 *
 * 用法：
 *   node tools/unify-ui.mjs                 # 只统计，不写文件（默认）
 *   node tools/unify-ui.mjs --apply         # 真改，备份到 target/backup-unify-ui/
 *   node tools/unify-ui.mjs --only=R1,R3    # 只跑指定规则
 *   node tools/unify-ui.mjs --file=dashboard.html
 */
import { readFileSync, writeFileSync, readdirSync, mkdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = 'src/main/resources/static';
const BACKUP = 'target/backup-unify-ui';
const argv = process.argv.slice(2);
const APPLY = argv.includes('--apply');
const ONLY = (argv.find((a) => a.startsWith('--only=')) || '').replace('--only=', '').split(',').filter(Boolean);
const FILE = (argv.find((a) => a.startsWith('--file=')) || '').replace('--file=', '');
// 按页面分批迁移：--pages=a.html,b.html（第十九轮「页内迁移」分四批：学生端/教师端/管理端/宿舍端）
const PAGES = (argv.find((a) => a.startsWith('--pages=')) || '')
  .replace('--pages=', '')
  .split(',')
  .filter(Boolean);

// dorm.html 自带内联 <style>、根本不加载 nest.css，迁移它会直接把页面弄坏。
const SKIP = new Set(['dorm.html']);

const R = {
  rm: (list) => ({ kind: 'rm', list }),
  rx: (res) => ({ kind: 'rx', list: res }),
};

const RULES = [
  {
    id: 'R8',
    name: '已迁移元素上的残留圆角类 → 交给组件自己的圆角',
    // 先跑：R1/R2/R3 第一次跑时，圆角正则漏掉了 rounded-[2.5rem] 这种方括号写法，
    // 于是有 3 个元素带着旧圆角进了组件态。这个规则专门清这种残留，且幂等。
    test: (t) => (t.has('nx-panel') || t.has('nx-dialog') || t.has('nx-btn')) && [...t].some((x) => /^rounded(-|$)/.test(x) && !x.startsWith('rounded-none')),
    remove: [R.rx([/^rounded(-(?!none)|\[)/])],
    add: () => [],
  },
  {
    id: 'R10',
    name: '修掉「标签被当按钮迁移」的混合体（tab-btn + nx-btn）',
    // R3 第一遍把 teacher-course-detail 里那颗「静态激活态」标签
    //（tab-btn ... bg-primary text-white font-bold rounded-full）当成了主按钮。
    // 这里把它还原成 nx-tab：标签一律用 .nx-tab + .is-active，不用按钮档。
    test: (t) => t.has('tab-btn') && [...t].some((x) => /^nx-btn/.test(x)),
    remove: [R.rx([/^nx-btn/, /^(px|py|h)-\d+(\.\d+)?$/, /^text-(xs|sm|base|lg)$/])],
    add: () => ['nx-tab'],
  },
  {
    id: 'R9',
    name: '已迁移按钮上的尺寸工具类 → 交给组件档位',
    // .nx-btn 自带 height/padding/字号，页面再写 h-10/h-11/px-5/text-xs 会造出
    // 44px 这种「刻度外」的高度（实测 dashboard 上就有一颗 44px 的按钮）。
    test: (t) => t.has('nx-btn') && [...t].some((x) => /^(h|px|py)-\d+(\.\d+)?$/.test(x) || /^text-(xs|sm|base|lg)$/.test(x)),
    remove: [R.rx([/^(h|px|py)-\d+(\.\d+)?$/, /^text-(xs|sm|base|lg)$/])],
    add: () => [],
  },
  {
    id: 'R1',
    name: '玻璃面板 → .nx-panel',
    test: (t) =>
      t.has('border') &&
      t.has('border-primary/10') &&
      t.has('backdrop-blur-md') &&
      (t.has('bg-white/50') || t.has('bg-white/60')),
    remove: [
      'border', 'border-primary/10', 'bg-white/50', 'bg-white/60',
      'dark:bg-slate-800/30', 'backdrop-blur-md',
      R.rx([/^rounded-(sm|md|lg|xl|2xl|3xl)$/]),
    ],
    add: (t) => ['nx-panel', ...[...t].some((x) => x.startsWith('hover:bg-white')) ? ['nx-panel-i'] : []],
  },
  {
    id: 'R2',
    name: '弹窗/白底大卡 → .nx-dialog',
    test: (t) =>
      t.has('shadow-2xl') &&
      (t.has('backdrop-blur-xl') || t.has('backdrop-blur-lg') || t.has('backdrop-blur-md')) &&
      (t.has('bg-white') || t.has('bg-white/90')) &&
      (t.has('border') || t.has('border-primary/10') || t.has('border-primary/20')),
    remove: [
      'border', 'border-primary/10', 'border-primary/20', 'bg-white', 'bg-white/90',
      'dark:bg-slate-800', 'dark:bg-slate-800/90',
      'backdrop-blur-xl', 'backdrop-blur-lg', 'backdrop-blur-md', 'shadow-2xl',
      R.rx([/^rounded-(sm|md|lg|xl|2xl|3xl)$/]),
    ],
    add: () => ['nx-dialog'],
  },
  {
    id: 'R3',
    name: '主按钮（实心品牌） → .nx-btn-primary',
    // 尺寸类必须像「真按钮」：h-11/h-12/h-14 或 px-5/6/8 或 py-2.5/3/4。
    // 少了这一条，8 个「头像圆牌」（h-8 w-8 rounded-full bg-primary ... text-[10px] font-bold，
    // 里面是学生姓氏首字）也会被当成主按钮，nx-btn 的 height/padding 会把圆牌撑成胶囊。
    test: (t) =>
      t.has('bg-primary') && t.has('text-white') && t.has('font-bold') &&
      !t.has('tab-btn') &&
      [...t].some((x) => /^rounded-(full|sm|md|lg|xl|2xl)$/.test(x)) &&
      ['h-11', 'h-12', 'h-14', 'px-5', 'px-6', 'px-8', 'py-2.5', 'py-3', 'py-4'].some((x) => t.has(x)),
    remove: [
      'bg-primary', 'text-white', 'font-bold',
      R.rx([/^rounded-(full|sm|md|lg|xl|2xl|3xl)$/]),
      'hover:bg-primary/90', 'hover:bg-primary/95', 'hover:brightness-110', 'hover:brightness-105',
      'active:scale-[0.98]', 'active:scale-95', 'transition-all', 'transition-colors',
      'shadow-lg', 'shadow-xl', 'shadow-primary/20', 'shadow-primary/30', 'shadow-primary/10',
      R.rx([/^(h|py|px)-\d+(\.\d+)?$/]),
    ],
    add: (t) => ['nx-btn', 'nx-btn-primary', ...[...t].some((x) => x === 'h-12' || x === 'h-11' || x === 'py-3' || x === 'py-4') ? ['nx-btn-lg'] : []],
  },
  {
    id: 'R4',
    name: '浅底按钮（品牌 10% 底） → .nx-btn-soft',
    test: (t, tag) =>
      t.has('bg-primary/10') && t.has('text-primary') &&
      ([...t].some((x) => /^rounded-/.test(x))) &&
      (tag === 'button' || tag === 'a' || t.has('cursor-pointer')),
    remove: [
      'bg-primary/10', 'text-primary',
      R.rx([/^rounded-(full|sm|md|lg|xl|2xl|3xl)$/]),
      'transition-colors', 'transition-all',
      'hover:bg-primary/20', 'hover:bg-primary/15', 'hover:bg-primary/10',
      'font-medium', 'font-semibold',
      R.rx([/^(h|py|px)-\d+(\.\d+)?$/]),
    ],
    add: () => ['nx-btn', 'nx-btn-soft'],
  },
  {
    id: 'R5',
    name: '取消按钮（白底灰边） → .nx-btn-quiet',
    test: (t, tag) =>
      (t.has('border-slate-200') || t.has('border-slate-300')) &&
      (t.has('bg-white') || t.has('dark:bg-slate-800')) &&
      (tag === 'button' || tag === 'a' || t.has('cursor-pointer')),
    remove: [
      'border', 'border-slate-200', 'border-slate-300', 'dark:border-slate-700', 'dark:border-slate-600',
      'bg-white', 'dark:bg-slate-800', 'font-bold', 'font-semibold',
      R.rx([/^rounded-(full|sm|md|lg|xl|2xl|3xl)$/]),
      'hover:bg-slate-50', 'hover:bg-slate-100', 'dark:hover:bg-slate-700', 'dark:hover:bg-slate-600',
      'text-slate-600', 'text-slate-700', 'dark:text-slate-300', 'dark:text-slate-200',
      'transition-all', 'transition-colors',
      R.rx([/^(h|py|px)-\d+(\.\d+)?$/]),
    ],
    add: (t) => ['nx-btn', 'nx-btn-quiet', ...[...t].some((x) => x === 'h-12' || x === 'h-11' || x === 'py-3') ? ['nx-btn-lg'] : []],
  },
  {
    id: 'R6',
    name: '标签条 → .nx-tab',
    test: (t) => t.has('tab-btn') && [...t].some((x) => /^rounded-(full|md|lg|xl)$/.test(x)),
    remove: [
      R.rx([/^rounded-(full|md|lg|xl|2xl)$/, /^(px|py)-\d+(\.\d+)?$/]),
      'text-sm', 'font-bold', 'font-semibold',
      'hover:bg-primary/5', 'hover:bg-primary/10', 'hover:text-primary',
      'transition-colors', 'transition-all',
      'text-slate-500', 'text-slate-600', 'text-slate-700',
      'dark:text-slate-200', 'dark:text-slate-300', 'dark:text-slate-400',
    ],
    add: () => ['nx-tab'],
  },
  {
    id: 'R7',
    name: '杂项圆角 → 刻度内的值',
    test: (t) => [...t].some((x) => /^rounded-\[/.test(x)),
    remove: [R.rx([/^rounded-\[.*\]$/])],
    add: () => ['rounded-3xl'],
  },
  /* ── 第十九轮「页内迁移」：外壳统一之后，页面内部还是旧词汇（白底 + slate 色 + 一堆圆角）。
     证据（tools/inventory-ui.mjs 实测）：全站 52 种卡片写法、172 种按钮写法；course-detail.html
     一个页面 62 处圆角、186 处 slate-*。规则顺序有意义 —— RULES.find 只取第一条命中的规则，
     所以窄条件（弹窗）必须排在宽条件（白卡）前面。 */
  {
    id: 'R11a',
    name: '白底弹窗 → .nx-dialog',
    // 旧弹窗外壳：bg-white + 大阴影 + 尺寸上限（max-w-/max-h-）。它必须排在 R11 前，
    // 否则会被当成普通卡片，圆角从 24px 掉到 16px、阴影也降级。
    test: (t) =>
      t.has('bg-white') &&
      [...t].some((x) => x.startsWith('max-w-') || x.startsWith('max-h-')) &&
      (t.has('shadow-2xl') || t.has('shadow-xl') || t.has('shadow-lg')),
    remove: [
      'bg-white', 'dark:bg-slate-800', 'dark:bg-slate-900',
      'shadow-md', 'shadow-lg', 'shadow-xl', 'shadow-2xl',
      'backdrop-blur-sm', 'backdrop-blur-md', 'backdrop-blur-lg', 'backdrop-blur-xl',
      'border', 'border-slate-200', 'border-slate-300', 'dark:border-slate-700',
      'border-primary/10', 'border-primary/20',
      R.rx([/^rounded-(sm|md|lg|xl|2xl|3xl)$/, /^rounded-\[.*\]$/]),
    ],
    add: () => ['nx-dialog'],
  },
  {
    id: 'R18',
    name: '旧白底弹窗（无尺寸上限类）→ .nx-dialog',
    // teacher-course-detail 里的弹窗把 max-w 放在外层包层上，自己只有 bg-white +
    // rounded-3xl + shadow-2xl，R11a 抓不到、R11 会当普通卡片（圆角 24→16、阴影降级）。
    // 位置必须紧跟在 R11a 之后、R11 之前：RULES.find() 只取第一条命中的规则。
    test: (t) =>
      t.has('bg-white') &&
      !t.has('nx-card') && !t.has('nx-dialog') && !t.has('nx-panel') &&
      (t.has('rounded-3xl') || t.has('rounded-2xl')) &&
      (t.has('shadow-2xl') || t.has('shadow-xl')),
    remove: [
      'bg-white', 'dark:bg-slate-800', 'dark:bg-slate-900',
      'shadow-md', 'shadow-lg', 'shadow-xl', 'shadow-2xl',
      'backdrop-blur-sm', 'backdrop-blur-md', 'backdrop-blur-lg', 'backdrop-blur-xl',
      R.rx([/^rounded-(xl|2xl|3xl)$/]),
    ],
    add: () => ['nx-dialog'],
  },
  {
    id: 'R11',
    name: '不透明白卡 → .nx-card',
    test: (t, tag) =>
      t.has('bg-white') &&
      // 表单控件（input/select/textarea）也常写成 bg-white + border + rounded，
      // 它们绝对不能被换成卡片：卡片类是「块级容器」，会把输入框的 padding/高度语义吃坏。
      !['input', 'select', 'textarea', 'option'].includes(tag) &&
      !t.has('nx-card') && !t.has('nx-card-flat') && !t.has('nx-panel') && !t.has('nx-dialog') &&
      !t.has('nx-glass') && !t.has('nx-inset') &&
      ![...t].some((x) => /^nx-(btn|tab|badge|chip|input|field)/.test(x)) &&
      (t.has('shadow-md') || t.has('shadow-lg') || t.has('shadow-xl') || t.has('shadow-2xl') ||
       t.has('shadow-primary/5') || t.has('shadow-primary/10') ||
       (t.has('border') && [...t].some((x) => /^rounded-/.test(x)))),
    remove: [
      'bg-white', 'dark:bg-slate-800', 'dark:bg-slate-900', 'dark:bg-slate-700',
      'shadow-md', 'shadow-lg', 'shadow-xl', 'shadow-2xl',
      'shadow-primary/5', 'shadow-primary/10', 'shadow-primary/20', 'shadow-primary/30',
      'hover:shadow-md', 'hover:shadow-lg', 'hover:shadow-xl', 'hover:shadow-2xl',
      'backdrop-blur-sm', 'backdrop-blur-md', 'backdrop-blur-lg', 'backdrop-blur-xl',
      'border', 'border-slate-100', 'border-slate-200', 'border-slate-300',
      'dark:border-slate-700', 'dark:border-slate-600',
      'border-primary/5', 'border-primary/10', 'border-primary/20',
      R.rx([/^rounded-(sm|md|lg|xl|2xl|3xl)$/, /^rounded-\[.*\]$/]),
    ],
    // 只有「可点」的卡才有悬停抬升：一屏都在动比不动更累人
    add: (t, tag) => ['nx-card', ...[tag === 'a' || tag === 'button' || t.has('cursor-pointer') ? ['nx-card-i'] : []]],
  },
  {
    id: 'R12',
    name: '弱化文字 → .nx-muted（对比度达标）',
    // .nx-muted = Radix slate-11，浅色/深色都过 WCAG AA；页面里直接写 text-slate-400
    // 在浅底上只有 2.9:1。已迁移的按钮/标签/Badge 自己带颜色，不要动。
    test: (t) =>
      (t.has('text-slate-400') || t.has('text-slate-500')) &&
      ![...t].some((x) => /^nx-(btn|tab|badge|chip|input|field|muted)/.test(x)),
    remove: ['text-slate-400', 'text-slate-500', 'dark:text-slate-400', 'dark:text-slate-500'],
    add: () => ['nx-muted'],
  },
  {
    id: 'R13',
    name: '内嵌浅底块 → .nx-inset',
    test: (t, tag) =>
      (t.has('bg-slate-50') || t.has('bg-slate-100') ||
       t.has('dark:bg-slate-900/30') || t.has('dark:bg-slate-800/30') || t.has('dark:bg-slate-900/40')) &&
      // 表单控件与按钮另有归属（.nx-input / .nx-btn-quiet），这里的 nx-inset 只服务「卡片里的信息块」
      !['input', 'select', 'textarea', 'option', 'button'].includes(tag) &&
      !t.has('nx-inset') && !t.has('nx-card') && !t.has('nx-panel'),
    remove: [
      'bg-slate-50', 'bg-slate-100',
      'dark:bg-slate-900/30', 'dark:bg-slate-800/30', 'dark:bg-slate-900/40',
      R.rx([/^rounded-(sm|md|lg|xl|2xl)$/]),
    ],
    add: () => ['nx-inset'],
  },
  {
    id: 'R14',
    name: '旧遮罩 → .nx-scrim',
    // 弹窗遮罩老写法：absolute inset-0 bg-slate-900/40|60 backdrop-blur-*。
    // 换成 .nx-scrim（配方见 css/nest-art3.css：底 + 一层极淡品牌径向 + blur(6px)）。
    test: (t) => [...t].some((x) => /^bg-slate-90?0?\/(40|50|60|70|80)$/.test(x)) || t.has('bg-slate-900/60'),
    remove: [
      R.rx([/^bg-slate-900\/(40|50|60|70|80)$/]),
      'backdrop-blur-sm', 'backdrop-blur-md', 'backdrop-blur-lg', 'backdrop-blur-xl',
    ],
    add: () => ['nx-scrim'],
  },
  {
    id: 'R15',
    name: '幽灵图标按钮 → .nx-icon-ghost',
    test: (t, tag) =>
      (t.has('hover:bg-slate-100') || t.has('hover:bg-slate-200')) &&
      (tag === 'button' || tag === 'a' || t.has('cursor-pointer')),
    remove: ['hover:bg-slate-100', 'hover:bg-slate-200', 'dark:hover:bg-slate-700', 'dark:hover:bg-slate-600'],
    add: () => ['nx-icon-ghost'],
  },
  {
    id: 'R16',
    name: '危险态胶囊 → .nx-danger-soft',
    test: (t) => t.has('hover:bg-red-500/10') || (t.has('text-red-500') && [...t].some((x) => x.startsWith('hover:bg-red-500'))),
    remove: ['hover:bg-red-500/10', 'hover:bg-red-500/20', 'text-red-500', 'dark:text-red-400'],
    add: () => ['nx-danger-soft'],
  },
  {
    id: 'R17',
    name: '品牌渐变图标底 → .nx-tile-icon',
    test: (t) => t.has('bg-gradient-to-br') && t.has('from-primary') && [...t].some((x) => x.startsWith('to-')),
    remove: ['bg-gradient-to-br', 'from-primary', 'to-blue-400', 'to-blue-500', 'to-indigo-500',
      'shadow-lg', 'shadow-primary/30', 'shadow-primary/20', 'text-white'],
    add: () => ['nx-tile-icon'],
  },
  {
    id: 'R20',
    name: '旧主文字色 → 令牌继承',
    test: (t) => t.has('text-slate-900') || t.has('text-slate-800'),
    remove: ['text-slate-900', 'text-slate-800', 'dark:text-white', 'dark:text-slate-100'],
    add: () => [],
  },
  {
    id: 'R21',
    name: '半透明白边 → 发丝线',
    test: (t) => [...t].some((x) => /^border-white\/(10|20|30|50|60)$/.test(x)),
    remove: [R.rx([/^border-white\/(10|20|30|50|60)$/]), R.rx([/^dark:border-white\/(5|10|20)$/])],
    add: () => ['border-base-300'],
  },
];

const files = readdirSync(ROOT)
  .filter((f) => f.endsWith('.html') && !SKIP.has(f) && (!FILE || f === FILE) && (!PAGES.length || PAGES.includes(f)))
  .sort();

const stats = new Map(RULES.map((r) => [r.id, { n: 0, files: new Map(), samples: [], tags: new Map() }]));
let touchedFiles = 0;

for (const file of files) {
  const path = join(ROOT, file);
  const src = readFileSync(path, 'utf8');
  let out = '';
  let cursor = 0;
  let changed = 0;
  const re = /<([a-zA-Z][\w-]*)([^>]*?)\sclass="([^"]*)"/g;
  let m;
  while ((m = re.exec(src))) {
    const [whole, tag, mid, cls] = m;
    const tokens = cls.split(/\s+/).filter(Boolean);
    const set = new Set(tokens);
    const rule = RULES.find(
      (r) => (!ONLY.length || ONLY.includes(r.id)) && r.test(set, tag.toLowerCase())
    );
    if (!rule) continue;
    const removeOne = (tok) => {
      for (const item of rule.remove) {
        if (typeof item === 'string') {
          if (item === tok) return true;
        } else if (item.kind === 'rx') {
          if (item.list.some((rx) => rx.test(tok))) return true;
        }
      }
      return false;
    };
    const kept = tokens.filter((tok) => !removeOne(tok));
    const add = rule.add(set);
    for (const a of add) if (!kept.includes(a)) kept.push(a);
    const next = kept.join(' ');
    if (next === cls) continue;
    out += src.slice(cursor, m.index) + `<${tag}${mid} class="${next}"`;
    cursor = m.index + whole.length;
    changed++;
    const st = stats.get(rule.id);
    st.n++;
    st.files.set(file, (st.files.get(file) || 0) + 1);
    const tl = tag.toLowerCase();
    st.tags.set(tl, (st.tags.get(tl) || 0) + 1);
    if (st.samples.length < 3) {
      const line = src.slice(0, m.index).split('\n').length;
      st.samples.push(`${file}:${line}  ${cls.trim().replace(/\s+/g, ' ')}\n        → ${next}`);
      void line;
    }
  }
  if (!changed) continue;
  out += src.slice(cursor);
  touchedFiles++;
  if (APPLY) {
    if (!existsSync(BACKUP)) mkdirSync(BACKUP, { recursive: true });
    writeFileSync(join(BACKUP, file), src);
    writeFileSync(path, out);
  }
}

console.log(`\n=== unify-ui.mjs ${APPLY ? '(已写入)' : '(dry-run，未写文件)'} ===`);
console.log(`扫描 ${files.length} 个页面，命中 ${touchedFiles} 个页面\n`);
for (const rule of RULES) {
  const st = stats.get(rule.id);
  if (!ONLY.length && !st.n) continue;
  const tagHist = [...st.tags].sort((a, b) => b[1] - a[1]).map(([t, n]) => `${t}×${n}`).join(' ');
  console.log(`[${rule.id}] ${rule.name} —— ${st.n} 处，涉及 ${st.files.size} 个页面　标签：[${tagHist}]`);
  for (const [f, n] of [...st.files].sort((a, b) => b[1] - a[1])) console.log(`      ${f} × ${n}`);
  for (const s of st.samples) console.log(`    · ${s}`);
  console.log('');
}
const total = [...stats.values()].reduce((a, b) => a + b.n, 0);
console.log(`合计改写 ${total} 处 class。`);
if (!APPLY) console.log('加 --apply 才会真正写文件（改前备份到 target/backup-unify-ui/）。');

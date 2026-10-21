/*
 * 交互扫描：在页面里把所有 [onclick] 元素逐个点一遍，记录
 *   - 点击后 DOM 有没有变化（死了的 handler / 找不到 id 的 handler 会不动或报错）
 *   - 每次点击产生了什么新错误（window.error / unhandledrejection / console.error）
 *   - 打开了几个模态框
 *
 * 三条硬性安全措施：
 *   1) EXCLUDE 名单里的「破坏性 / 离站 / 真调大模型」handler 一律不点（删除、清空、发布、
 *      提交、退出登录、跑分配实验、生成数据、AI 出题批改…）。这些另走专门的受控流程。
 *   2) 覆盖 alert/confirm/prompt —— 否则原生弹窗会把 CDP 的 Runtime.evaluate 卡死。
 *   3) 每次点击之间 forceClose() 强制关掉模态框 —— 扫的是「handler 能不能跑」，不是「用户
 *      必须手点关闭按钮」，避免状态漂移导致后面的点击落在遮罩上。
 *
 * 返回对象（probe.mjs 会 JSON.stringify 后打印）：
 *   { total, visible, clicked, skipped, mutations, deadish, errCount, errs:[...], skippedSample:[...] }
 */
(async () => {
  var EXCLUDE = new RegExp([
    // 破坏性：删、清空、移除、改状态、发布、提交
    'confirmDelete', 'deleteUser', 'deleteCourse', 'deleteQuiz', 'removeStudent', 'deleteAnnouncement',
    'deleteMaterial', 'deleteFile', 'deleteQuestion', 'removeQuestion', 'removeFile', 'dropFile',
    'clearAll', 'clearData', 'logout',
    // 真调大模型（另做受控测试，避免扫描里烧 token、也避免超时）
    'aiGenerate', 'aiGradeSuggest', 'aiGradeApply',
    // 会写数据 / 有副作用的动作
    'submitQuiz', 'submitAssignment', 'submitCheckin', 'saveQuiz', 'publishQuiz',
    'createUser', 'saveUser', 'createCourse', 'saveCourse', 'createCode', 'toggleUserStatus', 'toggleCode',
    'runAssign', 'crossTrial', 'genData', 'importData', 'useBatch', 'runExtract', 'runApply',
    'applyAppeal', 'doBind', 'doAppeal', 'saveAnswers', 'saveText', 'savePref', 'addPref', 'dropPref',
    'assign', 'synthesize', 'audit', 'explain', 'roomView', 'openBatch',
    // 会离站 / 下载 / 关窗：点击后本页执行上下文就没了，扫描循环必须避开
    'location', 'downloadFile', 'openInNewTab', 'window.close', 'navigateToFolder', 'viewDetail'
  ].join('|'));

  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  var errs = [];
  var onErr = function (e) { errs.push('window.error: ' + (e.message || e)); };
  var onRej = function (e) { errs.push('unhandledrejection: ' + (e.reason && e.reason.message ? e.reason.message : String(e.reason))); };
  window.addEventListener('error', onErr);
  window.addEventListener('unhandledrejection', onRej);

  var origErr = console.error.bind(console);
  console.error = function () {
    errs.push('console.error: ' + Array.prototype.map.call(arguments, String).join(' '));
    origErr.apply(null, arguments);
  };

  // 原生弹窗会把 CDP 卡死 —— 一律拦下并记账
  var alerts = [];
  window.alert = function (m) { alerts.push('alert: ' + m); };
  window.confirm = function (m) { alerts.push('confirm: ' + m); return false; };
  window.prompt = function (m) { alerts.push('prompt: ' + m); return null; };
  var origOpen = window.open;
  window.open = function (u) { alerts.push('window.open: ' + u); return null; };

  function forceClose() {
    Array.prototype.forEach.call(document.querySelectorAll('.nx-cmdk.is-open'), function (c) { c.classList.remove('is-open'); });
    Array.prototype.forEach.call(document.querySelectorAll('[id*="odal"], .nx-qed'), function (m) { m.classList.add('hidden'); });
    document.body.classList.remove('nx-noscroll');
  }

  var all = Array.prototype.slice.call(document.querySelectorAll('[onclick]'));
  var visible = all.filter(function (el) { return !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length); });
  var plan = [];
  var skipped = [];
  visible.forEach(function (el) {
    var sig = el.getAttribute('onclick') || '';
    if (EXCLUDE.test(sig)) { skipped.push(sig.replace(/\s+/g, ' ').slice(0, 60)); return; }
    plan.push({ el: el, sig: sig, label: (el.id || el.textContent || el.tagName).replace(/\s+/g, ' ').trim().slice(0, 34) });
  });

  // 初始可见性不变式：class 里写了 hidden 的元素，计算样式必须是 display:none。
  // 这条能抓住「CSS 顺序错位让 .hidden 失效」这类缺陷 —— 2026-10-13 教师端一进课程
  // 详情就弹出测验编辑器，就是 .nx-qed{display:flex} 被输出到 .hidden 之后造成的；
  // 当时所有流程都只断言「什么应该可见」，没人断言「什么必须不可见」，所以漏掉了。
  var hiddenVisible = Array.prototype.filter.call(document.querySelectorAll('.hidden'), function (el) {
    if (getComputedStyle(el).display === 'none') return false;
    // 带断点/状态变体的类（`md:inline`、`sm:flex`、`group-hover:block`…）会在某些视口或状态下
    // 正当地把 hidden 盖回来 —— 例如 `hidden md:inline` 在桌面宽度上本来就该看得见，不算缺陷。
    // 只有「没有任何变体类、却依然显示」的 hidden 元素才是 CSS 顺序错位的信号。
    if (/[a-z-]+:/.test(String(el.className))) return false;
    return true;
  }).map(function (el) {
    return (el.id ? '#' + el.id : el.tagName.toLowerCase()) + ' [' + String(el.className).replace(/\s+/g, ' ').slice(0, 70) + ']';
  });

  // 第二条件不变式：初始状态里不允许有「盖住整屏」的浮层。教师端「一进课程详情整页被
  // 测验编辑器盖住」这种缺陷，用这一条能直接抓到（不管它跟 .hidden 有没有关系）。
  // 装饰性光斑（负 z-index / pointer-events:none / blur-3xl）不计。
  var vw = window.innerWidth, vh = window.innerHeight;
  var overlayVisible = Array.prototype.filter.call(document.querySelectorAll('body *'), function (el) {
    var cs = getComputedStyle(el);
    if (cs.position !== 'fixed' || cs.display === 'none' || cs.visibility === 'hidden') return false;
    if (cs.pointerEvents === 'none') return false;
    var z = parseInt(cs.zIndex, 10);
    if (isNaN(z) || z < 0) return false;
    if (/pointer-events-none|blur-3xl|nx-aurora|nx-grid|glow/.test(String(el.className))) return false;
    var r = el.getBoundingClientRect();
    return r.width * r.height >= 0.8 * vw * vh;
  }).map(function (el) {
    var r = el.getBoundingClientRect();
    return (el.id ? '#' + el.id : el.tagName.toLowerCase()) + ' [' + String(el.className).replace(/\s+/g, ' ').slice(0, 70) +
      '] ' + Math.round(r.width) + 'x' + Math.round(r.height) + ' z=' + getComputedStyle(el).zIndex;
  });

  var results = [];
  var mutations = 0;
  for (var i = 0; i < plan.length; i++) {
    var it = plan[i];
    forceClose();
    await sleep(50);
    var before = document.body.innerHTML.length;
    var err0 = errs.length;
    try { it.el.click(); } catch (e) { errs.push('click threw @' + it.label + ': ' + (e && e.message)); }
    await sleep(170);
    var after = document.body.innerHTML.length;
    var grew = after !== before;
    if (grew) mutations++;
    var openModals = Array.prototype.filter.call(document.querySelectorAll('[id*="odal"], .nx-qed'), function (m) { return !m.classList.contains('hidden'); }).length;
    var added = errs.slice(err0).concat(alerts.splice(0));
    results.push({ i: i, label: it.label, sig: it.sig.replace(/\s+/g, ' ').slice(0, 56), mutated: grew, modals: openModals, errs: added });
  }

  window.removeEventListener('error', onErr);
  window.removeEventListener('unhandledrejection', onRej);
  console.error = origErr;
  window.open = origOpen;
  forceClose();

  var bad = results.filter(function (r) { return r.errs.length; });
  var deadish = results.filter(function (r) { return !r.mutated && r.modals === 0 && r.errs.length === 0; });
  return {
    total: all.length,
    visible: visible.length,
    clicked: plan.length,
    skipped: skipped.length,
    mutations: mutations,
    errCount: bad.length,
    errs: bad.slice(0, 14),
    silentSample: deadish.slice(0, 14).map(function (r) { return r.label + ' :: ' + r.sig; }),
    skippedSample: skipped.slice(0, 30),
    hiddenVisibleCount: hiddenVisible.length,
    hiddenVisible: hiddenVisible.slice(0, 20),
    overlayCount: overlayVisible.length,
    overlay: overlayVisible.slice(0, 8)
  };
})()

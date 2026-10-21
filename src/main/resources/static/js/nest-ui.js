/*
 * NEST · 站点交互层（学生端 / 教师端 / 管理端共用一个文件）
 *
 * 为什么是一个共享文件，而不是每页各贴一段：
 *   之前每个页面都自己写一遍「取元素、判空、绑事件」，同一个 bug 要在 21 个页面里各修一次
 *   （settings.html 的验证徽章、eval-review.html 的弹窗重渲染都是这么坏的）。
 *   交互属于「站点的行为」，应该只有一份实现、一处修改。
 *
 * 为什么零依赖、不引任何 CDN 上的动画库：
 *   1. 学校内网访问境外 CDN 不稳定，一个 30KB 的动画库换来的效果，用原生 API 就能做到；
 *   2. 这些效果全是「体验加分项」，不能成为「打不开页面」的原因；
 *   3. 全部走原生 API（View Transitions / IntersectionObserver / rAF），浏览器不支持时
 *      自动降级成「直接生效、没有动画」，功能一个不少。
 *
 * 三件事必须成立，代码里到处都在护着它们：
 *   · 无 JS 时页面照常可用 —— 需要 JS 才出现的样式都由 JS 自己加类（.nx-reveal / .nx-spot 等）；
 *   · 系统设了「减少动态效果」时全部不动画 —— 只在 JS 里判断一次，不做「动画一会儿再停」；
 *   · 任何一块出错都不连累其它块 —— 每块 init 各自 try/catch，一个报错不会让整页脚本停摆。
 *
 * 页面不需要写一行 JS，只要加这些属性：
 *   [data-nx-theme-toggle]        主题开关按钮（按钮里放 .nx-theme-icon-light / -dark 两个图标，靠 CSS 切换）
 *   [data-nx-cmdk-open]           点它打开命令面板
 *   [data-nx-cmd="标题"]          命令面板条目；可选 data-nx-cmd-group / -icon / -keywords，选中后触发该元素的 click
 *   [data-nx-copy="要复制的文本"]  点击复制，并弹一条反馈
 *   [data-nx-count]               数字从 0 滚到元素当前文本（数据异步到位后调 NestUI.countUp(el)）
 *   [data-nx-spotlight]           鼠标跟随的聚光（仅限有真实指针的设备）
 *   [data-nx-reveal]              进入视口时渐入；可选 data-nx-reveal-delay="120"
 *   在 <html> 上写 data-nx-progress="off" 可关掉顶部阅读进度条。
 */
(function () {
  'use strict';

  // 已经在页面里跑过一次就不再初始化：某些页面会在局部刷新时重复插脚本。
  if (window.NestUI) {
    return;
  }

  var docEl = document.documentElement;
  var motionQuery = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;

  function reduced() {
    return !!(motionQuery && motionQuery.matches);
  }

  // 每块功能独立 try/catch 的载体：一块坏了只打印，不影响后面几块。
  function guard(name, fn) {
    try {
      fn();
    } catch (err) {
      console.error('[nest-ui] ' + name + ' 初始化失败（其余功能不受影响）：', err);
    }
  }

  /* ══════════════════════════════════════════════════════════════════════════
   * 通用小工具
   * ══════════════════════════════════════════════════════════════════════════ */

  function escapeHtml(text) {
    return String(text).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function readStore(key) {
    try {
      return window.localStorage.getItem(key);
    } catch (err) {
      // 隐私模式下 localStorage 会直接抛异常。存不了就存不了，不能因此报错。
      return null;
    }
  }

  function writeStore(key, value) {
    try {
      window.localStorage.setItem(key, value);
    } catch (err) {
      /* 同上：忽略 */
    }
  }

  function iconOf(el) {
    var icon = el ? el.querySelector('.material-symbols-outlined') : null;
    return icon ? (icon.textContent || '').trim() : '';
  }

  /**
   * 取元素的纯文字。必须先把图标 span 摘掉：
   * 图标是用「连字」实现的，<span class="material-symbols-outlined">home_work</span>
   * 的 textContent 就是字符串 "home_work"，直接读 textContent 会得到
   * 「home_work 我的宿舍 · 安居」这种东西进搜索框。
   * 所以复制一份、删掉图标节点再读 —— 不动原 DOM（原来的节点上还挂着事件）。
   */
  function plainLabel(el) {
    if (!el) {
      return '';
    }
    var clone = el.cloneNode(true);
    Array.prototype.slice.call(clone.querySelectorAll('.material-symbols-outlined')).forEach(function (node) {
      if (node.parentNode) {
        node.parentNode.removeChild(node);
      }
    });
    return (clone.textContent || '').replace(/\s+/g, ' ').trim();
  }

  function clickTarget(event, selector) {
    var node = event.target;
    if (!node || !node.closest) {
      return null;
    }
    return node.closest(selector);
  }

  var isMac = /Mac|iPhone|iPad|iPod/.test(navigator.platform || navigator.userAgent || '');

  /* ══════════════════════════════════════════════════════════════════════════
   * 1. 主题（浅色 / 深色）
   *
   * 站点的 dark: 工具类和 .dark 设计令牌一直都在，但没有任何入口能切过去 ——
   * 整套暗色样式其实是「写了但到不了」的死代码。这里补上开关。
   *
   * 选择存在 localStorage；没有选择时跟随系统，并且系统切换时实时跟随
   * （晚上手机自动进深色，页面不该跟用户对着干）。
   * ══════════════════════════════════════════════════════════════════════════ */

  var THEME_KEY = 'nest-theme';
  // 换主题的过渡编号：用来判断「这次兜底清理还是不是当前那场过渡」，
  // 免得连点两下时后一场的标记被前一场的定时器提前摘掉。
  var vtSeq = 0;

  function systemPrefersDark() {
    return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  }

  function themeIsDark() {
    var saved = readStore(THEME_KEY);
    if (saved === 'dark') { return true; }
    if (saved === 'light') { return false; }
    return systemPrefersDark();
  }

  function paintTheme(dark) {
    docEl.classList.toggle('dark', dark);
    // 第十八轮：除了 .dark 类，还要给 daisyUI 一个 data-theme（它按这个属性取整套主题变量）。
    // 两套机制并存不是冗余：.dark 管的是我们自己的 --nx-* 令牌与手写规则，
    // data-theme 管的是 daisyUI 那套组件（btn / card / input / drawer …）。名字映射见
    // tools/webbuild/tailwind.config.js 的 daisyui.themes。
    docEl.setAttribute('data-theme', dark ? 'nestdark' : 'nestlight');
    // color-scheme 让浏览器自带的控件（滚动条、日期选择器、下拉框）一起变色，
    // 否则深色页面里会突兀地出现一条亮色滚动条。
    docEl.style.colorScheme = dark ? 'dark' : 'light';
    var meta = document.querySelector('meta[name="theme-color"]');
    if (!meta) {
      // 手机浏览器地址栏的颜色跟着页面走，深色页面配一条亮蓝地址栏很难看。
      // 页面没写这个 meta 就补一个，省得 21 个页面各写一遍。
      meta = document.createElement('meta');
      meta.setAttribute('name', 'theme-color');
      document.head.appendChild(meta);
    }
    // 主题自举脚本跑得比这段早，所以 meta 的颜色在第一次绘制前就是对的。
    // 颜色取自 Radix 色阶本身（indigo-9 品牌色 / slate-1 深色底），与 --nx-* 令牌同源：
    // 令牌都写成 var(--indigo-9) 之后，这里再硬写一个「差不多」的十六进制就会开始漂移。
    meta.setAttribute('content', dark ? '#111113' : '#3e63dd');
  }

  /**
   * 切换主题。支持从点击位置圆形揭开的动效（View Transitions API，Chrome 111+）。
   * 为什么值得为此写 20 行：切换整站配色时页面突然「闪一下」很廉价，
   * 而从一个点开始揭开既说明了「是谁变的」，也让切换这件事本身有反馈。
   * 不支持该 API 的浏览器直接换色 —— 效果少一个，功能一个不少。
   */
  function setTheme(dark, origin) {
    function commit() {
      paintTheme(dark);
      writeStore(THEME_KEY, dark ? 'dark' : 'light');
    }

    if (reduced() || typeof document.startViewTransition !== 'function') {
      commit();
      return;
    }

    var box = origin && origin.getBoundingClientRect ? origin.getBoundingClientRect() : null;
    var x = box && box.width ? box.left + box.width / 2 : window.innerWidth - 32;
    var y = box && box.height ? box.top + box.height / 2 : 32;
    // 半径要够到最远的那个角，否则圆没盖满就结束了，会看到一条没换色的边。
    var radius = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y));

    // 只在「圆形揭开」这一场过渡里关掉默认的交叉淡入（见 input.css），
    // 站内换页那场过渡还要用默认淡入 —— 两场过渡得区分开，所以先打标记再开始过渡。
    vtSeq += 1;
    var seq = vtSeq;
    docEl.setAttribute('data-vt', 'theme');
    // 标记必须摘掉：留着它会把「换页淡入」也一起关掉（那个效果就白写了）。
    // 过渡的 promise 理论上会结束，但实测有结束时也不 resolve 的情况，
    // 所以一手挂 promise、一手挂定时器兜底。
    function clearVt() {
      if (seq === vtSeq) {
        docEl.removeAttribute('data-vt');
      }
    }
    var transition = document.startViewTransition(commit);
    transition.finished.then(clearVt).catch(clearVt);
    window.setTimeout(clearVt, 1400);
    transition.ready.then(function () {
      docEl.animate(
        { clipPath: ['circle(0px at ' + x + 'px ' + y + 'px)', 'circle(' + radius + 'px at ' + x + 'px ' + y + 'px)'] },
        { duration: 460, easing: 'cubic-bezier(.22,.61,.36,1)', pseudoElement: '::view-transition-new(root)' }
      );
    }).catch(function () {
      /* 动画失败无所谓，颜色已经换好了 */
    });
  }

  function initTheme() {
    paintTheme(themeIsDark());

    var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
    if (media) {
      var followSystem = function () {
        if (!readStore(THEME_KEY)) {
          paintTheme(media.matches);
        }
      };
      if (media.addEventListener) {
        media.addEventListener('change', followSystem);
      } else if (media.addListener) {
        media.addListener(followSystem);
      }
    }
  }

  /* ══════════════════════════════════════════════════════════════════════════
   * 2. Toast 轻提示
   *
   * 以前的操作反馈是 window.alert / 页面上某一行小字，看完就没了、还会打断操作。
   * Toast 只做「已经发生的事」的确认（复制成功、保存成功、失败原因），
   * 不承载需要用户决策的内容 —— 那种还是得用模态框。
   * ══════════════════════════════════════════════════════════════════════════ */

  // 图标名只能由变量拼出来，这里显式声明给 check-icon-font.mjs 对账：
  /* nx-icons: check_circle warning error info bolt close */
  var TOAST_ICON = { ok: 'check_circle', warn: 'warning', danger: 'error', info: 'info' };

  function toastStack() {
    var stack = document.querySelector('.nx-toast-stack');
    if (!stack) {
      stack = document.createElement('div');
      stack.className = 'nx-toast-stack';
      // role=status + aria-live=polite：屏幕阅读器会朗读提示，但不会打断当前朗读。
      stack.setAttribute('role', 'status');
      stack.setAttribute('aria-live', 'polite');
      document.body.appendChild(stack);
    }
    return stack;
  }

  function toast(message, options) {
    var opts = options || {};
    var kind = TOAST_ICON[opts.kind] ? opts.kind : 'info';
    var ms = typeof opts.duration === 'number' ? opts.duration : 3400;
    var stack = toastStack();
    var item = document.createElement('div');
    item.className = 'nx-toast nx-toast-' + kind;
    item.innerHTML =
      '<span class="material-symbols-outlined nx-toast-icon">' + TOAST_ICON[kind] + '</span>' +
      '<span class="nx-toast-text">' + escapeHtml(message) + '</span>' +
      '<button type="button" class="nx-toast-close" aria-label="关闭提示">' +
      '<span class="material-symbols-outlined">close</span></button>' +
      (ms > 0 ? '<span class="nx-toast-bar" style="animation-duration:' + ms + 'ms"></span>' : '');
    stack.appendChild(item);

    var timer = 0;
    var remove = function () {
      if (!item.parentNode) { return; }
      window.clearTimeout(timer);
      item.classList.add('is-out');
      window.setTimeout(function () {
        if (item.parentNode) { item.parentNode.removeChild(item); }
      }, 220);
    };

    item.querySelector('.nx-toast-close').addEventListener('click', remove);
    // 鼠标停上去就暂停倒计时：正在读提示的人不该被计时器打断。
    // 进度条是 CSS 动画，光停 JS 计时器会让条子先跑完、提示却还留着 —— 两处一起停。
    var bar = item.querySelector('.nx-toast-bar');
    item.addEventListener('mouseenter', function () {
      window.clearTimeout(timer);
      if (bar) { bar.style.animationPlayState = 'paused'; }
    });
    item.addEventListener('mouseleave', function () {
      if (bar) { bar.style.animationPlayState = 'running'; }
      if (ms > 0) { timer = window.setTimeout(remove, 1200); }
    });
    if (ms > 0) {
      timer = window.setTimeout(remove, ms);
    }
    return remove;
  }

  /* ══════════════════════════════════════════════════════════════════════════
   * 3. 复制
   *
   * 班级邀请码、学号这类东西让学生手动拖选很容易漏字符。
   * 注意 navigator.clipboard 只在安全上下文（https / localhost）可用，
   * 学校内网用 http://192.168.x.x 打开时它是 undefined —— 所以必须留一条
   * execCommand 的老路，否则「内网打开复制按钮点了没反应」。
   * ══════════════════════════════════════════════════════════════════════════ */

  function legacyCopy(text) {
    try {
      var area = document.createElement('textarea');
      area.value = text;
      area.setAttribute('readonly', 'readonly');
      area.style.position = 'fixed';
      area.style.top = '-1000px';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      var ok = document.execCommand('copy');
      document.body.removeChild(area);
      return ok;
    } catch (err) {
      return false;
    }
  }

  function copyText(text) {
    var value = text == null ? '' : String(text);
    var done = function () { toast('已复制到剪贴板', { kind: 'ok', duration: 2200 }); };
    var failed = function () { toast('复制失败，请手动选择文本', { kind: 'danger' }); };

    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(value).then(done, function () {
        if (legacyCopy(value)) { done(); } else { failed(); }
      });
      return;
    }
    if (legacyCopy(value)) { done(); } else { failed(); }
  }

  /* ══════════════════════════════════════════════════════════════════════════
   * 4. 顶部阅读进度条
   *
   * 综测汇总与名册这类页面很长，学生往下翻时完全没有「还剩多少」的概念。
   * 用 transform: scaleX() 而不是改 width：scale 只合成不重排，
   * 滚动时不会引起整页 reflow。滚动事件里必须走 rAF 节流，否则
   * 每个滚动事件都读写一次布局，在低配机器上会明显卡顿。
   * ══════════════════════════════════════════════════════════════════════════ */

  function initProgress() {
    if (docEl.getAttribute('data-nx-progress') === 'off') {
      return;
    }
    var bar = document.createElement('div');
    bar.className = 'nx-progress-top';
    bar.setAttribute('aria-hidden', 'true'); // 纯装饰，不该被读屏念出来
    var fill = document.createElement('i');
    bar.appendChild(fill);
    document.body.appendChild(bar);

    var ticking = false;
    function update() {
      ticking = false;
      var max = docEl.scrollHeight - window.innerHeight;
      var ratio = max > 4 ? Math.min(1, Math.max(0, (window.pageYOffset || docEl.scrollTop || 0) / max)) : 0;
      fill.style.transform = 'scaleX(' + ratio + ')';
      // 页面根本没得滚（或已经回到顶部）时整条隐藏：静态页面上不该常驻一根线。
      bar.classList.toggle('is-on', ratio > 0.004 && max > 4);
    }
    function request() {
      if (!ticking) {
        ticking = true;
        window.requestAnimationFrame(update);
      }
    }

    window.addEventListener('scroll', request, { passive: true });
    window.addEventListener('resize', request);
    update();
  }

  /* ══════════════════════════════════════════════════════════════════════════
   * 5. 滚动渐入
   *
   * .nx-reveal 这个类由 JS 添加：没有 JS 时元素就是普通可见元素，
   * 而不是「永远 opacity:0 的白页」。这是这类「入场动画」最常见的翻车点。
   * ══════════════════════════════════════════════════════════════════════════ */

  function initReveal() {
    var nodes = Array.prototype.slice.call(document.querySelectorAll('[data-nx-reveal]:not(.nx-reveal)'));
    if (!nodes.length) {
      return;
    }
    if (reduced() || !('IntersectionObserver' in window)) {
      nodes.forEach(function (el) { el.classList.add('nx-reveal', 'is-in'); });
      return;
    }

    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) {
          return;
        }
        var el = entry.target;
        var delay = parseInt(el.getAttribute('data-nx-reveal-delay') || '0', 10);
        if (delay > 0) {
          el.style.transitionDelay = delay + 'ms';
        }
        el.classList.add('is-in');
        observer.unobserve(el); // 只入场一次，来回滚动不该反复闪
      });
    }, { rootMargin: '0px 0px -6% 0px', threshold: 0.05 });

    nodes.forEach(function (el) {
      el.classList.add('nx-reveal');
      observer.observe(el);
    });
  }

  /* ══════════════════════════════════════════════════════════════════════════
   * 6. 数字滚动
   *
   * 只对「纯整数」生效：把 2026-09-27 或 4.5 当数字滚一遍会滚出乱码。
   * 数据是异步来的（先渲染成 —），所以对外暴露 countUp(el)，
   * 由页面在拿到数据、写完文本之后调用。
   * ══════════════════════════════════════════════════════════════════════════ */

  function countUp(el, duration) {
    if (!el) {
      return;
    }
    var raw = (el.textContent || '').replace(/,/g, '').trim();
    var target = /^-?\d+$/.test(raw) ? parseInt(raw, 10) : NaN;
    if (!isFinite(target)) {
      return; // 不是整数就原样留着，别把内容改坏
    }
    el.classList.add('nx-tnum');
    if (reduced() || Math.abs(target) < 2) {
      el.textContent = String(target);
      return;
    }

    // 第 9 轮：数字滚动改由 vendored 的 CountUp.js（/libs/countup/countUp.umd.js，MIT）负责，
    // 库没加载到、或它抛错，就退回下面这套手写 rAF —— 少一种效果，不影响功能。
    var CU = window.countUp && (window.countUp.CountUp || window.countUp.default);
    if (CU) {
      try {
        el.textContent = '0';
        new CU(el, target, {
          duration: (typeof duration === 'number' ? duration : 760) / 1000,
          startVal: 0,
          useGrouping: false,
          onCompleteCallback: function () { el.textContent = String(target); }
        }).start();
        return;
      } catch (e) { /* 参数不被接受就落回手写实现 */ }
    }

    var ms = typeof duration === 'number' ? duration : 760;
    var started = 0;
    function frame(now) {
      if (!started) { started = now; }
      var p = Math.min(1, (now - started) / ms);
      var eased = 1 - Math.pow(1 - p, 3); // easeOutCubic：结尾收得稳，不像线性那样「硬停」
      el.textContent = String(Math.round(target * eased));
      if (p < 1) {
        window.requestAnimationFrame(frame);
      } else {
        el.textContent = String(target); // 收尾写准确值，避免四舍五入差 1
      }
    }
    el.textContent = '0';
    window.requestAnimationFrame(frame);
  }

  function initCountUp() {
    Array.prototype.slice.call(document.querySelectorAll('[data-nx-count]')).forEach(function (el) {
      var observer = 'IntersectionObserver' in window
        ? new IntersectionObserver(function (entries) {
          entries.forEach(function (entry) {
            if (entry.isIntersecting) {
              observer.disconnect();
              countUp(el);
            }
          });
        }, { threshold: 0.4 })
        : null;
      if (observer) {
        observer.observe(el);
      } else {
        countUp(el);
      }
    });
  }

  /* ══════════════════════════════════════════════════════════════════════════
   * 7. 鼠标聚光
   *
   * 只给 [data-nx-spotlight] 用，且只在真有指针、没有设「减少动态效果」时启用。
   * 触屏设备上 pointermove 会在手指按住时乱跳，效果变成噪声，所以直接不做。
   * ══════════════════════════════════════════════════════════════════════════ */

  function initSpotlight() {
    var canHover = window.matchMedia && window.matchMedia('(hover: hover)').matches;
    if (!canHover || reduced()) {
      return;
    }
    Array.prototype.slice.call(document.querySelectorAll('[data-nx-spotlight]:not(.nx-spot)')).forEach(function (el) {
      el.classList.add('nx-spot');
      var queued = 0;
      el.addEventListener('pointermove', function (event) {
        if (queued) {
          return;
        }
        queued = window.requestAnimationFrame(function () {
          queued = 0;
          var box = el.getBoundingClientRect();
          if (!box.width || !box.height) {
            return;
          }
          el.style.setProperty('--nx-mx', ((event.clientX - box.left) / box.width * 100).toFixed(2) + '%');
          el.style.setProperty('--nx-my', ((event.clientY - box.top) / box.height * 100).toFixed(2) + '%');
        });
      });
      el.addEventListener('pointerleave', function () {
        el.style.removeProperty('--nx-mx');
        el.style.removeProperty('--nx-my');
      });
    });
  }

  /* ══════════════════════════════════════════════════════════════════════════
   * 8. 命令面板（Ctrl / ⌘ + K）
   *
   * 让全校学生去记住「假条审批在哪个页面的第几个页签」是不现实的。
   * 命令面板把「页面上所有可做的事」摊成一张能搜的清单：
   * 只认页面里已有的元素，不做第二套真相 ——
   *   · [data-nx-cmd] 标注过的按钮/卡片（选中 = 触发它自己的 click）
   *   · 页面里的站内链接（走 href 跳转）
   * 所以「新加一个功能忘了加到命令面板」不会发生：它本来就在页面上。
   * 面板每次打开时现场收集，异步渲染出来的假条与奖惩卡片也能立刻搜到。
   * ══════════════════════════════════════════════════════════════════════════ */

  var CMDK_RECENT_KEY = 'nest-cmdk-recent';
  var cmdk = { root: null, input: null, list: null, entries: [], shown: [], active: 0, open: false, restore: null };

  function paletteEntries() {
    var out = [];
    var pageCmds = [];
    var linkCmds = [];
    var labels = {};

    // 每个页面都该有的条目，不依赖页面写任何标记：
    // 换主题、回到顶部。页面自己已经有同名条目（比如顶栏上的主题按钮）就不重复加 ——
    // 同一个行为出现两份，只会让人以为它们是两件事。
    function builtin(label, group, icon, extra, run, when) {
      if (!when || labels[label.toLowerCase()]) {
        return;
      }
      labels[label.toLowerCase()] = true;
      out.push({ label: label, group: group, icon: icon, extra: extra, run: run });
    }

    var dark = themeIsDark();
    Array.prototype.slice.call(document.querySelectorAll('[data-nx-cmd]')).forEach(function (el) {
      var label = (el.getAttribute('data-nx-cmd') || plainLabel(el)).replace(/\s+/g, ' ').trim();
      if (!label) {
        return;
      }
      pageCmds.push({
        label: label,
        group: el.getAttribute('data-nx-cmd-group') || '操作',
        icon: el.getAttribute('data-nx-cmd-icon') || iconOf(el) || 'bolt',
        extra: (el.getAttribute('data-nx-cmd-keywords') || '').toLowerCase(),
        el: el
      });
      labels[label.toLowerCase()] = true;
    });

    Array.prototype.slice.call(document.querySelectorAll('a[href]')).forEach(function (link) {
      if (link.hasAttribute('data-nx-cmd')) {
        return;
      }
      var href = link.getAttribute('href') || '';
      if (!href || href.charAt(0) === '#' || href.indexOf('javascript:') === 0) {
        return;
      }
      if (!/^(\/|\.\/|[a-z0-9_-]+\.html)/i.test(href)) {
        return; // 站外链接不进面板：学生要的是站内跳转
      }
      var label = plainLabel(link);
      if (!label || label.length > 24 || labels[label.toLowerCase()]) {
        return;
      }
      labels[label.toLowerCase()] = true;
      linkCmds.push({ label: label, group: '跳转', icon: iconOf(link) || 'arrow_forward', extra: href.toLowerCase(), el: link });
    });

    // 页面自己的条目收集完再决定加不加内置的：页面上已经有换主题的入口时，
    // 面板里就不该再冒出一条同义的（同一个动作两个名字，只会让人点错）。
    /* nx-icons: dark_mode light_mode arrow_upward */
    var pageHasTheme = Object.keys(labels).some(function (label) {
      return /深色|浅色|主题|theme|dark|light/.test(label);
    });
    builtin('切换深色模式', '外观', 'dark_mode', 'theme dark 深色 夜间 主题', function () { setTheme(true); }, !dark && !pageHasTheme);
    builtin('切换浅色模式', '外观', 'light_mode', 'theme light 浅色 日间 主题', function () { setTheme(false); }, dark && !pageHasTheme);
    builtin('回到顶部', '操作', 'arrow_upward', 'top 顶部 回到顶部 scroll', function () {
      window.scrollTo({ top: 0, behavior: reduced() ? 'auto' : 'smooth' });
    }, (window.scrollY || window.pageYOffset || 0) > 300);

    // 顺序 = 面板里分组的顺序：先是每个页面都有的（外观/操作），再是这一页自己的，最后是站内跳转。
    return out.concat(pageCmds, linkCmds);
  }

  function recentLabels() {
    var raw = readStore(CMDK_RECENT_KEY);
    if (!raw) {
      return [];
    }
    try {
      var parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed : [];
    } catch (err) {
      return [];
    }
  }

  function rememberRecent(label) {
    var list = recentLabels().filter(function (item) { return item !== label; });
    list.unshift(label);
    writeStore(CMDK_RECENT_KEY, JSON.stringify(list.slice(0, 6)));
  }

  /**
   * 子序列模糊匹配（GitHub / VS Code 那一类）。
   * 为什么不用 includes：学生想问「java 作业」，而条目叫「Java基础语法练习」——
   * 中间隔了几个字就搜不到，会让人以为面板是坏的。
   * 打分偏好：连着的字符 > 分散的；命中开头 > 命中中间；短的 > 长的。
   */
  function fuzzy(query, text) {
    var q = query.toLowerCase();
    var t = text.toLowerCase();
    if (!q) {
      return { score: 0, hits: [] };
    }
    var qi = 0;
    var streak = 0;
    var score = 0;
    var hits = [];
    for (var i = 0; i < t.length && qi < q.length; i++) {
      if (t.charAt(i) === q.charAt(qi)) {
        hits.push(i);
        qi++;
        streak++;
        score += 2 + streak * 3 + (i === 0 ? 6 : 0);
      } else {
        streak = 0;
      }
    }
    if (qi < q.length) {
      return null;
    }
    score -= (t.length - hits.length) * 0.12;
    return { score: score, hits: hits };
  }

  function highlight(label, hits) {
    var marks = {};
    (hits || []).forEach(function (index) { marks[index] = true; });
    var out = '';
    var opened = false;
    for (var i = 0; i < label.length; i++) {
      var hit = !!marks[i];
      if (hit && !opened) {
        out += '<mark class="nx-cmdk-hit">';
        opened = true;
      } else if (!hit && opened) {
        out += '</mark>';
        opened = false;
      }
      out += escapeHtml(label.charAt(i));
    }
    return opened ? out + '</mark>' : out;
  }

  function cmdkRows(query) {
    var all = cmdk.entries;
    var rows = [];
    if (!query) {
      var recent = recentLabels();
      recent.forEach(function (label) {
        var hit = all.filter(function (entry) { return entry.label === label; })[0];
        if (hit) {
          rows.push({ entry: hit, label: hit.label, hits: [], group: '最近使用' });
        }
      });
      all.forEach(function (entry) {
        rows.push({ entry: entry, label: entry.label, hits: [], group: entry.group });
      });
      return rows;
    }

    var scored = [];
    all.forEach(function (entry) {
      var best = fuzzy(query, entry.label);
      var penalty = 0;
      if (!best && entry.extra) {
        // 关键词/URL 命中算「次等命中」：能找到，但排在有真正标题命中的后面。
        best = fuzzy(query, entry.extra);
        penalty = 8;
      }
      if (best) {
        scored.push({ entry: entry, label: entry.label, hits: best.hits, group: entry.group, score: best.score - penalty });
      }
    });
    scored.sort(function (a, b) { return b.score - a.score; });
    return scored;
  }

  function renderCmdk(query) {
    cmdk.shown = cmdkRows(query);
    var html = '';
    var lastGroup = '';
    var flat = 0;
    cmdk.shown.forEach(function (row, index) {
      if (row.group !== lastGroup) {
        lastGroup = row.group;
        html += '<p class="nx-cmdk-group">' + escapeHtml(row.group) + '</p>';
      }
      html +=
        '<button type="button" class="nx-cmdk-item' + (index === cmdk.active ? ' is-active' : '') + '"' +
        ' id="nx-cmdk-opt-' + index + '" role="option" aria-selected="' + (index === cmdk.active ? 'true' : 'false') + '"' +
        ' data-nx-cmdk-index="' + index + '">' +
        '<span class="material-symbols-outlined nx-cmdk-item-icon">' + escapeHtml(row.entry.icon || 'bolt') + '</span>' +
        '<span class="nx-cmdk-item-label">' + highlight(row.label, row.hits) + '</span>' +
        '<span class="nx-cmdk-item-hint">' + escapeHtml(row.entry.group) + '</span>' +
        '</button>';
      flat++;
    });

    if (!flat) {
      html = '<div class="nx-cmdk-none">' +
        '<span class="material-symbols-outlined">search_off</span>' +
        '<p>没有匹配「' + escapeHtml(query) + '」的内容</p>' +
        '<p class="nx-cmdk-none-hint">换个说法试试，比如「请假」「综测」「宿舍」。</p>' +
        '</div>';
    }
    cmdk.list.innerHTML = html;

    if (cmdk.active >= cmdk.shown.length) {
      cmdk.active = cmdk.shown.length ? 0 : -1;
    }
    var active = cmdk.list.querySelector('.nx-cmdk-item.is-active');
    if (active && active.scrollIntoView) {
      active.scrollIntoView({ block: 'nearest' });
    }
    cmdk.input.setAttribute('aria-activedescendant', cmdk.active >= 0 ? 'nx-cmdk-opt-' + cmdk.active : '');
  }

  function moveCmdk(step) {
    if (!cmdk.shown.length) {
      return;
    }
    cmdk.active = (cmdk.active + step + cmdk.shown.length) % cmdk.shown.length;
    renderCmdk(cmdk.input.value.trim());
  }

  function buildCmdk() {
    var root = document.createElement('div');
    root.className = 'nx-cmdk';
    root.setAttribute('role', 'dialog');
    root.setAttribute('aria-modal', 'true');
    root.setAttribute('aria-label', '命令面板');
    root.innerHTML =
      '<div class="nx-cmdk-backdrop" data-nx-cmdk-dismiss></div>' +
      '<div class="nx-cmdk-panel">' +
      '<div class="nx-cmdk-search">' +
      '<span class="material-symbols-outlined nx-cmdk-search-icon">search</span>' +
      '<input type="text" class="nx-cmdk-input" role="combobox" aria-expanded="true" aria-autocomplete="list"' +
      ' aria-controls="nx-cmdk-list" placeholder="搜索假条、奖惩、页面…" autocomplete="off" spellcheck="false"/>' +
      '<kbd class="nx-kbd nx-cmdk-esc">Esc</kbd>' +
      '</div>' +
      '<div class="nx-cmdk-list nx-scroll" id="nx-cmdk-list" role="listbox" aria-label="搜索结果"></div>' +
      '<div class="nx-cmdk-foot">' +
      '<span><kbd class="nx-kbd">↑</kbd><kbd class="nx-kbd">↓</kbd> 选择</span>' +
      '<span><kbd class="nx-kbd">Enter</kbd> 打开</span>' +
      '<span><kbd class="nx-kbd">Esc</kbd> 关闭</span>' +
      '<span class="nx-cmdk-brand">NEST 命令面板</span>' +
      '</div>' +
      '</div>';
    document.body.appendChild(root);

    cmdk.root = root;
    cmdk.input = root.querySelector('.nx-cmdk-input');
    cmdk.list = root.querySelector('.nx-cmdk-list');

    cmdk.input.addEventListener('input', function () {
      cmdk.active = 0;
      renderCmdk(cmdk.input.value.trim());
    });
    // 鼠标点条目：mousedown 而不是 click —— 面板会在输入框失焦时关闭，
    // 用 click 的话「先失焦关闭」会抢在「选中」前面，表现为点了没反应。
    cmdk.list.addEventListener('mousedown', function (event) {
      var item = clickTarget(event, '[data-nx-cmdk-index]');
      if (!item) {
        return;
      }
      event.preventDefault();
      activateCmdk(cmdk.shown[parseInt(item.getAttribute('data-nx-cmdk-index'), 10)]);
    });
    root.addEventListener('mousedown', function (event) {
      if (clickTarget(event, '[data-nx-cmdk-dismiss]')) {
        closeCmdk();
      }
    });
  }

  function openCmdk(trigger) {
    if (!cmdk.root) {
      buildCmdk();
    }
    cmdk.entries = paletteEntries();
    cmdk.active = 0;
    // 每次打开现场收集：课程卡片是异步渲染的，缓存下来就会搜不到新加的课。
    cmdk.restore = trigger || document.activeElement;
    cmdk.open = true;
    cmdk.input.value = '';
    renderCmdk('');
    cmdk.root.classList.add('is-open');
    document.body.classList.add('nx-noscroll');
    window.setTimeout(function () { cmdk.input.focus(); }, 20);
  }

  function closeCmdk() {
    if (!cmdk.root || !cmdk.open) {
      return;
    }
    cmdk.open = false;
    cmdk.root.classList.remove('is-open');
    document.body.classList.remove('nx-noscroll');
    if (cmdk.restore && cmdk.restore.focus) {
      cmdk.restore.focus();
    }
  }

  function activateCmdk(row) {
    if (!row || !row.entry) {
      return;
    }
    rememberRecent(row.label);
    // 触发元素自己的行为，而不是另写一份跳转逻辑：
    // 页面上这个按钮原本怎么工作，从这里打开就怎么工作。
    closeCmdk();
    var el = row.entry.el;
    if (el && typeof el.click === 'function') {
      el.click();
    } else if (row.entry.href) {
      window.location.href = row.entry.href;
    } else if (typeof row.entry.run === 'function') {
      row.entry.run(); // 内置条目（换主题、回到顶部）没有对应元素，自带行为
    }
  }

  function initCmdk() {
    Array.prototype.slice.call(document.querySelectorAll('[data-nx-kbd-text]')).forEach(function (el) {
      el.textContent = isMac ? '⌘ K' : 'Ctrl K';
    });

    // 快捷键写在 document 上（而不是面板自己身上），这样面板没打开时也能被叫出来。
    document.addEventListener('keydown', function (event) {
      var key = event.key || '';
      if ((event.ctrlKey || event.metaKey) && (key === 'k' || key === 'K')) {
        event.preventDefault();
        if (cmdk.open) { closeCmdk(); } else { openCmdk(null); }
        return;
      }
      if (!cmdk.open) {
        // 「/」快速打开：GitHub 上最顺手的那个键，且不会和输入框冲突。
        var tag = (event.target && event.target.tagName) || '';
        var typing = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (event.target && event.target.isContentEditable);
        if (key === '/' && !typing && !event.ctrlKey && !event.metaKey && !event.altKey) {
          event.preventDefault();
          openCmdk(null);
        }
        return;
      }
      if (key === 'Escape') {
        event.preventDefault();
        closeCmdk();
      } else if (key === 'ArrowDown') {
        event.preventDefault();
        moveCmdk(1);
      } else if (key === 'ArrowUp') {
        event.preventDefault();
        moveCmdk(-1);
      } else if (key === 'Home') {
        event.preventDefault();
        cmdk.active = 0;
        renderCmdk(cmdk.input.value.trim());
      } else if (key === 'End') {
        event.preventDefault();
        cmdk.active = cmdk.shown.length - 1;
        renderCmdk(cmdk.input.value.trim());
      } else if (key === 'Enter') {
        event.preventDefault();
        activateCmdk(cmdk.shown[cmdk.active]);
      } else if (key === 'Tab') {
        // 面板里只有输入框一个可聚焦元素，Tab 出去会把焦点丢到背后的页面上，
        // 读屏用户会「跳进一个看不见的页面」。直接把 Tab 吸收掉。
        event.preventDefault();
      }
    });
  }

  /* ══════════════════════════════════════════════════════════════════════════
   * 全局委托：主题开关 / 复制 / 打开面板
   * 用委托而不是逐元素绑定 —— 异步插进来的按钮（卡片上的复制按钮）
   * 不需要再单独绑一次事件。
   * ══════════════════════════════════════════════════════════════════════════ */

  /* ══════════════════════════════════════════════════════════════════════════
   * 按下时的涟漪
   * ══════════════════════════════════════════════════════════════════════════ */

  // 只给「真的可点的按钮」加，卡片之类大面积元素不加 —— 满屏涟漪比没有还吵。
  var RIPPLE_SELECTOR = '.nx-btn, .nx-icon-btn, .nx-tab, [data-nx-ripple]';

  function spawnRipple(host, event) {
    var rect = host.getBoundingClientRect();
    var size = Math.max(rect.width, rect.height) * 2;
    var ink = document.createElement('span');
    ink.className = 'nx-ripple';
    ink.style.width = size + 'px';
    ink.style.height = size + 'px';
    ink.style.left = (event.clientX - rect.left - size / 2) + 'px';
    ink.style.top = (event.clientY - rect.top - size / 2) + 'px';
    host.classList.add('nx-ripple-host');
    host.appendChild(ink);
    window.setTimeout(function () { ink.remove(); }, 700);
  }

  function initRipple() {
    if (reduced()) {
      return;
    }
    document.addEventListener('pointerdown', function (event) {
      if (typeof event.button === 'number' && event.button !== 0) {
        return;
      }
      var host = clickTarget(event, RIPPLE_SELECTOR);
      if (host) {
        spawnRipple(host, event);
      }
    }, { passive: true });
  }

  /* ══════════════════════════════════════════════════════════════════════════
   * 顶栏滚起来之后浮一点
   * ══════════════════════════════════════════════════════════════════════════ */

  // 21 个页面各写了自己的顶栏，不该为这点效果去改 21 个文件：
  // 谁真的粘在顶上（sticky / fixed）就给谁加一道阴影，不粘的页面自然什么都不发生。
  function initElevate() {
    var bars = [];
    Array.prototype.slice.call(document.querySelectorAll('header, nav')).forEach(function (el) {
      var position = window.getComputedStyle(el).position;
      if (position === 'sticky' || position === 'fixed') {
        bars.push(el);
      }
    });
    if (!bars.length) {
      return;
    }
    var ticking = false;
    function apply() {
      ticking = false;
      var scrolled = (window.scrollY || window.pageYOffset || 0) > 8;
      bars.forEach(function (el) {
        if (scrolled) {
          el.setAttribute('data-nx-scrolled', '1');
        } else {
          el.removeAttribute('data-nx-scrolled');
        }
      });
    }
    function onScroll() {
      if (ticking) {
        return;
      }
      ticking = true;
      window.requestAnimationFrame(apply);
    }
    window.addEventListener('scroll', onScroll, { passive: true });
    apply();
  }

  /* ══════════════════════════════════════════════════════════════════════════
   * 顶栏上的 ⌘K 入口
   * ══════════════════════════════════════════════════════════════════════════ */

  // 命令面板在每个页面都能用，但没人知道它存在。21 个页面各写一遍按钮太笨，
  // 于是：顶栏右侧那一组里没有入口时，就补一个（窄屏 CSS 只留放大镜）。
  function initChip() {
    if (document.querySelector('[data-nx-cmdk-open]')) {
      return;
    }
    var header = document.querySelector('header');
    var group = header && header.lastElementChild;
    if (!group || group === header || !group.children || !group.children.length) {
      return;
    }
    var chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'nx-cmdk-chip';
    chip.setAttribute('data-nx-cmdk-open', '');
    chip.setAttribute('aria-label', '搜索假条、奖惩、页面（Ctrl 或 Command + K）');
    chip.title = '命令面板';
    /* nx-icons: search */
    chip.innerHTML = '<span class="material-symbols-outlined">search</span>' +
      '<span class="nx-cmdk-chip-text">Search</span>' +
      '<kbd class="nx-kbd" data-nx-kbd-text></kbd>';
    group.appendChild(chip);
  }

  function initDelegates() {
    document.addEventListener('click', function (event) {
      var themeBtn = clickTarget(event, '[data-nx-theme-toggle]');
      if (themeBtn) {
        event.preventDefault();
        setTheme(!docEl.classList.contains('dark'), themeBtn);
        return;
      }
      var openBtn = clickTarget(event, '[data-nx-cmdk-open]');
      if (openBtn) {
        event.preventDefault();
        openCmdk(openBtn);
        return;
      }
      var copyBtn = clickTarget(event, '[data-nx-copy]');
      if (copyBtn) {
        event.preventDefault();
        copyText(copyBtn.getAttribute('data-nx-copy') || copyBtn.textContent);
      }
    });
  }

  /* ══════════════════════════════════════════════════════════════════════════
   * 统一对话框：alert / confirm / prompt
   * 全站 18 处原生弹窗的收口点。原生弹窗的三个毛病（换不了皮肤、换不了
   * 按钮文案、把整个 tab 锁死）写在 input.css 的 .nx-dialog-* 注释里。
   *
   * 用 Promise 而不是回调：调用点几乎都在 async 函数里，
   * `if (!(await NestUI.dialog.confirm('…'))) return;` 读起来和原来的
   * `if (!confirm('…')) return;` 一模一样，不必把周围逻辑改成回调风格。
   * ══════════════════════════════════════════════════════════════════════════ */

  var DLG_TITLE = { alert: '提示', confirm: '请确认', prompt: '请输入' };

  // 唯一实例的句柄：同一时刻只允许一个对话框。
  var dlg = {
    root: null,      // 固定层（role="dialog" 挂在它身上）
    kind: '',        // 'alert' | 'confirm' | 'prompt'
    settle: null,    // 本次调用的 resolve，收场时只允许调一次
    restore: null,   // 打开前的焦点，关闭后还回去
    multiline: false
  };
  var dlgSeq = 0;

  // 「取消」在不同类型里的语义：alert 没有返回值、confirm 是 false、prompt 是 null。
  function dlgCancelValue(kind) {
    if (kind === 'confirm') {
      return false;
    }
    if (kind === 'prompt') {
      return null;
    }
    return undefined;
  }

  // 焦点还回去之前先确认那个元素还在文档里：调用点经常在 await 之后重渲染一遍列表，
  // 原来的按钮已经被换掉了。
  function dlgAttached(el) {
    if (!el || typeof el.focus !== 'function') {
      return false;
    }
    if (typeof document.contains === 'function') {
      return document.contains(el);
    }
    return true;
  }

  function dlgFocusables() {
    if (!dlg.root) {
      return [];
    }
    var panel = dlg.root.querySelector('.nx-dialog-panel');
    if (!panel) {
      return [];
    }
    var nodes = panel.querySelectorAll('button, input, textarea, select, a[href]');
    var list = [];
    Array.prototype.slice.call(nodes).forEach(function (el) {
      if (el.disabled) {
        return;
      }
      list.push(el);
    });
    return list;
  }

  // 统一出口：按钮、Esc、点遮罩、被下一个对话框顶掉，四条路都走这里，
  // 保证 resolve 只发生一次、焦点一定还回去。
  function dlgClose(value) {
    if (!dlg.root) {
      return;
    }
    var root = dlg.root;
    var settle = dlg.settle;
    var restore = dlg.restore;
    dlg.root = null;
    dlg.kind = '';
    dlg.settle = null;
    dlg.restore = null;
    if (root.parentNode) {
      root.parentNode.removeChild(root);
    }
    // 命令面板也用这个类锁滚动，它开着的时候别替它解锁。
    if (!cmdk.open) {
      document.body.classList.remove('nx-noscroll');
    }
    if (dlgAttached(restore)) {
      try {
        restore.focus();
      } catch (error) {
        // 焦点还回去失败，不该连带把 resolve 也一起丢掉。
      }
    }
    if (settle) {
      settle(value);
    }
  }

  // prompt 的结算值：空输入按「取消」（null）处理 —— 调用点统一判 null，
  // 让空串漏过去会变成「拿空学号去查学生」这类事故。
  function dlgSubmit(input) {
    if (dlg.kind === 'prompt') {
      var value = input && input.value != null ? String(input.value) : '';
      dlgClose(value.trim() === '' ? null : value);
      return;
    }
    if (dlg.kind === 'confirm') {
      dlgClose(true);
      return;
    }
    dlgClose(undefined);
  }

  function dlgOpen(kind, message, options) {
    var opts = options || {};
    // 前一个还没收场就又开了一个：老的按「取消」结算，否则它那个 await
    // 会永远悬着，页面上表现为「点了没反应」。
    if (dlg.root) {
      dlgClose(dlgCancelValue(dlg.kind));
    }

    var settle = null;
    var promise = new Promise(function (resolve) { settle = resolve; });
    dlg.settle = settle;
    dlg.kind = kind;
    dlg.restore = document.activeElement;
    dlg.multiline = opts.multiline === true;

    try {
      buildDlg(kind, message, opts);
    } catch (error) {
      // DOM 没建起来也必须把 Promise 结掉，不然调用点永远等下去。
      dlg.root = null;
      dlg.kind = '';
      dlg.settle = null;
      if (window.console && console.error) {
        console.error('[nest-ui] 对话框构建失败：', error);
      }
      settle(dlgCancelValue(kind));
    }
    return promise;
  }

  function buildDlg(kind, message, options) {
    var danger = options.danger === true;
    var multiline = options.multiline === true;
    var titleId = 'nx-dialog-title-' + (++dlgSeq);
    var titleText = options.title || DLG_TITLE[kind] || '提示';
    var okText = options.okText || '确定';
    var cancelText = options.cancelText || '取消';
    var bodyText = message == null ? '' : String(message);
    var placeholder = typeof options.placeholder === 'string' ? options.placeholder : '';

    // 文案一律过 escapeHtml；正文里的 \n 原样留着，靠 CSS 的 pre-wrap 折行
    // （原生 alert 就支持换行，迁移时不能把这些换行吃掉）。
    var html = '<div class="nx-dialog-backdrop" data-nx-dialog-dismiss></div>' +
      '<div class="nx-dialog nx-dialog-panel' + (danger ? ' nx-dialog-danger' : '') + '">' +
      '<p class="nx-dialog-title" id="' + titleId + '">' + escapeHtml(titleText) + '</p>' +
      '<p class="nx-dialog-body">' + escapeHtml(bodyText) + '</p>';
    if (kind === 'prompt') {
      if (multiline) {
        html += '<textarea class="nx-dialog-input" rows="3" placeholder="' +
          escapeHtml(placeholder) + '"></textarea>';
      } else {
        html += '<input type="text" class="nx-dialog-input" placeholder="' + escapeHtml(placeholder) +
          '" autocomplete="off" spellcheck="false"/>';
      }
    }
    html += '<div class="nx-dialog-actions">';
    if (kind !== 'alert') {
      html += '<button type="button" class="nx-btn nx-btn-ghost" data-nx-dialog-cancel>' +
        escapeHtml(cancelText) + '</button>';
    }
    html += '<button type="button" class="nx-btn ' + (danger ? 'nx-btn-danger' : 'nx-btn-primary') +
      '" data-nx-dialog-ok>' + escapeHtml(okText) + '</button>';
    html += '</div></div>';

    var root = document.createElement('div');
    root.className = 'nx-dialog-layer';
    root.setAttribute('role', 'dialog');
    root.setAttribute('aria-modal', 'true');
    // 标题节点就是 aria-labelledby 的目标：读屏进来先念标题，再念正文。
    root.setAttribute('aria-labelledby', titleId);
    root.innerHTML = html;
    document.body.appendChild(root);

    dlg.root = root;
    document.body.classList.add('nx-noscroll');

    var input = root.querySelector('.nx-dialog-input');
    if (input && typeof options.defaultValue === 'string') {
      // 用属性赋值而不是拼进 HTML：默认值里带引号也不会破标签。
      input.value = options.defaultValue;
    }

    var okBtn = root.querySelector('[data-nx-dialog-ok]');
    var cancelBtn = root.querySelector('[data-nx-dialog-cancel]');

    // mousedown 而不是 click：和命令面板一致，按下就收场，不会被面板里的
    // 其它「按下/抬起」逻辑抢先。
    root.addEventListener('mousedown', function (event) {
      if (clickTarget(event, '[data-nx-dialog-dismiss]')) {
        dlgClose(dlgCancelValue(dlg.kind));
      }
    });

    root.addEventListener('click', function (event) {
      if (clickTarget(event, '[data-nx-dialog-ok]')) {
        dlgSubmit(input);
        return;
      }
      if (clickTarget(event, '[data-nx-dialog-cancel]')) {
        dlgClose(dlgCancelValue(dlg.kind));
      }
    });

    root.addEventListener('keydown', function (event) {
      var key = event.key;
      if (key === 'Escape' || key === 'Esc') {
        event.preventDefault();
        dlgClose(dlgCancelValue(dlg.kind));
        return;
      }
      if (key === 'Tab') {
        // 焦点必须在面板里循环：Tab 跑出去就是把读屏用户丢进一层看不见的页面。
        var list = dlgFocusables();
        if (!list.length) {
          event.preventDefault();
          return;
        }
        var first = list[0];
        var last = list[list.length - 1];
        var active = document.activeElement;
        var inside = list.indexOf(active) !== -1;
        if (event.shiftKey) {
          if (!inside || active === first) {
            event.preventDefault();
            last.focus();
          }
          return;
        }
        if (!inside || active === last) {
          event.preventDefault();
          first.focus();
        }
        return;
      }
      // 单行 prompt 里回车＝确认（原生 prompt 的手感）；多行里回车是换行。
      if (key === 'Enter' && dlg.kind === 'prompt' && input &&
        event.target === input && !dlg.multiline) {
        event.preventDefault();
        dlgSubmit(input);
      }
    });

    // 初始焦点：danger 落在「取消」上（默认动作不能是危险的那个），其余落在「确认」上。
    // prompt 例外，焦点给输入框并全选：把焦点放在按钮上，用户敲字会敲到空处，
    // 等于把原生 prompt 的手感弄坏（原生 prompt 也是聚焦它的输入框）。
    if (kind === 'prompt' && input) {
      input.focus();
      if (typeof input.select === 'function') {
        try {
          input.select();
        } catch (error) {
          // 个别老浏览器不支持 select，聚焦成功就够了。
        }
      }
      return;
    }
    var initial = danger ? cancelBtn : okBtn;
    if (initial && typeof initial.focus === 'function') {
      initial.focus();
    }
  }

  var dialogApi = {
    alert: function (message, options) { return dlgOpen('alert', message, options); },
    confirm: function (message, options) { return dlgOpen('confirm', message, options); },
    prompt: function (message, options) { return dlgOpen('prompt', message, options); }
  };

  /* ══════════════════════════════════════════════════════════════════════════
   * 启动
   * ══════════════════════════════════════════════════════════════════════════ */

  function boot() {
    guard('主题', initTheme);
    guard('事件委托', initDelegates);
    guard('命令面板入口', initChip);
    guard('命令面板', initCmdk);
    guard('Toast 容器', function () { /* 首次真正用时才创建，这里只保证不报错 */ });
    guard('阅读进度条', initProgress);
    guard('滚动渐入', initReveal);
    guard('数字滚动', initCountUp);
    guard('鼠标聚光', initSpotlight);
    guard('按下涟漪', initRipple);
    guard('顶栏浮起', initElevate);
  }

  // 每次改这个文件都把这行往上加一位：探针里能直接读出页面到底加载到了哪一版。
  // 没有它的时候踩过一次「改了 JS，浏览器还在用内存里的旧副本，于是新功能看起来像没生效」，
  // 白查了一轮。
  var BUILD = 'ui-5';

  window.NestUI = {
    version: BUILD,
    theme: {
      get: function () { return docEl.classList.contains('dark') ? 'dark' : 'light'; },
      set: setTheme,
      toggle: function (origin) { setTheme(!docEl.classList.contains('dark'), origin); }
    },
    toast: toast,
    copy: copyText,
    countUp: countUp,
    // 全站统一的 alert / confirm / prompt（都返回 Promise）。
    dialog: dialogApi,
    palette: {
      open: openCmdk,
      close: closeCmdk,
      refresh: function () { if (cmdk.open) { cmdk.entries = paletteEntries(); renderCmdk(cmdk.input.value.trim()); } }
    },
    // 异步渲染完新内容后调一次，让新元素也能渐入 / 聚光。
    refresh: function () {
      guard('滚动渐入', initReveal);
      guard('数字滚动', initCountUp);
      guard('鼠标聚光', initSpotlight);
    },
    reducedMotion: reduced
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();

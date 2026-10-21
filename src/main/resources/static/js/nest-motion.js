/*! nest-motion.js — 学工平台 灵动层（第 9 轮：把动效交给有出处的库）
 *
 *  用到的东西全部 vendored 到 /libs/（离线校内网可用、零 CDN）：
 *    anime.js        v4.5.0  MIT   /libs/animejs/anime.umd.min.js        交错/弹性/序列动画引擎
 *    vanilla-tilt    v1.8.1  MIT   /libs/vanilla-tilt/vanilla-tilt.min.js 卡片 3D 倾斜（跟随鼠标）
 *    canvas-confetti v1.9.4  ISC   /libs/canvas-confetti/confetti.browser.js 成功时的彩带
 *  CountUp.js v2.10.1 MIT（/libs/countup/countUp.umd.js）由 nest-ui.js 的数字滚动使用，
 *  本文件**不再**实现数字滚动：[data-nx-count] 被两边同时接管会滚两遍。
 *
 *  引用顺序（都是 defer，按文档顺序执行）：
 *    <script defer src="/libs/animejs/anime.umd.min.js"></script>
 *    <script defer src="/libs/countup/countUp.umd.js"></script>
 *    <script defer src="/libs/vanilla-tilt/vanilla-tilt.min.js"></script>
 *    <script defer src="/libs/canvas-confetti/confetti.browser.js"></script>
 *    <script defer src="/js/nest-motion.js"></script>
 *
 *  自动生效（不用改页面）：
 *    · .nx-btn / .nx-icon-btn / .nx-chip / .nx-tab 鼠标跟随光斑（spotlight）
 *    · .nx-btn-primary / .nx-btn-danger / .nx-icon-btn 磁性微移 + 松手回弹
 *    · .nx-chip / .nx-badge / .nx-tab 点击回弹
 *    · .nx-card / .nx-tile / .nx-stat / .nx-empty 滚入视线时揭示（同容器内自动错峰）
 *    · .nx-tile 3D 倾斜；.nx-stat 里的数字滚动
 *    · 成功类 .nx-toast 出现时放一小把彩带
 *  按需生效（加属性）：
 *    data-nx-tilt / data-nx-stagger（子元素错峰） / data-nx-celebrate（点击放彩带）
 *    data-nx-magnetic / data-nx-spot
 *  分工（同一个元素别被两边接管）：
 *    [data-nx-reveal] [data-nx-count] [data-nx-spotlight] 归 nest-ui.js（.nx-reveal/.is-in/.nx-spot）
 *    本文件用 .nx-appear（初始态）+ .nx-in（进场完成），与上面那套类名完全不同，不会互相覆盖
 *  对外 API：
 *    window.NestMotion = { version, refresh(), celebrate(opts), reveal(el) }
 *
 *  设计原则：任何一个库没加载成功都只是「少一种效果」，绝不影响页面功能；
 *  系统开了「减少动态效果」时全部跳过（只在 CSS 层保留静态样式）。
 */
(function () {
  'use strict';

  var VERSION = 'motion-1';
  var root = document.documentElement;
  var A = null; // anime.js

  function lib(name) {
    try { return window[name]; } catch (e) { return null; }
  }
  function reduced() {
    try { return window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) { return false; }
  }
  function finePointer() {
    try { return window.matchMedia('(hover: hover) and (pointer: fine)').matches; } catch (e) { return false; }
  }
  function arr(list) { return Array.prototype.slice.call(list || []); }
  function each(list, fn) { for (var i = 0; i < list.length; i++) fn(list[i], i); }
  function visible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var r = el.getBoundingClientRect();
    return r.width > 2 && r.height > 2;
  }

  /* ── anime.js 的薄封装：库不在 / 参数不对，都只当这次动画没发生 ── */
  function anim(targets, params) {
    if (!A || typeof A.animate !== 'function') return null;
    try { return A.animate(targets, params); } catch (e) { return null; }
  }
  function stagger(ms) {
    if (A && typeof A.stagger === 'function') return A.stagger(ms);
    return ms;
  }

  /* ── 1. 滚入视线时揭示（IntersectionObserver + anime.js） ───────────── */
  var revealIO = null;

  function ensureRevealIO() {
    if (revealIO || !('IntersectionObserver' in window)) return revealIO;
    revealIO = new IntersectionObserver(function (entries) {
      each(entries, function (en) {
        if (!en.isIntersecting) return;
        revealIO.unobserve(en.target);
        revealNow(en.target);
      });
    }, { rootMargin: '0px 0px -6% 0px', threshold: 0.05 });
    return revealIO;
  }
  function revealNow(el) {
    var delay = el.__nxDelay || 0;
    var done = false;
    function finish() {
      if (done) return;
      done = true;
      el.classList.add('nx-in');
      el.style.removeProperty('opacity');
      el.style.removeProperty('transform');
    }
    anim(el, {
      opacity: [0, 1],
      translateY: [16, 0],
      duration: 520,
      delay: delay,
      ease: 'outCubic',
      onComplete: finish
    });
    window.setTimeout(finish, 520 + delay + 300);
  }

  function setupReveal(el) {
    if (el.__nxRevealDone) return;
    el.__nxRevealDone = 1;
    if (reduced()) { el.classList.add('nx-in'); return; }
    root.classList.add('nx-motion');
    el.classList.add('nx-appear');

    // 同一个容器里连着好几个卡片时自动错峰，看起来是「排队进场」而不是「一起弹出来」
    if (!el.__nxDelay) {
      var parent = el.parentElement;
      var idx = 0;
      if (parent && parent.children) {
        var already = arr(parent.children).filter(function (c) { return c.__nxRevealDone && c !== el; });
        idx = already.length;
      }
      el.__nxDelay = Math.min(idx * 45, 270);
    }
    var io = ensureRevealIO();
    if (io) io.observe(el);
    else revealNow(el);
  }

  /* ── 2. 卡片 3D 倾斜（vanilla-tilt） ─────────────────────────────── */
  function setupTilt(el) {
    if (el.__nxTiltDone) return;
    el.__nxTiltDone = 1;
    var VT = window.VanillaTilt;
    if (!VT || reduced() || !finePointer()) return;
    try {
      VT.init(el, {
        max: 6,
        speed: 900,
        scale: 1.012,
        glare: false,
        gyroscope: false,
        'full-page-listening': false,
        reverse: false
      });
      el.classList.add('nx-tilt');
    } catch (e) { /* 忽略 */ }
  }

  /* ── 3. 鼠标跟随光斑 + 磁性微移 ─────────────────────────────────── */
  function bindSpotlight(el) {
    if (el.__nxSpotDone) return;
    el.__nxSpotDone = 1;
    if (reduced() || !finePointer()) return;
    // daisyUI 的 .btn-* 是对等物：整站重做后按钮换成 .btn，光斑与磁性必须跟着走，
    // 否则「第 18 轮把按钮换成 daisyUI」会静默丢掉第 10 轮做的灵动层。
    var magnetic = el.__nxMagnetic || el.classList.contains('nx-btn-primary') ||
      el.classList.contains('nx-btn-danger') || el.classList.contains('nx-icon-btn') ||
      el.classList.contains('btn-primary') || el.classList.contains('btn-error') ||
      el.classList.contains('btn-circle');
    var raf = 0, lastEvent = null;

    function paint() {
      raf = 0;
      if (!lastEvent) return;
      var e = lastEvent;
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) return;
      var x = ((e.clientX - r.left) / r.width) * 100;
      var y = ((e.clientY - r.top) / r.height) * 100;
      el.style.setProperty('--nx-mx', x.toFixed(2) + '%');
      el.style.setProperty('--nx-my', y.toFixed(2) + '%');
      if (!magnetic) return;
      var max = 3.4;
      var dx = ((e.clientX - (r.left + r.width / 2)) / r.width) * max;
      var dy = ((e.clientY - (r.top + r.height / 2)) / r.height) * max;
      var scale = el.__nxPressed ? 0.975 : 1;
      el.style.transform = 'translate3d(' + dx.toFixed(2) + 'px,' + dy.toFixed(2) + 'px,0) scale(' + scale + ')';
    }

    el.addEventListener('pointerenter', function () { el.classList.add('nx-spot-on'); }, { passive: true });
    el.addEventListener('pointermove', function (e) {
      lastEvent = e;
      if (!raf) raf = window.requestAnimationFrame(paint);
    }, { passive: true });
    el.addEventListener('pointerdown', function () { el.__nxPressed = 1; if (lastEvent) paint(); }, { passive: true });
    el.addEventListener('pointerup', function () { el.__nxPressed = 0; }, { passive: true });
    el.addEventListener('pointerleave', function () {
      el.classList.remove('nx-spot-on');
      el.__nxPressed = 0;
      lastEvent = null;
      if (!magnetic) return;
      var done = false;
      function reset() { if (done) return; done = true; el.style.transform = ''; }
      anim(el, { translateX: 0, translateY: 0, duration: 620, ease: 'outElastic(1, .5)', onComplete: reset });
      window.setTimeout(reset, 900);
    }, { passive: true });
  }

  /* ── 4. 点击回弹（chip / badge / tab） ──────────────────────────── */
  function bindPop(el) {
    if (el.__nxPopDone) return;
    el.__nxPopDone = 1;
    if (reduced()) return;
    el.addEventListener('click', function () {
      var done = false;
      // anime 会把 scale 写进内联 transform，结束后必须清掉，否则 :hover / :active 的 CSS 变换被内联样式永久压住
      function finish() {
        if (done) return;
        done = true;
        el.style.removeProperty('scale');
        el.style.removeProperty('transform');
      }
      anim(el, { scale: [0.93, 1], duration: 320, ease: 'outBack(2.2)', onComplete: finish });
      window.setTimeout(finish, 620);
    }, { passive: true });
  }

  /* ── 5. 标签栏滑动指示条（自己算位置，CSS 负责过渡） ─────────────── */
  // 选择器同时覆盖两种标记：学工各页用 .nx-tab，宿舍控制台只有 .tab-btn
  var TAB_SEL = '.nx-tab, .tab-btn';
  // 激活态判定：先认显式标记，再退到 Tailwind 的 bg-primary ——
  // 必须用 classList.contains（整词），不能用 [class*=bg-primary]：
  // 后者会命中未激活标签上的 hover:bg-primary/5，于是指示条永远停在第一颗上。
  function activeTab(p) {
    var cands = arr(p.children).filter(function (c) {
      return c.classList && (c.classList.contains('nx-tab') || c.classList.contains('tab-btn'));
    });
    var i, t;
    for (i = 0; i < cands.length; i++) {
      t = cands[i];
      if (t.classList.contains('is-active') || t.classList.contains('active') || t.classList.contains('tab-active') || t.getAttribute('aria-selected') === 'true') return t;
    }
    for (i = 0; i < cands.length; i++) {
      if (cands[i].classList.contains('bg-primary')) return cands[i];
    }
    return null;
  }
  var tabGroups = [];
  function setupTabs() {
    var tabs = arr(document.querySelectorAll(TAB_SEL));
    var parents = [];
    each(tabs, function (t) {
      var p = t.parentElement;
      if (!p || parents.indexOf(p) >= 0) return;
      parents.push(p);
    });
    each(parents, function (p) {
      if (p.__nxTabsDone) return;
      var own = arr(p.children).filter(function (c) {
        return c.classList && (c.classList.contains('nx-tab') || c.classList.contains('tab-btn'));
      });
      if (own.length < 2 || p.__nxTabsSkip) return;
      p.__nxTabsDone = 1;
      if (getComputedStyle(p).position === 'static') p.style.position = 'relative';
      var ind = document.createElement('i');
      ind.className = 'nx-tab-ind';
      ind.setAttribute('aria-hidden', 'true');
      p.appendChild(ind);
      var group = { parent: p, ind: ind, place: null };
      group.place = function () {
        var act = activeTab(p);
        if (!act || !visible(act)) { ind.style.opacity = '0'; return; }
        var pr = p.getBoundingClientRect();
        var ar = act.getBoundingClientRect();
        ind.style.opacity = '1';
        ind.style.width = Math.max(ar.width * 0.62, 22) + 'px';
        ind.style.transform = 'translateX(' + ((ar.left - pr.left) + (ar.width - Math.max(ar.width * 0.62, 22)) / 2).toFixed(1) + 'px)';
        ind.style.top = (ar.bottom - pr.top - 5).toFixed(1) + 'px';
      };
      tabGroups.push(group);
      group.place();
      if ('MutationObserver' in window) {
        new MutationObserver(group.place).observe(p, { attributes: true, subtree: true, attributeFilter: ['class', 'aria-selected'] });
      }
    });
  }

  /* ── 6. 彩带（canvas-confetti）：成功提示 / data-nx-celebrate / API ── */
  function celebrate(opts) {
    var C = window.confetti;
    if (!C || reduced()) return;
    opts = opts || {};
    var base = {
      particleCount: opts.count || 60,
      spread: opts.spread || 66,
      startVelocity: 32,
      gravity: 0.86,
      scalar: 0.88,
      ticks: 140,
      disableForReducedMotion: true,
      colors: ['#3e63dd', '#9eb1ff', '#5b5bd6', '#30a46c', '#f5d90a'],
      origin: opts.origin || { x: 0.5, y: 0.62 }
    };
    try { C(base); } catch (e) { /* 忽略 */ }
    if (opts.double) {
      window.setTimeout(function () {
        try { C({ particleCount: 34, angle: 60, spread: 58, origin: { x: 0, y: 0.7 }, disableForReducedMotion: true, colors: base.colors }); } catch (e) {}
        try { C({ particleCount: 34, angle: 120, spread: 58, origin: { x: 1, y: 0.7 }, disableForReducedMotion: true, colors: base.colors }); } catch (e) {}
      }, 160);
    }
  }
  function looksLikeSuccess(node) {
    if (!node || node.nodeType !== 1) return false;
    // 用 getAttribute('class') 而不是 node.className：SVG 元素的 className 是对象不是字符串，
    // 而且写 typeof ... === 'string' 会被 check-ui-css.mjs 当成一个叫 string 的类名。
    var c = (node.getAttribute && node.getAttribute('class')) || '';
    if (/nx-toast/.test(c) && /(ok|success|okay)/i.test(c)) return true;
    var kind = node.getAttribute && node.getAttribute('data-kind');
    if (/^(ok|success)$/i.test(kind || '')) return true;
    var text = (node.textContent || '');
    return /nx-toast/.test(c) && /(成功|已保存|已发布|已提交|Saved|Published|Success)/.test(text);
  }
  function bindCelebrate(el) {
    if (el.__nxCelebrateDone) return;
    el.__nxCelebrateDone = 1;
    el.addEventListener('click', function () { celebrate({ double: true }); }, { passive: true });
  }

  /* ── 7. 扫描入口：新内容（页面多为 fetch 后 innerHTML）也能自动生效 ── */
  var AUTO_REVEAL = '.nx-card, .nx-tile, .nx-stat, .nx-empty';
  var AUTO_TILT = '.nx-tile, [data-nx-tilt]';
  var AUTO_SPOT = '.nx-btn, .nx-icon-btn, .nx-chip, .nx-tab, .tab-btn, [data-nx-spot], .btn';
  var AUTO_POP = '.nx-chip, .nx-badge, .nx-tab, .tab-btn';

  function scan(scope) {
    scope = scope || document;
    if (scope.nodeType === 1 && scope.matches && scope.matches(AUTO_SPOT)) bindSpotlight(scope);
    if (scope.nodeType === 1 && scope.matches && scope.matches(AUTO_POP)) bindPop(scope);
    if (scope.nodeType === 1 && scope.matches && scope.matches('[data-nx-celebrate]')) bindCelebrate(scope);
    if (scope.nodeType === 1 && scope.matches && scope.matches(AUTO_TILT)) setupTilt(scope);

    each(scope.querySelectorAll(AUTO_SPOT), bindSpotlight);
    each(scope.querySelectorAll(AUTO_POP), bindPop);
    each(scope.querySelectorAll('[data-nx-celebrate]'), bindCelebrate);
    each(scope.querySelectorAll(AUTO_REVEAL), setupReveal);
    each(scope.querySelectorAll(AUTO_TILT), setupTilt);
    each(scope.querySelectorAll('[data-nx-stagger]'), function (box) {
      each(arr(box.children), function (kid, i) {
        kid.__nxDelay = Math.min(i * 55, 330);
        setupReveal(kid);
      });
    });
    setupTabs();
  }

  function refresh() {
    scan(document);
    each(tabGroups, function (g) { g.place(); });
  }

  /* ── 启动 ─────────────────────────────────────────────────────── */
  function boot() {
    A = lib('anime');
    if (!reduced()) root.classList.add('nx-motion');
    scan(document);

    // 异步渲染的内容：监听 DOM 变化，节流后重新扫描
    if ('MutationObserver' in window) {
      var pend = false;
      var mo = new MutationObserver(function (records) {
        each(records, function (rec) {
          each(arr(rec.addedNodes), function (n) {
            if (n.nodeType === 1 && looksLikeSuccess(n)) celebrate({ count: 50, double: true });
            if (n.nodeType === 1 && n.querySelector && n.querySelector('.nx-toast')) {
              each(arr(n.querySelectorAll('.nx-toast')), function (t) { if (looksLikeSuccess(t)) celebrate({ count: 50, double: true }); });
            }
          });
        });
        if (pend) return;
        pend = true;
        window.setTimeout(function () { pend = false; scan(document); }, 140);
      });
      mo.observe(document.body || root, { childList: true, subtree: true });
    }

    var rt = 0;
    window.addEventListener('resize', function () {
      if (rt) window.clearTimeout(rt);
      rt = window.setTimeout(function () { each(tabGroups, function (g) { g.place(); }); }, 160);
    }, { passive: true });
    window.addEventListener('load', function () { each(tabGroups, function (g) { g.place(); }); });

    // 暴露给页面：发布/交卷之类的地方可以手动放一把
    window.NestMotion = {
      version: VERSION,
      refresh: refresh,
      celebrate: celebrate,
      reveal: function (el) { setupReveal(el); }
    };
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();

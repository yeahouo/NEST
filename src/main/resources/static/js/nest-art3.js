/*! nest-art3.js — 学工平台 艺术层第三级（第二十轮：灵动有创意的现成配方）
 *
 *  出处（全部 vendored 到 /libs/，离线校内网可用、零 CDN，许可证见 libs/ART-LIBS.md）：
 *    splitting              v1.1.0  MIT  /libs/splitting/splitting.min.js        标题拆成 .word/.char
 *    embla-carousel         v8.6.0  MIT  /libs/embla/embla-carousel.umd.js       横向卡片滑动
 *    @formkit/auto-animate 0.10.0  MIT  /libs/auto-animate/auto-animate.min.js   列表增删自动过渡（ESM，动态 import）
 *
 *  按需生效 —— 只认属性，不认页面（也就不会把效果塞给不需要的页）：
 *    data-nx-split        标题拆字 + 逐字入场（IntersectionObserver 触发，只动 transform/opacity）
 *    data-nx-carousel     横向卡片行（拖拽 + 左右按钮 + 键盘；不自动播放）
 *    data-nx-autoanimate  容器内列表增删的过渡
 *
 *  三个库都是**惰性加载**：页面上没有对应属性时，一个字节都不会去下载。
 *
 *  红线：
 *    · prefers-reduced-motion: reduce 或低功耗设备（hardwareConcurrency ≤ 4 / deviceMemory ≤ 4）
 *      ⇒ 整层不启用，并给 <html> 加 .nx-art3-off（CSS 侧据此把「入场初始态」全部关掉，
 *        元素停在作者写好的终态上，绝不会出现空白标题）。
 *    · 任何库加载失败、任何一次初始化抛错，都只是「少一种效果」，绝不影响页面功能。
 *    · 拆字后 2.5s 仍未进场的标题会被强制显示（兜底，防止观察者没触发就永久隐身）。
 *
 *  对外 API：window.NestArt3 = { version, refresh(), off }
 */
(function () {
  'use strict';

  var VERSION = 'art3-1';
  var root = document.documentElement;
  var libs = {}; // name -> 'loading' | 'ready' | false
  var done = false;

  function reduced() {
    try { return window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) { return false; }
  }

  function lowPower() {
    var cores = navigator.hardwareConcurrency || 8;
    var mem = navigator.deviceMemory || 8;
    return cores <= 4 || mem <= 4;
  }

  function disabled() {
    return reduced() || lowPower() || root.classList.contains('nx-art3-plain');
  }

  /* 惰性加载一个 UMD 库；同一页面重复调用只会加载一次。 */
  function need(name, url, check) {
    if (check()) { libs[name] = 'ready'; return Promise.resolve(); }
    if (libs[name] === 'loading') {
      return new Promise(function (res) {
        var t = setInterval(function () {
          if (check()) { clearInterval(t); res(); }
        }, 40);
        setTimeout(function () { clearInterval(t); res(); }, 2500);
      });
    }
    libs[name] = 'loading';
    return new Promise(function (res) {
      var s = document.createElement('script');
      s.src = url;
      s.defer = true;
      s.onload = function () { libs[name] = check() ? 'ready' : false; res(); };
      s.onerror = function () { libs[name] = false; res(); };
      document.head.appendChild(s);
      setTimeout(function () { if (libs[name] === 'loading') { libs[name] = false; res(); } }, 2500);
    });
  }

  /* ── data-nx-split：标题拆字 + 逐字入场 ─────────────────────────────── */
  function mountSplit() {
    var targets = [].slice.call(document.querySelectorAll('[data-nx-split]'));
    if (!targets.length) return;
    need('splitting', '/libs/splitting/splitting.min.js', function () { return !!window.Splitting; }).then(function () {
      if (!window.Splitting) return;
      var io = null;
      if (window.IntersectionObserver) {
        io = new IntersectionObserver(function (entries) {
          entries.forEach(function (en) {
            if (en.isIntersecting) { reveal(en.target); io.unobserve(en.target); }
          });
        }, { threshold: .15 });
      }
      targets.forEach(function (el) {
        if (el.getAttribute('data-nx-split-done')) return;
        el.setAttribute('data-nx-split-done', '1');
        try {
          window.Splitting({ target: el, by: 'chars' });
        } catch (e) { return; }
        if (!el.querySelector('.char')) return;
        el.classList.add('nx-split-line');
        // 逐字延迟：按字符顺序写进 CSS 变量，CSS 只做 calc(var(--nx-split-i) * 26ms)
        var i = 0;
        [].forEach.call(el.querySelectorAll('.char'), function (ch) {
          ch.style.setProperty('--nx-split-i', String(i++));
        });
        if (io) { io.observe(el); } else { reveal(el); }
        // 兜底：2.5s 后无论如何都显示（观察者没触发也不能让标题消失）
        setTimeout(function () { reveal(el); }, 2500);
      });
    });
  }

  function reveal(el) {
    if (!el || el.classList.contains('nx-split-in')) return;
    el.classList.add('nx-split-in');
    [].forEach.call(el.querySelectorAll('.char'), function (ch) { ch.style.removeProperty('--nx-split-i'); });
  }

  /* ── data-nx-carousel：横向卡片行 ──────────────────────────────────── */
  function mountCarousel() {
    var hosts = [].slice.call(document.querySelectorAll('[data-nx-carousel]'));
    if (!hosts.length) return;
    need('embla', '/libs/embla/embla-carousel.umd.js', function () { return !!window.EmblaCarousel; }).then(function () {
      hosts.forEach(function (host) {
        if (host.getAttribute('data-nx-carousel-done')) return;
        host.setAttribute('data-nx-carousel-done', '1');

        // 把原有子元素搬进 track，外面再套一层 viewport —— 页面本身不用改结构
        var track = document.createElement('div');
        track.className = 'nx-carousel-track';
        while (host.firstElementChild) track.appendChild(host.firstElementChild);
        var viewport = document.createElement('div');
        viewport.className = 'nx-carousel-viewport';
        viewport.appendChild(track);
        host.classList.add('nx-carousel');
        host.appendChild(viewport);

        if (!window.EmblaCarousel) { viewport.setAttribute('data-nx-carousel-fallback', '1'); return; }

        var api;
        try {
          api = window.EmblaCarousel(viewport, { align: 'start', dragFree: true, containScroll: 'trimSnaps', loop: false });
        } catch (e) { viewport.setAttribute('data-nx-carousel-fallback', '1'); return; }

        var prev = navBtn('prev', '上一组');
        var next = navBtn('next', '下一组');
        host.appendChild(prev);
        host.appendChild(next);
        prev.addEventListener('click', function () { api.scrollPrev(); });
        next.addEventListener('click', function () { api.scrollNext(); });

        function sync() {
          var canPrev = api.canScrollPrev(), canNext = api.canScrollNext();
          prev.disabled = !canPrev;
          next.disabled = !canNext;
        }
        api.on('select', sync);
        api.on('init', sync);
        api.on('reInit', sync);
        sync();
      });
    });
  }

  function navBtn(dir, label) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'nx-carousel-nav nx-carousel-nav--' + dir;
    b.setAttribute('aria-label', label);
    b.innerHTML = '<span class="material-symbols-outlined text-lg">' +
      (dir === 'prev' ? 'chevron_left' : 'chevron_right') + '</span>';
    return b;
  }

  /* ── data-nx-autoanimate：列表增删过渡（ESM，动态 import）──────────── */
  function mountAutoAnimate() {
    var hosts = [].slice.call(document.querySelectorAll('[data-nx-autoanimate]'));
    if (!hosts.length) return;
    import('/libs/auto-animate/auto-animate.min.js').then(function (mod) {
      var fn = mod && (mod.autoAnimate || mod.default);
      if (typeof fn !== 'function') return;
      hosts.forEach(function (el) {
        if (el.getAttribute('data-nx-autoanimate-done')) return;
        el.setAttribute('data-nx-autoanimate-done', '1');
        try { fn(el, { duration: 220, easing: 'ease-out' }); } catch (e) { /* 少一种效果而已 */ }
      });
    }).catch(function () { /* 内核不支持动态 import 时静默跳过 */ });
  }

  /* ── 滚动进度条：给 .nx-scroll-progress 写 --nx-progress ───────────── */
  function mountProgress() {
    var bar = document.querySelector('.nx-scroll-progress');
    if (!bar) return;
    var raf = 0;
    function upd() {
      raf = 0;
      var h = document.documentElement.scrollHeight - window.innerHeight;
      var p = h > 0 ? Math.min(1, Math.max(0, window.scrollY / h)) : 0;
      bar.style.setProperty('--nx-progress', p.toFixed(4));
    }
    window.addEventListener('scroll', function () { if (!raf) raf = window.requestAnimationFrame(upd); }, { passive: true });
    window.addEventListener('resize', upd, { passive: true });
    upd();
  }

  function refresh() {
    if (disabled()) { root.classList.add('nx-art3-off'); return; }
    root.classList.remove('nx-art3-off');
    mountSplit();
    mountCarousel();
    mountAutoAnimate();
    mountProgress();
  }

  function boot() {
    if (done) return;
    done = true;
    if (disabled()) {
      root.classList.add('nx-art3-off');
      // 减少动效时不拆字、不挂 carousel 的按钮，但进度条这种纯静态读数仍然可用
      mountProgress();
      return;
    }
    if (window.requestIdleCallback) window.requestIdleCallback(refresh, { timeout: 1200 });
    else setTimeout(refresh, 180);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();

  // 有的页面是 SPA 式局部刷新，给一个手动重扫的入口（也方便探针驱动）
  window.NestArt3 = { version: VERSION, refresh: refresh, off: function () { root.classList.add('nx-art3-off'); } };
})();

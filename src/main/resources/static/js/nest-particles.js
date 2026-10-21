/*!
 * nest-particles.js —— 校园网离线可用的背景粒子层
 *
 * 出处（选型取证见 docs/课堂管理系统-功能补全说明.md §19）：
 *   tsParticles —— https://github.com/tsparticles/tsparticles （MIT，8,994 stars，
 *   2026-10-10 仍有推送；是 30k stars 的 particles.js 的现代续作）。
 *   本文件加载的是官方 npm 包 @tsparticles/slim@4.4.0 里那个自带引擎的
 *   IIFE bundle（158,878 字节，全局 tsParticles），已 vendored 到
 *   /libs/tsparticles/ —— 零 CDN，断网可用。
 *
 * 设计约束：
 *   1. 按页 opt-in：<body data-nx-particles="on|dense|soft">（写在 <html> 上也认）。
 *      没写这个属性的页面一行都不跑，也不加载那 158 KB。
 *   2. 尊重 prefers-reduced-motion：用户系统里关了动效就完全不建 canvas。
 *   3. 省电：pauseOnBlur + pauseOnOutsideViewport，切走标签页/滚出视口时暂停。
 *   4. 层级沿用站点既有约定：登录页的装饰层是
 *      `<div class="nx-aurora nx-grid fixed inset-0 -z-10">`，内容容器是 relative z-10。
 *      所以粒子层也用 position:fixed; z-index:-10; pointer-events:none ——
 *      它在所有正文之下、在所有页面底色之上（body 的背景传播到根 canvas，
 *      负 z 子元素画在根背景之上），不需要动任何一页的布局。
 *   5. 不用 Tailwind 类名（工具类是编译期从 HTML 扫出来的，JS 里拼的类名不会生成 CSS），
 *      只在 JS 里写内联样式。
 */
(function () {
  'use strict';

  var VERSION = 'particles-5';   /* particles-4 = 2026-10-16 用户澄清「我想让那个几何形状的那些
                                    东西（＝会发出抓取线的粒子点本身）更明显」：点半径与点不透明度
                                    各提一档，点色换成对比更强的蓝（见 dotColor()）。
                                    particles-5 = 紧接着「再加大力度，还有这些浮游的点我感觉
                                    有点少，要多一些」：点再提一档、数量 +55%~58%（见 PRESETS）。 */
  var LIB_URL = '/libs/tsparticles/tsparticles.slim.bundle.min.js';
  var HOST_ID = 'nx-particles';
  var body = document.body;
  if (!body) { return; }

  var spec = body.getAttribute('data-nx-particles');
  if (spec === null) { spec = document.documentElement.getAttribute('data-nx-particles'); }
  if (spec === null) { return; }                      /* 未 opt-in：直接返回 */

  var preset = String(spec || 'on').trim().toLowerCase() || 'on';
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  window.NestParticles = {
    version: VERSION,
    preset: preset,
    hostId: HOST_ID,
    lib: LIB_URL,
    state: reduced ? 'skipped-reduced-motion' : 'pending',
    container: null
  };

  if (reduced) { return; }                            /* 关动效 = 不加载、不建 canvas */

  /* ---- 三档预设：粒子数 / 速度 / 连线距离 / 连线不透明度 / 线宽 / 指针抓取线不透明度 /
            点半径 / 点不透明度 ----
     线宽与抓取线不透明度是 2026-10-16 按用户反馈调过的（连着两轮）：
       第一轮「粘在鼠标指针上的线太浅、太细，看不见」⇒ 抓取线不透明度 0.26 → 0.70/0.75，
         线宽 1 → 1.4/1.5，普通连线 0.16/0.20/0.10 → 0.22/0.26/0.14；
       第二轮「还是不够粗，颜色再明显一点」⇒ 线宽 1.4/1.5 → 2.0/2.2（soft 1.7），
         抓取线不透明度 → 0.92/0.95（soft 0.60），普通连线 → 0.38/0.42（soft 0.26），
         并且连线与抓取线都换成比品牌色更深的蓝（见 linkColor() / grabColor()）。
       第三轮（用户澄清他说的「背景那些几何条框」其实是**会发出抓取线的粒子点**，不是页面底纹）
         ⇒ 点半径 0.6~2.6 → 1.8~5.0、点不透明度 0.12~0.42 → 0.55~1.00（soft 0.40~0.75），
         点色从品牌色换成更深的蓝（浅色 #2f4fd6 / 暗色 #dbe3ff，见 dotColor()），
         闪烁速度 0.35 → 0.25（点更实、少一点忽明忽暗）。
       第四轮「再加大力度，还有这些浮游的点我感觉有点少，要多一些」⇒ 点半径再提一档
         （on 2.4~6.0 / dense 2.6~6.4 / soft 1.8~4.4）、点不透明度下限再抬
         （0.70 / 0.72 / 0.55）、数量 60/92/34 → 95/145/52。数量提上去后连线对数会按
         「粒子数 × 邻域数」增长（≈2 倍），所以 dense 的连线距离 152 → 150 收回来一点，
         避免变成一团糊住的网（点数按用户要求加，线条密度基本维持原观感）。
     为什么不能只加粗抓取线：**抓取线没有独立的线宽参数** —— 见下面 options() 的注释，
     它用的是 `particles.links.width`，所以粗细只能连普通连线一起调。 */
  var PRESETS = {
    on: {
      count: 95, speed: 0.55, link: 140, linkOpacity: 0.38, width: 2.0, grabOpacity: 0.92,
      size: [2.4, 6.0], dot: [0.70, 1.00]
    },
    dense: {
      count: 145, speed: 0.72, link: 150, linkOpacity: 0.42, width: 2.2, grabOpacity: 0.95,
      size: [2.6, 6.4], dot: [0.72, 1.00]
    },
    soft: {
      count: 52, speed: 0.38, link: 124, linkOpacity: 0.26, width: 1.7, grabOpacity: 0.60,
      size: [1.8, 4.4], dot: [0.55, 0.90]
    }
  };
  var cfg = PRESETS[preset] || PRESETS.on;

  /* 小屏减量：手机上一半左右，别拿电池烧背景 */
  function countForViewport(base) {
    var w = window.innerWidth || 1440;
    if (w < 640) { return Math.round(base * 0.5); }
    if (w < 1024) { return Math.round(base * 0.75); }
    return base;
  }

  function isDark() { return document.documentElement.classList.contains('dark'); }
  function brand() { return isDark() ? '#9eb1ff' : '#3e63dd'; }
  /* 粒子点：2026-10-16 第三轮按用户反馈「几何形状本身要更明显」从 brand() 拆出来单独给色。
     浅色底上是更深的蓝（品牌色 #3e63dd 偏亮，小点上对比不够），暗色底上是更亮的浅蓝。 */
  function dotColor() { return isDark() ? '#dbe3ff' : '#2f4fd6'; }
  /* 连线与抓取线都用比品牌色更深的蓝：品牌色本身偏亮，叠在白底上对比不足，
     用户连着两轮反馈「太浅」；粒子点仍用品牌色，线的加深不影响整体色调。 */
  function linkColor() { return isDark() ? '#aebfff' : '#2b46c8'; }
  function grabColor() { return isDark() ? '#e6ebff' : '#12309e'; }

  function options() {
    return {
      fpsLimit: 60,
      detectRetina: true,
      pauseOnBlur: true,
      pauseOnOutsideViewport: true,
      particles: {
        number: { value: countForViewport(cfg.count) },
        color: { value: dotColor() },
        shape: { type: 'circle' },
        opacity: {
          value: { min: cfg.dot[0], max: cfg.dot[1] },
          animation: { enable: true, speed: 0.25, sync: false }
        },
        size: { value: { min: cfg.size[0], max: cfg.size[1] } },
        links: {
          enable: true,
          distance: cfg.link,
          color: linkColor(),
          opacity: cfg.linkOpacity,
          /* 这里同时管普通连线与「指针抓取线」的粗细：vendo 的 bundle 里画抓取线的
             Ms() 用的是 e.retina.linksWidth，抓取模式本身只读 distance 与
             links.{opacity,color,blink,consent} —— 没有独立线宽参数（2026-10-16 取证）。 */
          width: cfg.width
        },
        move: {
          enable: true,
          speed: cfg.speed,
          direction: 'none',
          random: false,
          straight: false,
          outModes: { default: 'out' }
        }
      },
      interactivity: {
        detectsOn: 'window',
        events: {
          onHover: { enable: true, mode: 'grab' },
          onClick: { enable: false },
          resize: { enable: true }
        },
        modes: {
          grab: { distance: 200, links: { opacity: cfg.grabOpacity, color: grabColor() } }
        }
      }
    };
  }

  function host() {
    var el = document.getElementById(HOST_ID);
    if (el) { return el; }
    el = document.createElement('div');
    el.id = HOST_ID;
    el.setAttribute('aria-hidden', 'true');
    /* 层级约定见文件头第 4 条 */
    el.style.cssText = 'position:fixed;inset:0;z-index:-10;pointer-events:none;overflow:hidden';
    body.appendChild(el);
    return el;
  }

  function loadLib() {
    if (window.tsParticles) { return Promise.resolve(window.tsParticles); }
    return new Promise(function (resolve, reject) {
      var s = document.createElement('script');
      s.src = LIB_URL;
      s.async = true;
      s.onload = function () {
        if (window.tsParticles) { resolve(window.tsParticles); }
        else { reject(new Error('tsParticles global missing')); }
      };
      s.onerror = function () { reject(new Error('lib load failed')); };
      document.head.appendChild(s);
    });
  }

  /* bundle 把插件（color/opacity/size/move 这些更新器）放在文件末尾的异步 IIFE 里注册。
     注册没跑完就 load()，容器会缺 color/opacity ⇒ 渲染器算不出 fill 直接跳过不画
     （真机上「canvas 在跑、state=running，但截图差分 differ=0」，2026-10 定位）。
     所以先 await 上游导出的 loadSlim()（就是那个注册函数），再留一小段，最后才建容器。 */
  function waitPlugins(engine) {
    var p = null;
    if (typeof window.loadSlim === 'function') { p = window.loadSlim(engine); }
    else if (engine && typeof engine.loadSlim === 'function') { p = engine.loadSlim(engine); }
    return Promise.resolve(p).catch(function () { /* 没有 loadSlim 就退到下面的延时兜底 */ })
      .then(function () { return new Promise(function (r) { window.setTimeout(r, 320); }); });
  }

  var booted = false;
  function boot() {
    if (booted) { return; }
    booted = true;
    var el = host();
    loadLib().then(function (engine) {
      return waitPlugins(engine).then(function () {
        return engine.load({ id: HOST_ID, element: el, options: options() });
      });
    }).then(function (container) {
      window.NestParticles.container = container;
      window.NestParticles.state = 'running';
    }).catch(function () {
      /* 离线首访等情况下加载失败：安静降级，页面照常（探针查 state 即可） */
      window.NestParticles.state = 'lib-failed';
    });
  }

  /* 深浅色切换时换粒子颜色（引擎会重建容器） */
  function reload() {
    if (!window.tsParticles || !window.NestParticles.container) { return; }
    try {
      window.tsParticles.load({ id: HOST_ID, element: host(), options: options() }).then(function (c) {
        window.NestParticles.container = c;
      });
    } catch (err) { /* 换色失败不影响页面 */ }
  }
  if (window.MutationObserver) {
    new MutationObserver(reload).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  }

  /* 让首屏先画完（共享动效层也在同一帧里排动画），空闲了再起粒子 */
  function schedule() {
    if (window.requestIdleCallback) { window.requestIdleCallback(boot, { timeout: 1200 }); }
    else { window.setTimeout(boot, 240); }
  }
  if (document.readyState === 'complete') { schedule(); }
  else { window.addEventListener('load', schedule, { once: true }); }

  /* 供调试/探针用 */
  window.NestParticles.destroy = function () {
    if (window.tsParticles && window.NestParticles.container) {
      window.NestParticles.container.destroy();
      window.NestParticles.container = null;
      window.NestParticles.state = 'destroyed';
    }
  };
})();

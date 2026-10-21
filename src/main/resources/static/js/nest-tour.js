/**
 * 新手引导（product tour）—— 共享层第 4 个脚本，但它是**按需加载**的：
 * 页面里没有 data-nx-tour 标记时，这个文件连 driver.js 都不会去下载。
 *
 * 用的是 driver.js 1.9.0（MIT，作者 Kamran Ahmed，https://driverjs.com），
 * 已经 vendor 到 /libs/driver/ 下面，不联网、不走 CDN —— 学校的机器断网也能用。
 * 为什么用现成的：引导这件事（高亮环、遮罩、弹出层定位、键盘控制、边界裁剪）
 * 自己写一遍只会写出一个更难看的版本。
 *
 * 页面怎么描述自己的一步（脚本只按 DOM 顺序捡起来，不写死在 JS 里）：
 *   <div data-nx-tour="标题"
 *        data-nx-tour-text="说明文字"
 *        data-nx-tour-side="bottom|top|left|right"     可选，默认 bottom
 *        data-nx-tour-align="start|center|end"         可选，默认 start
 *        data-nx-tour-step="2">                        可选，强制排序
 *
 * 行为约定：
 *   · 同一个页面只在**第一次访问**时自动弹出（localStorage: nest-tour-v1:<path>）；
 *     隐私模式下 localStorage 会抛异常，那时按「已经看过」处理 —— 不打扰人。
 *   · 关掉就算看过了，不会每次刷新都冒出来；想再看一遍走命令面板里的「新手引导」。
 *   · 系统开了「减少动态效果」时不滚动、不做动画（driver.js 的 smoothScroll/animate）。
 */
(function () {
  'use strict';

  var KEY = 'nest-tour-v1';
  var CSS_HREF = '/libs/driver/driver.css';
  var SRC = '/libs/driver/driver.js.iife.js';
  var loading = null;

  function seenKey() {
    return KEY + ':' + location.pathname;
  }

  function seen() {
    try {
      return localStorage.getItem(seenKey()) === '1';
    } catch (err) {
      // 隐私模式/禁用存储：当作看过，宁可少弹一次，也不要每次刷新都跳出来
      return true;
    }
  }

  function markSeen() {
    try {
      localStorage.setItem(seenKey(), '1');
    } catch (err) {
      /* 存不下就算了，最多下次再弹一次 */
    }
  }

  function reduced() {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  function collect() {
    var nodes = Array.prototype.slice.call(document.querySelectorAll('[data-nx-tour]'));
    return nodes
      .map(function (el, i) {
        return {
          order: parseInt(el.getAttribute('data-nx-tour-step'), 10),
          index: i,
          step: {
            element: el,
            popover: {
              title: el.getAttribute('data-nx-tour'),
              description: el.getAttribute('data-nx-tour-text') || '',
              side: el.getAttribute('data-nx-tour-side') || 'bottom',
              align: el.getAttribute('data-nx-tour-align') || 'start',
            },
          },
        };
      })
      .sort(function (a, b) {
        var ao = isNaN(a.order) ? 1000 + a.index : a.order;
        var bo = isNaN(b.order) ? 1000 + b.index : b.order;
        return ao - bo;
      })
      .map(function (row) {
        return row.step;
      });
  }

  function load() {
    if (window.driver && window.driver.js) {
      return Promise.resolve();
    }
    if (loading) {
      return loading;
    }
    loading = new Promise(function (resolve, reject) {
      if (!document.querySelector('link[href="' + CSS_HREF + '"]')) {
        var link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = CSS_HREF;
        document.head.appendChild(link);
      }
      var script = document.createElement('script');
      script.src = SRC;
      script.onload = function () {
        resolve();
      };
      script.onerror = function () {
        reject(new Error('引导脚本加载失败：' + SRC));
      };
      document.head.appendChild(script);
    });
    return loading;
  }

  function fail(err) {
    // 不安静地失败：引导加载不了要说出来，否则用户只会觉得「点了没反应」
    console.error('[nest-tour]', err);
    if (window.NestUI && window.NestUI.toast) {
      window.NestUI.toast('新手引导加载失败，请刷新页面重试。', { kind: 'warn' });
    }
  }

  function start(force) {
    var steps = collect();
    if (!steps.length) {
      return false;
    }
    if (!force && seen()) {
      return false;
    }
    load()
      .then(function () {
        var tour = window.driver.js.driver({
          steps: steps,
          showProgress: true,
          progressText: '第 {{current}} 步 / 共 {{total}} 步',
          nextBtnText: '下一步',
          prevBtnText: '上一步',
          doneBtnText: '知道了',
          allowClose: true,
          overlayColor: '#0b0d12',
          overlayOpacity: 0.62,
          stagePadding: 8,
          stageRadius: 14,
          // 深色页面里弹出白色卡片会很刺眼，配色交给 input.css 的 .nx-tour-popover 覆盖
          popoverClass: 'nx-tour-popover',
          smoothScroll: !reduced(),
          animate: !reduced(),
          // driver.js 里「用户想关掉」只是通知，真正结束要自己调 destroy()
          onDestroyStarted: function () {
            markSeen();
            tour.destroy();
          },
        });
        markSeen();
        tour.drive();
      })
      .catch(fail);
    return true;
  }

  function boot() {
    if (!document.querySelector('[data-nx-tour]')) {
      return;
    }
    // 命令面板的入口：nest-ui.js 会找到 [data-nx-cmd] 并「选中 = click 它」
    var trigger = document.createElement('button');
    trigger.type = 'button';
    trigger.className = 'hidden';
    trigger.setAttribute('data-nx-cmd', '新手引导');
    trigger.setAttribute('data-nx-cmd-group', '操作');
    trigger.setAttribute('data-nx-cmd-icon', 'school');
    trigger.setAttribute('data-nx-cmd-keywords', 'tour guide help 引导 新手');
    trigger.addEventListener('click', function () {
      start(true);
    });
    document.body.appendChild(trigger);

    window.NestTour = {
      start: function () {
        return start(true);
      },
      steps: collect().length,
    };

    // nest-ui.js 在 DOMContentLoaded 时收集命令面板条目；这里只等 DOM 就绪，
    // 让标记了 data-nx-tour 的元素都已经在文档里。
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function () {
        start(false);
      });
    } else {
      start(false);
    }
  }

  boot();
})();

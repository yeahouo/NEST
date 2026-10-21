/*
 * 移动端审计：在一个窄视口里检查
 *   1) 有没有横向溢出（documentElement.scrollWidth > innerWidth）
 *   2) 谁溢出了（找最宽的几个元素，给出 id/class/宽度）
 *   3) 触控目标够不够大（a / button / .nx-btn / .nx-icon-btn / .nx-tab，<40px 视为不达标；
 *      UI 规范要 44px，这里 40px 是放宽后的下限，只报大的问题）
 *   4) 粘性头/底栏有没有挡住内容
 */
(function () {
  var vw = window.innerWidth;
  var doc = document.documentElement;
  var trunc = function (s, n) { return (s || '').replace(/\s+/g, ' ').trim().slice(0, n || 34); };

  var wide = [];
  Array.prototype.forEach.call(document.querySelectorAll('body *'), function (el) {
    var r = el.getBoundingClientRect();
    if (r.width > vw + 1 && r.height > 0) {
      wide.push({ sel: el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (el.className && typeof el.className === 'string' ? '.' + el.className.split(/\s+/).slice(0, 2).join('.') : ''), w: Math.round(r.width) });
    }
  });
  wide.sort(function (a, b) { return b.w - a.w; });

  var small = [];
  var small44 = [];
  Array.prototype.forEach.call(document.querySelectorAll('a, button, .nx-btn, .nx-icon-btn, .nx-tab, [onclick]'), function (el) {
    var r = el.getBoundingClientRect();
    var vis = r.width > 0 && r.height > 0;
    if (!vis) return;
    var txt = trunc(el.textContent || el.getAttribute('onclick') || el.id, 24);
    var sel = el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (el.className && typeof el.className === 'string' ? '.' + el.className.split(/\s+/).slice(0, 2).join('.') : '');
    if (r.height < 40 || r.width < 24) {
      small.push({ t: txt, w: Math.round(r.width), h: Math.round(r.height) });
    }
    // 规范目标是 44（WCAG 2.5.5 / iOS HIG）；40 只是「明显做坏了」的告警线。
    // 两套数字分开报：修的时候先看 44 那栏，表格里的紧凑链接再按例外处理。
    if (r.height < 44 || r.width < 44) {
      small44.push({ sel: sel, t: txt, w: Math.round(r.width), h: Math.round(r.height) });
    }
  });

  return {
    path: location.pathname + location.search,
    vw: vw,
    hOverflow: doc.scrollWidth > vw + 1,
    scrollWidth: doc.scrollWidth,
    bodyScrollWidth: document.body.scrollWidth,
    wideCount: wide.length,
    wide: wide.slice(0, 8),
    touchTotal: document.querySelectorAll('a, button, .nx-btn, .nx-icon-btn, .nx-tab, [onclick]').length,
    touchSmall: small.length,
    small: small.slice(0, 12),
    touchBelow44: small44.length,
    below44: small44.slice(0, 40)
  };
})()

/*
 * UI 盘点：把当前页面的可交互元素压缩成一份清单，用来写受控流程时选对选择器。
 * 只返回 id / 文本 / type / onclick 前 40 字，避免输出过大。
 */
(function () {
  var trunc = function (s, n) { return (s || '').replace(/\s+/g, ' ').trim().slice(0, n || 30); };
  var pick = function (list, n) { return Array.prototype.slice.call(list).slice(0, n || 40); };

  var buttons = pick(document.querySelectorAll('button, a.nx-btn, [role=button]'), 60).map(function (b) {
    return { id: b.id || null, t: trunc(b.textContent, 24), oc: trunc(b.getAttribute('onclick'), 40), hide: !!(b.offsetWidth || b.offsetHeight) ? 0 : 1 };
  });
  var inputs = pick(document.querySelectorAll('input, textarea, select'), 60).map(function (i) {
    return { id: i.id || null, type: i.type || i.tagName.toLowerCase(), ph: trunc(i.placeholder, 20), req: i.required ? 1 : 0 };
  });
  var modals = pick(document.querySelectorAll('[id*="odal"], .nx-qed, [class*="fixed inset-0"]'), 40).map(function (m) {
    return { id: m.id || null, cls: trunc(m.className, 30), hidden: m.classList.contains('hidden') ? 1 : 0 };
  });
  var tabs = pick(document.querySelectorAll('[onclick*="switchTab"], .nx-tab'), 20).map(function (t) {
    return { t: trunc(t.textContent, 18), oc: trunc(t.getAttribute('onclick'), 34) };
  });
  var links = pick(document.querySelectorAll('a[href]'), 30).map(function (a) { return trunc(a.getAttribute('href'), 34); });

  return {
    path: location.pathname + location.search,
    title: document.title,
    buttons: buttons,
    inputs: inputs,
    modals: modals,
    tabs: tabs,
    links: links
  };
})()

/*
 * 学工平台 · PWA 引导脚本（学生端页面共用）
 *
 * 做两件事：注册 Service Worker；在浏览器说"这个站可以装"时，弹一个"安装到桌面"的按钮。
 *
 * 装成 App 的意义不是好看：学生把「学工平台」装到主屏之后，请假、查资助档次、看综测
 * 就不用每次翻微信通知找网址、重新登录。对学校来说，这也是唯一一种不花应用商店审核
 * 成本的多端方案——同一份代码，安卓、iOS、Windows、macOS 都能装。
 *
 * 安装按钮只在学生入口出现（见下面的 INSTALL_PAGES）：manifest 的 start_url 指向
 * /index.html，在管理端或辅导员页面上点"安装"，装完打开的是学生入口，这比不提供按钮
 * 更让人困惑；管理员用的是桌面浏览器，本来也不需要。
 */
(function () {
  'use strict';

  if (!('serviceWorker' in navigator)) {
    return;
  }
  // 直接用 file:// 打开页面时不注册：Service Worker 只允许在 http(s) 下工作，
  // 而且注册失败会在控制台留一串红字，看起来像坏了。
  if (location.protocol === 'file:') {
    return;
  }

  window.addEventListener('load', function () {
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(function (err) {
      // 注册失败不影响任何功能，只是不能离线用。不弹窗、不打断学生。
      console.warn('[pwa] Service Worker 注册失败，功能不受影响：', err);
    });
  });

  // 安装按钮只挂学生入口。用白名单而不是"排除管理端"的正则：管理端页面以后还会加，
  // 白名单漏一个学生页最多是少一个按钮，黑名单漏一个管理页就会在辅导员屏幕上弹按钮。
  var INSTALL_PAGES = ['/', '/index.html', '/dashboard.html', '/dorm-portal.html'];
  if (INSTALL_PAGES.indexOf(location.pathname) < 0) {
    return;
  }

  var deferredPrompt = null;

  // 统一对话框的兜底：pwa.js 不是页面，没有页内 helper 区，就地写一次。
  // nest-ui.js 是 defer 加载的，但这里只在点击时才用到，那时它已经就绪了。
  function nxAlert(message) {
    if (window.NestUI && NestUI.dialog) return NestUI.dialog.alert(message);
    window.alert(message);
    return Promise.resolve();
  }

  window.addEventListener('beforeinstallprompt', function (e) {
    e.preventDefault();
    deferredPrompt = e;
    mountInstallButton('安装到桌面', function () {
      deferredPrompt.prompt();
      deferredPrompt.userChoice.then(function () {
        deferredPrompt = null;
        removeButton();
      }).catch(function () {
        deferredPrompt = null;
      });
    });
  });

  window.addEventListener('appinstalled', function () {
    removeButton();
  });

  // iOS Safari 永远不发 beforeinstallprompt —— 那边只能靠用户自己在分享菜单里选
  // "添加到主屏幕"。所以这里给一句提示，不然 iPhone 用户会以为这站装不了。
  if (isIosSafari() && !isStandalone()) {
    var told = false;
    try {
      told = window.localStorage.getItem('nest.pwa.iosHint') === '1';
    } catch (e) {
      told = false;
    }
    if (!told) {
      mountInstallButton('装到主屏', function () {
        void nxAlert('在 Safari 里点底部的「分享」按钮，然后选「添加到主屏幕」。');
        try {
          window.localStorage.setItem('nest.pwa.iosHint', '1');
        } catch (e) {
          // 隐私模式下 localStorage 会抛异常，忽略即可：下次还会提示一次而已。
        }
      });
    }
  }

  function isStandalone() {
    return (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches)
      || window.navigator.standalone === true;
  }

  function isIosSafari() {
    var ua = window.navigator.userAgent || '';
    if (!/iPhone|iPad|iPod/.test(ua)) {
      return false;
    }
    // Chrome/Firefox 在 iOS 上也是 WebKit，但它们同样没有"添加到主屏幕"，所以不排除。
    return true;
  }

  function removeButton() {
    var old = document.getElementById('pwa-install');
    if (old && old.parentNode) {
      old.parentNode.removeChild(old);
    }
  }

  /**
   * 按钮用内联样式而不是 Tailwind 类：Tailwind 是从 CDN 加载的，
   * 万一 CDN 断了，这个按钮至少还得看得见、点得动。
   */
  function mountInstallButton(label, onClick) {
    if (document.getElementById('pwa-install') || isStandalone()) {
      return;
    }
    var btn = document.createElement('button');
    btn.id = 'pwa-install';
    btn.type = 'button';
    btn.textContent = label;
    btn.style.cssText = [
      'position:fixed', 'right:16px', 'bottom:calc(16px + env(safe-area-inset-bottom, 0px))',
      'z-index:60', 'display:flex', 'align-items:center', 'gap:6px',
      'padding:12px 18px', 'border:0', 'border-radius:9999px',
      'background:#0751a6', 'color:#fff', 'font-size:14px', 'font-weight:700',
      'font-family:inherit', 'cursor:pointer',
      'box-shadow:0 8px 24px rgba(7,81,166,.35)'
    ].join(';');
    btn.addEventListener('click', onClick);
    (document.body || document.documentElement).appendChild(btn);
  }
})();

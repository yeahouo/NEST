/*
 * 学工平台 · Service Worker
 *
 * 目的只有一个：让这个网页能被装到手机主屏、并且断网时不至于白屏。
 * 它不承担任何业务逻辑——请假、资助、综测这些数据永远从服务器拿。
 *
 * 缓存策略（四条，各有理由）：
 *   1. /api/**          一律放行、永不经手。请假审批结果、资助档次这类东西拿缓存糊弄，
 *                       比直接报错还糟：学生会看到一个"看起来对"的旧结果，而且不会怀疑。
 *   2. 页面导航          先网络后缓存。在线时学生永远看到最新结果，断网时回退到上次那份。
 *   3. 自己写的 js/css   先网络后缓存。文件名不带版本号，走"先缓存后网络"的话，
 *                       部署新版本后老用户第一次打开拿到的还是旧脚本 —— 表现就是
 *                       "新功能没生效"，而且刷新一下就正常，最难查。
 *   4. 其余静态资源与 CDN 先缓存后网络，同时后台静默刷新（stale-while-revalidate）。
 *                       图标、字体这些几乎不变，走缓存最快；Tailwind / 字体这些 CDN 资源
 *                       也一起缓存，否则断网时页面是裸的。
 */
'use strict';

// v2：图标字体从 17.8KB 的子集换成了全量字体（原来 132 个图标名里只有 68 个能渲染）。
// v3：自己写的 js/css 改成先网络后缓存（上面第 3 条）。换 CACHE 名是为了让老客户端
//     立刻丢掉 v2 里那份旧脚本 —— 否则这次修复本身也要等两轮刷新才生效。
// v4：平台身份从「安居 · 宿舍分配」改成「学工平台」，预缓存与离线兜底页从宿舍门户换成
//     /index.html（未登录也能看）。同样必须换名：老客户端的 v3 里没有 index.html，
//     不换名则断网兜底还停在宿舍门户，而且那份 v3 缓存永远不会被清掉。
const CACHE = 'nest-shell-v4';
const OFFLINE_URL = '/index.html';

// 只预缓存"装完立刻要用"的东西：落地页 + 学生工作台。
// 管理端与辅导员页面一律不预缓存：装这个 App 的是学生，不该让他的手机替管理员下载工作台。
const SHELL = [
  '/index.html',
  '/dashboard.html',
  '/manifest.webmanifest',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/icon-maskable-512.png',
  '/icons/apple-touch-icon.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE)
      // 逐个 add 并吞掉单个失败：某个图标不存在不该让整个 SW 装不上，
      // 那会变成"改了图标名字，全站离线能力静默消失"。
      .then((cache) => Promise.all(SHELL.map((url) => cache.add(url).catch(() => null))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') {
    return;
  }
  const url = new URL(req.url);

  if (url.origin === self.location.origin && url.pathname.startsWith('/api/')) {
    return;
  }

  // SW 自己的脚本也放行。浏览器检查更新时本来就绕开缓存，但页面自己 fetch('/sw.js')
  // （自检/诊断）走的是这里——不拦的话会拿到缓存里那份旧副本，量出来的版本和真正在跑的
  // 那个不是一回事（本轮就因此把 4992 字节的新版误读成 4473 字节的旧版）。
  if (url.origin === self.location.origin && url.pathname === '/sw.js') {
    return;
  }

  if (req.mode === 'navigate') {
    event.respondWith(
      fetchFresh(req).then((fresh) => fresh || caches.match(req).then((hit) => hit || caches.match(OFFLINE_URL)))
    );
    return;
  }

  // 自己写的 js/css：先网络后缓存。见开头第 3 条 —— 在线时永远是最新的，
  // 断网时回退到上一次成功抓到的那份（所以第一次联网访问过之后，离线仍然可用）。
  if (url.origin === self.location.origin && (url.pathname.startsWith('/js/') || url.pathname.startsWith('/css/'))) {
    event.respondWith(
      fetchFresh(req).then((fresh) => fresh || caches.match(req).then((hit) => hit || Response.error()))
    );
    return;
  }

  event.respondWith(
    caches.match(req).then((hit) => {
      const fromNet = fetchFresh(req).then((fresh) => fresh || hit);
      return hit || fromNet;
    })
  );
});

/**
 * 先网络后缓存里的"网络"那一步。返回 null 表示"没拿到能用的新副本"。
 *
 * 为什么不是裸的 `fetch(req)`：只判 catch 会漏掉一种很常见的失败——
 * 机房代理/网关超时时给的是一张 **504 的 HTML 错误页**。fetch 不抛异常、`res.ok` 却是 false，
 * 那份 HTML 会被当成 JS/CSS 塞给页面（报错信息通常只是"意外的 <"），而且还会被写进缓存固化下来。
 * 所以这里把"非 200"和"抛异常"当成同一件事处理，交给调用方回退到缓存。
 */
function fetchFresh(req) {
  return fetch(req)
    .then((res) => {
      if (!res || !res.ok) {
        return null;
      }
      stash(req, res.clone());
      return res;
    })
    .catch(() => null);
}

/**
 * 写进缓存。两类响应会被丢掉：
 * 非 200 的（缓存一个 404 等于把错误固化下来）；以及 put 本身抛异常的情况
 * ——比如响应带 `Vary: *`，Cache API 拒绝存储且抛错，不吞掉会打断整个 fetch 链。
 */
function stash(req, res) {
  if (!res || (res.status !== 200 && res.type !== 'opaque')) {
    return;
  }
  caches.open(CACHE).then((cache) => cache.put(req, res)).catch(() => {});
}

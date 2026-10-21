/*! nest-shader-bg.js — 入口页的着色器背景层（第十七轮「整站艺术化」）
 *
 * 这是什么：把概念页 /art.html 首屏那层 mesh-gradient 搬到全站入口页（首页 / 登录 /
 * 注册 / 忘记密码 / 重置密码 / 开发中提示）的固定背景层上，作为「整站艺术化」的站点级
 * 一层。工作页不加这一层（它们已经有 /js/nest-particles.js 的粒子层，两层叠加在老机器上
 * 是白付填充率）。
 *
 * 来源：@paper-design/shaders 0.0.81（Apache-2.0），已 vendored 到
 *       /libs/paper-shaders/（见该目录的 VERSION.txt 与 LICENSE）。
 *
 * ⚠ 尺寸 uniform 的坑（2026-10-15 用 gl.readPixels 抓到，成本是一整张静默透明的画布）：
 *   vertex-shader.js 里的 v_objectUV /= u_scale 需要一整套尺寸 uniform，而它们的默认值
 *   只在 shader-sizing.js 里（上游的 React 包装层才会填）。直接用核心 ShaderMount 又
 *   不传这一套 ⇒ u_scale = 0 ⇒ 除出 Inf/NaN ⇒ 片元写成全透明黑，**页面照旧好看、
 *   控制台一个字都不报**。所以下面必须把 SIZING 一起传进去。
 *
 * 降级：WebGL 不可用 / 挂载抛错 ⇒ 摘掉宿主、给 <html> 加 .nx-no-webgl，什么都不做。
 *   这时页面看到的是 nest.css 里的 CSS 洗底（.nx-aurora 或 body.bg-background-light），
 *   观感依然成立，不会留下空白。
 * 省电：maxPixelCount 封顶 1280×720、minPixelRatio = 1（不为老机器的 2 倍填充率买单）；
 *   ShaderMount 自带 IntersectionObserver，滚出视口时 currentSpeed 自动归零；
 *   系统「减少动态效果」或浏览器 Save-Data 打开时 speed = 0（只画一帧静态渐变）。
 */
import { ShaderMount } from '/libs/paper-shaders/shader-mount.js';
import { meshGradientFragmentShader } from '/libs/paper-shaders/shaders/mesh-gradient.js';
import { getShaderColorFromString } from '/libs/paper-shaders/get-shader-color-from-string.js';
import { ShaderFitOptions, defaultObjectSizing } from '/libs/paper-shaders/shader-sizing.js';

const VERSION = 'shader-bg-1';
const HOST_ID = 'nx-shader-bg';
const THEME_KEY = 'nest-theme';

/* 见文件头那个坑：这一套必须传，不能省。 */
const SIZING = {
  u_fit: ShaderFitOptions[defaultObjectSizing.fit],
  u_scale: defaultObjectSizing.scale,
  u_rotation: defaultObjectSizing.rotation,
  u_offsetX: defaultObjectSizing.offsetX,
  u_offsetY: defaultObjectSizing.offsetY,
  u_originX: defaultObjectSizing.originX,
  u_originY: defaultObjectSizing.originY,
  u_worldWidth: defaultObjectSizing.worldWidth,
  u_worldHeight: defaultObjectSizing.worldHeight,
  u_imageAspectRatio: 1,
};

/* 五色取自 /art.html 的 hero 预设（同一套调色），只是把底色换成整站的暖纸 / 深底，
   让网格渐变和页面底自然接上，而不是像贴了一张图。 */
const PRESETS = {
  light: {
    colors: ['#f7f5f0', '#e3e8fd', '#93a8f8', '#5b74e0', '#c9b8ff'],
    speed: 0.2,
    distortion: 0.9,
    swirl: 0.3,
    grainMixer: 0.14,
    grainOverlay: 0.05,
  },
  dark: {
    colors: ['#0d0e11', '#141d3d', '#26358a', '#4b3a8f', '#1c2028'],
    speed: 0.2,
    distortion: 0.9,
    swirl: 0.3,
    grainMixer: 0.12,
    grainOverlay: 0.05,
  },
};

const root = document.documentElement;
const reduceQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
/* Save-Data（客户端省流开关）与「减少动态效果」同等待遇：只画一帧静态渐变。
   实测（无头 Chrome 软件渲染，无 GPU）：全屏 mesh-gradient 在 1440×1100 吃到约 70% 的帧预算、
   1265×720 约 55% —— 所以像素数封顶 1280×720，别给机房里没有独显的机器加班。 */
const saveData = !!(navigator.connection && navigator.connection.saveData);

let host = null;
let mount = null;
let state = 'pending';
let error = null;

function themeOf() {
  /* 以 <html> 上的 .dark 为准 —— 那才是页面真正画出来的样子（每页头部的内联引导脚本
     已经在样式表之前按 localStorage 设好了它）。localStorage 只在内联脚本缺席时兜底。 */
  if (root.classList.contains('dark')) return 'dark';
  if (root.classList.contains('light')) return 'light';
  let stored = null;
  try {
    stored = localStorage.getItem(THEME_KEY);
  } catch (e) {
    stored = null;
  }
  return stored === 'dark' ? 'dark' : 'light';
}

function uniformsFor(theme) {
  const p = PRESETS[theme] || PRESETS.light;
  return Object.assign({}, SIZING, {
    u_colors: p.colors.map(getShaderColorFromString),
    u_colorsCount: p.colors.length,
    u_distortion: p.distortion,
    u_swirl: p.swirl,
    u_grainMixer: p.grainMixer,
    u_grainOverlay: p.grainOverlay,
  });
}

function speedFor() {
  if (reduceQuery.matches || saveData) return 0;
  return (PRESETS[themeOf()] || PRESETS.light).speed;
}

function ensureHost() {
  if (host) return host;
  host = document.createElement('div');
  host.id = HOST_ID;
  host.setAttribute('aria-hidden', 'true');
  /* -z-20：粒子层是 -10、.nx-aurora 是 -10，着色器在最底下当「纸」。 */
  host.style.cssText = 'position:fixed;inset:0;z-index:-20;pointer-events:none;overflow:hidden';
  document.body.appendChild(host);
  return host;
}

function mountNow() {
  const theme = themeOf();
  const el = ensureHost();
  /* 参数顺序照 ShaderMount 的签名：host, fragment, uniforms, webglContext, speed,
     frame, minPixelRatio, maxPixelCount, ... */
  mount = new ShaderMount(el, meshGradientFragmentShader, uniformsFor(theme), undefined, speedFor(), 0, 1, 1280 * 720);
  state = 'running';
}

function fail(e) {
  error = String((e && e.message) || e);
  state = 'css-fallback';
  root.classList.add('nx-no-webgl');
  if (host) {
    host.remove();
    host = null;
  }
  mount = null;
}

function boot() {
  if (!document.body) {
    state = 'no-body';
    return;
  }
  try {
    mountNow();
  } catch (e) {
    fail(e);
  }
}

/* 主题切换：与 /art.html 同一约定 —— 主题按钮只负责给 <html> 加/去 .dark 并写
   localStorage，这里监听 class 变化换一套 uniform。 */
const themeObserver = new MutationObserver(() => {
  if (!mount || state !== 'running') return;
  try {
    mount.setUniforms(uniformsFor(themeOf()));
  } catch (e) {
    error = String((e && e.message) || e);
  }
});
themeObserver.observe(root, { attributes: true, attributeFilter: ['class'] });

if (typeof reduceQuery.addEventListener === 'function') {
  reduceQuery.addEventListener('change', () => {
    if (!mount || state !== 'running') return;
    try {
      mount.setSpeed(speedFor());
    } catch (e) {
      error = String((e && e.message) || e);
    }
  });
}

/* 只读 API（供探针与别处自查用）。用取值器暴露，不要在启动时把状态抄成快照。 */
const api = {
  version: VERSION,
  get state() {
    return state;
  },
  get error() {
    return error;
  },
  get host() {
    return host;
  },
  get mount() {
    return mount;
  },
  get theme() {
    return themeOf();
  },
  get reduced() {
    return reduceQuery.matches;
  },
  speed() {
    return speedFor();
  },
  destroy() {
    try {
      if (mount && typeof mount.dispose === 'function') mount.dispose();
    } catch (e) {
      error = String((e && e.message) || e);
    }
    if (host) host.remove();
    mount = null;
    host = null;
    state = 'destroyed';
    return state;
  },
};
window.NestShaderBg = api;

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot, { once: true });
} else {
  boot();
}

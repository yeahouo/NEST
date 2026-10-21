/*
 * NEST 安居 · 艺术版概念页（/art.html）脚本
 * ---------------------------------------------------------------------------
 * 只做四件事：
 *   1) 用 paper-design/shaders（Apache-2.0，vendored 在 /libs/paper-shaders/）
 *      在 hero 与中部色带上挂 WebGL2 网格渐变；无 WebGL2 或出错时退回 CSS 渐变；
 *   2) 主题开关（写 localStorage 的 nest-theme，与全站同一个键，切换后重算渐变配色）；
 *   3) 动效开关（关掉即把着色器 speed 设为 0、销毁 Lenis、入场动画一次性置位）；
 *   4) 滚动进度条 + 入场观察器（IntersectionObserver）。
 * 尊重 prefers-reduced-motion：默认静态、不启动平滑滚动。
 * 设计来源与许可证见页面 §03 与 docs/课堂管理系统-功能补全说明.md §20。
 */

import { ShaderMount } from '/libs/paper-shaders/shader-mount.js';
import { meshGradientFragmentShader } from '/libs/paper-shaders/shaders/mesh-gradient.js';
import { getShaderColorFromString } from '/libs/paper-shaders/get-shader-color-from-string.js';
import { ShaderFitOptions, defaultObjectSizing } from '/libs/paper-shaders/shader-sizing.js';

const VERSION = 'art-2';
const THEME_KEY = 'nest-theme';
const MOTION_KEY = 'nest-art-motion';

const reduceQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
const root = document.documentElement;

/* ShaderMount 是「低级」入口：顶点着色器里 u_scale/u_fit/u_rotation/... 这些
   **尺寸 uniform 不是可选装饰**，@paper-design/shaders-react 才会替你填；直接用核心包
   就必须自己传。少了它们 u_scale 默认为 0，顶点着色器里 `v_objectUV /= u_scale`
   得到 Inf/NaN，片元输出被写成透明黑 —— 表现是「页面看着正常、canvas 一个像素都没画」。
   （2026-10-15 用 gl.readPixels 逐像素抓到：mean/min/max 全 0，而手动 gl.clear 红色
   能读回 [255,0,0,255]，说明上下文正常、只是没画出东西。） */
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
  u_imageAspectRatio: 1
};

/* 配色沿用品牌 indigo (#3e63dd, Radix indigo-9) + iris/violet + 一点暖金，
   深色档整体压暗，避免在 #111113 的纸上发灰。 */
const PRESETS = {
  light: {
    hero: { colors: ['#eef1fd', '#8ea5f7', '#3e63dd', '#9b6fd6', '#f6c67a'], distortion: 0.85, swirl: 0.26, grainMixer: 0.14, grainOverlay: 0.05 },
    band: { colors: ['#ffffff', '#dfe5fb', '#3e63dd', '#8e4ec6', '#f5c36b'], distortion: 1.05, swirl: 0.16, grainMixer: 0.1, grainOverlay: 0.04 }
  },
  dark: {
    hero: { colors: ['#0b0d13', '#20306e', '#3e63dd', '#6b46b0', '#8a5a12'], distortion: 0.9, swirl: 0.3, grainMixer: 0.18, grainOverlay: 0.08 },
    band: { colors: ['#111318', '#1b2757', '#3e63dd', '#5b3a91', '#7a5418'], distortion: 1.05, swirl: 0.18, grainMixer: 0.14, grainOverlay: 0.06 }
  }
};

/* 动效偏好：localStorage 优先（'off'/'on'），没存过就看系统 prefers-reduced-motion。
   「系统要求减少动效」是硬底线：即使存过 'on' 也仍然不动。 */
function motionPref() {
  let stored = null;
  try { stored = localStorage.getItem(MOTION_KEY); } catch (e) { stored = null; }
  if (stored === 'off') return false;
  if (stored === 'on') return !reduceQuery.matches;
  return !reduceQuery.matches;
}

const state = {
  version: VERSION,
  state: 'pending',
  theme: root.classList.contains('dark') ? 'dark' : 'light',
  motion: motionPref(),
  hero: null,
  band: null,
  lenis: null,
  error: null
};

function themeOf() {
  return root.classList.contains('dark') ? 'dark' : 'light';
}

function uniformsFor(kind, theme) {
  const preset = PRESETS[theme][kind];
  const colors = preset.colors.map(getShaderColorFromString);
  return {
    ...SIZING,
    u_colors: colors,
    u_colorsCount: colors.length,
    u_distortion: preset.distortion,
    u_swirl: preset.swirl,
    u_grainMixer: preset.grainMixer,
    u_grainOverlay: preset.grainOverlay
  };
}

/* ── WebGL 背景 ───────────────────────────────────────────────────────── */
function mountShader(host, kind, speed) {
  const mount = new ShaderMount(
    host,
    meshGradientFragmentShader,
    uniformsFor(kind, themeOf()),
    undefined,
    speed,
    0,
    1,                 /* minPixelRatio：不给 2，教室里旧机器的填充率更重要 */
    1920 * 1080        /* maxPixelCount：Full HD 以上不再加像素 */
  );
  return mount;
}

function setSpeed(mount, speed) {
  if (mount) mount.setSpeed(speed);
}

function startShaders() {
  const heroHost = document.querySelector('[data-art-shader="hero"]');
  const bandHost = document.querySelector('[data-art-shader="band"]');
  const speed = state.motion ? 0.34 : 0;
  try {
    if (heroHost) state.hero = mountShader(heroHost, 'hero', speed);
    if (bandHost) state.band = mountShader(bandHost, 'band', 0);
    state.state = state.motion ? 'running' : 'static';
  } catch (error) {
    state.error = String(error && error.message ? error.message : error);
    root.classList.add('art-no-webgl');
    state.state = 'css-fallback';
  }
}

function applyTheme() {
  state.theme = themeOf();
  if (state.hero) state.hero.setUniforms(uniformsFor('hero', state.theme));
  if (state.band) state.band.setUniforms(uniformsFor('band', state.theme));
}

/* ── 平滑滚动（Lenis, MIT） ───────────────────────────────────────────── */
let lenisRaf = 0;
function startLenis() {
  if (state.lenis || !state.motion || reduceQuery.matches) return;
  if (typeof window.Lenis !== 'function') return;
  state.lenis = new window.Lenis({ lerp: 0.11, wheelMultiplier: 1, smoothWheel: true, anchors: false });
  const raf = (time) => {
    if (!state.lenis) return;
    state.lenis.raf(time);
    lenisRaf = requestAnimationFrame(raf);
  };
  lenisRaf = requestAnimationFrame(raf);
}
function stopLenis() {
  if (lenisRaf) cancelAnimationFrame(lenisRaf);
  lenisRaf = 0;
  if (state.lenis) {
    state.lenis.destroy();
    state.lenis = null;
  }
}

/* ── 入场 ─────────────────────────────────────────────────────────────── */
function revealAll() {
  document.querySelectorAll('[data-art-reveal]').forEach((el) => el.classList.add('is-in'));
}
function startReveal() {
  if (state.motion === false || !('IntersectionObserver' in window)) {
    revealAll();
    return;
  }
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      const el = entry.target;
      const delay = Number(el.getAttribute('data-art-reveal')) || 0;
      window.setTimeout(() => el.classList.add('is-in'), delay);
      observer.unobserve(el);
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -6% 0px' });
  document.querySelectorAll('[data-art-reveal]').forEach((el) => observer.observe(el));
}

/* ── 滚动进度 ─────────────────────────────────────────────────────────── */
function bindProgress() {
  const bar = document.getElementById('artProgress');
  if (!bar) return;
  const fill = bar.firstElementChild;
  const update = () => {
    const max = root.scrollHeight - window.innerHeight;
    const ratio = max > 0 ? Math.min(1, Math.max(0, window.scrollY / max)) : 0;
    fill.style.width = (ratio * 100).toFixed(2) + '%';
  };
  update();
  window.addEventListener('scroll', update, { passive: true });
  window.addEventListener('resize', update, { passive: true });
}

/* ── 开关 ─────────────────────────────────────────────────────────────── */
function bindTheme() {
  const btn = document.getElementById('artTheme');
  if (!btn) return;
  const sync = () => {
    /* themeOf() 返回字符串：写成 `dark ? 'true' : 'false'` 会让它永远为真
       （'light' 也是真值），aria-pressed 就再也没对过。必须先比出布尔值。 */
    const isDark = themeOf() === 'dark';
    btn.setAttribute('aria-pressed', isDark ? 'true' : 'false');
    btn.textContent = isDark ? '浅色' : '深色';
  };
  btn.addEventListener('click', () => {
    const next = themeOf() === 'dark' ? 'light' : 'dark';
    root.classList.toggle('dark', next === 'dark');
    try { localStorage.setItem(THEME_KEY, next); } catch (e) { /* 隐私模式下忽略 */ }
    applyTheme();
    sync();
  });
  sync();
}

function bindMotion() {
  const btn = document.getElementById('artMotion');
  if (!btn) return;

  const sync = () => {
    btn.setAttribute('aria-pressed', state.motion ? 'true' : 'false');
    btn.textContent = state.motion ? '暂停动效' : '开启动效';
  };
  btn.addEventListener('click', () => {
    state.motion = !state.motion;
    try { localStorage.setItem(MOTION_KEY, state.motion ? 'on' : 'off'); } catch (e) { /* ignore */ }
    root.classList.toggle('art-static', !state.motion);
    setSpeed(state.hero, state.motion ? 0.34 : 0);
    if (state.motion) { startLenis(); startReveal(); }
    else { stopLenis(); revealAll(); }
    sync();
  });
  sync();
}

/* ── 启动 ─────────────────────────────────────────────────────────────── */
function boot() {
  root.classList.toggle('art-static', !state.motion);
  startShaders();
  applyTheme();
  /* 动效偏好要在启动平滑滚动/入场动画**之前**就生效，否则「上次关掉动效」的
     用户这一屏仍会被滚动接管、文字还会飞进来一次。 */
  if (state.motion) { startLenis(); startReveal(); } else { stopLenis(); revealAll(); }
  bindProgress();
  bindTheme();
  bindMotion();
  state.state = state.state === 'css-fallback' ? state.state : (state.motion ? 'running' : 'static');

  /* 这些必须是**活的**取值器：Object.assign 会把 getter 当普通属性读出来，
     之后开关切换就再也读不到新值（第一版就是这么错的，探针当场抓到）。 */
  const readers = {
    state: () => state.state,
    theme: () => state.theme,
    motion: () => state.motion,
    preset: () => state.theme,
    canvases: () => document.querySelectorAll('[data-art-shader] canvas').length,
    frame: () => (state.hero ? state.hero.currentFrame : 0),
    speed: () => (state.hero ? state.hero.currentSpeed : 0),
    webgl: () => !root.classList.contains('art-no-webgl'),
    error: () => state.error
  };
  const api = window.NestArt || {};
  api.version = VERSION;
  Object.keys(readers).forEach((key) => {
    Object.defineProperty(api, key, { configurable: true, enumerable: true, get: readers[key] });
  });
  api.applyTheme = applyTheme;
  api.destroy = () => {
    stopLenis();
    if (state.hero) { state.hero.dispose(); state.hero = null; }
    if (state.band) { state.band.dispose(); state.band = null; }
    state.state = 'destroyed';
  };
  window.NestArt = api;
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
else boot();

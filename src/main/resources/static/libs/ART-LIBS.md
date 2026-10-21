# 艺术层本地库清单（离线 vendored，零 CDN）

本目录下的文件全部随仓库分发，校内网离线可用，**不引用任何 CDN**。
选型纪律（沿用项目既定政策）：只用**有出处**的现成设计/库，不自创；每个库都记下版本、许可证、来源与体积，便于答辩时逐条溯源。

| 目录 | 库 | 版本 | 许可证 | 来源 | 落盘文件（字节） |
|---|---|---|---|---|---|
| `splitting/` | Splitting.js | 1.1.0 | MIT | https://github.com/shshaw/Splitting | `splitting.min.js` 3,871（UMD）+ `splitting.css` 1,784 |
| `auto-animate/` | @formkit/auto-animate | 0.10.0 | MIT | https://github.com/formkit/auto-animate | `auto-animate.min.js` 8,406（**ESM**，见下）+ `LICENSE` 1,052 |
| `embla/` | embla-carousel | 8.6.0 | MIT | https://github.com/davidjerleke/embla-carousel | `embla-carousel.umd.js` 17,946（UMD） |
| `atropos/` | Atropos | 2.0.2 | MIT | https://github.com/nolimits4web/atropos | `atropos.min.js` 6,925（IIFE 全局 `Atropos`）+ `atropos.min.css` 1,666 |
| `tsparticles/` | tsParticles slim | 3.x | MIT | https://github.com/tsparticles/tsparticles | `tsparticles.slim.bundle.min.js` 158,878 |
| `paper-shaders/` | @paper-design/shaders | 0.0.81 | Apache-2.0 | https://github.com/paper-design/shaders | dist 多模块 ESM（整目录复制） |
| `lenis/` | Lenis | 1.3.26 | MIT | https://github.com/darkroomengineering/lenis | `lenis.min.js` |
| `open-props/` | Open Props | 1.7.23 | MIT | https://github.com/argyleink/open-props | 设计令牌 CSS |
| `@radix-ui/colors` | Radix Colors | 3.0.0 | MIT | https://github.com/radix-ui/colors | 调色板 CSS |
| `daisyui/` | daisyUI（仅审计副本） | 4.12.24 | MIT | https://github.com/saadeghi/daisyui | 见该目录 `VERSION.txt` |

## 三个必须记住的接线坑

1. **`auto-animate` 只有 ESM 产物**：包内 `index.min.js` 结尾是 `export { R as autoAnimate, ... }`，`package.json` 里 `"type": "module"`、没有 `dist/`、没有 UMD/CJS。
   用 `<script defer src=...>` 直接引入会因 `export` 报语法错误；必须写成 `import('/libs/auto-animate/auto-animate.min.js')` 形式的**动态 import**（或在 module 脚本里静态 import）。
   我们的 `js/nest-art2.js` 就采用动态 import，失败时退化为自写的 FLIP 列表动画。
2. **`atropos.min.js` 是 IIFE**，暴露全局 `Atropos`，用普通 `<script defer>` 即可；但它的 3D 效果依赖 `atropos.min.css`。
3. **`splitting` / `embla` 是 UMD**，普通 `<script defer>` 引入后可用全局 `Splitting` / `EmblaCarousel`。

## 一句话记住

先用纯 CSS（渐变、噪点、点阵、描边字、环形进度、shimmer）做完能做的，再让上面这几个库只承担「文字切分、列表增删动画、轮播、3D 倾斜」这四件 CSS 干不好的事。

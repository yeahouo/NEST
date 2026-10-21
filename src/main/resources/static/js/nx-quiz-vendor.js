/* 把 vendor 进来的 auto-animate（ESM 包）挂到 window，供页面里的普通脚本使用。
   来源：@formkit/auto-animate 0.10.0（MIT），许可证见 /libs/auto-animate/LICENSE。
   SortableJS 是 UMD，普通 <script> 直接给出全局 Sortable，不需要这个包装。 */
import { autoAnimate } from '/libs/auto-animate/auto-animate.min.js';

window.autoAnimate = autoAnimate;
window.dispatchEvent(new Event('nx-automate-ready'));

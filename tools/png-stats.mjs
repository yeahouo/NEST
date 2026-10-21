/*
 * png-stats.mjs —— 只读统计一张 PNG 的像素（用来证明「图上真有色差」，不是靠肉眼看）。
 * 只支持 8bit RGB/RGBA 非隔行 PNG —— Chrome 的截图就是这两种。
 *
 * 用法：
 *   node tools/png-stats.mjs <png> [x,y,w,h]          默认整图
 *   node tools/png-stats.mjs <png> 0,0,1440,900 --rows  额外打印每 1/10 高度的均值
 */
import { readFileSync } from 'node:fs';
import { inflateSync } from 'node:zlib';

function decodePng(buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('不是 PNG');
  let off = 8;
  let ihdr = null;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      ihdr = {
        width: data.readUInt32BE(0),
        height: data.readUInt32BE(4),
        bitDepth: data[8],
        colorType: data[9],
        interlace: data[12]
      };
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    off += 12 + len;
  }
  if (!ihdr) throw new Error('没有 IHDR');
  if (ihdr.bitDepth !== 8) throw new Error(`不支持的 bitDepth ${ihdr.bitDepth}`);
  if (ihdr.interlace !== 0) throw new Error('不支持隔行 PNG');
  const channels = ihdr.colorType === 6 ? 4 : ihdr.colorType === 2 ? 3 : null;
  if (!channels) throw new Error(`不支持的 colorType ${ihdr.colorType}`);
  const raw = inflateSync(Buffer.concat(idat));
  const { width, height } = ihdr;
  const stride = width * channels;
  const out = Buffer.alloc(stride * height);
  let pos = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[pos++];
    const line = raw.subarray(pos, pos + stride);
    pos += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : null;
    for (let i = 0; i < stride; i++) {
      const raw0 = line[i];
      const a = i >= channels ? cur[i - channels] : 0;
      const b = prev ? prev[i] : 0;
      const c = prev && i >= channels ? prev[i - channels] : 0;
      let v;
      switch (filter) {
        case 0: v = raw0; break;
        case 1: v = raw0 + a; break;
        case 2: v = raw0 + b; break;
        case 3: v = raw0 + ((a + b) >> 1); break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
          v = raw0 + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
          break;
        }
        default: throw new Error(`未知的 PNG 过滤器 ${filter}（第 ${y} 行）`);
      }
      cur[i] = v & 0xff;
    }
  }
  return { width, height, channels, pixels: out };
}

function stats(img, rect) {
  const [x0, y0, w, h] = rect;
  const { width, channels, pixels } = img;
  const sum = [0, 0, 0];
  const min = [255, 255, 255];
  const max = [0, 0, 0];
  const colors = new Set();
  let n = 0, sq = 0;
  for (let y = y0; y < y0 + h; y++) {
    for (let x = x0; x < x0 + w; x++) {
      const i = (y * width + x) * channels;
      const r = pixels[i], g = pixels[i + 1], b = pixels[i + 2];
      sum[0] += r; sum[1] += g; sum[2] += b;
      const rgb = [r, g, b];
      for (let k = 0; k < 3; k++) {
        if (rgb[k] < min[k]) min[k] = rgb[k];
        if (rgb[k] > max[k]) max[k] = rgb[k];
      }
      if (colors.size < 20000) colors.add((r << 16) | (g << 8) | b);
      const lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
      sq += lum * lum;
      n++;
    }
  }
  const mean = sum.map((s) => Math.round(s / n));
  const ml = 0.2126 * mean[0] + 0.7152 * mean[1] + 0.0722 * mean[2];
  return {
    rect: `${x0},${y0},${w},${h}`,
    mean,
    min,
    max,
    meanLum: Math.round(ml),
    sdLum: Math.round(Math.sqrt(Math.max(0, sq / n - ml * ml))),
    colors: colors.size
  };
}

const file = process.argv[2];
if (!file) {
  console.error('用法: node tools/png-stats.mjs <png> [x,y,w,h] [--rows]');
  process.exit(2);
}
const img = decodePng(readFileSync(file));
const rectArg = process.argv[3] && /^\d+,\d+,\d+,\d+$/.test(process.argv[3]) ? process.argv[3].split(',').map(Number) : null;
const rect = rectArg || [0, 0, img.width, img.height];
const out = { file, size: `${img.width}x${img.height}`, channels: img.channels, full: stats(img, rect) };
if (process.argv.includes('--rows')) {
  out.rows = [];
  const bandH = Math.max(1, Math.round(img.height / 10));
  for (let i = 0; i < 10; i++) {
    const y = i * bandH;
    const h = Math.min(bandH, img.height - y);
    if (h <= 0) break;
    out.rows.push(stats(img, [0, y, img.width, h]));
  }
}
console.log(JSON.stringify(out, null, 2));

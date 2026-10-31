/**
 * 由设计师稿 tools/ic_launcher_source.svg 生成 Android 启动图标：
 *   - 自适应前景 PNG（透明、仅字形），自动居中并按 66/108 安全区缩放
 *     → mipmap-{mdpi..xxxhdpi}/ic_launcher_foreground.png （108/162/216/324/432）
 *   - 旧版方形 PNG（银底 + 字形，用于 <26 或作为兜底）
 *     → mipmap-{mdpi..xxxhdpi}/ic_launcher.png （48/72/96/144/192）
 * 背景层用 res/drawable/ic_launcher_background.xml（渐变），不走 PNG。
 *
 * 用法: node tools/genlaunchericon.js <app/src/main/res>
 *   SHARP_PATH 环境变量可指定 sharp 模块路径
 */
const fs = require('fs');
const path = require('path');

function loadSharp() {
  const candidates = [
    process.env.SHARP_PATH,
    'sharp',
    'C:/Users/83722/WorkBuddy/2026-10-31-22-24-43/.iconbuild/node_modules/sharp',
  ].filter(Boolean);
  for (const c of candidates) {
    try { return require(c); } catch (e) { /* try next */ }
  }
  throw new Error('找不到 sharp：请设置 SHARP_PATH 指向已安装的 sharp 模块');
}
const sharp = loadSharp();

const SRC = path.join(__dirname, 'ic_launcher_source.svg');
const CANVAS = 1024;
const VIEW = 108;              // 自适应图标画布单位
const SAFE = 64;               // 字形目标宽度（单位），< 66 安全区

(async () => {
  const resDir = process.argv[2];
  if (!resDir) throw new Error('用法: node tools/genlaunchericon.js <res 目录>');
  const src = fs.readFileSync(SRC, 'utf8');

  // 去掉背景/描边 rect，得到纯字形层
  // 只去掉带 rx="180" 的背景层与内描边；注意蒙版内部的白色底 rect 必须保留，
  // 否则 T3 描边字会被整个遮掉
  const glyphInner = src
    .replace(/<rect[^>]*rx="180"[^>]*\/>/g, '')
    .replace(/<rect x="6"[^>]*\/>/g, '')
    .replace(/<rect x="10"[^>]*\/>/g, '');
  const inner = glyphInner.replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '');

  const wrap = (body) =>
    `<svg xmlns="http://www.w3.org/2000/svg" width="${CANVAS}" height="${CANVAS}" viewBox="0 0 ${CANVAS} ${CANVAS}">${body}</svg>`;

  // ---- 1) 测量纯字形墨迹包围盒（1024 空间） ----
  const { data, info } = await sharp(Buffer.from(wrap(inner)))
    .ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  let minX = info.width, maxX = -1, minY = info.height, maxY = -1;
  for (let y = 0; y < info.height; y++) {
    for (let x = 0; x < info.width; x++) {
      if (data[(y * info.width + x) * info.channels + 3] > 20) {
        if (x < minX) minX = x; if (x > maxX) maxX = x;
        if (y < minY) minY = y; if (y > maxY) maxY = y;
      }
    }
  }
  const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
  const iw = maxX - minX + 1, ih = maxY - minY + 1;
  console.log(`字形墨迹 bbox=[${minX},${minY}-${maxX},${maxY}] ${iw}x${ih} center=(${cx.toFixed(1)},${cy.toFixed(1)})`);

  const ADAPTIVE = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 };

  // ---- 2.5) 自适应背景层：银色渐变 + 拉丝纹理（去圆角，由 launcher 蒙形） ----
  const bgSvg = (size) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${VIEW} ${VIEW}" width="${size}" height="${size}">
<defs>
<linearGradient id="s" x1="0" y1="0" x2="0" y2="1">
<stop offset="0" stop-color="#f4f4f2"/><stop offset="0.45" stop-color="#e2e2df"/>
<stop offset="0.55" stop-color="#d6d6d3"/><stop offset="1" stop-color="#c2c2bf"/>
</linearGradient>
<pattern id="b" width="0.84" height="0.84" patternUnits="userSpaceOnUse" patternTransform="rotate(90)">
<line x1="0" y1="0" x2="0" y2="0.84" stroke="#ffffff" stroke-opacity="0.35" stroke-width="0.1"/>
<line x1="0.42" y1="0" x2="0.42" y2="0.84" stroke="#9a9a97" stroke-opacity="0.18" stroke-width="0.1"/>
</pattern>
<linearGradient id="sh" x1="0" y1="0" x2="0" y2="1">
<stop offset="0" stop-color="#ffffff" stop-opacity="0.55"/>
<stop offset="0.5" stop-color="#ffffff" stop-opacity="0"/>
<stop offset="1" stop-color="#000000" stop-opacity="0.06"/>
</linearGradient>
</defs>
<rect width="${VIEW}" height="${VIEW}" fill="url(#s)"/>
<rect width="${VIEW}" height="${VIEW}" fill="url(#b)"/>
<rect width="${VIEW}" height="${VIEW}" fill="url(#sh)"/>
</svg>`;
  for (const [dens, size] of Object.entries(ADAPTIVE)) {
    const dir = path.join(resDir, `mipmap-${dens}`);
    fs.mkdirSync(dir, { recursive: true });
    await sharp(Buffer.from(bgSvg(size)), { density: 400 }).resize(size, size).png().toFile(path.join(dir, 'ic_launcher_background.png'));
    console.log(`  mipmap-${dens}/ic_launcher_background.png ${size}px`);
  }

  // ---- 2) 自适应前景：居中 + 缩放到安全区 ----
  const k = SAFE / CANVAS / (iw / CANVAS);      // 目标：墨迹宽 = SAFE/108 画布
  const fg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${VIEW} ${VIEW}" width="{S}" height="{S}">`
    + `<g transform="translate(${VIEW / 2},${VIEW / 2}) scale(${k.toFixed(6)}) translate(${(-cx).toFixed(2)},${(-cy).toFixed(2)})">`
    + inner + `</g></svg>`;
  console.log(`前景缩放 k=${k.toFixed(6)} → 墨迹宽 ${(iw * k).toFixed(1)}/${VIEW}`);

  for (const [dens, size] of Object.entries(ADAPTIVE)) {
    const dir = path.join(resDir, `mipmap-${dens}`);
    fs.mkdirSync(dir, { recursive: true });
    const svg = Buffer.from(fg.replace(/\{S\}/g, String(size)));
    await sharp(svg, { density: 400 }).resize(size, size).png().toFile(path.join(dir, 'ic_launcher_foreground.png'));
    console.log(`  mipmap-${dens}/ic_launcher_foreground.png ${size}px`);
  }

  // ---- 3) 旧版方形图标：保留银底，字形重新居中 ----
  const centerInner = inner
    .replace('<text x="512" y="520"', `<g><text x="512" y="520"`)
    .replace('</g>\n</svg>', '');   // 安全兜底
  // 直接给两个 text/mask 组套一层平移
  const fix = `<g transform="translate(${(CANVAS / 2 - cx).toFixed(2)},${(CANVAS / 2 - cy).toFixed(2)})">`
    + inner + `</g>`;
  const fullSrc = src.replace(
    /([\s\S]*?)(\s*<!-- CONTAX 主字样[\s\S]*?)(\s*<\/svg>)/,
    (m, head, body, tail) => head + '<g transform="translate(' +
      (CANVAS / 2 - cx).toFixed(2) + ',' + (CANVAS / 2 - cy).toFixed(2) + ')">' + body + '</g>' + tail
  );
  const LEGACY = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
  for (const [dens, size] of Object.entries(LEGACY)) {
    const dir = path.join(resDir, `mipmap-${dens}`);
    fs.mkdirSync(dir, { recursive: true });
    await sharp(Buffer.from(fullSrc), { density: 400 }).resize(size, size).png().toFile(path.join(dir, 'ic_launcher.png'));
    console.log(`  mipmap-${dens}/ic_launcher.png ${size}px`);
  }
  void centerInner; void fix;
  console.log('完成');
})();

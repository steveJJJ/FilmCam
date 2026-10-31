const fs = require('fs');
const http = require('http');
const path = require('path');

// ---- 25 支胶卷的封面参数（颜色/字标来自 filmcover 预设，缺失型号按同品牌版式推导）----
const KODAK = (o) => Object.assign({ brand: 'Kodak', brandColor: '#cc1c05', topColor: '#fccd01', style: 'helv', bottomBand: true }, o);
const FJ = (o) => Object.assign({ brand: 'FUJIFILM', brandColor: '#141414', topColor: '#ffffff', style: 'helv', brandScale: 0.62 }, o);
const IL = (o) => Object.assign({ brand: 'ILFORD', brandColor: '#111111', topColor: '#ffffff', style: 'helv', brandScale: 0.78, bottomBand: true, bottomColor: '#ffffff' }, o);
const CS = (o) => Object.assign({ brand: 'CineStill', brandColor: '#ffffff', topColor: '#141414', style: 'helv', brandScale: 0.8, bottomBand: true, bottomColor: '#141414' }, o);

const SPECS = [
  // --- Kodak 负片 ---
  ['ic_film_portra400',      KODAK({ bodyColor: '#653be5', name: 'PORTRA', number: '400' })],
  ['ic_film_portra160',      KODAK({ bodyColor: '#e5761f', name: 'PORTRA', number: '160' })],
  ['ic_film_portra800',      KODAK({ bodyColor: '#2c2f8f', name: 'PORTRA', number: '800' })],
  ['ic_film_portra800p1',    KODAK({ bodyColor: '#2c2f8f', name: 'PORTRA', number: '800', tag: 'PUSH +1' })],
  ['ic_film_portra800p2',    KODAK({ bodyColor: '#2c2f8f', name: 'PORTRA', number: '800', tag: 'PUSH +2' })],
  ['ic_film_ektar100',       KODAK({ brandColor: '#db0111', topColor: '#fcd605', bodyColor: '#a90214', name: 'EKTAR', number: '100' })],
  ['ic_film_gold200',        KODAK({ brandColor: '#e1371d', topColor: '#f0c344', bodyColor: '#c744ab', style: 'geo', name: 'GOLD', number: '200', nameWidth: 1.42, nameTrack: 0.05 })],
  ['ic_film_ultramax400',    KODAK({ brandColor: '#e2361e', topColor: '#f5ca47', bodyColor: '#4a96f4', name: 'ULTRAMAX', number: '400', numScale: 1.125 })],
  // --- Kodak 黑白 ---
  ['ic_film_trix400',        KODAK({ brandColor: '#c82020', topColor: '#f8b909', bodyColor: '#17583a', name: 'TRI-X', number: '400' })],
  ['ic_film_tmax100',        KODAK({ brandColor: '#d42a1c', topColor: '#f8c000', bodyColor: '#1d1d1f', name: 'T-MAX', number: '100' })],
  // --- 电影卷 ---
  ['ic_film_vision3_50d',    KODAK({ topColor: '#f8c000', bodyColor: '#e0a51c', name: 'VISION3', number: '50D' })],
  ['ic_film_vision3_200t',   KODAK({ topColor: '#f8c000', bodyColor: '#2f7f8c', name: 'VISION3', number: '200T' })],
  ['ic_film_vision3_500t',   KODAK({ topColor: '#f8c000', bodyColor: '#4677b1', name: 'VISION3', number: '500T' })],
  ['ic_film_verita200d',     KODAK({ topColor: '#f8c000', bodyColor: '#402a1e', name: 'VERITA', number: '200D' })],
  // --- 反转片（列表内已禁用，图标仍保留）---
  ['ic_film_ektachrome100',  KODAK({ brandColor: '#db0111', topColor: '#fcd605', bodyColor: '#1a4c96', name: 'EKTACHROME', number: '100', perfs: true })],
  // --- 富士 ---
  ['ic_film_c200',           FJ({ bodyColor: '#1d9a55', name: 'FUJICOLOR', number: 'C200' })],
  ['ic_film_superia400',     FJ({ bodyColor: '#d22630', name: 'SUPERIA', number: '400' })],
  ['ic_film_pro400h',        FJ({ bodyColor: '#2f8fb0', bottomBand: true, bottomColor: '#ffffff', name: 'PRO', number: '400H' })],
  ['ic_film_provia100f',     FJ({ bodyColor: '#1f5fae', bottomBand: true, bottomColor: '#ffffff', name: 'PROVIA', number: '100F', perfs: true })],
  ['ic_film_velvia100',      FJ({ bodyColor: '#5a2a8c', bottomBand: true, bottomColor: '#ffffff', name: 'VELVIA', number: '100', perfs: true })],
  ['ic_film_acros100',       FJ({ bodyColor: '#1a1a1a', name: 'ACROS', number: '100' })],
  ['ic_film_vision3_250d',   KODAK({ topColor: '#f8c000', bodyColor: '#c0392b', name: 'VISION3', number: '250D' })],
  ['ic_film_kodachrome64',   KODAK({ brandColor: '#cc1c05', topColor: '#fcd601', bodyColor: '#d8262c', name: 'KODACHROME', number: '64', perfs: true })],
  // --- 其他 ---
  ['ic_film_hp5',            IL({ bodyColor: '#151515', name: 'HP5 PLUS', number: '400' })],
  ['ic_film_cinestill800t',  CS({ bodyColor: '#141414', name: 'TUNGSTEN', number: '800T' })],
];

const TEMPLATE = process.argv[2] || 'kodak';   // kodak | box | cine | cinebox
const SIZE = parseInt(process.argv[3] || '512', 10);
const OUT = process.argv[4] || '/tmp/fc/out-' + TEMPLATE;

function get(url) {
  return new Promise((res, rej) => {
    http.get(url, (r) => { let d = ''; r.on('data', (c) => d += c); r.on('end', () => res(d)); }).on('error', rej);
  });
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  let list = null;
  for (let i = 0; i < 60; i++) {
    try {
      const raw = await get('http://127.0.0.1:9333/json/list');
      const arr = JSON.parse(raw);
      list = arr.find((t) => t.type === 'page' && t.url.includes('gen.html'));
      if (list && list.webSocketDebuggerUrl) break;
    } catch (e) { /* not up yet */ }
    await sleep(300);
  }
  if (!list) { console.error('CDP 未就绪'); process.exit(1); }

  const ws = new WebSocket(list.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  });
  await new Promise((r) => ws.addEventListener('open', r));
  const send = (method, params) => new Promise((res) => {
    const mid = ++id;
    pending.set(mid, res);
    ws.send(JSON.stringify({ id: mid, method, params }));
  });

  await send('Runtime.enable', {});
  const expr = `(async () => {
    await document.fonts.ready;
    const SPECS = ${JSON.stringify(SPECS.map(([f, d]) => [f, d]))};
    const out = [];
    for (const [file, data] of SPECS) {
      const cv = document.createElement('canvas');
      const d = Object.assign({ exp: '36', proText: 'Professional' }, data);
      renderTo(cv, ${SIZE}, ${JSON.stringify(TEMPLATE)}, d);
      if (data.tag) {
        const band = 0.906, cy = (band + 1) / 2;
        drawText(cv.getContext('2d'), ${SIZE}, { text: data.tag, font: 'FK-Inter900', track: 0.06, h: 0.052, cy: cy, x: 0.5, align: 'center', color: data.textColor || '#ffffff' });
      }
      if (data.perfs) {
        // 反转片：上下加胶卷齿孔条（35mm 胶片边缘的输片孔），示意幻灯片/直出正像
        const c2 = cv.getContext('2d');
        const S2 = ${SIZE};
        const hole = (x, y, w, h, r) => { c2.beginPath(); c2.roundRect(x*S2, y*S2, w*S2, h*S2, r*S2); c2.fill(); };
        c2.fillStyle = 'rgba(14,12,10,0.9)';
        c2.fillRect(0, 0, S2, 0.055*S2);
        c2.fillRect(0, 0.945*S2, S2, 0.055*S2);
        c2.fillStyle = '#ece7db';
        const n = 7, w = 0.054, h = 0.027, gap = (1 - n*w) / (n + 1);
        for (let i = 0; i < n; i++) {
          const x = gap + i*(w + gap);
          hole(x, (0.055 - h)/2, w, h, 0.006);
          hole(x, 0.945 + (0.055 - h)/2, w, h, 0.006);
        }
      }
      out.push([file, cv.toDataURL('image/png')]);
    }
    return out;
  })()`;

  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  if (r.result && r.result.exceptionDetails) { console.error(JSON.stringify(r.result.exceptionDetails).slice(0, 800)); process.exit(1); }
  const arr = r.result.result.value;
  if (!Array.isArray(arr)) { console.error('返回值异常', JSON.stringify(r).slice(0, 600)); process.exit(1); }

  for (const [name, dataUrl] of arr) {
    fs.writeFileSync(path.join(OUT, name + '.png'), Buffer.from(dataUrl.split(',')[1], 'base64'));
  }
  console.log(`${TEMPLATE} ${SIZE}px -> ${OUT}  共 ${arr.length} 张`);
  ws.close();
  process.exit(0);
})();

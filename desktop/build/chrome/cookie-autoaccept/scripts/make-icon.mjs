import { writeFileSync, mkdirSync } from 'node:fs';
import { deflateSync } from 'node:zlib';

const N = 128;
const px = Buffer.alloc(N * (N * 4 + 1));
const chips = [[45, 40], [80, 35], [60, 70], [90, 75], [40, 90], [75, 100]];
for (let y = 0; y < N; y++) {
  px[y * (N * 4 + 1)] = 0;
  for (let x = 0; x < N; x++) {
    const o = y * (N * 4 + 1) + 1 + x * 4;
    const d = Math.hypot(x - 63.5, y - 63.5);
    const bite = Math.hypot(x - 112, y - 22) < 22;
    let c = [0, 0, 0, 0];
    if (d < 58 && !bite) c = chips.some(([cx, cy]) => Math.hypot(x - cx, y - cy) < 8) ? [70, 40, 20, 255] : [214, 160, 90, 255];
    px.set(c, o);
  }
}
const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
const crc = (b) => {
  let c = 0xffffffff;
  for (const v of b) c = crcTable[(c ^ v) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};
const chunk = (type, data) => {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const c = Buffer.alloc(4);
  c.writeUInt32BE(crc(td));
  return Buffer.concat([len, td, c]);
};
const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(N, 0);
ihdr.writeUInt32BE(N, 4);
ihdr.set([8, 6, 0, 0, 0], 8);
mkdirSync('static/icons', { recursive: true });
writeFileSync('static/icons/icon128.png', Buffer.concat([
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
  chunk('IHDR', ihdr),
  chunk('IDAT', deflateSync(px)),
  chunk('IEND', Buffer.alloc(0)),
]));

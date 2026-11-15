// Unit tests for frontend/geo.js.   Run:  node tests/frontend/geo.test.js
const assert = require('assert');
const G = require('../../frontend/geo.js');
let passed = 0, failed = 0;
function test(name, fn) { try { fn(); passed++; console.log('PASS  ' + name); } catch (e) { failed++; console.log('FAIL  ' + name + '\n      -> ' + e.message); } }
const near = (a, b, tol, msg) => assert.ok(Math.abs(a - b) <= tol, (msg || '') + ' expected ' + b + ' got ' + a);

test('lon/lat <-> cell: corners, centre, round trip', () => {
  assert.deepStrictEqual(G.lonLatToCell(45, 30), { x: 0, y: 0 });
  assert.deepStrictEqual(G.lonLatToCell(90, 0), { x: 1440, y: 960 });
  const c = G.lonLatToCell(62.5, 12.25), p = G.cellToLonLat(c.x, c.y);
  near(p.lon, 62.5, 1e-12); near(p.lat, 12.25, 1e-12);
  near(c.x, 560, 1e-9); near(c.y, 568, 1e-9);
  assert.ok(G.inBounds(45, 0) && G.inBounds(90, 30) && !G.inBounds(44.9, 10) && !G.inBounds(60, 30.1));
  assert.ok(G.cellInBounds(0, 0) && G.cellInBounds(1439.9, 959.9) && !G.cellInBounds(1440, 0) && !G.cellInBounds(-0.1, 5));
  near(G.GRID.cellM, 1852.334, 0.01, 'cell size (matches the Java backend)');
});
test('view: fit, screen <-> cell, zoom keeps the point under the cursor fixed, pan is clamped', () => {
  const v = G.fitView(1000, 700);
  near(v.k, Math.min(1000 / 1440, 700 / 960), 1e-12);
  const c = G.screenToCell(v, 400, 300), s = G.cellToScreen(v, c.x, c.y);
  near(s.x, 400, 1e-9); near(s.y, 300, 1e-9);
  const z = G.zoomAt(v, 3, 400, 300, 1000, 700), c2 = G.screenToCell(z, 400, 300);
  near(c2.x, c.x, 1e-6, 'x under cursor'); near(c2.y, c.y, 1e-6, 'y under cursor');
  assert.ok(z.k > v.k);
  const out = G.zoomAt(v, 0.01, 10, 10, 1000, 700);
  near(out.k, v.fitK, 1e-12, 'cannot zoom out past fit');
  const far = G.zoomAt(v, 1000, 500, 350, 1000, 700);
  assert.ok(far.k <= 14, 'max zoom');
  const panned = G.clampView({ k: z.k, tx: 5000, ty: -9000, fitK: v.fitK }, 1000, 700);
  assert.ok(panned.tx <= 60 && panned.ty >= 700 - 960 * z.k - 60, 'pan stays near the map');
});
test('formatting is null-safe and uses a real minus sign', () => {
  assert.strictEqual(G.fmtInt(null), '\u2014'); assert.strictEqual(G.fmtKm(undefined), '\u2014'); assert.strictEqual(G.fmtDeg(NaN), '\u2014');
  assert.strictEqual(G.fmtInt(-1250), '\u22121,250'); assert.strictEqual(G.fmtKm(3512), '3.5 km'); assert.strictEqual(G.fmtDeg(4.24), '4.2\u00B0');
  assert.strictEqual(G.fmtLonLat(62.3414, 12.8741), '62.341\u00B0E 12.874\u00B0N');
});
test('hillshade: flat = 1, lit slope brighter than shadowed slope, strength increases contrast, data untouched', () => {
  const cols = 20, rows = 20, flat = new Int16Array(cols * rows);
  const hs = G.hillshade(flat, cols, rows, 3);
  assert.ok(hs.every(v => Math.abs(v - 1) < 1e-6), 'flat terrain should not be shaded');
  // terrain rising toward the SE: its NW-facing side is lit by the NW sun, the opposite slope is in shadow
  const ramp = new Int16Array(cols * rows);
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) ramp[r * cols + c] = 200 * (c + r);
  const copy = Int16Array.from(ramp);
  const lit = G.hillshade(ramp, cols, rows, 1)[10 * cols + 10];
  const rev = new Int16Array(cols * rows); for (let i = 0; i < rev.length; i++) rev[i] = -ramp[i];
  const dark = G.hillshade(rev, cols, rows, 1)[10 * cols + 10];
  assert.ok(lit > 1 && dark < 1, 'lit ' + lit + ' dark ' + dark);
  const lit3 = G.hillshade(ramp, cols, rows, 3)[10 * cols + 10];
  assert.ok(Math.abs(lit3 - 1) >= Math.abs(lit - 1) - 1e-9, 'higher strength = at least as much contrast');
  assert.deepStrictEqual(ramp, copy, 'hillshade must never modify the elevation data');
});
test('colour ramps: endpoints and monotone sanity', () => {
  assert.deepStrictEqual(G.hypsometric(-9999), [27, 42, 107]); assert.deepStrictEqual(G.hypsometric(9999), [243, 238, 232]);
  assert.deepStrictEqual(G.slopeColor(0), [28, 43, 58]); assert.deepStrictEqual(G.slopeColor(90), [106, 4, 15]);
  const mid = G.slopeColor(3.5); assert.ok(mid.every(v => v >= 0 && v <= 255));
});
test('route helpers: cumulative length, point along, anchor angle', () => {
  const cells = [{ x: 0, y: 0 }, { x: 10, y: 0 }, { x: 10, y: 10 }], cum = G.cumulative(cells);
  assert.deepStrictEqual(Array.from(cum), [0, 10, 20]);
  const p = G.pointAlong(cells, cum, 15); near(p.x, 10, 1e-9); near(p.y, 5, 1e-9); assert.strictEqual(p.i, 1);
  const a = G.anchorAtDistance(cells, cum, 2000, 500);   // 25% along = 5 cells east
  near(a.x, 5, 1e-9); near(a.y, 0, 1e-9); near(a.angle, 0, 1e-9, 'heading east');
  const b = G.anchorAtDistance(cells, cum, 2000, 1500);  // 75% along = 5 cells south
  near(b.angle, Math.PI / 2, 1e-9, 'heading south (y grows downward)');
});
test('label placement: no overlaps, stays on screen, skips what cannot fit', () => {
  const anchors = [];
  for (let i = 0; i < 12; i++) anchors.push({ x: 300 + i * 6, y: 300, angle: 0, w: 120, h: 50 });   // 12 labels crammed on one spot
  const placed = G.placeLabels(anchors, 800, 600, []);
  assert.ok(placed.length >= 1 && placed.length < 12, 'placed ' + placed.length);
  for (let i = 0; i < placed.length; i++) {
    const r = placed[i].rect; assert.ok(r.x >= 0 && r.y >= 0 && r.x + r.w <= 800 && r.y + r.h <= 600, 'on screen');
    for (let j = i + 1; j < placed.length; j++) assert.ok(!G.rectsOverlap(r, placed[j].rect, 0), 'labels ' + i + ' and ' + j + ' overlap');
  }
  const blocked = G.placeLabels([{ x: 400, y: 300, angle: 0, w: 120, h: 50 }], 800, 600, [{ x: 0, y: 0, w: 800, h: 600 }]);
  assert.strictEqual(blocked.length, 0, 'an obstacle covering everything leaves no room');
  const edge = G.placeLabels([{ x: 2, y: 2, angle: 0, w: 120, h: 50 }], 800, 600, []);
  edge.forEach(p => assert.ok(p.rect.x >= 6 && p.rect.y >= 6));
});
console.log('\n' + passed + ' passed, ' + failed + ' failed');
process.exit(failed ? 1 : 0);

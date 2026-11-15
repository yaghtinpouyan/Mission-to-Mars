/*
 * Pure helper functions for the Marswalk Planner map (no DOM access, so they can be unit-tested in Node).
 *
 * Coordinate conventions - the same as the Java backend:
 *   - "cell space": x = fractional column, y = fractional row on the 1,440 x 960 navigation grid.
 *     x = 0 is the WEST edge (45 E), y = 0 is the NORTH edge (30 N). Cell centres are at +0.5.
 *   - longitude is degrees east, latitude is degrees north.
 *   - "screen space": canvas pixels. screen = cell * k + (tx, ty).
 */
(function (root) {
  'use strict';

  const GRID = {
    cols: 1440, rows: 960,
    west: 45, east: 90, south: 0, north: 30,
    degPerCell: 1 / 32,
    radius: 3396190,                       // metres, from the data's .prj file
  };
  GRID.cellM = GRID.radius * Math.PI / 180 * GRID.degPerCell;   // north-south ground size of a cell (~1,852 m)

  // ---------- coordinates ----------
  function lonLatToCell(lon, lat) { return { x: (lon - GRID.west) / GRID.degPerCell, y: (GRID.north - lat) / GRID.degPerCell }; }
  function cellToLonLat(x, y) { return { lon: GRID.west + x * GRID.degPerCell, lat: GRID.north - y * GRID.degPerCell }; }
  function inBounds(lon, lat) { return lon >= GRID.west && lon <= GRID.east && lat >= GRID.south && lat <= GRID.north; }
  function cellInBounds(x, y) { return x >= 0 && y >= 0 && x < GRID.cols && y < GRID.rows; }

  // ---------- view (pan / zoom) ----------
  function fitView(w, h) {
    const k = Math.min(w / GRID.cols, h / GRID.rows);
    return { k, tx: (w - GRID.cols * k) / 2, ty: (h - GRID.rows * k) / 2, fitK: k };
  }
  function cellToScreen(v, x, y) { return { x: x * v.k + v.tx, y: y * v.k + v.ty }; }
  function screenToCell(v, sx, sy) { return { x: (sx - v.tx) / v.k, y: (sy - v.ty) / v.k }; }
  function clampView(v, w, h) {
    const mw = GRID.cols * v.k, mh = GRID.rows * v.k, pad = 60;
    const out = { k: v.k, tx: v.tx, ty: v.ty, fitK: v.fitK };
    out.tx = mw <= w ? (w - mw) / 2 : Math.min(pad, Math.max(w - mw - pad, v.tx));
    out.ty = mh <= h ? (h - mh) / 2 : Math.min(pad, Math.max(h - mh - pad, v.ty));
    return out;
  }
  /** Zoom by `factor` keeping the cell under screen point (sx, sy) fixed. */
  function zoomAt(v, factor, sx, sy, w, h) {
    const maxK = 14;
    const k = Math.max(v.fitK, Math.min(maxK, v.k * factor));
    const c = screenToCell(v, sx, sy);
    return clampView({ k, tx: sx - c.x * k, ty: sy - c.y * k, fitK: v.fitK }, w, h);
  }

  // ---------- number formatting (null-safe) ----------
  function isNum(v) { return typeof v === 'number' && isFinite(v); }
  function fmtInt(v) { return isNum(v) ? Math.round(v).toLocaleString('en-US').replace('-', '\u2212') : '\u2014'; }
  function fmtKm(m) { return isNum(m) ? (m / 1000).toLocaleString('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + ' km' : '\u2014'; }
  function fmtM(m) { return isNum(m) ? fmtInt(m) + ' m' : '\u2014'; }
  function fmtDeg(d, dp) { return isNum(d) ? d.toFixed(dp == null ? 1 : dp) + '\u00B0' : '\u2014'; }
  function fmtLonLat(lon, lat) {
    return Math.abs(lon).toFixed(3) + '\u00B0' + (lon >= 0 ? 'E' : 'W') + ' ' + Math.abs(lat).toFixed(3) + '\u00B0' + (lat >= 0 ? 'N' : 'S');
  }

  // ---------- colour ramps ----------
  const HYPSO = [[-5000, [27, 42, 107]], [-3500, [36, 87, 184]], [-2000, [47, 156, 207]], [-500, [82, 199, 176]], [0, [123, 212, 122]],
                 [500, [201, 217, 106]], [1500, [224, 176, 74]], [2500, [184, 115, 51]], [3300, [243, 238, 232]]];
  const SLOPE = [[0, [28, 43, 58]], [2, [45, 106, 79]], [5, [149, 213, 178]], [8, [255, 209, 102]], [12, [244, 132, 95]], [18, [214, 40, 40]], [25, [106, 4, 15]]];
  function ramp(stops, v) {
    if (v <= stops[0][0]) return stops[0][1];
    for (let i = 1; i < stops.length; i++) {
      if (v <= stops[i][0]) {
        const a = stops[i - 1], b = stops[i], f = (v - a[0]) / (b[0] - a[0]);
        return [a[1][0] + (b[1][0] - a[1][0]) * f, a[1][1] + (b[1][1] - a[1][1]) * f, a[1][2] + (b[1][2] - a[1][2]) * f];
      }
    }
    return stops[stops.length - 1][1];
  }
  const hypsometric = z => ramp(HYPSO, z);
  const slopeColor = d => ramp(SLOPE, d);

  // ---------- hillshade (visual only) ----------
  /**
   * Relief shading from elevation. `strength` controls display contrast only; it never changes the elevation data.
   * elev: Int16Array (metres, -32768 = no data), row-major, row 0 = north. Returns Float32Array of brightness multipliers.
   */
  function hillshade(elev, cols, rows, strength, opts) {
    const o = Object.assign({ azimuthDeg: 315, altitudeDeg: 45, cellM: GRID.cellM, north: GRID.north, degPerCell: GRID.degPerCell }, opts || {});
    const az = o.azimuthDeg * Math.PI / 180, alt = o.altitudeDeg * Math.PI / 180;
    const Le = Math.sin(az) * Math.cos(alt), Ln = Math.cos(az) * Math.cos(alt), Lu = Math.sin(alt);
    const out = new Float32Array(cols * rows);
    const at = (c, r) => { c = c < 0 ? 0 : c >= cols ? cols - 1 : c; r = r < 0 ? 0 : r >= rows ? rows - 1 : r; return elev[r * cols + c]; };
    for (let r = 0; r < rows; r++) {
      const lat = o.north - (r + 0.5) * o.degPerCell;
      const dx = o.cellM * Math.cos(lat * Math.PI / 180), dy = o.cellM;      // east-west ground spacing shrinks with latitude
      for (let c = 0; c < cols; c++) {
        const a = at(c - 1, r - 1), b = at(c, r - 1), cc = at(c + 1, r - 1), d = at(c - 1, r), f = at(c + 1, r), g = at(c - 1, r + 1), h = at(c, r + 1), i = at(c + 1, r + 1);
        const gE = strength * ((cc + 2 * f + i) - (a + 2 * d + g)) / (8 * dx);
        const gN = strength * ((a + 2 * b + cc) - (g + 2 * h + i)) / (8 * dy);
        const inv = 1 / Math.sqrt(gE * gE + gN * gN + 1);
        const shade = Math.max(0, (-gE * Le - gN * Ln + Lu) * inv);
        const m = 1 + 1.4 * (shade - Lu);
        out[r * cols + c] = m < 0.25 ? 0.25 : m > 1.6 ? 1.6 : m;
      }
    }
    return out;
  }

  // ---------- route helpers ----------
  /** cumulative length of a polyline in cell units */
  function cumulative(cells) {
    const cum = new Float64Array(cells.length);
    for (let i = 1; i < cells.length; i++) cum[i] = cum[i - 1] + Math.hypot(cells[i].x - cells[i - 1].x, cells[i].y - cells[i - 1].y);
    return cum;
  }
  /** point at fraction t (0..1) of the polyline, plus the index of the last vertex passed */
  function pointAlong(cells, cum, dist) {
    if (dist <= 0) return { x: cells[0].x, y: cells[0].y, i: 0 };
    const total = cum[cum.length - 1];
    if (dist >= total) return { x: cells[cells.length - 1].x, y: cells[cells.length - 1].y, i: cells.length - 1 };
    let lo = 0, hi = cells.length - 1;
    while (hi - lo > 1) { const mid = (lo + hi) >> 1; if (cum[mid] <= dist) lo = mid; else hi = mid; }
    const f = (dist - cum[lo]) / ((cum[hi] - cum[lo]) || 1);
    return { x: cells[lo].x + (cells[hi].x - cells[lo].x) * f, y: cells[lo].y + (cells[hi].y - cells[lo].y) * f, i: lo };
  }
  /** Position and direction on the route at a given real-world distance (metres) - used to anchor the labels. */
  function anchorAtDistance(cells, cum, totalM, dM) {
    const p = pointAlong(cells, cum, cum[cum.length - 1] * (dM / totalM));
    const j = Math.min(cells.length - 1, p.i + 1), i0 = Math.max(0, j - 1);
    return { x: p.x, y: p.y, angle: Math.atan2(cells[j].y - cells[i0].y, cells[j].x - cells[i0].x) };
  }

  // ---------- label placement (avoid overlap, stay on screen) ----------
  function rectsOverlap(a, b, gap) { return !(a.x + a.w + gap <= b.x || b.x + b.w + gap <= a.x || a.y + a.h + gap <= b.y || b.y + b.h + gap <= a.y); }
  /**
   * anchors: [{x, y, angle, w, h}] in screen pixels, in priority order.
   * Tries a position at each side of the route; skips a label if every candidate collides or leaves the screen.
   * `obstacles` are rectangles that must stay clear (e.g. the markers).
   */
  function placeLabels(anchors, viewW, viewH, obstacles) {
    const placed = [], rects = (obstacles || []).slice();
    for (const a of anchors) {
      const nx = -Math.sin(a.angle), ny = Math.cos(a.angle);       // unit normal to the route
      const d = 26;
      const cand = [[nx, ny], [-nx, -ny], [nx * 1.9, ny * 1.9], [-nx * 1.9, -ny * 1.9]];
      for (const [cx, cy] of cand) {
        const ccx = a.x + cx * d, ccy = a.y + cy * d;
        const rect = { x: ccx - a.w / 2, y: ccy - a.h / 2, w: a.w, h: a.h };
        if (rect.x < 6 || rect.y < 6 || rect.x + rect.w > viewW - 6 || rect.y + rect.h > viewH - 6) continue;
        if (rects.some(r => rectsOverlap(rect, r, 4))) continue;
        rects.push(rect);
        placed.push({ anchor: a, rect });
        break;
      }
    }
    return placed;
  }

  const api = { GRID, lonLatToCell, cellToLonLat, inBounds, cellInBounds, fitView, cellToScreen, screenToCell, clampView, zoomAt,
    isNum, fmtInt, fmtKm, fmtM, fmtDeg, fmtLonLat, hypsometric, slopeColor, hillshade, cumulative, pointAlong, anchorAtDistance, rectsOverlap, placeLabels };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.MarsGeo = api;
})(typeof window !== 'undefined' ? window : globalThis);

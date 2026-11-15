// Marswalk Planner: interactive MC-13 terrain route planner.
// The page only does the map and the display. All route finding happens in the Java backend (see /api/*).
// Pure maths (coordinates, zoom, shading, label placement) lives in geo.js.
(function () {
  'use strict';
  const G = MarsGeo;
  const COLS = G.GRID.cols, ROWS = G.GRID.rows;
  const API = (new URLSearchParams(location.search).get('api') || '').replace(/\/$/, '');   // e.g. ?api=http://localhost:8080 when using Live Server
  const COLORS = { general: '#ffb000', optimized: '#00e5ff' };
  const $ = id => document.getElementById(id);

  const el = {
    viewer: $('mapArea'), map: $('map'), status: $('status'), steps: $('steps'), reset: $('reset'), recalc: $('recalc'),
    ghost: $('ghost'), layer: $('layer'), exag: $('exag'), exagVal: $('exagVal'), exagNote: $('exagNote'),
    summary: $('summary'), summaryFor: $('summaryFor'), profile: $('profile'), legend: $('legend'), readout: $('readout'),
    busy: $('busy'), busyText: $('busyText'), fatal: $('fatal'), views: document.getElementsByName('routeview'),
  };
  const ctx = el.map.getContext('2d');

  const S = {
    info: null, elev: null, slope: null, colorImg: null,
    layer: 'color', exag: 2,
    view: null, w: 900, h: 640,
    start: null, end: null,               // {lon, lat}
    general: null, optimized: null,       // see makeRoute()
    show: 'general', ghost: false,
    busy: null, ctrl: null,
    pulse: { start: -1e9, end: -1e9 },
    drawAnim: { route: null, t0: 0, dur: 1000 },
    hover: null, rafPending: false,
  };

  // ------------------------------------------------------------------ network
  async function getJSON(path, signal) {
    const r = await fetch(API + path, { signal });
    let body = null;
    try { body = await r.json(); } catch (_) { /* not JSON */ }
    if (!r.ok) {
      const e = new Error((body && body.message) || ('Server answered HTTP ' + r.status));
      e.status = r.status; e.code = body && body.error;
      throw e;
    }
    return body;
  }
  async function getGrid(path) {
    const r = await fetch(API + path);
    if (!r.ok) throw new Error('Could not load ' + path);
    const buf = await r.arrayBuffer(), dv = new DataView(buf), out = new Int16Array(buf.byteLength / 2);
    for (let i = 0; i < out.length; i++) out[i] = dv.getInt16(i * 2, false);      // the server sends big-endian 16-bit values
    return out;
  }
  function loadImage(url) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => reject(new Error('Could not load the color map image'));
      img.src = url;
    });
  }

  // ------------------------------------------------------------------ small UI helpers
  function say(text, kind) { el.status.textContent = text; el.status.className = 'status' + (kind ? ' ' + kind : ''); }
  function setBusy(text) {
    S.busy = text;
    el.busy.hidden = !text;
    el.busyText.textContent = text || '';
    updateSteps();
    invalidate();
  }
  function updateSteps() {
    const set = (name, cls) => { const li = el.steps.querySelector('[data-step="' + name + '"]'); li.className = cls || ''; };
    set('start', S.start ? 'done' : 'active');
    set('end', S.end ? 'done' : (S.start ? 'active' : ''));
    const genBusy = S.busy && !S.general, optBusy = S.busy && S.general && !S.optimized;
    set('general', S.general ? 'done' : (genBusy ? 'active' : ''));
    set('optimized', S.optimized ? 'done' : (optBusy ? 'active' : ''));
    el.recalc.disabled = !(S.start && S.end) || !!S.busy;
  }
  function visibleRoute() { return (S.show === 'optimized' && S.optimized) ? S.optimized : S.general; }
  function syncToggle() {
    for (const r of el.views) {
      r.disabled = r.value === 'general' ? !S.general : !S.optimized;
      r.checked = r.value === (visibleRoute() === S.optimized && S.optimized ? 'optimized' : 'general');
    }
    el.ghost.disabled = !(S.general && S.optimized);
  }

  // ------------------------------------------------------------------ map layers
  const shadeCache = new Map(), layerCache = new Map();
  function getShade(z) {
    if (!shadeCache.has(z)) shadeCache.set(z, G.hillshade(S.elev, COLS, ROWS, z));
    return shadeCache.get(z);
  }
  function layerCanvas(name) {
    const key = name + ':' + S.exag;
    if (layerCache.has(key)) return layerCache.get(key);
    if (layerCache.size > 8) layerCache.clear();
    const shade = getShade(S.exag), cv = document.createElement('canvas');
    cv.width = COLS; cv.height = ROWS;
    const c2 = cv.getContext('2d'), img = c2.createImageData(COLS, ROWS), d = img.data;
    for (let i = 0; i < COLS * ROWS; i++) {
      let rgb, m = shade[i];
      if (name === 'elevation') { const z = S.elev[i]; rgb = z === -32768 ? [90, 90, 90] : G.hypsometric(z); }
      else { const s = S.slope[i]; rgb = s === -32768 ? [90, 90, 90] : G.slopeColor(s / 100); m = 1 + 0.5 * (m - 1); }
      d[i * 4] = rgb[0] * m; d[i * 4 + 1] = rgb[1] * m; d[i * 4 + 2] = rgb[2] * m; d[i * 4 + 3] = 255;
    }
    c2.putImageData(img, 0, 0);
    layerCache.set(key, cv);
    return cv;
  }

  // ------------------------------------------------------------------ routes
  function makeRoute(json, kind) {
    const pts = json.route.points, cells = pts.map(p => G.lonLatToCell(p[0], p[1]));
    return { kind, id: json.id, points: pts, cells, cum: G.cumulative(cells), stats: json.route.stats, meta: json.meta,
             generalOnHires: json.generalOnHires || null, adjustments: json.adjustments || null };
  }
  function costQuery() {
    const p = new URLSearchParams();
    p.set('wSlope', $('wSlope').value);
    p.set('wClimb', $('wClimb').value);
    if ($('useMinElev').checked) p.set('minElev', $('minElev').value);
    if ($('useMaxSlope').checked) p.set('maxSlope', $('maxSlope').value);
    return p;
  }
  function cancel() { if (S.ctrl) S.ctrl.abort(); S.ctrl = null; }

  async function compute() {
    if (!(S.start && S.end)) return;
    cancel();
    S.ctrl = new AbortController();
    const signal = S.ctrl.signal;
    S.general = null; S.optimized = null; S.show = 'general';
    syncToggle(); updateAll();
    try {
      setBusy('Finding the general route…');
      const q = costQuery();
      q.set('startLon', S.start.lon); q.set('startLat', S.start.lat); q.set('endLon', S.end.lon); q.set('endLat', S.end.lat);
      const g = await getJSON('/api/route/general?' + q, signal);
      S.general = makeRoute(g, 'general');
      S.show = 'general';
      startDrawAnim(S.general);
      syncToggle(); updateAll();
      if (!g.hiresAvailable) {
        setBusy(null);
        say('General route ready. The high-resolution terrain data is not installed on the server, so there is no optimized route yet.', 'warn');
        return;
      }
      setBusy('Optimizing with 463 m terrain data…');
      say('General route ready. Now refining it with the high-resolution terrain…');
      try {
        const o = await getJSON('/api/route/refine?id=' + encodeURIComponent(g.id) + '&maxRadiusM=' + $('width').value, signal);
        S.optimized = makeRoute(o, 'optimized');
        S.show = 'optimized';
        startDrawAnim(S.optimized);
        setBusy(null);
        say('Optimized route ready. Use "Route shown" to compare it with the general route.');
      } catch (e) {
        if (e.name === 'AbortError') throw e;
        setBusy(null);
        say('Showing the general route only. ' + e.message, 'warn');
      }
      syncToggle(); updateAll();
    } catch (e) {
      if (e.name === 'AbortError') return;
      setBusy(null);
      say(e.status ? e.message : 'Could not reach the backend. Is it still running?', 'error');
      updateAll();
    }
  }

  function startDrawAnim(route) { S.drawAnim = { route, t0: performance.now(), dur: 1000 }; invalidate(); }

  function resetSelection() {
    cancel();
    S.start = S.end = S.general = S.optimized = null; S.show = 'general';
    setBusy(null);
    syncToggle(); updateAll();
    say('Click the map to choose a start point.');
  }

  // ------------------------------------------------------------------ summary, legend, profile
  const row = (k, v, cls) => '<div class="row' + (cls ? ' ' + cls : '') + '"><span>' + k + '</span><b>' + v + '</b></div>';
  function summaryHTML(r) {
    const s = r.stats;
    let h = row('Distance', G.fmtKm(s.distanceM));
    h += row('Straight-line distance', G.fmtKm(s.straightLineM) + (G.isNum(s.detourRatio) ? ' (route is ' + s.detourRatio.toFixed(2) + '×)' : ''));
    h += row('Start elevation', G.fmtM(s.startElevM)) + row('Destination elevation', G.fmtM(s.endElevM));
    h += row('Elevation gain', G.fmtM(s.elevGainM));
    h += row('Elevation loss', G.fmtM(s.elevLossM));
    h += row('Lowest / highest point', G.fmtM(s.minElevM) + ' / ' + G.fmtM(s.maxElevM));
    h += row('Average slope along route', G.fmtDeg(s.avgSlopeDeg, 2));
    h += row('Maximum slope along route', G.fmtDeg(s.maxSlopeDeg, 1));
    h += row('Steepest terrain crossed', G.fmtDeg(s.maxTerrainSlopeDeg, 1));
    h += row('Steep sections (≥ ' + G.fmtDeg(s.steepThresholdDeg, 0) + ')', G.fmtInt(s.steepSections) + (s.steepSections ? ' · ' + G.fmtKm(s.steepLengthM) : ''));
    if (r.adjustments) h += row('Detours from general route', G.fmtInt(r.adjustments.count) + ' · max ' + G.fmtM(r.adjustments.maxDeviationM), '');
    h += row('Measured on ' + G.fmtInt(s.sampleSpacingM) + ' m cells', s.unknownSlopeSamples ? G.fmtInt(s.unknownSlopeSamples) + ' samples without slope data' : 'complete data', 'sub');
    if (r.generalOnHires) {
      const a = r.generalOnHires, b = s;
      h += '<div class="cmp"><h3>Same 463 m terrain: general route → optimized</h3>' +
        row('Distance', G.fmtKm(a.distanceM) + ' → ' + G.fmtKm(b.distanceM)) +
        row('Maximum slope along route', G.fmtDeg(a.maxSlopeDeg, 1) + ' → ' + G.fmtDeg(b.maxSlopeDeg, 1)) +
        row('Steepest terrain crossed', G.fmtDeg(a.maxTerrainSlopeDeg, 1) + ' → ' + G.fmtDeg(b.maxTerrainSlopeDeg, 1)) +
        row('Steep sections', G.fmtInt(a.steepSections) + ' → ' + G.fmtInt(b.steepSections)) + '</div>';
    }
    return h;
  }
  function updateSummary() {
    const r = visibleRoute();
    if (!r) { el.summary.className = 'muted'; el.summary.textContent = 'Choose a start and destination to see route details.'; el.summaryFor.textContent = ''; el.profile.style.display = 'none'; return; }
    el.summary.className = '';
    el.summary.innerHTML = summaryHTML(r);
    el.summaryFor.textContent = r.kind === 'optimized' ? 'optimized' : 'general';
    drawProfile(r);
  }
  function updateLegend() {
    const r = visibleRoute();
    if (!r && !S.start) { el.legend.hidden = true; return; }
    el.legend.hidden = false;
    let h = '';
    if (r) h += '<div class="li"><i class="sw" style="background:' + COLORS[r.kind] + '"></i><span>' +
      (r.kind === 'optimized' ? '<b>Optimized route</b> · refined on 463 m terrain' : '<b>General route</b> · 1,852 m terrain grid') + '</span></div>';
    if (S.start) h += '<div class="li"><i class="dot" style="background:#2ecc71"></i><span>Start</span></div>';
    if (S.end) h += '<div class="li"><i class="dot" style="background:#e74c3c"></i><span>Destination</span></div>';
    el.legend.innerHTML = h;
  }
  function drawProfile(r) {
    const cv = el.profile, c = cv.getContext('2d'), W = cv.width, H = cv.height, prof = r.stats.profile || [];
    cv.style.display = 'block';
    c.clearRect(0, 0, W, H);
    const pts = prof.filter(p => G.isNum(p[1]));
    if (pts.length < 2) return;
    let lo = Infinity, hi = -Infinity;
    for (const p of pts) { lo = Math.min(lo, p[1]); hi = Math.max(hi, p[1]); }
    if (hi - lo < 20) { const m = (hi + lo) / 2; lo = m - 10; hi = m + 10; }
    const total = prof[prof.length - 1][0] || 1, L = 6, R = W - 6, T = 14, B = H - 16;
    const X = d => L + (R - L) * d / total, Y = e => B - (B - T) * (e - lo) / (hi - lo);
    c.beginPath(); c.moveTo(X(pts[0][0]), B);
    for (const p of pts) c.lineTo(X(p[0]), Y(p[1]));
    c.lineTo(X(pts[pts.length - 1][0]), B); c.closePath();
    c.fillStyle = COLORS[r.kind] + '33'; c.fill();
    c.beginPath();
    pts.forEach((p, i) => i ? c.lineTo(X(p[0]), Y(p[1])) : c.moveTo(X(p[0]), Y(p[1])));
    c.strokeStyle = COLORS[r.kind]; c.lineWidth = 1.5; c.stroke();
    c.fillStyle = '#a89588'; c.font = '10px system-ui';
    c.fillText(G.fmtM(hi), L, 10); c.fillText(G.fmtM(lo), L, H - 4);
    c.textAlign = 'right'; c.fillText(G.fmtKm(total), R, H - 4); c.textAlign = 'left';
  }
  function updateAll() { updateSteps(); updateSummary(); updateLegend(); syncToggle(); invalidate(); }

  // ------------------------------------------------------------------ drawing
  function invalidate() {
    if (S.rafPending) return;
    S.rafPending = true;
    requestAnimationFrame(frame);
  }
  function animating(now) {
    return (now - S.pulse.start < 800) || (now - S.pulse.end < 800) || (S.drawAnim.route && now - S.drawAnim.t0 < S.drawAnim.dur + 50) || !!S.busy;
  }
  function frame(now) {
    S.rafPending = false;
    now = now || performance.now();
    draw(now);
    if (animating(now)) invalidate();
  }

  function trace(route, limit) {
    const v = S.view, c = route.cells, n = c.length;
    ctx.beginPath();
    let p = G.cellToScreen(v, c[0].x, c[0].y);
    ctx.moveTo(p.x, p.y);
    for (let i = 1; i < n; i++) {
      if (route.cum[i] <= limit) { p = G.cellToScreen(v, c[i].x, c[i].y); ctx.lineTo(p.x, p.y); }
      else {
        const f = (limit - route.cum[i - 1]) / ((route.cum[i] - route.cum[i - 1]) || 1);
        p = G.cellToScreen(v, c[i - 1].x + (c[i].x - c[i - 1].x) * f, c[i - 1].y + (c[i].y - c[i - 1].y) * f);
        ctx.lineTo(p.x, p.y);
        break;
      }
    }
  }
  function strokeRoute(route, color, opts) {
    const total = route.cum[route.cum.length - 1], limit = total * (opts.progress == null ? 1 : opts.progress);
    ctx.save();
    ctx.lineJoin = 'round'; ctx.lineCap = 'round'; ctx.globalAlpha = opts.alpha == null ? 1 : opts.alpha;
    ctx.setLineDash(opts.dash || []); ctx.lineDashOffset = opts.dashOffset || 0;
    trace(route, limit); ctx.strokeStyle = 'rgba(0,0,0,0.65)'; ctx.lineWidth = (opts.width || 3) + 3; ctx.stroke();
    trace(route, limit); ctx.strokeStyle = color; ctx.lineWidth = opts.width || 3; ctx.stroke();
    ctx.restore();
  }
  function marker(pt, letter, color, startedAt, now) {
    const p = G.cellToScreen(S.view, pt.x, pt.y);
    const t = (now - startedAt) / 700;
    ctx.save();
    if (t >= 0 && t < 1) {                                     // expanding ring when a point is placed
      ctx.globalAlpha = 1 - t; ctx.beginPath(); ctx.arc(p.x, p.y, 10 + 26 * t, 0, Math.PI * 2);
      ctx.strokeStyle = color; ctx.lineWidth = 3; ctx.stroke(); ctx.globalAlpha = 1;
    }
    ctx.beginPath(); ctx.arc(p.x, p.y, 10, 0, Math.PI * 2);
    ctx.fillStyle = color; ctx.fill(); ctx.lineWidth = 2.5; ctx.strokeStyle = '#fff'; ctx.stroke();
    ctx.fillStyle = '#fff'; ctx.font = 'bold 11px system-ui'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText(letter, p.x, p.y + 0.5);
    ctx.restore();
    return { x: p.x - 16, y: p.y - 16, w: 32, h: 32 };
  }
  function drawLabels(route, now, obstacles) {
    const labels = route.stats.labels || [];
    if (!labels.length) return;
    ctx.save();
    ctx.font = '11px system-ui';
    const anchors = [];
    for (const l of labels) {
      const c = G.lonLatToCell(l.lon, l.lat), p = G.cellToScreen(S.view, c.x, c.y);
      if (p.x < 0 || p.y < 0 || p.x > S.w || p.y > S.h) continue;
      const lines = ['Elevation: ' + G.fmtM(l.elevM), 'Slope: ' + G.fmtDeg(l.slopeDeg, 1), 'Distance: ' + G.fmtKm(l.distanceM)];
      const a = G.anchorAtDistance(route.cells, route.cum, route.stats.distanceM, l.distanceM);
      anchors.push({ x: p.x, y: p.y, angle: a.angle, w: Math.max(...lines.map(t => ctx.measureText(t).width)) + 14, h: 3 * 14 + 8, lines });
    }
    const placed = G.placeLabels(anchors, S.w, S.h, obstacles);
    const color = COLORS[route.kind];
    for (const { anchor: a, rect: r } of placed) {
      ctx.beginPath(); ctx.moveTo(a.x, a.y);
      ctx.lineTo(Math.max(r.x, Math.min(a.x, r.x + r.w)), Math.max(r.y, Math.min(a.y, r.y + r.h)));
      ctx.strokeStyle = color; ctx.lineWidth = 1; ctx.stroke();
      ctx.beginPath(); ctx.arc(a.x, a.y, 3.5, 0, Math.PI * 2); ctx.fillStyle = '#fff'; ctx.fill(); ctx.strokeStyle = color; ctx.stroke();
      ctx.beginPath(); ctx.roundRect ? ctx.roundRect(r.x, r.y, r.w, r.h, 6) : ctx.rect(r.x, r.y, r.w, r.h);
      ctx.fillStyle = 'rgba(20,16,14,0.9)'; ctx.fill(); ctx.strokeStyle = color; ctx.stroke();
      ctx.fillStyle = '#eadfd6'; ctx.textBaseline = 'top'; ctx.textAlign = 'left';
      a.lines.forEach((t, i) => ctx.fillText(t, r.x + 7, r.y + 5 + i * 14));
    }
    ctx.restore();
  }
  function draw(now) {
    const dp = window.devicePixelRatio || 1;
    ctx.setTransform(dp, 0, 0, dp, 0, 0);
    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, S.w, S.h);
    if (!S.view || !S.elev) return;

    // base map
    const v = S.view;
    ctx.imageSmoothingEnabled = v.k < 4;
    ctx.drawImage(S.layer === 'color' ? S.colorImg : layerCanvas(S.layer), v.tx, v.ty, COLS * v.k, ROWS * v.k);

    // routes
    const vis = visibleRoute(), other = vis === S.optimized ? S.general : S.optimized;
    if (S.ghost && vis && other) strokeRoute(other, COLORS[other.kind], { alpha: 0.4, width: 2, dash: [6, 6] });
    if (S.busy && S.general) strokeRoute(S.general, '#ffffff', { alpha: 0.9, width: 2, dash: [10, 10], dashOffset: -(now / 40) % 20 });   // "scanning" ants while optimizing
    let progress = 1;
    if (vis && S.drawAnim.route === vis) { const t = Math.min(1, (now - S.drawAnim.t0) / S.drawAnim.dur); progress = 1 - Math.pow(1 - t, 3); }
    if (vis) strokeRoute(vis, COLORS[vis.kind], { width: 3.5, progress });

    // markers and labels
    const obstacles = [];
    if (S.start) obstacles.push(marker(G.lonLatToCell(S.start.lon, S.start.lat), 'A', '#2ecc71', S.pulse.start, now));
    if (S.end) obstacles.push(marker(G.lonLatToCell(S.end.lon, S.end.lat), 'B', '#e74c3c', S.pulse.end, now));
    if (vis && progress >= 1) drawLabels(vis, now, obstacles.map(o => ({ x: o.x - 6, y: o.y - 6, w: o.w + 12, h: o.h + 12 })));
  }

  // ------------------------------------------------------------------ view handling
  function resize() {
    const w = el.viewer.clientWidth || S.w, h = el.viewer.clientHeight || S.h, dp = window.devicePixelRatio || 1;
    S.w = w; S.h = h;
    el.map.width = Math.round(w * dp); el.map.height = Math.round(h * dp);
    const fit = G.fitView(w, h);
    S.view = S.view && S.view.k > fit.fitK ? G.clampView({ k: S.view.k, tx: S.view.tx, ty: S.view.ty, fitK: fit.fitK }, w, h) : fit;
    invalidate();
  }
  function zoom(factor, sx, sy) { S.view = G.zoomAt(S.view, factor, sx == null ? S.w / 2 : sx, sy == null ? S.h / 2 : sy, S.w, S.h); invalidate(); }
  function localXY(e) { const r = el.map.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }

  function onMapClick(sx, sy) {
    if (S.busy) { say('Please wait for the current calculation to finish (or press Clear).'); return; }
    const c = G.screenToCell(S.view, sx, sy), p = G.cellToLonLat(c.x, c.y);
    if (!G.cellInBounds(c.x, c.y)) { say('Click inside the MC-13 region (45–90°E, 0–30°N).', 'warn'); return; }
    if (S.end || (S.general && !S.start)) { S.start = S.end = S.general = S.optimized = null; S.show = 'general'; syncToggle(); }
    if (!S.start) {
      S.start = p; S.pulse.start = performance.now();
      say('Start set at ' + G.fmtLonLat(p.lon, p.lat) + '. Now click the destination.');
      updateAll();
    } else {
      S.end = p; S.pulse.end = performance.now();
      say('Destination set at ' + G.fmtLonLat(p.lon, p.lat) + '.');
      updateAll();
      compute();
    }
  }

  let drag = null;
  el.map.addEventListener('pointerdown', e => {
    const p = localXY(e);
    drag = { x: p.x, y: p.y, tx: S.view.tx, ty: S.view.ty, moved: false };
    try { el.map.setPointerCapture(e.pointerId); } catch (_) { /* not supported in some environments */ }
  });
  el.map.addEventListener('pointermove', e => {
    const p = localXY(e);
    if (drag) {
      if (Math.hypot(p.x - drag.x, p.y - drag.y) > 4) { drag.moved = true; el.map.classList.add('dragging'); }
      if (drag.moved) { S.view = G.clampView({ k: S.view.k, tx: drag.tx + p.x - drag.x, ty: drag.ty + p.y - drag.y, fitK: S.view.fitK }, S.w, S.h); invalidate(); }
    }
    updateReadout(p.x, p.y);
  });
  el.map.addEventListener('pointerup', e => {
    const p = localXY(e);
    if (drag && !drag.moved) onMapClick(p.x, p.y);
    drag = null; el.map.classList.remove('dragging');
  });
  el.map.addEventListener('pointercancel', () => { drag = null; el.map.classList.remove('dragging'); });
  el.map.addEventListener('pointerleave', () => { el.readout.textContent = 'Move the pointer over the map'; });
  el.map.addEventListener('wheel', e => { e.preventDefault(); const p = localXY(e); zoom(e.deltaY < 0 ? 1.25 : 0.8, p.x, p.y); }, { passive: false });

  function updateReadout(sx, sy) {
    const c = G.screenToCell(S.view, sx, sy);
    if (!G.cellInBounds(c.x, c.y)) { el.readout.textContent = 'Outside the MC-13 region'; return; }
    const p = G.cellToLonLat(c.x, c.y), i = Math.floor(c.y) * COLS + Math.floor(c.x), z = S.elev[i], s = S.slope[i];
    el.readout.textContent = G.fmtLonLat(p.lon, p.lat) + ' · Elevation ' + (z === -32768 ? 'n/a' : G.fmtM(z)) +
      ' · Slope ' + (s === -32768 ? 'n/a (map edge)' : G.fmtDeg(s / 100, 1));
  }

  // ------------------------------------------------------------------ controls
  const viewer = {
    setLayer(name) {
      S.layer = name; el.layer.value = name;
      el.exag.disabled = name === 'color';
      el.exagNote.textContent = name === 'color'
        ? 'The color map already has its shading built in, so exaggeration applies to the Elevation and Slope layers. It is a visual effect only.'
        : 'Visual shading only. It never changes routes, slopes or any measurement.';
      invalidate();
    },
    setExaggeration(n) { S.exag = n; el.exag.value = n; el.exagVal.textContent = n + '×'; invalidate(); },
    getState() { return S; },
  };
  window.viewer = viewer;       // other code (e.g. a future 3D view) can drive the same controls

  el.layer.addEventListener('change', () => viewer.setLayer(el.layer.value));
  el.exag.addEventListener('input', () => viewer.setExaggeration(Number(el.exag.value)));
  el.reset.addEventListener('click', resetSelection);
  el.recalc.addEventListener('click', compute);
  el.ghost.addEventListener('change', () => { S.ghost = el.ghost.checked; invalidate(); });
  for (const r of el.views) r.addEventListener('change', () => {
    if (!r.checked) return;
    S.show = r.value;
    const route = visibleRoute();
    startDrawAnim(route);
    updateAll();
    say(r.value === 'optimized' ? 'Showing the optimized route.' : 'Showing the general route.');
  });
  for (const [id, out] of [['wSlope', 'wSlopeVal'], ['wClimb', 'wClimbVal']]) $(id).addEventListener('input', () => { $(out).textContent = $(id).value; });
  $('zin').addEventListener('click', () => zoom(1.5));
  $('zout').addEventListener('click', () => zoom(1 / 1.5));
  $('zfit').addEventListener('click', () => { S.view = G.fitView(S.w, S.h); invalidate(); });
  window.addEventListener('keydown', e => { if (e.key === 'Escape') resetSelection(); });
  window.addEventListener('resize', resize);

  // ------------------------------------------------------------------ start
  async function boot() {
    resize();
    updateSteps(); viewer.setLayer('color'); viewer.setExaggeration(2);
    try {
      S.info = await getJSON('/api/info');
      [S.elev, S.slope, S.colorImg] = await Promise.all([getGrid('/api/grid/elevation.i16'), getGrid('/api/grid/slope.i16'), loadImage(API + '/api/layer/color.png')]);
    } catch (e) {
      el.fatal.hidden = false;
      el.fatal.innerHTML = '<div><h2>Cannot reach the Marswalk backend</h2><p>Start it with <code>backend/run.sh</code> (Windows: <code>backend\\run.bat</code>) and reload this page.</p>' +
        '<p class="muted">If you opened this page with Live Server, add <code>?api=http://localhost:8080</code> to the address.</p></div>';
      say('Backend not reachable.', 'error');
      return;
    }
    const hi = S.info.hires;
    say(hi.available
      ? 'Ready. Click the map to choose a start point. High-resolution terrain (' + G.fmtInt(hi.cellM) + ' m) is available.'
      : 'Ready. Click the map to choose a start point. The high-resolution terrain file is not installed, so only general routes will be available.',
      hi.available ? '' : 'warn');
    updateAll();
    invalidate();
  }
  boot();
})();

// End-to-end UI test: runs the REAL page (frontend/) against the REAL Java backend inside jsdom with a real canvas
// (node-canvas), clicks the map like a user, and saves screenshots of the map canvas.
//   Needs:  npm install jsdom canvas      and a running backend:  java -cp backend/out marswalk.Main --port 18080 [--hires file]
//   Run:    node tests/frontend/ui-smoke.js http://localhost:18080 /tmp/shots [expectHires=1|0]
const { JSDOM } = require('jsdom');
const fs = require('fs');
const BASE = process.argv[2] || 'http://localhost:18080';
const OUT = process.argv[3] || '/tmp/shots';
const EXPECT_HIRES = (process.argv[4] || '1') === '1';
fs.mkdirSync(OUT, { recursive: true });

let passed = 0, failed = 0;
const sleep = ms => new Promise(r => setTimeout(r, ms));
function check(cond, msg) { if (cond) { passed++; console.log('PASS  ' + msg); } else { failed++; console.log('FAIL  ' + msg); } }
async function waitFor(fn, ms, what) { const t0 = Date.now(); while (Date.now() - t0 < ms) { if (fn()) return true; await sleep(50); } console.log('  (timed out waiting for ' + what + ')'); return false; }

async function open(url) {
  const dom = await JSDOM.fromURL(url, {
    runScripts: 'dangerously', resources: 'usable', pretendToBeVisual: true,
    beforeParse(w) {
      w.fetch = (u, o) => fetch(new URL(u, url), o);
      w.AbortController = AbortController;
    },
  });
  return dom.window;
}
function shot(w, name) { const d = w.document.getElementById('map').toDataURL('image/png').split(',')[1]; fs.writeFileSync(OUT + '/' + name + '.png', Buffer.from(d, 'base64')); }
function fire(w, type, x, y, extra) { const Ctor = type === 'wheel' ? w.WheelEvent : w.MouseEvent; w.document.getElementById('map').dispatchEvent(new Ctor(type, Object.assign({ clientX: x, clientY: y, bubbles: true, cancelable: true }, extra || {}))); }
function click(w, x, y) { fire(w, 'pointerdown', x, y); fire(w, 'pointerup', x, y); }
const $ = (w, id) => w.document.getElementById(id);
function screenOf(w, lon, lat) { const G = w.MarsGeo, S = w.viewer.getState(), c = G.lonLatToCell(lon, lat); return G.cellToScreen(S.view, c.x, c.y); }

(async () => {
  console.log('== Page against ' + BASE + ' (expect high-res: ' + EXPECT_HIRES + ') ==');
  const w = await open(BASE + '/');
  const errors = []; w.addEventListener('error', e => errors.push(e.message));
  check(await waitFor(() => { const s = w.viewer && w.viewer.getState(); return s && s.elev && s.colorImg; }, 20000, 'map data'), 'page boots and loads info, elevation, slope and the colour map');
  const S = w.viewer.getState();
  check(/Ready/.test($(w, 'status').textContent), 'status says ready: "' + $(w, 'status').textContent + '"');
  check(S.elev.length === 1440 * 960 && S.slope.length === 1440 * 960, 'grids decoded (1440 x 960)');
  check(!document_hasOldStuff(w), 'old weather / science-stop / GO-NO-GO controls are gone');
  await sleep(300); shot(w, '01_color');

  // layers + vertical exaggeration
  const before = Int16Array.from(S.elev);
  w.viewer.setLayer('elevation'); w.viewer.setExaggeration(3); await sleep(500); shot(w, '02_elevation_x3');
  w.viewer.setExaggeration(5); await sleep(400); shot(w, '03_elevation_x5');
  w.viewer.setLayer('slope'); await sleep(500); shot(w, '04_slope');
  check($(w, 'exag').disabled === false, 'exaggeration slider is enabled on the slope/elevation layers');
  w.viewer.setLayer('color'); await sleep(200);
  check($(w, 'exag').disabled === true, 'exaggeration slider is disabled on the colour layer (its shading is built in)');
  check(before.every((v, i) => v === S.elev[i]), 'changing vertical exaggeration never changes the elevation data');

  // click outside the map, then a route
  click(w, 5, 5);
  check(/inside the MC-13/.test($(w, 'status').textContent), 'click outside the region is rejected with a message');
  const A = screenOf(w, 56.0, 26.0), B = screenOf(w, 84.0, 4.0);
  click(w, A.x, A.y); await sleep(200);
  check(S.start && !S.end && /Start set/.test($(w, 'status').textContent), 'first click sets the start');
  shot(w, '05_start_marker');
  click(w, B.x, B.y);
  check(S.end !== null, 'second click sets the destination');
  check(await waitFor(() => S.general, 30000, 'general route'), 'general route arrives');
  const busyNow = !$(w, 'busy').hidden && /Optimizing/.test($(w, 'busyText').textContent); shot(w, EXPECT_HIRES ? '06_general_while_optimizing' : '06_general_only');
  check(S.general.stats.distanceM > 0 && S.general.points.length > 10, 'general route has geometry and statistics');
  check(/general/i.test($(w, 'summaryFor').textContent), 'summary shows the general route');
  if (EXPECT_HIRES) {
    check(busyNow || S.optimized, 'indeterminate "Optimizing" animation is visible while the 463 m search runs');
    check(await waitFor(() => S.optimized, 120000, 'optimized route'), 'optimized route arrives');
    await sleep(1500); shot(w, '07_optimized');
    check($(w, 'busy').hidden, 'busy animation disappears when the optimized route is ready');
    check(S.show === 'optimized' && /optimized/.test($(w, 'summaryFor').textContent), 'optimized route is shown automatically');
    check(/Same 463 m terrain/.test($(w, 'summary').innerHTML), 'summary compares general vs optimized on the same 463 m terrain');
    check(/Detours from general route/.test($(w, 'summary').innerHTML), 'summary lists detours from the general route');
    const radios = Array.from(w.document.getElementsByName('routeview'));
    check(radios.every(r => !r.disabled), 'both toggle options are enabled');
    // toggle back to general
    const rg = radios.find(r => r.value === 'general'); rg.checked = true; rg.dispatchEvent(new w.Event('change', { bubbles: true }));
    await sleep(1300); shot(w, '08_toggled_to_general');
    check(S.show === 'general' && /general/i.test($(w, 'summaryFor').textContent) && S.optimized, 'toggle switches to the general route and keeps the optimized one');
    $(w, 'ghost').checked = true; $(w, 'ghost').dispatchEvent(new w.Event('change', { bubbles: true }));
    const ro = radios.find(r => r.value === 'optimized'); ro.checked = true; ro.dispatchEvent(new w.Event('change', { bubbles: true }));
    await sleep(1300); shot(w, '09_optimized_with_faint_general');
    check(S.show === 'optimized' && S.ghost, 'toggle back to optimized, with the other route faintly visible');
  } else {
    check(S.optimized === null && /not installed|general route only|no optimized/i.test($(w, 'status').textContent), 'without the 463 m raster the page says so and keeps the general route: "' + $(w, 'status').textContent + '"');
    check(Array.from(w.document.getElementsByName('routeview')).find(r => r.value === 'optimized').disabled, 'optimized toggle stays disabled');
  }

  // zoom: labels get room, view stays valid
  const mid = S.general.cells[Math.floor(S.general.cells.length / 2)], ms = w.MarsGeo.cellToScreen(S.view, mid.x, mid.y);
  const k0 = S.view.k;
  for (let i = 0; i < 4; i++) fire(w, 'wheel', ms.x, ms.y, { deltaY: -100 });
  await sleep(300); shot(w, '10_zoomed_labels');
  check(S.view.k > k0 * 2, 'wheel zoom works (' + k0.toFixed(2) + ' -> ' + S.view.k.toFixed(2) + ' px per cell)');
  // drag to pan must not set a point
  const startBefore = JSON.stringify(S.start), tx0 = S.view.tx;
  fire(w, 'pointerdown', 300, 300); fire(w, 'pointermove', 360, 330); fire(w, 'pointerup', 360, 330);
  check(S.view.tx !== tx0 && JSON.stringify(S.start) === startBefore, 'dragging pans the map and does not place a point');

  // strict slope limit -> clear backend error, then recover
  $(w, 'prefs').open = true;
  $(w, 'useMaxSlope').checked = true; $(w, 'maxSlope').value = '1';
  $(w, 'recalc').click();
  check(await waitFor(() => /No general route|no route/i.test($(w, 'status').textContent), 30000, 'error message'), 'impossible settings give a clear error: "' + $(w, 'status').textContent + '"');
  check($(w, 'status').className.includes('error') && !S.general, 'error is styled as an error and the stale route is cleared');
  $(w, 'useMaxSlope').checked = false; $(w, 'recalc').click();
  check(await waitFor(() => S.general, 30000, 'general route again'), 'recalculate works again after fixing the settings');
  if (EXPECT_HIRES) await waitFor(() => S.optimized, 120000, 'optimized again');

  // reset
  $(w, 'reset').click(); await sleep(100);
  check(!S.start && !S.end && !S.general && !S.optimized && /Click the map/.test($(w, 'status').textContent), 'Clear resets everything');
  check(/Choose a start/.test($(w, 'summary').textContent), 'summary is reset');
  check(errors.length === 0, 'no uncaught script errors' + (errors.length ? ': ' + errors.join(' | ') : ''));

  // backend unreachable
  const dead = await open(BASE + '/?api=http://localhost:1');
  check(await waitFor(() => !dead.document.getElementById('fatal').hidden, 15000, 'fatal overlay'), 'unreachable backend shows a helpful message');

  console.log('\n' + passed + ' passed, ' + failed + ' failed');
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });

function document_hasOldStuff(w) {
  const t = w.document.body.innerHTML;
  return /cond|Science stops|GO\/NO|verdict|Clear sol|Dust warning|Jezero/i.test(t.replace(/Marswalk/g, ''));
}

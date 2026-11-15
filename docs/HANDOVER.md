# Handover (state at end of session)

## Status: WORKING END TO END, except Stage 2 has only been run on a SYNTHETIC 463 m file
Verified: backend 25/25 tests, frontend math 7/7, real page + real backend UI test 31/31 (tests/frontend/ui-smoke.js),
screenshot inspected. Stage 1 works on the real data. Stage 2 code, file reader, API and UI all work, but the real
463 m raster was not available to me: `data/hires/mc13_elevation_463m.raw` must be created by the user (data/hires/README.md).
The synthetic file (marswalk.SyntheticHires) is a bilinear upsample with NO real detail - plumbing tests only.
NOT done / not tested: real Stage 2 quality on real crater terrain; visual check in a real browser (only jsdom+canvas screenshots);
pinch zoom on touch screens; GitHub push (no credentials; the branch exists on GitHub as `Marswalk-Planner-Development`).

## Phase 1 findings (data investigation)
- From QGIS projects v3-v5 (the only evidence I could see): `mc13_full.tif` (5760x3840, ~463 m, Equirectangular Mars) existed in the
  QGIS folder; it is NOT in the repo zip and I could not open it. **No 463 m slope raster was ever created** (slope was only computed
  on the shrunk grid). Backend computes slope on demand instead.
- Repo files verified: both .asc 1440x960, cell 1852.374166 m, elevation -4945..3135 m, 0 NoData; slope 0..27.2174 deg, 4796 NoData = exactly the 1-cell border.
- Slope file == gdaldem Horn slope of the already-averaged 1,852 m DEM (max diff 0.0005 deg) -> it is a smoothed average, real steep walls are steeper.
- QGIS slope ignored cos(latitude): E-W spacing is 1604 m at 30N vs 1852 m assumed; correct slope is up to 2.9 deg higher (mean +0.04). The backend uses the corrected formula for 463 m and cost.
- color PNG is 2880x1920 (2x the grid, not 1440x960). Alignment with elevation verified by hue-vs-elevation consistency: best at zero shift (within ~1 PNG px = ~0.9 km).
- Header-derived corner is 45.00097E/30.00065N; the project uses exact 45E/30N/1/32 deg (diff ~60 m, far below a cell); loader rejects >0.01 deg.
- Working tree from the zip had CRLF line endings; only line endings differed from Git. `.gitattributes` added (eol=lf). Reader handles both.
- Repo changes: data moved with `git mv` to data/lowres + data/color (no content change); .DS_Store removed; frontend/route.json (old Jezero mock) deleted.

## Decisions
- Zero-dependency Java 21 (com.sun.net.httpserver, javac scripts, own JSON) because Maven Central was unreachable and it is simpler to run.
- Cost = dist*(wDist + wSlope*(s/10deg)^2) + wClimb*climb; s = max(step slope, 3x3 cell slope). Defaults wSlope 4, wClimb 2. No hard slope limit by default (user may set maxSlope/minElev/maxElev). A* heuristic admissible; A* equals brute-force Dijkstra in tests. 16-neighbour moves.
- Stage 2: corridor (buffer) around the general route, A* on 463 m cells; corridor doubles (5.6 km -> up to 40 km) when no route fits, route hugs the corridor wall, or best route still crosses cells with slope >= 10 deg. Tested on synthetic ridge: max slope 47.9 -> 9.2 deg.
- 463 m storage: flat Int16 file memory-mapped (44 MB). COG/tiling rejected as unnecessary for MC-13 only.
- "Vertical exaggeration" = hillshade strength (visual only, disabled on the colour layer which has baked shading).

## Known issues / next steps for the next Claude
1. Create the real raster (data/hires/README.md), run `java -cp backend/out marswalk.Main`, test real routes across Isidis basin/craters; check refine times (long steep routes widen up to 6 attempts; ~3-7 s on a 1,700 km route with synthetic data).
2. Tune defaults (wSlope, wClimb, corridor sizes) with real 463 m data; consider stopping early / parallelising attempts.
3. Look at the app in a real browser (layout on small screens, label readability, pinch zoom).
4. Refine step counts "detours" use a 1 km threshold (RoutePlanner.refine) - adjust if wanted.
5. Slope NoData border cells are allowed (step slope used); set CostModel.blockOnSlopeNoData to forbid.
6. Possible extras: click-to-pick target auto-suggestion, export route as GeoJSON/CSV, Maven/Gradle build.
Commands: build `sh backend/build.sh`; backend tests `sh backend/test.sh`; frontend math `node tests/frontend/geo.test.js`;
UI test needs `npm i jsdom @napi-rs/canvas` then start a backend and `node tests/frontend/ui-smoke.js http://localhost:18080 /tmp/shots 1`.

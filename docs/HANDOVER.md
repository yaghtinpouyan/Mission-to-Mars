# Handover (state at end of session)

## Status: Stage 1 and Stage 2 verified on real terrain
Previously verified: backend 25/25 tests, frontend math 7/7, real page + backend UI test 31/31 (`tests/frontend/ui-smoke.js`),
screenshot inspected. Current branch verification: backend 26/26 tests; the uploaded 463 m raster loaded and three real route
refinements completed via the HTTP API. The raw raster is in `data/hires/mc13_elevation_463m.raw` locally and is gitignored;
the ENVI header remains tracked. Do not treat the synthetic upsample in `marswalk.SyntheticHires` as real terrain.

Real Stage 2 samples (balanced 22 km maximum corridor):
- Isidis crossing (50E,5N to 85E,25N): high-res steepest terrain 11.15 -> 7.11 deg; distance 2,365.1 -> 2,383.2 km; refine ~0.64 s.
- Eastern crater plain (89.5E,29N to 89.5E,1N): 15.83 -> 8.76 deg; distance 1,676.2 -> 1,681.7 km; refine ~1.29 s.
- Isidis basin traverse (82E,12N to 89E,12N): 3.28 -> 2.35 deg; distance 409.4 -> 407.5 km; refine ~0.07 s.

NOT done / not tested: visual check in a real browser (only previous jsdom+canvas screenshots); pinch zoom on touch screens;
broader route-quality sampling beyond the real routes above.

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
- Elevation and slope layers use fixed hillshade at true vertical scale; the colour layer has baked shading.

## Known issues / next steps for the next Claude
1. Keep the 44 MB raw raster available locally or in a release/LFS for deployments; it is intentionally excluded from normal Git history.
2. Look at the app in a real browser (layout on small screens, label readability, pinch zoom).
3. Continue sampling real routes across additional crater/basin areas before changing the cost or corridor defaults.
4. Refine step counts: "detours" use a 1 km threshold (`RoutePlanner.refine`) - adjust if wanted.
5. Slope NoData border cells are allowed (step slope used); set `CostModel.blockOnSlopeNoData` to forbid.
6. Possible extras: click-to-pick target auto-suggestion, export route as GeoJSON/CSV, Maven/Gradle build.
Commands: build `sh backend/build.sh`; backend tests `sh backend/test.sh`; frontend math `node tests/frontend/geo.test.js`;
UI test needs `npm i jsdom @napi-rs/canvas` then start a backend and `node tests/frontend/ui-smoke.js http://localhost:18080 /tmp/shots 1`.

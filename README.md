# Marswalk Planner - MC-13 (Syrtis Major) terrain route planner

Pick a start and a destination on the map. Stage 1 finds a general route on the 1,852 m grids; Stage 2 refines it on the
463 m terrain (if installed) inside a corridor that widens when needed. Candidate routes from terrain data only - NOT validated
rover mobility limits.

## Run (needs a JDK 17+; no other dependencies)
    sh backend/run.sh            (Windows: backend\run.bat)
    open http://localhost:8080
Optional: install the 463 m raster -> data/hires/README.md. Tests: `sh backend/test.sh` and `node tests/frontend/geo.test.js`.
Live Server users: open the page with `?api=http://localhost:8080`.

## Layout
- data/lowres/*.asc  1,440 x 960 elevation + slope (1,852 m).  data/color/mc13_color.png  2,880 x 1,920 picture (2 px per cell).
- backend/src/main/java/marswalk/  Java: Georef, AsciiGrid, HornSlope, CostModel, AStar, Corridor, Refiner, RouteStats, RoutePlanner, MarswalkServer, Main
- backend/src/test/java/marswalk/  TestMain (25 tests), SyntheticHires (TEST ONLY fake 463 m file)
- frontend/  index.html, style.css, app.js, geo.js.   tests/frontend/  geo.test.js, ui-smoke.js (jsdom)
- docs/HANDOVER.md  status, decisions, known issues, next steps.

## API
GET /api/info | /api/route/general?startLon&startLat&endLon&endLat[&wSlope&wClimb&slopeRef&maxSlope&minElev&maxElev]
| /api/route/refine?id=<id>[&radiusM&maxRadiusM] | /api/layer/color.png | /api/grid/{elevation,slope}.i16 (big-endian int16; slope in 1/100 deg)

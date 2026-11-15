# Technical handover

## Current local run path

The checked-in app is a static frontend in `frontend/` (`index.html`, `app.js`, `style.css`, and `route.json`). `backend/run.bat` compiles `backend/StaticFileServer.java` into a temporary directory and serves that frontend on `127.0.0.1:8080`. The batch script quotes every path passed to `javac`, uses its own location rather than the caller's working directory, and removes its temporary class directory when the server exits. JDK 17 or newer must be installed with `javac` and `java` available on `PATH`.

This only serves the static frontend; it is not an application backend and does not provide a backend API. Each teammate can run the frontend locally without joining another developer's Wi-Fi. Access from another device on a LAN would be a separate networking/firewall configuration.

## Still needed for the requested data work

- There is no `/hire` route or matching implementation in the checked-in frontend.
- No translated/input artifact was present in the checkout to derive corrected `/hire` values from. `frontend/route.json` currently contains example `clear` and `dusty` summaries; their values should not be treated as verified translated output.
- `backend/src/main/java/example/mc13_elevation.asc` and `mc13_slope.asc` are present, as are the projection files, but `mc13_elevation_463m.raw.dat` and a translated input artifact are not present in the checkout or session Library. The available elevation grid is 1440 × 960 with a 1852.374166201484 m cell size and `NODATA_value -32768`; it is not the requested 463 m source. Do not rename or resample it into a purported 463 m raw dataset. Obtain the actual translated/raw data and its encoding/metadata before adding or wiring it into the app.

Once the missing inputs are available, confirm the expected `/hire` route behavior, the authoritative route values, and the raw raster's encoding and dimensions before implementing that part.

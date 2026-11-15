# High-resolution (463 m) elevation raster - NOT in Git, you must create it

Stage 2 (optimized route) needs the original 463 m MC-13 elevation. It is only ~44 MB, so it is a plain flat file that the
backend memory-maps (only the parts a route touches are read). Without it the app still works: you get the general route.

## Create it (one QGIS step, 1 minute)
You already made `mc13_full.tif` (5760 x 3840, 463 m) earlier. In QGIS:
Raster > Conversion > Translate (Convert Format)
- Input layer: `mc13_full`
- Additional command-line parameters: `-of ENVI -ot Int16`
- Output file: `data/hires/mc13_elevation_463m.raw`   (QGIS may also write `mc13_elevation_463m.hdr` - keep both)

Expected: raw file = 44,236,800 bytes; header says samples = 5760, lines = 3840, data type = 2.
The backend reads the ENVI map info and checks the raster origin (within 0.01°) and cell spacing against the MC-13 grid before enabling optimization.
If `mc13_full.tif` is gone: re-crop the global MOLA DEM with `-srcwin 28800 7680 5760 3840` (see top-level README), then do the step above.
No 463 m slope file is needed: the backend computes slope from elevation (latitude-corrected).

## Hosting options (decision record)
- MC-13 only = 44 MB -> keep it local, or attach it to a GitHub Release, or Git LFS. Do not commit it to normal Git history.
- A Cloud Optimized GeoTIFF + HTTP range requests only pays off if the whole planet (2 GB) is served from a remote host.
  Java would then need GeoTools/GDAL. Not needed for this project.

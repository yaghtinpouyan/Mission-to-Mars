# High-resolution MC-13 elevation

`mc13_elevation_463m.raw` is included in this branch. It lets the backend refine routes against the 463 m terrain immediately after checkout; no separate QGIS installation or local export is needed. The backend memory-maps the file.

## Source and format

The raster is a crop of the NASA Planetary Data System's MGS MOLA 128 pixels/degree topography tile [`megt44n000hb.img`](https://pds-geosciences.wustl.edu/mgs/urn-nasa-pds-mgs_mola_topography_derived/meg128/megt44n000hb.img). Its PDS metadata describes signed 16-bit big-endian elevations in meters on a 11520 x 5632 grid covering 0-90°E and 0-44°N.

The checked-in 5760 x 3840 crop covers 45-90°E and 0-30°N: source columns 5760-11519 and rows 1792-5631. PDS big-endian samples are byte-swapped to the app's little-endian Int16 format, and the PDS no-data value (0) is stored as -32768 to match the tracked ENVI header. The expected raw size is 44,236,800 bytes. No interpolation or synthetic elevation values are added.

The tracked `mc13_elevation_463m.hdr` provides the app's Equirectangular Mars georeference; the backend validates that it aligns with the low-resolution navigation grid before enabling refinement. No 463 m slope file is needed: the backend computes slope from elevation.

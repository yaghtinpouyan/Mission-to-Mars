package marswalk;

import java.io.IOException;
import java.nio.file.Path;

/** Terrain held entirely in memory (used for the ~1,852 m navigation grids). */
public final class GridTerrain implements Terrain {
    private final Georef geo;
    private final float[] elev;
    private final float[] slope;

    public GridTerrain(Georef geo, float[] elev, float[] slope) {
        if (elev.length != geo.cols * geo.rows || slope.length != elev.length)
            throw new IllegalArgumentException("array size does not match the georeference");
        this.geo = geo; this.elev = elev; this.slope = slope;
    }

    public Georef georef() { return geo; }
    public float elevation(int c, int r) { return geo.inBounds(c, r) ? elev[r * geo.cols + c] : Float.NaN; }
    public float slopeDeg(int c, int r) { return geo.inBounds(c, r) ? slope[r * geo.cols + c] : Float.NaN; }
    public float[] elevationArray() { return elev; }
    public float[] slopeArray() { return slope; }

    /**
     * Loads the supplied elevation and slope grids and checks that they really are the
     * MC-13 grid (same size, same corner, same cell size, matching the expected georeference).
     */
    public static GridTerrain loadLowres(Path elevationAsc, Path slopeAsc) throws IOException {
        AsciiGrid e = AsciiGrid.read(elevationAsc);
        AsciiGrid s = AsciiGrid.read(slopeAsc);
        if (e.ncols != s.ncols || e.nrows != s.nrows || e.xllcorner != s.xllcorner
                || e.yllcorner != s.yllcorner || e.cellsize != s.cellsize)
            throw new IOException("Elevation and slope grids are not aligned (size/corner/cellsize differ)");
        Georef nominal = Georef.mc13Lowres();
        if (e.ncols != nominal.cols || e.nrows != nominal.rows)
            throw new IOException("Unexpected grid size " + e.ncols + "x" + e.nrows);
        double R = Georef.MARS_RADIUS_M;
        // The header-derived corner differs from exactly 45E/30N by ~0.001 deg (about 60 m) because the DEM's
        // metre pixel size was defined with a slightly different radius. That is far below one cell, so the
        // project uses the exact quadrangle bounds, but we refuse anything that is further off than 0.01 deg.
        if (Math.abs(e.westDeg(R) - nominal.west) > 0.01 || Math.abs(e.northDeg(R) - nominal.north) > 0.01
                || Math.abs(e.degPerCell(R) - nominal.degPerCell) > 1e-4 * nominal.degPerCell)
            throw new IOException("Grid georeference does not match MC-13 (45-90E, 0-30N)");
        return new GridTerrain(nominal, e.data, s.data);
    }
}

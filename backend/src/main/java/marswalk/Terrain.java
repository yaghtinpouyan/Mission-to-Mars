package marswalk;

/** Read-only access to elevation and slope for a raster. NaN means "no data". */
public interface Terrain {
    Georef georef();

    /** Elevation in metres relative to the MOLA reference surface. NaN = unknown or outside. */
    float elevation(int col, int row);

    /** Steepest-direction slope of the 3x3 neighbourhood in degrees. NaN = unknown or outside. */
    float slopeDeg(int col, int row);

    /** Bilinear elevation at a lon/lat position (metres); NaN if any of the 4 cells is unknown. */
    default float elevationAt(double lon, double lat) {
        Georef g = georef();
        double cf = g.colF(lon) - 0.5, rf = g.rowF(lat) - 0.5;   // cell-centre based
        int c0 = (int) Math.floor(cf), r0 = (int) Math.floor(rf);
        double fx = cf - c0, fy = rf - r0;
        int c1 = Math.min(g.cols - 1, c0 + 1), r1 = Math.min(g.rows - 1, r0 + 1);
        c0 = Math.max(0, c0);
        r0 = Math.max(0, r0);
        float a = elevation(c0, r0), b = elevation(c1, r0), c = elevation(c0, r1), d = elevation(c1, r1);
        if (Float.isNaN(a) || Float.isNaN(b) || Float.isNaN(c) || Float.isNaN(d)) return Float.NaN;
        double top = a + (b - a) * fx, bottom = c + (d - c) * fx;
        return (float) (top + (bottom - top) * fy);
    }

    /** Slope of the cell that contains a lon/lat position. */
    default float slopeAt(double lon, double lat) {
        Georef g = georef();
        return slopeDeg(g.col(lon), g.row(lat));
    }
}

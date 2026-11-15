package marswalk;

/**
 * Horn (1981) 3x3 slope, the same method as gdaldem / QGIS "Slope", but with the
 * east-west ground spacing corrected for latitude (cos(lat)).
 * Edge cells (and cells next to no-data) get NaN.
 */
public final class HornSlope {
    private HornSlope() {}

    /** Slope in degrees at one cell, or NaN if the 3x3 window is incomplete. */
    public static float at(Terrain t, int col, int row, boolean latitudeCorrected) {
        Georef g = t.georef();
        if (col < 1 || row < 1 || col > g.cols - 2 || row > g.rows - 2) return Float.NaN;
        float a = t.elevation(col - 1, row - 1), b = t.elevation(col, row - 1), c = t.elevation(col + 1, row - 1);
        float d = t.elevation(col - 1, row),                                     f = t.elevation(col + 1, row);
        float gg = t.elevation(col - 1, row + 1), h = t.elevation(col, row + 1), i = t.elevation(col + 1, row + 1);
        if (Float.isNaN(a) || Float.isNaN(b) || Float.isNaN(c) || Float.isNaN(d)
                || Float.isNaN(f) || Float.isNaN(gg) || Float.isNaN(h) || Float.isNaN(i)) return Float.NaN;
        double dx = latitudeCorrected ? g.eastMeters(row) : g.southMeters();
        double dy = g.southMeters();
        double dzdx = ((c + 2.0 * f + i) - (a + 2.0 * d + gg)) / (8.0 * dx);
        double dzdy = ((gg + 2.0 * h + i) - (a + 2.0 * b + c)) / (8.0 * dy);
        return (float) Math.toDegrees(Math.atan(Math.hypot(dzdx, dzdy)));
    }

    /** Slope for every cell of a dense elevation array (row-major). */
    public static float[] computeAll(Georef g, float[] elev, boolean latitudeCorrected) {
        Terrain t = new Terrain() {
            public Georef georef() { return g; }
            public float elevation(int c, int r) { return g.inBounds(c, r) ? elev[r * g.cols + c] : Float.NaN; }
            public float slopeDeg(int c, int r) { return Float.NaN; }
        };
        float[] out = new float[g.cols * g.rows];
        for (int r = 0; r < g.rows; r++)
            for (int c = 0; c < g.cols; c++) out[r * g.cols + c] = at(t, c, r, latitudeCorrected);
        return out;
    }
}

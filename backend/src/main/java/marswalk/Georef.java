package marswalk;

/**
 * Converts between grid cells and longitude/latitude for a north-up raster
 * in an equirectangular (plate carree) Mars projection.
 *
 * Conventions (used everywhere in this project):
 *  - column 0 is the WEST edge, row 0 is the NORTH edge.
 *  - longitude is degrees EAST, latitude is degrees NORTH.
 *  - a cell's centre is at (index + 0.5) cells from the raster's top-left corner.
 *  - ground distance east-west shrinks with cos(latitude); north-south does not.
 */
public final class Georef {
    /** Sphere radius taken from the data's .prj file (Mars_localRadius). */
    public static final double MARS_RADIUS_M = 3_396_190.0;

    public final int cols, rows;
    public final double west, north;     // degrees of the raster's left and top edges
    public final double degPerCell;
    public final double radius;

    private final double southMeters;    // north-south ground size of one cell
    private final double[] eastMeters;   // east-west ground size of one cell, per row
    private final double minEastMeters;

    public Georef(int cols, int rows, double west, double north, double degPerCell, double radius) {
        this.cols = cols;
        this.rows = rows;
        this.west = west;
        this.north = north;
        this.degPerCell = degPerCell;
        this.radius = radius;
        this.southMeters = radius * Math.toRadians(degPerCell);
        this.eastMeters = new double[rows];
        double min = Double.MAX_VALUE;
        for (int r = 0; r < rows; r++) {
            eastMeters[r] = southMeters * Math.cos(Math.toRadians(centerLat(r)));
            min = Math.min(min, eastMeters[r]);
        }
        this.minEastMeters = min;
    }

    /** The MC-13 (Syrtis Major) quadrangle at the 1,852 m navigation resolution. */
    public static Georef mc13Lowres() {
        return new Georef(1440, 960, 45.0, 30.0, 1.0 / 32.0, MARS_RADIUS_M);
    }

    /** Same area, each cell split into factor x factor smaller cells (4 gives ~463 m). */
    public Georef refined(int factor) {
        return new Georef(cols * factor, rows * factor, west, north, degPerCell / factor, radius);
    }

    public double east() { return west + cols * degPerCell; }
    public double south() { return north - rows * degPerCell; }

    // ---- cell <-> degrees -------------------------------------------------
    public double colF(double lon) { return (lon - west) / degPerCell; }   // fractional, edge based
    public double rowF(double lat) { return (north - lat) / degPerCell; }
    public int col(double lon) { return (int) Math.floor(colF(lon)); }
    public int row(double lat) { return (int) Math.floor(rowF(lat)); }
    public double centerLon(int col) { return west + (col + 0.5) * degPerCell; }
    public double centerLat(int row) { return north - (row + 0.5) * degPerCell; }

    public boolean contains(double lon, double lat) {
        return lon >= west && lon <= east() && lat <= north && lat >= south();
    }
    public boolean inBounds(int col, int row) { return col >= 0 && row >= 0 && col < cols && row < rows; }

    // ---- distances ---------------------------------------------------------
    public double southMeters() { return southMeters; }
    public double eastMeters(int row) { return eastMeters[Math.max(0, Math.min(rows - 1, row))]; }
    public double minEastMeters() { return minEastMeters; }

    /** Ground distance between the centres of two cells (metres). */
    public double stepMeters(int c0, int r0, int c1, int r1) {
        double east = 0.5 * (eastMeters(r0) + eastMeters(r1));
        return Math.hypot((c1 - c0) * east, (r1 - r0) * southMeters);
    }

    /** Ground distance between two lon/lat points (equirectangular, fine for short steps). */
    public double distanceMeters(double lon0, double lat0, double lon1, double lat1) {
        double midLat = Math.toRadians(0.5 * (lat0 + lat1));
        double dx = Math.toRadians(lon1 - lon0) * Math.cos(midLat) * radius;
        double dy = Math.toRadians(lat1 - lat0) * radius;
        return Math.hypot(dx, dy);
    }
}

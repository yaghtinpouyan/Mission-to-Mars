package marswalk;

import java.util.Arrays;

/**
 * Terrain on top of the full-resolution raster. Slope is computed on demand (Horn, latitude corrected)
 * and cached, so the 463 m slope raster never has to exist as a file.
 * One instance per request: the small cache is not shared between threads.
 */
public final class HiResTerrain implements Terrain {
    private final HiResRaster raster;
    private final Georef geo;
    private final int cacheMask = (1 << 20) - 1;
    private final float[] cacheVal = new float[cacheMask + 1];
    private final int[] cacheKey = new int[cacheMask + 1];

    /** @param coarse the georeference of the navigation grid; the raster must be an exact integer refinement of it */
    public HiResTerrain(HiResRaster raster, Georef coarse) {
        if (raster.cols % coarse.cols != 0 || raster.rows % coarse.rows != 0
                || raster.cols / coarse.cols != raster.rows / coarse.rows)
            throw new IllegalArgumentException("High-resolution raster " + raster.cols + "x" + raster.rows
                    + " is not an integer refinement of the " + coarse.cols + "x" + coarse.rows + " navigation grid");
        Georef expected = coarse.refined(raster.cols / coarse.cols);
        HiResRaster.MapInfo map = raster.mapInfo;
        if (map != null) {
            double originToleranceDeg = 0.01;
            double cellToleranceDeg = expected.degPerCell * 1e-4;
            if (Math.abs(map.west - expected.west) > originToleranceDeg
                    || Math.abs(map.north - expected.north) > originToleranceDeg
                    || Math.abs(map.cellWidthDeg - expected.degPerCell) > cellToleranceDeg
                    || Math.abs(map.cellHeightDeg - expected.degPerCell) > cellToleranceDeg)
                throw new IllegalArgumentException(String.format(
                        "High-resolution raster map info is misaligned: origin %.5fE, %.5fN, cell %.8f x %.8f degrees; "
                                + "expected origin %.5fE, %.5fN, cell %.8f degrees",
                        map.west, map.north, map.cellWidthDeg, map.cellHeightDeg,
                        expected.west, expected.north, expected.degPerCell));
        }
        this.raster = raster;
        this.geo = expected;
        Arrays.fill(cacheKey, -1);
    }

    public Georef georef() { return geo; }

    public float elevation(int c, int r) {
        if (!geo.inBounds(c, r)) return Float.NaN;
        short v = raster.raw(c, r);
        return v == raster.nodata ? Float.NaN : v;
    }

    public float slopeDeg(int c, int r) {
        if (!geo.inBounds(c, r)) return Float.NaN;
        int key = r * geo.cols + c;
        int slot = (key * 0x9E3779B1) >>> 12 & cacheMask;
        if (cacheKey[slot] == key) return cacheVal[slot];
        float s = HornSlope.at(this, c, r, true);
        cacheKey[slot] = key;
        cacheVal[slot] = s;
        return s;
    }
}

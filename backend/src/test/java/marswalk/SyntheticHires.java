package marswalk;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * TEST UTILITY ONLY. Writes a fake "463 m" raster by bilinearly up-sampling the 1,852 m grid.
 * It contains NO new terrain detail - it only exists to test file access, memory-mapping, the API and the UI
 * on machines that do not have the real 463 m data. Never put its output in data/hires of a real install.
 *   java -cp backend/out marswalk.SyntheticHires <output.raw>
 */
public final class SyntheticHires {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        GridTerrain low = GridTerrain.loadLowres(root.resolve("data/lowres/mc13_elevation.asc"), root.resolve("data/lowres/mc13_slope.asc"));
        Georef hi = low.georef().refined(4);
        short[] z = new short[hi.cols * hi.rows];
        for (int r = 0; r < hi.rows; r++)
            for (int c = 0; c < hi.cols; c++) z[r * hi.cols + c] = (short) Math.round(low.elevationAt(hi.centerLon(c), hi.centerLat(r)));
        HiResRaster.writeEnvi(Paths.get(args[0]), hi.cols, hi.rows, z);
        System.out.println("Wrote SYNTHETIC test raster " + args[0] + " (" + hi.cols + " x " + hi.rows + ")");
    }
}

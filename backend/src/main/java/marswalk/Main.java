package marswalk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Starts the Marswalk Planner server.
 *   java -cp backend/out marswalk.Main [--port 8080] [--root <project folder>] [--hires <file.raw>]
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        int port = 8080;
        Path root = null, hiresFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--root" -> root = Paths.get(args[++i]).toAbsolutePath().normalize();
                case "--hires" -> hiresFile = Paths.get(args[++i]).toAbsolutePath().normalize();
                default -> { System.err.println("Unknown option " + args[i]); System.exit(2); }
            }
        }
        if (root == null) root = findRoot();
        if (hiresFile == null) hiresFile = root.resolve("data/hires/mc13_elevation_463m.raw");

        long t0 = System.nanoTime();
        GridTerrain low = GridTerrain.loadLowres(root.resolve("data/lowres/mc13_elevation.asc"), root.resolve("data/lowres/mc13_slope.asc"));
        System.out.printf("Loaded 1,852 m navigation grids (%d x %d) in %d ms%n", low.georef().cols, low.georef().rows, (System.nanoTime() - t0) / 1_000_000);

        HiResRaster hires = null;
        if (Files.exists(hiresFile)) {
            hires = HiResRaster.open(hiresFile);
            new HiResTerrain(hires, low.georef());        // validates that it lines up with the navigation grid
            System.out.printf("High-resolution raster: %s (%d x %d, memory-mapped)%n", hiresFile, hires.cols, hires.rows);
        } else {
            System.out.println("High-resolution raster NOT found at " + hiresFile + " - only the general route is available.");
            System.out.println("See data/hires/README.md to create it from mc13_full.tif.");
        }

        MarswalkServer server = new MarswalkServer(new RoutePlanner(low, hires),
                root.resolve("frontend"), root.resolve("data/color/mc13_color.png"));
        server.start(port);
        System.out.println("Marswalk Planner running at http://localhost:" + server.port() + "/   (Ctrl+C to stop)");
    }

    private static Path findRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent())
            if (Files.exists(p.resolve("data/lowres/mc13_elevation.asc"))) return p;
        throw new IllegalStateException("Could not find the project folder (the one that contains data/lowres). Use --root <folder>.");
    }
}

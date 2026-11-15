package marswalk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/** Dependency-free test runner:  javac ... && java -cp backend/out marswalk.TestMain [project root] */
public final class TestMain {
    static int passed = 0, failed = 0;
    static Path root;
    static GridTerrain real;                       // the real 1,852 m grids
    static final double R = Georef.MARS_RADIUS_M;

    interface T { void run() throws Exception; }

    static void test(String name, T t) {
        long t0 = System.nanoTime();
        try { t.run(); passed++; System.out.printf("PASS  %-62s %5d ms%n", name, (System.nanoTime() - t0) / 1_000_000); }
        catch (Throwable e) { failed++; System.out.printf("FAIL  %s%n      -> %s%n", name, e); if (!(e instanceof AssertionError)) e.printStackTrace(System.out); }
    }
    static void check(boolean c, String msg) { if (!c) throw new AssertionError(msg); }
    static void near(double a, double b, double tol, String msg) { if (!(Math.abs(a - b) <= tol)) throw new AssertionError(msg + " expected " + b + " got " + a); }
    static void info(String s) { System.out.println("      · " + s); }

    // ------------------------------------------------------------------ synthetic helpers
    static final Georef SMALL = new Georef(60, 40, 45.0, 30.0, 1.0 / 32.0, R);

    interface Surface { double z(int col, int row); }

    static GridTerrain grid(Georef g, Surface s) {
        float[] e = new float[g.cols * g.rows];
        for (int r = 0; r < g.rows; r++) for (int c = 0; c < g.cols; c++) e[r * g.cols + c] = (float) s.z(c, r);
        return new GridTerrain(g, e, HornSlope.computeAll(g, e, true));
    }

    static double length(Terrain t, int[] path) {
        int cols = t.georef().cols; double d = 0;
        for (int i = 1; i < path.length; i++) d += t.georef().stepMeters(path[i-1] % cols, path[i-1] / cols, path[i] % cols, path[i] / cols);
        return d;
    }

    static double[] lonOf(Georef g, double... cols) { double[] o = new double[cols.length]; for (int i = 0; i < o.length; i++) o[i] = g.west + cols[i] * g.degPerCell; return o; }
    static double[] latOf(Georef g, double... rows) { double[] o = new double[rows.length]; for (int i = 0; i < o.length; i++) o[i] = g.north - rows[i] * g.degPerCell; return o; }

    static AStar.Result run(Terrain t, CostModel cm, int sc, int sr, int gc, int gr, boolean sixteen) {
        return AStar.search(t, cm, sc, sr, gc, gr, null, sixteen, Long.MAX_VALUE);
    }

    /** Independent reference: plain Dijkstra with the same cost function and neighbour sets. */
    static double dijkstra(Terrain t, CostModel cm, int sc, int sr, int gc, int gr, boolean sixteen) {
        int[][] nb = sixteen ? new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1},{2,1},{2,-1},{-2,1},{-2,-1},{1,2},{1,-2},{-1,2},{-1,-2}}
                             : new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        Georef g = t.georef();
        double[] best = new double[g.cols * g.rows];
        java.util.Arrays.fill(best, Double.POSITIVE_INFINITY);
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        best[sr * g.cols + sc] = 0; pq.add(new double[]{0, sr * g.cols + sc});
        while (!pq.isEmpty()) {
            double[] top = pq.poll(); int k = (int) top[1];
            if (top[0] > best[k]) continue;
            int c = k % g.cols, r = k / g.cols;
            if (c == gc && r == gr) return top[0];
            for (int[] d : nb) {
                int nc = c + d[0], nr = r + d[1];
                if (!g.inBounds(nc, nr)) continue;
                double s = cm.stepCost(t, c, r, nc, nr);
                if (Double.isInfinite(s)) continue;
                int nk = nr * g.cols + nc;
                if (top[0] + s < best[nk]) { best[nk] = top[0] + s; pq.add(new double[]{best[nk], nk}); }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    static Path findRoot(String[] args) {
        if (args.length > 0) return Paths.get(args[0]).toAbsolutePath();
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) if (Files.exists(p.resolve("data/lowres/mc13_elevation.asc"))) return p;
        throw new IllegalStateException("project root not found");
    }

    // ------------------------------------------------------------------ main
    public static void main(String[] args) throws Exception {
        root = findRoot(args);
        System.out.println("Project root: " + root + "\n");

        System.out.println("== Georeference ==");
        test("cell <-> lon/lat round trip and bounds", () -> {
            Georef g = Georef.mc13Lowres();
            check(g.col(60.0) == 480 && g.row(15.0) == 480, "60E,15N should be col 480,row 480");
            near(g.centerLon(480), 60.0 + 0.5 / 32, 1e-12, "centre lon");
            near(g.centerLat(0), 30.0 - 0.5 / 32, 1e-12, "row 0 centre is just south of 30N (row 0 = north)");
            check(g.contains(45, 0) && g.contains(90, 30) && !g.contains(44.99, 10) && !g.contains(60, 30.01), "bounds");
            near(g.east(), 90.0, 1e-9, "east edge"); near(g.south(), 0.0, 1e-9, "south edge");
        });
        test("east-west distance shrinks with cos(latitude), north-south does not", () -> {
            Georef g = Georef.mc13Lowres();
            near(g.stepMeters(10, 500, 10, 501), R * Math.PI / 180 / 32, 1e-6, "vertical step = R*pi/180/32 = 1852.34 m");
            near(g.eastMeters(959) / g.southMeters(), Math.cos(Math.toRadians(g.centerLat(959))), 1e-9, "equator-side row");
            near(g.eastMeters(0) / g.southMeters(), Math.cos(Math.toRadians(29.984)), 1e-4, "30N row ~ cos 30 = 0.866");
            near(g.distanceMeters(60, 10, 60, 11), R * Math.PI / 180, 1e-6, "1 degree of latitude");
        });

        System.out.println("\n== Real data files ==");
        test("ASCII grids load and match the handover numbers", () -> {
            AsciiGrid e = AsciiGrid.read(root.resolve("data/lowres/mc13_elevation.asc"));
            AsciiGrid s = AsciiGrid.read(root.resolve("data/lowres/mc13_slope.asc"));
            check(e.ncols == 1440 && e.nrows == 960 && s.ncols == 1440 && s.nrows == 960, "size");
            check(e.nodataCount == 0, "elevation NoData cells: " + e.nodataCount);
            check(s.nodataCount == 4796, "slope NoData cells: " + s.nodataCount);
            double mn = 1e9, mx = -1e9, smx = -1;
            for (float v : e.data) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
            for (float v : s.data) if (!Float.isNaN(v)) smx = Math.max(smx, v);
            near(mn, -4945, 0, "min elevation"); near(mx, 3135, 0, "max elevation"); near(smx, 27.2173, 1e-3, "max slope");
            check(e.nodata == -32768 && s.nodata == -9999, "NoData codes");
            real = GridTerrain.loadLowres(root.resolve("data/lowres/mc13_elevation.asc"), root.resolve("data/lowres/mc13_slope.asc"));
        });
        test("slope NoData cells are exactly the one-cell map border", () -> {
            Georef g = real.georef();
            for (int r = 0; r < g.rows; r++) for (int c = 0; c < g.cols; c++) {
                boolean border = r == 0 || c == 0 || r == g.rows - 1 || c == g.cols - 1;
                check(Float.isNaN(real.slopeDeg(c, r)) == border, "cell " + c + "," + r);
            }
        });
        test("supplied slope == Horn slope of the supplied elevation (square cells, as QGIS did it)", () -> {
            Georef g = real.georef();
            float[] mine = HornSlope.computeAll(g, real.elevationArray(), false);
            double maxDiff = 0;
            for (int i = 0; i < mine.length; i++) if (!Float.isNaN(mine[i])) maxDiff = Math.max(maxDiff, Math.abs(mine[i] - real.slopeArray()[i]));
            check(maxDiff < 1e-3, "max difference " + maxDiff);
            info("max |difference| = " + maxDiff + " deg  -> the file is gdaldem-style slope of the already-averaged 1,852 m DEM");
        });
        test("ignoring cos(latitude) under-states slope (quantified)", () -> {
            Georef g = real.georef();
            float[] fixed = HornSlope.computeAll(g, real.elevationArray(), true);
            double maxDiff = 0, sum = 0; int n = 0;
            for (int i = 0; i < fixed.length; i++) if (!Float.isNaN(fixed[i])) { double d = fixed[i] - real.slopeArray()[i]; maxDiff = Math.max(maxDiff, d); sum += d; n++; }
            check(maxDiff > 1.0 && maxDiff < 5.0, "unexpected size of correction " + maxDiff);
            info(String.format("latitude-corrected minus supplied: mean %.3f deg, max %.2f deg", sum / n, maxDiff));
        });
        test("ASCII reader handles CRLF and LF, rejects short files", () -> {
            Path d = Files.createTempDirectory("asc");
            String body = "ncols 3\nnrows 2\nxllcorner 0\nyllcorner 0\ncellsize 10\nNODATA_value -9999\n1 2 -9999\n4 5 6\n";
            Files.writeString(d.resolve("lf.asc"), body);
            Files.writeString(d.resolve("crlf.asc"), body.replace("\n", "\r\n"));
            for (String n : new String[]{"lf.asc", "crlf.asc"}) {
                AsciiGrid a = AsciiGrid.read(d.resolve(n));
                check(a.data.length == 6 && a.nodataCount == 1 && Float.isNaN(a.data[2]) && a.data[5] == 6f, n);
            }
            Files.writeString(d.resolve("short.asc"), body.replace("4 5 6\n", "4 5\n"));
            try { AsciiGrid.read(d.resolve("short.asc")); throw new AssertionError("should have failed"); } catch (IOException expected) { }
        });

        System.out.println("\n== A* correctness ==");
        test("flat terrain: straight line, cost = ground distance", () -> {
            GridTerrain t = grid(SMALL, (c, r) -> 0);
            AStar.Result res = run(t, new CostModel(), 3, 10, 55, 10, true);
            check(res.found, "found");
            for (int k : res.path) check(k / SMALL.cols == 10, "left the straight row");
            double want = 0; for (int i = 1; i < res.path.length; i++) want += SMALL.stepMeters(res.path[i-1] % 60, 10, res.path[i] % 60, 10);
            near(res.cost, want, want * 1e-6, "cost");
        });
        test("A* cost equals brute-force Dijkstra on rough random terrain (8 and 16 neighbours)", () -> {
            Random rnd = new Random(7);
            for (int trial = 0; trial < 12; trial++) {
                double a = rnd.nextDouble() * 6, b = rnd.nextDouble() * 6, amp = 200 + rnd.nextDouble() * 900;
                GridTerrain t = grid(SMALL, (c, r) -> amp * (Math.sin(c * 0.37 + a) + Math.cos(r * 0.53 + b) + 0.5 * Math.sin((c + r) * 0.9)));
                CostModel cm = new CostModel(); cm.wSlope = 1 + rnd.nextInt(8); cm.wClimb = rnd.nextInt(4);
                int sc = rnd.nextInt(60), sr = rnd.nextInt(40), gc = rnd.nextInt(60), gr = rnd.nextInt(40);
                for (boolean sixteen : new boolean[]{false, true}) {
                    double ref = dijkstra(t, cm, sc, sr, gc, gr, sixteen);
                    AStar.Result res = run(t, cm, sc, sr, gc, gr, sixteen);
                    check(res.found, "should be found");
                    near(res.cost, ref, Math.max(1e-3, ref * 1e-5), "trial " + trial + (sixteen ? " (16)" : " (8)"));
                }
            }
        });
        test("walls: route detours through the only gap; hard slope limit; no route reported", () -> {
            Surface wall = (c, r) -> (c >= 28 && c <= 31 && r < 35) ? 3000 : 0;
            GridTerrain t = grid(SMALL, wall);
            AStar.Result res = run(t, new CostModel(), 5, 5, 55, 5, true);
            check(res.found, "found");
            boolean viaGap = false; for (int k : res.path) if (k % 60 >= 28 && k % 60 <= 31 && k / 60 >= 35) viaGap = true;
            check(viaGap, "route should pass the wall through the gap");
            CostModel limit = new CostModel(); limit.maxSlopeDeg = 15;
            check(run(t, limit, 5, 5, 55, 5, true).found, "route via gap exists under a 15 deg limit");
            GridTerrain closed = grid(SMALL, (c, r) -> (c >= 28 && c <= 31) ? 3000 : 0);
            AStar.Result none = run(closed, limit, 5, 5, 55, 5, true);
            check(!none.found && none.reason.contains("no route"), "full wall + limit must report no route: " + none.reason);
        });
        test("NoData: cannot cross a NoData column; NoData start is rejected clearly", () -> {
            float[] e = new float[60 * 40]; float[] s = new float[60 * 40];
            for (int r = 0; r < 40; r++) e[r * 60 + 30] = Float.NaN;
            GridTerrain t = new GridTerrain(SMALL, e, s);
            AStar.Result res = run(t, new CostModel(), 5, 5, 55, 5, true);
            check(!res.found && res.reason.contains("no route"), res.reason);
            AStar.Result bad = run(t, new CostModel(), 30, 5, 55, 5, true);
            check(!bad.found && bad.reason.contains("start"), bad.reason);
            e[39 * 60 + 30] = 0f;                                           // leave one row open
            check(run(new GridTerrain(SMALL, e, s), new CostModel(), 5, 5, 55, 5, true).found, "gap in NoData wall is usable");
        });
        test("elevation limits keep the route out of a shallow basin", () -> {
            Surface basin = (c, r) -> { double d = Math.hypot(c - 30, r - 20); return d < 12 ? -300 * 0.5 * (1 + Math.cos(Math.PI * d / 12)) : 0; };
            GridTerrain t = grid(SMALL, basin);
            AStar.Result free = run(t, new CostModel(), 3, 20, 57, 20, true);
            CostModel dry = new CostModel(); dry.minElevM = -100;
            AStar.Result avoid = run(t, dry, 3, 20, 57, 20, true);
            check(free.found && avoid.found, "found");
            for (int k : avoid.path) check(t.elevation(k % 60, k / 60) >= -100, "entered the basin");
            boolean freeEntered = false; for (int k : free.path) if (t.elevation(k % 60, k / 60) < -100) freeEntered = true;
            check(freeEntered, "unconstrained route should cut through the basin");
            check(avoid.cost >= free.cost, "a constrained route can never be cheaper than the unconstrained optimum");
            check(length(t, avoid.path) > length(t, free.path), "the detour around the basin must be longer on the ground");
            CostModel impossible = new CostModel(); impossible.minElevM = 50;
            RoutePlanner rp = new RoutePlanner(t, null);
            try { rp.planGeneral(45.2, 29.0, 46.0, 29.0, impossible); throw new AssertionError("should fail"); }
            catch (IllegalArgumentException ex) { check(ex.getMessage().contains("elevation limits"), ex.getMessage()); }
        });
        test("start == destination and 1-cell routes", () -> {
            GridTerrain t = grid(SMALL, (c, r) -> 0);
            AStar.Result r0 = run(t, new CostModel(), 7, 7, 7, 7, true);
            check(r0.found && r0.path.length == 1, "same cell");
            RoutePlanner rp = new RoutePlanner(t, null);
            RoutePlanner.General g = rp.planGeneral(45.50, 29.50, 45.505, 29.505, new CostModel());
            check(g.lon.length >= 3 && g.stats.distanceM > 0, "tiny route still valid");
        });

        System.out.println("\n== Route statistics ==");
        test("constant slope: gain, loss, average and maximum slope are correct", () -> {
            GridTerrain t = grid(SMALL, (c, r) -> 100.0 * c);               // +100 m per cell eastwards
            double lat = SMALL.centerLat(20);
            double[] lon = {SMALL.centerLon(5), SMALL.centerLon(55)}, la = {lat, lat};
            RouteStats up = RouteStats.compute(t, lon, la, 10);
            double east = SMALL.eastMeters(20), wantSlope = Math.toDegrees(Math.atan(100 / east));
            near(up.distanceM, 50 * east, 5, "distance");
            near(up.elevGainM, 5000, 1, "gain"); near(up.elevLossM, 0, 1e-6, "loss");
            near(up.avgSlopeDeg, wantSlope, 0.01, "avg slope"); near(up.maxSlopeDeg, wantSlope, 0.01, "max slope");
            near(up.startElevM, 500, 1e-3, "start elevation"); near(up.endElevM, 5500, 1e-3, "end elevation");
            RouteStats down = RouteStats.compute(t, new double[]{lon[1], lon[0]}, la, 10);
            near(down.elevLossM, 5000, 1, "loss when reversed"); near(down.elevGainM, 0, 1e-6, "gain when reversed");
            check(up.labels.size() >= 3 && up.labels.size() <= 13, "label count " + up.labels.size());
            for (RouteStats.Label l : up.labels) near(l.elevM, 500 + 100 * ((l.lon - SMALL.west) / SMALL.degPerCell - 5) , 120, "label elevation consistent");
        });
        test("steep sections are counted as separate stretches", () -> {
            // two ramps (up at columns 15-19, down at 40-44) with a flat plateau between them: two separate steep stretches
            GridTerrain t = grid(SMALL, (c, r) -> c < 15 ? 0 : c <= 19 ? 500.0 * (c - 14) : c < 40 ? 2500 : c <= 44 ? 2500 - 500.0 * (c - 39) : 0);
            double lat = SMALL.centerLat(20);
            RouteStats s = RouteStats.compute(t, new double[]{SMALL.centerLon(3), SMALL.centerLon(57)}, new double[]{lat, lat}, 10);
            check(s.steepSections == 2, "sections " + s.steepSections);
            check(s.steepLengthM > 0 && s.maxTerrainSlopeDeg > 10, "steep length/slope");
        });
        test("JSON writer: nesting, escaping, NaN -> null, rounding", () -> {
            String j = new JsonWriter().beginObject().field("a", 1).name("b").beginArray().value(1.5, 2).value(Double.NaN, 1).value(-0.001, 2).endArray()
                    .name("c").beginObject().field("d", "x\"y\n").field("e", true).endObject().endObject().toString();
            check(j.equals("{\"a\":1,\"b\":[1.50,null,0.00],\"c\":{\"d\":\"x\\\"y\\n\",\"e\":true}}"), j);
        });

        System.out.println("\n== Stage 1 on the real MC-13 data ==");
        test("general routes: flat west side, across the Isidis basin edge, corner to corner", () -> {
            RoutePlanner rp = new RoutePlanner(real, null);
            Georef g = real.georef();
            double[][] cases = {{46.0, 29.0, 46.5, 1.0}, {89.5, 29.0, 89.5, 1.0}, {45.0001, 29.9999, 89.9999, 0.0001}, {50, 5, 85, 25}};
            for (double[] c : cases) {
                long t0 = System.nanoTime();
                RoutePlanner.General r = rp.planGeneral(c[0], c[1], c[2], c[3], new CostModel());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                RouteStats straight = RouteStats.compute(real, new double[]{c[0], c[2]}, new double[]{c[1], c[3]}, 10);
                check(r.stats.distanceM >= r.stats.straightLineM * 0.999, "route shorter than straight line?");
                check(r.stats.maxSlopeDeg <= straight.maxSlopeDeg + 1e-6, "route steeper than the straight line");
                check(r.stats.unknownElevationSamples == 0, "NoData elevation crossed");
                near(r.stats.startElevM, real.elevationAt(c[0], c[1]), 1e-3, "start elevation");
                info(String.format("(%.2fE,%.2fN)->(%.2fE,%.2fN): %.0f km (straight %.0f km), avg slope %.2f deg, max %.2f (straight line max %.2f), gain %.0f m, %d ms, %d cells searched",
                        c[0], c[1], c[2], c[3], r.stats.distanceM / 1000, r.stats.straightLineM / 1000, r.stats.avgSlopeDeg, r.stats.maxSlopeDeg,
                        straight.maxSlopeDeg, r.stats.elevGainM, ms, r.expanded));
            }
        });
        test("minimum-elevation limit on real data: route never dips below the limit", () -> {
            RoutePlanner rp = new RoutePlanner(real, null);
            CostModel cm = new CostModel(); cm.minElevM = -2500;
            RoutePlanner.General r = rp.planGeneral(52, 27, 85, 3, cm);
            double lowest = 1e9;
            for (int i = 1; i < r.lon.length - 1; i++) lowest = Math.min(lowest, real.elevation(real.georef().col(r.lon[i]), real.georef().row(r.lat[i])));
            check(lowest >= -2500, "lowest vertex " + lowest);
            RoutePlanner.General free = rp.planGeneral(52, 27, 85, 3, new CostModel());
            info(String.format("limited: %.0f km, lowest point %.0f m   |   unlimited: %.0f km, lowest point %.0f m", r.stats.distanceM / 1000, lowest, free.stats.distanceM / 1000, free.stats.minElevM));
        });
        test("points outside MC-13 are rejected; border cells (slope NoData) still route", () -> {
            RoutePlanner rp = new RoutePlanner(real, null);
            try { rp.planGeneral(30, 10, 60, 10, new CostModel()); throw new AssertionError("outside start accepted"); } catch (IllegalArgumentException e) { check(e.getMessage().contains("start"), e.getMessage()); }
            try { rp.planGeneral(60, 10, 60, 35, new CostModel()); throw new AssertionError("outside end accepted"); } catch (IllegalArgumentException e) { check(e.getMessage().contains("destination"), e.getMessage()); }
            RoutePlanner.General r = rp.planGeneral(45.01, 15, 45.01, 14, new CostModel());
            check(r.stats.distanceM > 0, "border route");
            info("route along the western border: unknown-slope samples = " + r.stats.unknownSlopeSamples + " (allowed, step slope used)");
        });

        System.out.println("\n== High-resolution file access ==");
        test("ENVI reader: .hdr and .raw.hdr naming, values, size checks", () -> {
            Path d = Files.createTempDirectory("envi");
            short[] data = new short[7 * 5]; for (int i = 0; i < data.length; i++) data[i] = (short) (i * 11 - 100);
            data[3] = -32768;
            HiResRaster.writeEnvi(d.resolve("a.raw"), 7, 5, data);
            try (HiResRaster h = HiResRaster.open(d.resolve("a.raw"))) {
                check(h.cols == 7 && h.rows == 5, "dims");
                check(h.raw(3, 0) == -32768 && h.raw(6, 4) == data[34] && h.raw(0, 1) == data[7], "values");
            }
            Files.move(d.resolve("a.hdr"), d.resolve("a.raw.hdr"));
            try (HiResRaster h = HiResRaster.open(d.resolve("a.raw"))) { check(h.raw(1, 1) == data[8], "alt header name"); }
            Files.write(d.resolve("a.raw"), new byte[10]);
            try { HiResRaster.open(d.resolve("a.raw")); throw new AssertionError("short file accepted"); } catch (IOException expected) { }
            try { new HiResTerrain(HiResRaster.fromArray(7, 5, data, (short) -32768), SMALL); throw new AssertionError("misfit accepted"); } catch (IllegalArgumentException expected) { }
        });

        System.out.println("\n== Stage 2: corridor search on a synthetic 463 m raster (test fixtures only, not real terrain) ==");
        final Georef FINE = SMALL.refined(4);                    // 240 x 160 cells
        test("corridor widens until it finds the pass that avoids a steep ridge", () -> {
            short[] z = ridge(FINE, 60, 80);                     // gap in the ridge at rows 60..80
            HiResTerrain hi = new HiResTerrain(HiResRaster.fromArray(FINE.cols, FINE.rows, z, (short) -32768), SMALL);
            double[] lon = lonOf(FINE, 20.5, 220.5), lat = latOf(FINE, 20.5, 20.5);          // general route cuts straight across the ridge
            Refiner.Result r = Refiner.refine(hi, new CostModel(), lon, lat, new Refiner.Params());
            check(r.found, r.reason);
            check(r.attempts.size() >= 2, "expected the corridor to widen, attempts=" + r.attempts.size());
            RouteStats before = RouteStats.compute(hi, lon, lat, 10), after = RouteStats.compute(hi, r.lon, r.lat, 10);
            check(after.maxTerrainSlopeDeg < before.maxTerrainSlopeDeg - 10, String.format("max terrain slope %.1f -> %.1f", before.maxTerrainSlopeDeg, after.maxTerrainSlopeDeg));
            check(after.steepSections == 0 && before.steepSections >= 1, "steep sections " + before.steepSections + " -> " + after.steepSections);
            near(r.lon[0], lon[0], 1e-9, "starts at the clicked point"); near(r.lat[r.lat.length - 1], lat[1], 1e-9, "ends at the clicked point");
            info(String.format("max terrain slope %.1f -> %.1f deg, distance %.1f -> %.1f km, attempts: %s", before.maxTerrainSlopeDeg, after.maxTerrainSlopeDeg,
                    before.distanceM / 1000, after.distanceM / 1000, RoutePlanner.describeAttempts(r)));
        });
        test("corridor limit is respected: pass out of reach -> route still found but steep; hard limit -> clear failure", () -> {
            short[] z = ridge(FINE, 135, 155);                   // gap too far away for the default 40 km limit
            HiResTerrain hi = new HiResTerrain(HiResRaster.fromArray(FINE.cols, FINE.rows, z, (short) -32768), SMALL);
            double[] lon = lonOf(FINE, 20.5, 220.5), lat = latOf(FINE, 20.5, 20.5);
            Refiner.Result r = Refiner.refine(hi, new CostModel(), lon, lat, new Refiner.Params());
            check(r.found, "a (steep) route inside the corridor still exists");
            check(r.radiusUsedM <= new Refiner.Params().maxRadiusM + 1, "radius limit");
            CostModel strict = new CostModel(); strict.maxSlopeDeg = 20;
            Refiner.Result f = Refiner.refine(hi, strict, lon, lat, new Refiner.Params());
            check(!f.found && f.reason.contains("no high-resolution route"), "should fail clearly: " + f.reason);
            check(f.attempts.get(f.attempts.size() - 1).radiusM >= new Refiner.Params().maxRadiusM - 1, "it must try the full allowed radius before giving up");
        });
        test("route near the raster boundary: no false 'hugging the edge' loop", () -> {
            short[] z = new short[FINE.cols * FINE.rows];
            HiResTerrain hi = new HiResTerrain(HiResRaster.fromArray(FINE.cols, FINE.rows, z, (short) -32768), SMALL);
            double[] lon = lonOf(FINE, 0.6, 239.4), lat = latOf(FINE, 0.6, 0.6);
            Refiner.Result r = Refiner.refine(hi, new CostModel(), lon, lat, new Refiner.Params());
            check(r.found && r.attempts.size() == 1, "flat route along the boundary: attempts=" + r.attempts.size());
        });

        System.out.println("\n== Stage 1 -> Stage 2 -> HTTP API on real low-res data + SYNTHETIC high-res (plumbing test only) ==");
        Path tmp = Files.createTempDirectory("marswalk-hires");
        Path hiFile = tmp.resolve("mc13_elevation_463m.raw");
        test("synthetic 463 m file: write, memory-map, refine a real general route", () -> {
            Georef lowG = real.georef(); Georef hiG = lowG.refined(4);
            short[] z = new short[hiG.cols * hiG.rows];
            for (int r = 0; r < hiG.rows; r++) for (int c = 0; c < hiG.cols; c++)
                z[r * hiG.cols + c] = (short) Math.round(real.elevationAt(hiG.centerLon(c), hiG.centerLat(r)));   // bilinear upsample: NO new detail
            HiResRaster.writeEnvi(hiFile, hiG.cols, hiG.rows, z);
            long mb = Files.size(hiFile) / 1048576;
            try (HiResRaster raster = HiResRaster.open(hiFile)) {
                RoutePlanner rp = new RoutePlanner(real, raster);
                RoutePlanner.General gen = rp.planGeneral(89.5, 29.0, 89.5, 1.0, new CostModel());
                long t0 = System.nanoTime();
                RoutePlanner.Refined ref = rp.refine(gen, new Refiner.Params());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                check(ref.search.found, ref.search.reason);
                check(ref.stats.unknownElevationSamples == 0, "NoData crossed");
                check(Math.abs(ref.stats.distanceM / gen.stats.distanceM - 1) < 0.12, "refined distance is wildly different");
                info(String.format("raster file %d MB (flat file, memory-mapped); refine took %d ms, heap %.0f MB, attempts %s", mb, ms, ref.usedMemoryMb, RoutePlanner.describeAttempts(ref.search)));
                info(String.format("general route on its own grid: %.0f km | refined: %.0f km | adjustments (>1 km off): %d, max deviation %.0f m",
                        gen.stats.distanceM / 1000, ref.stats.distanceM / 1000, ref.adjustments, ref.maxDeviationM));
            }
        });
        test("HTTP API: info, general, refine, grids, errors", () -> {
            try (HiResRaster raster = HiResRaster.open(hiFile)) {
                MarswalkServer srv = new MarswalkServer(new RoutePlanner(real, raster), root.resolve("frontend"), root.resolve("data/color/mc13_color.png"));
                srv.start(0);
                String base = "http://localhost:" + srv.port();
                HttpClient http = HttpClient.newHttpClient();
                try {
                    HttpResponse<String> i = get(http, base + "/api/info");
                    check(i.statusCode() == 200 && i.body().contains("\"available\":true") && i.body().contains("\"west\":45.00000"), i.body());
                    HttpResponse<String> g = get(http, base + "/api/route/general?startLon=60&startLat=25&endLon=80&endLat=5");
                    check(g.statusCode() == 200 && g.body().contains("\"points\":[[60.00000,25.00000]"), g.body().substring(0, 200));
                    String id = g.body().replaceAll(".*\"id\":\"([0-9a-f]+)\".*", "$1").substring(0, 8);
                    HttpResponse<String> r = get(http, base + "/api/route/refine?id=" + id);
                    check(r.statusCode() == 200 && r.body().contains("\"stage\":\"optimized\"") && r.body().contains("\"generalOnHires\""), r.body().substring(0, 200));
                    check(get(http, base + "/api/route/refine?id=nope").statusCode() == 404, "unknown id");
                    check(get(http, base + "/api/route/general?startLon=60&startLat=25&endLon=80").statusCode() == 400, "missing param");
                    check(get(http, base + "/api/route/general?startLon=10&startLat=25&endLon=80&endLat=5").statusCode() == 422, "outside region");
                    check(get(http, base + "/api/route/general?startLon=60&startLat=25&endLon=80&endLat=5&wSlope=-1").statusCode() == 400, "bad weight");
                    check(get(http, base + "/../etc/passwd").statusCode() == 404, "path traversal");
                    HttpResponse<byte[]> e = http.send(HttpRequest.newBuilder(URI.create(base + "/api/grid/elevation.i16")).build(), HttpResponse.BodyHandlers.ofByteArray());
                    check(e.body().length == 1440 * 960 * 2, "grid bytes " + e.body().length);
                    int v = ((e.body()[0] & 0xff) << 8) | (e.body()[1] & 0xff);          // big-endian
                    near((short) v, real.elevation(0, 0), 0.5, "first grid value");
                    HttpResponse<byte[]> png = http.send(HttpRequest.newBuilder(URI.create(base + "/api/layer/color.png")).build(), HttpResponse.BodyHandlers.ofByteArray());
                    check(png.statusCode() == 200 && png.body().length > 1_000_000 && png.body()[1] == 'P', "color png");
                } finally { srv.stop(); }
            }
            // without the 463 m raster the refine endpoint must say so, and the general route must still work
            MarswalkServer s2 = new MarswalkServer(new RoutePlanner(real, null), root.resolve("frontend"), root.resolve("data/color/mc13_color.png"));
            s2.start(0);
            try {
                HttpClient http = HttpClient.newHttpClient(); String base = "http://localhost:" + s2.port();
                HttpResponse<String> g = get(http, base + "/api/route/general?startLon=60&startLat=25&endLon=80&endLat=5");
                check(g.statusCode() == 200 && g.body().contains("\"hiresAvailable\":false"), "general without hires");
                String id = g.body().replaceAll(".*\"id\":\"([0-9a-f]+)\".*", "$1").substring(0, 8);
                HttpResponse<String> r = get(http, base + "/api/route/refine?id=" + id);
                check(r.statusCode() == 503 && r.body().contains("hires_unavailable"), r.body());
            } finally { s2.stop(); }
        });

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static HttpResponse<String> get(HttpClient h, String url) throws Exception {
        return h.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Flat terrain with a 3,000 m ridge across columns 110-130 (steep flanks); the ridge is absent for rows gapFrom..gapTo. */
    static short[] ridge(Georef g, int gapFrom, int gapTo) {
        short[] z = new short[g.cols * g.rows];
        for (int r = 0; r < g.rows; r++) for (int c = 0; c < g.cols; c++) {
            double h = 0;
            if (r < gapFrom || r > gapTo) {
                double d = Math.abs(c - 120);
                if (d <= 10) h = 3000; else if (d <= 20) { double u = (20 - d) / 10.0; h = 3000 * u * u * (3 - 2 * u); }
            }
            z[r * g.cols + c] = (short) Math.round(h);
        }
        return z;
    }
}

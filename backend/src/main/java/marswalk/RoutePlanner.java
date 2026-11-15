package marswalk;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Ties the two navigation stages together. */
public final class RoutePlanner {
    private final GridTerrain low;
    private final HiResRaster hires;       // null when the 463 m raster is not installed

    public RoutePlanner(GridTerrain low, HiResRaster hires) {
        this.low = low;
        this.hires = hires;
    }

    public GridTerrain lowres() { return low; }
    public boolean hiresAvailable() { return hires != null; }
    public HiResRaster hires() { return hires; }

    // ---------------------------------------------------------------- stage 1
    public static final class General {
        public String id;
        public double startLon, startLat, endLon, endLat;
        public double[] lon, lat;
        public RouteStats stats;
        public CostModel cost;
        public double costValue;
        public int expanded;
        public long millis;
    }

    /** @throws IllegalArgumentException for points outside MC-13 or when no route exists */
    public General planGeneral(double sLon, double sLat, double eLon, double eLat, CostModel cm) {
        cm.validate();
        Georef g = low.georef();
        if (!g.contains(sLon, sLat)) throw new IllegalArgumentException("The start point is outside the MC-13 region (45-90 E, 0-30 N).");
        if (!g.contains(eLon, eLat)) throw new IllegalArgumentException("The destination is outside the MC-13 region (45-90 E, 0-30 N).");
        long t0 = System.nanoTime();
        int sc = clamp(g.col(sLon), g.cols), sr = clamp(g.row(sLat), g.rows);
        int gc = clamp(g.col(eLon), g.cols), gr = clamp(g.row(eLat), g.rows);
        if (!cm.elevationAllowed(low.elevation(sc, sr)))
            throw new IllegalArgumentException("The start point is outside your elevation limits (" + Math.round(low.elevation(sc, sr)) + " m).");
        if (!cm.elevationAllowed(low.elevation(gc, gr)))
            throw new IllegalArgumentException("The destination is outside your elevation limits (" + Math.round(low.elevation(gc, gr)) + " m).");
        AStar.Result r = AStar.search(low, cm, sc, sr, gc, gr, null, true, Long.MAX_VALUE);
        if (!r.found) throw new IllegalArgumentException("No general route found: " + r.reason + ".");

        int m = r.path.length + 2;
        double[] lon = new double[m], lat = new double[m];
        lon[0] = sLon; lat[0] = sLat;
        for (int i = 0; i < r.path.length; i++) {
            lon[i + 1] = g.centerLon(r.path[i] % g.cols);
            lat[i + 1] = g.centerLat(r.path[i] / g.cols);
        }
        lon[m - 1] = eLon; lat[m - 1] = eLat;

        General out = new General();
        out.id = UUID.randomUUID().toString().substring(0, 8);
        out.startLon = sLon; out.startLat = sLat; out.endLon = eLon; out.endLat = eLat;
        out.lon = lon; out.lat = lat;
        out.cost = cm.copy();
        out.costValue = r.cost;
        out.expanded = r.expanded;
        out.stats = RouteStats.compute(low, lon, lat, 10.0);
        out.millis = (System.nanoTime() - t0) / 1_000_000;
        return out;
    }

    private static int clamp(int v, int n) { return Math.max(0, Math.min(n - 1, v)); }

    // ---------------------------------------------------------------- stage 2
    public static final class Refined {
        public Refiner.Result search;
        public RouteStats stats;            // the refined route on the 463 m terrain
        public RouteStats generalOnHires;   // the general route, measured on the same 463 m terrain
        public int adjustments;             // stretches where the route leaves the general route by > thresholdM
        public double maxDeviationM, thresholdM;
        public long millis;
        public double usedMemoryMb;
    }

    public Refined refine(General gen, Refiner.Params p) {
        if (hires == null) throw new IllegalStateException("The high-resolution raster is not installed.");
        long t0 = System.nanoTime();
        HiResTerrain hi = new HiResTerrain(hires, low.georef());
        Refined out = new Refined();
        out.search = Refiner.refine(hi, gen.cost, gen.lon, gen.lat, p);
        if (out.search.found) {
            out.stats = RouteStats.compute(hi, out.search.lon, out.search.lat, 10.0);
            out.generalOnHires = RouteStats.compute(hi, gen.lon, gen.lat, 10.0);
            out.thresholdM = 1000.0;
            double[] dev = deviations(hi.georef(), out.search.lon, out.search.lat, gen.lon, gen.lat);
            boolean inRun = false;
            for (double d : dev) {
                out.maxDeviationM = Math.max(out.maxDeviationM, d);
                boolean far = d > out.thresholdM;
                if (far && !inRun) out.adjustments++;
                inRun = far;
            }
        }
        out.millis = (System.nanoTime() - t0) / 1_000_000;
        Runtime rt = Runtime.getRuntime();
        out.usedMemoryMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
        return out;
    }

    /** Distance (m) from each point of route A to the polyline B. */
    static double[] deviations(Georef g, double[] aLon, double[] aLat, double[] bLon, double[] bLat) {
        double[] out = new double[aLon.length];
        for (int i = 0; i < aLon.length; i++) {
            double best = Double.MAX_VALUE;
            double k = Math.cos(Math.toRadians(aLat[i])) * g.radius * Math.PI / 180.0;
            double m = g.radius * Math.PI / 180.0;
            double px = aLon[i] * k, py = aLat[i] * m;
            for (int j = 0; j + 1 < bLon.length; j++) {
                double x0 = bLon[j] * k, y0 = bLat[j] * m, x1 = bLon[j + 1] * k, y1 = bLat[j + 1] * m;
                double dx = x1 - x0, dy = y1 - y0, len2 = dx * dx + dy * dy;
                double u = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - x0) * dx + (py - y0) * dy) / len2));
                best = Math.min(best, Math.hypot(px - (x0 + u * dx), py - (y0 + u * dy)));
            }
            out[i] = best;
        }
        return out;
    }

    public static List<String> describeAttempts(Refiner.Result r) {
        List<String> l = new ArrayList<>();
        for (Refiner.Attempt a : r.attempts)
            l.add(String.format("radius %.0f m: %s, %d cells searched, %d ms", a.radiusM, a.found ? "route found" : a.reason, a.expanded, a.millis));
        return l;
    }
}

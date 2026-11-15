package marswalk;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 2: improve the general route using the high-resolution terrain, looking only in a corridor
 * around the general route. If no route fits (or the best route is pressed against the corridor wall,
 * which suggests a better one lies outside), the corridor grows step by step up to a configurable limit.
 */
public final class Refiner {
    private Refiner() {}

    public static final class Params {
        public double initialRadiusM = 5_557;     // about 3 coarse cells either side of the general route
        public double growth = 2.0;               // corridor radius multiplier per expansion
        public double maxRadiusM = 40_000;        // hard limit on how far the search may spread
        public boolean expandWhenHuggingEdge = true;
        /** Widen the corridor while the best route inside it still crosses ground steeper than this (degrees, 3x3 cell slope). */
        public double expandSlopeDeg = 10.0;
        public boolean sixteenNeighbours = true;  // smoother, less "grid-like" routes than 8 neighbours
        public long maxExpansionsPerSearch = 30_000_000L;
    }

    public static final class Attempt {
        public double radiusM; public boolean found; public String reason;
        public double cost; public int expanded, edgeCells, corridorCells; public long millis;
    }

    public static final class Result {
        public boolean found; public String reason = "";
        public double[] lon, lat;
        public double cost;
        public double radiusUsedM;
        public final List<Attempt> attempts = new ArrayList<>();
    }

    private static double maxCellSlope(Terrain t, int[] path) {
        int cols = t.georef().cols;
        double m = 0;
        for (int k : path) { float sl = t.slopeDeg(k % cols, k / cols); if (!Float.isNaN(sl)) m = Math.max(m, sl); }
        return m;
    }

    /** @param lon,lat the general route polyline, including the exact start and destination points */
    public static Result refine(HiResTerrain hi, CostModel cm, double[] lon, double[] lat, Params p) {
        Georef g = hi.georef();
        double cellM = g.southMeters();
        int n = lon.length;
        double[] colF = new double[n], rowF = new double[n];
        for (int i = 0; i < n; i++) { colF[i] = g.colF(lon[i]); rowF[i] = g.rowF(lat[i]); }
        int sc = (int) Math.floor(colF[0]), sr = (int) Math.floor(rowF[0]);
        int gc = (int) Math.floor(colF[n - 1]), gr = (int) Math.floor(rowF[n - 1]);

        Result out = new Result();
        AStar.Result best = null;
        double bestRadius = 0, radius = p.initialRadiusM;
        while (true) {
            long t0 = System.nanoTime();
            Corridor cor = Corridor.around(colF, rowF, radius / cellM, g.cols, g.rows);
            AStar.Result r = AStar.search(hi, cm, sc, sr, gc, gr, cor, p.sixteenNeighbours, p.maxExpansionsPerSearch);
            Attempt a = new Attempt();
            a.radiusM = radius; a.found = r.found; a.reason = r.reason; a.expanded = r.expanded;
            a.corridorCells = cor.cellCount();
            boolean hugging = false, steep = false;
            if (r.found) {
                a.cost = r.cost;
                a.edgeCells = cor.nearEdgeCount(r.path);
                if (best == null || r.cost < best.cost) { best = r; bestRadius = radius; }
                hugging = p.expandWhenHuggingEdge && a.edgeCells >= Math.max(5, r.path.length / 100);
                steep = maxCellSlope(hi, r.path) >= p.expandSlopeDeg;
            }
            a.millis = (System.nanoTime() - t0) / 1_000_000;
            out.attempts.add(a);

            // Widen when: nothing fit, the route is pressed against the corridor wall, or it still has to cross steep ground
            // (a gentler pass may exist further out). Always bounded by maxRadiusM.
            boolean needMore = !r.found || hugging || steep;
            if (!needMore || radius >= p.maxRadiusM) break;
            radius = Math.min(p.maxRadiusM, radius * p.growth);
        }
        if (best == null) {
            out.found = false;
            out.reason = "no high-resolution route found within " + Math.round(p.maxRadiusM) + " m of the general route ("
                    + out.attempts.get(out.attempts.size() - 1).reason + ")";
            return out;
        }
        out.found = true;
        out.cost = best.cost;
        out.radiusUsedM = bestRadius;
        // cell centres, then begin and end exactly where the user clicked
        int m = Math.max(2, best.path.length);
        out.lon = new double[m]; out.lat = new double[m];
        for (int i = 0; i < best.path.length; i++) {
            int c = best.path[i] % g.cols, r = best.path[i] / g.cols;
            out.lon[i] = g.centerLon(c); out.lat[i] = g.centerLat(r);
        }
        out.lon[0] = lon[0]; out.lat[0] = lat[0];
        out.lon[m - 1] = lon[n - 1]; out.lat[m - 1] = lat[n - 1];
        return out;
    }
}

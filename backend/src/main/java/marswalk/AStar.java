package marswalk;

import java.util.Arrays;

/**
 * A* search on a raster of cells (8 or 16 neighbours), restricted to an optional region.
 * Works on any Terrain, so the same code runs on the 1,852 m grid and on windows of the 463 m raster.
 * The heuristic (straight-line ground distance using the smallest east-west cell width) never
 * overestimates the cost, so the route found is the cheapest under the CostModel.
 */
public final class AStar {
    private AStar() {}

    public interface Region { boolean contains(int col, int row); }

    public static final class Result {
        public final boolean found;
        public final String reason;
        /** Cell indices (row * cols + col) from start to goal. */
        public final int[] path;
        public final double cost;
        public final int expanded;
        Result(boolean found, String reason, int[] path, double cost, int expanded) {
            this.found = found; this.reason = reason; this.path = path; this.cost = cost; this.expanded = expanded;
        }
    }

    private static final int[][] N8 = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    private static final int[][] N16 = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1},
            {2,1},{2,-1},{-2,1},{-2,-1},{1,2},{1,-2},{-1,2},{-1,-2}};

    public static Result search(Terrain t, CostModel cm, int sc, int sr, int gc, int gr,
                                Region region, boolean sixteen, long maxExpansions) {
        Georef g = t.georef();
        if (!passable(t, region, sc, sr)) return new Result(false, "start cell is outside the allowed area or has no elevation data", null, 0, 0);
        if (!passable(t, region, gc, gr)) return new Result(false, "destination cell is outside the allowed area or has no elevation data", null, 0, 0);
        int startKey = sr * g.cols + sc, goalKey = gr * g.cols + gc;
        if (startKey == goalKey) return new Result(true, "", new int[]{startKey}, 0, 0);

        long cells = (long) g.cols * g.rows;
        NodeStore store = cells <= 4_000_000L ? new DenseStore((int) cells) : new SparseStore();
        MinHeap heap = new MinHeap();
        double hScale = cm.wDistance;
        double minEast = g.minEastMeters(), south = g.southMeters();

        int s0 = store.slot(startKey);
        store.setG(s0, 0f);
        store.setParent(s0, -1);
        heap.push((float) (hScale * Math.hypot((sc - gc) * minEast, (sr - gr) * south)), startKey);

        int[][] nb = sixteen ? N16 : N8;
        int expanded = 0;
        while (!heap.isEmpty()) {
            int key = heap.pop();
            int slot = store.slot(key);
            if (store.closed(slot)) continue;
            store.close(slot);
            if (key == goalKey) return reconstruct(t, cm, store, key, expanded);
            if (++expanded > maxExpansions) return new Result(false, "search limit reached", null, 0, expanded);

            int c = key % g.cols, r = key / g.cols;
            float gHere = store.g(slot);
            for (int[] d : nb) {
                int nc = c + d[0], nr = r + d[1];
                if (!passable(t, region, nc, nr)) continue;
                if (!cornersClear(t, region, c, r, d[0], d[1])) continue;
                int nkey = nr * g.cols + nc;
                int nslot = store.slot(nkey);
                if (store.closed(nslot)) continue;
                double step = cm.stepCost(t, c, r, nc, nr);
                if (Double.isInfinite(step)) continue;
                float ng = gHere + (float) step;
                if (ng < store.g(nslot)) {
                    store.setG(nslot, ng);
                    store.setParent(nslot, key);
                    float h = (float) (hScale * Math.hypot((nc - gc) * minEast, (nr - gr) * south));
                    heap.push(ng + h, nkey);
                }
            }
        }
        return new Result(false, "no route exists inside the allowed area", null, 0, expanded);
    }

    private static boolean passable(Terrain t, Region region, int c, int r) {
        if (!t.georef().inBounds(c, r)) return false;
        if (region != null && !region.contains(c, r)) return false;
        return !Float.isNaN(t.elevation(c, r));
    }

    /** Diagonal and knight moves must not slip between two blocked cells. */
    private static boolean cornersClear(Terrain t, Region region, int c, int r, int dx, int dy) {
        if (Math.abs(dx) + Math.abs(dy) == 1) return true;
        if (Math.abs(dx) == 2) {
            int mx = c + Integer.signum(dx);
            return passable(t, region, mx, r) && passable(t, region, mx, r + dy);
        }
        if (Math.abs(dy) == 2) {
            int my = r + Integer.signum(dy);
            return passable(t, region, c, my) && passable(t, region, c + dx, my);
        }
        return passable(t, region, c + dx, r) && passable(t, region, c, r + dy);
    }

    private static Result reconstruct(Terrain t, CostModel cm, NodeStore store, int goalKey, int expanded) {
        int n = 0;
        for (int k = goalKey; k != -1; k = store.parent(store.slot(k))) n++;
        int[] path = new int[n];
        int i = n - 1;
        for (int k = goalKey; k != -1; k = store.parent(store.slot(k))) path[i--] = k;
        int cols = t.georef().cols;
        double cost = 0;
        for (int j = 1; j < n; j++)
            cost += cm.stepCost(t, path[j - 1] % cols, path[j - 1] / cols, path[j] % cols, path[j] / cols);
        return new Result(true, "", path, cost, expanded);
    }

    // ---- storage ------------------------------------------------------------
    private interface NodeStore {
        int slot(int key);
        float g(int slot); void setG(int slot, float g);
        int parent(int slot); void setParent(int slot, int parentKey);
        boolean closed(int slot); void close(int slot);
    }

    private static final class DenseStore implements NodeStore {
        final float[] g; final int[] parent; final boolean[] closed;
        DenseStore(int n) { g = new float[n]; Arrays.fill(g, Float.POSITIVE_INFINITY); parent = new int[n]; closed = new boolean[n]; }
        public int slot(int key) { return key; }
        public float g(int s) { return g[s]; } public void setG(int s, float v) { g[s] = v; }
        public int parent(int s) { return parent[s]; } public void setParent(int s, int p) { parent[s] = p; }
        public boolean closed(int s) { return closed[s]; } public void close(int s) { closed[s] = true; }
    }

    /** Open-addressing hash so memory grows with the cells actually visited (corridor searches on the big raster). */
    private static final class SparseStore implements NodeStore {
        int[] keys = new int[1 << 16]; float[] g = new float[1 << 16]; int[] parent = new int[1 << 16]; boolean[] closed = new boolean[1 << 16];
        int used = 0;
        SparseStore() { Arrays.fill(keys, -1); Arrays.fill(g, Float.POSITIVE_INFINITY); }
        public int slot(int key) {
            int mask = keys.length - 1;
            int i = (key * 0x9E3779B1) >>> 7 & mask;
            while (true) {
                if (keys[i] == key) return i;
                if (keys[i] == -1) {
                    if ((used + 1) * 2 > keys.length) { grow(); return slot(key); }
                    keys[i] = key; used++; return i;
                }
                i = (i + 1) & mask;
            }
        }
        private void grow() {
            int[] ok = keys; float[] og = g; int[] op = parent; boolean[] oc = closed;
            int n = ok.length * 2;
            keys = new int[n]; g = new float[n]; parent = new int[n]; closed = new boolean[n];
            Arrays.fill(keys, -1); Arrays.fill(g, Float.POSITIVE_INFINITY);
            used = 0;
            for (int i = 0; i < ok.length; i++) if (ok[i] != -1) {
                int s = slot(ok[i]); g[s] = og[i]; parent[s] = op[i]; closed[s] = oc[i];
            }
        }
        public float g(int s) { return g[s]; } public void setG(int s, float v) { g[s] = v; }
        public int parent(int s) { return parent[s]; } public void setParent(int s, int p) { parent[s] = p; }
        public boolean closed(int s) { return closed[s]; } public void close(int s) { closed[s] = true; }
    }

    /** Binary min-heap of (priority, node) pairs; duplicates are allowed (stale ones are skipped on pop). */
    private static final class MinHeap {
        float[] key = new float[1 << 12]; int[] val = new int[1 << 12]; int size = 0;
        boolean isEmpty() { return size == 0; }
        void push(float k, int v) {
            if (size == key.length) { key = Arrays.copyOf(key, size * 2); val = Arrays.copyOf(val, size * 2); }
            int i = size++;
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (key[p] <= k) break;
                key[i] = key[p]; val[i] = val[p]; i = p;
            }
            key[i] = k; val[i] = v;
        }
        int pop() {
            int top = val[0];
            size--;
            if (size > 0) {
                float k = key[size]; int v = val[size];
                int i = 0;
                while (true) {
                    int l = 2 * i + 1;
                    if (l >= size) break;
                    int r = l + 1;
                    int m = (r < size && key[r] < key[l]) ? r : l;
                    if (key[m] >= k) break;
                    key[i] = key[m]; val[i] = val[m]; i = m;
                }
                key[i] = k; val[i] = v;
            }
            return top;
        }
    }
}

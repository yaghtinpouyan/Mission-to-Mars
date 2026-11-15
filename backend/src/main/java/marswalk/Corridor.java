package marswalk;

import java.util.BitSet;

/** The set of high-resolution cells within a given distance of the general route (a "buffer"). */
public final class Corridor implements AStar.Region {
    private final int col0, row0, w, h, rasterCols, rasterRows;
    private final BitSet bits;

    private Corridor(int col0, int row0, int w, int h, int rasterCols, int rasterRows, BitSet bits) {
        this.col0 = col0; this.row0 = row0; this.w = w; this.h = h;
        this.rasterCols = rasterCols; this.rasterRows = rasterRows; this.bits = bits;
    }

    /** @param colF,rowF the route as fractional cell coordinates (cell-edge based, like Georef.colF/rowF) */
    public static Corridor around(double[] colF, double[] rowF, double radiusCells, int rasterCols, int rasterRows) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < colF.length; i++) {
            minX = Math.min(minX, colF[i]); maxX = Math.max(maxX, colF[i]);
            minY = Math.min(minY, rowF[i]); maxY = Math.max(maxY, rowF[i]);
        }
        int c0 = Math.max(0, (int) Math.floor(minX - radiusCells) - 1);
        int r0 = Math.max(0, (int) Math.floor(minY - radiusCells) - 1);
        int c1 = Math.min(rasterCols - 1, (int) Math.ceil(maxX + radiusCells) + 1);
        int r1 = Math.min(rasterRows - 1, (int) Math.ceil(maxY + radiusCells) + 1);
        int w = c1 - c0 + 1, h = r1 - r0 + 1;
        BitSet bits = new BitSet(w * h);
        Corridor cor = new Corridor(c0, r0, w, h, rasterCols, rasterRows, bits);
        for (int i = 0; i < colF.length; i++) {
            cor.stamp(colF[i], rowF[i], radiusCells);
            if (i + 1 < colF.length) {
                double dx = colF[i + 1] - colF[i], dy = rowF[i + 1] - rowF[i];
                int n = (int) Math.ceil(Math.hypot(dx, dy) / 0.5);          // a disc every half cell
                for (int k = 1; k < n; k++) cor.stamp(colF[i] + dx * k / n, rowF[i] + dy * k / n, radiusCells);
            }
        }
        return cor;
    }

    /** Marks every cell whose centre is within `radius` cells of the point (px, py). */
    private void stamp(double px, double py, double radius) {
        int rLo = Math.max(row0, (int) Math.ceil(py - radius - 0.5));
        int rHi = Math.min(row0 + h - 1, (int) Math.floor(py + radius - 0.5));
        for (int r = rLo; r <= rHi; r++) {
            double dy = (r + 0.5) - py;
            double half = Math.sqrt(Math.max(0.0, radius * radius - dy * dy));
            int cLo = Math.max(col0, (int) Math.ceil(px - half - 0.5));
            int cHi = Math.min(col0 + w - 1, (int) Math.floor(px + half - 0.5));
            if (cHi >= cLo) bits.set((r - row0) * w + (cLo - col0), (r - row0) * w + (cHi - col0) + 1);
        }
    }

    @Override public boolean contains(int col, int row) {
        if (col < col0 || row < row0 || col >= col0 + w || row >= row0 + h) return false;
        return bits.get((row - row0) * w + (col - col0));
    }

    public int cellCount() { return bits.cardinality(); }

    /**
     * How many route cells run right along the edge of the corridor (so a better route may exist outside it)?
     * The raster boundary does not count as a corridor edge.
     */
    public int nearEdgeCount(int[] path) {
        int[][] ring = {{2,0},{-2,0},{0,2},{0,-2},{2,2},{2,-2},{-2,2},{-2,-2}};
        int count = 0;
        for (int key : path) {
            int c = key % rasterCols, r = key / rasterCols;
            for (int[] d : ring) {
                int nc = c + d[0], nr = r + d[1];
                if (nc < 0 || nr < 0 || nc >= rasterCols || nr >= rasterRows) continue;
                if (!contains(nc, nr)) { count++; break; }
            }
        }
        return count;
    }
}

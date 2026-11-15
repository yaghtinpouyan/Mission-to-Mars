package marswalk;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.StringTokenizer;

/** Reader for ESRI/Arc ASCII grids (.asc). Handles LF and CRLF line endings. */
public final class AsciiGrid {
    public final int ncols, nrows;
    public final double xllcorner, yllcorner, cellsize, nodata;
    /** Row-major, row 0 = north. NoData cells are converted to NaN. */
    public final float[] data;
    public final int nodataCount;

    private AsciiGrid(int ncols, int nrows, double xll, double yll, double cell, double nodata, float[] data, int nd) {
        this.ncols = ncols; this.nrows = nrows; this.xllcorner = xll; this.yllcorner = yll;
        this.cellsize = cell; this.nodata = nodata; this.data = data; this.nodataCount = nd;
    }

    public static AsciiGrid read(Path file) throws IOException {
        int ncols = -1, nrows = -1;
        double xll = Double.NaN, yll = Double.NaN, cell = Double.NaN, nodata = -9999;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.US_ASCII)) {
            String line;
            // header: key value lines until the first line that starts with a number
            in.mark(1 << 16);
            while (true) {
                in.mark(1 << 16);
                line = in.readLine();
                if (line == null) throw new IOException("Unexpected end of file in header: " + file);
                line = line.trim();
                if (line.isEmpty()) continue;
                StringTokenizer st = new StringTokenizer(line);
                String key = st.nextToken().toLowerCase();
                if (Character.isDigit(key.charAt(0)) || key.charAt(0) == '-' || key.charAt(0) == '.') { in.reset(); break; }
                String val = st.nextToken();
                switch (key) {
                    case "ncols" -> ncols = Integer.parseInt(val);
                    case "nrows" -> nrows = Integer.parseInt(val);
                    case "xllcorner" -> xll = Double.parseDouble(val);
                    case "yllcorner" -> yll = Double.parseDouble(val);
                    case "xllcenter", "yllcenter" -> throw new IOException("xllcenter/yllcenter headers are not supported: " + file);
                    case "cellsize" -> cell = Double.parseDouble(val);
                    case "nodata_value" -> nodata = Double.parseDouble(val);
                    default -> throw new IOException("Unknown header key '" + key + "' in " + file);
                }
            }
            if (ncols <= 0 || nrows <= 0 || Double.isNaN(xll) || Double.isNaN(yll) || Double.isNaN(cell))
                throw new IOException("Incomplete ASCII grid header in " + file);

            float[] data = new float[ncols * nrows];
            int n = 0, nd = 0;
            while ((line = in.readLine()) != null) {
                StringTokenizer st = new StringTokenizer(line);
                while (st.hasMoreTokens()) {
                    if (n >= data.length) throw new IOException("More values than ncols*nrows in " + file);
                    double v = Double.parseDouble(st.nextToken());
                    if (v == nodata) { data[n++] = Float.NaN; nd++; } else data[n++] = (float) v;
                }
            }
            if (n != data.length) throw new IOException("Expected " + data.length + " values but found " + n + " in " + file);
            return new AsciiGrid(ncols, nrows, xll, yll, cell, nodata, data, nd);
        }
    }

    /** Degrees per cell implied by the header for a sphere of the given radius. */
    public double degPerCell(double radius) { return Math.toDegrees(cellsize / radius); }
    public double westDeg(double radius) { return Math.toDegrees(xllcorner / radius); }
    public double northDeg(double radius) { return Math.toDegrees((yllcorner + nrows * cellsize) / radius); }
}

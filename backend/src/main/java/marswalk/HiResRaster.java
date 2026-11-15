package marswalk;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The full-resolution (~463 m) elevation raster for MC-13, stored as a flat 16-bit file plus an ENVI
 * text header (what GDAL writes with "-of ENVI"). The file is memory-mapped, so only the parts of the
 * raster that a route search actually touches are ever read from disk - the whole file is never loaded.
 * (5760 x 3840 cells x 2 bytes = about 44 MB.)
 */
public final class HiResRaster implements AutoCloseable {
    public final int cols, rows;
    public final short nodata;
    private final ByteBuffer buf;          // already set to the file's byte order
    private final FileChannel channel;     // null for in-memory rasters

    private HiResRaster(int cols, int rows, short nodata, ByteBuffer buf, FileChannel ch) {
        this.cols = cols; this.rows = rows; this.nodata = nodata; this.buf = buf; this.channel = ch;
    }

    /** Raw 16-bit value (metres) at a cell; callers must check bounds. */
    public short raw(int col, int row) { return buf.getShort((row * cols + col) * 2); }

    /** Opens file.raw (or .bin/.i16...) together with its ENVI header (file.hdr or file.raw.hdr). */
    public static HiResRaster open(Path raw) throws IOException {
        Path hdr = findHeader(raw);
        Map<String, String> h = parseHeader(hdr);
        int cols = Integer.parseInt(req(h, "samples", hdr));
        int rows = Integer.parseInt(req(h, "lines", hdr));
        int bands = Integer.parseInt(h.getOrDefault("bands", "1"));
        int type = Integer.parseInt(req(h, "data type", hdr));
        int order = Integer.parseInt(h.getOrDefault("byte order", "0"));
        int offset = Integer.parseInt(h.getOrDefault("header offset", "0"));
        short nodata = (short) Math.round(Double.parseDouble(h.getOrDefault("data ignore value", "-32768")));
        if (bands != 1) throw new IOException("Expected 1 band, header says " + bands + " (" + hdr + ")");
        if (type != 2) throw new IOException("Expected ENVI data type 2 (16-bit signed integer), found " + type);
        long expected = (long) cols * rows * 2 + offset;
        long actual = Files.size(raw);
        if (actual < expected) throw new IOException("Raster file is " + actual + " bytes but header needs " + expected);
        RandomAccessFile f = new RandomAccessFile(raw.toFile(), "r");
        FileChannel ch = f.getChannel();
        ByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, offset, (long) cols * rows * 2);
        b.order(order == 1 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        return new HiResRaster(cols, rows, nodata, b, ch);
    }

    /** In-memory raster (used by tests and the synthetic demo only). */
    public static HiResRaster fromArray(int cols, int rows, short[] data, short nodata) {
        ByteBuffer b = ByteBuffer.allocate(cols * rows * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : data) b.putShort(v);
        return new HiResRaster(cols, rows, nodata, b, null);
    }

    /** Writes the same flat 16-bit little-endian file + ENVI header that "gdal_translate -of ENVI" produces. */
    public static void writeEnvi(Path raw, int cols, int rows, short[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(cols * rows * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : data) b.putShort(v);
        Files.write(raw, b.array());
        String hdr = "ENVI\nsamples = " + cols + "\nlines   = " + rows + "\nbands   = 1\nheader offset = 0\n"
                + "file type = ENVI Standard\ndata type = 2\ninterleave = bsq\nbyte order = 0\ndata ignore value = -32768\n";
        Files.writeString(raw.resolveSibling(stem(raw) + ".hdr"), hdr);
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static Path findHeader(Path raw) throws IOException {
        List<Path> candidates = new ArrayList<>();
        candidates.add(raw.resolveSibling(stem(raw) + ".hdr"));
        candidates.add(raw.resolveSibling(raw.getFileName() + ".hdr"));
        for (Path c : candidates) if (Files.exists(c)) return c;
        throw new IOException("No ENVI header found next to " + raw + " (looked for " + candidates + ")");
    }

    private static Map<String, String> parseHeader(Path hdr) throws IOException {
        Map<String, String> m = new HashMap<>();
        for (String line : Files.readAllLines(hdr, StandardCharsets.US_ASCII)) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            m.put(line.substring(0, eq).trim().toLowerCase(), line.substring(eq + 1).trim());
        }
        return m;
    }

    private static String req(Map<String, String> h, String k, Path hdr) throws IOException {
        String v = h.get(k);
        if (v == null) throw new IOException("ENVI header " + hdr + " has no '" + k + "'");
        return v;
    }

    @Override public void close() throws IOException { if (channel != null) channel.close(); }
}

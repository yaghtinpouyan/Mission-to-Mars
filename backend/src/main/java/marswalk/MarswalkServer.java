package marswalk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** HTTP API plus static file server for the frontend. Uses only the JDK. */
public final class MarswalkServer {
    private final RoutePlanner planner;
    private final Path frontendDir, colorPng;
    private final Map<String, RoutePlanner.General> recent = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, RoutePlanner.General> e) { return size() > 50; }
    };
    private byte[] elevationI16, slopeI16;
    private HttpServer http;

    public MarswalkServer(RoutePlanner planner, Path frontendDir, Path colorPng) {
        this.planner = planner; this.frontendDir = frontendDir; this.colorPng = colorPng;
        buildGridBytes();
    }

    public HttpServer start(int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/api/info", ex -> guarded(ex, this::info));
        http.createContext("/api/layer/color.png", ex -> guarded(ex, this::colorLayer));
        http.createContext("/api/grid/elevation.i16", ex -> guarded(ex, e -> bytes(e, 200, "application/octet-stream", elevationI16)));
        http.createContext("/api/grid/slope.i16", ex -> guarded(ex, e -> bytes(e, 200, "application/octet-stream", slopeI16)));
        http.createContext("/api/route/general", ex -> guarded(ex, this::general));
        http.createContext("/api/route/refine", ex -> guarded(ex, this::refine));
        http.createContext("/", ex -> guarded(ex, this::staticFile));
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.start();
        return http;
    }

    public int port() { return http.getAddress().getPort(); }
    public void stop() { http.stop(0); }

    // ------------------------------------------------------------------ endpoints
    private void info(HttpExchange ex) throws IOException {
        Georef g = planner.lowres().georef();
        JsonWriter w = new JsonWriter().beginObject();
        w.name("bounds").beginObject().field("west", g.west, 5).field("east", g.east(), 5)
                .field("south", g.south(), 5).field("north", g.north, 5).endObject();
        w.name("lowres").beginObject().field("cols", g.cols).field("rows", g.rows).field("cellM", g.southMeters(), 1).endObject();
        w.name("hires").beginObject().field("available", planner.hiresAvailable());
        if (planner.hiresAvailable()) {
            Georef h = g.refined(planner.hires().cols / g.cols);
            w.field("cols", h.cols).field("rows", h.rows).field("cellM", h.southMeters(), 1);
        }
        w.endObject();
        CostModel d = new CostModel();
        w.name("defaults").beginObject().field("wSlope", d.wSlope, 2).field("wClimb", d.wClimb, 2)
                .field("slopeRefDeg", d.slopeRefDeg, 1).endObject();
        w.field("radiusM", g.radius, 0);
        w.field("notice", "Candidate routes from terrain data only. Not validated against any rover's mobility limits.");
        w.endObject();
        json(ex, 200, w.toString());
    }

    private void colorLayer(HttpExchange ex) throws IOException {
        if (!Files.exists(colorPng)) { error(ex, 404, "not_found", "Colour map image not found: " + colorPng); return; }
        ex.getResponseHeaders().add("Cache-Control", "max-age=3600");
        bytes(ex, 200, "image/png", Files.readAllBytes(colorPng));
    }

    private void general(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        double sLon, sLat, eLon, eLat;
        try {
            sLon = num(q, "startLon"); sLat = num(q, "startLat"); eLon = num(q, "endLon"); eLat = num(q, "endLat");
        } catch (IllegalArgumentException e) { error(ex, 400, "bad_request", e.getMessage()); return; }
        CostModel cm;
        try { cm = costFrom(q); } catch (IllegalArgumentException e) { error(ex, 400, "bad_request", e.getMessage()); return; }
        RoutePlanner.General gen;
        try { gen = planner.planGeneral(sLon, sLat, eLon, eLat, cm); }
        catch (IllegalArgumentException e) { error(ex, 422, "no_route", e.getMessage()); return; }
        synchronized (recent) { recent.put(gen.id, gen); }

        JsonWriter w = new JsonWriter().beginObject();
        w.field("id", gen.id).field("stage", "general");
        w.name("route"); routeJson(w, gen.lon, gen.lat, gen.stats);
        w.name("meta").beginObject().field("resolutionM", planner.lowres().georef().southMeters(), 0)
                .field("millis", gen.millis).field("cellsSearched", gen.expanded).field("cost", gen.costValue, 0).endObject();
        w.name("hiresAvailable").value(planner.hiresAvailable());
        w.endObject();
        json(ex, 200, w.toString());
    }

    private void refine(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        if (!planner.hiresAvailable()) {
            error(ex, 503, "hires_unavailable", "The high-resolution (463 m) raster is not installed on the server, so only the general route is available.");
            return;
        }
        RoutePlanner.General gen;
        synchronized (recent) { gen = recent.get(q.get("id")); }
        if (gen == null) { error(ex, 404, "unknown_route", "Unknown or expired general route id. Calculate the general route again."); return; }
        Refiner.Params p = new Refiner.Params();
        try {
            if (q.containsKey("radiusM")) p.initialRadiusM = num(q, "radiusM");
            if (q.containsKey("maxRadiusM")) p.maxRadiusM = num(q, "maxRadiusM");
            if (p.initialRadiusM <= 0 || p.maxRadiusM < p.initialRadiusM) throw new IllegalArgumentException("radiusM must be > 0 and <= maxRadiusM");
        } catch (IllegalArgumentException e) { error(ex, 400, "bad_request", e.getMessage()); return; }

        RoutePlanner.Refined r = planner.refine(gen, p);
        if (!r.search.found) { error(ex, 422, "no_refined_route", r.search.reason); return; }
        JsonWriter w = new JsonWriter().beginObject();
        w.field("id", gen.id).field("stage", "optimized");
        w.name("route"); routeJson(w, r.search.lon, r.search.lat, r.stats);
        w.name("generalOnHires"); r.generalOnHires.write(w);
        w.name("adjustments").beginObject().field("count", r.adjustments).field("maxDeviationM", r.maxDeviationM, 0)
                .field("thresholdM", r.thresholdM, 0).endObject();
        w.name("meta").beginObject().field("resolutionM", planner.lowres().georef().southMeters() / (planner.hires().cols / planner.lowres().georef().cols), 0)
                .field("millis", r.millis).field("radiusUsedM", r.search.radiusUsedM, 0).field("heapMb", r.usedMemoryMb, 0);
        w.name("attempts").beginArray();
        for (Refiner.Attempt a : r.search.attempts)
            w.beginObject().field("radiusM", a.radiusM, 0).field("found", a.found).field("cellsSearched", a.expanded)
                    .field("corridorCells", a.corridorCells).field("edgeCells", a.edgeCells).field("millis", a.millis).endObject();
        w.endArray().endObject();
        w.endObject();
        json(ex, 200, w.toString());
    }

    private void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = frontendDir.resolve(path.substring(1)).normalize();
        if (!file.startsWith(frontendDir) || !Files.isRegularFile(file)) { error(ex, 404, "not_found", "No such file: " + path); return; }
        bytes(ex, 200, mime(file.getFileName().toString()), Files.readAllBytes(file));
    }

    // ------------------------------------------------------------------ helpers
    private void routeJson(JsonWriter w, double[] lon, double[] lat, RouteStats stats) {
        w.beginObject();
        w.name("points").beginArray();
        for (int i = 0; i < lon.length; i++) w.beginArray().value(lon[i], 5).value(lat[i], 5).endArray();
        w.endArray();
        w.name("stats"); stats.write(w);
        w.endObject();
    }

    private CostModel costFrom(Map<String, String> q) {
        CostModel c = new CostModel();
        if (q.containsKey("wSlope")) c.wSlope = num(q, "wSlope");
        if (q.containsKey("wClimb")) c.wClimb = num(q, "wClimb");
        if (q.containsKey("slopeRef")) c.slopeRefDeg = num(q, "slopeRef");
        if (q.containsKey("maxSlope") && !q.get("maxSlope").isBlank()) c.maxSlopeDeg = num(q, "maxSlope");
        if (q.containsKey("minElev") && !q.get("minElev").isBlank()) c.minElevM = num(q, "minElev");
        if (q.containsKey("maxElev") && !q.get("maxElev").isBlank()) c.maxElevM = num(q, "maxElev");
        c.validate();
        return c;
    }

    private void buildGridBytes() {
        GridTerrain t = planner.lowres();
        float[] e = t.elevationArray(), s = t.slopeArray();
        ByteBuffer be = ByteBuffer.allocate(e.length * 2), bs = ByteBuffer.allocate(s.length * 2);   // big-endian
        for (int i = 0; i < e.length; i++) {
            be.putShort(Float.isNaN(e[i]) ? (short) -32768 : (short) Math.round(e[i]));
            bs.putShort(Float.isNaN(s[i]) ? (short) -32768 : (short) Math.round(s[i] * 100.0));   // hundredths of a degree
        }
        elevationI16 = be.array(); slopeI16 = bs.array();
    }

    private static double num(Map<String, String> q, String k) {
        String v = q.get(k);
        if (v == null) throw new IllegalArgumentException("Missing parameter: " + k);
        try {
            double d = Double.parseDouble(v);
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Parameter " + k + " must be a number"); }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i), v = i < 0 ? "" : part.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private interface Handler { void handle(HttpExchange ex) throws IOException; }

    private void guarded(HttpExchange ex, Handler h) throws IOException {
        try {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");   // lets Live Server (another port) call the API during development
            if (!ex.getRequestMethod().equals("GET")) { error(ex, 405, "method_not_allowed", "Use GET"); return; }
            h.handle(ex);
        } catch (Throwable t) {
            t.printStackTrace();
            try { error(ex, 500, "server_error", "Internal error: " + t.getClass().getSimpleName()); } catch (IOException ignored) {}
        } finally {
            ex.close();
        }
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        bytes(ex, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void error(HttpExchange ex, int status, String code, String message) throws IOException {
        json(ex, status, new JsonWriter().beginObject().field("error", code).field("message", message).endObject().toString());
    }

    private static void bytes(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static String mime(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}

package marswalk;

import java.util.ArrayList;
import java.util.List;

/**
 * Statistics measured along a route polyline, taken from the terrain raster the route is evaluated on.
 * The route is sampled about once per raster cell; elevation is interpolated bilinearly between cell centres.
 * Nothing here is estimated or invented: every number comes from the raster and the geometry.
 */
public final class RouteStats {
    public double distanceM, straightLineM;
    public double startElevM = Double.NaN, endElevM = Double.NaN, minElevM = Double.NaN, maxElevM = Double.NaN;
    public double elevGainM, elevLossM;
    public double avgSlopeDeg = Double.NaN, maxSlopeDeg = Double.NaN;        // along-track, between samples
    public double maxTerrainSlopeDeg = Double.NaN;                           // steepest-direction 3x3 slope of cells crossed
    public int steepSections; public double steepLengthM; public double steepThresholdDeg;
    public int unknownSlopeSamples, unknownElevationSamples;
    public double sampleSpacingM;
    public final List<Label> labels = new ArrayList<>();
    public final List<double[]> profile = new ArrayList<>();                 // {distance m, elevation m, terrain slope deg}

    public static final class Label {
        public final double distanceM, lon, lat, elevM, slopeDeg;
        Label(double d, double lon, double lat, double e, double s) { distanceM = d; this.lon = lon; this.lat = lat; elevM = e; slopeDeg = s; }
    }

    public static RouteStats compute(Terrain t, double[] lon, double[] lat, double steepThresholdDeg) {
        Georef g = t.georef();
        RouteStats st = new RouteStats();
        st.steepThresholdDeg = steepThresholdDeg;
        st.sampleSpacingM = g.southMeters();

        List<double[]> s = new ArrayList<>();   // {dist, lon, lat, elev, cellSlope}
        double dist = 0;
        s.add(sample(t, 0, lon[0], lat[0]));
        for (int i = 1; i < lon.length; i++) {
            double seg = g.distanceMeters(lon[i - 1], lat[i - 1], lon[i], lat[i]);
            int n = Math.max(1, (int) Math.ceil(seg / g.southMeters()));
            for (int k = 1; k <= n; k++) {
                double f = (double) k / n;
                s.add(sample(t, dist + seg * f, lon[i - 1] + (lon[i] - lon[i - 1]) * f, lat[i - 1] + (lat[i] - lat[i - 1]) * f));
            }
            dist += seg;
        }
        st.distanceM = dist;
        st.straightLineM = g.distanceMeters(lon[0], lat[0], lon[lon.length - 1], lat[lat.length - 1]);

        double sumSlopeLen = 0, sumLen = 0, maxStep = Double.NaN, maxTerrain = Double.NaN;
        double minE = Double.NaN, maxE = Double.NaN;
        boolean inSteep = false;
        for (int i = 0; i < s.size(); i++) {
            double[] cur = s.get(i);
            double e = cur[3], cs = cur[4];
            if (Double.isNaN(e)) st.unknownElevationSamples++;
            else { minE = Double.isNaN(minE) ? e : Math.min(minE, e); maxE = Double.isNaN(maxE) ? e : Math.max(maxE, e); }
            if (Double.isNaN(cs)) st.unknownSlopeSamples++;
            else maxTerrain = Double.isNaN(maxTerrain) ? cs : Math.max(maxTerrain, cs);

            boolean steep = !Double.isNaN(cs) && cs >= steepThresholdDeg;
            if (steep && !inSteep) st.steepSections++;
            inSteep = steep;

            if (i > 0) {
                double[] prev = s.get(i - 1);
                double ds = cur[0] - prev[0];
                if (steep && ds > 0) st.steepLengthM += ds;
                if (!Double.isNaN(e) && !Double.isNaN(prev[3]) && ds > 0) {
                    double dz = e - prev[3];
                    if (dz > 0) st.elevGainM += dz; else st.elevLossM += -dz;
                    double sl = Math.toDegrees(Math.atan(Math.abs(dz) / ds));
                    sumSlopeLen += sl * ds; sumLen += ds;
                    maxStep = Double.isNaN(maxStep) ? sl : Math.max(maxStep, sl);
                }
            }
        }
        st.startElevM = s.get(0)[3];
        st.endElevM = s.get(s.size() - 1)[3];
        st.minElevM = minE; st.maxElevM = maxE;
        st.avgSlopeDeg = sumLen > 0 ? sumSlopeLen / sumLen : Double.NaN;
        st.maxSlopeDeg = maxStep;
        st.maxTerrainSlopeDeg = maxTerrain;

        // elevation/slope profile (about 150 points) for a chart
        int stepP = Math.max(1, s.size() / 150);
        for (int i = 0; i < s.size(); i += stepP) st.profile.add(new double[]{s.get(i)[0], s.get(i)[3], s.get(i)[4]});
        double[] last = s.get(s.size() - 1);
        if (st.profile.get(st.profile.size() - 1)[0] != last[0]) st.profile.add(new double[]{last[0], last[3], last[4]});

        // labels every "nice" distance so that there are roughly 6-12 of them
        double[] nice = {1000, 2000, 5000, 10000, 20000, 25000, 50000, 100000, 200000, 250000, 500000};
        double step = nice[nice.length - 1];
        for (double nv : nice) if (dist / nv <= 12) { step = nv; break; }
        int idx = 0;
        for (double d = step; d < dist - step * 0.35; d += step) {
            while (idx < s.size() - 1 && s.get(idx)[0] < d) idx++;
            double[] p = s.get(idx);
            st.labels.add(new Label(p[0], p[1], p[2], p[3], p[4]));
        }
        return st;
    }

    private static double[] sample(Terrain t, double dist, double lon, double lat) {
        return new double[]{dist, lon, lat, t.elevationAt(lon, lat), t.slopeAt(lon, lat)};
    }

    public void write(JsonWriter w) {
        w.beginObject();
        w.field("distanceM", distanceM, 0).field("straightLineM", straightLineM, 0);
        w.field("detourRatio", straightLineM > 0 ? distanceM / straightLineM : Double.NaN, 3);
        w.field("startElevM", startElevM, 0).field("endElevM", endElevM, 0);
        w.field("minElevM", minElevM, 0).field("maxElevM", maxElevM, 0);
        w.field("elevGainM", elevGainM, 0).field("elevLossM", elevLossM, 0);
        w.field("avgSlopeDeg", avgSlopeDeg, 2).field("maxSlopeDeg", maxSlopeDeg, 2);
        w.field("maxTerrainSlopeDeg", maxTerrainSlopeDeg, 2);
        w.field("steepSections", steepSections).field("steepLengthM", steepLengthM, 0).field("steepThresholdDeg", steepThresholdDeg, 1);
        w.field("unknownSlopeSamples", unknownSlopeSamples).field("unknownElevationSamples", unknownElevationSamples);
        w.field("sampleSpacingM", sampleSpacingM, 0);
        w.name("labels").beginArray();
        for (Label l : labels) {
            w.beginObject().field("distanceM", l.distanceM, 0).field("lon", l.lon, 5).field("lat", l.lat, 5)
                    .field("elevM", l.elevM, 0).field("slopeDeg", l.slopeDeg, 1).endObject();
        }
        w.endArray();
        w.name("profile").beginArray();
        for (double[] p : profile) { w.beginArray().value(p[0], 0).value(p[1], 0).value(p[2], 1).endArray(); }
        w.endArray();
        w.endObject();
    }
}

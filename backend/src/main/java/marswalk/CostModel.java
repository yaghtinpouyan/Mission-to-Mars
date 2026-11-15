package marswalk;

/**
 * How expensive it is to move between two neighbouring cells. Cost is in "equivalent metres":
 *
 *   cost = distance * (wDistance + wSlope * (s / slopeRefDeg)^2)  +  wClimb * metresClimbed
 *
 * where s = the larger of (a) the slope along the step (from the two elevations) and
 * (b) the 3x3 terrain slope of the two cells (this also catches steep ground that is crossed sideways).
 *
 * These weights are tuning knobs for a candidate-route search, NOT validated rover mobility limits.
 * maxSlopeDeg is an optional hard limit chosen by the user; by default there is none.
 */
public final class CostModel {
    public double wDistance = 1.0;
    public double wSlope = 4.0;
    public double slopeRefDeg = 10.0;
    public double wClimb = 2.0;
    public double maxSlopeDeg = Double.POSITIVE_INFINITY;
    /** Optional user-chosen elevation limits in metres (e.g. "stay above -1500 m" to keep out of deep basins). Default: none. */
    public double minElevM = Double.NEGATIVE_INFINITY;
    public double maxElevM = Double.POSITIVE_INFINITY;
    /** If true, cells whose terrain slope is unknown (map edge) are forbidden. Default: allowed, using the step slope only. */
    public boolean blockOnSlopeNoData = false;

    public CostModel copy() {
        CostModel c = new CostModel();
        c.wDistance = wDistance; c.wSlope = wSlope; c.slopeRefDeg = slopeRefDeg; c.wClimb = wClimb;
        c.maxSlopeDeg = maxSlopeDeg; c.blockOnSlopeNoData = blockOnSlopeNoData;
        c.minElevM = minElevM; c.maxElevM = maxElevM;
        return c;
    }

    public void validate() {
        if (!(wDistance > 0) || wSlope < 0 || !(slopeRefDeg > 0) || wClimb < 0 || !(maxSlopeDeg > 0))
            throw new IllegalArgumentException("Cost weights must be: wDistance>0, wSlope>=0, slopeRef>0, wClimb>=0, maxSlope>0");
        if (minElevM > maxElevM) throw new IllegalArgumentException("minElev must not be above maxElev");
    }

    public boolean elevationAllowed(double z) { return z >= minElevM && z <= maxElevM; }

    /** Cost of moving from cell (c0,r0) to the neighbouring cell (c1,r1); +Infinity if not allowed. */
    public double stepCost(Terrain t, int c0, int r0, int c1, int r1) {
        float z0 = t.elevation(c0, r0), z1 = t.elevation(c1, r1);
        if (Float.isNaN(z0) || Float.isNaN(z1)) return Double.POSITIVE_INFINITY;
        if (!elevationAllowed(z0) || !elevationAllowed(z1)) return Double.POSITIVE_INFINITY;
        double dist = t.georef().stepMeters(c0, r0, c1, r1);
        double dz = z1 - z0;
        double edgeSlope = Math.toDegrees(Math.atan(Math.abs(dz) / dist));
        float s0 = t.slopeDeg(c0, r0), s1 = t.slopeDeg(c1, r1);
        double cellSlope;
        if (Float.isNaN(s0) && Float.isNaN(s1)) {
            if (blockOnSlopeNoData) return Double.POSITIVE_INFINITY;
            cellSlope = edgeSlope;
        } else if (Float.isNaN(s0)) cellSlope = s1;
        else if (Float.isNaN(s1)) cellSlope = s0;
        else cellSlope = 0.5 * (s0 + s1);
        double s = Math.max(edgeSlope, cellSlope);
        if (s > maxSlopeDeg) return Double.POSITIVE_INFINITY;
        double ratio = s / slopeRefDeg;
        return dist * (wDistance + wSlope * ratio * ratio) + wClimb * Math.max(0.0, dz);
    }
}

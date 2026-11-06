package dev.frontier.aviation.impact;

/**
 * Continuous SAT for two native OBBs: {@code a} translates by the relative motion while
 * {@code b} stays put. Unlike the voxel sweep, an overlap at the start is reported as a
 * contact at time zero, because nothing native separates two airframes that pass through
 * each other.
 */
public final class SweptAircraftPair {
    private static final double TOUCH = 1e-8;
    private SweptAircraftPair() { }

    /** Earliest contact in [0, 1] with its normal pointing from {@code b} toward {@code a}, or null. */
    public static SweptAircraftBox.Hit contact(SweptAircraftBox.Box a, SweptAircraftBox.Box b,
            double mx, double my, double mz) {
        return contact(a, b, 0, 0, 0, mx, my, mz);
    }

    /** As {@link #contact(SweptAircraftBox.Box, SweptAircraftBox.Box, double, double, double)}, with {@code a} starting shifted by (ox, oy, oz). */
    public static SweptAircraftBox.Hit contact(SweptAircraftBox.Box a, SweptAircraftBox.Box b,
            double ox, double oy, double oz, double mx, double my, double mz) {
        if (!Double.isFinite(mx) || !Double.isFinite(my) || !Double.isFinite(mz)
                || !Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz)) return null;
        double dx = a.x + ox - b.x, dy = a.y + oy - b.y, dz = a.z + oz - b.z;
        double enter = Double.NEGATIVE_INFINITY, exit = Double.POSITIVE_INFINITY;
        double hitX = 0, hitY = 0, hitZ = 0;
        double[] pa = a.axes, pb = b.axes;
        for (int k = 0; k < 15; k++) {
            double nx, ny, nz;
            if (k < 3) {
                nx = pa[k * 3]; ny = pa[k * 3 + 1]; nz = pa[k * 3 + 2];
            } else if (k < 6) {
                nx = pb[(k - 3) * 3]; ny = pb[(k - 3) * 3 + 1]; nz = pb[(k - 3) * 3 + 2];
            } else {
                int i = (k - 6) / 3, j = (k - 6) % 3;
                double ax = pa[i * 3], ay = pa[i * 3 + 1], az = pa[i * 3 + 2];
                double bx = pb[j * 3], by = pb[j * 3 + 1], bz = pb[j * 3 + 2];
                nx = ay * bz - az * by; ny = az * bx - ax * bz; nz = ax * by - ay * bx;
            }
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            // Parallel edge pairs give no new axis; the face axes already cover them.
            if (length < 1e-6) continue;
            nx /= length; ny /= length; nz /= length;
            double radius = radius(a, nx, ny, nz) + radius(b, nx, ny, nz);
            double distance = dx * nx + dy * ny + dz * nz;
            double speed = mx * nx + my * ny + mz * nz;
            if (Math.abs(speed) < 1e-10) {
                if (Math.abs(distance) >= radius - TOUCH) return null;
                continue;
            }
            double t1 = (-radius - distance) / speed, t2 = (radius - distance) / speed;
            double first = Math.min(t1, t2), last = Math.max(t1, t2);
            if (first > enter) {
                enter = first;
                double direction = distance + speed * first >= 0 ? 1 : -1;
                hitX = nx * direction; hitY = ny * direction; hitZ = nz * direction;
            }
            exit = Math.min(exit, last);
            if (enter > exit || exit < 0 || enter > 1) return null;
        }
        if (enter == Double.NEGATIVE_INFINITY) {
            // No relative motion along any axis and overlapping on all of them.
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            return length < 1e-8 ? new SweptAircraftBox.Hit(0, 0, 1, 0)
                    : new SweptAircraftBox.Hit(0, dx / length, dy / length, dz / length);
        }
        if (!Double.isFinite(enter)) return null;
        return new SweptAircraftBox.Hit(Math.max(0, enter), hitX, hitY, hitZ);
    }

    private static double radius(SweptAircraftBox.Box box, double nx, double ny, double nz) {
        double[] p = box.axes;
        return box.ex * Math.abs(nx * p[0] + ny * p[1] + nz * p[2])
                + box.ey * Math.abs(nx * p[3] + ny * p[4] + nz * p[5])
                + box.ez * Math.abs(nx * p[6] + ny * p[7] + nz * p[8]);
    }
}

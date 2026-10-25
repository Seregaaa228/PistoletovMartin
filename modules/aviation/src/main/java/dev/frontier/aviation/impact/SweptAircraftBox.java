package dev.frontier.aviation.impact;

/** Continuous SAT for one translating native OBB and one axis-aligned voxel box. */
public final class SweptAircraftBox {
    public static final class Box {
        final double x, y, z, ex, ey, ez;
        final double[] axes;
        final double[] normals = new double[45];
        final double[] radii = new double[15];
        public Box(double x, double y, double z, double ex, double ey, double ez, double[] axes) {
            if (axes.length != 9) throw new IllegalArgumentException("Three native OBB axes required");
            this.x = x; this.y = y; this.z = z;
            this.ex = ex; this.ey = ey; this.ez = ez;
            this.axes = axes.clone();
            normals[0] = normals[4] = normals[8] = 1;
            System.arraycopy(axes, 0, normals, 9, 9);
            for (int a = 0; a < 3; a++) {
                double ax = axes[a * 3], ay = axes[a * 3 + 1], az = axes[a * 3 + 2];
                int n = 18 + a * 9;
                normals[n + 1] = az; normals[n + 2] = -ay;
                normals[n + 3] = -az; normals[n + 5] = ax;
                normals[n + 6] = ay; normals[n + 7] = -ax;
            }
            for (int a = 0; a < 15; a++) {
                double nx = normals[a * 3], ny = normals[a * 3 + 1], nz = normals[a * 3 + 2];
                radii[a] = ex * Math.abs(nx * axes[0] + ny * axes[1] + nz * axes[2])
                        + ey * Math.abs(nx * axes[3] + ny * axes[4] + nz * axes[5])
                        + ez * Math.abs(nx * axes[6] + ny * axes[7] + nz * axes[8]);
            }
        }
    }
    public record Hit(double time, double nx, double ny, double nz) { }
    private SweptAircraftBox() { }

    public static double allowedFraction(double contactTime, double speed) {
        return Math.max(0, Math.min(1, contactTime - 1e-4 / Math.max(speed, 1e-8)));
    }

    public static double[] clipMotion(double contactTime, double nx, double ny, double nz,
            double mx, double my, double mz) {
        double fraction = allowedFraction(contactTime, Math.sqrt(mx * mx + my * my + mz * mz));
        double inward = Math.min(0, mx * nx + my * ny + mz * nz);
        double removed = inward * (1 - fraction);
        return new double[] {mx - nx * removed, my - ny * removed, mz - nz * removed};
    }

    public static Hit contact(Box box, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, double mx, double my, double mz) {
        double cx = (minX + maxX) * .5, cy = (minY + maxY) * .5, cz = (minZ + maxZ) * .5;
        double ex = (maxX - minX) * .5, ey = (maxY - minY) * .5, ez = (maxZ - minZ) * .5;
        double enter = Double.NEGATIVE_INFINITY, exit = Double.POSITIVE_INFINITY;
        double hitX = 0, hitY = 0, hitZ = 0;
        for (int a = 0; a < 15; a++) {
            double nx = box.normals[a * 3], ny = box.normals[a * 3 + 1], nz = box.normals[a * 3 + 2];
            double length = nx * nx + ny * ny + nz * nz;
            if (length < 1e-12) continue;
            double radius = box.radii[a] + ex * Math.abs(nx) + ey * Math.abs(ny) + ez * Math.abs(nz);
            double distance = (box.x - cx) * nx + (box.y - cy) * ny + (box.z - cz) * nz;
            double speed = mx * nx + my * ny + mz * nz;
            if (Math.abs(speed) < 1e-10) {
                // Merely touching a surface while sliding parallel to it is safe.
                if (Math.abs(distance) >= radius - 1e-8) return null;
                continue;
            }
            double t1 = (-radius - distance) / speed, t2 = (radius - distance) / speed;
            double first = Math.min(t1, t2), last = Math.max(t1, t2);
            if (first > enter) {
                enter = first;
                double direction = distance + speed * first >= 0 ? 1 : -1;
                double scale = direction / Math.sqrt(length);
                hitX = nx * scale; hitY = ny * scale; hitZ = nz * scale;
            }
            exit = Math.min(exit, last);
            if (enter > exit) return null;
        }
        // Initial overlap is not a new impact; native movement resolves it.
        if (enter < -1e-8 || exit < 0 || enter > 1 || !Double.isFinite(enter)) return null;
        return new Hit(Math.max(0, enter), hitX, hitY, hitZ);
    }
}

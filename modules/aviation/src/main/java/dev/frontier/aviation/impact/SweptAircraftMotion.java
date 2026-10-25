package dev.frontier.aviation.impact;

import java.util.ArrayList;
import java.util.List;

/** Bounded continuous movement with re-sweeps after each solid contact. */
public final class SweptAircraftMotion {
    public static final int MAX_SWEEPS = 4;
    public static final int MAX_PLANES = 3;
    @FunctionalInterface
    public interface Scene {
        SweptAircraftBox.Hit firstContact(double ox, double oy, double oz, double mx, double my, double mz);
    }
    public record Contact(double nx, double ny, double nz, double normalSpeed) { }
    public record Result(double x, double y, double z, List<Contact> contacts, double[] planes, int sweeps) {
        public double[] clipVelocity(double x, double y, double z) { return project(x, y, z, planes); }
    }
    private SweptAircraftMotion() { }

    public static Result resolve(double mx, double my, double mz, Scene scene) {
        double ox = 0, oy = 0, oz = 0, vx = mx, vy = my, vz = mz;
        var contacts = new ArrayList<Contact>(MAX_SWEEPS);
        double[] planes = new double[0];
        int sweeps = 0;
        for (; sweeps < MAX_SWEEPS; sweeps++) {
            double speed = Math.sqrt(mx * mx + my * my + mz * mz);
            if (speed < 1e-8) break;
            var hit = scene.firstContact(ox, oy, oz, mx, my, mz);
            if (hit == null) {
                ox += mx; oy += my; oz += mz;
                sweeps++;
                break;
            }
            double normalSpeed = Math.max(0, -(vx * hit.nx() + vy * hit.ny() + vz * hit.nz()));
            contacts.add(new Contact(hit.nx(), hit.ny(), hit.nz(), normalSpeed));
            double fraction = SweptAircraftBox.allowedFraction(hit.time(), speed);
            ox += mx * fraction; oy += my * fraction; oz += mz * fraction;
            mx *= 1 - fraction; my *= 1 - fraction; mz *= 1 - fraction;
            boolean known = false;
            for (int i = 0; i < planes.length; i += 3) {
                if (planes[i] * hit.nx() + planes[i + 1] * hit.ny() + planes[i + 2] * hit.nz() > 1 - 1e-8) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                if (planes.length == MAX_PLANES * 3) break;
                planes = java.util.Arrays.copyOf(planes, planes.length + 3);
                planes[planes.length - 3] = hit.nx();
                planes[planes.length - 2] = hit.ny();
                planes[planes.length - 1] = hit.nz();
            }
            double[] remaining = project(mx, my, mz, planes);
            mx = remaining[0]; my = remaining[1]; mz = remaining[2];
            double[] velocity = project(vx, vy, vz, planes);
            vx = velocity[0]; vy = velocity[1]; vz = velocity[2];
        }
        // Never append untested motion if the bounded contact budget was used.
        return new Result(ox, oy, oz, List.copyOf(contacts), planes, sweeps);
    }

    /** Closest motion satisfying all admitted outward contact half-spaces. */
    private static double[] project(double x, double y, double z, double[] planes) {
        if (safe(x, y, z, planes)) return new double[] {x, y, z};
        double[] best = {0, 0, 0};
        double bestDistance = x * x + y * y + z * z;
        for (int i = 0; i < planes.length; i += 3) {
            double dot = x * planes[i] + y * planes[i + 1] + z * planes[i + 2];
            double px = x - dot * planes[i], py = y - dot * planes[i + 1], pz = z - dot * planes[i + 2];
            double distance = squared(x - px, y - py, z - pz);
            if (safe(px, py, pz, planes) && distance < bestDistance) {
                best = new double[] {px, py, pz}; bestDistance = distance;
            }
            for (int j = i + 3; j < planes.length; j += 3) {
                double cx = planes[i + 1] * planes[j + 2] - planes[i + 2] * planes[j + 1];
                double cy = planes[i + 2] * planes[j] - planes[i] * planes[j + 2];
                double cz = planes[i] * planes[j + 1] - planes[i + 1] * planes[j];
                double length = squared(cx, cy, cz);
                if (length < 1e-12) continue;
                double scale = (x * cx + y * cy + z * cz) / length;
                px = cx * scale; py = cy * scale; pz = cz * scale;
                distance = squared(x - px, y - py, z - pz);
                if (safe(px, py, pz, planes) && distance < bestDistance) {
                    best = new double[] {px, py, pz}; bestDistance = distance;
                }
            }
        }
        return best;
    }
    private static boolean safe(double x, double y, double z, double[] planes) {
        for (int i = 0; i < planes.length; i += 3) {
            if (x * planes[i] + y * planes[i + 1] + z * planes[i + 2] < -1e-8) return false;
        }
        return true;
    }
    private static double squared(double x, double y, double z) { return x * x + y * y + z * z; }
}

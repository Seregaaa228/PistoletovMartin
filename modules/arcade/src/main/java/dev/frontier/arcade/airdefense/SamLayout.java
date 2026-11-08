package dev.frontier.arcade.airdefense;

import net.minecraft.world.phys.Vec3;

/**
 * Where each round leaves the launcher, in the models' own units (16 per block; front is -Z,
 * left is +X, as written by {@code modules/arcade/art/sam_assets.py}). The pivots here and the
 * bones in the generator are the same numbers.
 */
public final class SamLayout {
    /** S-125 launcher pivots (turntable, beams) and its four rails (lateral, vertical) in one row, round order. */
    public static final double[] S125_TURRET = {0, 15, 0};
    public static final double[] S125_PIVOT = {0, 30, 0};
    public static final double[][] S125_RAILS = {{16, 9}, {-16, 9}, {40, 5}, {-40, 5}};
    /** The rounds' noses: nozzle at z 17, 94 long; a missile leaves from just ahead of its nose. */
    public static final double S125_NOSE_Z = 17 - 94;
    /** S-300/S-400 launch pack pivot at the rear of the truck, tube length, tube x positions. */
    public static final double[] PACK_PIVOT = {0, 42, 70};
    public static final double PACK_LENGTH = 128;
    public static final double[] PACK_TUBES = {-21, -7, 7, 21};
    /** Pantsir turret (yaw) pivot, launcher (elevation) pivot, and the centre of each six-round pack's mouth. */
    public static final double[] PANTSIR_PIVOT = {0, 50, 42};
    public static final double[] PANTSIR_LAUNCHER = {0, 58, 42};
    public static final double PANTSIR_PACK_X = 24, PANTSIR_PACK_Y = 65.2, PANTSIR_MOUTH_Z = 4;

    /** Patriot: train axis, rear elevation hinge, fixed firing elevation, and the four canister muzzles at rest. */
    public static final double[] PATRIOT_TURRET = {0, 25, 70};
    public static final double[] PATRIOT_HINGE = {0, 30, 95};
    public static final float PATRIOT_ELEVATION = 38;
    /** Canister centres (x, y) at the muzzle face z 1: 0-1 the lower PAC-2 pair, 2-3 the upper PAC-3 pair. */
    public static final double[][] PATRIOT_CANISTERS = {{8.6, 39.8}, {-8.6, 39.8}, {8.6, 57.0}, {-8.6, 57.0}};
    public static final double PATRIOT_MUZZLE_Z = 1.0, PAC3_CELL = 3.8;

    private SamLayout() { }

    /** World point for model coordinates on the hull of {@code site}. */
    static Vec3 hull(SamSiteEntity site, double x, double y, double z) {
        double yaw = Math.toRadians(site.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        return site.position().add(forward.scale(-z / 16)).add(left.scale(x / 16)).add(0, y / 16, 0);
    }

    /** Muzzle of the given round, slightly ahead of the rail or tube so the missile clears the model. */
    static Vec3 launchPoint(SamSiteEntity site, int rack, int round, Vec3 dir) {
        switch (site.system()) {
            case S125 -> {
                double[] rail = S125_RAILS[Math.floorMod(round, S125_RAILS.length)];
                double[] p = turretPoint(S125_TURRET, S125_PIVOT,
                    new double[]{rail[0], S125_PIVOT[1] + rail[1], S125_NOSE_Z}, site.aimYaw(), site.aimPitch());
                return hull(site, p[0], p[1], p[2]).add(dir.scale(0.5));
            }
            case S300, S400 -> {
                // S-400: rack 0 (48N6) in the outer tubes, rack 1 (9M96, four per tube) in the inner ones.
                int tube = site.system() == SamSystem.S300 ? round
                    : rack == 0 ? (round % 2 == 0 ? 0 : 3) : (round % 2 == 0 ? 1 : 2);
                double x = PACK_TUBES[Math.floorMod(tube, PACK_TUBES.length)];
                return hull(site, x, PACK_PIVOT[1] + PACK_LENGTH + 8, PACK_PIVOT[2]);
            }
            case PATRIOT -> {
                // PAC-2: one per lower canister. PAC-3: four cells per upper canister, alternating sides.
                double[] c = PATRIOT_CANISTERS[rack == 0 ? Math.floorMod(round, 2) : 2 + Math.floorMod(round, 2)];
                double cx = c[0], cy = c[1];
                if (rack == 1) {
                    int cell = Math.floorMod(round / 2, 4);
                    cx += (cell % 2 == 0 ? 1 : -1) * PAC3_CELL;
                    cy += (cell < 2 ? 1 : -1) * PAC3_CELL;
                }
                double[] p = turretPoint(PATRIOT_TURRET, PATRIOT_HINGE, new double[]{cx, cy, PATRIOT_MUZZLE_Z}, site.aimYaw(), site.aimPitch());
                return hull(site, p[0], p[1], p[2]).add(dir.scale(0.5));
            }
            default -> {
                // Even rounds from the left pack, odd from the right; the mouth follows turret yaw and pack elevation.
                double side = (round % 2 == 0 ? 1 : -1) * PANTSIR_PACK_X;
                double[] p = turretPoint(PANTSIR_PIVOT, PANTSIR_LAUNCHER, new double[]{side, PANTSIR_PACK_Y, PANTSIR_MOUTH_Z},
                    site.aimYaw(), site.aimPitch());
                return hull(site, p[0], p[1], p[2]).add(dir.scale(0.6));
            }
        }
    }

    /**
     * Model point {@code at} on a launcher that elevates by {@code pitch} about X at {@code launcher}
     * and turns by {@code yaw} about Y at {@code turret} (both degrees; yaw as the site's aimYaw, pitch
     * positive raising the front).
     */
    static double[] turretPoint(double[] turret, double[] launcher, double[] at, double yaw, double pitch) {
        double t = Math.toRadians(pitch), a = Math.toRadians(yaw);
        double y = at[1] - launcher[1], z = at[2] - launcher[2];
        double ry = y * Math.cos(t) - z * Math.sin(t), rz = y * Math.sin(t) + z * Math.cos(t);
        double px = launcher[0] - turret[0] + at[0] - launcher[0];
        double py = launcher[1] - turret[1] + ry, pz = launcher[2] - turret[2] + rz;
        double qx = px * Math.cos(a) + pz * Math.sin(a), qz = -px * Math.sin(a) + pz * Math.cos(a);
        return new double[]{turret[0] + qx, turret[1] + py, turret[2] + qz};
    }
}

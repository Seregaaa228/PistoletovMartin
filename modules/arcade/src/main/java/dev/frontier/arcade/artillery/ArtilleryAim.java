package dev.frontier.arcade.artillery;

import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import com.atsuishio.superbwarfare.entity.vehicle.base.SpArtilleryEntity;
import dev.frontier.arcade.ArcadeVehicles;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Trues up a laid self-propelled-gun shot onto its crew's mission point. The crew lays the barrel by hand against a
 * mark; a shell leaving the barrel within {@link #TRUE_UP_DEGREES} of the exact arc is turned onto it at the same
 * muzzle speed, so the round lands on the point instead of tens of metres off at range. A shot laid elsewhere flies
 * untouched. The arc is solved for SBW's own stepping (position first, then gravity, no drag), which the far-flight
 * carrier repeats.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class ArtilleryAim {
    /** Closest map-fire point; nearer targets are direct fire. */
    public static final double MIN_RANGE = 100;
    /** Largest elevation or bearing error a true-up takes away. */
    public static final double TRUE_UP_DEGREES = 1.0;
    /** Rocket-to-rocket scatter of a launcher salvo (1 sigma): about 13 m either way at 4 km. */
    private static final double SALVO_SPREAD_DEGREES = 0.18;
    private static final Map<UUID, Point> MISSIONS = new HashMap<>();

    private record Point(int x, int z) { }

    private ArtilleryAim() { }

    /** A crew member's mission point, kept per gun; only from a seat in that gun and within its reach. */
    static void mission(ServerPlayer player, int x, int z, boolean active, double reach) {
        if (player == null || !(player.getVehicle() instanceof SpArtilleryEntity gun) || gun.isWreck()) return;
        if (!active) { MISSIONS.remove(gun.getUUID()); return; }
        if (Math.hypot(x + 0.5 - gun.getX(), z + 0.5 - gun.getZ()) > reach) return;
        if (!player.serverLevel().getWorldBorder().isWithinBounds(x, z)) return;
        MISSIONS.put(gun.getUUID(), new Point(x, z));
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        MISSIONS.clear();
    }

    /** Called once for a shell that just left a gun's barrel. */
    static void trueUp(ServerLevel level, SpArtilleryEntity gun, FastThrowableProjectile shell) {
        var point = MISSIONS.get(gun.getUUID());
        if (point == null) return;
        Vec3 muzzle = shell.position(), velocity = shell.getDeltaMovement();
        double speed = velocity.length(), gravity = shell.getCustomGravity();
        if (gravity <= 0) gravity = gun.getProjectileGravity("Main");
        if (speed < 1.0E-3 || gravity <= 0) return;
        double dx = point.x + 0.5 - muzzle.x, dz = point.z + 0.5 - muzzle.z, distance = Math.hypot(dx, dz);
        if (distance < MIN_RANGE) return;
        // The shell meets the top face of the column's highest block.
        double rise = ArtilleryHeights.surface(level, point.x, point.z) + 1.0 - muzzle.y;
        double horizontal = Math.hypot(velocity.x, velocity.z);
        double shotElevation = Math.atan2(velocity.y, horizontal);
        double bearingError = Math.acos(Math.max(-1, Math.min(1, (velocity.x * dx + velocity.z * dz) / (horizontal * distance))));
        double elevation = elevation(speed, gravity, distance, rise, shotElevation);
        if (Double.isNaN(elevation) || Math.toDegrees(Math.abs(elevation - shotElevation)) > TRUE_UP_DEGREES
            || Math.toDegrees(bearingError) > TRUE_UP_DEGREES) return;
        double bearing = Math.atan2(dz, dx);
        if (ArtilleryVehicles.isGrad(gun.getType())) {
            // A salvo covers an area around the point instead of landing every rocket in one crater.
            var random = level.getRandom();
            elevation += Math.toRadians(random.nextGaussian() * SALVO_SPREAD_DEGREES);
            bearing += Math.toRadians(random.nextGaussian() * SALVO_SPREAD_DEGREES);
        }
        double across = speed * Math.cos(elevation);
        shell.setDeltaMovement(Math.cos(bearing) * across, speed * Math.sin(elevation), Math.sin(bearing) * across);
    }

    /**
     * Launch elevation (radians) that brings a shell to {@code distance} horizontally at {@code rise} above the
     * muzzle; of the flat and the high arc, the one nearer {@code hint}. NaN when out of reach.
     */
    static double elevation(double speed, double gravity, double distance, double rise, double hint) {
        double low = Math.toRadians(-30), high = Math.toRadians(89.5);
        // Height at the target distance peaks at the max-range elevation; the two arcs lie either side of it.
        double a = low, b = high;
        for (int i = 0; i < 80; i++) {
            double m1 = a + (b - a) / 3, m2 = b - (b - a) / 3;
            if (height(speed, gravity, distance, m1) < height(speed, gravity, distance, m2)) a = m1; else b = m2;
        }
        double peak = (a + b) / 2;
        if (height(speed, gravity, distance, peak) < rise) return Double.NaN;
        double flat = root(speed, gravity, distance, rise, low, peak, true);
        double lob = root(speed, gravity, distance, rise, peak, high, false);
        if (Double.isNaN(flat)) return lob;
        if (Double.isNaN(lob)) return flat;
        return Math.abs(flat - hint) <= Math.abs(lob - hint) ? flat : lob;
    }

    /** Height above the muzzle after covering {@code distance}: SBW moves first, then applies gravity. */
    private static double height(double speed, double gravity, double distance, double elevation) {
        double ticks = distance / (speed * Math.cos(elevation));
        return ticks * speed * Math.sin(elevation) - gravity * ticks * (ticks - 1) / 2;
    }

    /** Bisection on a monotone stretch; {@code rising} says which way the height goes from a to b. */
    private static double root(double speed, double gravity, double distance, double rise, double a, double b, boolean rising) {
        double fa = height(speed, gravity, distance, a) - rise, fb = height(speed, gravity, distance, b) - rise;
        if (fa * fb > 0) return Double.NaN;
        for (int i = 0; i < 80; i++) {
            double m = (a + b) / 2, fm = height(speed, gravity, distance, m) - rise;
            if ((fm < 0) == rising) a = m; else b = m;
        }
        return (a + b) / 2;
    }
}

package dev.frontier.arcade.artillery.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.SpArtilleryEntity;
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import dev.frontier.arcade.artillery.ArtilleryAim;
import dev.frontier.arcade.artillery.ArtilleryHeights;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The crew's own fire mission: a map point and an arc, kept on the client per gun. The server only reports the
 * surface height of a far column and keeps the mission point to true up a laid shot; the gunner lays the barrel by
 * hand and fires with SBW's own trigger. The solution is SBW's drag-free TrajectoryCalculator from the live muzzle,
 * matching the native shell.
 */
public final class FireMission {
    static final String WEAPON = "Main";
    static final double MIN_RANGE = ArtilleryAim.MIN_RANGE;
    private static int gunId = -1;
    private static BlockPos target;
    private static boolean heightKnown;
    private static boolean flat = true;
    /** Client game time when the last shell fired at this mission lands; 0 when none is in flight. */
    private static long impactTick;

    private FireMission() { }

    /**
     * A loaded column gives the real surface; otherwise the server reports it, and until then the gun's own
     * level stands in (shown as approximate).
     */
    static void set(SpArtilleryEntity gun, int x, int z) {
        gunId = gun.getId();
        target = new BlockPos(x, gun.getBlockY(), z);
        heightKnown = false;
        impactTick = 0;
        refreshHeight();
        if (!heightKnown) ArtilleryHeights.query(x, z);
        ArtilleryHeights.mission(x, z);
    }

    /** Server answer for an unloaded column; ignored once the mission moved elsewhere. */
    public static void surface(int x, int z, int y) {
        if (target == null || heightKnown || target.getX() != x || target.getZ() != z) return;
        target = new BlockPos(x, y, z);
        heightKnown = true;
    }

    static void clear() {
        if (target != null) ArtilleryHeights.clearMission();
        gunId = -1; target = null; impactTick = 0;
    }

    static boolean tooClose(SpArtilleryEntity gun) {
        var point = target(gun);
        return point != null && Math.hypot(point.getX() + 0.5 - gun.getX(), point.getZ() + 0.5 - gun.getZ()) < MIN_RANGE;
    }

    static void fired(long now, double flightSeconds) { impactTick = now + Math.round(flightSeconds * 20); }
    static long impactTick() { return impactTick; }
    static boolean flat() { return flat; }
    static void flat(boolean value) { flat = value; }
    static boolean heightKnown() { return heightKnown; }

    static BlockPos target(SpArtilleryEntity gun) {
        if (gun == null || gun.getId() != gunId || target == null) return null;
        if (!heightKnown) refreshHeight();
        return target;
    }

    private static void refreshHeight() {
        var level = Minecraft.getInstance().level;
        if (level == null || target == null) return;
        // The client answers unloaded columns with an empty chunk whose surface is the world floor.
        LevelChunk chunk = level.getChunkSource().getChunk(target.getX() >> 4, target.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null || chunk.isEmpty()) return;
        target = new BlockPos(target.getX(), chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, target.getX() & 15, target.getZ() & 15), target.getZ());
        heightKnown = true;
    }

    /** Unit launch direction for the mission, or null when this arc cannot reach it within the barrel's limits. */
    static Vec3 solution(SpArtilleryEntity gun, float partialTick) {
        var point = target(gun);
        if (point == null || tooClose(gun)) return null;
        double velocity = gun.getProjectileVelocity(WEAPON), gravity = gun.getProjectileGravity(WEAPON);
        if (velocity <= 0 || gravity <= 0) return null;
        // Aim at the top face the shell actually meets; on a shallow descent half a block is tens of metres.
        var surface = new Vec3(point.getX() + 0.5, point.getY() + 1.0, point.getZ() + 0.5);
        // SBW's own flat/high helpers take the first listed root; pick the arc by elevation instead.
        Vec3 chosen = null;
        for (Vec3 launch : TrajectoryCalculator.INSTANCE.calculateShootVectors(gun.getShootPos(WEAPON, partialTick), surface, velocity, gravity)) {
            if (chosen == null || (flat ? launch.y < chosen.y : launch.y > chosen.y)) chosen = launch;
        }
        if (chosen == null) return null;
        // The shell steps its position before gravity each tick, which flies the parabola of a launch g/2 faster
        // upward; take that back from the continuous solution so the barrel direction matches the real flight.
        chosen = chosen.scale(velocity).subtract(0, gravity / 2, 0).normalize();
        double elevation = elevationDegrees(chosen);
        float low = Math.min(gun.getTurretMinPitch(), gun.getTurretMaxPitch()), high = Math.max(gun.getTurretMinPitch(), gun.getTurretMaxPitch());
        return elevation >= low && elevation <= high ? chosen : null;
    }

    /** Level-ground reach of the drag-free shell, the ring drawn on the map. */
    static double maxRange(SpArtilleryEntity gun) {
        double velocity = gun.getProjectileVelocity(WEAPON), gravity = gun.getProjectileGravity(WEAPON);
        return velocity > 0 && gravity > 0 ? velocity * velocity / gravity : 0;
    }

    static double flightSeconds(Vec3 launch, SpArtilleryEntity gun) {
        var point = target(gun);
        double horizontal = Math.hypot(launch.x, launch.z) * gun.getProjectileVelocity(WEAPON);
        if (point == null || horizontal < 1.0E-6) return 0;
        return Math.hypot(point.getX() + 0.5 - gun.getX(), point.getZ() + 0.5 - gun.getZ()) / horizontal / 20.0;
    }

    static double elevationDegrees(Vec3 direction) {
        return Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
    }

    /** Compass bearing: 0 north, 90 east. */
    static double bearingDegrees(Vec3 direction) {
        double bearing = Math.toDegrees(Math.atan2(direction.x, -direction.z));
        return bearing < 0 ? bearing + 360 : bearing;
    }
}

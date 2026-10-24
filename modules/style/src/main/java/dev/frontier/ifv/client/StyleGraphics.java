package dev.frontier.ifv.client;

import com.atsuishio.superbwarfare.tools.ParticleTool;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Client cosmetic budgets. Native gameplay and light expiry remain authoritative. */
public final class StyleGraphics {
    public static final int MAX_LIGHT_POSITIONS_PER_TICK = 64;
    public static final int MAX_ACTIVE_LIGHTS = 256;
    public static final double LIGHT_DISTANCE = 64.0;
    public static final int MAX_EXPLOSION_SPAWN_ATTEMPTS_PER_TICK = 128;
    public static final int MAX_NEAR_EXPLOSION_SPAWNS = 30;
    public static final int MAX_FAR_EXPLOSION_SPAWNS = 8;

    private static final ResourceLocation NATIVE_RECON_DRONE = new ResourceLocation("superbwarfare", "drone");
    private static final LongOpenHashSet lightPositions = new LongOpenHashSet(MAX_LIGHT_POSITIONS_PER_TICK);
    private static final LongOpenHashSet pendingLights = new LongOpenHashSet(MAX_LIGHT_POSITIONS_PER_TICK);
    private static Level budgetWorld;

    // Passive observations for acceptance checks. All counters cover the current
    // client world/game tick, and reset lazily before the next budget operation.
    public static volatile long worldTick = Long.MIN_VALUE;
    public static volatile int lightCalls;
    public static volatile int lightAdmitted;
    public static volatile int lightRejected;
    public static volatile int lightDuplicateRejected;
    public static volatile int lightDistanceRejected;
    public static volatile int lightCapacityRejected;
    public static volatile int lightInvalidRejected;
    public static volatile int lightDistinctPositions;
    public static volatile int nativeActiveLights;
    public static volatile int lightRadiusCalls;
    public static volatile int lightRadiusSkipped;
    public static volatile int explosionCalls;
    public static volatile int explosionSpawnAttempts;
    public static volatile int explosionOmittedSpawns;
    public static volatile int explosionCulled;
    public static volatile int droneLayersSkipped;

    private StyleGraphics() { }

    private static void scope(Level world) {
        long tick = world == null ? Long.MIN_VALUE : world.getGameTime();
        if (world == budgetWorld && tick == worldTick) return;
        budgetWorld = world;
        worldTick = tick;
        lightPositions.clear();
        pendingLights.clear();
        lightCalls = lightAdmitted = lightRejected = 0;
        lightDuplicateRejected = lightDistanceRejected = lightCapacityRejected = lightInvalidRejected = 0;
        lightDistinctPositions = nativeActiveLights = lightRadiusCalls = lightRadiusSkipped = 0;
        explosionCalls = explosionSpawnAttempts = explosionOmittedSpawns = explosionCulled = 0;
        droneLayersSkipped = 0;
    }

    /** The rendered camera also follows native FPV/remote camera transforms. */
    private static double cameraDistanceSquared(double x, double y, double z) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double dx = x - camera.x;
        double dy = y - camera.y;
        double dz = z - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Called at native putSpark HEAD, before any native registry mutation. */
    public static boolean allowSpark(long packedPos, int maxLevel, int ttlTicks, LongOpenHashSet active) {
        Minecraft minecraft = Minecraft.getInstance();
        scope(minecraft.level);
        lightCalls++;
        nativeActiveLights = active.size();
        if (minecraft.level == null || maxLevel <= 0 || ttlTicks <= 0) {
            lightInvalidRejected++;
            lightRejected++;
            return false;
        }
        double distance = cameraDistanceSquared(BlockPos.getX(packedPos) + .5,
                BlockPos.getY(packedPos) + .5, BlockPos.getZ(packedPos) + .5);
        if (!Double.isFinite(distance) || distance > LIGHT_DISTANCE * LIGHT_DISTANCE) {
            lightDistanceRejected++;
            lightRejected++;
            return false;
        }
        if (lightPositions.contains(packedPos)) {
            lightDuplicateRejected++;
            lightRejected++;
            return false;
        }
        // Existing native positions can still refresh at the active cap. New
        // positions never push the actual native set beyond our smaller limit.
        if (lightPositions.size() >= MAX_LIGHT_POSITIONS_PER_TICK
                || (!active.contains(packedPos) && active.size() >= MAX_ACTIVE_LIGHTS)) {
            lightCapacityRejected++;
            lightRejected++;
            return false;
        }
        lightPositions.add(packedPos);
        pendingLights.add(packedPos);
        lightDistinctPositions = lightPositions.size();
        return true;
    }

    /** Called at native RETURN: admission is counted only after the real set contains the position. */
    public static void sparkReturned(long packedPos, LongOpenHashSet active) {
        if (!pendingLights.remove(packedPos)) return;
        nativeActiveLights = active.size();
        if (active.contains(packedPos)) {
            lightAdmitted++;
        } else {
            lightPositions.remove(packedPos);
            lightDistinctPositions = lightPositions.size();
            lightRejected++;
        }
    }

    /** Skip radial iteration when none of its native putSpark calls can be useful. */
    public static boolean skipSparkRadius(BlockPos center, int maxLevel, int ttlTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        scope(minecraft.level);
        lightRadiusCalls++;
        if (minecraft.level == null || maxLevel <= 0 || ttlTicks <= 0
                || lightPositions.size() >= MAX_LIGHT_POSITIONS_PER_TICK) {
            lightRadiusSkipped++;
            return true;
        }
        double distance = cameraDistanceSquared(center.getX() + .5, center.getY() + .5, center.getZ() + .5);
        boolean skip = !Double.isFinite(distance) || distance > 70.0 * 70.0;
        if (skip) lightRadiusSkipped++;
        return skip;
    }

    /** Replaces only ParticleTool's client explosion decoration, never its server wrapper. */
    public static void explosion(ParticleTool.ParticleType type, Level level, Vec3 position) {
        Minecraft minecraft = Minecraft.getInstance();
        scope(minecraft.level);
        explosionCalls++;
        if (level != minecraft.level || !level.isClientSide) {
            explosionCulled++;
            return;
        }
        double distance = cameraDistanceSquared(position.x, position.y, position.z);
        if (!Double.isFinite(distance) || distance > 512.0 * 512.0) {
            explosionCulled++;
            return;
        }
        int strength = type == null ? 0 : type.ordinal();
        int desired;
        if (distance > 192.0 * 192.0) {
            desired = 1;
        } else if (distance >= 64.0 * 64.0) {
            desired = MAX_FAR_EXPLOSION_SPAWNS;
        } else {
            desired = Math.min(MAX_NEAR_EXPLOSION_SPAWNS, 8 + strength * 4);
        }
        int count = Math.min(desired, MAX_EXPLOSION_SPAWN_ATTEMPTS_PER_TICK - explosionSpawnAttempts);
        explosionOmittedSpawns += desired - count;
        if (count <= 0) {
            explosionCulled++;
            return;
        }
        if (distance > 192.0 * 192.0) {
            particle(level, ParticleTypes.SMOKE, true, position.x, position.y + .2, position.z, 0, .035, 0);
            return;
        }
        // EXPLOSION is a single short vanilla particle. EXPLOSION_EMITTER would
        // create children after this budget has been counted and is never used.
        particle(level, ParticleTypes.EXPLOSION, true, position.x, position.y + .2, position.z, 0, 0, 0);
        double spread = distance >= 64.0 * 64.0 ? .7 : .3 + strength * .24;
        for (int i = 1; i < count; i++) {
            double angle = i * 2.399963229728653;
            double radius = spread * (.3 + (i % 5) * .175);
            double x = Math.cos(angle);
            double z = Math.sin(angle);
            ParticleOptions particle = i % 3 == 0 ? ParticleTypes.FLAME : ParticleTypes.SMOKE;
            particle(level, particle, false,
                    position.x + x * radius, position.y + .1 + (i % 4) * spread * .2,
                    position.z + z * radius, x * .045, .025 + (i % 3) * .012, z * .045);
        }
    }

    private static void particle(Level level, ParticleOptions particle, boolean visible,
                                 double x, double y, double z, double vx, double vy, double vz) {
        // Count every Level.addParticle invocation, even if vanilla's own
        // settings/distance culling subsequently declines to create a particle.
        explosionSpawnAttempts++;
        if (visible) level.addParticle(particle, true, x, y, z, vx, vy, vz);
        else level.addParticle(particle, x, y, z, vx, vy, vz);
    }

    /** Exact registry identity: addon DroneEntity subclasses keep their own layers. */
    public static boolean hideReconGlow(Entity entity) {
        if (!NATIVE_RECON_DRONE.equals(EntityType.getKey(entity.getType()))) return false;
        scope(Minecraft.getInstance().level);
        droneLayersSkipped++;
        return true;
    }
}

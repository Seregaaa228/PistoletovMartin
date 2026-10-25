package dev.frontier.ifv.client;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Client-only replacement for native endlessly refreshed wreck smoke and fire. */
public final class WreckEffects {
    public static final int MAX_PARTICLES_PER_TICK = 24;
    public static final double MAX_DISTANCE = 128;
    private static final Map<Entity, WreckEffectTimeline> wrecks = new WeakHashMap<>();
    private static Level world;
    private static long tick = Long.MIN_VALUE;
    public static volatile int particleAttempts;
    public static volatile int nativeCallsSuppressed;
    public static volatile long transitionsObserved;
    public static volatile long lastBrightExpiry = Long.MIN_VALUE;
    public static volatile long lastSmokeExpiry = Long.MIN_VALUE;

    private WreckEffects() { }

    /** Spawn metadata for already-dead entities is not a new destruction event. */
    public static void transition(Entity wreck, boolean destroyed) {
        if (!scope(wreck.level())) return;
        if (!destroyed) {
            wrecks.remove(wreck);
        } else if (wreck.tickCount > 1 && !wrecks.containsKey(wreck)) {
            wrecks.put(wreck, new WreckEffectTimeline(wreck.level().getGameTime()));
            transitionsObserved++;
        }
    }

    public static int age(Entity wreck) {
        if (!scope(wreck.level())) return -1;
        WreckEffectTimeline timeline = wrecks.get(wreck);
        return timeline == null ? -1 : timeline.age(wreck.level().getGameTime());
    }

    private static boolean scope(Level level) {
        Minecraft mc = Minecraft.getInstance();
        if (level != mc.level || mc.level == null) return false;
        if (world != mc.level) {
            wrecks.clear();
            world = mc.level;
            tick = Long.MIN_VALUE;
        }
        long now = mc.level.getGameTime();
        if (tick != now) {
            tick = now;
            particleAttempts = nativeCallsSuppressed = 0;
        }
        return true;
    }

    /** Called instead of native cosmetic particle helpers; no server tick is cancelled. */
    public static void particles(Entity wreck) {
        Minecraft mc = Minecraft.getInstance();
        if (!scope(wreck.level())) return;
        long now = mc.level.getGameTime();
        nativeCallsSuppressed++;
        WreckEffectTimeline effect = wrecks.get(wreck);
        // Only an observed wreck transition starts an effect. Reload/retrack of
        // an old wreck has no timeline and cannot replay its destruction.
        if (effect == null || !effect.emit(now) || particleAttempts >= MAX_PARTICLES_PER_TICK
                || mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(wreck.position())
                    > MAX_DISTANCE * MAX_DISTANCE) return;
        double y = wreck.getY() + wreck.getBbHeight() * .5;
        int flameLife = effect.brightLifetime(now);
        if (flameLife > 0) spawn(ParticleTypes.FLAME, wreck.getX(), y, wreck.getZ(), flameLife);
        int smokeLife = effect.smokeLifetime(now);
        if (smokeLife > 0) spawn(ParticleTypes.SMOKE, wreck.getX(), y + .25, wreck.getZ(), smokeLife);
    }

    private static void spawn(ParticleOptions type, double x, double y, double z, int life) {
        nativeParticle(world, type, false, x, y, z, 0, .035, 0, life);
    }

    /** Pre-existing damaged-vehicle clouds must also expire within the wreck deadline. */
    public static void nativeParticle(Level level, ParticleOptions type, boolean visible,
            double x, double y, double z, double vx, double vy, double vz) {
        nativeParticle(level, type, visible, x, y, z, vx, vy, vz, WreckEffectTimeline.BRIGHT_TICKS);
    }

    private static void nativeParticle(Level level, ParticleOptions type, boolean visible,
            double x, double y, double z, double vx, double vy, double vz, int life) {
        if (!scope(level) || Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()
                .distanceToSqr(x, y, z) > MAX_DISTANCE * MAX_DISTANCE) return;
        if (particleAttempts >= MAX_PARTICLES_PER_TICK) return;
        particleAttempts++;
        var particle = Minecraft.getInstance().particleEngine.createParticle(type, x, y, z, vx, vy, vz);
        if (particle != null) {
            particle.setLifetime(Math.min(particle.getLifetime(), life));
            long expiry = level.getGameTime() + particle.getLifetime();
            if (type == ParticleTypes.FLAME) lastBrightExpiry = expiry;
            if (type == ParticleTypes.SMOKE) lastSmokeExpiry = expiry;
        }
    }
}

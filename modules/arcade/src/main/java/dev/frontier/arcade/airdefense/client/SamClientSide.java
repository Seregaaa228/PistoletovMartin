package dev.frontier.arcade.airdefense.client;

import dev.frontier.arcade.airdefense.AirDefense;
import dev.frontier.arcade.airdefense.SamMissile;
import dev.frontier.arcade.airdefense.SamMissileEntity;
import dev.frontier.arcade.airdefense.SamNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Air-defence effects, all on the client and only near the viewer: the launch blast and smoke,
 * the motor's trail and the burst. Detail falls with distance from the camera and a per-tick
 * particle budget caps a salvo; past the cut-off a missile draws only its glow.
 */
final class SamClientSide implements AirDefense.Client {
    /** Particles this client may add per tick for all missiles together. */
    private static final int BUDGET = 160;
    private static final double TRAIL_SIGHT = 224, LAUNCH_SIGHT = 256;
    private static final RandomSource RANDOM = RandomSource.create();
    private static long budgetTick;
    private static int spent;

    static volatile SamNetwork.Picture picture;
    static long pictureTick;
    static SamNetwork.Warning warning;
    static long warningTick;

    @Override
    public void picture(SamNetwork.Picture picture) {
        SamClientSide.picture = picture;
        var mc = Minecraft.getInstance();
        pictureTick = mc.level == null ? 0 : mc.level.getGameTime();
    }

    @Override
    public void warning(SamNetwork.Warning warning) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        SamClientSide.warning = warning;
        warningTick = mc.level.getGameTime();
        SamSounds.inbound(warning.missileId());
    }

    @Override
    public void launch(SamMissileEntity missile) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 at = missile.position(), dir = missile.direction();
        double distance = at.distanceTo(camera);
        // Launch roar by distance, arriving late with the speed of sound; then the motor's loop.
        String sound = distance < 48 ? "entity.interceptor.launch_close" : distance < 160 ? "entity.interceptor.launch_mid" : "entity.interceptor.launch_far";
        SamSounds.delayed(new ResourceLocation("deepstrike", sound), at, distance < 48 ? 1.6f : 4f, 0.95f + RANDOM.nextFloat() * 0.1f);
        SamSounds.launched(missile);
        SamTrails.flash(at, 2.2f, true);
        if (distance > LAUNCH_SIGHT) return;
        float detail = distance < 64 ? 1f : distance < 128 ? 0.6f : 0.3f;
        boolean vertical = missile.missileType().verticalTicks > 0;
        // Flash and the motor's first flame.
        for (int i = 0; i < 10 * detail; i++)
            add(ParticleTypes.FLAME, at.add(jitter(0.4)), dir.scale(-0.3).add(jitter(0.15)));
        // Blast of smoke at the launcher: rolling along the ground for rail launch, a column for a cold vertical launch.
        Vec3 base = vertical ? at.add(0, -6, 0) : at.subtract(dir.scale(2.5));
        for (int i = 0; i < 26 * detail; i++) {
            Vec3 drift = vertical ? new Vec3(RANDOM.nextGaussian() * 0.25, 0.05 + RANDOM.nextFloat() * 0.25, RANDOM.nextGaussian() * 0.25)
                : dir.scale(-0.25 - RANDOM.nextFloat() * 0.3).add(RANDOM.nextGaussian() * 0.2, 0.06, RANDOM.nextGaussian() * 0.2);
            add(i % 3 == 0 ? ParticleTypes.CAMPFIRE_COSY_SMOKE : ParticleTypes.CLOUD, base.add(jitter(1.2)), drift);
        }
        for (int i = 0; i < 12 * detail; i++)
            add(ParticleTypes.LARGE_SMOKE, base.add(jitter(1.5)), new Vec3(RANDOM.nextGaussian() * 0.15, 0.05, RANDOM.nextGaussian() * 0.15));
        if (vertical) for (int i = 0; i < 8 * detail; i++)
            add(ParticleTypes.POOF, at.add(jitter(0.6)), new Vec3(RANDOM.nextGaussian() * 0.2, 0.3, RANDOM.nextGaussian() * 0.2));
    }

    @Override
    public void trail(SamMissileEntity missile) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 at = missile.position(), dir = missile.direction();
        double distance = at.distanceTo(camera);
        SamSounds.flyby(missile, distance);
        boolean burning = missile.burning();
        Vec3 nozzle = at.subtract(dir.scale(0.9));
        // The continuous trail is drawn geometry (SamTrails); particles only add texture up close.
        SamTrails.record(missile, nozzle);
        if (distance > TRAIL_SIGHT) return;
        Vec3 step = dir.scale(Math.max(0.5, missile.getDeltaMovement().length()));
        // Fill the gap the missile flew this tick so the trail reads as one line, not beads.
        int puffs = burning ? (distance < 48 ? 3 : distance < 96 ? 1 : 0) : 0;
        for (int i = 0; i < puffs; i++) {
            Vec3 p = nozzle.subtract(step.scale(i / (double) Math.max(1, puffs)));
            add(burning ? ParticleTypes.CAMPFIRE_COSY_SMOKE : ParticleTypes.CLOUD, p.add(jitter(0.12)), new Vec3(0, 0.012, 0));
        }
        if (burning && distance < 96) add(ParticleTypes.FLAME, nozzle, dir.scale(-0.2));
    }

    @Override
    public void burst(SamNetwork.Burst burst) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 at = new Vec3(burst.x(), burst.y(), burst.z());
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        double distance = at.distanceTo(camera);
        SamMissile type = SamMissile.of(burst.missile());
        // Near: the sharp burst; farther: SBW's distant explosion; both late by the speed of sound.
        if (distance < 64) SamSounds.delayed(new ResourceLocation("deepstrike", "entity.interceptor.pac2_burst"), at, 4f, 0.9f + RANDOM.nextFloat() * 0.2f);
        else SamSounds.delayed(new ResourceLocation("superbwarfare", distance < 192 ? "explosion_far" : "explosion_very_far"), at, 8f, 1f);
        float size = type.plume;
        SamTrails.flash(at, 4.5f * size, true);
        float detail = distance < 96 ? 1f : distance < 192 ? 0.5f : 0.25f;
        add(ParticleTypes.FLASH, at, Vec3.ZERO);
        add(ParticleTypes.EXPLOSION, at, Vec3.ZERO);
        for (int i = 0; i < 3 * size; i++) add(ParticleTypes.EXPLOSION, at.add(jitter(1.6 * size)), Vec3.ZERO);
        // A dark ball of smoke that hangs, and fragments falling as sparks.
        for (int i = 0; i < 24 * detail * size; i++)
            add(ParticleTypes.LARGE_SMOKE, at.add(jitter(1.8 * size)), jitter(0.12));
        for (int i = 0; i < 14 * detail * size; i++)
            add(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.add(jitter(1.2 * size)), new Vec3(0, 0.01, 0));
        for (int i = 0; i < 18 * detail * size; i++)
            add(ParticleTypes.LAVA, at.add(jitter(0.8)), Vec3.ZERO);
    }

    private static Vec3 jitter(double scale) {
        return new Vec3(RANDOM.nextGaussian() * scale, RANDOM.nextGaussian() * scale, RANDOM.nextGaussian() * scale);
    }

    private static void add(ParticleOptions type, Vec3 at, Vec3 velocity) {
        var mc = Minecraft.getInstance();
        long now = mc.level.getGameTime();
        if (now != budgetTick) { budgetTick = now; spent = 0; }
        if (++spent > BUDGET) return;
        mc.level.addAlwaysVisibleParticle(type, true, at.x, at.y, at.z, velocity.x, velocity.y, velocity.z);
    }
}

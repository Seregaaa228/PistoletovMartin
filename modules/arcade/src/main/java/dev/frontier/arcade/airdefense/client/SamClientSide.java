package dev.frontier.arcade.airdefense.client;

import dev.frontier.arcade.airdefense.AirDefense;
import dev.frontier.arcade.airdefense.SamMissile;
import dev.frontier.arcade.airdefense.SamMissileEntity;
import dev.frontier.arcade.airdefense.SamNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The client half of the air defence: the crew's radar picture, the pilot's launch warning, and
 * the launch, trail and burst effects ({@link SamFx}) and sounds ({@link SamSounds}). Effects
 * thin out with distance and stop at these sights.
 */
final class SamClientSide implements AirDefense.Client {
    private static final double TRAIL_SIGHT = 512, LAUNCH_SIGHT = 512, BURST_SIGHT = 768;
    private static final RandomSource RANDOM = RandomSource.create();

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
    public void launch(SamMissileEntity missile, boolean fresh) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 at = missile.position(), dir = missile.direction();
        double distance = at.distanceTo(camera);
        SamSounds.launched(missile);
        if (!fresh) return;
        // Launch roar by distance, arriving late with the speed of sound; then the motor's loop.
        String sound = distance < 48 ? "entity.interceptor.launch_close" : distance < 160 ? "entity.interceptor.launch_mid" : "entity.interceptor.launch_far";
        SamSounds.delayed(new ResourceLocation("deepstrike", sound), at, distance < 48 ? 1.6f : SamSounds.reach(distance, 4f), 0.95f + RANDOM.nextFloat() * 0.1f);
        if (distance <= LAUNCH_SIGHT) SamFx.launch(at, dir, missile.missileType().verticalTicks > 0, distance, missile.missileType().plume);
    }

    @Override
    public void trail(SamMissileEntity missile) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 at = missile.position(), dir = missile.direction();
        double distance = at.distanceTo(camera);
        SamSounds.flyby(missile, distance);
        if (distance > TRAIL_SIGHT) return;
        // Smoke along a whole tick's stretch, one tick behind: the missile is drawn between its last
        // two positions, so smoke laid up to the newest one would show ahead of its nose. The
        // renderer's own plume bridges the nozzle and the newest smoke.
        Vec3 back = dir.scale(SamMissileRenderer.Look.tail(missile.missileType()));
        Vec3 last = new Vec3(missile.xo, missile.yo, missile.zo).subtract(back);
        Vec3 before = last.subtract(at.subtract(back).subtract(last));
        SamFx.trail(before, last, missile.burning(), missile.missileType().plume, distance);
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
        else SamSounds.delayed(new ResourceLocation("superbwarfare", distance < 192 ? "explosion_far" : "explosion_very_far"), at, SamSounds.reach(distance, 8f), 1f);
        if (distance <= BURST_SIGHT) SamFx.burst(at, type.plume, distance);
    }
}

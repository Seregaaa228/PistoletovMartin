package dev.frontier.aviation.weapons;

import com.atsuishio.superbwarfare.init.ModSounds;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * What a КАБ impact looks and sounds like from afar. Vanilla culls ordinary particles
 * beyond 32 blocks and SBW's explosion sounds fade within ~100, so every player in range
 * gets forced (long-distance) fireball, flash and a lingering smoke column, and a boom
 * picked by distance and played next to them so it is heard at any range.
 */
public final class KabImpact {
    private KabImpact() {}

    public static void broadcast(ServerLevel server, Vec3 at, int variant, double range) {
        // 0: КАБ-500, 1: КАБ-1500, 2: КАБ-3000.
        float size = 1.0F + variant * 0.8F;
        for (ServerPlayer player : server.players()) {
            double distance = player.position().distanceTo(at);
            if (distance > range) continue;
            particles(server, player, at, size);
            boom(player, at, distance, variant);
        }
    }

    private static void particles(ServerLevel server, ServerPlayer player, Vec3 at, float size) {
        int s = Math.round(size * 4);
        send(server, player, ParticleTypes.FLASH, at.add(0, 2, 0), 3, 0, 0, 0, 0);
        send(server, player, ParticleTypes.EXPLOSION_EMITTER, at.add(0, 1, 0), 2 + s, 3 * size, 2 * size, 3 * size, 0);
        send(server, player, ParticleTypes.LAVA, at, 10 * s, 2 * size, 1, 2 * size, 0.8);
        // Mushroom: a fast rising column topped by a wide cap, then long signal smoke.
        send(server, player, ParticleTypes.LARGE_SMOKE, at.add(0, 4, 0), 30 * s, size, 6 * size, size, 0.35);
        send(server, player, ParticleTypes.LARGE_SMOKE, at.add(0, 14 * size, 0), 30 * s, 6 * size, 2 * size, 6 * size, 0.15);
        send(server, player, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, at.add(0, 2, 0), 8 * s, 3 * size, 2, 3 * size, 0.06);
        send(server, player, ParticleTypes.CAMPFIRE_COSY_SMOKE, at.add(0, 1, 0), 16 * s, 5 * size, 1, 5 * size, 0.04);
    }

    private static void send(ServerLevel server, ServerPlayer player, ParticleOptions type, Vec3 at,
                             int count, double dx, double dy, double dz, double speed) {
        server.sendParticles(player, type, true, at.x, at.y, at.z, count, dx, dy, dz, speed);
    }

    private static void boom(ServerPlayer player, Vec3 at, double distance, int variant) {
        SoundEvent sound;
        if (distance < 80) sound = close(variant);
        else if (distance < 300) sound = far(variant);
        else sound = veryFar(variant);
        // The client attenuates by distance, so the source is put a few blocks from the
        // listener in the impact direction; direction and loudness stay right.
        Vec3 toward = distance < 1 ? Vec3.ZERO : at.subtract(player.position()).normalize().scale(Math.min(distance, 8));
        Vec3 source = distance < 80 ? at : player.position().add(toward);
        float volume = distance < 80 ? 6.0F : (float) Math.max(0.6, 2.0 - distance / 700);
        float pitch = 1.0F - variant * 0.12F;
        player.connection.send(new ClientboundSoundPacket(Holder.direct(sound), SoundSource.BLOCKS,
            source.x, source.y, source.z, volume, pitch, player.getRandom().nextLong()));
    }

    private static SoundEvent close(int variant) {
        return (variant == 0 ? ModSounds.EXPLOSION_CLOSE : variant == 1 ? ModSounds.HUGE_EXPLOSION_CLOSE : ModSounds.EPIC_EXPLOSION_CLOSE).get();
    }

    private static SoundEvent far(int variant) {
        return (variant == 0 ? ModSounds.EXPLOSION_FAR : variant == 1 ? ModSounds.HUGE_EXPLOSION_FAR : ModSounds.EPIC_EXPLOSION_FAR).get();
    }

    private static SoundEvent veryFar(int variant) {
        return (variant == 0 ? ModSounds.EXPLOSION_VERY_FAR : variant == 1 ? ModSounds.HUGE_EXPLOSION_VERY_FAR : ModSounds.EPIC_EXPLOSION_VERY_FAR).get();
    }
}

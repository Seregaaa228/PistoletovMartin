package dev.frontier.arcade.artillery;

import dev.frontier.arcade.ArcadeVehicles;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The look and sound of a self-propelled-gun shell bursting, on top of SBW's own explosion. Everyone in sight range
 * sees a fireball, thrown earth, a smoke cloud and a rising column at once. The boom travels at the speed of sound:
 * crews near the burst hear it immediately, a distant observer a few seconds after the flash, quieter and lower.
 * Sizes follow the shell's explosion radius, so an HE burst dwarfs an AP one. All of it is vanilla particles and
 * sounds sent from the server: no client asset, nothing extra to install.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class ArtilleryBlast {
    /** Players this far see the burst; forced particles render up to 512 blocks. */
    private static final double SIGHT = 512;
    /** Players this far hear the distant boom. */
    private static final double HEARING = 1600;
    /** Nearer than this the burst's own world sound already reaches the player. */
    private static final double NEAR = 48;
    /** 343 m/s in blocks per tick. */
    private static final double SOUND_PER_TICK = 343.0 / 20.0;
    /** Beyond these, a player gets 60% / 35% of the particles: far bursts are small on screen anyway. */
    private static final double DETAIL_NEAR = 128, DETAIL_FAR = 256;
    /** Launch smoke is seen this far; farther away only the sound of the launch arrives. */
    private static final double LAUNCH_SIGHT = 384;
    private static final int MAX_ECHOES = 1024;
    private static final ResourceLocation ROCKET_FIRE = new ResourceLocation("superbwarfare", "medium_rocket_fire");
    /** DeepStrike's heavy launch as heard from the middle distance and from far off. */
    private static final ResourceLocation LAUNCH_MID = new ResourceLocation("deepstrike", "entity.missile.launch_mid");
    private static final ResourceLocation LAUNCH_FAR = new ResourceLocation("deepstrike", "entity.missile.launch_far");
    /** Beyond this the far launch sound is used. */
    private static final double MID_LAUNCH = 400;
    /** A salvo reaches each listener as a ripple, not forty stacked copies: one roar per player per this many ticks. */
    private static final int ROAR_GAP = 3;
    private static final java.util.Map<UUID, Long> LAST_ROAR = new java.util.HashMap<>();
    private static final List<Echo> ECHOES = new ArrayList<>();

    /** A sound on its way to one listener: a burst's boom, or a rocket launch's roar. */
    private record Echo(ServerLevel level, UUID player, long due, Vec3 blast, double distance, boolean launch) { }

    private ArtilleryBlast() { }

    public static void burst(ServerLevel level, Vec3 at, float radius) {
        float r = Math.max(radius, 4);
        // A launcher salvo is forty small bursts: each gets fewer particles than one heavy shell.
        float weight = radius >= 30 ? 1 : 0.6f;
        var below = level.getBlockState(BlockPos.containing(at).below());
        var ground = below.isAir() ? Blocks.DIRT.defaultBlockState() : below;
        var debris = new BlockParticleOption(ParticleTypes.BLOCK, ground);
        long now = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            double distance = player.position().distanceTo(at);
            if (distance <= SIGHT) {
                float k = weight * (distance <= DETAIL_NEAR ? 1 : distance <= DETAIL_FAR ? 0.6f : 0.35f);
                send(level, player, ParticleTypes.FLASH, at, 2, 0, 0, 0, 0);
                send(level, player, ParticleTypes.EXPLOSION_EMITTER, at, Math.max(2, Math.round(r / 6 * k)), r * 0.15, r * 0.08, r * 0.15, 0);
                send(level, player, ParticleTypes.EXPLOSION, at, Math.round(r * 2 * k), r * 0.3, r * 0.15, r * 0.3, 0);
                send(level, player, ParticleTypes.FLAME, at, Math.round(r * 3 * k), r * 0.2, r * 0.1, r * 0.2, 0.35);
                send(level, player, ParticleTypes.LAVA, at, Math.round(r * k), r * 0.2, r * 0.1, r * 0.2, 0);
                send(level, player, debris, at.add(0, 1, 0), Math.round(r * 6 * k), r * 0.3, r * 0.15, r * 0.3, 0.6);
                send(level, player, ParticleTypes.LARGE_SMOKE, at.add(0, r * 0.15, 0), Math.round(r * 4 * k), r * 0.35, r * 0.25, r * 0.35, 0.08);
                send(level, player, ParticleTypes.CLOUD, at, Math.round(r * 3 * k), r * 0.5, 0.3, r * 0.5, 0.25);
                send(level, player, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, at, Math.round(r * k), r * 0.15, 1, r * 0.15, 0.02);
            }
            if (distance > NEAR && distance <= HEARING && ECHOES.size() < MAX_ECHOES)
                ECHOES.add(new Echo(level, player.getUUID(), now + Math.round(distance / SOUND_PER_TICK), at, distance, false));
        }
        // Up close: a sharp crack over SBW's own blast, carried as a world sound to everyone within ~130 blocks.
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 8, 0.55f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.BLOCKS, 6, 0.6f);
    }

    /**
     * A round leaving a gun or launcher. A shell: muzzle fireball and a smoke cloud, dust kicked up around the
     * vehicle. A rocket: dust kicked up round the launcher (each client draws the backblast and the motor jet), and
     * its roar reaching distant players at the speed of sound (SBW plays the gun's own near and far report).
     */
    public static void launch(ServerLevel level, Entity gun, Entity round, boolean rocket) {
        Vec3 muzzle = round.position();
        Vec3 ahead = round.getDeltaMovement().lengthSqr() < 1.0E-6 ? Vec3.ZERO : round.getDeltaMovement().normalize();
        Vec3 ground = gun.position().add(0, 0.3, 0);
        long now = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            double distance = player.position().distanceTo(muzzle);
            if (distance <= LAUNCH_SIGHT) {
                float k = distance <= DETAIL_NEAR ? 1 : 0.5f;
                if (rocket) {
                    // The backblast out of the back of the pack is drawn by each client (RocketJets); here only dust.
                    send(level, player, ParticleTypes.CLOUD, ground, Math.round(6 * k), 2.5, 0.3, 2.5, 0.06);
                } else {
                    send(level, player, ParticleTypes.EXPLOSION, muzzle.add(ahead), 1, 0, 0, 0, 0);
                    send(level, player, ParticleTypes.LARGE_SMOKE, muzzle.add(ahead.scale(1.5)), Math.round(18 * k), 1.2, 0.8, 1.2, 0.06);
                    send(level, player, ParticleTypes.CLOUD, ground, Math.round(24 * k), 4, 0.2, 4, 0.12);
                }
            }
            if (rocket && distance > NEAR && distance <= HEARING && ECHOES.size() < MAX_ECHOES)
                ECHOES.add(new Echo(level, player.getUUID(), now + Math.round(distance / SOUND_PER_TICK), muzzle, distance, true));
        }
    }

    private static void send(ServerLevel level, ServerPlayer player, ParticleOptions type, Vec3 at, int count,
                             double dx, double dy, double dz, double speed) {
        level.sendParticles(player, type, true, at.x, at.y, at.z, count, dx, dy, dz, speed);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ECHOES.isEmpty()) return;
        ECHOES.removeIf(echo -> {
            if (echo.level.getGameTime() < echo.due) return false;
            if (echo.level.getPlayerByUUID(echo.player) instanceof ServerPlayer player) {
                if (echo.launch) roar(player, echo); else boom(player, echo);
            }
            return true;
        });
    }

    /**
     * The far boom, played two blocks from the listener toward the burst so it keeps its direction; louder and
     * sharper the nearer it is, a low rolling thunder from far away.
     */
    private static void boom(ServerPlayer player, Echo echo) {
        Vec3 ear = player.getEyePosition();
        Vec3 toward = echo.blast.subtract(ear);
        Vec3 source = toward.lengthSqr() < 1.0E-6 ? ear : ear.add(toward.normalize().scale(2));
        float near = (float) (1 - echo.distance / HEARING);
        float volume = 0.4f + 0.6f * near;
        long seed = player.getRandom().nextLong();
        play(player, SoundEvents.GENERIC_EXPLODE, source, volume, 0.4f + 0.2f * near, seed);
        play(player, SoundEvents.LIGHTNING_BOLT_THUNDER, source, volume * 0.7f, 0.65f + 0.2f * near, seed);
    }

    /** A distant rocket launch: DeepStrike's heavy launch at mid or far range, quieter with distance, from its direction. */
    private static void roar(ServerPlayer player, Echo echo) {
        long now = echo.level.getGameTime();
        Long last = LAST_ROAR.get(player.getUUID());
        if (last != null && now - last < ROAR_GAP) return;
        LAST_ROAR.put(player.getUUID(), now);
        Vec3 ear = player.getEyePosition();
        Vec3 toward = echo.blast.subtract(ear);
        Vec3 source = toward.lengthSqr() < 1.0E-6 ? ear : ear.add(toward.normalize().scale(2));
        float near = (float) (1 - echo.distance / HEARING);
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(echo.distance < MID_LAUNCH ? LAUNCH_MID : LAUNCH_FAR);
        if (sound == null) sound = ForgeRegistries.SOUND_EVENTS.getValue(ROCKET_FIRE);
        play(player, sound != null ? sound : SoundEvents.FIREWORK_ROCKET_LAUNCH, source, 0.35f + 0.55f * near, 0.9f + 0.15f * near,
            player.getRandom().nextLong());
    }

    private static void play(ServerPlayer player, SoundEvent sound, Vec3 at, float volume, float pitch, long seed) {
        player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.BLOCKS,
            at.x, at.y, at.z, volume, pitch, seed));
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        ECHOES.clear();
        LAST_ROAR.clear();
    }
}

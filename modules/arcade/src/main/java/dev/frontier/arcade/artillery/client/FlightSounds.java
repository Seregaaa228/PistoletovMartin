package dev.frontier.arcade.artillery.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Sound for rounds the server carries outside active terrain, where no entity exists for SBW to give its own flight
 * sound. The few nearest rockets carry DeepStrike's missile flight roar along with them; a shell or rocket coming
 * down near the listener whistles once as it passes. Client-side: nothing is sent, and at most {@link #LOOPS} loops
 * play at once so a salvo cannot use up the sound channels.
 */
final class FlightSounds {
    private static final int LOOPS = 6;
    /** Rockets this close are heard roaring by. */
    private static final double ROAR = 160;
    /** A round coming down this close whistles. */
    private static final double WHISTLE = 140;
    private static final ResourceLocation FLIGHT = new ResourceLocation("deepstrike", "entity.missile.flight_loop");
    private static final ResourceLocation FLYBY = new ResourceLocation("deepstrike", "entity.missile.whistle_flyby");
    private static final ResourceLocation SHELL = new ResourceLocation("superbwarfare", "shell_fly");
    private static final ResourceLocation ROCKET = new ResourceLocation("superbwarfare", "rocket_fly");
    private static final Map<Integer, Roar> ROARING = new HashMap<>();
    private static final Set<Integer> WHISTLED = new HashSet<>();

    /** One round this tick: its id, where it is, how it moves, and whether it is a rocket. */
    record Round(int id, Vec3 position, Vec3 velocity, boolean rocket) { }

    private FlightSounds() { }

    static void update(List<Round> rounds, Vec3 listener) {
        var mc = Minecraft.getInstance();
        List<Round> rockets = new ArrayList<>();
        Set<Integer> present = new HashSet<>();
        for (Round round : rounds) {
            present.add(round.id);
            double distance = round.position.distanceTo(listener);
            if (round.rocket && distance < ROAR) rockets.add(round);
            if (round.velocity.y < 0 && distance < WHISTLE && WHISTLED.add(round.id)) {
                SoundEvent sound = sound(round.rocket ? FLYBY : SHELL, round.rocket ? ROCKET : SHELL);
                if (sound != null) mc.getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.NEUTRAL,
                    (float) Math.max(0.4, 2.2 - distance / 80), 0.9f + mc.level.getRandom().nextFloat() * 0.2f, RandomSource.create(),
                    round.position.x, round.position.y, round.position.z));
            }
        }
        rockets.sort(Comparator.comparingDouble(round -> round.position.distanceToSqr(listener)));
        Set<Integer> keep = new HashSet<>();
        for (int i = 0; i < Math.min(LOOPS, rockets.size()); i++) {
            Round round = rockets.get(i);
            keep.add(round.id);
            Roar roar = ROARING.get(round.id);
            if (roar == null) {
                SoundEvent sound = sound(FLIGHT, ROCKET);
                if (sound == null) continue;
                roar = new Roar(sound, round.position);
                ROARING.put(round.id, roar);
                mc.getSoundManager().play(roar);
            }
            roar.at = round.position;
        }
        ROARING.entrySet().removeIf(entry -> {
            if (keep.contains(entry.getKey()) && !entry.getValue().isStopped()) return false;
            entry.getValue().finish();
            return true;
        });
        WHISTLED.retainAll(present);
    }

    static void clear() {
        ROARING.values().forEach(Roar::finish);
        ROARING.clear();
        WHISTLED.clear();
    }

    private static SoundEvent sound(ResourceLocation id, ResourceLocation fallback) {
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(id);
        if (sound == null || sound == SoundEvents.EMPTY) sound = ForgeRegistries.SOUND_EVENTS.getValue(fallback);
        return sound;
    }

    /** A flight roar that follows its rocket. */
    private static final class Roar extends AbstractTickableSoundInstance {
        Vec3 at;

        Roar(SoundEvent sound, Vec3 at) {
            super(sound, SoundSource.NEUTRAL, RandomSource.create());
            this.at = at;
            this.looping = true;
            this.delay = 0;
            this.volume = 1.6f;
            this.x = at.x; this.y = at.y; this.z = at.z;
        }

        @Override
        public void tick() {
            this.x = at.x; this.y = at.y; this.z = at.z;
        }

        void finish() {
            stop();
        }
    }
}

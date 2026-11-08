package dev.frontier.arcade.airdefense.client;

import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.airdefense.SamMissileEntity;
import dev.frontier.arcade.airdefense.SamSiteEntity;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Air-defence sound, all client-side from what the client already knows (no extra packets):
 * the launcher's servos and hydraulics, each missile's motor loop and fly-by whistle, launches and
 * bursts heard late with distance, the crew's radar and lock tones, and the pilot's warning that
 * beeps faster as the missile closes. Loops are capped and only play near the listener.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class SamSounds {
    /** Blocks per tick: sound travels ~340 m/s. */
    private static final double SOUND_SPEED = 17;
    private static final int MAX_LOOPS = 6;
    private static final double LOOP_RANGE = 160, SERVO_RANGE = 48, FLYBY_RANGE = 22;
    private static final RandomSource RANDOM = RandomSource.create();
    private record Delayed(long at, ResourceLocation id, Vec3 where, float volume, float pitch) { }
    private static final List<Delayed> DELAYED = new ArrayList<>();
    private static final Map<SamSiteEntity, float[]> POSES = new WeakHashMap<>();
    private static final Map<SamMissileEntity, Boolean> FLYBY = new WeakHashMap<>();
    private static final List<MissileLoop> LOOPS = new ArrayList<>();
    /** Missiles launched at us (the pilot), by entity id, with the tick of the next warning beep. */
    private static final Map<Integer, Long> INBOUND = new java.util.HashMap<>();

    private SamSounds() { }

    static ResourceLocation id(String namespace, String path) { return new ResourceLocation(namespace, path); }

    /** A positional sound that reaches the listener after the time sound takes to travel. */
    static void delayed(ResourceLocation id, Vec3 where, float volume, float pitch) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        double distance = where.distanceTo(mc.gameRenderer.getMainCamera().getPosition());
        DELAYED.add(new Delayed(mc.level.getGameTime() + (long) (distance / SOUND_SPEED), id, where, volume, pitch));
    }

    static void ui(ResourceLocation id, float volume, float pitch) {
        SoundEvent event = ForgeRegistries.SOUND_EVENTS.getValue(id);
        if (event != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
    }

    /** A new missile on this client: its motor loop, if one is free and it is near enough. */
    static void launched(SamMissileEntity missile) {
        var mc = Minecraft.getInstance();
        LOOPS.removeIf(AbstractTickableSoundInstance::isStopped);
        if (LOOPS.size() >= MAX_LOOPS || missile.distanceTo(mc.player) > LOOP_RANGE) return;
        SoundEvent event = ForgeRegistries.SOUND_EVENTS.getValue(id("deepstrike", "entity.interceptor.flight_loop"));
        if (event == null) return;
        var loop = new MissileLoop(missile, event);
        LOOPS.add(loop);
        mc.getSoundManager().play(loop);
    }

    /** The server warned us of a launch at our aircraft: track that missile until it is gone. */
    static void inbound(int missileId) {
        var mc = Minecraft.getInstance();
        if (mc.level != null) INBOUND.put(missileId, mc.level.getGameTime());
    }

    static boolean anyInbound() { return !INBOUND.isEmpty(); }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        var mc = Minecraft.getInstance();
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.level == null || mc.player == null) { DELAYED.clear(); INBOUND.clear(); SamTrails.clear(); return; }
        long now = mc.level.getGameTime();
        for (Iterator<Delayed> it = DELAYED.iterator(); it.hasNext(); ) {
            Delayed d = it.next();
            if (now < d.at) continue;
            it.remove();
            SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(d.id);
            if (sound != null) mc.level.playLocalSound(d.where.x, d.where.y, d.where.z, sound, SoundSource.HOSTILE, d.volume, d.pitch, false);
        }
        servos(mc);
        warnings(mc, now);
    }

    /** Turret, launcher and pack movement near the listener: SBW's own turret servo sound. */
    private static void servos(Minecraft mc) {
        AABB near = mc.player.getBoundingBox().inflate(SERVO_RANGE);
        for (SamSiteEntity site : mc.level.getEntitiesOfClass(SamSiteEntity.class, near)) {
            float[] now = {site.aimYaw(), site.aimPitch(), site.deployPercent()};
            float[] before = POSES.put(site, now);
            if (before == null) continue;
            float yaw = net.minecraft.util.Mth.wrapDegrees(now[0] - before[0]), pitch = now[1] - before[1];
            if (Math.abs(yaw) > 0.5f || Math.abs(pitch) > 0.5f) site.turretTurnSound(pitch, yaw, 1.0f);
            // Hydraulics: the pack rising or folding sounds lower and longer than a turret.
            if (now[2] != before[2] && site.tickCount % 4 == 0) site.turretTurnSound(3, 0, 0.55f);
        }
    }

    /** The pilot's warning: one beep per interval, faster as the missile closes; ends when it is gone. */
    private static void warnings(Minecraft mc, long now) {
        for (Iterator<Map.Entry<Integer, Long>> it = INBOUND.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var missile = mc.level.getEntity(entry.getKey());
            if (!(missile instanceof SamMissileEntity) || !missile.isAlive()) {
                // A missile not yet (or no longer) tracked by this client: keep a few seconds of grace.
                if (missile == null && now - entry.getValue() < 60) continue;
                it.remove();
                continue;
            }
            if (now < entry.getValue()) continue;
            double distance = missile.distanceTo(mc.player);
            ui(id("superbwarfare", distance < 64 ? "locked_warning" : "missile_warning"), 0.9f, 1.0f);
            entry.setValue(now + (long) Math.max(2, Math.min(20, distance / 14)));
        }
    }

    /** A missile passing close: one whistle. */
    static void flyby(SamMissileEntity missile, double distance) {
        if (distance > FLYBY_RANGE || FLYBY.put(missile, Boolean.TRUE) != null) return;
        SoundEvent event = ForgeRegistries.SOUND_EVENTS.getValue(id("deepstrike", "entity.missile.whistle_flyby"));
        Vec3 p = missile.position();
        if (event != null) Minecraft.getInstance().level.playLocalSound(p.x, p.y, p.z, event, SoundSource.HOSTILE, 2.5f,
            0.95f + RANDOM.nextFloat() * 0.1f, false);
    }

    /** The motor's roar, following the missile; it fades after burnout and stops when the missile is gone. */
    private static final class MissileLoop extends AbstractTickableSoundInstance {
        private final SamMissileEntity missile;

        MissileLoop(SamMissileEntity missile, SoundEvent event) {
            super(event, SoundSource.HOSTILE, SoundInstance.createUnseededRandom());
            this.missile = missile;
            this.looping = true;
            this.delay = 0;
            this.volume = 2.5f;
            this.pitch = 0.9f + RANDOM.nextFloat() * 0.2f;
            this.x = missile.getX();
            this.y = missile.getY();
            this.z = missile.getZ();
        }

        @Override
        public void tick() {
            if (!missile.isAlive()) { stop(); return; }
            x = missile.getX();
            y = missile.getY();
            z = missile.getZ();
            volume = missile.burning() ? 2.5f : Math.max(0, volume - 0.08f);
            if (volume <= 0.01f) stop();
        }
    }
}

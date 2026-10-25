package dev.frontier.industry.acceptance;

import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleEffectUtils;
import dev.frontier.aviation.AircraftIdentity;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.ifv.client.WreckEffects;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import dev.sbw.deepstrike.client.Su24mScreen;
import dev.sbw.deepstrike.registry.BFEntities;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/** Opt-in helper only: exercise real native client hooks without touching server entities. */
public final class AviationClientChecks {
    private static Su24mEntity fixture;
    private static long started;
    private static int peak;
    private static boolean emitted;
    private static boolean deadlines = true;
    private static boolean budget = true;
    private static boolean replay;
    private static boolean nativeSu24Identity;
    private static Screen nativeMap, previousScreen;
    private static int nativeMapOpenedAt;
    private static boolean nativeMapAttempted, nativeMapCaptured, nativeMapLabel, nativeMapHeading;
    private static boolean renderListener;
    private static int nativeMapFrames;

    private AviationClientChecks() { }

    public static Map<String, Boolean> tick(Minecraft mc, int ticks) {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        if (mc.level == null || mc.player == null) return checks;
        if (fixture == null || fixture.level() != mc.level) {
            fixture = AircraftProfile.F16.entity.get().create(mc.level);
            if (fixture == null) throw new IllegalStateException("F-16 client fixture could not be created");
            fixture.setPos(mc.gameRenderer.getMainCamera().getPosition().add(mc.player.getLookAngle().scale(8)));
            fixture.tickCount = 10;
            fixture.setWreck(true); // real onSyncedDataUpdated -> WreckTransitionMixin
            started = mc.level.getGameTime();
            peak = 0;
            emitted = false;
            deadlines = budget = true;
            replay = false;
            Su24mEntity nativePlane = BFEntities.SU24M.get().create(mc.level);
            nativeSu24Identity = nativePlane != null
                    && "SU-24M".equals(AircraftIdentity.mapLabel(nativePlane, "SU-24M"))
                    && "Су-24М · бортовой планшет".equals(AircraftIdentity.mapHeading(nativePlane, "Су-24М · бортовой планшет"));
            nativeMap = previousScreen = null;
            nativeMapOpenedAt = 0;
            nativeMapAttempted = nativeMapCaptured = nativeMapLabel = nativeMapHeading = false;
            nativeMapFrames = 0;
        }
        long age = mc.level.getGameTime() - started;
        WreckEffects.age(fixture); // scope per-tick counters before observing this pair of calls
        int before = WreckEffects.particleAttempts;
        int suppressed = WreckEffects.nativeCallsSuppressed;
        Vec3 position = fixture.position();
        // Both pinned helper overloads are used. Multiple calls in one tick
        // must become a single short, bounded local batch for this wreck.
        VehicleEffectUtils.addRandomParticle(fixture, ParticleTypes.FLAME, position, .2F, mc.level, 1F, 20);
        VehicleEffectUtils.addRandomParticle(fixture, ParticleTypes.SMOKE, position, .2F, mc.level, 20, Vec3.ZERO);
        int after = WreckEffects.particleAttempts;
        peak = Math.max(peak, after);
        budget &= after <= WreckEffects.MAX_PARTICLES_PER_TICK;
        if (after > before) {
            emitted = true;
            deadlines &= age < 100;
            if (age < 40) deadlines &= WreckEffects.lastBrightExpiry <= started + 40;
            deadlines &= WreckEffects.lastSmokeExpiry <= started + 100;
        }
        checks.put("aviation_wreck_hooks_suppress_native", WreckEffects.nativeCallsSuppressed >= suppressed + 2);
        checks.put("aviation_wreck_particle_budget", budget && peak <= 24);
        checks.put("aviation_f16_identity", "F-16".equals(AircraftIdentity.mapLabel(fixture, "SU-24M")));
        checks.put("aviation_native_su24_keeps_own_marker_and_heading", nativeSu24Identity);
        actualMap(mc, ticks, checks);
        if (age >= 105) {
            checks.put("aviation_wreck_real_expiry_deadlines", emitted && deadlines && after == before);
            if (!replay) {
                Su24mEntity reloaded = AircraftProfile.F16.entity.get().create(mc.level);
                if (reloaded == null) throw new IllegalStateException("Reloaded F-16 fixture could not be created");
                reloaded.setUUID(fixture.getUUID());
                reloaded.setPos(position);
                reloaded.tickCount = 0;
                reloaded.setWreck(true); // spawn/retrack metadata, not a fresh destruction
                int replayBefore = WreckEffects.particleAttempts;
                VehicleEffectUtils.addRandomParticle(reloaded, ParticleTypes.FLAME, position, .2F, mc.level, 1F, 20);
                replay = WreckEffects.age(reloaded) == -1 && WreckEffects.particleAttempts == replayBefore;
            }
            checks.put("aviation_old_wreck_retrack_no_replay", replay);
        }
        return checks;
    }

    private static void actualMap(Minecraft mc, int ticks, Map<String, Boolean> checks) {
        if (nativeMap != null && mc.screen != nativeMap && nativeMapFrames < 3) {
            // Native tick closes a map whose vehicle disappeared or moved
            // beyond interaction range. A one-tick cannon fixture is not a
            // usable render target; wait for the stable occupied Flight F-16.
            com.mojang.logging.LogUtils.getLogger().info("AVIATION_MAP target closed before capture after {} frames", nativeMapFrames);
            nativeMap = null;
            nativeMapAttempted = false;
        }
        if (!nativeMapAttempted && ticks > 20 && mc.screen == null) {
            for (var entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof Su24mEntity plane) || AircraftProfile.of(plane) != AircraftProfile.F16
                        || plane.isRemoved() || plane.isWreck() || plane.tickCount < 20
                        || plane.getPassengers().isEmpty()
                        || (mc.player.getVehicle() != plane && mc.player.distanceToSqr(plane) > 144)) continue;
                previousScreen = mc.screen;
                Su24mScreen.open(plane.getId());
                if (!(mc.screen instanceof Su24mScreen)) break; // native map provider may still be warming up
                nativeMapAttempted = true;
                nativeMap = mc.screen;
                nativeMapOpenedAt = ticks;
                nativeMapFrames = 0;
                com.mojang.logging.LogUtils.getLogger().info("AVIATION_MAP stable tracked F16={} age={} distance={} cockpit={}",
                        plane.getId(), plane.tickCount, mc.player.distanceToSqr(plane), mc.player.getVehicle() == plane);
                if (!renderListener) {
                    MinecraftForge.EVENT_BUS.addListener(AviationClientChecks::render);
                    renderListener = true;
                }
                // Query the actual transformed native handler with its real
                // tracked vehicle field; this catches a missing/mistargeted
                // ModifyConstant even if the separate identity helper works.
                try {
                    for (Method method : nativeMap.getClass().getDeclaredMethods()) {
                        if (method.getName().contains("aviation$profileLabel")) {
                            method.setAccessible(true);
                            nativeMapLabel = "F-16".equals(method.invoke(nativeMap, "SU-24M"));
                        } else if (method.getName().contains("aviation$profileHeading")) {
                            method.setAccessible(true);
                            String heading = (String) method.invoke(nativeMap, "Су-24М · бортовой планшет");
                            nativeMapHeading = heading.startsWith("F-16") && !heading.contains("24");
                        }
                    }
                } catch (ReflectiveOperationException failed) {
                    throw new IllegalStateException("Native F-16 map label hook failed", failed);
                }
                break;
            }
        }
        if (nativeMap != null && (nativeMapFrames >= 6 || ticks - nativeMapOpenedAt >= 80)) {
            if (mc.screen == nativeMap) mc.setScreen(previousScreen);
            nativeMap = null;
        }
        if (nativeMapAttempted) {
            checks.put("aviation_actual_native_map_mixin_label_is_f16", nativeMapLabel);
            checks.put("aviation_actual_native_map_mixin_heading_is_f16", nativeMapHeading);
            if (nativeMap == null) checks.put("aviation_actual_native_map_rendered_and_captured", nativeMapCaptured && nativeMapFrames >= 3);
        } else if (ticks > 500) {
            checks.put("aviation_actual_native_map_mixin_label_is_f16", false);
            checks.put("aviation_actual_native_map_mixin_heading_is_f16", false);
            checks.put("aviation_actual_native_map_rendered_and_captured", false);
        }
    }

    private static void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || nativeMap == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != nativeMap) return;
        nativeMapFrames++;
        if (nativeMapFrames == 3) {
            Screenshot.grab(mc.gameDirectory, "combat-f16-native-map.png", mc.getMainRenderTarget(), ignored -> {});
            nativeMapCaptured = true;
            com.mojang.logging.LogUtils.getLogger().info("AVIATION_MAP actual screen rendered for three frames and captured");
        }
    }
}

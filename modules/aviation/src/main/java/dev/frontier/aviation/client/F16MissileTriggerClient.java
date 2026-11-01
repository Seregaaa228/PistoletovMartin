package dev.frontier.aviation.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModKeyMappings;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.event.ClientEventHandler;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import dev.frontier.aviation.aircraft.AircraftCatalog;
import dev.frontier.aviation.weapons.WeaponAimResolver;
import dev.frontier.aviation.weapons.WeaponAimResult;
import net.minecraftforge.registries.ForgeRegistries;
import dev.frontier.aviation.LightAviation;
import dev.frontier.aviation.control.FlightSnapshot;
import dev.frontier.aviation.control.client.FlightClient;
import dev.frontier.aviation.control.net.FlightNetwork;
import dev.frontier.aviation.missile.MissileTrigger;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** One explicit single/salvo gesture, scoped to the current manual pilot lease. */
@Mod.EventBusSubscriber(modid = LightAviation.ID, value = Dist.CLIENT)
public final class F16MissileTriggerClient {
    private static final MissileTrigger TRIGGER = new MissileTrigger();
    private static VehicleEntity selected;
    private static long epoch = -1;
    private static boolean mouseAttackDown;

    private F16MissileTriggerClient() {}

    public static void cancelGesture() {
        TRIGGER.reset();
        mouseAttackDown = false;
    }

    public static boolean handlesNative() {
        VehicleEntity vehicle = FlightClient.controlledEntity();
        if (vehicle == null) return false;
        var id = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.getType());
        return id != null && AircraftCatalog.find(id.toString())
            .flatMap(definition -> definition.weapons().nativeGun(vehicle.getGunName(0)))
            .filter(mount -> mount.kind().guided()).isPresent();
    }

    @SubscribeEvent
    public static void frame(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START)
            sample(mouseAttackDown || ModKeyMappings.FIRE.isDown());
    }

    // Preserve clicks shorter than a rendered frame and use SBW's fire binding.
    // SBW cancels Pre for vehicle fire before vanilla updates key mappings and
    // before Post exists. Keep the physical press for this scoped gesture.
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void mouse(InputEvent.MouseButton.Pre event) {
        if (ModKeyMappings.FIRE.matchesMouse(event.getButton())) {
            if (event.getAction() == GLFW.GLFW_PRESS) { mouseAttackDown = true; sample(true); }
            else if (event.getAction() == GLFW.GLFW_RELEASE) { mouseAttackDown = false; sample(false); }
        }
    }

    @SubscribeEvent
    public static void key(InputEvent.Key event) {
        if (ModKeyMappings.FIRE.matches(event.getKey(), event.getScanCode())) {
            if (event.getAction() == GLFW.GLFW_PRESS) sample(true);
            else if (event.getAction() == GLFW.GLFW_RELEASE) sample(false);
        }
    }

    @SubscribeEvent public static void screen(ScreenEvent.Opening event) { cancelGesture(); }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) { cancelGesture(); selected = null; epoch = -1; }
    }

    private static void sample(boolean pressed) {
        Minecraft mc = Minecraft.getInstance();
        VehicleEntity vehicle = handlesNative() ? FlightClient.controlledEntity() : null;
        FlightSnapshot snapshot = vehicle == null ? null : FlightClient.snapshot();
        long currentEpoch = snapshot == null ? -1 : snapshot.epoch();
        if (selected != vehicle || epoch != currentEpoch) {
            TRIGGER.reset(); selected = vehicle; epoch = currentEpoch;
        }
        boolean eligible = vehicle != null && snapshot != null && mc.player != null
            && mc.player.getUUID().equals(snapshot.controllerId()) && mc.screen == null
            && mc.isWindowActive() && !mc.isPaused() && !FlightClient.usingDroneMonitor();
        MissileTrigger.Action action = TRIGGER.update(pressed, System.nanoTime(), eligible);
        if (eligible && action != MissileTrigger.Action.NONE) {
            var id = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.getType());
            var mount = AircraftCatalog.find(id.toString()).orElseThrow().weapons().nativeGun(vehicle.getGunName(0)).orElseThrow();
            var gun = vehicle.getGunData(mount.nativeGun());
            if (gun == null || mc.level == null) return;
            int count = Math.min(action == MissileTrigger.Action.SALVO ? 2 : 1, mount.maximumBurst());
            var aim = WeaponAimResolver.resolve(mc.level, mc.player, vehicle.getShootPosForHud(mc.player, 1),
                vehicle.getShootDirectionForHud(mc.player, 1), WeaponAimResolver.range(mount, gun));
            UUID tracked = trackedTarget(vehicle, gun, aim.orElse(null));
            // An entity is sent by identity: its interpolated client position
            // lags a fast jet by several blocks, which a point claim cannot survive.
            if (tracked != null) FlightNetwork.fireMissilesAt(snapshot, count, tracked);
            else FlightNetwork.fireMissiles(snapshot, count,
                aim.filter(result -> result.kind() != WeaponAimResult.Kind.SPACE).map(result -> result.point()).orElse(null));
        }
    }

    /** SBW's own seeker lock first, else an entity directly under the reticle; never for block-only seekers. */
    private static UUID trackedTarget(VehicleEntity vehicle, GunData gun, WeaponAimResult aim) {
        if (gun.get(GunProp.SEEK_WEAPON_INFO).getOnlyLockBlock()) return null;
        Entity locked = ClientEventHandler.lockingEntityVehicle;
        if (locked != null && locked.isAlive() && !locked.isRemoved() && locked.level() == vehicle.level()
                && locked.getRootVehicle() != vehicle.getRootVehicle()) return locked.getUUID();
        return aim == null || aim.kind() != WeaponAimResult.Kind.ENTITY ? null : aim.entity().orElse(null);
    }
}

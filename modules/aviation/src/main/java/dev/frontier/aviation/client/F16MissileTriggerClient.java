package dev.frontier.aviation.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.event.ClientEventHandler;
import com.atsuishio.superbwarfare.init.ModKeyMappings;
import dev.frontier.aviation.AircraftProfile;
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
        return vehicle != null && AircraftProfile.of(vehicle) == AircraftProfile.F16
            && "Missile".equals(vehicle.getGunName(0));
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
            FlightNetwork.fireMissiles(snapshot, action == MissileTrigger.Action.SALVO ? 2 : 1,
                ClientEventHandler.lockingPosVehicle);
        }
    }
}

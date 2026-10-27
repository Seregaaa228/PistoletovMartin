package dev.frontier.aviation.client;

import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.LightAviation;
import dev.frontier.aviation.control.FlightState;
import dev.frontier.aviation.control.PilotCommand;
import dev.frontier.aviation.control.client.FlightClient;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Per-world presentation state. No packets or flight controls are modified. */
@Mod.EventBusSubscriber(modid = LightAviation.ID, value = Dist.CLIENT)
public final class F16ControlSurfaceManager {
    private static final Map<Su24mEntity, Entry> STATES = new IdentityHashMap<>();
    private static final F16ControlSurfaces.Axes NEUTRAL = new F16ControlSurfaces.Axes(0, 0, 0);
    private static ClientLevel world;
    private static long tick;

    private static final class Entry {
        final F16ControlSurfaces surfaces = new F16ControlSurfaces();
        long lastSeen;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (world != mc.level) { STATES.clear(); world = mc.level; }
        if (world == null || mc.isPaused()) return;
        long currentTick = ++tick;
        for (var entity : world.entitiesForRendering()) {
            if (!(entity instanceof Su24mEntity plane) || plane.isRemoved()
                    || AircraftProfile.of(plane) != AircraftProfile.F16) continue;
            Entry entry = STATES.computeIfAbsent(plane, ignored -> new Entry());
            entry.lastSeen = currentTick;
            PilotCommand command = FlightClient.visualInput(plane);
            FlightState state = FlightClient.hasVisualPilot(plane) ? FlightClient.visualState(plane) : null;
            F16ControlSurfaces.Axes input = command == null ? null
                : new F16ControlSurfaces.Axes(command.rollAxis(), command.pitchAxis(), command.yawAxis());
            F16ControlSurfaces.Axes rates = state == null ? null
                : new F16ControlSurfaces.Axes(state.rollRate(), state.pitchRate(), state.yawRate());
            entry.surfaces.tick(input, rates, plane.getRoll(), plane.getXRot(), plane.getYRot());
        }
        STATES.values().removeIf(entry -> entry.lastSeen != currentTick);
    }

    public static F16ControlSurfaces.Axes surfaces(Su24mEntity plane, float partialTick) {
        Entry entry = STATES.get(plane);
        return entry == null ? NEUTRAL : entry.surfaces.at(partialTick);
    }
    private F16ControlSurfaceManager() {}
}

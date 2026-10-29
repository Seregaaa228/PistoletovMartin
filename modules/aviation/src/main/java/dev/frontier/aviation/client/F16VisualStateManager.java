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

/** Owns the single per-world F16 visual-state pass and lifecycle. */
@Mod.EventBusSubscriber(modid = LightAviation.ID, value = Dist.CLIENT)
public final class F16VisualStateManager {
    private static final Map<Su24mEntity, Entry> STATES = new IdentityHashMap<>();
    private static final F16ControlSurfaces.Axes NEUTRAL = new F16ControlSurfaces.Axes(0, 0, 0);
    private static ClientLevel world;
    private static long tick;

    private static final class Entry {
        final F16ControlSurfaces surfaces = new F16ControlSurfaces();
        final F16AirBrakeSmoothing airBrake = new F16AirBrakeSmoothing();
        final F16Suspension suspension = new F16Suspension();
        final F16WheelSpin wheels = new F16WheelSpin();
        long lastSeen;
        void update(Su24mEntity plane) {
            PilotCommand command = FlightClient.visualInput(plane);
            FlightState flightState = FlightClient.hasVisualPilot(plane) ? FlightClient.visualState(plane) : null;
            F16ControlSurfaces.Axes input = command == null ? null
                : new F16ControlSurfaces.Axes(command.rollAxis(), command.pitchAxis(), command.yawAxis());
            F16ControlSurfaces.Axes rates = flightState == null ? null
                : new F16ControlSurfaces.Axes(flightState.rollRate(), flightState.pitchRate(), flightState.yawRate());
            surfaces.tick(input, rates, plane.getRoll(), plane.getXRot(), plane.getYRot());
            airBrake.tick(FlightClient.visualAirBrake(plane));

            boolean gearDown = plane.landingGearProgress(1.0f) <= 0.001f;
            suspension.tick(plane.onGround(), gearDown, plane.getDeltaMovement().y);
            double yaw = Math.toRadians(plane.getYRot());
            double travel = -(plane.getX() - plane.xo) * Math.sin(yaw)
                    + (plane.getZ() - plane.zo) * Math.cos(yaw);
            wheels.tick(plane.onGround(), gearDown, travel);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (world != minecraft.level) {
            STATES.clear();
            world = minecraft.level;
        }
        if (world == null || minecraft.isPaused()) return;

        long currentTick = ++tick;
        for (var entity : world.entitiesForRendering()) {
            if (!(entity instanceof Su24mEntity plane) || plane.isRemoved()
                    || AircraftProfile.of(plane) != AircraftProfile.F16) continue;
            Entry entry = STATES.computeIfAbsent(plane, ignored -> new Entry());
            entry.lastSeen = currentTick;
            entry.update(plane);
        }
        STATES.values().removeIf(entry -> entry.lastSeen != currentTick);
    }

    static F16ControlSurfaces.Axes surfaces(Su24mEntity plane, float partialTick) {
        Entry entry = STATES.get(plane);
        return entry == null ? NEUTRAL : entry.surfaces.at(partialTick);
    }

    static float compression(Su24mEntity plane, float partialTick) {
        Entry entry = STATES.get(plane);
        return entry == null ? 0 : entry.suspension.compression(partialTick);
    }

    static float wheelAngle(Su24mEntity plane, float partialTick, boolean nose) {
        Entry entry = STATES.get(plane);
        return entry == null ? 0 : entry.wheels.angle(partialTick, nose);
    }

    static float airBrake(Su24mEntity plane, float partialTick) {
        Entry entry = STATES.get(plane);
        return entry == null ? 0 : entry.airBrake.at(partialTick);
    }

    /** One STATES lookup for every F16 value the renderer needs for a given frame, instead of one per value. */
    public record RenderState(F16ControlSurfaces.Axes surfaces, float airBrake, float compression,
                               float noseWheelAngle, float mainWheelAngle) {}

    public static RenderState renderState(Su24mEntity plane, float partialTick) {
        Entry entry = STATES.get(plane);
        if (entry == null) return new RenderState(NEUTRAL, 0, 0, 0, 0);
        return new RenderState(entry.surfaces.at(partialTick), entry.airBrake.at(partialTick),
            entry.suspension.compression(partialTick),
            entry.wheels.angle(partialTick, true), entry.wheels.angle(partialTick, false));
    }

    private F16VisualStateManager() {}
}

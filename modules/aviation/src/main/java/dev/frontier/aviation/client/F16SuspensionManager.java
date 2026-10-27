package dev.frontier.aviation.client;

import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.LightAviation;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Visual-only state, owned by the current client world and advanced once per tick. */
@Mod.EventBusSubscriber(modid = LightAviation.ID, value = Dist.CLIENT)
public final class F16SuspensionManager {
    private static final Map<Su24mEntity, Entry> STATES = new IdentityHashMap<>();
    private static ClientLevel world;
    private static long tick;

    private static final class Entry {
        final F16Suspension state = new F16Suspension();
        final F16WheelSpin wheels = new F16WheelSpin();
        long lastSeen;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (world != minecraft.level) {
            STATES.clear();
            world = minecraft.level;
        }
        if (world == null) return;
        if (minecraft.isPaused()) return;
        long currentTick = ++tick;
        for (var entity : world.entitiesForRendering()) {
            if (!(entity instanceof Su24mEntity plane) || plane.isRemoved()
                    || AircraftProfile.of(plane) != AircraftProfile.F16) continue;
            Entry entry = STATES.computeIfAbsent(plane, ignored -> new Entry());
            entry.lastSeen = currentTick;
            entry.state.tick(plane.onGround(), plane.landingGearProgress(1.0f) <= 0.001f,
                    plane.getDeltaMovement().y);
            double yaw = Math.toRadians(plane.getYRot());
            double travel = -(plane.getX() - plane.xo) * Math.sin(yaw)
                    + (plane.getZ() - plane.zo) * Math.cos(yaw);
            entry.wheels.tick(plane.onGround(), plane.landingGearProgress(1.0f) <= 0.001f, travel);
        }
        STATES.values().removeIf(entry -> entry.lastSeen != currentTick);
    }

    public static float compression(Su24mEntity plane, float partialTick) {
        Entry entry = STATES.get(plane);
        return entry == null ? 0 : entry.state.compression(partialTick);
    }

    public static float wheelAngle(Su24mEntity plane, float partialTick, boolean nose) {
        Entry entry = STATES.get(plane);
        return entry == null ? 0 : entry.wheels.angle(partialTick, nose);
    }

    private F16SuspensionManager() {}
}

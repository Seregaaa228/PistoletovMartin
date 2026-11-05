package dev.frontier.aviation.client;

import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.LightAviation;
import dev.frontier.aviation.aircraft.AircraftCatalog;
import dev.frontier.aviation.handling.Buffet;
import dev.frontier.aviation.handling.FlightDynamics;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Camera-only buffet for the pilot of one of our jets: near the stall, at a high angle of
 * attack, low over the ground at speed and on the take-off roll. The aircraft, its aim and
 * the networked flight state are untouched; only the view shakes by a fraction of a degree.
 */
@Mod.EventBusSubscriber(modid = LightAviation.ID, value = Dist.CLIENT)
public final class FlightBuffetCamera {
    private FlightBuffetCamera() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.isPaused()) return;
        Entity plane = mc.player.getVehicle();
        if (plane == null || AircraftProfile.of(plane) == null || plane.getFirstPassenger() != mc.player) return;
        var id = ForgeRegistries.ENTITY_TYPES.getKey(plane.getType());
        if (id == null) return;
        FlightDynamics dynamics = AircraftCatalog.dynamics(id.toString());
        var performance = AircraftCatalog.performance(id.toString());
        if (!dynamics.active() || performance.isEmpty() || dynamics.buffet() <= 0) return;
        Vec3 velocity = plane.getDeltaMovement();
        float speed = (float) velocity.length();
        float aoa = 0;
        if (speed > .2f) {
            double cos = velocity.normalize().dot(plane.getViewVector(1));
            aoa = (float) Math.toDegrees(Math.acos(Mth.clamp(cos, -1, 1)));
        }
        int x = Mth.floor(plane.getX()), z = Mth.floor(plane.getZ());
        float clearance = (float) Math.max(0, plane.getY() - mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
        float intensity = Buffet.intensity(speed, plane.isNoGravity() ? 0 : performance.get().stallSpeed(),
            aoa, clearance, plane.onGround());
        if (intensity <= 0) return;
        var shake = Buffet.shake(plane.tickCount + event.getPartialTick(), plane.getId(), intensity, dynamics.buffet());
        event.setPitch(event.getPitch() + shake.pitch());
        event.setRoll(event.getRoll() + shake.roll());
    }
}

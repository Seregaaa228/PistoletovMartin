package dev.frontier.aviation;

import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3d;

@Mod.EventBusSubscriber(modid = LightAviation.ID, value = Dist.CLIENT)
public final class AircraftParticles {
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        int budget = 16;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Su24mEntity plane) || ((plane.tickCount + plane.getId()) & 3) != 0
                    || plane.distanceToSqr(camera) > 96 * 96 || !AircraftExhaust.running(plane)) continue;
            AircraftProfile profile = AircraftProfile.of(plane);
            if (profile == null) continue;
            var transform = plane.getTransformFromString("Vehicle");
            for (int engine = 0; engine < profile.nozzles().length; engine++) {
                if ((engine == 0 ? plane.getMainEngineHealth() : plane.getSubEngineHealth()) <= 0) continue;
                double[] local = profile.nozzles()[engine];
                Vector3d pos = transform.transformPosition(new Vector3d(local[0], local[1], local[2]));
                Vector3d drift = transform.transformDirection(new Vector3d(0, 0, -0.14));
                var velocity = plane.getDeltaMovement();
                mc.level.addParticle(ParticleTypes.SMOKE, pos.x, pos.y, pos.z,
                        velocity.x + drift.x, velocity.y + drift.y, velocity.z + drift.z);
                if (--budget == 0) return;
                // A visible vanilla flame remains on unsupported or external shaders.
                if (!AircraftExhaust.nativeFireAvailable()) {
                    mc.level.addParticle(ParticleTypes.FLAME, pos.x, pos.y, pos.z,
                            velocity.x + drift.x, velocity.y + drift.y, velocity.z + drift.z);
                    if (--budget == 0) return;
                }
            }
        }
    }
}

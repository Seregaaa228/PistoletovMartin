package dev.frontier.aviation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;

/** Local visuals only: no packets, damage, world lights, or effect entities. */
public final class AircraftExhaust {
    public static final int FIRE_DISTANCE = 128;

    private AircraftExhaust() {}

    public static boolean running(Su24mEntity plane) {
        return plane.isAlive() && plane.getEngineStart() && plane.getEngineStartOver()
                && plane.getEnergy() > 0 && plane.getHealth() > 0;
    }

    public static boolean nativeFireAvailable() {
        return NativeAircraftEffects.available();
    }

    public static void render(Su24mEntity plane, PoseStack pose, float partial, MultiBufferSource buffers) {
        if (!running(plane) || !nativeFireAvailable()) return;
        AircraftProfile profile = AircraftProfile.of(plane);
        if (profile == null) return;
        double distance = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()
                .distanceToSqr(plane.position());
        if (distance > FIRE_DISTANCE * FIRE_DISTANCE) return;
        float power = Mth.clamp(plane.getPower(), 0, 1);
        float scale = profile.definition().model().exhaustScale();
        var nozzles = profile.nozzles();
        for (int engine = 0; engine < nozzles.size(); engine++) {
            if ((engine == 0 ? plane.getMainEngineHealth() : plane.getSubEngineHealth()) <= 0) continue;
            var local = nozzles.get(engine);
            pose.pushPose();
            // SBW renderer space reverses vehicle X/Z, as with native stores.
            pose.translate(-local.x(), local.y(), -local.z());
            pose.mulPose(Axis.YP.rotationDegrees(-90));
            pose.scale(scale, scale, scale);
            NativeAircraftEffects.render(pose, buffers, plane.tickCount + partial, power, distance);
            pose.popPose();
        }
    }
}

package dev.frontier.flamingofix.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.frontier.flamingofix.mixin.NativePatriotFlight;
import dev.frontier.flamingofix.mixin.NativePatriotContact;
import dev.frontier.flamingofix.mixin.NativePatriotView;
import dev.sbw.deepstrike.client.ModelRenderer;
import dev.sbw.deepstrike.model.InterceptorGeometry;
import dev.sbw.deepstrike.model.ModelGeometry;
import dev.sbw.deepstrike.model.FlightOrientation;
import dev.sbw.deepstrike.model.ManpadGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import java.lang.ref.WeakReference;

/** Ordinary PAC/Tamir bodies without a full-screen framebuffer for each missile. */
public final class LightweightPatriotEffects {
    private static WeakReference<ClientLevel> burstLevel = new WeakReference<>(null);
    private static long burstTick = Long.MIN_VALUE;
    private static int bursts;
    private LightweightPatriotEffects() { }

    public static boolean render(int id, NativePatriotFlight flight, PoseStack pose,
            MultiBufferSource.BufferSource buffers, Vec3 camera) {
        if (NativePatriotView.flamingo$isTracked(flight.flamingo$uuid(), id)) return false;
        Vec3 relative = flight.flamingo$position().subtract(camera);
        double far = Math.max(1, Minecraft.getInstance().gameRenderer.getDepthFar() * 0.7);
        float scale = (float)Math.min(1, far / Math.max(1, relative.length()));
        int warhead = flight.flamingo$warhead();
        String model = InterceptorGeometry.model(warhead);
        var vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(
                new ResourceLocation("deepstrike", "textures/entity/" + model + ".png")));
        pose.pushPose();
        try {
            pose.translate(relative.x * scale, relative.y * scale, relative.z * scale);
            pose.scale(scale, scale, scale);
            pose.mulPoseMatrix(ModelGeometry.flight(flight.flamingo$yaw(), flight.flamingo$pitch()));
            float radial = InterceptorGeometry.radialScale(warhead);
            pose.scale(InterceptorGeometry.axialScale(warhead), radial, radial);
            ModelRenderer.render(model, model, pose, ignored -> vertices,
                    15728880, OverlayTexture.NO_OVERLAY, 0, 0, bone -> true);
        } finally {
            pose.popPose();
        }
        return true;
    }

    public static boolean renderNative(int id, NativePatriotFlight flight, PoseStack pose,
            MultiBufferSource.BufferSource buffers, Vec3 camera) {
        if (NativePatriotView.flamingo$isTracked(flight.flamingo$uuid(), id)) return false;
        var body = flight.flamingo$nativeBody();
        // The original path creates its private preview once; reuse it after that warmup.
        if (body == null) return false;
        Vec3 direction = FlightOrientation.direction(flight.flamingo$yaw(), flight.flamingo$pitch());
        Vec3 center = flight.flamingo$position().subtract(ManpadGeometry.nozzle(body, Vec3.ZERO, direction));
        Vec3 relative = center.subtract(camera);
        double far = Math.max(1, Minecraft.getInstance().gameRenderer.getDepthFar() * 0.7);
        float scale = (float)Math.min(1, far / Math.max(1, relative.length()));
        Vec3 position = body.position(), velocity = body.getDeltaMovement();
        double oldX = body.xo, oldY = body.yo, oldZ = body.zo;
        float yaw = body.getYRot(), pitch = body.getXRot(), oldYaw = body.yRotO, oldPitch = body.xRotO;
        boolean rendering = NativePatriotContact.flamingo$isRendering();
        pose.pushPose();
        try {
            body.setPos(center);
            body.xo = center.x; body.yo = center.y; body.zo = center.z;
            body.setDeltaMovement(direction);
            body.setYRot(flight.flamingo$yaw()); body.yRotO = flight.flamingo$yaw();
            body.setXRot(flight.flamingo$pitch()); body.xRotO = flight.flamingo$pitch();
            NativePatriotContact.flamingo$setRendering(true);
            pose.translate(relative.x * scale, relative.y * scale, relative.z * scale);
            pose.scale(scale, scale, scale);
            var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(body);
            // Native MANPAD models may mix triangle meshes and quads. Preserve their
            // material and vertex modes while avoiding only the framebuffer pass.
            renderer.render(body, flight.flamingo$yaw(), 0, pose, buffers, 15728880);
        } finally {
            pose.popPose();
            NativePatriotContact.flamingo$setRendering(rendering);
            body.setPos(position); body.setDeltaMovement(velocity);
            body.xo = oldX; body.yo = oldY; body.zo = oldZ;
            body.setYRot(yaw); body.setXRot(pitch); body.yRotO = oldYaw; body.xRotO = oldPitch;
        }
        return true;
    }

    public static void burst(Vec3 position) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()
                || position.distanceToSqr(mc.gameRenderer.getMainCamera().getPosition()) > 128 * 128) return;
        if (burstLevel.get() != mc.level || burstTick != mc.level.getGameTime()) {
            burstLevel = new WeakReference<>(mc.level); burstTick = mc.level.getGameTime(); bursts = 0;
        }
        if (bursts++ < 4) mc.level.addParticle(ParticleTypes.EXPLOSION,
                position.x, position.y, position.z, 0, 0, 0);
    }
}

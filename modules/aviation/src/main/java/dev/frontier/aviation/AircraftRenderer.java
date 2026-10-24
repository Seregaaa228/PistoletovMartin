package dev.frontier.aviation;

import com.atsuishio.superbwarfare.client.model.entity.VehicleModelInstance;
import com.atsuishio.superbwarfare.client.renderer.entity.GeoVehicleRenderer;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.sbw.deepstrike.client.ModelRenderer;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.model.ModelAssets;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.Minecraft;

/** Uses SBW's cached baked models and its distance-based LOD selector. */
public final class AircraftRenderer extends GeoVehicleRenderer<Su24mEntity> {
    private static final String[] GEAR = {"gear_nose", "gear_main_l", "gear_main_r"};

    public AircraftRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override
    public void transformCustomModelPart(Su24mEntity plane, VehicleModelInstance instance,
                                         PoseStack pose, float age, float partial) {
        super.transformCustomModelPart(plane, instance, pose, age, partial);
        float progress = plane.landingGearProgress(partial);
        float fold = progress * progress * (3 - 2 * progress);
        for (String name : GEAR) {
            BoneState bone = instance.getBone(name);
            if (bone == null) continue;
            bone.visible = progress < 0.999F;
            bone.rotation.rotateX((float)Math.toRadians(name.equals("gear_nose") ? -88 : 82) * fold);
        }
        // Opaque low-poly canopy remains visible externally, while the local
        // first-person pilot gets a clear runway/HUD rather than its inner faces.
        BoneState canopy = instance.getBone("canopy");
        if (canopy != null) {
            Minecraft mc = Minecraft.getInstance();
            canopy.visible = !(mc.player != null && mc.player.getVehicle() == plane
                    && mc.options.getCameraType().isFirstPerson());
        }
    }

    @Override
    public void renderCustomPart(Su24mEntity plane, VehicleModelInstance instance, PoseStack pose,
                                  float yaw, float partial, MultiBufferSource buffers, int light) {
        super.renderCustomPart(plane, instance, pose, yaw, partial, buffers, light);
        AircraftExhaust.render(plane, pose, partial, buffers);
        // Small payload details follow the same 96-block LOD boundary as the airframe.
        if (getCurrentModelEntry(pose, plane).isLOD()) return;
        for (int slot = 0; slot < 2; slot++) {
            if (!plane.loaded(slot)) continue;
            pose.pushPose();
            pose.translate(slot == 0 ? -2.1 : 2.1, 2.2, -0.2);
            pose.mulPose(Axis.YP.rotationDegrees(-90));
            ModelRenderer.render(ModelAssets.missile(MissileKind.STORM_SHADOW),
                    ModelAssets.texture(MissileKind.STORM_SHADOW, false), pose, buffers,
                    light, OverlayTexture.NO_OVERLAY, 0, 0,
                    bone -> !bone.startsWith("wing_") && !bone.startsWith("fin_"));
            pose.popPose();
        }
    }
}

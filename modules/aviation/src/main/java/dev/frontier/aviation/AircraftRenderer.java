package dev.frontier.aviation;

import com.atsuishio.superbwarfare.client.model.entity.VehicleModelInstance;
import com.atsuishio.superbwarfare.client.renderer.entity.GeoVehicleRenderer;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.frontier.aviation.client.F16ControlSurfaces;
import dev.frontier.aviation.client.F16PayloadVisual;
import dev.frontier.aviation.client.F16VisualStateManager;
import com.atsuishio.superbwarfare.resource.model.ProjectileModelReloadListener;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes;
import dev.sbw.deepstrike.client.ModelRenderer;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.model.ModelAssets;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** Uses SBW's cached baked models and its distance-based LOD selector. */
public final class AircraftRenderer extends GeoVehicleRenderer<Su24mEntity> {
    private static final String[] GEAR = {"gear_nose", "gear_main_l", "gear_main_r"};
    private static final String[] GEAR_CHILD_SUFFIXES = {"_suspension", "_wheel"};
    private static final String[] PAYLOAD_MODELS = {"", "", "agm_65", "ru_9m100_missile", "ru_9m336_missile", "wire_guide_missile"};
    // Projectile models use their exhaust as origin, not their centre. Move
    // each model back so its centre sits beneath the physical pylon.
    // Measured from the pinned models after baking, with their flare hidden.
    private static final double[] PAYLOAD_CENTER_OFFSET = {0, 0, 1.86134, 1.25737, 0.85467, 1.03125};
    private static final BakedBedrockModel[] PAYLOAD_BAKED = new BakedBedrockModel[PAYLOAD_MODELS.length];
    private static final BakedModelInstance[] PAYLOAD_INSTANCE = new BakedModelInstance[PAYLOAD_MODELS.length];
    // Lazily built once per kind the first time it is rendered; stations reuse the same two models per kind.
    private static final ResourceLocation[] PAYLOAD_KEYS = new ResourceLocation[PAYLOAD_MODELS.length];
    private static final ResourceLocation[] PAYLOAD_TEXTURES = new ResourceLocation[PAYLOAD_MODELS.length];

    public AircraftRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override
    public void transformCustomModelPart(Su24mEntity plane, VehicleModelInstance instance,
                                         PoseStack pose, float age, float partial) {
        super.transformCustomModelPart(plane, instance, pose, age, partial);
        AircraftProfile profile = AircraftProfile.of(plane);
        float progress = plane.landingGearProgress(partial);
        float fold = progress * progress * (3 - 2 * progress);
        for (String name : GEAR) {
            BoneState bone = instance.getBone(name);
            if (bone == null) continue;
            boolean visible = progress < 0.999F;
            bone.visible = visible;
            // The Bedrock renderer does not inherit parent visibility: wheel
            // geometry must be hidden explicitly when the gear is retracted.
            for (String suffix : GEAR_CHILD_SUFFIXES) {
                BoneState child = instance.getBone(name + suffix);
                if (child != null) child.visible = visible;
            }
            bone.rotation.rotateX((float)Math.toRadians(name.equals("gear_nose") ? -88 : 82) * fold);
        }
        if (profile == AircraftProfile.F16) {
            // One STATES lookup for all five F16 values instead of six separate ones.
            F16VisualStateManager.RenderState state = F16VisualStateManager.renderState(plane, partial);
            F16ControlSurfaces.Axes surfaces = state.surfaces();
            rotateSurface(instance, "f16_aileron_left", 25 * surfaces.roll(), false);
            rotateSurface(instance, "f16_aileron_right", -25 * surfaces.roll(), false);
            rotateSurface(instance, "f16_elevator_left", 25 * surfaces.pitch(), false);
            rotateSurface(instance, "f16_elevator_right", 25 * surfaces.pitch(), false);
            rotateSurface(instance, "f16_rudder", 25 * surfaces.yaw(), true);
            rotateSurface(instance, "f16_airbrake_left", -55 * state.airBrake(), true);
            rotateSurface(instance, "f16_airbrake_right", 55 * state.airBrake(), true);
            for (String name : GEAR) {
                BoneState suspension = instance.getBone(name + "_suspension");
                if (suspension != null) suspension.y = suspension.definition().bindY() + state.compression();
                BoneState wheel = instance.getBone(name + "_wheel");
                if (wheel != null) wheel.rotation.rotateX(
                    name.equals("gear_nose") ? state.noseWheelAngle() : state.mainWheelAngle());
            }
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

    private static void rotateSurface(VehicleModelInstance instance, String name, float degrees, boolean vertical) {
        BoneState bone = instance.getBone(name);
        if (bone == null) return;
        float radians = (float) Math.toRadians(degrees);
        if (vertical) bone.rotation.rotateY(radians);
        else bone.rotation.rotateX(radians);
    }

    @Override
    public void renderCustomPart(Su24mEntity plane, VehicleModelInstance instance, PoseStack pose,
                                  float yaw, float partial, MultiBufferSource buffers, int light) {
        // F-16 payloads are rendered from the server's combined station state;
        // the native hook would otherwise draw magazine rounds a second time.
        AircraftProfile profile = AircraftProfile.of(plane);
        boolean f16 = profile == AircraftProfile.F16;
        if (!f16) super.renderCustomPart(plane, instance, pose, yaw, partial, buffers, light);
        AircraftExhaust.render(plane, pose, partial, buffers);
        // Small payload details follow the same 96-block LOD boundary as the airframe.
        if (getCurrentModelEntry(pose, plane).isLOD()) return;
        int bits = f16 ? F16PayloadVisual.bits(plane.getUUID()) : 0;
        if (f16) {
            double[] x = {-2.1, 2.1, -3.4, 3.4, -5.65625, 5.65625};
            for (int station = 0; station < x.length; station++) {
                int kind = (bits >>> (station * 3)) & 7;
                if (kind < 2 || kind >= PAYLOAD_MODELS.length) continue;
                var key = PAYLOAD_KEYS[kind];
                if (key == null) {
                    key = new ResourceLocation("superbwarfare", "models/bedrock/projectile/" + PAYLOAD_MODELS[kind] + ".geo.json");
                    PAYLOAD_KEYS[kind] = key;
                }
                BakedBedrockModel baked = ProjectileModelReloadListener.INSTANCE.getModel(key);
                if (baked == null) continue;
                if (PAYLOAD_BAKED[kind] != baked) {
                    PAYLOAD_BAKED[kind] = baked;
                    PAYLOAD_INSTANCE[kind] = baked.createInstance();
                }
                BakedModelInstance payload = PAYLOAD_INSTANCE[kind];
                BoneState flare = payload.getBone("flare");
                if (flare != null) flare.visible = false;
                var texture = PAYLOAD_TEXTURES[kind];
                if (texture == null) {
                    texture = new ResourceLocation("superbwarfare", "textures/bedrock/projectile/" + PAYLOAD_MODELS[kind] + ".png");
                    PAYLOAD_TEXTURES[kind] = texture;
                }
                pose.pushPose();
                pose.translate(x[station], station >= 4 ? 2.6 : 2.2,
                    (station >= 4 ? 1.25 : -0.2) + PAYLOAD_CENTER_OFFSET[kind]);
                payload.renderToBuffer(pose, buffers, RenderType.entityCutout(texture),
                    BedrockModelRenderTypes.polyMeshCutout(texture), light, OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }
        }
        for (int slot = 0; slot < 2; slot++) {
            boolean displayed = f16
                ? ((bits >>> (slot * 3)) & 7) == 1
                : plane.loaded(slot);
            if (!displayed) continue;
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

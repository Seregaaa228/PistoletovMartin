package dev.frontier.aviation;

import com.atsuishio.superbwarfare.client.model.entity.VehicleModelInstance;
import com.atsuishio.superbwarfare.client.renderer.entity.GeoVehicleRenderer;
import com.atsuishio.superbwarfare.data.vehicle_skin.SkinInfo;
import com.atsuishio.superbwarfare.data.vehicle_skin.VehicleSkin;
import com.atsuishio.superbwarfare.entity.vehicle.VehicleModelEntry;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.frontier.aviation.client.F16ControlSurfaces;
import dev.frontier.aviation.client.F16PayloadVisual;
import dev.frontier.aviation.client.F16VisualStateManager;
import dev.frontier.aviation.ordnance.StoreStations;
import dev.frontier.aviation.weapons.GlideBombRackView;
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
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Uses SBW's cached baked models and its distance-based LOD selector. */
public final class AircraftRenderer extends GeoVehicleRenderer<Su24mEntity> {
    private static final String[] GEAR = {"gear_nose", "gear_main_l", "gear_main_r"};
    private static final String[] GEAR_CHILD_SUFFIXES = {"_suspension", "_wheel"};
    /** Nose-wheel steering angle at full pedal (degrees). */
    private static final float NOSE_STEER = 30;
    // "_l" doors hinge on their +X edge, "_r" doors on their -X edge. SBW bakes bones in an
    // X-mirrored frame, so rotations about Y and Z run opposite to the geo file's axes.
    private static final String[] GEAR_DOORS = {"gear_door_nose_l", "gear_door_nose_r", "gear_door_left_l",
        "gear_door_left_r", "gear_door_right_l", "gear_door_right_r"};
    private static final String[] PAYLOAD_MODELS = {"", "", "agm_65", "ru_9m100_missile", "ru_9m336_missile", "wire_guide_missile"};
    private static final BakedBedrockModel[] PAYLOAD_BAKED = new BakedBedrockModel[PAYLOAD_MODELS.length];
    private static final BakedModelInstance[] PAYLOAD_INSTANCE = new BakedModelInstance[PAYLOAD_MODELS.length];
    // Lazily built once per kind the first time it is rendered; stations reuse the same two models per kind.
    private static final ResourceLocation[] PAYLOAD_KEYS = new ResourceLocation[PAYLOAD_MODELS.length];
    private static final ResourceLocation[] PAYLOAD_TEXTURES = new ResourceLocation[PAYLOAD_MODELS.length];
    private static final ResourceLocation KAB_MODEL = new ResourceLocation(LightAviation.ID, "models/bedrock/projectile/kab_500.geo.json");
    private static final ResourceLocation KAB_TEXTURE = new ResourceLocation(LightAviation.ID, "textures/bedrock/projectile/kab_500.png");
    private static BakedBedrockModel kabBaked;
    private static BakedModelInstance kabInstance;

    /** Copies of SBW's LOD entries carrying a skin texture: source entry, then skin texture. */
    private final Map<VehicleModelEntry, Map<ResourceLocation, VehicleModelEntry>> skinnedLod = new WeakHashMap<>();

    public AircraftRenderer(EntityRendererProvider.Context context) { super(context); }

    /**
     * SBW applies the sprayed vehicle skin to the near model only, so beyond the 96-block LOD
     * boundary a jet would fall back to its default paint. The near and LOD models share one atlas
     * layout (art/realistic packs both into each texture), so the skin texture fits the LOD model:
     * hand SBW a copy of the LOD entry that carries it. Model instance and distance stay shared.
     */
    @Override
    public VehicleModelEntry getCurrentModelEntry(PoseStack pose, Su24mEntity plane) {
        VehicleModelEntry entry = super.getCurrentModelEntry(pose, plane);
        if (entry == null || !entry.isLOD()) return entry;
        SkinInfo skin = VehicleSkin.getSkin(plane);
        String name = skin == null ? null : skin.getTexture();
        ResourceLocation texture = name == null ? null : ResourceLocation.tryParse(name);
        if (texture == null || texture.equals(entry.getTexture())) return entry;
        return skinnedLod.computeIfAbsent(entry, key -> new HashMap<>(4)).computeIfAbsent(texture,
            key -> entry.copy(entry.getInstance(), key, entry.getEmissiveTexture(), entry.getLodDistance()));
    }

    @Override
    public void transformCustomModelPart(Su24mEntity plane, VehicleModelInstance instance,
                                         PoseStack pose, float age, float partial) {
        super.transformCustomModelPart(plane, instance, pose, age, partial);
        AircraftProfile profile = AircraftProfile.of(plane);
        float progress = plane.landingGearProgress(partial);
        float fold = progress * progress * (3 - 2 * progress);
        // The Su-25 nose leg folds forward; the F-16 and J-10 nose legs fold aft. Mains fold forward.
        float noseFold = profile == AircraftProfile.SU25 ? 88 : -88;
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
            bone.rotation.rotateX((float)Math.toRadians(name.equals("gear_nose") ? noseFold : 82) * fold);
        }
        // Nose-wheel steering: the whole nose leg turns about its vertical strut on the ground.
        // Opposite sense to the rudder, whose trailing edge sits behind its hinge.
        BoneState noseLeg = instance.getBone("gear_nose");
        F16VisualStateManager.RenderState state = F16VisualStateManager.renderState(plane, partial);
        if (noseLeg != null && progress < 0.001F && plane.onGround())
            noseLeg.rotation.rotateY((float) Math.toRadians(-NOSE_STEER * state.surfaces().yaw()));
        // Bay doors stand open while the gear is out and close flush once it is stowed.
        for (String name : GEAR_DOORS) {
            BoneState door = instance.getBone(name);
            if (door != null) door.rotation.rotateZ((float) Math.toRadians(name.endsWith("_l") ? -85 : 85) * (1 - fold));
        }
        // One STATES lookup for all values of this frame (taken above).
        F16ControlSurfaces.Axes axes = state.surfaces();
        rotateSurface(instance, "aileron_left", 25 * axes.roll(), false);
        rotateSurface(instance, "aileron_right", -25 * axes.roll(), false);
        rotateSurface(instance, "elevator_left", 25 * axes.pitch(), false);
        rotateSurface(instance, "elevator_right", 25 * axes.pitch(), false);
        rotateSurface(instance, "elevon_left", 22 * axes.pitch() + 18 * axes.roll(), false);
        rotateSurface(instance, "elevon_right", 22 * axes.pitch() - 18 * axes.roll(), false);
        rotateSurface(instance, "canard_left", -20 * axes.pitch(), false);
        rotateSurface(instance, "canard_right", -20 * axes.pitch(), false);
        rotateRudder(instance, profile, 25 * axes.yaw());
        // Flaps follow the landing gear: down for take-off and landing, up in cruise.
        rotateSurface(instance, "flap_left", 30 * (1 - fold), false);
        rotateSurface(instance, "flap_right", 30 * (1 - fold), false);
        rotateSurface(instance, "airbrake_left_upper", -50 * state.airBrake(), false);
        rotateSurface(instance, "airbrake_left_lower", 50 * state.airBrake(), false);
        rotateSurface(instance, "airbrake_right_upper", -50 * state.airBrake(), false);
        rotateSurface(instance, "airbrake_right_lower", 50 * state.airBrake(), false);
        rotateSurface(instance, "airbrake_left", -50 * state.airBrake(), true);
        rotateSurface(instance, "airbrake_right", 50 * state.airBrake(), true);
        if (profile == AircraftProfile.F16) {
            rotateSurface(instance, "f16_aileron_left", 25 * axes.roll(), false);
            rotateSurface(instance, "f16_aileron_right", -25 * axes.roll(), false);
            rotateSurface(instance, "f16_elevator_left", 25 * axes.pitch(), false);
            rotateSurface(instance, "f16_elevator_right", 25 * axes.pitch(), false);
            rotateSurface(instance, "f16_airbrake_left", -55 * state.airBrake(), true);
            rotateSurface(instance, "f16_airbrake_right", 55 * state.airBrake(), true);
        }
        // Shock absorbers and wheel spin: all three jets carry *_suspension / *_wheel under each leg.
        for (String name : GEAR) {
            BoneState suspension = instance.getBone(name + "_suspension");
            if (suspension != null) suspension.y = suspension.definition().bindY() + state.compression();
            BoneState wheel = instance.getBone(name + "_wheel");
            if (wheel != null) wheel.rotation.rotateX(
                name.equals("gear_nose") ? state.noseWheelAngle() : state.mainWheelAngle());
        }
        // Opaque low-poly canopy remains visible externally, while the local
        // first-person pilot gets a clear runway/HUD rather than its inner faces.
        Minecraft mc = Minecraft.getInstance();
        boolean cockpitView = mc.player != null && mc.player.getVehicle() == plane
                && mc.options.getCameraType().isFirstPerson();
        BoneState windscreen = instance.getBone("windscreen");
        if (windscreen != null) windscreen.visible = !cockpitView;
        // Su-25: the pilot's instrument panel, drawn closer to the shared eye point; the windscreen
        // bone carries the outside view's copy in place under the glass.
        BoneState cockpitPanel = instance.getBone("cockpit_panel");
        if (cockpitPanel != null) cockpitPanel.visible = cockpitView;
        BoneState canopy = instance.getBone("canopy");
        if (canopy != null) {
            canopy.visible = !cockpitView;
            // Parked with an empty cockpit: the Su-25 canopy swings open to the right,
            // the F-16 and J-10 canopies lift on their rear hinge.
            float open = state.canopy();
            if (open > 0) {
                if (profile == AircraftProfile.SU25) canopy.rotation.rotateZ((float) Math.toRadians(-100 * open));
                else canopy.rotation.rotateX((float) Math.toRadians(35 * open));
            }
        }
    }

    /** Aft lean of each rudder hinge from vertical (deg): it follows the fin's swept trailing edge. */
    private static float rudderHingeSweep(AircraftProfile profile) {
        if (profile == AircraftProfile.F16) return 23.74f;
        if (profile == AircraftProfile.J10) return 18.66f;
        return 0;   // the Su-25 rudder hangs on a vertical hinge
    }

    /**
     * Turns the whole rudder about its own hinge line. A plain Y rotation would swing a
     * swept rudder diagonally, with the top moving far more than the bottom.
     */
    public static void rotateRudder(VehicleModelInstance instance, AircraftProfile profile, float degrees) {
        BoneState bone = instance.getBone(profile == AircraftProfile.F16 ? "f16_rudder" : "rudder");
        if (bone == null) return;
        double sweep = Math.toRadians(rudderHingeSweep(profile));
        bone.rotation.rotateAxis((float) Math.toRadians(degrees), 0, (float) Math.cos(sweep), (float) Math.sin(sweep));
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
        // Owned jets draw their stores from the server's combined station state
        // (StoreStations); the native hook would otherwise draw magazine rounds a second time.
        AircraftProfile profile = AircraftProfile.of(plane);
        var layout = profile == null ? null : StoreStations.forType(profile.definition().typeId()).orElse(null);
        if (layout == null) super.renderCustomPart(plane, instance, pose, yaw, partial, buffers, light);
        AircraftExhaust.render(plane, pose, partial, buffers);
        // Small payload details follow the same 96-block LOD boundary as the airframe.
        if (layout == null || getCurrentModelEntry(pose, plane).isLOD()) return;
        int bits = F16PayloadVisual.bits(plane.getUUID());
        var racks = (GlideBombRackView) (Object) plane;
        for (int index = 0; index < layout.size(); index++) {
            int kind = (bits >>> (index * 3)) & 7;
            var station = layout.stations().get(index);
            if (kind == StoreStations.RACK) {
                int rack = station.rack();
                int variant = rack < 0 || (racks.aviation$kabMask() >> rack & 1) == 0 ? -1 : racks.aviation$kabVariant(rack);
                if (variant >= 0) renderKab(pose, buffers, light, station, variant);
                else renderStormShadow(pose, buffers, light, station);
            } else if (kind >= StoreStations.AGM_65 && kind < PAYLOAD_MODELS.length) {
                renderPayload(pose, buffers, light, station, kind);
            }
        }
    }

    /** An SBW projectile model under a station; it uses its exhaust as origin, not its centre. */
    private static void renderPayload(PoseStack pose, MultiBufferSource buffers, int light,
                                      StoreStations.Station station, int kind) {
        var key = PAYLOAD_KEYS[kind];
        if (key == null) {
            key = new ResourceLocation("superbwarfare", "models/bedrock/projectile/" + PAYLOAD_MODELS[kind] + ".geo.json");
            PAYLOAD_KEYS[kind] = key;
        }
        BakedBedrockModel baked = ProjectileModelReloadListener.INSTANCE.getModel(key);
        if (baked == null) return;
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
        double[] at = StoreStations.hang(station, StoreStations.model(kind, -1));
        pose.pushPose();
        pose.translate(at[0], at[1], at[2]);
        payload.renderToBuffer(pose, buffers, RenderType.entityCutout(texture),
            BedrockModelRenderTypes.polyMeshCutout(texture), light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    /** DeepStrike's Storm Shadow with its wings and fins folded away, as carried. */
    private static void renderStormShadow(PoseStack pose, MultiBufferSource buffers, int light,
                                          StoreStations.Station station) {
        double[] at = StoreStations.hang(station, StoreStations.model(StoreStations.RACK, -1));
        pose.pushPose();
        pose.translate(at[0], at[1], at[2]);
        pose.mulPose(Axis.YP.rotationDegrees(-90));
        ModelRenderer.render(ModelAssets.missile(MissileKind.STORM_SHADOW),
                ModelAssets.texture(MissileKind.STORM_SHADOW, false), pose, buffers,
                light, OverlayTexture.NO_OVERLAY, 0, 0,
                bone -> !bone.startsWith("wing_") && !bone.startsWith("fin_"));
        pose.popPose();
    }

    /** A tablet rack holding a КАБ (GlideBombPylon): the bomb's own projectile model, heavier ones larger. */
    private static void renderKab(PoseStack pose, MultiBufferSource buffers, int light,
                                  StoreStations.Station station, int variant) {
        BakedBedrockModel baked = ProjectileModelReloadListener.INSTANCE.getModel(KAB_MODEL);
        if (baked == null) return;
        if (kabBaked != baked) {
            kabBaked = baked;
            kabInstance = baked.createInstance();
        }
        // Wings stay folded on the pylon: the first frame of the KAB's release animation
        // (animation.projectile.start), which unfolds them only after the drop.
        for (String wing : new String[]{"wing_left", "wing_right"}) {
            BoneState bone = kabInstance.getBone(wing);
            if (bone == null) continue;
            bone.reset();
            bone.rotation.rotateY((float) Math.toRadians(wing.equals("wing_left") ? -88 : 88));
        }
        float scale = 1.0F + 0.25F * Math.max(0, variant);
        double[] at = StoreStations.hang(station, StoreStations.model(StoreStations.RACK, variant));
        pose.pushPose();
        pose.translate(at[0], at[1], at[2]);
        pose.scale(scale, scale, scale);
        kabInstance.renderToBuffer(pose, buffers, RenderType.entityCutout(KAB_TEXTURE),
            BedrockModelRenderTypes.polyMeshCutout(KAB_TEXTURE), light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
}

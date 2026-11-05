package dev.frontier.industry.buildings.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import dev.frontier.industry.buildings.GunDisplayBlock;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.HashMap;
import java.util.Map;

/** Uses the installed TaCZ geometry and texture without a second homemade stand/model. */
public final class GunDisplayRenderer implements BlockEntityRenderer<GunDisplayBlock.GunDisplayEntity> {
    private static final double SURFACE_GAP = 1.0 / 512.0;
    private final Map<ResourceLocation, ItemStack> stacks = new HashMap<>();
    private final Map<ResourceLocation, Calibration> calibrations = new HashMap<>();
    private static final Map<String, PlacementSnapshot> LAST_PLACEMENTS = new HashMap<>();

    public GunDisplayRenderer(BlockEntityRendererProvider.Context context) {}

    @Override public void render(GunDisplayBlock.GunDisplayEntity display, float partial, PoseStack pose,
                                 MultiBufferSource buffer, int light, int overlay) {
        ResourceLocation id = ResourceLocation.tryParse(display.gunId());
        if (id == null) return;
        ItemStack gun = stack(id);
        if (gun.isEmpty()) return;
        var nativeDisplay = TimelessAPI.getGunDisplay(gun).orElse(null);
        if (nativeDisplay == null) return;
        BedrockGunModel model = nativeDisplay.getGunModel();
        if (model == null || nativeDisplay.getModelTexture() == null) return;

        // FIXED applies per-pack origin rotations and item-frame scale. NONE keeps
        // the original model axes, with the barrel along Z and its side on the belt.
        model.cleanAnimationTransform();
        Calibration calibration = calibrations.get(id);
        if (calibration == null || calibration.model != model) {
            calibration = new Calibration(model, measure(model));
            calibrations.put(id, calibration);
        }
        Bounds bounds = calibration.bounds;
        if (!bounds.valid()) return;
        double scale = Math.min(targetLength(id) / (bounds.maxZ - bounds.minZ),
            .8 / (bounds.maxX - bounds.minX));
        double surface = supportHeight(display);
        boolean renderHand = model.getRenderHand();
        pose.pushPose();
        try {
            pose.translate(.5, surface + SURFACE_GAP, .5);
            pose.mulPose(Axis.YP.rotationDegrees(180 - display.getBlockState()
                .getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot()));
            pose.scale((float) scale, (float) scale, (float) scale);
            pose.translate(-(bounds.minX + bounds.maxX) / 2, -bounds.minY,
                -(bounds.minZ + bounds.maxZ) / 2);
            flatModelPose(pose);
            model.setRenderHand(false);
            model.render(pose, gun, ItemDisplayContext.NONE,
                RenderType.entityCutout(nativeDisplay.getModelTexture()), light, overlay);

            // Native rendering resolves attachment/magazine visibility. Refine once
            // using those actual visible vertices, excluding hidden alternate parts.
            // A resource reload replaces the native model and starts calibration again.
            Bounds visible = calibration.bounds;
            boolean usedRefinedBounds = calibration.refined;
            if (!usedRefinedBounds) {
                visible = measure(model);
                if (visible.valid()) calibration.bounds = visible;
                calibration.refined = true;
            }
            LAST_PLACEMENTS.put(id.toString(), new PlacementSnapshot(id.toString(), surface,
                surface + SURFACE_GAP + (visible.minY - bounds.minY) * scale,
                surface + SURFACE_GAP + (visible.maxY - bounds.minY) * scale,
                (visible.maxX - visible.minX) * scale, (visible.maxZ - visible.minZ) * scale,
                usedRefinedBounds));
        } finally {
            model.setRenderHand(renderHand);
            model.cleanAnimationTransform();
            pose.popPose();
        }
    }

    private ItemStack stack(ResourceLocation id) {
        ItemStack cached = stacks.get(id);
        if (cached != null) return cached;
        if (TimelessAPI.getCommonGunIndex(id).isEmpty()) return ItemStack.EMPTY;
        ItemStack built = GunItemBuilder.create().setId(id).setAmmoCount(0).setAmmoInBarrel(false).build();
        if (!built.isEmpty()) stacks.put(id, built);
        return built;
    }

    private static double targetLength(ResourceLocation id) {
        return switch (id.getPath()) {
            case "rpg7" -> 1.2;
            case "glock_17" -> .32;
            default -> .96;
        };
    }

    private static double supportHeight(GunDisplayBlock.GunDisplayEntity display) {
        var level = display.getLevel();
        if (level == null) return 0;
        var below = display.getBlockPos().below();
        var shape = level.getBlockState(below).getCollisionShape(level, below);
        // Support is one cell below: belt top 12/16 => -.25, full table top => 0.
        return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y) - 1;
    }

    private static void flatModelPose(PoseStack pose) {
        pose.mulPose(Axis.ZP.rotationDegrees(90));
        // The same Bedrock handedness conversion used by TaCZ's own renderer.
        pose.scale(-1, -1, 1);
    }

    private static Bounds measure(BedrockGunModel model) {
        PoseStack pose = new PoseStack();
        flatModelPose(pose);
        Bounds bounds = new Bounds();
        for (BedrockPart part : model.getShouldRender()) measurePart(part, pose, bounds);
        return bounds;
    }

    private static void measurePart(BedrockPart part, PoseStack pose, Bounds bounds) {
        // Player-hand marker cubes and the reload-only magazine are never samples.
        if (!part.visible || "lefthand".equals(part.name) || "righthand".equals(part.name)
            || "additional_magazine".equals(part.name)) return;
        pose.pushPose();
        try {
            part.translateAndRotateAndScale(pose);
            part.compile(pose.last(), bounds, 0, 0, 1, 1, 1, 1);
            for (BedrockPart child : part.children) measurePart(child, pose, bounds);
        } finally {
            pose.popPose();
        }
    }

    /** Numeric acceptance data for a client probe; no screenshots are required. */
    public static Map<String, PlacementSnapshot> placementSnapshot() {
        return Map.copyOf(LAST_PLACEMENTS);
    }

    public record PlacementSnapshot(String gunId, double supportY, double minY, double maxY,
                                    double width, double length, boolean refined) {}

    private static final class Calibration {
        private final BedrockGunModel model;
        private Bounds bounds;
        private boolean refined;
        private Calibration(BedrockGunModel model, Bounds bounds) {
            this.model = model;
            this.bounds = bounds;
        }
    }

    /** Captures the transformed native cube vertices without a second draw call. */
    private static final class Bounds implements VertexConsumer {
        private double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY,
            minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY,
            maxZ = Double.NEGATIVE_INFINITY;

        private boolean valid() {
            return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                && maxX > minX && maxY > minY && maxZ > minZ;
        }

        @Override public VertexConsumer vertex(double x, double y, double z) {
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() {}
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }
}

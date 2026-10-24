package dev.frontier.industry.combat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.frontier.industry.combat.CombatContent;
import dev.frontier.industry.combat.LauncherEntity;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CombatContent.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CombatClient {
    private static final ModelLayerLocation LAUNCHER_LAYER = new ModelLayerLocation(new ResourceLocation(CombatContent.MOD_ID, "combat_launcher"), "main");
    private static final ModelLayerLocation MISSILE_LAYER = new ModelLayerLocation(new ResourceLocation(CombatContent.MOD_ID, "combat_missile"), "main");
    private static final ResourceLocation LAUNCHER_TEXTURE = new ResourceLocation(CombatContent.MOD_ID, "textures/entity/combat_launcher.png");
    private static final ResourceLocation MISSILE_TEXTURE = new ResourceLocation(CombatContent.MOD_ID, "textures/entity/combat_missile.png");
    private CombatClient() { }
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CombatContent.LAUNCHER.get(), LauncherRenderer::new);
        event.registerEntityRenderer(CombatContent.MISSILE.get(), context -> new ProjectileRenderer<>(context, false));
        event.registerEntityRenderer(CombatContent.INTERCEPTOR.get(), context -> new ProjectileRenderer<>(context, true));
    }
    @SubscribeEvent public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(LAUNCHER_LAYER, CombatClient::launcherLayer);
        event.registerLayerDefinition(MISSILE_LAYER, CombatClient::missileLayer);
    }
    private static LayerDefinition launcherLayer() {
        MeshDefinition mesh = new MeshDefinition();
        var root = mesh.getRoot();
        root.addOrReplaceChild("base", CubeListBuilder.create().texOffs(0, 0).addBox(-20, -8, -14, 40, 6, 28)
                .texOffs(0, 36).addBox(-17, -13, -12, 34, 5, 24), PartPose.ZERO);
        root.addOrReplaceChild("wheels", CubeListBuilder.create().texOffs(0, 52)
                .addBox(-22, -6, -12, 5, 6, 7).addBox(-22, -6, 5, 5, 6, 7)
                .addBox(17, -6, -12, 5, 6, 7).addBox(17, -6, 5, 5, 6, 7), PartPose.ZERO);
        root.addOrReplaceChild("tube_a", CubeListBuilder.create().texOffs(72, 0).addBox(-13, -33, -6, 7, 24, 7), PartPose.ZERO);
        root.addOrReplaceChild("tube_b", CubeListBuilder.create().texOffs(72, 0).addBox(-4, -33, -6, 7, 24, 7), PartPose.ZERO);
        root.addOrReplaceChild("tube_c", CubeListBuilder.create().texOffs(72, 0).addBox(5, -33, -6, 7, 24, 7), PartPose.ZERO);
        root.addOrReplaceChild("radar", CubeListBuilder.create().texOffs(68, 36).addBox(-1, -17, -1, 2, 17, 2)
                .texOffs(84, 36).addBox(-8, -22, -2, 16, 7, 3), PartPose.offset(11, -10, 8));
        root.addOrReplaceChild("console", CubeListBuilder.create().texOffs(0, 70).addBox(-7, -19, 5, 12, 6, 7), PartPose.ZERO);
        return LayerDefinition.create(mesh, 128, 96);
    }
    private static LayerDefinition missileLayer() {
        MeshDefinition mesh = new MeshDefinition();
        var root = mesh.getRoot();
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0).addBox(-2, -12, -2, 4, 24, 4)
                .texOffs(16, 0).addBox(-1.5F, -16, -1.5F, 3, 4, 3)
                .texOffs(0, 32).addBox(-6, 6, -0.5F, 12, 7, 1)
                .texOffs(0, 42).addBox(-0.5F, 6, -6, 1, 7, 12), PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 64);
    }
    private static final class LauncherRenderer extends EntityRenderer<LauncherEntity> {
        private final ModelPart model;
        private LauncherRenderer(EntityRendererProvider.Context context) { super(context); model = context.bakeLayer(LAUNCHER_LAYER); shadowRadius = 1.5F; }
        @Override public ResourceLocation getTextureLocation(LauncherEntity entity) { return LAUNCHER_TEXTURE; }
        @Override public void render(LauncherEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
            pose.pushPose(); pose.mulPose(Axis.YP.rotationDegrees(180 - yaw)); pose.scale(1, -1, -1);
            model.getChild("radar").visible = entity.kind() != LauncherEntity.Kind.BALLISTIC;
            model.getChild("radar").yRot = entity.active() ? (entity.tickCount + partialTick) * 0.025F : 0;
            float red = entity.kind() == LauncherEntity.Kind.PATRIOT ? 0.88F : 0.66F;
            float green = entity.kind() == LauncherEntity.Kind.S400 ? 0.88F : 0.76F;
            VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(LAUNCHER_TEXTURE));
            model.render(pose, vertices, light, OverlayTexture.NO_OVERLAY, red, green, 0.62F, 1);
            pose.popPose(); super.render(entity, yaw, partialTick, pose, buffers, light);
        }
    }
    private static final class ProjectileRenderer<T extends Entity> extends EntityRenderer<T> {
        private final ModelPart model;
        private final boolean interceptor;
        private ProjectileRenderer(EntityRendererProvider.Context context, boolean interceptor) { super(context); this.interceptor = interceptor; model = context.bakeLayer(MISSILE_LAYER); shadowRadius = 0.1F; }
        @Override public ResourceLocation getTextureLocation(T entity) { return MISSILE_TEXTURE; }
        @Override public void render(T entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
            pose.pushPose(); pose.mulPose(Axis.YP.rotationDegrees(180 - yaw)); pose.mulPose(Axis.XP.rotationDegrees(entity.getXRot() + 90));
            float scale = interceptor ? 0.5F : 0.85F; pose.scale(scale, -scale, -scale);
            model.render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(MISSILE_TEXTURE)), light, OverlayTexture.NO_OVERLAY,
                    interceptor ? 0.65F : 1, interceptor ? 0.85F : 1, 1, 1);
            pose.popPose(); super.render(entity, yaw, partialTick, pose, buffers, light);
        }
    }
}

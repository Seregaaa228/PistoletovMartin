package dev.frontier.industry.client.repair;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.frontier.industry.IndustryMod;
import dev.frontier.industry.WorkerEntity;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Client-only look of frontier_industry:worker: a vanilla villager (the game's own base skin,
 * loaded at runtime, keeps the villager head, face and nose) dressed in our construction work
 * clothes overlay (art/worker/worker_outfit.py): hard hat, shirt, gloves, hi-vis vest, tool
 * belt, trousers and boots. The held tool sits in the folded arms like a villager's item.
 */
public final class WorkerVillagerRenderer extends MobRenderer<WorkerEntity, WorkerVillagerRenderer.WorkerModel> {
    static final ResourceLocation BASE_SKIN = new ResourceLocation("textures/entity/villager/villager.png");
    static final ResourceLocation OUTFIT = new ResourceLocation(IndustryMod.ID, "textures/entity/worker_outfit.png");

    public WorkerVillagerRenderer(EntityRendererProvider.Context context) {
        super(context, new WorkerModel(context.bakeLayer(ModelLayers.VILLAGER)), .5f);
        addLayer(new ClothesLayer(this));
        addLayer(new FoldedArmsToolLayer(this, context.getItemInHandRenderer()));
    }

    @Override public ResourceLocation getTextureLocation(WorkerEntity entity) { return BASE_SKIN; }

    /** Same scale as an adult vanilla villager; the worker hitbox is already villager-sized. */
    @Override protected void scale(WorkerEntity entity, PoseStack pose, float partialTick) { pose.scale(.9375f, .9375f, .9375f); }

    /** Vanilla villager animation (head look, legs; no unhappy shake for non-villagers) plus work motion of the folded arms. */
    public static final class WorkerModel extends VillagerModel<WorkerEntity> {
        private static final EntityDataAccessor<Integer> WORK_STATE = workStateAccessor();
        private static final int REPAIR_STATE = 2;
        private final ModelPart arms;
        private final float armsRest;
        float armsLift;

        WorkerModel(ModelPart root) {
            super(root);
            arms = root.getChild("arms");
            armsRest = arms.getInitialPose().xRot;
        }

        @Override public void setupAnim(WorkerEntity entity, float limbSwing, float limbAmount, float age, float headYaw, float headPitch) {
            super.setupAnim(entity, limbSwing, limbAmount, age, headYaw, headPitch);
            float lift = 0;
            if (attackTime > 0) lift = Mth.sin(Mth.sqrt(attackTime) * Mth.PI) * .9f;          // builder's synced hand swing
            else if (repairing(entity)) lift = (Mth.sin(age * .6f) * .5f + .5f) * .55f;          // former repair animation
            armsLift = lift;
            arms.xRot = armsRest - lift;
            if (lift > 0) getHead().xRot += lift * .35f;
        }

        private static boolean repairing(WorkerEntity entity) {
            return WORK_STATE != null && entity.getEntityData().get(WORK_STATE) == REPAIR_STATE;
        }

        /** Reads the existing synced work-animation key without touching the shared entity class. */
        @SuppressWarnings("unchecked")
        private static EntityDataAccessor<Integer> workStateAccessor() {
            try {
                var field = WorkerEntity.class.getDeclaredField("WORK_ANIMATION");
                field.setAccessible(true);
                return (EntityDataAccessor<Integer>) field.get(null);
            } catch (ReflectiveOperationException | RuntimeException missing) {
                com.mojang.logging.LogUtils.getLogger().warn("Worker repair motion disabled: {}", missing.toString());
                return null;
            }
        }
    }

    /** The work clothes drawn over the base skin on the same villager model. */
    private static final class ClothesLayer extends RenderLayer<WorkerEntity, WorkerModel> {
        ClothesLayer(RenderLayerParent<WorkerEntity, WorkerModel> parent) { super(parent); }

        @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, WorkerEntity entity,
                                     float limbSwing, float limbAmount, float partialTick, float age, float headYaw, float headPitch) {
            if (entity.isInvisible()) return;
            renderColoredCutoutModel(getParentModel(), OUTFIT, pose, buffers, light, entity, 1, 1, 1);
        }
    }

    /** CrossedArmsItemLayer placement, rotated with the arms when they lift for work. */
    private static final class FoldedArmsToolLayer extends RenderLayer<WorkerEntity, WorkerModel> {
        private static final float PIVOT_Y = 3 / 16f, PIVOT_Z = -1 / 16f;
        private final ItemInHandRenderer items;

        FoldedArmsToolLayer(RenderLayerParent<WorkerEntity, WorkerModel> parent, ItemInHandRenderer items) {
            super(parent);
            this.items = items;
        }

        @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, WorkerEntity entity,
                                     float limbSwing, float limbAmount, float partialTick, float age, float headYaw, float headPitch) {
            ItemStack tool = entity.getItemBySlot(EquipmentSlot.MAINHAND);
            if (tool.isEmpty()) return;
            pose.pushPose();
            float lift = getParentModel().armsLift;
            if (lift != 0) {
                pose.translate(0, PIVOT_Y, PIVOT_Z);
                pose.mulPose(Axis.XP.rotation(-lift));
                pose.translate(0, -PIVOT_Y, -PIVOT_Z);
            }
            pose.translate(0, .4f, -.4f);
            pose.mulPose(Axis.XP.rotationDegrees(180));
            items.renderItem(entity, tool, ItemDisplayContext.GROUND, false, pose, buffers, light);
            pose.popPose();
        }
    }
}

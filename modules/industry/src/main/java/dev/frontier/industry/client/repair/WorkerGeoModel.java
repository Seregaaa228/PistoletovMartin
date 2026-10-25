package dev.frontier.industry.client.repair;

import dev.frontier.industry.IndustryMod;
import dev.frontier.industry.WorkerEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public final class WorkerGeoModel extends GeoModel<WorkerEntity> {
    @Override public ResourceLocation getModelResource(WorkerEntity entity) { return new ResourceLocation(IndustryMod.ID, "geo/worker.geo.json"); }
    @Override public ResourceLocation getTextureResource(WorkerEntity entity) { return new ResourceLocation(IndustryMod.ID, "textures/entity/worker.png"); }
    @Override public ResourceLocation getAnimationResource(WorkerEntity entity) { return new ResourceLocation(IndustryMod.ID, "animations/worker.animation.json"); }
}

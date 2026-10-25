package dev.frontier.industry.client.repair;

import dev.frontier.industry.IndustryMod;
import dev.frontier.industry.repair.PressWorkstationEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public final class PressGeoModel extends GeoModel<PressWorkstationEntity> {
    @Override public ResourceLocation getModelResource(PressWorkstationEntity entity) { return new ResourceLocation(IndustryMod.ID, "geo/press.geo.json"); }
    @Override public ResourceLocation getTextureResource(PressWorkstationEntity entity) { return new ResourceLocation(IndustryMod.ID, "textures/block/press_workstation.png"); }
    @Override public ResourceLocation getAnimationResource(PressWorkstationEntity entity) { return new ResourceLocation(IndustryMod.ID, "animations/press.animation.json"); }
}

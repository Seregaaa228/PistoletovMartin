package dev.frontier.industry.client.repair;

import dev.frontier.industry.WorkerEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public final class WorkerGeoRenderer extends GeoEntityRenderer<WorkerEntity> {
    public WorkerGeoRenderer(EntityRendererProvider.Context context) { super(context, new WorkerGeoModel()); shadowRadius = .4f; }
}

package dev.frontier.industry.client.repair;

import dev.frontier.industry.repair.PressWorkstationEntity;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public final class PressGeoRenderer extends GeoBlockRenderer<PressWorkstationEntity> {
    public PressGeoRenderer() { super(new PressGeoModel()); }
}

package dev.frontier.arcade.relay;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/**
 * The withdrawn airborne relay. The registration stays so worlds that still contain one load
 * normally; such a relay removes itself on its first server tick, without drops or a refund.
 */
public final class RelayEntity extends AddonDroneEntity {
    private static final Logger LOG = LoggerFactory.getLogger(ArcadeVehicles.ID);
    private static final ResourceLocation MODEL = new ResourceLocation("frontier_arcade_vehicles", "geo/relay_plane.geo.json");
    private static final ResourceLocation TEXTURE = new ResourceLocation("frontier_arcade_vehicles", "textures/entity/relay_plane.png");
    private static final ResourceLocation ANIMATION = new ResourceLocation("frontier_arcade_vehicles", "animations/relay_plane.animation.json");
    private static boolean logged;

    public RelayEntity(EntityType<? extends DroneEntity> type, Level level) { super(type, level); }

    @Override public void tick() {
        if (level().isClientSide) return;
        if (!logged) {
            logged = true;
            LOG.info("Removing withdrawn relay plane(s) found in the world, first at {}; nothing is refunded.", blockPosition());
        }
        discard();
    }

    @Override public ResourceLocation getModelResource() { return MODEL; }
    @Override public ResourceLocation getTextureResource() { return TEXTURE; }
    @Override public ResourceLocation getAnimationResource() { return ANIMATION; }
}

package dev.frontier.aviation.mixin;

import dev.frontier.aviation.AircraftProfile;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only DSM's fresh item placement; native SBW container restoration bypasses this item. */
@Mixin(targets = "dev.sbw.deepstrike.game.VehicleDeployItem", remap = false)
public abstract class FreshAircraftDeployment {
    @Redirect(method = "m_6225_", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;m_7967_(Lnet/minecraft/world/entity/Entity;)Z"), remap = false)
    private boolean aviation$deployFueled(Level level, Entity entity) {
        if (entity instanceof Su24mEntity plane && AircraftProfile.of(plane) != null) {
            plane.setEnergy(plane.getMaxEnergy());
        }
        return level.addFreshEntity(entity);
    }
}

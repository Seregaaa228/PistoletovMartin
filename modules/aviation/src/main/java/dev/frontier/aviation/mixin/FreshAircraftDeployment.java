package dev.frontier.aviation.mixin;

import dev.frontier.aviation.AircraftPlacement;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.control.nativebridge.NativeFlightAdapter;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Only DSM's fresh item placement; native SBW container restoration bypasses this item.
 * Aircraft stand where the player clicked (AircraftPlacement), not DSM's strict pad search.
 */
@Mixin(targets = "dev.sbw.deepstrike.game.VehicleDeployItem", remap = false)
public abstract class FreshAircraftDeployment {
    @Redirect(method = "m_6225_", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;m_7967_(Lnet/minecraft/world/entity/Entity;)Z"), remap = false)
    private boolean aviation$deployFueled(Level level, Entity entity) {
        if (entity instanceof Su24mEntity plane && AircraftProfile.of(plane) != null) {
            plane.setEnergy(plane.getMaxEnergy());
            // Standing on its wheels from the first tick: gear stance, wings level, grounded,
            // so neither the airborne native branch nor terrain compat tilts it while it settles.
            NativeFlightAdapter.deployOnGear(plane);
            plane.setOnGround(true);
        }
        return level.addFreshEntity(entity);
    }

    @Inject(method = "placement", at = @At("HEAD"), cancellable = true, remap = false)
    private static void aviation$placeAtClick(UseOnContext context, Entity entity, float yaw, CallbackInfoReturnable<Vec3> result) {
        if (entity instanceof Su24mEntity) result.setReturnValue(AircraftPlacement.find(context, entity, yaw));
    }
}

package dev.frontier.aviation.mixin;

import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Frustum and occlusion culling (vanilla and Embeddium) decide from this box whether to
 * draw an entity at all. A tablet jet's box is its 8 x 6.5 body, while the model reaches
 * ~6 blocks out along a wing and ~8 along the fuselage and turns with yaw and roll: with
 * the box behind a hill or off the screen edge the whole visible jet vanished.
 */
@Mixin(value = Entity.class, remap = false)
public abstract class AircraftRenderBounds {
    @Inject(method = "m_6921_()Lnet/minecraft/world/phys/AABB;", at = @At("HEAD"), cancellable = true, remap = false)
    private void aviation$wholeAircraft(CallbackInfoReturnable<AABB> result) {
        Object self = this;
        if (self instanceof Su24mEntity plane) result.setReturnValue(plane.getBoundingBox().inflate(4.5, 3, 4.5));
    }
}

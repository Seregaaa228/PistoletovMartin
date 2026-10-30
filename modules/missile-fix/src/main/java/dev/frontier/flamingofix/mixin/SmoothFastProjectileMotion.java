package dev.frontier.flamingofix.mixin;

import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SBW fast projectiles (cannon shells, grenades, small rockets) advance themselves with setPos every
 * client tick, but are also tracked with updateInterval=1. Base Entity.lerpTo is an immediate setPos,
 * so each late server move packet snapped the shell back along its path. Ignore those packets for
 * these projectiles only; spawn position, velocity sync and removal still come from the server.
 */
@Mixin(value = Entity.class, remap = false)
public abstract class SmoothFastProjectileMotion {
    // Production runs SRG names without a refmap, so the SRG name is required; lerpTo covers dev runs.
    @Inject(method = {"m_6453_(DDDFFIZ)V", "lerpTo(DDDFFIZ)V"},
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void flamingo$keepPredictedFastProjectile(double x, double y, double z, float yRot, float xRot,
            int steps, boolean teleport, CallbackInfo ci) {
        if ((Object)this instanceof FastThrowableProjectile) ci.cancel();
    }
}

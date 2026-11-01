package dev.frontier.flamingofix.mixin;

import com.atsuishio.superbwarfare.entity.projectile.BounceProjectile;
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SBW's fast projectiles advance themselves with setPos every client tick (FastThrowableProjectile:
 * cannon shells, grenades, rockets, missiles; the separate ProjectileEntity: bullets), but are also
 * tracked with updateInterval=1. Base Entity.lerpTo is an immediate setPos, so each late server move
 * packet snapped the shot back along its path. Ignore those packets for these projectiles only;
 * spawn position, velocity sync and removal still come from the server.
 */
@Mixin(value = Entity.class, remap = false)
public abstract class SmoothFastProjectileMotion {
    // Production runs SRG names without a refmap, so the SRG name is required; lerpTo covers dev runs.
    @Inject(method = {"m_6453_(DDDFFIZ)V", "lerpTo(DDDFFIZ)V"},
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void flamingo$keepPredictedFastProjectile(double x, double y, double z, float yRot, float xRot,
            int steps, boolean teleport, CallbackInfo ci) {
        Object self = this;
        if (self instanceof BounceProjectile grenade) {
            // Grenades bounce only on the server (hit detection is server-side); the client just collides,
            // so each bounce leaves an offset. Take the server's onGround teleports and, once slow, its moves.
            if (!teleport && grenade.getDeltaMovement().lengthSqr() >= SLOW_SQR) ci.cancel();
        } else if (self instanceof FastThrowableProjectile || self instanceof ProjectileEntity) ci.cancel();
    }

    /** Below 0.05 blocks/tick a late packet is off by a negligible distance. */
    private static final double SLOW_SQR = 0.05 * 0.05;
}

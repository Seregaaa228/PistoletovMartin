package dev.frontier.ifv.mixin.client;

import com.atsuishio.superbwarfare.entity.vehicle.TurretWreckEntity;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TurretWreckEntity.class, remap = false)
public abstract class TurretWreckParticleBudgetMixin {
    @Inject(method = "addRandomParticle", at = @At("HEAD"), cancellable = true, remap = false)
    private void frontierStyle$turretParticles(ParticleOptions particle, Vec3 position, float spread,
            Level level, float probability, int count, CallbackInfo callback) {
        if (level.isClientSide) {
            // The parent vehicle supplies timed smoke. Native turret state has
            // no age, so it cannot distinguish a fresh turret from a reloaded
            // one; never replay a second destruction effect on tracking.
            callback.cancel();
        }
    }
}

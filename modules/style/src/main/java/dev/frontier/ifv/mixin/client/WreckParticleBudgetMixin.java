package dev.frontier.ifv.mixin.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleEffectUtils;
import dev.frontier.ifv.client.WreckEffects;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Guard cosmetic helpers, not lowHealthWarning which also handles server mechanics. */
@Mixin(value = VehicleEffectUtils.class, remap = false)
public abstract class WreckParticleBudgetMixin {
    @Redirect(method = {"addRandomParticle(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/world/phys/Vec3;FLnet/minecraft/world/level/Level;FI)V",
            "addRandomParticle(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/world/phys/Vec3;FLnet/minecraft/world/level/Level;ILnet/minecraft/world/phys/Vec3;)V"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;m_6485_(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V"), remap = false)
    private static void frontierStyle$shortDamagedCloud(Level level, ParticleOptions particle, boolean visible,
            double x, double y, double z, double vx, double vy, double vz) {
        WreckEffects.nativeParticle(level, particle, visible, x, y, z, vx, vy, vz);
    }

    @Inject(method = "addRandomParticle(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/world/phys/Vec3;FLnet/minecraft/world/level/Level;FI)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void frontierStyle$wreck(VehicleEntity vehicle, ParticleOptions particle, Vec3 position,
            float spread, Level level, float probability, int count, CallbackInfo callback) {
        if (level.isClientSide && vehicle.isWreck()) {
            WreckEffects.particles(vehicle);
            callback.cancel();
        }
    }

    @Inject(method = "addRandomParticle(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/world/phys/Vec3;FLnet/minecraft/world/level/Level;ILnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void frontierStyle$movingWreck(VehicleEntity vehicle, ParticleOptions particle, Vec3 position,
            float spread, Level level, int count, Vec3 velocity, CallbackInfo callback) {
        if (level.isClientSide && vehicle.isWreck()) {
            WreckEffects.particles(vehicle);
            callback.cancel();
        }
    }
}

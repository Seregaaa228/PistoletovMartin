package dev.frontier.aviation.systemstest.mixin;

import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import dev.frontier.aviation.systemstest.ImpactObservations;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MissileProjectile.class, remap = false)
public abstract class MissileImpactProbe {
    @Inject(method = "afterHitBlock", at = @At("HEAD"), require = 1)
    private void systems$missileImpact(BlockHitResult hit, CallbackInfo ci) {
        ImpactObservations.impact((MissileProjectile) (Object) this, hit);
    }
}
